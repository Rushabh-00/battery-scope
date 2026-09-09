package com.batteryscope.app.battery

import android.os.BatteryManager
import java.io.File
import kotlin.math.abs
import kotlin.math.ln

class CurrentReader(private val batteryManager: BatteryManager?) {
    fun readAmps(): Double? {
        val rawCandidates = buildList {
            readProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.let { add(it.toDouble()) }
            readProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)?.let { add(it.toDouble()) }
            readSysfsRaw()?.let { add(it) }
        }

        return rawCandidates.asSequence()
            .mapNotNull(::normalizeRawCurrent)
            .filter { it.isFinite() && it in MIN_PLAUSIBLE_AMPS..MAX_PLAUSIBLE_AMPS }
            .minByOrNull(::plausibilityScore)
    }

    private fun readProperty(property: Int): Long? =
        batteryManager?.getLongProperty(property)?.takeUnless { it == Long.MIN_VALUE || it == 0L }

    private fun readSysfsRaw(): Double? {
        val paths = listOf(
            "/sys/class/power_supply/battery/current_now",
            "/sys/class/power_supply/BAT0/current_now",
            "/sys/class/power_supply/BATT/current_now",
        )
        return paths.asSequence()
            .mapNotNull { path ->
                File(path).takeIf { it.isFile && it.canRead() }
                    ?.readText()?.trim()?.toDoubleOrNull()
            }
            .firstOrNull { it != 0.0 }
    }

    /**
     * Battery current properties are not consistent across all devices.
     * Try common unit conversions plus the requested correction factors.
     * The score prefers realistic battery-current magnitudes and rejects
     * values that would clearly be sensor/unit noise.
     */
    private fun normalizeRawCurrent(raw: Double): Double? {
        val magnitude = abs(raw)
        if (!magnitude.isFinite() || magnitude == 0.0) return null

        val unitScales = doubleArrayOf(
            1e-6, // microamps -> amps
            1e-3, // milliamps -> amps
            1.0,  // amps
        )
        val correctionMultipliers = doubleArrayOf(
            0.5,
            1.0,
            2.0,
            1000.0,
        )

        return unitScales.asSequence()
            .flatMap { scale ->
                correctionMultipliers.asSequence().map { multiplier ->
                    magnitude * scale * multiplier
                }
            }
            .filter { it.isFinite() && it in MIN_PLAUSIBLE_AMPS..MAX_PLAUSIBLE_AMPS }
            .minByOrNull(::plausibilityScore)
    }

    private fun plausibilityScore(amps: Double): Double {
        if (amps in 0.05..5.0) {
            return abs(ln(amps))
        }
        return 10.0 + abs(ln(amps / 1.0))
    }

    companion object {
        private const val MIN_PLAUSIBLE_AMPS = 0.01
        private const val MAX_PLAUSIBLE_AMPS = 10.0
    }
}
