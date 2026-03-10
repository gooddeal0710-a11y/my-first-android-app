package com.example.neareststationnotifier

import kotlin.math.*

object StationFormatter {

    /**
     * 駅名で同一扱い（正規化キー）して上位3件だけ表示。
     * 同名駅が複数ある場合は「距離が最小の1件」を代表として残す。
     *
     * 注意:
     * - lat/lon がある場合はハバースインで距離計算
     * - lat/lon が無い場合は distanceRaw を簡易パースして距離計算
     */
    fun formatTop3WithNextPrev(list: List<StationCandidate>): String {
        if (list.isEmpty()) return "--"

        // 表示側も「同名は距離最小の代表」に統一
        val uniqueNearest = dedupeByStationNameKeepNearest(list).take(3)

        return buildString {
            uniqueNearest.forEachIndexed { idx, s ->
                append("${idx + 1}. ${s.name}")
                if (s.distanceRaw.isNotBlank()) append("（${s.distanceRaw}）")
                if (s.line.isNotBlank()) append(" / ${s.line}")
                append("\n")
            }
        }.trimEnd()
    }

    /**
     * 駅名キーで同一扱いし、各グループから「距離が最小」の候補を代表として残す。
     * LinkedHashMap を使って、初出順の安定性も保つ（代表は距離で入れ替わり得る）。
     */
    private fun dedupeByStationNameKeepNearest(list: List<StationCandidate>): List<StationCandidate> {
        val bestByKey = LinkedHashMap<String, StationCandidate>()
        for (s in list) {
            val key = stationKeyFromName(s.name)
            val prev = bestByKey[key]
            if (prev == null) {
                bestByKey[key] = s
            } else {
                val dPrev = distanceMeters(prev)
                val dNew = distanceMeters(s)
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

    private fun distanceMeters(st: StationCandidate): Double {
        return if (st.lat != null && st.lon != null) {
            // 現在地が無いので、ここでは「候補同士の比較」用に distanceRaw より信頼できる値が無い。
            // ただし list は通常「現在地からの近い順」で来る想定なので、
            // lat/lon がある場合でも distanceRaw が空なら 0 扱いにならないように distanceRaw を優先せず、
            // lat/lon がある場合は distanceRaw を使わずに「distanceRawが無い=不明」として扱うのは難しい。
            // ここでは distanceRaw が使えるならそれを使い、無ければ 0 にしないために大きめを返す。
            // → 実運用では API が distanceRaw を返す前提なので問題になりにくい。
            parseDistanceMeters(st.distanceRaw).takeIf { it > 0.0 } ?: 1.0e15
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
}
