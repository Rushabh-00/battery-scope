package com.batteryscope.app.battery

import android.os.BatteryManager
import java.io.File
import kotlin.math.abs

class CurrentReader(
    private val batteryManager: BatteryManager?,
    var invertChargingPolarity: Boolean,
) {
    private val currentFiles = discoverCurrentFiles()
    private val candidates = ArrayList<Candidate>(32)

    fun readAmps(): Double? {
        candidates.clear()
        addPropertyCandidate(candidates, BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        addPropertyCandidate(candidates, BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        readSysfsCandidates(candidates)
        if (candidates.isEmpty()) return null

        val selected = candidates.minWithOrNull(
            compareBy<Candidate> { score(it.absAmps) }.thenBy { it.correctionPenalty }
        ) ?: return null

        var signed = selected.absAmps.copySignFrom(selected.raw)
        if (invertChargingPolarity) signed = -signed
        return if (abs(signed) < 0.0005) 0.0 else signed
    }

    private fun addPropertyCandidate(target: MutableList<Candidate>, property: Int) {
        val value = batteryManager?.getLongProperty(property)
            ?.takeUnless { it == Long.MIN_VALUE || it == 0L }
            ?: return
        addNormalizedCandidates(target, value.toDouble())
    }

    private fun readSysfsCandidates(target: MutableList<Candidate>) {
        for (path in currentFiles) {
            if (!path.isFile || !path.canRead()) continue
            val raw = path.readText().trim().toDoubleOrNull() ?: continue
            if (raw == 0.0) continue
            addNormalizedCandidates(target, raw)
        }
    }

    private data class Candidate(
        val absAmps: Double,
        val raw: Double,
        val correctionPenalty: Int,
    )

    /** Try common Android/sysfs current scales and modest correction factors. */
    private fun addNormalizedCandidates(target: MutableList<Candidate>, raw: Double) {
        val magnitude = abs(raw)
        if (!magnitude.isFinite() || magnitude == 0.0) return

        val base = when {
            magnitude >= 100_000.0 -> magnitude / 1_000_000.0
            magnitude >= 100.0 -> magnitude / 1_000.0
            else -> magnitude
        }
        val multipliers = MULTIPLIERS
        for (index in multipliers.indices) {
            val amps = base * multipliers[index]
            if (amps.isFinite() && amps in MIN_PLAUSIBLE_AMPS..MAX_PLAUSIBLE_AMPS) {
                target.add(Candidate(amps, raw, index))
            }
        }
    }

    private fun score(amps: Double): Double = when {
        amps in 0.05..5.0 -> 0.0
        amps < 0.05 -> 3.0
        else -> 3.0 + (amps - 5.0)
    }

    private fun Double.copySignFrom(source: Double): Double = if (source < 0.0) -this else this

    private fun discoverCurrentFiles(): List<File> = File("/sys/class/power_supply")
        .listFiles()
        .orEmpty()
        .map { File(it, "current_now") }
        .filter { it.isFile && it.canRead() }

    private companion object {
        val MULTIPLIERS = doubleArrayOf(1.0, 0.5, 2.0, 1000.0)
        const val MIN_PLAUSIBLE_AMPS = 0.005
        const val MAX_PLAUSIBLE_AMPS = 10.0
    }
}
