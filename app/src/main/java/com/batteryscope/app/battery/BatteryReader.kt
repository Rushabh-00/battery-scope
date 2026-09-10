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
    private val chargeTimeEstimator = ChargeTimeEstimator()

    fun read(trackSession: Boolean = true): BatterySnapshot {
        currentReader.invertChargingPolarity = settings.invertChargingPolarity
        val intent = appContext.registerReceiver(null, batteryChangedFilter)
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val levelPercent = if (scale > 0) ((level * 100f) / scale).toInt().coerceIn(0, 100) else 0
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val full = status == BatteryManager.BATTERY_STATUS_FULL || levelPercent >= 100
        val voltageV = (intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0).takeIf { it > 0 }?.div(1000.0)
        val temperatureC = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }?.div(10.0)
        val remainingMah = readChargeCounterMah()
        val currentA = currentReader.readAmps()
        val designCapacityMah = capacityPreferences.designCapacityMah ?: capacityReader.read(voltageV).designMah
        val powerW = if (currentA != null && voltageV != null) currentA * voltageV else null
        val energyWh = if (remainingMah != null && voltageV != null) remainingMah / 1000.0 * voltageV else null
        val chargeTimeRemainingMs = chargeTimeEstimator.estimate(
            nowMs = System.currentTimeMillis(), levelPercent = levelPercent, charging = charging, full = full,
            remainingMah = remainingMah, currentA = currentA, capacityMah = sessionAnalyzer.learnedCapacityMah() ?: designCapacityMah,
        )
        val base = BatterySnapshot(
            levelPercent = levelPercent, charging = charging, full = full, voltageV = voltageV,
            currentA = currentA, temperatureC = temperatureC, remainingMah = remainingMah,
            batteryCapacityMah = designCapacityMah, estimatedCapacityMah = null, powerW = powerW,
            energyWh = energyWh, chargeTimeRemainingMs = chargeTimeRemainingMs,
        )
        if (!trackSession) return base
        val analysis = sessionAnalyzer.update(base)
        return base.copy(estimatedCapacityMah = analysis.learnedCapacityMah, sessionAnalysis = analysis)
    }

    private fun readChargeCounterMah(): Double? {
        val microAh = batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: Long.MIN_VALUE
        return microAh.takeIf { it > 0 }?.toDouble()?.div(1000.0)
    }
}

private class ChargeTimeEstimator {
    private data class Sample(val timeMs: Long, val remainingMah: Double, val currentA: Double?)
    private val samples = ArrayDeque<Sample>()

    fun estimate(nowMs: Long, levelPercent: Int, charging: Boolean, full: Boolean, remainingMah: Double?, currentA: Double?, capacityMah: Double?): Long? {
        if (!charging) { samples.clear(); return null }
        if (full || levelPercent >= 100) { samples.clear(); return 0L }
        if (remainingMah != null) samples.addLast(Sample(nowMs, remainingMah, currentA))
        while (samples.isNotEmpty() && nowMs - samples.first().timeMs > WINDOW_MS) samples.removeFirst()
        val targetMah = capacityMah?.takeIf { it in 100.0..30_000.0 }
        val neededMah = when {
            targetMah != null && remainingMah != null -> targetMah - remainingMah
            targetMah != null -> targetMah * (100 - levelPercent).coerceAtLeast(0) / 100.0
            else -> null
        }?.coerceAtLeast(0.0) ?: return null
        if (neededMah <= 0.0) return 0L

        val first = samples.firstOrNull()
        val last = samples.lastOrNull()
        val measuredRateMahPerHour = if (first != null && last != null && last.timeMs - first.timeMs >= MIN_TREND_MS) {
            val deltaMah = last.remainingMah - first.remainingMah
            if (deltaMah > MIN_DELTA_MAH) deltaMah / ((last.timeMs - first.timeMs) / 3_600_000.0) else null
        } else null
        val currentRateMahPerHour = samples.mapNotNull { it.currentA?.let { current -> abs(current) * 1000.0 } }.takeIf { it.isNotEmpty() }?.average()
        val rateMahPerHour = measuredRateMahPerHour ?: currentRateMahPerHour ?: return null
        if (!rateMahPerHour.isFinite() || rateMahPerHour < MIN_RATE_MAH_PER_HOUR) return null
        return (neededMah / rateMahPerHour * 3_600_000.0).takeIf { it.isFinite() && it >= 0.0 }?.toLong()
    }

    private companion object {
        const val WINDOW_MS = 45_000L
        const val MIN_TREND_MS = 8_000L
        const val MIN_DELTA_MAH = 1.0
        const val MIN_RATE_MAH_PER_HOUR = 10.0
    }
}

class BatteryCapacityReader {
    data class Result(val designMah: Double?)
    fun read(voltageV: Double?): Result {
        val dirs = File("/sys/class/power_supply").listFiles().orEmpty()
        val designMah = dirs.asSequence().mapNotNull { readCapacityMah(it, "charge_full_design", voltageV) ?: readCapacityMah(it, "energy_full_design", voltageV) }.firstOrNull()
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
        } else when { raw > 30_000.0 -> raw / 1000.0; else -> raw }
        return value.takeIf { it in 100.0..30_000.0 }
    }
}
