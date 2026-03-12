package com.example.neareststationnotifier

class NearestStationsWorker(
    private val stationApi: StationApi,
    private val predictor: NextStationPredictor = NextStationPredictor()
) {
    private var predictorState = NextStationPredictor.State()
    private var prevFix: Pair<Double, Double>? = null

    /**
     * 駅取得 + 次駅推測 + 表示文字列生成までをまとめて行う。
     * 成功時は stationsText を返す。失敗時は例外を投げる（呼び出し側で握る）。
     */
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

        return currentLine + "\n" +
            nextLine + "\n" +
            r.debugText + "\n" +
            StationFormatter.formatTop3WithNextPrev(list, cur)
    }
}
