package com.batteryscope.app.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.File
import kotlin.math.abs

class BatteryReader(context: Context) {
    private val appContext = context.applicationContext
    private val batteryManager = appContext.getSystemService(BatteryManager::class.java)
    private val capacityReader = BatteryCapacityReader()

    fun read(): BatterySnapshot {
        val intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val levelPercent = if (scale > 0) ((level * 100f) / scale).toInt().coerceIn(0, 100) else 0
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val voltageV = voltageMv.takeIf { it > 0 }?.div(1000.0)
        val tempTenthsC = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val temperatureC = tempTenthsC
            ?.takeIf { it != Int.MIN_VALUE }
            ?.div(10.0)

        val remainingMah = readChargeCounterMah()
        val currentA = readCurrentA()
        val capacity = capacityReader.read(remainingMah, levelPercent)
        val estimatedCapacityMah = capacity.estimatedMah
        val powerW = if (currentA != null && voltageV != null) abs(currentA) * voltageV else null
        val energyWh = if (remainingMah != null && voltageV != null) remainingMah / 1000.0 * voltageV else null

        return BatterySnapshot(
            levelPercent = levelPercent,
            charging = charging,
            voltageV = voltageV,
            currentA = currentA,
            temperatureC = temperatureC,
            remainingMah = remainingMah,
            batteryCapacityMah = capacity.designMah,
            estimatedCapacityMah = estimatedCapacityMah,
            powerW = powerW,
            energyWh = energyWh,
        )
    }

    private fun readChargeCounterMah(): Double? {
        val microAh = batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            ?: Long.MIN_VALUE
        return microAh.takeIf { it > 0 }?.toDouble()?.div(1000.0)
    }

    private fun readCurrentA(): Double? {
        val microAmps = batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            ?: Long.MIN_VALUE
        if (microAmps == Long.MIN_VALUE) return null
        return microAmps.toDouble() / 1_000_000.0
    }
}

class BatteryCapacityReader {
    data class Result(
        val designMah: Double?,
        val estimatedMah: Double?,
    )

    fun read(remainingMah: Double?, levelPercent: Int): Result {
        val designMah = firstReadableMah(DESIGN_PATHS)
        val fullMah = firstReadableMah(FULL_PATHS)
        val estimatedFromLevel = if (fullMah == null && remainingMah != null && levelPercent in 20..99) {
            (remainingMah * 100.0 / levelPercent).takeIf { it in 100.0..30_000.0 }
        } else {
            null
        }
        return Result(
            designMah = designMah,
            estimatedMah = fullMah ?: estimatedFromLevel
        )
    }

    private fun firstReadableMah(paths: List<String>): Double? {
        for (path in paths) {
            val value = readMah(path)
            if (value != null) return value
        }
        return null
    }

    private fun readMah(path: String): Double? {
        val file = File(path)
        if (!file.isFile || !file.canRead()) return null
        val raw = file.readText().trim().toDoubleOrNull() ?: return null
        if (raw <= 0.0) return null
        return when {
            raw > 1_000_000.0 -> raw / 1000.0
            raw > 30_000.0 -> raw / 1000.0
            else -> raw
        }.takeIf { it in 100.0..30_000.0 }
    }

    companion object {
        private val DESIGN_PATHS = listOf(
            "/sys/class/power_supply/battery/charge_full_design",
            "/sys/class/power_supply/BAT0/charge_full_design",
            "/sys/class/power_supply/BATT/charge_full_design",
        )
        private val FULL_PATHS = listOf(
            "/sys/class/power_supply/battery/charge_full",
            "/sys/class/power_supply/BAT0/charge_full",
            "/sys/class/power_supply/BATT/charge_full",
        )
    }
}
