package com.example.neareststationnotifier

import android.content.Context
import android.location.Location

class NearestStationsWorker(
    private val context: Context,
    private val stationApi: StationApi,
    private val predictor: NextStationPredictor = NextStationPredictor()
) {
    private var predictorState = NextStationPredictor.State()
    private var prevFix: Pair<Double, Double>? = null

    fun fetchStationsText(loc: Location): String {
        val lat = loc.latitude
        val lon = loc.longitude

        val list = stationApi.getNearestStations(lat, lon)

        // 推定に使う候補は多めに（表示とは別）
        val candidates = list.take(50)

        val cur = Pair(lat, lon)
        val r = predictor.predict(
            prevLatLon = prevFix,
            curLatLon = cur,
            candidates = candidates,
            state = predictorState,
            speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else null,
            bearingDeg = if (loc.hasBearing()) loc.bearing.toDouble() else null,
            accuracyM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null
        )
        predictorState = r.state
        prevFix = cur

        val currentLine = r.currentName?.let { "現在: $it" } ?: "現在: --"
        val nextLine = r.nextName?.let { "次: $it" } ?: "次: --"

        val showDebugOverlay = DebugPrefs.getShowDebug(context)

        // ★渋谷などで表示が爆発しないよう、デバッグ表示は少し絞る
        val apiTopN = 6

        val apiListText = list.take(apiTopN).mapIndexed { i, s ->
            val dist = s.distanceRaw.ifBlank { "--" }
            val line = s.line.ifBlank { "--" }
            val company = s.company.ifBlank { "--" }
            val next = s.next.ifBlank { "--" }
            val prev = s.prev.ifBlank { "--" }
            "${i + 1}. ${s.name} / $line / $company / dist=$dist / next=$next prev=$prev"
        }.joinToString("\n")

        return buildString {
            // ★スクロール不可なので、最重要の api count を先頭に固定
            if (showDebugOverlay) {
                append("api count=").append(list.size).append("\n")
            }

            append(currentLine).append("\n")
            append(nextLine).append("\n")

            if (showDebugOverlay && r.debugText.isNotBlank()) {
                append(r.debugText).append("\n")
            }

            if (showDebugOverlay) {
                append("api stations(top ").append(apiTopN).append("):\n")
                append(apiListText).append("\n")
            }

            append(StationFormatter.formatTop3WithNextPrev(list, cur))
        }
    }
}
