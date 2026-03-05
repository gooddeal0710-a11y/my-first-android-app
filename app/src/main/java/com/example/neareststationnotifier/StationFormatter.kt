package com.example.neareststationnotifier

import java.util.Locale

object StationFormatter {

    fun formatTop3WithNextPrev(list: List<StationCandidate>): String {
        if (list.isEmpty()) return "--"

        val seen = LinkedHashSet<String>()
        val lines = ArrayList<String>(3)

        for (st in list) {
            val id = "${st.name}|${st.line}|${st.company}"
            if (!seen.add(id)) continue

            val metersText = formatDistanceToMeters(st.distanceRaw)

            val suffix = buildString {
                if (st.line.isNotBlank()) append(" / ${st.line}")
                if (st.company.isNotBlank()) append(" / ${st.company}")
            }

            val np = buildString {
                if (st.next.isNotBlank()) append("\n   next: ${st.next}")
                if (st.prev.isNotBlank()) append("\n   prev: ${st.prev}")
            }

            lines.add("${lines.size + 1}. ${st.name} (${metersText})$suffix$np")
            if (lines.size >= 3) break
        }

        return if (lines.isEmpty()) "--" else lines.joinToString("\n")
    }

    fun formatDistanceToMeters(distanceRaw: String): String {
        val s0 = distanceRaw.trim()
        if (s0.isEmpty()) return "--m"

        val lower = s0.lowercase(Locale.US)
        val num = lower.replace("km", "").replace("m", "").trim()
        val v = num.toDoubleOrNull() ?: return "--m"

        val meters = when {
            lower.contains("km") -> v * 1000.0
            lower.contains("m") -> v
            v < 10.0 -> v * 1000.0
            else -> v
        }

        return "${meters.toInt().coerceAtLeast(0)}m"
    }
}
