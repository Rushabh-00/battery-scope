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
    private val capacityReader = BatteryCapacityReader()
    private val capacityPreferences = CapacityPreferences(appContext)
    private val sessionAnalyzer = BatterySessionAnalyzer(appContext)
    private val settings = AppSettings(appContext)
    private val currentReader = CurrentReader(
        batteryManager = batteryManager,
        invertChargingPolarity = settings.invertChargingPolarity,
    )

    fun read(): BatterySnapshot {
        currentReader.invertChargingPolarity = settings.invertChargingPolarity

        val intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val levelPercent = if (scale > 0) ((level * 100f) / scale).toInt().coerceIn(0, 100) else 0
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val full = status == BatteryManager.BATTERY_STATUS_FULL || levelPercent >= 100

        val voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val voltageV = voltageMv.takeIf { it > 0 }?.div(1000.0)
        val tempTenthsC = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val temperatureC = tempTenthsC?.takeIf { it != Int.MIN_VALUE }?.div(10.0)

        val remainingMah = readChargeCounterMah()
        val currentA = currentReader.readAmps()
        val capacity = capacityReader.read(voltageV)
        val designCapacityMah = capacity.designMah ?: capacityPreferences.designCapacityMah
        val powerW = if (currentA != null && voltageV != null) currentA * voltageV else null
        val energyWh = if (remainingMah != null && voltageV != null) remainingMah / 1000.0 * voltageV else null

        val baseSnapshot = BatterySnapshot(
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
        return baseSnapshot.copy(sessionAnalysis = sessionAnalyzer.update(baseSnapshot))
    }

    private fun readChargeCounterMah(): Double? {
        val microAh = batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            ?: Long.MIN_VALUE
        return microAh.takeIf { it > 0 }?.toDouble()?.div(1000.0)
    }
}

class BatteryCapacityReader {
    data class Result(val designMah: Double?)

    fun read(voltageV: Double?): Result {
        val powerSupplyDirs = File("/sys/class/power_supply").listFiles().orEmpty()
        val designMah = powerSupplyDirs.asSequence()
            .mapNotNull { readCapacityMah(it, "charge_full_design", voltageV) ?: readCapacityMah(it, "energy_full_design", voltageV) }
            .firstOrNull()
        return Result(designMah)
    }

    private fun readCapacityMah(dir: File, name: String, voltageV: Double?): Double? {
        val file = File(dir, name)
        if (!file.isFile || !file.canRead()) return null
        val raw = file.readText().trim().toDoubleOrNull() ?: return null
        if (raw <= 0.0) return null
        val value = if (name.startsWith("energy_")) {
            val voltageMv = voltageV?.times(1000.0)?.takeIf { it > 0.0 } ?: return null
            raw / voltageMv
        } else {
            when {
                raw > 1_000_000.0 -> raw / 1000.0
                raw > 30_000.0 -> raw / 1000.0
                else -> raw
            }
        }
        return value.takeIf { it in 100.0..30_000.0 }
    }
}
