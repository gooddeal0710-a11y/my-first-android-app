package com.example.neareststationnotifier

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class StationApi(
    private val http: OkHttpClient = OkHttpClient()
) {
    fun getNearestStations(lat: Double, lon: Double): List<StationCandidate> {
        // ★変更：radius=5000（単位m）
        val url = "https://express.heartrails.com/api/json?method=getStations&x=$lon&y=$lat&radius=5000"
        val req = Request.Builder().url(url).get().build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("http ${resp.code}")

            val body = resp.body?.string().orEmpty()
            val json = JSONObject(body)
            val arr = json.optJSONObject("response")?.optJSONArray("station") ?: return emptyList()

            val out = ArrayList<StationCandidate>(arr.length())
            for (i in 0 until arr.length()) {
                val st = arr.optJSONObject(i) ?: continue

                out.add(
                    StationCandidate(
                        name = st.optString("name", "--"),
                        line = st.optString("line", ""),
                        company = st.optString("company", ""),
                        distanceRaw = pickDistanceRaw(st),
                        next = st.optString("next", ""),
                        prev = st.optString("prev", ""),
                        lat = st.optString("y", "").toDoubleOrNull(),
                        lon = st.optString("x", "").toDoubleOrNull()
                    )
                )
            }
            return out
        }
    }

    private fun pickDistanceRaw(st: JSONObject): String {
        val candidates = listOf("distance", "dist", "distance_km", "km", "meter", "meters", "m")
        for (k in candidates) {
            val v = st.optString(k, "")
            if (v.isNotBlank()) return v
        }
        return ""
    }
}
