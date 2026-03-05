package com.example.neareststationnotifier

object StationFormatter {

    /**
     * 近い順のリストを受け取り、駅名で重複排除して上位3件を表示。
     * ついでに「次/前」も同様に駅名で重複排除して表示。
     */
    fun formatTop3WithNextPrev(list: List<StationItem>): String {
        if (list.isEmpty()) return "--"

        // 1) 駅名で重複排除（順序は維持）
        val unique = distinctByStationNameKeepOrder(list)

        // 2) 表示用に上位3件
        val top = unique.take(3)

        val sb = StringBuilder()
        top.forEachIndexed { idx, s ->
            sb.append("${idx + 1}. ${s.name}")
            if (!s.line.isNullOrBlank()) sb.append("（${s.line}）")
            if (s.distanceMeters != null) sb.append("  ${s.distanceMeters}m")
            sb.append("\n")
        }

        // 3) 次/前（任意：あなたの既存仕様に合わせて残してます）
        val next = unique.getOrNull(3)
        val prev = unique.getOrNull(4)

        if (next != null) {
            sb.append("\nnext: ${next.name}")
            if (!next.line.isNullOrBlank()) sb.append("（${next.line}）")
        }
        if (prev != null) {
            sb.append("\nprev: ${prev.name}")
            if (!prev.line.isNullOrBlank()) sb.append("（${prev.line}）")
        }

        return sb.toString().trimEnd()
    }

    private fun distinctByStationNameKeepOrder(list: List<StationItem>): List<StationItem> {
        val seen = HashSet<String>()
        val out = ArrayList<StationItem>(list.size)
        for (s in list) {
            val key = normalizeStationName(s.name)
            if (seen.add(key)) out.add(s)
        }
        return out
    }

    private fun normalizeStationName(name: String): String {
        // 「駅」有無や空白差などを吸収したい場合はここで調整
        return name.trim()
            .replace("　", " ")
            .replace(Regex("\\s+"), " ")
    }
}
