package com.batteryscope.app.battery

/** Calculates robust health from quality-weighted full-charge capacity measurements. */
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
        return calculateFromWeightedSamples(design, samples.map { WeightedSample(it, 1.0) })
    }

    fun calculate(
        designCapacityMah: Double?,
        fullChargeSessions: List<CapacitySessionTracker.FullChargeSession>,
    ): Result {
        val design = designCapacityMah?.takeIf { it > 0.0 } ?: return Result(null, 0, null)
        val sessions = fullChargeSessions.takeLast(MAX_SAMPLES)
            .filter { it.estimatedCapacityMah > 0.0 && it.estimatedCapacityMah.isFinite() }
        if (sessions.isEmpty()) return Result(null, 0, null)
        return calculateFromWeightedSamples(
            design,
            sessions.map { WeightedSample(it.estimatedCapacityMah, (it.qualityPercent / 100.0).coerceIn(0.25, 1.0)) },
        )
    }

    private fun calculateFromWeightedSamples(design: Double, samples: List<WeightedSample>): Result {
        val sorted = samples.sortedBy { it.capacityMah }
        val totalWeight = sorted.sumOf { it.weight }
        var accumulated = 0.0
        val estimated = sorted.firstOrNull { sample ->
            accumulated += sample.weight
            accumulated >= totalWeight / 2.0
        }?.capacityMah ?: sorted.last().capacityMah
        val averageQuality = (samples.sumOf { it.weight } / samples.size).coerceIn(0.25, 1.0)
        val countConfidence = when (samples.size) {
            1 -> 25
            2 -> 50
            3 -> 70
            4 -> 85
            else -> 100
        }
        val confidence = (countConfidence * (0.6 + 0.4 * averageQuality)).toInt().coerceIn(0, 100)
        val health = (estimated / design * 100.0).coerceIn(0.0, 100.0)
        val wear = (design - estimated).coerceAtLeast(0.0)
        return Result(health, confidence, wear)
    }

    private data class WeightedSample(val capacityMah: Double, val weight: Double)

    private const val MAX_SAMPLES = 5
}
