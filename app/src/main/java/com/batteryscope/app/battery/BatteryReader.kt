package com.batteryscope.app.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlin.math.abs

class BatteryReader(context: Context) {
    private val appContext = context.applicationContext
    private val batteryManager = appContext.getSystemService(BatteryManager::class.java)

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
        val estimatedCapacityMah = estimateFullCapacity(remainingMah, levelPercent)
        val powerW = if (currentA != null && voltageV != null) abs(currentA) * voltageV else null
        val energyWh = if (remainingMah != null && voltageV != null) remainingMah / 1000.0 * voltageV else null

        return BatterySnapshot(
            levelPercent = levelPercent,
            charging = charging,
            voltageV = voltageV,
            currentA = currentA,
            temperatureC = temperatureC,
            remainingMah = remainingMah,
            estimatedCapacityMah = estimatedCapacityMah,
            powerW = powerW,
            energyWh = energyWh,
        )
    }

    private fun readChargeCounterMah(): Double? {
        val microAh = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: Int.MIN_VALUE
        return microAh.takeIf { it > 0 }?.toDouble()?.div(1000.0)
    }

    private fun readCurrentA(): Double? {
        val microAmps = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: Int.MIN_VALUE
        return microAmps.takeIf { it != Int.MIN_VALUE }?.toDouble()?.div(1_000_000.0)
    }

    private fun estimateFullCapacity(remainingMah: Double?, levelPercent: Int): Double? {
        if (remainingMah == null || levelPercent < 10 || levelPercent > 99) return null
        return (remainingMah * 100.0 / levelPercent).takeIf { it in 100.0..30_000.0 }
    }
}
