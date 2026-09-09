package com.batteryscope.app.battery

import android.content.Context
import kotlin.math.roundToLong

/** Learns a stable full-charge estimate from repeated charge-counter/level samples. */
class CapacityEstimator(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun estimate(remainingMah: Double?, levelPercent: Int, directFullMah: Double?): Double? {
        if (directFullMah != null && directFullMah > 0.0) {
            persistEstimate(directFullMah)
            return directFullMah
        }

        val remaining = remainingMah ?: return storedEstimate()
        if (levelPercent !in 20..95) return storedEstimate()

        val candidate = remaining * 100.0 / levelPercent
        if (!candidate.isFinite() || candidate !in MIN_MAH..MAX_MAH) return storedEstimate()

        val samples = loadSamples().toMutableList()
        samples += candidate
        while (samples.size > MAX_SAMPLES) samples.removeAt(0)
        saveSamples(samples)

        // Median makes the estimate resistant to a noisy single level/counter sample.
        val sorted = samples.sorted()
        val median = if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        }
        val rounded = median.roundToLong().toDouble()
        persistEstimate(rounded)
        return rounded
    }

    private fun persistEstimate(value: Double) {
        preferences.edit().putLong(KEY_ESTIMATE, value.roundToLong()).apply()
    }

    private fun storedEstimate(): Double? =
        if (preferences.contains(KEY_ESTIMATE)) preferences.getLong(KEY_ESTIMATE, 0L).toDouble().takeIf { it > 0.0 }
        else null

    private fun loadSamples(): List<Double> {
        val encoded = preferences.getString(KEY_SAMPLES, null) ?: return emptyList()
        return encoded.split(',').mapNotNull { it.toDoubleOrNull() }.filter { it in MIN_MAH..MAX_MAH }
    }

    private fun saveSamples(samples: List<Double>) {
        preferences.edit().putString(KEY_SAMPLES, samples.joinToString(",")).apply()
    }

    companion object {
        private const val FILE_NAME = "battery_scope_capacity"
        private const val KEY_ESTIMATE = "estimate_mah"
        private const val KEY_SAMPLES = "samples_mah"
        private const val MAX_SAMPLES = 31
        private const val MIN_MAH = 500.0
        private const val MAX_MAH = 30_000.0
    }
}
