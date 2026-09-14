package com.batteryscope.app.battery

import kotlin.math.abs
import kotlin.math.roundToInt

/** Robust battery health from validated charge sessions with fuel-gauge capacity used only as corroboration. */
object BatteryHealthCalculator {
    data class Result(val healthPercent: Double?, val confidencePercent: Int, val wearMah: Double?)

    fun calculate(designCapacityMah: Double?, fullChargeCapacitiesMah: List<Double>): Result {
        val design = designCapacityMah?.takeIf { it > 0.0 } ?: return Result(null, 0, null)
        val samples = fullChargeCapacitiesMah.takeLast(MAX_SAMPLES).filter { it > 0.0 && it.isFinite() }
        if (samples.isEmpty()) return Result(null, 0, null)
        return calculateFromWeightedSamples(design, samples.map { WeightedSample(it, 1.0) }, true)
    }

    fun calculateFromSessions(
        designCapacityMah: Double?,
        fullChargeSessions: List<CapacitySessionTracker.FullChargeSession>,
    ): Result {
        val design = designCapacityMah?.takeIf { it > 0.0 } ?: return Result(null, 0, null)
        val sessions = fullChargeSessions.takeLast(MAX_SAMPLES).filter {
            it.estimatedCapacityMah.isFinite() && it.estimatedCapacityMah in design * MIN_SESSION_RATIO..design * MAX_SESSION_RATIO
        }
        if (sessions.isEmpty()) return Result(null, 0, null)

        val benchmark = sessions.asReversed().firstOrNull { it.benchmark }
        if (benchmark != null) {
            val health = (benchmark.estimatedCapacityMah / design * 100.0).coerceIn(0.0, 100.0)
            val confidence = (70 + benchmark.qualityPercent * 0.20).roundToInt().coerceIn(70, 90)
            return Result(health, confidence, (design - benchmark.estimatedCapacityMah).coerceAtLeast(0.0))
        }

        val normal = sessions.filterNot { it.benchmark }
        if (normal.size < MIN_INDEPENDENT_SESSIONS) return Result(null, 0, null)
        return calculateFromWeightedSamples(
            design,
            normal.map { WeightedSample(it.estimatedCapacityMah, (it.qualityPercent / 100.0).coerceIn(0.25, 1.0)) },
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
        val session = calculateFromSessions(design, fullChargeSessions)
        if (session.healthPercent == null) return session

        val gauge = gaugeFullChargeMah?.takeIf {
            it.isFinite() && it in design * MIN_GAUGE_RATIO..design * MAX_GAUGE_RATIO
        }
        if (gauge == null) return session

        val gaugeHealth = gauge / design * 100.0
        val agreementConfidenceBoost = if (abs(session.healthPercent - gaugeHealth) <= 12.0) 5 else 0
        val gaugeErrorBoost = gaugeErrorMarginPercent?.let { (5 - it / 20).coerceIn(0, 5) } ?: 0
        return session.copy(confidencePercent = (session.confidencePercent + agreementConfidenceBoost + gaugeErrorBoost).coerceAtMost(95))
    }

    private fun calculateFromWeightedSamples(
        design: Double,
        samples: List<WeightedSample>,
        useEvenMedian: Boolean,
    ): Result {
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
        val confidence = (countConfidence * (0.6 + 0.4 * averageQuality)).roundToInt().coerceIn(0, 100)
        val health = (estimated / design * 100.0).coerceIn(0.0, 100.0)
        val wear = (design - estimated).coerceAtLeast(0.0)
        return Result(health, confidence, wear)
    }

    private data class WeightedSample(val capacityMah: Double, val weight: Double)
    private const val MAX_SAMPLES = 5
    private const val MIN_INDEPENDENT_SESSIONS = 2
    private const val MIN_SESSION_RATIO = 0.35
    private const val MAX_SESSION_RATIO = 1.10
    private const val MIN_GAUGE_RATIO = 0.50
    private const val MAX_GAUGE_RATIO = 1.10
}
