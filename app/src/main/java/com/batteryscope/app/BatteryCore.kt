package com.batteryscope.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import kotlin.math.abs
import kotlin.math.max

const val DESIGN_CAPACITY_MAH = 5000.0

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
    val estimatedCapacityMah: Double
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

fun estimateHealth(sessions: List<ChargeSession>): HealthEstimate {
    if (sessions.isEmpty()) return HealthEstimate(null, null, 0, 0, "Estimated from charge sessions")
    val valid = sessions.map { it.estimatedCapacityMah }.filter { it in 2500.0..6500.0 }
    if (valid.isEmpty()) return HealthEstimate(null, null, 0, 0, "Insufficient usable sessions")
    val sorted = valid.sorted()
    val trimmed = if (sorted.size >= 5) sorted.drop(1).dropLast(1) else sorted
    val mean = trimmed.average()
    val health = (mean / DESIGN_CAPACITY_MAH * 100.0).coerceIn(0.0, 100.0)
    val confidence = when (valid.size) {
        1 -> 20
        2 -> 35
        3 -> 50
        4 -> 60
        in 5..7 -> 75
        else -> 90
    }
    return HealthEstimate(mean, health, confidence, valid.size, "Estimated from repeated charging sessions")
}

class MeasurementEngine(private val store: BatteryStore) {
    private var lastSample: BatterySnapshot? = null
    private var sessionStartLevel: Int? = null
    private var sessionStartTime: Long? = null
    private var sessionMah: Double = 0.0

    fun observe(snapshot: BatterySnapshot) {
        val previous = lastSample
        if (previous != null && snapshot.charging && snapshot.currentMa != null) {
            val dtHours = (snapshot.timestamp - previous.timestamp).coerceAtLeast(0L) / 3_600_000.0
            if (dtHours in 0.0..0.05) sessionMah += abs(snapshot.currentMa) * dtHours
        }
        if (snapshot.charging && sessionStartLevel == null && snapshot.level < 99) {
            sessionStartLevel = snapshot.level
            sessionStartTime = snapshot.timestamp
            sessionMah = 0.0
        }
        if (sessionStartLevel != null && snapshot.level >= 99 && snapshot.charging) {
            val start = sessionStartLevel ?: snapshot.level
            val delta = (snapshot.level - start).coerceAtLeast(1)
            val estimate = if (sessionMah > 50.0) sessionMah * 100.0 / delta else 0.0
            if (estimate in 2500.0..6500.0) {
                store.addSession(ChargeSession(sessionStartTime ?: snapshot.timestamp, snapshot.timestamp, start, snapshot.level, sessionMah, estimate))
            }
            sessionStartLevel = null
            sessionStartTime = null
            sessionMah = 0.0
        }
        if (!snapshot.charging && sessionStartLevel != null && snapshot.level < 95) {
            sessionStartLevel = null
            sessionStartTime = null
            sessionMah = 0.0
        }
        if (previous == null || snapshot.timestamp - previous.timestamp >= 60_000L) {
            store.addSample(HistorySample(snapshot.timestamp, snapshot.level, snapshot.temperatureC, snapshot.voltageV, snapshot.currentMa))
        }
        lastSample = snapshot
    }
}
