package com.batteryscope.app.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.File

class BatteryReader(context: Context) {
    private val appContext = context.applicationContext
    private val batteryManager = appContext.getSystemService(BatteryManager::class.java)
    private val capacityReader = BatteryCapacityReader(appContext)
    private val capacityPreferences = CapacityPreferences(appContext)
    private val settings = com.batteryscope.app.settings.AppSettings(appContext)
    private val currentReader = CurrentReader(
        batteryManager = batteryManager,
        invertChargingPolarity = settings.invertChargingPolarity,
    )

    fun read(): BatterySnapshot {
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
        val capacity = capacityReader.read(remainingMah, levelPercent, voltageV)
        val designCapacityMah = capacity.designMah ?: capacityPreferences.designCapacityMah
        // Power follows the same signed current polarity. Do not invert power separately.
        val powerW = if (currentA != null && voltageV != null) currentA * voltageV else null
        val energyWh = if (remainingMah != null && voltageV != null) remainingMah / 1000.0 * voltageV else null

        return BatterySnapshot(
            levelPercent = levelPercent,
            charging = charging,
            full = full,
            voltageV = voltageV,
            currentA = currentA,
            temperatureC = temperatureC,
            remainingMah = remainingMah,
            batteryCapacityMah = designCapacityMah,
            estimatedCapacityMah = capacity.estimatedMah,
            powerW = powerW,
            energyWh = energyWh,
        )
    }

    private fun readChargeCounterMah(): Double? {
        val microAh = batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            ?: Long.MIN_VALUE
        return microAh.takeIf { it > 0 }?.toDouble()?.div(1000.0)
    }
}

class BatteryCapacityReader(private val context: Context) {
    data class Result(
        val designMah: Double?,
        val estimatedMah: Double?,
    )

    fun read(remainingMah: Double?, levelPercent: Int, voltageV: Double?): Result {
        val powerSupplyDirs = File("/sys/class/power_supply").listFiles().orEmpty()
        val designMah = powerSupplyDirs.asSequence()
            .mapNotNull { readCapacityMah(it, "charge_full_design", voltageV) ?: readCapacityMah(it, "energy_full_design", voltageV) }
            .firstOrNull()
        val fullMah = powerSupplyDirs.asSequence()
            .mapNotNull { readCapacityMah(it, "charge_full", voltageV) ?: readCapacityMah(it, "energy_full", voltageV) }
            .firstOrNull()
        val estimatedFromCounter = if (remainingMah != null && levelPercent in 20..99) {
            (remainingMah * 100.0 / levelPercent).takeIf { it in MIN_CAPACITY_MAH..MAX_CAPACITY_MAH }
        } else null
        return Result(designMah, fullMah ?: estimatedFromCounter)
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
        return value.takeIf { it in MIN_CAPACITY_MAH..MAX_CAPACITY_MAH }
    }

    companion object {
        private const val MIN_CAPACITY_MAH = 100.0
        private const val MAX_CAPACITY_MAH = 30_000.0
    }
}
