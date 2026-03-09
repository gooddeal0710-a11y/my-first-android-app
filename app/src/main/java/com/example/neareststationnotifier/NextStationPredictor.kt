package com.example.neareststationnotifier

import kotlin.math.*

class NextStationPredictor {

    data class State(
        val confirmedName: String? = null,
        val lastWinnerName: String? = null,
        val lastWinnerStreak: Int = 0
    )

    data class Result(
        val predictedName: String?,
        val state: State
    )

    /**
     * 本番向けの最小構成：
     * - 距離 + 進行方向（取れるときだけ）で候補駅をスコアリング
     * - 駅名の切り替わりを抑えるため、同じ勝者が連続2回で「確定」
     */
    fun predict(
        prevLatLon: Pair<Double, Double>?,
        curLatLon: Pair<Double, Double>,
        candidates: List<StationCandidate>,
        state: State
    ): Result {
        if (candidates.isEmpty()) return Result(null, state)

        val (curLat, curLon) = curLatLon
        val moveBearing = prevLatLon?.let { bearingDeg(it.first, it.second, curLat, curLon) }

        // 座標が取れてる候補を優先（取れてない場合は全候補で距離Raw頼み）
        val usable = candidates.filter { it.lat != null && it.lon != null }
        val list = if (usable.isNotEmpty()) usable else candidates

        val winner = list.maxByOrNull { s ->
            scoreStation(
                moveBearing = moveBearing,
                curLat = curLat,
                curLon = curLon,
                st = s
            )
        }

        val winnerName = winner?.name

        // ヒステリシス：同じ勝者が連続2回で確定
        val newStreak =
            if (winnerName != null && winnerName == state.lastWinnerName) state.lastWinnerStreak + 1
            else 1

        val newConfirmed =
            if (winnerName != null && newStreak >= 2) winnerName else state.confirmedName

        val newState = State(
            confirmedName = newConfirmed,
            lastWinnerName = winnerName,
            lastWinnerStreak = newStreak
        )

        val predicted = newConfirmed ?: winnerName
        return Result(predicted, newState)
    }

    private fun scoreStation(
        moveBearing: Double?,
        curLat: Double,
        curLon: Double,
        st: StationCandidate
    ): Double {
        val distM = if (st.lat != null && st.lon != null) {
            haversineMeters(curLat, curLon, st.lat, st.lon)
        } else {
            parseDistanceMeters(st.distanceRaw)
        }

        // 近いほど高得点（200mスケール）
        val distScore = 1.0 / (1.0 + distM / 200.0)

        // 進行方向が取れるときだけ「前方」を加点
        val dirScore = if (moveBearing != null && st.lat != null && st.lon != null) {
            val toStation = bearingDeg(curLat, curLon, st.lat, st.lon)
            val diff = angleDiffDeg(moveBearing, toStation) // 0..180
            max(0.0, 1.0 - (diff / 120.0))
        } else 0.0

        // 距離重視 + 方向で少し補正
        return distScore * 1.0 + dirScore * 0.35
    }

    private fun parseDistanceMeters(raw: String): Double {
        val v = raw.trim()
        val d = v.toDoubleOrNull() ?: return 0.0
        // HeartRailsのdistanceはkm文字列のことが多い想定
        return if (d < 10.0) d * 1000.0 else d
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    private fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        val brng = Math.toDegrees(atan2(y, x))
        return (brng + 360.0) % 360.0
    }

    private fun angleDiffDeg(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }
}
