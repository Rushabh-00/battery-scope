package com.batteryscope.app.battery

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Rates a completed charge session so noisy measurements have less influence on health. */
object CapacitySessionQuality {
    fun calculate(
        startLevelPercent: Int,
        endLevelPercent: Int,
        chargedMah: Double,
        durationMs: Long,
        gaugeChargedMah: Double,
        currentChargedMah: Double,
    ): Int {
        val span = (endLevelPercent - startLevelPercent).coerceAtLeast(0)
        val startScore = when {
            startLevelPercent <= 5 -> 100.0
            startLevelPercent <= 10 -> 95.0
            startLevelPercent <= 15 -> 85.0
            else -> 50.0
        }
        val spanScore = when {
            span >= 80 -> 100.0
            span >= 60 -> 95.0
            span >= 40 -> 85.0
            span >= 20 -> 70.0
            else -> 0.0
        }
        val chargeScore = when {
            !chargedMah.isFinite() || chargedMah <= 0.0 -> 0.0
            chargedMah >= 250.0 -> 100.0
            else -> chargedMah / 250.0 * 100.0
        }
        val durationMinutes = durationMs.coerceAtLeast(0L) / 60_000.0
        val durationScore = when {
            durationMinutes in 30.0..480.0 -> 100.0
            durationMinutes in 15.0..720.0 -> 85.0
            durationMinutes > 0.0 -> 65.0
            else -> 0.0
        }
        val agreementScore = if (gaugeChargedMah > 0.0 && currentChargedMah > 0.0) {
            val difference = abs(gaugeChargedMah - currentChargedMah)
            (100.0 - difference / max(gaugeChargedMah, currentChargedMah) * 100.0).coerceIn(0.0, 100.0)
        } else {
            55.0
        }

        return (startScore * 0.20 + spanScore * 0.30 + chargeScore * 0.15 + durationScore * 0.15 + agreementScore * 0.20)
            .roundToIntClamped()
    }

    private fun Double.roundToIntClamped(): Int = min(100.0, max(0.0, this)).toInt()
}
