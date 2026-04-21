package com.example.neareststationnotifier

class NextStationPredictorSupport(
    private val candidates: List<StationCandidate>,
    private val curLatLon: Pair<Double, Double>,
    private val trainMode: Boolean
) {
    fun stationRecords(name: String): List<StationCandidate> {
        val nn = GeoLineUtils.normalizeStationName(name)
        return candidates.filter { GeoLineUtils.normalizeStationName(it.name) == nn }
    }

    fun linesForStationName(name: String): Set<String> {
        return stationRecords(name)
            .map { GeoLineUtils.normalizeLine(it.line) }
            .filter { it.isNotBlank() }
            .toSet()
    }

    fun stationHasLine(name: String, line: String?): Boolean {
        val nl = line?.let { GeoLineUtils.normalizeLine(it) }?.takeIf { it.isNotBlank() } ?: return false
        return stationRecords(name).any { GeoLineUtils.normalizeLine(it.line) == nl }
    }

    fun normalizeAdjName(raw: String?): String? {
        val v = raw?.trim()?.takeIf { it.isNotEmpty() && it != "-" } ?: return null
        return GeoLineUtils.normalizeStationName(v)
    }

    fun findStationOnLine(name: String?, line: String?): StationCandidate? {
        if (name.isNullOrBlank() || line.isNullOrBlank()) return null
        val nn = GeoLineUtils.normalizeStationName(name)
        val nl = GeoLineUtils.normalizeLine(line)
        return candidates.firstOrNull {
            GeoLineUtils.normalizeStationName(it.name) == nn &&
                GeoLineUtils.normalizeLine(it.line) == nl
        }
    }

    fun bearingScoreForRecord(record: StationCandidate, moveBearing: Double?): Double {
        if (moveBearing == null || moveBearing.isNaN()) return 0.5

        val dirs = mutableListOf<Double>()

        val prevRec = findStationOnLine(record.prev, record.line)
        val prevPair = nextStationPairOrNull(prevRec)
        val recPair = nextStationPairOrNull(record)
        if (prevPair != null && recPair != null) {
            dirs += GeoLineUtils.bearingFrom(prevPair, recPair)
        }

        val nextRec = findStationOnLine(record.next, record.line)
        val nextPair = nextStationPairOrNull(nextRec)
        if (recPair != null && nextPair != null) {
            dirs += GeoLineUtils.bearingFrom(recPair, nextPair)
        }

        if (dirs.isEmpty()) return 0.5

        val bestDiff = dirs.minOf { nextStationAngleDiffDeg(moveBearing, it) }
        return 1.0 - (bestDiff / 180.0)
    }

    fun distanceScoreForRecord(record: StationCandidate): Double {
        val d = GeoLineUtils.distM(curLatLon, record)
        return 1.0 / (1.0 + d)
    }

    fun choosePrimaryLineForStationName(
        name: String,
        preferredLockedLine: String?,
        preferredPrimaryLine: String?,
        preferredLines: Set<String>,
        moveBearing: Double?,
        trainModeNow: Boolean
    ): String? {
        val records = stationRecords(name)
        if (records.isEmpty()) return null

        val normalizedLocked = preferredLockedLine?.let { GeoLineUtils.normalizeLine(it) }?.takeIf { it.isNotBlank() }
        val normalizedPrimary = preferredPrimaryLine?.let { GeoLineUtils.normalizeLine(it) }?.takeIf { it.isNotBlank() }
        val normalizedPreferredLines = preferredLines
            .map { GeoLineUtils.normalizeLine(it) }
            .filter { it.isNotBlank() }
            .toSet()

        val filtered: List<StationCandidate> = when {
            normalizedLocked != null &&
                records.any { GeoLineUtils.normalizeLine(it.line) == normalizedLocked } ->
                records.filter { GeoLineUtils.normalizeLine(it.line) == normalizedLocked }

            normalizedPrimary != null &&
                records.any { GeoLineUtils.normalizeLine(it.line) == normalizedPrimary } ->
                records.filter { GeoLineUtils.normalizeLine(it.line) == normalizedPrimary }

            normalizedPreferredLines.isNotEmpty() &&
                records.any { GeoLineUtils.normalizeLine(it.line) in normalizedPreferredLines } ->
                records.filter { GeoLineUtils.normalizeLine(it.line) in normalizedPreferredLines }

            else -> records
        }

        val dirWeight = if (trainModeNow) 0.65 else 0.25
        val distWeight = 1.0 - dirWeight

        val best = filtered.maxByOrNull { rec ->
            val b = bearingScoreForRecord(rec, moveBearing)
            val d = distanceScoreForRecord(rec)
            dirWeight * b + distWeight * d
        }

        return best?.line?.let { GeoLineUtils.normalizeLine(it) }?.takeIf { it.isNotBlank() }
    }

    fun chooseRelineForCurrentStation(currentName: String?, moveBearing: Double?): String? {
        if (currentName.isNullOrBlank()) return null

        val records = stationRecords(currentName)
        if (records.isEmpty()) return null

        val dirWeight = if (trainMode) 0.75 else 0.30
        val distWeight = 1.0 - dirWeight

        val best = records.maxByOrNull { rec ->
            val b = bearingScoreForRecord(rec, moveBearing)
            val d = distanceScoreForRecord(rec)
            dirWeight * b + distWeight * d
        }

        return best?.line?.let { GeoLineUtils.normalizeLine(it) }?.takeIf { it.isNotBlank() }
    }

    fun isNaturalTrainSwitch(fromName: String?, toName: String?, line: String?): Boolean {
        if (toName.isNullOrBlank()) return false
        if (fromName.isNullOrBlank()) return true

        val fromNorm = GeoLineUtils.normalizeStationName(fromName)
        val toNorm = GeoLineUtils.normalizeStationName(toName)
        if (fromNorm == toNorm) return true

        val normalizedLine = line?.let { GeoLineUtils.normalizeLine(it) }?.takeIf { it.isNotBlank() } ?: return false

        val fromRecords = stationRecords(fromName)
            .filter { GeoLineUtils.normalizeLine(it.line) == normalizedLine }

        val toRecords = stationRecords(toName)
            .filter { GeoLineUtils.normalizeLine(it.line) == normalizedLine }

        if (fromRecords.isEmpty() || toRecords.isEmpty()) return false

        return fromRecords.any { from ->
            val nextNorm = normalizeAdjName(from.next)
            val prevNorm = normalizeAdjName(from.prev)
            toNorm == nextNorm || toNorm == prevNorm
        }
    }
}
