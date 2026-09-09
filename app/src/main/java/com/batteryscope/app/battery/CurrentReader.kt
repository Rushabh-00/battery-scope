package com.batteryscope.app.battery

import android.os.BatteryManager
import java.io.File
import kotlin.math.abs

class CurrentReader(private val batteryManager: BatteryManager?) {
    private var lastAmps: Double? = null

    fun readAmps(): Double? {
        val rawCandidates = buildList {
            readProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.let { add(it.toDouble()) }
            readProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)?.let { add(it.toDouble()) }
            readSysfsRaw()?.let { add(it) }
        }

        val readings = rawCandidates.flatMap(::normalizeCandidates)
        if (readings.isEmpty()) return null

        val selected = readings.minWithOrNull(
            compareBy<Candidate>({ score(it) }, { it.correctionPenalty })
        )?.amps

        if (selected != null && selected.isFinite()) {
            lastAmps = selected
            return selected
        }
        return null
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

    private data class Candidate(
        val amps: Double,
        val correctionPenalty: Int,
    )

    /**
     * Try common raw units first. When the normal interpretation is near
     * zero, also test correction factors such as 0.5x, 1x, 2x and 1000x.
     */
    private fun normalizeCandidates(raw: Double): List<Candidate> {
        val magnitude = abs(raw)
        if (!magnitude.isFinite() || magnitude == 0.0) return emptyList()

        val base = when {
            magnitude >= 100_000.0 -> magnitude / 1_000_000.0
            magnitude >= 100.0 -> magnitude / 1_000.0
            else -> magnitude
        }

        val multipliers = if (base < MIN_USEFUL_AMPS || base > MAX_PLAUSIBLE_AMPS) {
            doubleArrayOf(0.5, 1.0, 2.0, 1000.0)
        } else {
            doubleArrayOf(1.0, 0.5, 2.0, 1000.0)
        }

        return multipliers.mapIndexedNotNull { index, multiplier ->
            val amps = base * multiplier
            amps.takeIf { it.isFinite() && it in MIN_PLAUSIBLE_AMPS..MAX_PLAUSIBLE_AMPS }
                ?.let { Candidate(it, index) }
        }
    }

    private fun score(candidate: Candidate): Double {
        val amps = candidate.amps
        val continuity = lastAmps?.let { previous ->
            if (previous > 0.0) abs(amps - previous) / maxOf(previous, 0.05) else 0.0
        } ?: 0.0

        val usefulRangePenalty = when {
            amps in 0.05..5.0 -> 0.0
            amps < 0.05 -> 3.0
            else -> 3.0 + (amps - 5.0)
        }
        return continuity + usefulRangePenalty
    }

    companion object {
        private const val MIN_PLAUSIBLE_AMPS = 0.005
        private const val MIN_USEFUL_AMPS = 0.005
        private const val MAX_PLAUSIBLE_AMPS = 10.0
    }
}
