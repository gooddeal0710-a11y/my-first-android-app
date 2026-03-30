package com.example.neareststationnotifier

class LineLockResolver(
    private val confirmTimes: Int = 3
) {
    data class Input(
        val trainMode: Boolean,
        val primaryLine: String?,
        val lockedLine: String?,
        val lockedCandidateLine: String?,
        val lockedCandidateCount: Int
    )

    data class Output(
        val lockedLine: String?,
        val lockedCandidateLine: String?,
        val lockedCandidateCount: Int,
        val lockedPend: Int
    )

    fun resolve(input: Input): Output {
        if (!input.trainMode) {
            return Output(
                lockedLine = null,
                lockedCandidateLine = null,
                lockedCandidateCount = 0,
                lockedPend = 0
            )
        }

        val primary = input.primaryLine?.norm()
        val locked = input.lockedLine?.norm()
        val cand = input.lockedCandidateLine?.norm()

        if (locked.isNullOrBlank()) {
            return Output(
                lockedLine = input.primaryLine,
                lockedCandidateLine = null,
                lockedCandidateCount = 0,
                lockedPend = 0
            )
        }

        if (primary.isNullOrBlank() || primary == locked) {
            return Output(
                lockedLine = input.lockedLine,
                lockedCandidateLine = null,
                lockedCandidateCount = 0,
                lockedPend = 0
            )
        }

        val nextCount = if (cand == primary) input.lockedCandidateCount + 1 else 1

        return if (nextCount >= confirmTimes) {
            Output(
                lockedLine = input.primaryLine,
                lockedCandidateLine = null,
                lockedCandidateCount = 0,
                lockedPend = nextCount
            )
        } else {
            Output(
                lockedLine = input.lockedLine,
                lockedCandidateLine = input.primaryLine,
                lockedCandidateCount = nextCount,
                lockedPend = nextCount
            )
        }
    }

    private fun String.norm(): String =
        lowercase()
            .replace("ＪＲ", "jr")
            .replace("（", "(")
            .replace("）", ")")
            .replace(" ", "")
            .trim()
}
