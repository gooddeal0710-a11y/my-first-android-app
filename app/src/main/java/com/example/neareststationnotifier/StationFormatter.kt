package com.example.neareststationnotifier

import kotlin.math.*

object StationFormatter {

    /**
     * 駅名で同一扱い（正規化キー）して上位3件だけ表示。
     * 同名駅が複数ある場合は「現在地からの距離が最小の1件」を代表として残す。
     */
    fun formatTop3WithNextPrev(
        list: List<StationCandidate>,
        curLatLon: Pair<Double, Double>
    ): String {
        if (list.isEmpty()) return "--"

        val (curLat, curLon) = curLatLon
        val uniqueNearest = dedupeByStationNameKeepNearest(
            list = list,
            curLat = curLat,
            curLon = curLon
        ).take(3)

        return buildString {
            uniqueNearest.forEachIndexed { idx, s ->
                append("${idx + 1}. ${s.name}")
                if (s.distanceRaw.isNotBlank()) append("（${s.distanceRaw}）")
                if (s.line.isNotBlank()) append(" / ${s.line}")
                append("\n")
            }
        }.trimEnd()
    }

    private fun dedupeByStationNameKeepNearest(
        list: List<StationCandidate>,
        curLat: Double,
        curLon: Double
    ): List<StationCandidate> {
        val bestByKey = LinkedHashMap<String, StationCandidate>()
        for (s in list) {
            val key = stationKeyFromName(s.name)
            val prev = bestByKey[key]
            if (prev == null) {
                bestByKey[key] = s
            } else {
                val dPrev = distanceMeters(curLat, curLon, prev)
                val dNew = distanceMeters(curLat, curLon, s)
                if (dNew < dPrev) bestByKey[key] = s
            }
        }
        return bestByKey.values.toList()
    }

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
        val d = v.toDoubleOrNull() ?: return Double.POSITIVE_INFINITY
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
}
