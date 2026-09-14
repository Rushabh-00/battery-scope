package com.batteryscope.app.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.batteryscope.app.settings.AppSettings
import java.io.File
import kotlin.math.abs

class BatteryReader(context: Context) {
    private val appContext = context.applicationContext
    private val batteryManager = appContext.getSystemService(BatteryManager::class.java)
    private val batteryChangedFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
    private val capacityReader = BatteryCapacityReader()
    private val capacityPreferences = CapacityPreferences(appContext)
    private val sessionAnalyzer = BatterySessionAnalyzer(appContext)
    private val settings = AppSettings(appContext)
    private val currentReader = CurrentReader(batteryManager, settings.invertChargingPolarity)
    private var cachedDesignCapacityMah: Double? = null

    fun read(): BatterySnapshot {
        currentReader.invertChargingPolarity = settings.invertChargingPolarity
        val intent = appContext.registerReceiver(null, batteryChangedFilter) ?: error("Battery telemetry broadcast unavailable")
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        require(level >= 0 && scale > 0) { "Battery level telemetry unavailable" }
        val levelPercent = ((level * 100f) / scale).toInt().coerceIn(0, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val full = status == BatteryManager.BATTERY_STATUS_FULL || levelPercent >= 100
        val voltageV = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0).takeIf { it > 0 }?.div(1000.0)
        val temperatureC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }?.div(10.0)
        val remainingMah = readChargeCounterMah()
        val currentA = currentReader.readAmps()
        val chargeCurrentA = readBatteryChargeCurrentA()
        val capacityInfo = capacityReader.read(voltageV)
        val designCapacityMah = capacityPreferences.designCapacityMah ?: cachedDesignCapacityMah ?: capacityInfo.designMah.also {
            if (it != null) cachedDesignCapacityMah = it
        }
        val powerW = if (currentA != null && voltageV != null) currentA * voltageV else null
        val energyWh = readEnergyWh() ?: if (remainingMah != null && voltageV != null) remainingMah / 1000.0 * voltageV else null
        val snapshot = BatterySnapshot(
            levelPercent = levelPercent,
            charging = charging,
            full = full,
            voltageV = voltageV,
            currentA = currentA,
            temperatureC = temperatureC,
            remainingMah = remainingMah,
            batteryCapacityMah = designCapacityMah,
            fullChargeCapacityMah = capacityInfo.fullChargeMah,
            powerW = powerW,
            energyWh = energyWh,
        )
        val analysis = sessionAnalyzer.update(snapshot, chargeCurrentA)
        return snapshot.copy(sessionAnalysis = analysis)
    }

    private fun readChargeCounterMah(): Double? {
        val microAh = batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: Long.MIN_VALUE
        return microAh.takeIf { it > 0 }?.toDouble()?.div(1000.0)
    }

    private fun readBatteryChargeCurrentA(): Double? {
        for (property in CURRENT_PROPERTIES) {
            val microamps = batteryManager?.getLongProperty(property)?.takeUnless { it == Long.MIN_VALUE || it == 0L } ?: continue
            val amps = microamps / 1_000_000.0
            if (amps.isFinite() && abs(amps) <= 10.0) return amps
        }
        return null
    }

    private fun readEnergyWh(): Double? {
        val nanoWh = batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER) ?: Long.MIN_VALUE
        return nanoWh.takeIf { it > 0 }?.toDouble()?.div(1_000_000_000.0)
    }

    private companion object {
        val CURRENT_PROPERTIES = intArrayOf(
            BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE,
            BatteryManager.BATTERY_PROPERTY_CURRENT_NOW,
        )
    }
}

class BatteryCapacityReader {
    data class Result(val designMah: Double?, val fullChargeMah: Double?)
    private data class Candidate(val file: File, val energyBased: Boolean, val fullCharge: Boolean)
    private var candidates: List<Candidate>? = null

    fun read(voltageV: Double?): Result {
        val files = candidates ?: discoverCandidates().also { candidates = it }
        val design = files.asSequence().filter { !it.fullCharge }.mapNotNull { readCapacityMah(it.file, it.energyBased, voltageV) }.firstOrNull()
        val full = files.asSequence().filter { it.fullCharge }.mapNotNull { readCapacityMah(it.file, it.energyBased, voltageV) }.firstOrNull()
        return Result(design, full)
    }

    private fun discoverCandidates(): List<Candidate> = File("/sys/class/power_supply").listFiles().orEmpty().flatMap { dir ->
        buildList {
            add(Candidate(File(dir, "charge_full_design"), false, false))
            add(Candidate(File(dir, "energy_full_design"), true, false))
            add(Candidate(File(dir, "charge_full"), false, true))
            add(Candidate(File(dir, "energy_full"), true, true))
        }
    }.filter { it.file.isFile && it.file.canRead() }

    private fun readCapacityMah(file: File, energyBased: Boolean, voltageV: Double?): Double? {
        val raw = file.readText().trim().toDoubleOrNull() ?: return null
        if (raw <= 0.0) return null
        val value = if (energyBased) {
            val voltageMv = voltageV?.times(1000.0)?.takeIf { it > 0.0 } ?: return null
            raw / voltageMv
        } else if (raw > 30_000.0) raw / 1000.0 else raw
        return value.takeIf { it in 100.0..30_000.0 }
    }
}
