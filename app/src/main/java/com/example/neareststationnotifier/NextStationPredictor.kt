package com.example.neareststationnotifier

import kotlin.math.max

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

        val movedDistM = prevLatLon?.let { nextStationCoordDistM(it, curLatLon) } ?: 0.0
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
            val curNorm = GeoLineUtils.normalizeStationName(curName)
            candidates
                .filter { GeoLineUtils.normalizeStationName(it.name) == curNorm }
                .minOfOrNull { GeoLineUtils.distM(curLatLon, it) }
        } ?: Double.POSITIVE_INFINITY

        val fwdBearing = when {
            bearingDeg != null && !bearingDeg.isNaN() -> bearingDeg
            prevLatLon != null -> GeoLineUtils.bearingFrom(prevLatLon, curLatLon)
            else -> null
        }

        val support = NextStationPredictorSupport(
            candidates = candidates,
            curLatLon = curLatLon,
            trainMode = trainMode
        )

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
        var relined = false

        if (state.currentName == null) {
            if (nearestDist <= enterRadiusM) {
                val nm = nearest.name
                val pl = support.choosePrimaryLineForStationName(
                    name = nm,
                    preferredLockedLine = state.lockedLine,
                    preferredPrimaryLine = state.primaryLine,
                    preferredLines = state.currentLines,
                    moveBearing = fwdBearing,
                    trainModeNow = trainMode
                )
                newState = newState.copy(
                    currentName = nm,
                    primaryLine = pl,
                    currentLines = support.linesForStationName(nm),
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
            val currentName: String = requireNotNull(state.currentName)

            if (currentDist <= exitRadiusM) {
                newState = newState.copy(
                    pendingSwitchName = null,
                    pendingCount = 0
                )
                decision = "keep_hysteresis"
            } else {
                var effectiveLineBeforeSwitch = state.lockedLine ?: state.primaryLine

                lineMatched =
                    support.stationHasLine(nearest.name, effectiveLineBeforeSwitch) ||
                        support.linesForStationName(nearest.name).intersect(state.currentLines).isNotEmpty()

                forceReline = trainMode && (
                    !currentDist.isFinite() ||
                        (currentDist >= 350.0 && nearestDist <= 180.0)
                    )

                adjacencyOk = if (trainMode) {
                    val fromName: String = state.lastName ?: currentName
                    val candidateLinesForAdj = listOfNotNull(
                        state.lockedLine,
                        state.primaryLine,
                        nearest.line
                    ).map { GeoLineUtils.normalizeLine(it) }
                        .filter { it.isNotBlank() }
                        .distinct()

                    candidateLinesForAdj.any { adjLine ->
                        support.isNaturalTrainSwitch(fromName, nearest.name, adjLine) ||
                            support.isNaturalTrainSwitch(currentName, nearest.name, adjLine)
                    }
                } else {
                    true
                }

                if (trainMode && forceReline && !adjacencyOk) {
                    val relinedLine = support.chooseRelineForCurrentStation(
                        currentName = currentName,
                        moveBearing = fwdBearing
                    )

                    if (!relinedLine.isNullOrBlank() &&
                        GeoLineUtils.normalizeLine(relinedLine) !=
                        GeoLineUtils.normalizeLine(state.primaryLine)
                    ) {
                        newState = newState.copy(
                            primaryLine = relinedLine,
                            lockedLine = null,
                            lockedCandidateLine = null,
                            lockedCandidateCount = 0
                        )
                        relined = true
                        effectiveLineBeforeSwitch = relinedLine

                        lineMatched =
                            support.stationHasLine(nearest.name, effectiveLineBeforeSwitch) ||
                                support.linesForStationName(nearest.name).intersect(newState.currentLines).isNotEmpty()

                        adjacencyOk = if (trainMode) {
                            val fromName: String = state.lastName ?: currentName
                            val candidateLinesForAdj = listOfNotNull(
                                newState.lockedLine,
                                newState.primaryLine,
                                nearest.line
                            ).map { GeoLineUtils.normalizeLine(it) }
                                .filter { it.isNotBlank() }
                                .distinct()

                            candidateLinesForAdj.any { adjLine ->
                                support.isNaturalTrainSwitch(fromName, nearest.name, adjLine) ||
                                    support.isNaturalTrainSwitch(currentName, nearest.name, adjLine)
                            }
                        } else {
                            true
                        }
                    }
                }

                val needSwitch =
                    (if (trainMode) lineMatched || forceReline || relined else true) &&
                        adjacencyOk &&
                        (GeoLineUtils.normalizeStationName(nearest.name) !=
                            GeoLineUtils.normalizeStationName(currentName)) &&
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
                        val old: String = currentName
                        val nm = nearest.name
                        val pl = support.choosePrimaryLineForStationName(
                            name = nm,
                            preferredLockedLine = newState.lockedLine,
                            preferredPrimaryLine = newState.primaryLine,
                            preferredLines = newState.currentLines,
                            moveBearing = fwdBearing,
                            trainModeNow = trainMode
                        )
                        newState = newState.copy(
                            currentName = nm,
                            primaryLine = pl,
                            currentLines = support.linesForStationName(nm),
                            lastName = old,
                            pendingSwitchName = null,
                            pendingCount = 0
                        )
                        decision = when {
                            relined -> "switch_after_reline"
                            lineMatched -> "switch_confirmed"
                            else -> "switch_reline"
                        }
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
                        trainMode && !adjacencyOk && relined -> "keep_adj_after_reline"
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
            append(" relined=").append(relined)
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
