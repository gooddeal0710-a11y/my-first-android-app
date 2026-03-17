package com.example.neareststationnotifier

data class StationCandidate(
    val name: String,
    val line: String,
    val company: String,
    val distanceRaw: String,
    val next: String,
    val prev: String,
    val lat: Double?,
    val lon: Double?
)
