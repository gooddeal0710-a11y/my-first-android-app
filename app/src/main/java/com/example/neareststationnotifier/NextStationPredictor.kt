package com.example.neareststationnotifier

class NextStationPredictor(
    private val enterRadiusM: Double = 120.0,
    private val exitRadiusM: Double = 180.0,
    private val switchMarginM: Double = 80.0,
    private val trainSpeedThreshMps: Double = 5.0,
    private val trainHoldMs: Long = 90_000L,
    private val inferredTrainMoveM: Double = 120.0,
    private val lineLockWarmupMs: Long = 35_000L,
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
        val justEnteredTrainMode = !wasTrainMode && trainMode

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
            trainStartedAtMs = trainStartedAtMs,
            lockedLine = if (justEnteredTrainMode) null else state.lockedLine,
            lockedCandidateLine = if (justEnteredTrainMode) null else state.lockedCandidateLine,
            lockedCandidateCount = if (justEnteredTrainMode) 0 else state.lockedCandidateCount
        )

        val switchOut = NextStationPredictorSwitchLogic.run(
            state = newState,
            support = support,
            nearest = nearest,
            nearestDist = nearestDist,
            currentDist = currentDist,
            trainMode = trainMode,
            enterRadiusM = enterRadiusM,
            exitRadiusM = exitRadiusM,
            switchMarginM = switchMarginM,
            fwdBearing = fwdBearing
        )
        newState = switchOut.state

        val lockOut = NextStationPredictorLockLogic.run(
            state = newState,
            support = support,
            nearest = nearest,
            trainMode = trainMode,
            nowMs = nowMs,
            lineLockWarmupMs = lineLockWarmupMs,
            lineLockResolver = lineLockResolver,
            strongLineConflict = switchOut.strongLineConflict,
            relined = switchOut.relined,
            unlockByStrongMismatch = switchOut.unlockByStrongMismatch
        )
        newState = lockOut.state

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

        val dbg = NextStationPredictorDebug.build(
            effectiveLine = effectiveLine,
            newState = newState,
            trainMode = trainMode,
            lockWarmupDone = lockOut.lockWarmupDone,
            lockAllowed = lockOut.lockAllowed,
            lineLockWarmupMs = lineLockWarmupMs,
            nowMs = nowMs,
            nearest = nearest,
            nearestDist = nearestDist,
            currentDist = currentDist,
            movedDistM = movedDistM,
            pend = switchOut.pend,
            lockedPend = lockOut.lockedPend,
            lineMatched = switchOut.lineMatched,
            forceReline = switchOut.forceReline,
            relined = switchOut.relined,
            relineAttempted = switchOut.relineAttempted,
            strongLineConflict = switchOut.strongLineConflict,
            currentMissing = switchOut.currentMissing,
            sameLineAdvanceLikely = switchOut.sameLineAdvanceLikely,
            lockedLineMismatch = switchOut.lockedLineMismatch,
            suppressCrossLineSwitch = switchOut.suppressCrossLineSwitch,
            lockedCrossLineBlock = switchOut.lockedCrossLineBlock,
            unlockByStrongMismatch = switchOut.unlockByStrongMismatch,
            adjacencyOk = switchOut.adjacencyOk,
            fastRelock = lockOut.fastRelock,
            decision = switchOut.decision,
            nextByAdj = nextByAdj,
            fwdBearing = fwdBearing,
            speedMps = speedMps,
            inferredTrain = inferredTrain,
            accuracyM = accuracyM
        )

        return Result(
            currentName = newState.currentName,
            nextName = nextName,
            state = newState,
            debugText = dbg
        )
    }
}
