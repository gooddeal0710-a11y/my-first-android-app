package com.example.neareststationnotifier

import kotlin.math.*

class NextStationPredictor {

    data class State(
        val confirmedCurrentName: String? = null,
        val lastCurrentWinnerName: String? = null,
        val lastCurrentWinnerStreak: Int = 0
    )

    data class Result(
        val currentName: String?,
        val nextName: String?,
        val state: State
    )

    /**
     * 仕様（最小）:
     * - 現在駅: 距離が最小の候補を「勝者」とし、200m以内で同一勝者が連続2回なら確定
     * - 次駅: 現在駅を除外し、進行方向（取れるときだけ）+距離でスコア最大の候補
     */
    fun predict(
        prevLatLon: Pair<Double, Double>?,
        curLatLon: Pair<Double, Double>,
        candidates: List<StationCandidate>,
        state: State,
        currentConfirmMeters: Double = 200.0
    ): Result {
        if (candidates.isEmpty()) return Result(null, null, state)

        val (curLat, curLon) = curLatLon
        val moveBearing = prevLatLon?.let { bearingDeg(it.first, it.second, curLat, curLon) }

        val usable = candidates.filter { it.lat != null && it.lon != null }
        val list = if (usable.isNotEmpty()) usable else candidates

        // 現在駅（距離最小）
        val currentWinner = list.minByOrNull { st ->
            distanceMeters(curLat, curLon, st)
        }
        val currentWinnerName = currentWinner?.name
        val currentWinnerDist = currentWinner?.let { distanceMeters(curLat, curLon, it) } ?: Double.POSITIVE_INFINITY

        // ヒステリシス：200m以内で同じ勝者が連続2回なら確定
        val newStreak =
            if (currentWinnerName != null && currentWinnerName == state.lastCurrentWinnerName) state.lastCurrentWinnerStreak + 1
            else 1

        val newConfirmedCurrent =
            if (currentWinnerName != null && currentWinnerDist <= currentConfirmMeters && newStreak >= 2) {
                currentWinnerName
            } else {
                state.confirmedCurrentName
            }

        val newState = State(
            confirmedCurrentName = newConfirmedCurrent,
            lastCurrentWinnerName = currentWinnerName,
            lastCurrentWinnerStreak = newStreak
        )

        val currentName = newConfirmedCurrent ?: currentWinnerName

        // 次駅（現在駅を除外して前方スコア最大）
        val nextCandidateList = list.filter { it.name != currentName }
        val nextWinner = nextCandidateList.maxByOrNull { st ->
            scoreNextStation(
                moveBearing = moveBearing,
                curLat = curLat,
                curLon = curLon,
                st = st
            )
        }

        return Result(
            currentName = currentName,
            nextName = nextWinner?.name,
            state = newState
        )
    }

    private fun scoreNextStation(
        moveBearing: Double?,
        curLat: Double,
        curLon: Double,
        st: StationCandidate
    ): Double {
        val distM = distanceMeters(curLat, curLon, st)

        // 近いほど高得点（400mスケール：次駅は少し遠めも許容）
        val distScore = 1.0 / (1.0 + distM / 400.0)

        // 進行方向が取れるときだけ「前方」を加点
        val dirScore = if (moveBearing != null && st.lat != null && st.lon != null) {
            val toStation = bearingDeg(curLat, curLon, st.lat, st.lon)
            val diff = angleDiffDeg(moveBearing, toStation) // 0..180
            max(0.0, 1.0 - (diff / 90.0))
        } else 0.0

        return distScore * 1.0 + dirScore * 0.6
    }

    private fun distanceMeters(curLat: Double, curLon: Double, st: StationCandidate): Double {
        return if (st.lat != null && st.lon != null) {
            haversineMeters(curLat, curLon, st.lat, st.lon)
        } else {
            parseDistanceMeters(st.distanceRaw)
        }
    }

    private fun parseDistanceMeters(raw: String): Double {
        val v = raw.trim()
        val d = v.toDoubleOrNull() ?: return 0.0
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
