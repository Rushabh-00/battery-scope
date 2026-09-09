package com.batteryscope.app.battery

/** Calculates health from the design capacity and learned full-charge capacity. */
object BatteryHealthCalculator {
    data class Result(
        val healthPercent: Double?,
        val wearMah: Double?,
    )

    fun calculate(designCapacityMah: Double?, fullChargeCapacitiesMah: List<Double>): Result {
        val design = designCapacityMah?.takeIf { it > 0.0 } ?: return Result(null, null)
        val samples = fullChargeCapacitiesMah.takeLast(5).filter { it > 0.0 }
        if (samples.isEmpty()) return Result(null, null)

        val estimated = samples.average()
        val health = (estimated / design * 100.0).coerceIn(0.0, 100.0)
        val wear = (design - estimated).coerceAtLeast(0.0)
        return Result(health, wear)
    }
}
