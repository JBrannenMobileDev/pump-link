package dev.pumplink.simulator

data class SafetyLimits(
    val maxBolusMilliunits: Int = 25_000,
    val incrementMilliunits: Int = 50,
    val defaultReservoirMilliunits: Int = 200_000,
    val maxDurationSeconds: Int = 600,
)
