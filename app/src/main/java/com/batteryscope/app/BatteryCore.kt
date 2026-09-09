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
    val powerW: Double?,
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
)

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
    val currentUa = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).let { if (it == Int.MIN_VALUE) null else it.toLong() }
    } else null
    val currentMa = currentUa?.div(1000.0)
    val powerW = if (currentMa != null && voltage > 0) abs(currentMa) * voltage / 1000.0 else null
    val chargeTime = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) bm.computeChargeTimeRemaining().takeIf { it >= 0 } else null
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
        timestamp = System.currentTimeMillis(), level = level, temperatureC = temp, voltageV = voltage,
        status = status, technology = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "Unknown",
        currentMa = currentMa, powerW = powerW, chargeTimeRemainingMs = chargeTime,
        cycleCount = cycle, counterMicroAh = counter, energyCounterNWh = energy
    )
}

fun classifyTemperature(temp: Double): String = when {
    temp >= 45.0 -> "Very hot"
    temp >= 42.0 -> "Hot"
    temp >= 38.0 -> "Warm"
    temp >= 30.0 -> "Normal"
    else -> "Cool"
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
    if (valid.isEmpty()) return HealthEstimate(null, null, 0, 0, "Need a charge session covering at least 60 percentage points")

    // Like AccuBattery's health approach, favor the most recent usable sessions.
    val recent = valid.takeLast(5)
    val capacity = recent.map { it.estimatedCapacityMah }.average()
    val health = (capacity / DESIGN_CAPACITY_MAH * 100.0).coerceIn(0.0, 120.0)
    return HealthEstimate(capacity, health, healthConfidence(recent.size), recent.size, "Estimated from the last ${recent.size} usable charge sessions")
}

// Battery wear is a model, not a directly measured Android value. Higher end-of-charge
// voltage is assigned a higher cycle-cost multiplier, following the well-known Li-ion
// principle used by AccuBattery: roughly every 0.1 V lower end voltage halves wear.
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

    fun observe(snapshot: BatterySnapshot) {
        val previous = lastSample
        if (previous != null && snapshot.charging && snapshot.currentMa != null) {
            val dtHours = (snapshot.timestamp - previous.timestamp).coerceAtLeast(0L) / 3_600_000.0
            if (dtHours in 0.0..0.10) sessionMah += abs(snapshot.currentMa) * dtHours
        }

        if (snapshot.charging && sessionStartLevel == null && snapshot.level < 100) {
            sessionStartLevel = snapshot.level
            sessionStartTime = snapshot.timestamp
            sessionMah = 0.0
            sessionPeakVoltage = snapshot.voltageV
        }

        if (sessionStartLevel != null && snapshot.charging) {
            sessionPeakVoltage = maxOf(sessionPeakVoltage, snapshot.voltageV)
        }

        val completed = sessionStartLevel != null && (snapshot.level >= 99 || snapshot.status == "Full")
        if (completed) {
            val start = sessionStartLevel ?: snapshot.level
            val delta = snapshot.level - start
            val estimate = if (delta > 0 && sessionMah > 50.0) sessionMah * 100.0 / delta else 0.0
            if (delta >= 60 && estimate in 2500.0..6500.0) {
                val wear = estimateWearCycles(maxOf(sessionPeakVoltage, snapshot.voltageV), snapshot.level)
                val efficiency = if (wear > 0.0) delta / (wear * 100.0) * 100.0 else 0.0
                store.addSession(
                    ChargeSession(
                        startTime = sessionStartTime ?: snapshot.timestamp,
                        endTime = snapshot.timestamp,
                        startLevel = start,
                        endLevel = snapshot.level,
                        chargedMah = sessionMah,
                        estimatedCapacityMah = estimate,
                        endVoltageV = maxOf(sessionPeakVoltage, snapshot.voltageV),
                        wearCycles = wear,
                        efficiencyPercent = efficiency
                    )
                )
            }
            sessionStartLevel = null
            sessionStartTime = null
            sessionMah = 0.0
            sessionPeakVoltage = 0.0
        }

        if (!snapshot.charging && sessionStartLevel != null) {
            // A cable disconnect or a charging interruption ends the current measurement.
            sessionStartLevel = null
            sessionStartTime = null
            sessionMah = 0.0
            sessionPeakVoltage = 0.0
        }

        if (previous == null || snapshot.timestamp - previous.timestamp >= 60_000L) {
            store.addSample(HistorySample(snapshot.timestamp, snapshot.level, snapshot.temperatureC, snapshot.voltageV, snapshot.currentMa))
        }
        lastSample = snapshot
    }
}
