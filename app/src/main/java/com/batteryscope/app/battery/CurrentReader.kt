package com.batteryscope.app.battery

import android.os.BatteryManager
import java.io.File
import kotlin.math.abs

class CurrentReader(private val batteryManager: BatteryManager?) {
    fun readAmps(): Double? {
        val now = readProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val average = readProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        val batteryValue = listOf(now, average).firstOrNull { it != null && it != 0L }
        if (batteryValue != null) return abs(batteryValue.toDouble()) / 1_000_000.0

        return readSysfsAmps()
    }

    private fun readProperty(property: Int): Long? =
        batteryManager?.getLongProperty(property)?.takeUnless { it == Long.MIN_VALUE }

    private fun readSysfsAmps(): Double? {
        val paths = listOf(
            "/sys/class/power_supply/battery/current_now",
            "/sys/class/power_supply/BAT0/current_now",
            "/sys/class/power_supply/BATT/current_now",
        )
        for (path in paths) {
            val raw = File(path).takeIf { it.isFile && it.canRead() }
                ?.readText()?.trim()?.toDoubleOrNull() ?: continue
            if (raw == 0.0) continue
            return abs(raw) / when {
                abs(raw) >= 100_000.0 -> 1_000_000.0
                abs(raw) >= 100.0 -> 1_000.0
                else -> 1.0
            }
        }
        return null
    }
}
