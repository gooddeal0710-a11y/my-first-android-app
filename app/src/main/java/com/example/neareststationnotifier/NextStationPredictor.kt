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
     *
     * 追加仕様:
     * - 同名駅（"新宿駅" と "新宿"、"新宿（JR）" 等）を同一扱いし、代表は距離最小の1件に統合してから推定する
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

        // lat/lon が使える候補を優先
        val usable = candidates.filter { it.lat != null && it.lon != null }
        val baseList = if (usable.isNotEmpty()) usable else candidates

        // ★同名駅を統合（代表は距離最小）
        val list = dedupeByStationNameKeepNearest(
            curLat = curLat,
            curLon = curLon,
            list = baseList
        )

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

    /**
     * 駅名キーで同一扱いし、各グループから「現在地からの距離が最小」の候補を代表として残す。
     * LinkedHashMap を使うことで、初出順の安定性も保つ（ただし代表は距離で入れ替わり得る）。
     */
    private fun dedupeByStationNameKeepNearest(
        curLat: Double,
        curLon: Double,
        list: List<StationCandidate>
    ): List<StationCandidate> {
        val bestByKey = LinkedHashMap<String, StationCandidate>()
        for (st in list) {
            val key = stationKeyFromName(st.name)
            val prev = bestByKey[key]
            if (prev == null) {
                bestByKey[key] = st
            } else {
                val dPrev = distanceMeters(curLat, curLon, prev)
                val dNew = distanceMeters(curLat, curLon, st)
                if (dNew < dPrev) bestByKey[key] = st
            }
        }
        return bestByKey.values.toList()
    }

    /**
     * StationFormatter と同等の駅名正規化。
     * - 不可視文字除去
     * - 空白正規化
     * - 括弧以降を落とす
     * - 末尾の「駅」を落とす
     */
    private fun stationKeyFromName(raw: String): String {
        var s = raw

        // 不可視文字（ゼロ幅スペース等）を除去
        s = s.replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")

        // 空白正規化
        s = s.trim()
            .replace("　", " ")
            .replace(Regex("\\s+"), " ")

        // 括弧以降を落とす（駅名に余計な情報が混ざってる場合対策）
        s = s.replace(Regex("[（(].*$"), "")

        // 末尾の「駅」を落とす（"新宿駅" と "新宿" を同一扱い）
        s = s.trim().removeSuffix("駅")

        return s
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

    /**
     * distanceRaw が数値だけで来る想定の簡易パース。
     * - 10未満なら km とみなして m に変換（例: "0.3" -> 300m）
     * - 10以上なら m とみなす（例: "250" -> 250m）
     */
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
