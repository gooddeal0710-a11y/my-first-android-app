package com.example.neareststationnotifier

import kotlin.math.*

class NextStationPredictor {

    data class State(
        val currentName: String? = null,
        val currentLine: String? = null,
        val pendingSwitchName: String? = null,
        val pendingSwitchLine: String? = null,
        val pendingCount: Int = 0
    )

    data class Result(
        val currentName: String?,
        val nextName: String?,
        val state: State
    )

    fun predict(
        prevLatLon: Pair<Double, Double>?,
        curLatLon: Pair<Double, Double>,
        candidates: List<StationCandidate>,
        state: State
    ): Result {
        if (candidates.isEmpty()) return Result(state.currentName, null, state)

        // 近い順に並んでる前提が崩れてもいいように、距離で最寄りを取り直す
        val nearest = candidates.minByOrNull { distMetersOrInf(it) }!!
        val nearestDist = distMetersOrInf(nearest)

        val currentDist = state.currentName?.let { curName ->
            candidates.firstOrNull { it.name == curName }?.let { distMetersOrInf(it) }
        } ?: Double.POSITIVE_INFINITY

        var newState = state

        // 1) 200m以内なら即「現在」をnearestに
        if (nearestDist <= 200.0) {
            newState = State(
                currentName = nearest.name,
                currentLine = nearest.line,
                pendingSwitchName = null,
                pendingSwitchLine = null,
                pendingCount = 0
            )
        } else {
            // 2) 200m以内じゃなくても、明確に近い駅が出たら切替（追従重視）
            val margin = 80.0
            val needSwitch = (nearest.name != state.currentName) && (nearestDist + margin < currentDist)

            if (needSwitch) {
                val samePending = (state.pendingSwitchName == nearest.name)
                val nextCount = if (samePending) state.pendingCount + 1 else 1

                if (nextCount >= 2) {
                    newState = State(
                        currentName = nearest.name,
                        currentLine = nearest.line,
                        pendingSwitchName = null,
                        pendingSwitchLine = null,
                        pendingCount = 0
                    )
                } else {
                    newState = state.copy(
                        pendingSwitchName = nearest.name,
                        pendingSwitchLine = nearest.line,
                        pendingCount = nextCount
                    )
                }
            } else {
                // 条件を満たさないなら保留をリセット
                newState = state.copy(
                    pendingSwitchName = null,
                    pendingSwitchLine = null,
                    pendingCount = 0
                )
            }
        }

        // 「次」推測：同一路線を優先して、nearestのnext/prevも使う
        val nextName = pickNextName(
            prevLatLon = prevLatLon,
            curLatLon = curLatLon,
            candidates = candidates,
            currentLine = newState.currentLine
        )

        return Result(newState.currentName, nextName, newState)
    }

    private fun pickNextName(
        prevLatLon: Pair<Double, Double>?,
        curLatLon: Pair<Double, Double>,
        candidates: List<StationCandidate>,
        currentLine: String?
    ): String? {
        // まず同一路線だけに絞る（取れない/空なら全体）
        val pool = if (!currentLine.isNullOrBlank()) {
            candidates.filter { it.line == currentLine }.ifEmpty { candidates }
        } else candidates

        // prevが無いなら単純に最寄りの next を返す
        val nearest = pool.minByOrNull { distMetersOrInf(it) } ?: return null
        if (prevLatLon == null) return nearest.next.ifBlank { null }

        // 移動方向に近い方（next/prev）を選ぶ：簡易版
        val (pLat, pLon) = prevLatLon
        val (cLat, cLon) = curLatLon
        val vLat = cLat - pLat
        val vLon = cLon - pLon

        // ほぼ停止なら next を優先
        if (abs(vLat) + abs(vLon) < 1e-6) return nearest.next.ifBlank { null }

        // next/prev の駅名が候補内にあれば、その方向ベクトルで判定
        val nextCand = pool.firstOrNull { it.name == nearest.next }
        val prevCand = pool.firstOrNull { it.name == nearest.prev }

        val scoreNext = nextCand?.let { directionScore(curLatLon, it, vLat, vLon) } ?: Double.NEGATIVE_INFINITY
        val scorePrev = prevCand?.let { directionScore(curLatLon, it, vLat, vLon) } ?: Double.NEGATIVE_INFINITY

        return when {
            scoreNext >= scorePrev -> nearest.next.ifBlank { null }
            else -> nearest.prev.ifBlank { null }
        }
    }

    private fun directionScore(
        curLatLon: Pair<Double, Double>,
        target: StationCandidate,
        vLat: Double,
        vLon: Double
    ): Double {
        val tLat = target.lat ?: return Double.NEGATIVE_INFINITY
        val tLon = target.lon ?: return Double.NEGATIVE_INFINITY
        val dLat = tLat - curLatLon.first
        val dLon = tLon - curLatLon.second

        val vNorm = sqrt(vLat * vLat + vLon * vLon)
        val dNorm = sqrt(dLat * dLat + dLon * dLon)
        if (vNorm == 0.0 || dNorm == 0.0) return Double.NEGATIVE_INFINITY

        // cos類似度（-1..1）
        return (vLat * dLat + vLon * dLon) / (vNorm * dNorm)
    }

    private fun distMetersOrInf(c: StationCandidate): Double {
        val raw = c.distanceRaw.trim()
        if (raw.isEmpty()) return Double.POSITIVE_INFINITY

        // "0.123" は km とみなす（HeartRailsの実態に合わせる）
        raw.toDoubleOrNull()?.let { return it * 1000.0 }

        // "123m" / "0.123km" も一応吸収
        Regex("""^\s*([0-9]+(\.[0-9]+)?)\s*m\s*$""", RegexOption.IGNORE_CASE)
            .matchEntire(raw)?.let { return it.groupValues[1].toDoubleOrNull() ?: Double.POSITIVE_INFINITY }

        Regex("""^\s*([0-9]+(\.[0-9]+)?)\s*km\s*$""", RegexOption.IGNORE_CASE)
            .matchEntire(raw)?.let {
                val v = it.groupValues[1].toDoubleOrNull() ?: return Double.POSITIVE_INFINITY
                return v * 1000.0
            }

        return Double.POSITIVE_INFINITY
    }
}
