package com.batteryscope.app.battery

import android.os.BatteryManager
import java.io.File
import kotlin.math.abs

class CurrentReader(
    private val batteryManager: BatteryManager?,
    private val invertChargingPolarity: Boolean
) {
    fun readAmps(): Double? {
        val rawCandidates = buildList {
            readProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.let { add(it.toDouble()) }
            readProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)?.let { add(it.toDouble()) }
            readSysfsRaw()?.let { add(it) }
        }

        val candidates = ArrayList<Candidate>()
        for (raw in rawCandidates) {
            candidates.addAll(normalizeCandidates(raw))
        }
        if (candidates.isEmpty()) return null

        val selected = candidates.minWithOrNull(
            compareBy<Candidate> { score(it) }.thenBy { it.correctionPenalty }
        )?.amps ?: return null

        val signed = if (invertChargingPolarity) -selected else selected
        return if (abs(signed) < 0.0005) 0.0 else signed
    }

    private fun readProperty(property: Int): Long? =
        batteryManager?.getLongProperty(property)?.takeUnless {
            it == Long.MIN_VALUE || it == 0L
        }

    private fun readSysfsRaw(): Double? {
        val paths = listOf(
            "/sys/class/power_supply/battery/current_now",
            "/sys/class/power_supply/BAT0/current_now",
            "/sys/class/power_supply/BATT/current_now",
        )
        for (path in paths) {
            val value = File(path)
                .takeIf { it.isFile && it.canRead() }
                ?.readText()
                ?.trim()
                ?.toDoubleOrNull()
            if (value != null && value != 0.0) return value
        }
        return null
    }

    private data class Candidate(
        val amps: Double,
        val correctionPenalty: Int,
    )

    /** Try common raw unit interpretations while preserving sensor polarity. */
    private fun normalizeCandidates(raw: Double): List<Candidate> {
        val sign = if (raw < 0.0) -1.0 else 1.0
        val magnitude = abs(raw)
        if (!magnitude.isFinite() || magnitude == 0.0) return emptyList()

        val base = when {
            magnitude >= 100_000.0 -> magnitude / 1_000_000.0
            magnitude >= 100.0 -> magnitude / 1_000.0
            else -> magnitude
        }

        val multipliers = doubleArrayOf(1.0, 0.5, 2.0, 1000.0)
        val result = ArrayList<Candidate>(multipliers.size)
        for (index in multipliers.indices) {
            val amps = sign * base * multipliers[index]
            if (abs(amps).isFinite() && abs(amps) in MIN_PLAUSIBLE_AMPS..MAX_PLAUSIBLE_AMPS) {
                result.add(Candidate(amps, index))
            }
        }
        return result
    }

    private fun score(candidate: Candidate): Double {
        val amps = abs(candidate.amps)
        val usefulRangePenalty = when {
            amps in 0.05..5.0 -> 0.0
            amps < 0.05 -> 3.0
            else -> 3.0 + (amps - 5.0)
        }
        return usefulRangePenalty + candidate.correctionPenalty * 0.05
    }

    companion object {
        private const val MIN_PLAUSIBLE_AMPS = 0.005
        private const val MAX_PLAUSIBLE_AMPS = 10.0
    }
}
