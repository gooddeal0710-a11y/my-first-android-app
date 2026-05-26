package com.example.neareststationnotifier

internal object NextStationPredictorLockLogic {

    data class Output(
        val state: NextStationPredictor.State,
        val lockWarmupDone: Boolean,
        val lockAllowed: Boolean,
        val lockedPend: Int,
        val fastRelock: Boolean
    )

    fun run(
        state: NextStationPredictor.State,
        support: NextStationPredictorSupport,
        nearest: StationCandidate,
        trainMode: Boolean,
        nowMs: Long,
        lineLockWarmupMs: Long,
        lineLockResolver: LineLockResolver,
        strongLineConflict: Boolean,
        relined: Boolean,
        unlockByStrongMismatch: Boolean
    ): Output {
        var newState = state
        var lockedPend = 0
        var fastRelock = false

        val lockWarmupDone =
            trainMode &&
                newState.trainStartedAtMs > 0L &&
                (nowMs - newState.trainStartedAtMs >= lineLockWarmupMs)

        val lockPathConfirmed =
            trainMode &&
                !newState.currentName.isNullOrBlank() &&
                !newState.lastName.isNullOrBlank() &&
                !newState.primaryLine.isNullOrBlank() &&
                support.isNaturalTrainSwitch(
                    newState.lastName!!,
                    newState.currentName!!,
                    newState.primaryLine!!
                )

        val lockAllowed =
            trainMode &&
                lockWarmupDone &&
                lockPathConfirmed

        val primaryNorm = newState.primaryLine?.let { GeoLineUtils.normalizeLine(it) }
        val lockedNorm = newState.lockedLine?.let { GeoLineUtils.normalizeLine(it) }

        val currentSupportsPrimary =
            !newState.currentName.isNullOrBlank() &&
                !newState.primaryLine.isNullOrBlank() &&
                support.stationHasLine(newState.currentName!!, newState.primaryLine)

        val nearestSupportsPrimary =
            !newState.primaryLine.isNullOrBlank() &&
                support.stationHasLine(nearest.name, newState.primaryLine)

        if (
            lockAllowed &&
            !newState.lockedLine.isNullOrBlank() &&
            !newState.primaryLine.isNullOrBlank() &&
            lockedNorm != primaryNorm &&
            currentSupportsPrimary &&
            nearestSupportsPrimary
        ) {
            newState = newState.copy(
                lockedLine = newState.primaryLine,
                lockedCandidateLine = null,
                lockedCandidateCount = 0
            )
            fastRelock = true
        } else {
            val skipLockResolveThisTurn =
                trainMode && (strongLineConflict || relined || unlockByStrongMismatch)

            if (lockAllowed && !skipLockResolveThisTurn) {
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
                        lockedLine = newState.lockedLine,
                        lockedCandidateLine = null,
                        lockedCandidateCount = 0
                    )
                }
            }
        }

        return Output(
            state = newState,
            lockWarmupDone = lockWarmupDone,
            lockAllowed = lockAllowed,
            lockedPend = lockedPend,
            fastRelock = fastRelock
        )
    }
}
