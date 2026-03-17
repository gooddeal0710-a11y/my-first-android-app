package com.example.neareststationnotifier

import kotlin.math.*

class NextStationPredictor(
    private val enterRadiusM: Double = 120.0,
    private val exitRadiusM: Double = 180.0,
    private val switchMarginM: Double = 80.0,
    private val trainSpeedThreshMps: Double = 5.0, // 約18km/h
    private val wDir: Double = 0.60,
    private val wDist: Double = 0.40,
    private val otherLinePenaltyTrain: Double = 0.60,
    private val backwardPenaltyTrain: Double = 0.50
) {
    data class State(
        val currentName: String? = null,
        val currentLines: Set<String> = emptySet(),
        val pendingSwitchName: String? = null,
        val pendingCount: Int = 0
    )

    data class Result(
        val currentName: String?,
        val nextName: String?,
        val state: State,
        val debugText: String = ""
    )

    fun predict(
        prevLatLon: Pair<Double, Double>?,
        curLatLon: Pair<Double, Double>,
        candidates: List<StationCandidate>,
        state: State,
        speedMps: Double? = null,
        bearingDeg: Double? = null,
        accuracyM: Double? = null
    ): Result {
        if (candidates.isEmpty()) return Result(state.currentName, null, state, "dbg: no candidates")

        val nearest = candidates.minByOrNull { distM(curLatLon, it) }!!
        val nearestDist = distM(curLatLon, nearest)

        val currentDist = state.currentName?.let { curName ->
            candidates.firstOrNull { it.name == curName }?.let { distM(curLatLon, it) }
        } ?: Double.POSITIVE_INFINITY

        val trainMode = (speedMps ?: 0.0) >= trainSpeedThreshMps

        val fwdBearing = when {
            bearingDeg != null && !bearingDeg.isNaN() -> bearingDeg
            prevLatLon != null -> bearingFrom(prevLatLon, curLatLon)
            else -> null
        }

        // 現在駅の路線群：同名駅の候補から全部拾う（表記揺れ対策）
        fun linesForStationName(name: String): Set<String> =
            candidates.asSequence()
                .filter { it.name == name }
                .map { normalizeLine(it.line) }
                .filter { it.isNotBlank() }
                .toSet()

        var newState = state
        var decision = "keep"
        var pend = 0

        if (state.currentName == null) {
            if (nearestDist <= enterRadiusM) {
                newState = State(
                    currentName = nearest.name,
                    currentLines = linesForStationName(nearest.name),
                    pendingSwitchName = null,
                    pendingCount = 0
                )
                decision = "set_current_enter"
            } else {
                newState = state.copy(
                    pendingSwitchName = nearest.name,
                    pendingCount = 1
                )
                decision = "pending_init"
            }
        } else {
            if (currentDist <= exitRadiusM) {
                newState = state.copy(
                    pendingSwitchName = null,
                    pendingCount = 0
                )
                decision = "keep_hysteresis"
            } else {
                val needSwitch = (nearest.name != state.currentName) && (nearestDist + switchMarginM < currentDist)
                if (needSwitch) {
                    val same = (state.pendingSwitchName == nearest.name)
                    val nextCount = if (same) state.pendingCount + 1 else 1
                    pend = nextCount
                    val confirmTimes = if (trainMode) 1 else 2

                    if (nextCount >= confirmTimes) {
                        newState = State(
                            currentName = nearest.name,
                            currentLines = linesForStationName(nearest.name),
                            pendingSwitchName = null,
                            pendingCount = 0
                        )
                        decision = "switch_confirmed"
                    } else {
                        newState = state.copy(
                            pendingSwitchName = nearest.name,
                            pendingCount = nextCount
                        )
                        decision = "switch_pending"
                    }
                } else {
                    newState = state.copy(
                        pendingSwitchName = null,
                        pendingCount = 0
                    )
                    decision = "keep_reset"
                }
            }
        }

        val nextName = pickNextForward(
            curLatLon = curLatLon,
            candidates = candidates,
            currentName = newState.currentName,
            currentLines = newState.currentLines,
            fwdBearing = fwdBearing,
            trainMode = trainMode
        )

        val dbg = buildString {
            append("dbg lines=").append(if (newState.currentLines.isEmpty()) "--" else newState.currentLines.joinToString("|"))
            append(" train=").append(trainMode)
            append(" nearest=").append(nearest.name).append("@").append(nearest.line)
            append(" nd=").append(nearestDist.toInt()).append("m")
            append(" cd=").append(if (currentDist.isFinite()) currentDist.toInt() else -1).append("m")
            append(" pend=").append(pend)
            append(" dec=").append(decision)
            if (fwdBearing != null) append(" br=").append("%.1f".format(fwdBearing))
            if (speedMps != null) append(" sp=").append("%.1f".format(speedMps))
            if (accuracyM != null) append(" acc=").append("%.0f".format(accuracyM))
        }

        return Result(newState.currentName, nextName, newState, dbg)
    }

    private fun pickNextForward(
        curLatLon: Pair<Double, Double>,
        candidates: List<StationCandidate>,
        currentName: String?,
        currentLines: Set<String>,
        fwdBearing: Double?,
        trainMode: Boolean
    ): String? {
        val basePool = candidates.filter { it.name != currentName }
        if (basePool.isEmpty()) return null

        val normLines = currentLines.map { normalizeLine(it) }.filter { it.isNotBlank() }.toSet()

        // 「現在駅の路線群」に一致する候補を優先
        val sameLinePool = if (normLines.isNotEmpty()) {
            basePool.filter { normalizeLine(it.line) in normLines }
        } else emptyList()

        val pool = if (sameLinePool.isNotEmpty()) sameLinePool else basePool

        val dists = pool.map { distM(curLatLon, it) }.filter { it.isFinite() }
        val maxDist = dists.maxOrNull() ?: 1.0

        var best: StationCandidate? = null
        var bestScore = Double.NEGATIVE_INFINITY

        for (c in pool) {
            val d = distM(curLatLon, c)
            val distScore = if (d.isFinite()) 1.0 - (d / maxDist).coerceIn(0.0, 1.0) else 0.0

            val dirScore = if (fwdBearing != null && c.lat != null && c.lon != null) {
                val toBr = bearingFrom(curLatLon, Pair(c.lat, c.lon))
                val diff = angleDiffDeg(fwdBearing, toBr)
                cos(Math.toRadians(diff))
            } else 0.0

            var score = wDir * dirScore + wDist * distScore

            // 電車中は「路線違い」「後方」を強く抑制（フォールバック時の暴れ防止）
            if (trainMode && normLines.isNotEmpty()) {
                val sameLine = normalizeLine(c.line) in normLines
                if (!sameLine) score -= otherLinePenaltyTrain
            }
            if (trainMode && dirScore < 0.0) score -= backwardPenaltyTrain

            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        return best?.name
    }

    private fun distM(cur: Pair<Double, Double>, c: StationCandidate): Double {
        val lat = c.lat ?: return Double.POSITIVE_INFINITY
        val lon = c.lon ?: return Double.POSITIVE_INFINITY
        val dLat = (lat - cur.first) * 111_320.0
        val dLon = (lon - cur.second) * 111_320.0 * cos(Math.toRadians(cur.first))
        return sqrt(dLat * dLat + dLon * dLon)
    }

    private fun bearingFrom(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val lat1 = Math.toRadians(a.first)
        val lat2 = Math.toRadians(b.first)
        val dLon = Math.toRadians(b.second - a.second)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        var br = Math.toDegrees(atan2(y, x))
        if (br < 0) br += 360.0
        return br
    }

    private fun angleDiffDeg(a: Double, b: Double): Double {
        val d = (a - b + 540.0) % 360.0 - 180.0
        return abs(d)
    }

    private fun normalizeLine(s: String): String =
        s.lowercase()
            .replace("ＪＲ", "jr")
            .replace("（", "(").replace("）", ")")
            .replace(" ", "")
            .trim()
}
