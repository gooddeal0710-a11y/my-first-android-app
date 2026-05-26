package com.example.neareststationnotifier

import kotlin.math.max

internal object NextStationPredictorDebug {
    fun build(
        effectiveLine: String?,
        newState: NextStationPredictor.State,
        trainMode: Boolean,
        lockWarmupDone: Boolean,
        lockAllowed: Boolean,
        lineLockWarmupMs: Long,
        nowMs: Long,
        nearest: StationCandidate,
        nearestDist: Double,
        currentDist: Double,
        movedDistM: Double,
        pend: Int,
        lockedPend: Int,
        lineMatched: Boolean,
        forceReline: Boolean,
        relined: Boolean,
        relineAttempted: Boolean,
        strongLineConflict: Boolean,
        currentMissing: Boolean,
        sameLineAdvanceLikely: Boolean,
        lockedLineMismatch: Boolean,
        suppressCrossLineSwitch: Boolean,
        lockedCrossLineBlock: Boolean,
        unlockByStrongMismatch: Boolean,
        adjacencyOk: Boolean,
        fastRelock: Boolean,
        decision: String,
        nextByAdj: String?,
        fwdBearing: Double?,
        speedMps: Double?,
        inferredTrain: Boolean,
        accuracyM: Double?
    ): String {
        return buildString {
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
            append(" lallow=").append(lockAllowed)
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
            append(" rtry=").append(relineAttempted)
            append(" conflict=").append(strongLineConflict)
            append(" cmiss=").append(currentMissing)
            append(" sladv=").append(sameLineAdvanceLikely)
            append(" llmis=").append(lockedLineMismatch)
            append(" xsup=").append(suppressCrossLineSwitch)
            append(" xblk=").append(lockedCrossLineBlock)
            append(" xunlk=").append(unlockByStrongMismatch)
            append(" adjok=").append(adjacencyOk)
            append(" frelock=").append(fastRelock)
            append(" dec=").append(decision)
            append(" adj=").append(nextByAdj ?: "--")
            if (fwdBearing != null) append(" br=").append("%.1f".format(fwdBearing))
            if (speedMps != null) append(" sp=").append("%.1f".format(speedMps))
            append(" inf=").append(inferredTrain)
            if (accuracyM != null) append(" acc=").append("%.0f".format(accuracyM))
        }
    }
}
