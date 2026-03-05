package com.example.neareststationnotifier

object StationFormatter {

    fun formatTop3WithNextPrev(list: List<StationItem>): String {
        if (list.isEmpty()) return "--"

        val unique = distinctByStationNameKeepOrder(list).take(3)

        return buildString {
            unique.forEachIndexed { idx, s ->
                append("${idx + 1}. ${s.name}")
                if (!s.distance.isNullOrBlank()) append("（${s.distance}）")
                if (!s.line.isNullOrBlank()) append(" / ${s.line}")
                append("\n")
            }
        }.trimEnd()
    }

    private fun distinctByStationNameKeepOrder(list: List<StationItem>): List<StationItem> {
        val seen = HashSet<String>()
        val out = ArrayList<StationItem>(list.size)
        for (s in list) {
            val key = stationKeyFromName(s.name)
            if (seen.add(key)) out.add(s)
        }
        return out
    }

    /**
     * 「見た目は同じ駅名なのに重複する」問題を潰すための強め正規化。
     * - 前後空白除去
     * - 全角空白→半角
     * - 連続空白→1個
     * - ゼロ幅系の不可視文字除去
     * - "（...）" や "(...)" 以降を削除（駅名に余計な情報が混ざってるケース対策）
     * - 末尾の「駅」を削除（"新宿駅" と "新宿" を同一扱い）
     */
    private fun stationKeyFromName(raw: String): String {
        var s = raw

        // 不可視文字（ゼロ幅スペース等）を除去
        s = s.replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")

        // 空白正規化
        s = s.trim()
            .replace("　", " ")
            .replace(Regex("\\s+"), " ")

        // 括弧以降を落とす（駅名に路線等が混ざってる場合）
        s = s.replace(Regex("[（(].*$"), "")

        // 末尾の「駅」を落とす
        s = s.trim().removeSuffix("駅")

        return s
    }
}
