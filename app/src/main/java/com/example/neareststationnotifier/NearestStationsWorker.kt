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

        // 密集地対策：少し多めに
        val candidates = list.take(20)

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

        return buildString {
            append(currentLine).append("\n")
            append(nextLine).append("\n")
            if (showDebugOverlay && r.debugText.isNotBlank()) {
                append(r.debugText).append("\n")
            }
            append(StationFormatter.formatTop3WithNextPrev(list, cur))
        }
    }
}
