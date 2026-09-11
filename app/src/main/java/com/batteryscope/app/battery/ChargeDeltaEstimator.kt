package com.batteryscope.app.battery

import kotlin.math.abs
import kotlin.math.max

/** Chooses remaining-charge deltas conservatively, falling back to current integration on fuel-gauge jumps. */
object ChargeDeltaEstimator {
    data class Measurement(
        val acceptedMah: Double,
        val gaugeMah: Double,
        val currentMah: Double,
    )

    private const val MIN_RELIABLE_REMAINING_DELTA_MAH = 0.05
    private const val MIN_ALLOWED_JUMP_MAH = 250.0
    private const val CURRENT_HEADROOM_MULTIPLIER = 4.0
    private const val CURRENT_HEADROOM_MAH = 50.0
    private const val MILLIAMP_HOURS_PER_AMP_HOUR = 1000.0

    fun estimate(
        remainingDeltaMah: Double,
        currentA: Double?,
        deltaMs: Long,
        charging: Boolean,
    ): Double = measure(remainingDeltaMah, currentA, deltaMs, charging).acceptedMah

    fun measure(
        remainingDeltaMah: Double,
        currentA: Double?,
        deltaMs: Long,
        charging: Boolean,
    ): Measurement {
        val safeDeltaMs = deltaMs.coerceAtLeast(0L)
        val hours = safeDeltaMs / 3_600_000.0
        val currentBasedMah = currentA
            ?.let { abs(it) * hours * MILLIAMP_HOURS_PER_AMP_HOUR }
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?: 0.0

        val signedRemainingDelta = if (charging) remainingDeltaMah else -remainingDeltaMah
        val reliableGaugeMah = signedRemainingDelta.takeIf {
            it > MIN_RELIABLE_REMAINING_DELTA_MAH &&
                it <= max(MIN_ALLOWED_JUMP_MAH, currentBasedMah * CURRENT_HEADROOM_MULTIPLIER + CURRENT_HEADROOM_MAH)
        } ?: 0.0

        return Measurement(
            acceptedMah = if (reliableGaugeMah > 0.0) reliableGaugeMah else currentBasedMah,
            gaugeMah = reliableGaugeMah,
            currentMah = currentBasedMah,
        )
    }
}
