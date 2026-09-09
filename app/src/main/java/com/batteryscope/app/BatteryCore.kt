package com.batteryscope.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import kotlin.math.abs
import kotlin.math.pow

const val DESIGN_CAPACITY_MAH = 5000.0
const val REFERENCE_FULL_VOLTAGE = 4.35

data class BatterySnapshot(
    val timestamp: Long,
    val level: Int,
    val temperatureC: Double,
    val voltageV: Double,
    val status: String,
    val technology: String,
    val currentMa: Double?,
    val averageCurrentMa: Double?,
    val powerW: Double,
    val chargeTimeRemainingMs: Long?,
    val cycleCount: Int?,
    val counterMicroAh: Long?,
    val energyCounterNWh: Long?
) {
    val charging: Boolean get() = status == "Charging" || status == "Full"
}

data class HealthEstimate(
    val capacityMah: Double?,
    val healthPercent: Double?,
    val confidencePercent: Int,
    val completedSessions: Int,
    val source: String
)

data class ChargeSession(
    val startTime: Long,
    val endTime: Long,
    val startLevel: Int,
    val endLevel: Int,
    val chargedMah: Double,
    val estimatedCapacityMah: Double,
    val endVoltageV: Double,
    val wearCycles: Double,
    val efficiencyPercent: Double
)

data class HistorySample(
    val timestamp: Long,
    val level: Int,
    val temperatureC: Double,
    val voltageV: Double,
    val currentMa: Double?
) {
    val powerW: Double get() = currentMa?.let { it * voltageV / 1000.0 } ?: 0.0
}

private fun normalizedCurrentMa(rawUa: Long?, charging: Boolean): Double? {
    if (rawUa == null) return null
    val magnitudeMa = abs(rawUa) / 1000.0
    if (magnitudeMa < 0.5) return 0.0
    return if (charging) magnitudeMa else -magnitudeMa
}

fun readBattery(context: Context): BatterySnapshot {
    val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
    val temp = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
    val voltage = (intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0) / 1000.0
    val status = when (intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
        BatteryManager.BATTERY_STATUS_FULL -> "Full"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
        else -> "Unknown"
    }
    val charging = status == "Charging" || status == "Full"
    val rawNowUa = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).let { if (it == Int.MIN_VALUE) null else it.toLong() }
    } else null
    val rawAverageUa = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE).let { if (it == Int.MIN_VALUE) null else it.toLong() }
    } else null
    val currentMa = normalizedCurrentMa(rawNowUa, charging)
    val averageCurrentMa = normalizedCurrentMa(rawAverageUa, charging)
    val powerW = currentMa?.let { it * voltage / 1000.0 } ?: 0.0
    val chargeTime = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        bm.computeChargeTimeRemaining().takeIf { it >= 0 }
    } else null
    val cycle = if (Build.VERSION.SDK_INT >= 34) {
        intent?.getIntExtra("android.os.extra.CYCLE_COUNT", -1)?.takeIf { it >= 0 }
    } else null
    val counter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).takeIf { it >= 0 }
    } else null
    val energy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER).takeIf { it >= 0 }
    } else null
    return BatterySnapshot(
        timestamp = System.currentTimeMillis(),
        level = level,
        temperatureC = temp,
        voltageV = voltage,
        status = status,
        technology = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "Unknown",
        currentMa = currentMa,
        averageCurrentMa = averageCurrentMa,
        powerW = powerW,
        chargeTimeRemainingMs = chargeTime,
        cycleCount = cycle,
        counterMicroAh = counter,
        energyCounterNWh = energy
    )
}

fun healthConfidence(sessionCount: Int): Int = when {
    sessionCount <= 0 -> 0
    sessionCount == 1 -> 20
    sessionCount == 2 -> 35
    sessionCount == 3 -> 50
    sessionCount == 4 -> 60
    sessionCount <= 7 -> 75
    else -> 90
}

fun estimateHealth(sessions: List<ChargeSession>): HealthEstimate {
    if (sessions.isEmpty()) return HealthEstimate(null, null, 0, 0, "Estimated from charging sessions")
    val valid = sessions.filter { it.endLevel - it.startLevel >= 60 && it.estimatedCapacityMah in 2500.0..6500.0 }
    if (valid.isEmpty()) return HealthEstimate(null, null, 0, 0, "Need a usable charge session covering at least 60 percentage points")
    val recent = valid.takeLast(5)
    val capacity = recent.map { it.estimatedCapacityMah }.average()
    val health = (capacity / DESIGN_CAPACITY_MAH * 100.0).coerceIn(0.0, 120.0)
    return HealthEstimate(capacity, health, healthConfidence(recent.size), recent.size, "Estimated from the last ${recent.size} usable charge sessions")
}

fun estimateWearCycles(endVoltageV: Double, endLevel: Int): Double {
    if (endLevel < 60 || endVoltageV <= 0.0) return 0.0
    val clampedV = endVoltageV.coerceIn(3.95, REFERENCE_FULL_VOLTAGE)
    val highVoltageWear = 2.0.pow(10.0 * (clampedV - REFERENCE_FULL_VOLTAGE))
    val linearPart = if (endVoltageV < 3.95) 0.0625 else 1.0
    return (highVoltageWear * linearPart).coerceIn(0.01, 2.0)
}

class MeasurementEngine(private val store: BatteryStore) {
    private var lastSample: BatterySnapshot? = null
    private var sessionStartLevel: Int? = null
    private var sessionStartTime: Long? = null
    private var sessionMah: Double = 0.0
    private var sessionPeakVoltage: Double = 0.0

    private fun clearSession() {
        sessionStartLevel = null
        sessionStartTime = null
        sessionMah = 0.0
        sessionPeakVoltage = 0.0
    }

    private fun finishSession(snapshot: BatterySnapshot) {
        val start = sessionStartLevel ?: return
        val end = snapshot.level.coerceAtLeast(start)
        val delta = end - start
        val estimate = if (delta > 0 && sessionMah > 50.0) sessionMah * 100.0 / delta else 0.0
        if (delta >= 60 && estimate in 2500.0..6500.0) {
            val peakV = maxOf(sessionPeakVoltage, snapshot.voltageV)
            val wear = estimateWearCycles(peakV, end)
            val efficiency = if (wear > 0.0) delta / (wear * 100.0) * 100.0 else 0.0
            store.addSession(ChargeSession(sessionStartTime ?: snapshot.timestamp, snapshot.timestamp, start, end, sessionMah, estimate, peakV, wear, efficiency))
        }
        clearSession()
    }

    fun observe(snapshot: BatterySnapshot) {
        val previous = lastSample
        if (snapshot.charging && sessionStartLevel == null && snapshot.level < 100) {
            sessionStartLevel = snapshot.level
            sessionStartTime = snapshot.timestamp
            sessionMah = 0.0
            sessionPeakVoltage = snapshot.voltageV
        }
        if (previous != null && previous.charging && snapshot.charging && snapshot.currentMa != null) {
            val dtHours = (snapshot.timestamp - previous.timestamp).coerceAtLeast(0L) / 3_600_000.0
            if (dtHours in 0.0..0.10) sessionMah += abs(snapshot.currentMa) * dtHours
        }
        if (sessionStartLevel != null && snapshot.charging) sessionPeakVoltage = maxOf(sessionPeakVoltage, snapshot.voltageV)
        val completed = sessionStartLevel != null && (snapshot.level >= 99 || snapshot.status == "Full")
        if (completed) finishSession(snapshot)
        else if (previous?.charging == true && !snapshot.charging && sessionStartLevel != null) finishSession(previous)

        if (previous == null || snapshot.timestamp - previous.timestamp >= 60_000L) {
            store.addSample(HistorySample(snapshot.timestamp, snapshot.level, snapshot.temperatureC, snapshot.voltageV, snapshot.currentMa))
        }
        lastSample = snapshot
    }
}
