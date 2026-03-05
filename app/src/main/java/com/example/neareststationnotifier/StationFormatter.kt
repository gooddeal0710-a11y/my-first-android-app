package com.example.neareststationnotifier

object StationFormatter {

    // 駅名で重複排除して、上位3件だけ表示（距離/路線は最短の1件が残る）
    fun formatTop3WithNextPrev(list: List<StationCandidate>): String {
        if (list.isEmpty()) return "--"

        val unique = distinctByStationNameKeepOrder(list).take(3)

        return buildString {
            unique.forEachIndexed { idx, s ->
                append("${idx + 1}. ${s.name}")
                if (s.distanceRaw.isNotBlank()) append("（${s.distanceRaw}）")
                if (s.line.isNotBlank()) append(" / ${s.line}")
                append("\n")
            }
        }.trimEnd()
    }

    private fun distinctByStationNameKeepOrder(list: List<StationCandidate>): List<StationCandidate> {
        val seen = HashSet<String>()
        val out = ArrayList<StationCandidate>(list.size)
        for (s in list) {
            val key = stationKeyFromName(s.name)
            if (seen.add(key)) out.add(s)
        }
        return out
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
}
