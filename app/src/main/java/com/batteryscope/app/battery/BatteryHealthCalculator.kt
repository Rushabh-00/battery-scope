package com.batteryscope.app.battery

/** Robust battery health from fuel-gauge capacity or validated charge sessions. */
object BatteryHealthCalculator {
    data class Result(val healthPercent: Double?, val confidencePercent: Int, val wearMah: Double?)

    fun calculate(designCapacityMah: Double?, fullChargeCapacitiesMah: List<Double>): Result {
        val design = designCapacityMah?.takeIf { it > 0.0 } ?: return Result(null, 0, null)
        val samples = fullChargeCapacitiesMah.takeLast(MAX_SAMPLES).filter { it > 0.0 && it.isFinite() }
        if (samples.isEmpty()) return Result(null, 0, null)
        return calculateFromWeightedSamples(design, samples.map { WeightedSample(it, 1.0) }, true)
    }

    fun calculateFromSessions(designCapacityMah: Double?, fullChargeSessions: List<CapacitySessionTracker.FullChargeSession>): Result {
        val design = designCapacityMah?.takeIf { it > 0.0 } ?: return Result(null, 0, null)
        val sessions = fullChargeSessions.takeLast(MAX_SAMPLES).filter {
            it.estimatedCapacityMah in design * MIN_SESSION_RATIO..design * MAX_SESSION_RATIO
        }
        if (sessions.isEmpty()) return Result(null, 0, null)
        return calculateFromWeightedSamples(
            design,
            sessions.map { WeightedSample(it.estimatedCapacityMah, (it.qualityPercent / 100.0).coerceIn(0.25, 1.0)) },
            false,
        )
    }

    fun calculateFromSources(
        designCapacityMah: Double?,
        gaugeFullChargeMah: Double?,
        gaugeErrorMarginPercent: Int?,
        fullChargeSessions: List<CapacitySessionTracker.FullChargeSession>,
    ): Result {
        val design = designCapacityMah?.takeIf { it > 0.0 } ?: return Result(null, 0, null)
        val gauge = gaugeFullChargeMah?.takeIf { it.isFinite() && it in design * MIN_GAUGE_RATIO..design * MAX_GAUGE_RATIO }
        if (gauge != null) {
            val error = gaugeErrorMarginPercent?.coerceIn(0, 100)
            var confidence = if (error == null) 70 else (90 - error / 2).coerceIn(30, 90)
            val session = calculateFromSessions(design, fullChargeSessions)
            if (session.healthPercent != null && kotlin.math.abs(session.healthPercent - gauge / design * 100.0) <= 12.0) {
                confidence = (confidence + 10).coerceAtMost(95)
            }
            val health = (gauge / design * 100.0).coerceIn(0.0, 100.0)
            return Result(health, confidence, (design - gauge).coerceAtLeast(0.0))
        }
        return calculateFromSessions(design, fullChargeSessions)
    }

    private fun calculateFromWeightedSamples(design: Double, samples: List<WeightedSample>, useEvenMedian: Boolean): Result {
        val sorted = samples.sortedBy { it.capacityMah }
        val totalWeight = sorted.sumOf { it.weight }
        var accumulated = 0.0
        val estimated = if (useEvenMedian && sorted.size % 2 == 0) {
            val middle = sorted.size / 2
            (sorted[middle - 1].capacityMah + sorted[middle].capacityMah) / 2.0
        } else {
            sorted.firstOrNull {
                accumulated += it.weight
                accumulated >= totalWeight / 2.0
            }?.capacityMah ?: sorted.last().capacityMah
        }
        val averageQuality = (samples.sumOf { it.weight } / samples.size).coerceIn(0.25, 1.0)
        val countConfidence = when (samples.size) { 1 -> 20; 2 -> 40; 3 -> 60; 4 -> 80; else -> 100 }
        val confidence = (countConfidence * (0.6 + 0.4 * averageQuality)).toInt().coerceIn(0, 100)
        val health = (estimated / design * 100.0).coerceIn(0.0, 100.0)
        val wear = (design - estimated).coerceAtLeast(0.0)
        return Result(health, confidence, wear)
    }

    private data class WeightedSample(val capacityMah: Double, val weight: Double)
    private const val MAX_SAMPLES = 5
    private const val MIN_SESSION_RATIO = 0.35
    private const val MAX_SESSION_RATIO = 1.10
    private const val MIN_GAUGE_RATIO = 0.50
    private const val MAX_GAUGE_RATIO = 1.10
}
