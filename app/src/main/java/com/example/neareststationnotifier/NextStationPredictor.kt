package com.example.neareststationnotifier

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class NextStationPredictor(
    private val enterRadiusM: Double = 120.0,
    private val exitRadiusM: Double = 180.0,
    private val switchMarginM: Double = 80.0,
    private val trainSpeedThreshMps: Double = 5.0,
    private val trainHoldMs: Long = 90_000L,
    private val wDir: Double = 0.60,
    private val wDist: Double = 0.40,
    private val otherLinePenaltySlow: Double = 0.25,
    private val otherLinePenaltyTrain: Double = 0.85,
    private val backwardPenaltyTrain: Double = 0.60
) {
    private val lineLockResolver = LineLockResolver(confirmTimes = 3)

    data class State(
        val currentName: String? = null,
        val primaryLine: String? = null,
        val lockedLine: String? = null,
        val currentLines: Set<String> = emptySet(),
        val lastName: String? = null,
        val pendingSwitchName: String? = null,
        val pendingCount: Int = 0,
        val trainHoldUntilMs: Long = 0L,
        val lockedCandidateLine: String? = null,
        val lockedCandidateCount: Int = 0
    )

    data class Result(
        val currentName: String?,
        val nextName: String?,
        val state: State,
        val debugText: String = ""
    )

    fun predict(
        prevLatLon: Pair<Double, Double>?,
        curLatLon: Pair<Double, Double>,
        candidates: List<StationCandidate>,
        state: State,
        speedMps: Double? = null,
        bearingDeg: Double? = null,
        accuracyM: Double? = null
    ): Result {
        if (candidates.isEmpty()) {
            return Result(state.currentName, null, state, "dbg: no candidates")
        }

        val nowMs = System.currentTimeMillis()
        val speedTrain = (speedMps ?: 0.0) >= trainSpeedThreshMps
        val holdUntil = if (speedTrain) nowMs + trainHoldMs else state.trainHoldUntilMs
        val trainMode = speedTrain || (nowMs < holdUntil)

        val nearest = candidates.minByOrNull { distM(curLatLon, it) }!!
        val nearestDist = distM(curLatLon, nearest)

        val currentDist = state.currentName?.let { curName ->
            candidates.filter { it.name == curName }
                .minOfOrNull { distM(curLatLon, it) }
        } ?: Double.POSITIVE_INFINITY

        val fwdBearing = when {
            bearingDeg != null && !bearingDeg.isNaN() -> bearingDeg
            prevLatLon != null -> bearingFrom(prevLatLon, curLatLon)
            else -> null
        }

        fun linesForStationName(name: String): Set<String> =
            candidates.asSequence()
                .filter { it.name == name }
                .map { normalizeLine(it.line) }
                .filter { it.isNotBlank() }
                .toSet()

        fun primaryLineForStationName(name: String): String? {
            val best = candidates
                .filter { it.name == name }
                .minByOrNull { distM(curLatLon, it) }
            return best?.line?.let { normalizeLine(it) }?.takeIf { it.isNotBlank() }
        }

        var newState = state.copy(trainHoldUntilMs = holdUntil)
        var decision = "keep"
        var pend = 0
        var lockedPend = 0

        if (state.currentName == null) {
            if (nearestDist <= enterRadiusM) {
                val nm = nearest.name
                val pl = primaryLineForStationName(nm)
                newState = newState.copy(
                    currentName = nm,
                    primaryLine = pl,
                    currentLines = linesForStationName(nm),
                    lastName = null,
                    pendingSwitchName = null,
                    pendingCount = 0
                )
                decision = "set_current_enter"
            } else {
                newState = newState.copy(
                    pendingSwitchName = nearest.name,
                    pendingCount = 1
                )
                decision = "pending_init"
            }
        } else {
            if (currentDist <= exitRadiusM) {
                newState = newState.copy(
                    pendingSwitchName = null,
                    pendingCount = 0
                )
                decision = "keep_hysteresis"
            } else {
                val needSwitch = (nearest.name != state.currentName) && (nearestDist + switchMarginM < currentDist)
                if (needSwitch) {
                    val same = (state.pendingSwitchName == nearest.name)
                    val nextCount = if (same) state.pendingCount + 1 else 1
                    pend = nextCount
                    val confirmTimes = if (trainMode) 1 else 2

                    if (nextCount >= confirmTimes) {
                        val old = state.currentName
                        val nm = nearest.name
                        val pl = primaryLineForStationName(nm)
                        newState = newState.copy(
                            currentName = nm,
                            primaryLine = pl,
                            currentLines = linesForStationName(nm),
                            lastName = old,
                            pendingSwitchName = null,
                            pendingCount = 0
                        )
                        decision = "switch_confirmed"
                    } else {
                        newState = newState.copy(
                            pendingSwitchName = nearest.name,
                            pendingCount = nextCount
                        )
                        decision = "switch_pending"
                    }
                } else {
                    newState = newState.copy(
                        pendingSwitchName = null,
                        pendingCount = 0
                    )
                    decision = "keep_reset"
                }
            }
        }

        val lockResult = lineLockResolver.resolve(
            LineLockResolver.Input(
                trainMode = trainMode,
                primaryLine = newState.primaryLine,
                lockedLine = newState.lockedLine,
                lockedCandidateLine = newState.lockedCandidateLine,
                lockedCandidateCount = newState.lockedCandidateCount
            )
        )

        lockedPend = lockResult.lockedPend

        newState = newState.copy(
            lockedLine = lockResult.lockedLine,
            lockedCandidateLine = lockResult.lockedCandidateLine,
            lockedCandidateCount = lockResult.lockedCandidateCount
        )

        val effectiveLine = newState.lockedLine ?: newState.primaryLine

        val nextByAdj = if (trainMode) {
            pickNextByAdjacency(
                curLatLon = curLatLon,
                candidates = candidates,
                currentName = newState.currentName,
                currentLine = effectiveLine,
                fwdBearing = fwdBearing,
                lastName = newState.lastName
            )
        } else null

        val nextName = nextByAdj ?: pickNextForward(
            curLatLon = curLatLon,
            candidates = candidates,
            currentName = newState.currentName,
            currentLine = effectiveLine,
            currentLines = newState.currentLines,
            lastName = newState.lastName,
            fwdBearing = fwdBearing,
            trainMode = trainMode
        )

        val dbg = buildString {
            append("dbg currentLine=").append(effectiveLine ?: "--")
            append(" locked=").append(newState.lockedLine ?: "--")
            append(" primary=").append(newState.primaryLine ?: "--")
            append(" lines=").append(if (newState.currentLines.isEmpty()) "--" else newState.currentLines.joinToString("|"))
            append(" last=").append(newState.lastName ?: "--")
            append(" train=").append(trainMode)
            append(" hold=").append(max(0L, newState.trainHoldUntilMs - nowMs) / 1000).append("s")
            append(" nearest=").append(nearest.name).append("@").append(nearest.line)
            append(" nd=").append(nearestDist.toInt()).append("m")
            append(" cd=").append(if (currentDist.isFinite()) currentDist.toInt() else -1).append("m")
            append(" pend=").append(pend)
            append(" lpend=").append(lockedPend)
            append(" lcan=").append(newState.lockedCandidateLine ?: "--")
            append(" dec=").append(decision)
            append(" adj=").append(nextByAdj ?: "--")
            if (fwdBearing != null) append(" br=").append("%.1f".format(fwdBearing))
            if (speedMps != null) append(" sp=").append("%.1f".format(speedMps))
            if (accuracyM != null) append(" acc=").append("%.0f".format(accuracyM))
        }

        return Result(newState.currentName, nextName, newState, dbg)
    }

    private fun pickNextByAdjacency(
        curLatLon: Pair<Double, Double>,
        candidates: List<StationCandidate>,
        currentName: String?,
        currentLine: String?,
        fwdBearing: Double?,
        lastName: String?
    ): String? {
        if (currentName.isNullOrBlank()) return null

        val normLine = currentLine?.let { normalizeLine(it) }?.takeIf { it.isNotBlank() }

        val currentRecords = candidates.filter { it.name == currentName }
        val currentRec = when {
            currentRecords.isEmpty() -> null
            normLine == null -> currentRecords.minByOrNull { distM(curLatLon, it) }
            else -> currentRecords
                .filter { normalizeLine(it.line) == normLine }
                .minByOrNull { distM(curLatLon, it) }
                ?: currentRecords.minByOrNull { distM(curLatLon, it) }
        } ?: return null

        val a = currentRec.next.takeIf { it.isNotBlank() }
        val b = currentRec.prev.takeIf { it.isNotBlank() }

        val options = listOfNotNull(a, b)
            .distinct()
            .filter { it != currentName && it != lastName }

        if (options.isEmpty()) return null

        val optionCandidates = options.mapNotNull { name ->
            candidates.filter { it.name == name }
                .minByOrNull { distM(curLatLon, it) }
        }
        if (optionCandidates.isEmpty()) return null

        return if (fwdBearing != null) {
            optionCandidates.maxByOrNull { c ->
                val toBr = bearingFrom(curLatLon, Pair(c.lat ?: return@maxByOrNull -1e9, c.lon ?: return@maxByOrNull -1e9))
                val diff = angleDiffDeg(fwdBearing, toBr)
                cos(Math.toRadians(diff))
            }?.name
        } else {
            optionCandidates.minByOrNull { distM(curLatLon, it) }?.name
        }
    }

    private fun pickNextForward(
        curLatLon: Pair<Double, Double>,
        candidates: List<StationCandidate>,
        currentName: String?,
        currentLine: String?,
        currentLines: Set<String>,
        lastName: String?,
        fwdBearing: Double?,
        trainMode: Boolean
    ): String? {
        val basePool = candidates.filter { it.name != currentName && it.name != lastName }
        if (basePool.isEmpty()) return null

        val normCurrent = currentLine?.let { normalizeLine(it) }?.takeIf { it.isNotBlank() }
        val normLines = currentLines.map { normalizeLine(it) }.filter { it.isNotBlank() }.toSet()

        val sameLinePool = if (normCurrent != null) {
            basePool.filter { normalizeLine(it.line) == normCurrent }
        } else if (normLines.isNotEmpty()) {
            basePool.filter { normalizeLine(it.line) in normLines }
        } else emptyList()

        val pool = if (sameLinePool.isNotEmpty()) sameLinePool else basePool

        val dists = pool.map { distM(curLatLon, it) }.filter { it.isFinite() }
        val maxDist = dists.maxOrNull() ?: 1.0

        var best: StationCandidate? = null
        var bestScore = Double.NEGATIVE_INFINITY

        for (c in pool) {
            val d = distM(curLatLon, c)
            val distScore = if (d.isFinite()) 1.0 - (d / maxDist).coerceIn(0.0, 1.0) else 0.0

            val dirScore = if (fwdBearing != null && c.lat != null && c.lon != null) {
                val toBr = bearingFrom(curLatLon, Pair(c.lat, c.lon))
                val diff = angleDiffDeg(fwdBearing, toBr)
                cos(Math.toRadians(diff))
            } else 0.0

            var score = wDir * dirScore + wDist * distScore

            if (sameLinePool.isEmpty()) {
                if (normCurrent != null) {
                    val same = normalizeLine(c.line) == normCurrent
                    if (!same) score -= if (trainMode) otherLinePenaltyTrain else otherLinePenaltySlow
                } else if (normLines.isNotEmpty()) {
                    val same = normalizeLine(c.line) in normLines
                    if (!same) score -= if (trainMode) otherLinePenaltyTrain else otherLinePenaltySlow
                }
            }

            if (trainMode && dirScore < 0.0) score -= backwardPenaltyTrain

            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        return best?.name
    }

    private fun distM(cur: Pair<Double, Double>, c: StationCandidate): Double {
        val lat = c.lat ?: return Double.POSITIVE_INFINITY
        val lon = c.lon ?: return Double.POSITIVE_INFINITY
        val dLat = (lat - cur.first) * 111_320.0
        val dLon = (lon - cur.second) * 111_320.0 * cos(Math.toRadians(cur.first))
        return sqrt(dLat * dLat + dLon * dLon)
    }

    private fun bearingFrom(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val lat1 = Math.toRadians(a.first)
        val lat2 = Math.toRadians(b.first)
        val dLon = Math.toRadians(b.second - a.second)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        var br = Math.toDegrees(atan2(y, x))
        if (br < 0) br += 360.0
        return br
    }

    private fun angleDiffDeg(a: Double, b: Double): Double {
        val d = (a - b + 540.0) % 360.0 - 180.0
        return abs(d)
    }

    private fun normalizeLine(s: String): String =
        s.lowercase()
            .replace("ＪＲ", "jr")
            .replace("（", "(").replace("）", ")")
            .replace(" ", "")
            .trim()
}
