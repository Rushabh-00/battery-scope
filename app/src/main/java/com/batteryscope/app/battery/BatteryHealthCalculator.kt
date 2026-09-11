package com.batteryscope.app.battery

/** Calculates health from design capacity and robust full-charge capacity samples. */
object BatteryHealthCalculator {
    data class Result(
        val healthPercent: Double?,
        val confidencePercent: Int,
        val wearMah: Double?,
    )

    fun calculate(designCapacityMah: Double?, fullChargeCapacitiesMah: List<Double>): Result {
        val design = designCapacityMah?.takeIf { it > 0.0 } ?: return Result(null, 0, null)
        val samples = fullChargeCapacitiesMah.takeLast(MAX_SAMPLES).filter { it > 0.0 && it.isFinite() }
        if (samples.isEmpty()) return Result(null, 0, null)

        val sorted = samples.sorted()
        val middle = sorted.size / 2
        val estimated = if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
        val health = (estimated / design * 100.0).coerceIn(0.0, 100.0)
        val wear = (design - estimated).coerceAtLeast(0.0)
        return Result(health, (samples.size * 100 / MAX_SAMPLES).coerceAtMost(100), wear)
    }

    private const val MAX_SAMPLES = 5
}
