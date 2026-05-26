package com.example.neareststationnotifier

internal object NextStationPredictorSwitchLogic {

    data class Output(
        val state: NextStationPredictor.State,
        val decision: String,
        val pend: Int,
        val lineMatched: Boolean,
        val forceReline: Boolean,
        val adjacencyOk: Boolean,
        val relined: Boolean,
        val relineAttempted: Boolean,
        val strongLineConflict: Boolean,
        val currentMissing: Boolean,
        val sameLineAdvanceLikely: Boolean,
        val lockedLineMismatch: Boolean,
        val suppressCrossLineSwitch: Boolean,
        val lockedCrossLineBlock: Boolean,
        val unlockByStrongMismatch: Boolean
    )

    fun run(
        state: NextStationPredictor.State,
        support: NextStationPredictorSupport,
        nearest: StationCandidate,
        nearestDist: Double,
        currentDist: Double,
        trainMode: Boolean,
        enterRadiusM: Double,
        exitRadiusM: Double,
        switchMarginM: Double,
        fwdBearing: Double?
    ): Output {
        var newState = state
        var decision = "keep"
        var pend = 0
        var lineMatched = false
        var forceReline = false
        var adjacencyOk = true
        var relined = false
        var relineAttempted = false
        var strongLineConflict = false
        var currentMissing = false
        var sameLineAdvanceLikely = false
        var lockedLineMismatch = false
        var suppressCrossLineSwitch = false
        var lockedCrossLineBlock = false
        var unlockByStrongMismatch = false

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

            return Output(
                state = newState,
                decision = decision,
                pend = pend,
                lineMatched = lineMatched,
                forceReline = forceReline,
                adjacencyOk = adjacencyOk,
                relined = relined,
                relineAttempted = relineAttempted,
                strongLineConflict = strongLineConflict,
                currentMissing = currentMissing,
                sameLineAdvanceLikely = sameLineAdvanceLikely,
                lockedLineMismatch = lockedLineMismatch,
                suppressCrossLineSwitch = suppressCrossLineSwitch,
                lockedCrossLineBlock = lockedCrossLineBlock,
                unlockByStrongMismatch = unlockByStrongMismatch
            )
        }

        val currentName = state.currentName

        if (currentDist <= exitRadiusM) {
            newState = newState.copy(
                pendingSwitchName = null,
                pendingCount = 0
            )
            decision = "keep_hysteresis"

            return Output(
                state = newState,
                decision = decision,
                pend = pend,
                lineMatched = lineMatched,
                forceReline = forceReline,
                adjacencyOk = adjacencyOk,
                relined = relined,
                relineAttempted = relineAttempted,
                strongLineConflict = strongLineConflict,
                currentMissing = currentMissing,
                sameLineAdvanceLikely = sameLineAdvanceLikely,
                lockedLineMismatch = lockedLineMismatch,
                suppressCrossLineSwitch = suppressCrossLineSwitch,
                lockedCrossLineBlock = lockedCrossLineBlock,
                unlockByStrongMismatch = unlockByStrongMismatch
            )
        }

        var effectiveLineBeforeSwitch = state.lockedLine ?: state.primaryLine

        lineMatched =
            if (trainMode) {
                support.stationHasLine(nearest.name, effectiveLineBeforeSwitch)
            } else {
                support.stationHasLine(nearest.name, effectiveLineBeforeSwitch) ||
                    support.linesForStationName(nearest.name).intersect(state.currentLines).isNotEmpty()
            }

        forceReline = trainMode && (
            !currentDist.isFinite() ||
                (currentDist >= 350.0 && nearestDist <= 180.0)
            )

        strongLineConflict = trainMode && forceReline && !lineMatched
        currentMissing = !currentDist.isFinite()

        adjacencyOk = if (trainMode) {
            val fromName = state.lastName ?: currentName
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

        if (trainMode && forceReline) {
            relineAttempted = true

            val relinedLine = support.chooseRelineForCurrentStation(
                currentName = currentName,
                moveBearing = fwdBearing
            )

            val currentPrimaryNorm = state.primaryLine?.let { GeoLineUtils.normalizeLine(it) }

            if (!relinedLine.isNullOrBlank() &&
                GeoLineUtils.normalizeLine(relinedLine) != currentPrimaryNorm
            ) {
                newState = newState.copy(
                    primaryLine = relinedLine,
                    lockedCandidateLine = null,
                    lockedCandidateCount = 0
                )
                relined = true
                effectiveLineBeforeSwitch = relinedLine

                lineMatched =
                    if (trainMode) {
                        support.stationHasLine(nearest.name, effectiveLineBeforeSwitch)
                    } else {
                        support.stationHasLine(nearest.name, effectiveLineBeforeSwitch) ||
                            support.linesForStationName(nearest.name).intersect(newState.currentLines).isNotEmpty()
                    }

                adjacencyOk = if (trainMode) {
                    val fromName = state.lastName ?: currentName
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

        sameLineAdvanceLikely =
            trainMode &&
                support.stationHasLine(nearest.name, effectiveLineBeforeSwitch) &&
                (
                    currentMissing ||
                        (nearestDist + switchMarginM < currentDist)
                    )

        lockedLineMismatch =
            trainMode &&
                !newState.lockedLine.isNullOrBlank() &&
                !support.stationHasLine(nearest.name, newState.lockedLine)

        unlockByStrongMismatch =
            trainMode &&
                currentMissing &&
                forceReline &&
                !newState.lockedLine.isNullOrBlank() &&
                !support.stationHasLine(nearest.name, newState.lockedLine) &&
                nearestDist <= 80.0

        if (unlockByStrongMismatch) {
            newState = newState.copy(
                lockedLine = null,
                lockedCandidateLine = null,
                lockedCandidateCount = 0
            )
        }

        suppressCrossLineSwitch =
            lockedLineMismatch &&
                !strongLineConflict &&
                !relined &&
                !lineMatched &&
                !unlockByStrongMismatch

        lockedCrossLineBlock =
            trainMode &&
                !unlockByStrongMismatch &&
                !newState.lockedLine.isNullOrBlank() &&
                !support.stationHasLine(nearest.name, newState.lockedLine) &&
                !lineMatched &&
                !adjacencyOk

        val allowAdjGuardBypass =
            trainMode && (
                (strongLineConflict && (relined || relineAttempted)) ||
                    sameLineAdvanceLikely
                )

        val allowTrainLineGate =
            if (!trainMode) {
                true
            } else {
                lineMatched ||
                    relined ||
                    (forceReline && newState.lockedLine.isNullOrBlank())
            }

        val needSwitch =
            !suppressCrossLineSwitch &&
                !lockedCrossLineBlock &&
                allowTrainLineGate &&
                (adjacencyOk || allowAdjGuardBypass) &&
                (GeoLineUtils.normalizeStationName(nearest.name) !=
                    GeoLineUtils.normalizeStationName(currentName)) &&
                (currentMissing || (nearestDist + switchMarginM < currentDist))

        if (needSwitch) {
            val same = state.pendingSwitchName?.let {
                GeoLineUtils.normalizeStationName(it) ==
                    GeoLineUtils.normalizeStationName(nearest.name)
            } ?: false

            val nextCount = if (same) state.pendingCount + 1 else 1
            pend = nextCount
            val confirmTimes = if (trainMode) 1 else 2

            if (nextCount >= confirmTimes) {
                val old = currentName
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
                    unlockByStrongMismatch -> "switch_after_unlock"
                    relined -> "switch_after_reline"
                    forceReline && strongLineConflict -> "switch_force_reline"
                    currentMissing && sameLineAdvanceLikely -> "switch_missing_current"
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
                unlockByStrongMismatch -> "unlock_wait"
                lockedCrossLineBlock -> "keep_locked_crossline_block"
                suppressCrossLineSwitch -> "keep_locked_crossline_guard"
                trainMode && !adjacencyOk && relined -> "keep_adj_after_reline"
                trainMode && !adjacencyOk && currentMissing && sameLineAdvanceLikely -> "keep_missing_current_wait"
                trainMode && !adjacencyOk && allowAdjGuardBypass -> "keep_reline_bypass_wait"
                trainMode && !adjacencyOk -> "keep_adj_guard"
                trainMode && !lineMatched && forceReline -> "keep_reline_wait"
                trainMode && !lineMatched -> "keep_line_guard"
                else -> "keep_reset"
            }
        }

        return Output(
            state = newState,
            decision = decision,
            pend = pend,
            lineMatched = lineMatched,
            forceReline = forceReline,
            adjacencyOk = adjacencyOk,
            relined = relined,
            relineAttempted = relineAttempted,
            strongLineConflict = strongLineConflict,
            currentMissing = currentMissing,
            sameLineAdvanceLikely = sameLineAdvanceLikely,
            lockedLineMismatch = lockedLineMismatch,
            suppressCrossLineSwitch = suppressCrossLineSwitch,
            lockedCrossLineBlock = lockedCrossLineBlock,
            unlockByStrongMismatch = unlockByStrongMismatch
        )
    }
}
