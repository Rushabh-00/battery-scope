package com.batteryscope.app.battery

import kotlin.math.abs
import kotlin.math.max

/** Chooses remaining-charge deltas conservatively, falling back to current integration on fuel-gauge jumps. */
object ChargeDeltaEstimator {
    private const val MIN_RELIABLE_REMAINING_DELTA_MAH = 0.05
    private const val MIN_ALLOWED_JUMP_MAH = 250.0
    private const val CURRENT_HEADROOM_MULTIPLIER = 4.0
    private const val CURRENT_HEADROOM_MAH = 50.0

    fun estimate(
        remainingDeltaMah: Double,
        currentA: Double?,
        deltaMs: Long,
        charging: Boolean,
    ): Double {
        val safeDeltaMs = deltaMs.coerceAtLeast(0L)
        val hours = safeDeltaMs / 3_600_000.0
        val currentBasedMah = currentA
            ?.let { abs(it) * hours }
            ?.takeIf { it.isFinite() }
            ?: 0.0

        val signedRemainingDelta = if (charging) remainingDeltaMah else -remainingDeltaMah
        if (signedRemainingDelta > MIN_RELIABLE_REMAINING_DELTA_MAH &&
            signedRemainingDelta <= max(MIN_ALLOWED_JUMP_MAH, currentBasedMah * CURRENT_HEADROOM_MULTIPLIER + CURRENT_HEADROOM_MAH)
        ) {
            return signedRemainingDelta
        }

        return currentBasedMah
    }
}
