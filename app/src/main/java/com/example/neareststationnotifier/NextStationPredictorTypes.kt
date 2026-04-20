package com.example.neareststationnotifier

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.abs

data class NextStationPredictorState(
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

data class NextStationPredictorResult(
    val currentName: String?,
    val nextName: String?,
    val state: NextStationPredictorState,
    val debugText: String = ""
)

fun nextStationCoordDistM(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
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

fun nextStationAngleDiffDeg(a: Double, b: Double): Double {
    val d = abs(a - b) % 360.0
    return if (d > 180.0) 360.0 - d else d
}

fun nextStationPairOrNull(s: StationCandidate?): Pair<Double, Double>? {
    val lat = s?.lat
    val lon = s?.lon
    return if (lat != null && lon != null) Pair(lat, lon) else null
}
