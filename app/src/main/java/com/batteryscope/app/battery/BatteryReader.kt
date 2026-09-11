package com.batteryscope.app.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.batteryscope.app.settings.AppSettings
import java.io.File

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
        val intent = appContext.registerReceiver(null, batteryChangedFilter)
            ?: error("Battery telemetry broadcast unavailable")
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        require(level >= 0 && scale > 0) { "Battery level telemetry unavailable" }
        val levelPercent = ((level * 100f) / scale).toInt().coerceIn(0, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val full = status == BatteryManager.BATTERY_STATUS_FULL || levelPercent >= 100
        val voltageV = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0).takeIf { it > 0 }?.div(1000.0)
        val temperatureC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            .takeIf { it != Int.MIN_VALUE }?.div(10.0)
        val remainingMah = readChargeCounterMah()
        val currentA = currentReader.readAmps()
        val designCapacityMah = capacityPreferences.designCapacityMah
            ?: cachedDesignCapacityMah
            ?: capacityReader.read(voltageV).designMah.also { detected ->
                if (detected != null) cachedDesignCapacityMah = detected
            }
        val powerW = if (currentA != null && voltageV != null) currentA * voltageV else null
        val energyWh = if (remainingMah != null && voltageV != null) remainingMah / 1000.0 * voltageV else null
        val base = BatterySnapshot(
            levelPercent = levelPercent,
            charging = charging,
            full = full,
            voltageV = voltageV,
            currentA = currentA,
            temperatureC = temperatureC,
            remainingMah = remainingMah,
            batteryCapacityMah = designCapacityMah,
            estimatedCapacityMah = null,
            powerW = powerW,
            energyWh = energyWh,
        )
        val analysis = sessionAnalyzer.update(base)
        return base.copy(estimatedCapacityMah = analysis.learnedCapacityMah, sessionAnalysis = analysis)
    }

    private fun readChargeCounterMah(): Double? {
        val microAh = batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: Long.MIN_VALUE
        return microAh.takeIf { it > 0 }?.toDouble()?.div(1000.0)
    }
}

class BatteryCapacityReader {
    data class Result(val designMah: Double?)
    private data class Candidate(val file: File, val energyBased: Boolean)

    private var candidates: List<Candidate>? = null

    fun read(voltageV: Double?): Result {
        val files = candidates ?: discoverCandidates().also { candidates = it }
        val designMah = files.asSequence()
            .mapNotNull { readCapacityMah(it.file, it.energyBased, voltageV) }
            .firstOrNull()
        return Result(designMah)
    }

    private fun discoverCandidates(): List<Candidate> = File("/sys/class/power_supply")
        .listFiles()
        .orEmpty()
        .flatMap { dir ->
            buildList {
                add(Candidate(File(dir, "charge_full_design"), energyBased = false))
                add(Candidate(File(dir, "energy_full_design"), energyBased = true))
            }
        }
        .filter { it.file.isFile && it.file.canRead() }

    private fun readCapacityMah(file: File, energyBased: Boolean, voltageV: Double?): Double? {
        val raw = file.readText().trim().toDoubleOrNull() ?: return null
        if (raw <= 0.0) return null
        val value = if (energyBased) {
            val voltageMv = voltageV?.times(1000.0)?.takeIf { it > 0.0 } ?: return null
            raw / voltageMv
        } else {
            if (raw > 30_000.0) raw / 1000.0 else raw
        }
        return value.takeIf { it in 100.0..30_000.0 }
    }
}
