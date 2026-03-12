package com.example.neareststationnotifier

class NearestStationsWorker(
    private val stationApi: StationApi,
    private val predictor: NextStationPredictor = NextStationPredictor()
) {
    private var predictorState = NextStationPredictor.State()
    private var prevFix: Pair<Double, Double>? = null

    // ★アプリのボタンで切り替えるフラグ
    var showDebugOverlay: Boolean = true

    fun fetchStationsText(lat: Double, lon: Double): String {
        val list = stationApi.getNearestStations(lat, lon)

        val cur = Pair(lat, lon)
        val r = predictor.predict(
            prevLatLon = prevFix,
            curLatLon = cur,
            candidates = list.take(5),
            state = predictorState
        )
        predictorState = r.state
        prevFix = cur

        val currentLine = r.currentName?.let { "現在: $it" } ?: "現在: --"
        val nextLine = r.nextName?.let { "次: $it" } ?: "次: --"

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
