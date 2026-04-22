package com.example.neareststationnotifier

import kotlin.math.cos

object NextStationSelection {

    fun pickNextByAdjacency(
        curLatLon: Pair<Double, Double>,
        candidates: List<StationCandidate>,
        currentName: String?,
        currentLine: String?,
        fwdBearing: Double?,
        lastName: String?
    ): String? {
        if (currentName.isNullOrBlank()) return null

        val normCurrentName = GeoLineUtils.normalizeStationName(currentName)
        val normLastName = lastName?.let { GeoLineUtils.normalizeStationName(it) }

        val normLine = currentLine
            ?.let { GeoLineUtils.normalizeLine(it) }
            ?.takeIf { it.isNotBlank() }

        val currentRecords = candidates.filter {
            GeoLineUtils.normalizeStationName(it.name) == normCurrentName
        }

        val sameLineCurrentRecords = if (normLine != null) {
            currentRecords.filter { GeoLineUtils.normalizeLine(it.line) == normLine }
        } else {
            currentRecords
        }

        val sourceRecords = when {
            sameLineCurrentRecords.isNotEmpty() -> sameLineCurrentRecords
            currentRecords.isNotEmpty() -> currentRecords
            else -> return null
        }

        val options = sourceRecords
            .flatMap { rec ->
                listOfNotNull(
                    rec.next?.trim()?.takeIf { it.isNotEmpty() && it != "-" },
                    rec.prev?.trim()?.takeIf { it.isNotEmpty() && it != "-" }
                )
            }
            .distinct()
            .filter { name ->
                val norm = GeoLineUtils.normalizeStationName(name)
                norm != normCurrentName && norm != normLastName
            }

        if (options.isEmpty()) return null

        val optionCandidates = options.mapNotNull { name ->
            val normOptionName = GeoLineUtils.normalizeStationName(name)

            val sameLine = if (normLine != null) {
                candidates.filter {
                    GeoLineUtils.normalizeStationName(it.name) == normOptionName &&
                        GeoLineUtils.normalizeLine(it.line) == normLine
                }
            } else {
                emptyList()
            }

            when {
                sameLine.isNotEmpty() -> sameLine.minByOrNull { GeoLineUtils.distM(curLatLon, it) }
                normLine != null -> null
                else -> candidates
                    .filter { GeoLineUtils.normalizeStationName(it.name) == normOptionName }
                    .minByOrNull { GeoLineUtils.distM(curLatLon, it) }
            }
        }

        if (optionCandidates.isEmpty()) return null

        if (fwdBearing == null) {
            return optionCandidates.minByOrNull { GeoLineUtils.distM(curLatLon, it) }?.name
        }

        val best = optionCandidates
            .mapNotNull { c ->
                val lat = c.lat ?: return@mapNotNull null
                val lon = c.lon ?: return@mapNotNull null
                val toBr = GeoLineUtils.bearingFrom(curLatLon, Pair(lat, lon))
                val diff = GeoLineUtils.angleDiffDeg(fwdBearing, toBr)
                val score = cos(Math.toRadians(diff))
                Triple(c, diff, score)
            }
            .filter { (_, diff, _) -> diff <= 70.0 }
            .maxByOrNull { (_, _, score) -> score }

        return best?.first?.name
    }

    fun pickNextForward(
        curLatLon: Pair<Double, Double>,
        candidates: List<StationCandidate>,
        currentName: String?,
        currentLine: String?,
        currentLines: Set<String>,
        lastName: String?,
        fwdBearing: Double?,
        trainMode: Boolean,
        wDir: Double,
        wDist: Double,
        otherLinePenaltySlow: Double,
        otherLinePenaltyTrain: Double,
        backwardPenaltyTrain: Double
    ): String? {
        val normCurrentName = currentName?.let { GeoLineUtils.normalizeStationName(it) }
        val normLastName = lastName?.let { GeoLineUtils.normalizeStationName(it) }

        val basePool = candidates.filter {
            val norm = GeoLineUtils.normalizeStationName(it.name)
            norm != normCurrentName && norm != normLastName
        }
        if (basePool.isEmpty()) return null

        val normCurrent = currentLine
            ?.let { GeoLineUtils.normalizeLine(it) }
            ?.takeIf { it.isNotBlank() }

        val normLines = currentLines
            .map { GeoLineUtils.normalizeLine(it) }
            .filter { it.isNotBlank() }
            .toSet()

        val sameLinePool = if (normCurrent != null) {
            basePool.filter { GeoLineUtils.normalizeLine(it.line) == normCurrent }
        } else if (normLines.isNotEmpty()) {
            basePool.filter { GeoLineUtils.normalizeLine(it.line) in normLines }
        } else {
            emptyList()
        }

        if (trainMode && normCurrent != null && sameLinePool.isEmpty()) {
            return null
        }

        val pool = if (sameLinePool.isNotEmpty()) sameLinePool else basePool

        val dists = pool.map { GeoLineUtils.distM(curLatLon, it) }.filter { it.isFinite() }
        val maxDist = dists.maxOrNull() ?: 1.0

        var best: StationCandidate? = null
        var bestScore = Double.NEGATIVE_INFINITY

        for (c in pool) {
            val d = GeoLineUtils.distM(curLatLon, c)
            val distScore = if (d.isFinite()) {
                1.0 - (d / maxDist).coerceIn(0.0, 1.0)
            } else {
                0.0
            }

            val dirScore = if (fwdBearing != null && c.lat != null && c.lon != null) {
                val toBr = GeoLineUtils.bearingFrom(curLatLon, Pair(c.lat, c.lon))
                val diff = GeoLineUtils.angleDiffDeg(fwdBearing, toBr)
                cos(Math.toRadians(diff))
            } else {
                0.0
            }

            var score = wDir * dirScore + wDist * distScore

            if (sameLinePool.isEmpty()) {
                if (normCurrent != null) {
                    val same = GeoLineUtils.normalizeLine(c.line) == normCurrent
                    if (!same) score -= if (trainMode) otherLinePenaltyTrain else otherLinePenaltySlow
                } else if (normLines.isNotEmpty()) {
                    val same = GeoLineUtils.normalizeLine(c.line) in normLines
                    if (!same) score -= if (trainMode) otherLinePenaltyTrain else otherLinePenaltySlow
                }
            }

            if (trainMode && dirScore < 0.0) {
                score -= backwardPenaltyTrain
            }

            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }

        return best?.name
    }
}
