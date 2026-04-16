package com.example.neareststationnotifier

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
    private val inferredTrainMoveM: Double = 120.0,
    private val lineLockWarmupMs: Long = 20_000L,
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
        val trainStartedAtMs: Long = 0L,
        val lockedCandidateLine: String? = null,
        val lockedCandidateCount: Int = 0
    )

    data class Result(
        val currentName: String?,
        val nextName: String?,
        val state: State,
        val debugText: String = ""
    )

    private fun coordDistM(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val r = 6371000.0
        val lat1 = Math.toRadians(a.first)
        val lon1 = Math.toRadians(a.second)
        val lat2 = Math.toRadians(b.first)
        val lon2 = Math.toRadians(b.second)

        val dLat = lat2 - lat1
        val dLon = lon2 - lon1

        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)

        val c = 2 * atan2(sqrt(h), sqrt(1 - h))
        return r * c
    }

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

        val wasTrainMode = nowMs < state.trainHoldUntilMs

        val movedDistM = prevLatLon?.let { coordDistM(it, curLatLon) } ?: 0.0
        val speedTrain = (speedMps ?: 0.0) >= trainSpeedThreshMps
        val inferredTrain = (speedMps == null) && (movedDistM >= inferredTrainMoveM)

        val holdUntil = if (speedTrain || inferredTrain) nowMs + trainHoldMs else state.trainHoldUntilMs
        val trainMode = speedTrain || inferredTrain || (nowMs < holdUntil)

        val trainStartedAtMs = when {
            !wasTrainMode && trainMode -> nowMs
            trainMode && state.trainStartedAtMs > 0L -> state.trainStartedAtMs
            trainMode -> nowMs
            else -> 0L
        }

        val nearest = candidates.minByOrNull { GeoLineUtils.distM(curLatLon, it) }!!
        val nearestDist = GeoLineUtils.distM(curLatLon, nearest)

        val currentDist = state.currentName?.let { curName ->
            candidates.filter { it.name == curName }
                .minOfOrNull { GeoLineUtils.distM(curLatLon, it) }
        } ?: Double.POSITIVE_INFINITY

        val fwdBearing = when {
            bearingDeg != null && !bearingDeg.isNaN() -> bearingDeg
            prevLatLon != null -> GeoLineUtils.bearingFrom(prevLatLon, curLatLon)
            else -> null
        }

        fun stationRecords(name: String): List<StationCandidate> =
            candidates.filter {
                GeoLineUtils.normalizeStationName(it.name) == GeoLineUtils.normalizeStationName(name)
            }

        fun linesForStationName(name: String): Set<String> =
            stationRecords(name).asSequence()
                .map { GeoLineUtils.normalizeLine(it.line) }
                .filter { it.isNotBlank() }
                .toSet()

        fun stationHasLine(name: String, line: String?): Boolean {
            val nl = line?.let { GeoLineUtils.normalizeLine(it) }?.takeIf { it.isNotBlank() } ?: return false
            return stationRecords(name).any { GeoLineUtils.normalizeLine(it.line) == nl }
        }

        fun choosePrimaryLineForStationName(
            name: String,
            preferredLockedLine: String?,
            preferredPrimaryLine: String?,
            preferredLines: Set<String>
        ): String? {
            val records = stationRecords(name)
            if (records.isEmpty()) return null

            val normalizedLocked = preferredLockedLine
                ?.let { GeoLineUtils.normalizeLine(it) }
                ?.takeIf { it.isNotBlank() }

            val normalizedPrimary = preferredPrimaryLine
                ?.let { GeoLineUtils.normalizeLine(it) }
                ?.takeIf { it.isNotBlank() }

            val normalizedPreferredLines = preferredLines
                .map { GeoLineUtils.normalizeLine(it) }
                .filter { it.isNotBlank() }
                .toSet()

            if (normalizedLocked != null) {
                val hit = records.firstOrNull {
                    GeoLineUtils.normalizeLine(it.line) == normalizedLocked
                }
                if (hit != null) return normalizedLocked
            }

            if (normalizedPrimary != null) {
                val hit = records.firstOrNull {
                    GeoLineUtils.normalizeLine(it.line) == normalizedPrimary
                }
                if (hit != null) return normalizedPrimary
            }

            if (normalizedPreferredLines.isNotEmpty()) {
                val hit = records.firstOrNull {
                    GeoLineUtils.normalizeLine(it.line) in normalizedPreferredLines
                }
                if (hit != null) return GeoLineUtils.normalizeLine(hit.line)
            }

            return records
                .minByOrNull { GeoLineUtils.distM(curLatLon, it) }
                ?.line
                ?.let { GeoLineUtils.normalizeLine(it) }
                ?.takeIf { it.isNotBlank() }
        }

        fun normalizeAdjName(raw: String?): String? {
            val v = raw?.trim()?.takeIf { it.isNotEmpty() && it != "-" } ?: return null
            return GeoLineUtils.normalizeStationName(v)
        }

        fun isNaturalTrainSwitch(
            fromName: String?,
            toName: String,
            line: String?
        ): Boolean {
            if (fromName.isNullOrBlank()) return true

            val fromNorm = GeoLineUtils.normalizeStationName(fromName)
            val toNorm = GeoLineUtils.normalizeStationName(toName)
            if (fromNorm == toNorm) return true

            val normalizedLine = line
                ?.let { GeoLineUtils.normalizeLine(it) }
                ?.takeIf { it.isNotBlank() }
                ?: return false

            val fromRecords = stationRecords(fromName)
                .filter { GeoLineUtils.normalizeLine(it.line) == normalizedLine }

            val toRecords = stationRecords(toName)
                .filter { GeoLineUtils.normalizeLine(it.line) == normalizedLine }

            if (fromRecords.isEmpty() || toRecords.isEmpty()) return false

            return fromRecords.any { from ->
                val nextNorm = normalizeAdjName(from.next)
                val prevNorm = normalizeAdjName(from.prev)
                toNorm == nextNorm || toNorm == prevNorm
            }
        }

        var newState = state.copy(
            trainHoldUntilMs = holdUntil,
            trainStartedAtMs = trainStartedAtMs
        )
        var decision = "keep"
        var pend = 0
        var lockedPend = 0
        var lineMatched = false
        var forceReline = false
        var adjacencyOk = true

        if (state.currentName == null) {
            if (nearestDist <= enterRadiusM) {
                val nm = nearest.name
                val pl = choosePrimaryLineForStationName(
                    name = nm,
                    preferredLockedLine = state.lockedLine,
                    preferredPrimaryLine = state.primaryLine,
                    preferredLines = state.currentLines
                )
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
                val effectiveLineBeforeSwitch = state.lockedLine ?: state.primaryLine

                lineMatched =
                    stationHasLine(nearest.name, effectiveLineBeforeSwitch) ||
                        linesForStationName(nearest.name).intersect(state.currentLines).isNotEmpty()

                forceReline =
                    trainMode && (
                        !currentDist.isFinite() ||
                            (currentDist >= 350.0 && nearestDist <= 180.0)
                        )

                adjacencyOk = if (trainMode) {
                    val fromName = state.lastName ?: state.currentName
                    isNaturalTrainSwitch(
                        fromName = fromName,
                        toName = nearest.name,
                        line = effectiveLineBeforeSwitch
                    ) || isNaturalTrainSwitch(
                        fromName = state.currentName,
                        toName = nearest.name,
                        line = effectiveLineBeforeSwitch
                    )
                } else {
                    true
                }

                val switchLineOk = if (trainMode) {
                    lineMatched || forceReline
                } else {
                    true
                }

                val needSwitch =
                    switchLineOk &&
                        adjacencyOk &&
                        (GeoLineUtils.normalizeStationName(nearest.name) !=
                            GeoLineUtils.normalizeStationName(state.currentName)) &&
                        (nearestDist + switchMarginM < currentDist)

                if (needSwitch) {
                    val same = state.pendingSwitchName?.let {
                        GeoLineUtils.normalizeStationName(it) ==
                            GeoLineUtils.normalizeStationName(nearest.name)
                    } ?: false

                    val nextCount = if (same) state.pendingCount + 1 else 1
                    pend = nextCount
                    val confirmTimes = if (trainMode) 1 else 2

                    if (nextCount >= confirmTimes) {
                        val old = state.currentName
                        val nm = nearest.name
                        val pl = choosePrimaryLineForStationName(
                            name = nm,
                            preferredLockedLine = state.lockedLine,
                            preferredPrimaryLine = state.primaryLine,
                            preferredLines = state.currentLines
                        )
                        newState = newState.copy(
                            currentName = nm,
                            primaryLine = pl,
                            currentLines = linesForStationName(nm),
                            lastName = old,
                            pendingSwitchName = null,
                            pendingCount = 0
                        )
                        decision = if (lineMatched) "switch_confirmed" else "switch_reline"
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
                    decision = when {
                        trainMode && !adjacencyOk -> "keep_adj_guard"
                        trainMode && !lineMatched && forceReline -> "keep_reline_wait"
                        trainMode && !lineMatched -> "keep_line_guard"
                        else -> "keep_reset"
                    }
                }
            }
        }

        val lockWarmupDone =
            trainMode &&
                newState.trainStartedAtMs > 0L &&
                (nowMs - newState.trainStartedAtMs >= lineLockWarmupMs)

        if (lockWarmupDone) {
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
        } else {
            if (!trainMode) {
                newState = newState.copy(
                    lockedLine = null,
                    lockedCandidateLine = null,
                    lockedCandidateCount = 0
                )
            } else {
                newState = newState.copy(
                    lockedCandidateLine = null,
                    lockedCandidateCount = 0
                )
            }
        }

        val effectiveLine = newState.lockedLine ?: newState.primaryLine

        val nextByAdj = if (trainMode) {
            NextStationSelection.pickNextByAdjacency(
                curLatLon = curLatLon,
                candidates = candidates,
                currentName = newState.currentName,
                currentLine = effectiveLine,
                fwdBearing = fwdBearing,
                lastName = newState.lastName
            )
        } else {
            null
        }

        val nextName = nextByAdj ?: NextStationSelection.pickNextForward(
            curLatLon = curLatLon,
            candidates = candidates,
            currentName = newState.currentName,
            currentLine = effectiveLine,
            currentLines = newState.currentLines,
            lastName = newState.lastName,
            fwdBearing = fwdBearing,
            trainMode = trainMode,
            wDir = wDir,
            wDist = wDist,
            otherLinePenaltySlow = otherLinePenaltySlow,
            otherLinePenaltyTrain = otherLinePenaltyTrain,
            backwardPenaltyTrain = backwardPenaltyTrain
        )

        val dbg = buildString {
            append("dbg currentLine=").append(effectiveLine ?: "--")
            append(" locked=").append(newState.lockedLine ?: "--")
            append(" primary=").append(newState.primaryLine ?: "--")
            append(" lines=").append(
                if (newState.currentLines.isEmpty()) "--"
                else newState.currentLines.joinToString("|")
            )
            append(" last=").append(newState.lastName ?: "--")
            append(" train=").append(trainMode)
            append(" warm=").append(lockWarmupDone)
            append(" twait=").append(
                if (trainMode && newState.trainStartedAtMs > 0L) {
                    max(0L, lineLockWarmupMs - (nowMs - newState.trainStartedAtMs)) / 1000
                } else 0L
            ).append("s")
            append(" hold=").append(max(0L, newState.trainHoldUntilMs - nowMs) / 1000).append("s")
            append(" nearest=").append(nearest.name).append("@").append(nearest.line)
            append(" nd=").append(nearestDist.toInt()).append("m")
            append(" cd=").append(if (currentDist.isFinite()) currentDist.toInt() else -1).append("m")
            append(" moved=").append(movedDistM.toInt()).append("m")
            append(" pend=").append(pend)
            append(" lpend=").append(lockedPend)
            append(" lcan=").append(newState.lockedCandidateLine ?: "--")
            append(" lmatch=").append(lineMatched)
            append(" freline=").append(forceReline)
            append(" adjok=").append(adjacencyOk)
            append(" dec=").append(decision)
            append(" adj=").append(nextByAdj ?: "--")
            if (fwdBearing != null) append(" br=").append("%.1f".format(fwdBearing))
            if (speedMps != null) append(" sp=").append("%.1f".format(speedMps))
            append(" inf=").append(inferredTrain)
            if (accuracyM != null) append(" acc=").append("%.0f".format(accuracyM))
        }

        return Result(
            currentName = newState.currentName,
            nextName = nextName,
            state = newState,
            debugText = dbg
        )
    }
}
