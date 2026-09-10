package com.batteryscope.app.battery

data class BatterySnapshot(
    val levelPercent: Int,
    val charging: Boolean,
    val full: Boolean,
    val voltageV: Double?,
    val currentA: Double?,
    val temperatureC: Double?,
    val remainingMah: Double?,
    val batteryCapacityMah: Double?,
    val estimatedCapacityMah: Double?,
    val powerW: Double?,
    val energyWh: Double?,
    val sessionAnalysis: BatterySessionAnalyzer.Analysis? = null,
)
