package com.example.neareststationnotifier

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeoLineUtils {

    fun distM(cur: Pair<Double, Double>, c: StationCandidate): Double {
        val lat = c.lat ?: return Double.POSITIVE_INFINITY
        val lon = c.lon ?: return Double.POSITIVE_INFINITY
        val dLat = (lat - cur.first) * 111_320.0
        val dLon = (lon - cur.second) * 111_320.0 * cos(Math.toRadians(cur.first))
        return sqrt(dLat * dLat + dLon * dLon)
    }

    fun bearingFrom(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val lat1 = Math.toRadians(a.first)
        val lat2 = Math.toRadians(b.first)
        val dLon = Math.toRadians(b.second - a.second)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        var br = Math.toDegrees(atan2(y, x))
        if (br < 0) br += 360.0
        return br
    }

    fun angleDiffDeg(a: Double, b: Double): Double {
        val d = (a - b + 540.0) % 360.0 - 180.0
        return abs(d)
    }

    fun normalizeLine(s: String): String =
        s.lowercase()
            .replace("ＪＲ", "jr")
            .replace("（", "(")
            .replace("）", ")")
            .replace(" ", "")
            .trim()
}
