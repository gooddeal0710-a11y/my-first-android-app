package com.example.neareststationnotifier

import kotlin.math.max

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

        fun linesForStationName(name: String): Set<String> =
            candidates.asSequence()
                .filter { it.name == name }
                .map { GeoLineUtils.normalizeLine(it.line) }
                .filter { it.isNotBlank() }
                .toSet()

        fun primaryLineForStationName(name: String): String? {
            val best = candidates
                .filter { it.name == name }
                .minByOrNull { GeoLineUtils.distM(curLatLon, it) }
            return best?.line?.let { GeoLineUtils.normalizeLine(it) }?.takeIf { it.isNotBlank() }
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
            NextStationSelection.pickNextByAdjacency(
                curLatLon = curLatLon,
                candidates = candidates,
                currentName = newState.currentName,
                currentLine = effectiveLine,
                fwdBearing = fwdBearing,
                lastName = newState.lastName
            )
        } else null

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
}
