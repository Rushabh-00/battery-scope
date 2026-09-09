package com.batteryscope.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import kotlin.math.abs

const val DESIGN_CAPACITY_MAH = 5000.0

data class BatterySnapshot(
    val timestamp: Long,
    val level: Int,
    val temperatureC: Double,
    val voltageV: Double,
    val status: String,
    val technology: String,
    val currentMa: Double?,
    val averageCurrentMa: Double?,
    val rawCurrentMa: Double?,
    val powerW: Double,
    val chargeTimeRemainingMs: Long?,
    val cycleCount: Int?,
    val counterMicroAh: Long?,
    val energyCounterNWh: Long?,
    val plugged: Boolean?
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

private fun propertyOrNull(manager: BatteryManager, id: Int): Long? =
    manager.getLongProperty(id).takeUnless { it == Long.MIN_VALUE }

private fun chargingState(status: Int, manager: BatteryManager): Boolean = when (status) {
    BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
    BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
    else -> manager.isCharging
}

private fun normalizedCurrentMa(rawMicroAmps: Long?, charging: Boolean): Double? {
    if (rawMicroAmps == null) return null
    val magnitude = abs(rawMicroAmps) / 1000.0
    if (magnitude < 0.5) return 0.0
    return if (charging) magnitude else -magnitude
}

fun readBattery(context: Context, currentScale: Double = 1.0): BatterySnapshot {
    val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val rawLevel = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
    val rawScale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val level = if (rawScale > 0) (rawLevel * 100.0 / rawScale).toInt().coerceIn(0, 100) else rawLevel.coerceIn(0, 100)
    val temperatureC = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
    val voltageV = (intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0) / 1000.0
    val statusRaw = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val status = when (statusRaw) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
        BatteryManager.BATTERY_STATUS_FULL -> "Full"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
        else -> "Unknown"
    }
    val charging = chargingState(statusRaw, bm)
    val plugged = intent?.let { it.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1).takeIf { p -> p >= 0 }?.let { p -> p != 0 } }

    val rawNowUa = propertyOrNull(bm, BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
    val rawAverageUa = propertyOrNull(bm, BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
    val nowMa = normalizedCurrentMa(rawNowUa, charging)
    val averageMa = normalizedCurrentMa(rawAverageUa, charging)
    val baseRawMa = when {
        nowMa != null && abs(nowMa) >= 0.5 -> nowMa
        averageMa != null -> averageMa
        else -> null
    }
    val effectiveMa = baseRawMa?.let { it * currentScale.coerceIn(0.25, 1000.0) }
    val powerMagnitudeW = effectiveMa?.let { abs(it) * voltageV / 1000.0 } ?: 0.0
    val signedPowerW = if (charging) powerMagnitudeW else -powerMagnitudeW

    val chargeTime = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        bm.computeChargeTimeRemaining().takeIf { it >= 0L }
    } else null
    val cycleCount = if (Build.VERSION.SDK_INT >= 34) {
        intent?.getIntExtra("android.os.extra.CYCLE_COUNT", -1)?.takeIf { it >= 0 }
    } else null
    val chargeCounter = propertyOrNull(bm, BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.takeIf { it >= 0L }
    val energyCounter = propertyOrNull(bm, BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)?.takeIf { it >= 0L }

    return BatterySnapshot(
        timestamp = System.currentTimeMillis(),
        level = level,
        temperatureC = temperatureC,
        voltageV = voltageV,
        status = status,
        technology = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "Unknown",
        currentMa = effectiveMa?.let { if (charging) abs(it) else -abs(it) },
        averageCurrentMa = averageMa?.let { it * currentScale.coerceIn(0.25, 1000.0) },
        rawCurrentMa = baseRawMa,
        powerW = signedPowerW,
        chargeTimeRemainingMs = chargeTime,
        cycleCount = cycleCount,
        counterMicroAh = chargeCounter,
        energyCounterNWh = energyCounter,
        plugged = plugged
    )
}

fun calculateCapacityMah(chargedMah: Double, percentageGain: Int): Double? =
    chargedMah.takeIf { it > 0.0 && percentageGain > 0 }?.let { it * 100.0 / percentageGain }

fun calculateWearCycles(chargedMah: Double): Double = (chargedMah / DESIGN_CAPACITY_MAH).coerceIn(0.0, 2.0)

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
    val valid = sessions.filter { it.endLevel - it.startLevel >= 60 && it.estimatedCapacityMah in 2500.0..6500.0 }
    if (valid.isEmpty()) return HealthEstimate(null, null, 0, 0, "Estimated from usable charging sessions")
    val recent = valid.takeLast(5)
    val capacity = recent.map { it.estimatedCapacityMah }.average()
    val health = (capacity / DESIGN_CAPACITY_MAH * 100.0).coerceIn(0.0, 120.0)
    return HealthEstimate(capacity, health, healthConfidence(recent.size), recent.size, "Rolling average of the last ${recent.size} usable sessions")
}

class MeasurementEngine(private val store: BatteryStore) {
    private var lastSample: BatterySnapshot? = null
    private var sessionStartLevel: Int? = null
    private var sessionStartTime: Long? = null
    private var sessionMah = 0.0
    private var sessionPeakVoltage = 0.0
    private var lastCheckpoint = 0L
    private var lastCalibration = 0L
    private var lastCalibrationCounter: Long? = null

    init {
        store.activeChargeSession()?.let { saved ->
            if (System.currentTimeMillis() - saved.startTime <= 24L * 60L * 60L * 1000L) {
                sessionStartLevel = saved.startLevel
                sessionStartTime = saved.startTime
                sessionMah = saved.chargedMah
                sessionPeakVoltage = saved.peakVoltageV
            } else {
                store.clearActiveChargeSession()
            }
        }
    }

    private fun checkpoint(snapshot: BatterySnapshot) {
        if (sessionStartLevel == null) return
        if (snapshot.timestamp - lastCheckpoint < 60_000L) return
        store.saveActiveChargeSession(
            ActiveChargeSession(
                startTime = sessionStartTime ?: snapshot.timestamp,
                startLevel = sessionStartLevel ?: snapshot.level,
                chargedMah = sessionMah,
                peakVoltageV = sessionPeakVoltage
            )
        )
        lastCheckpoint = snapshot.timestamp
    }

    private fun autoCalibrate(snapshot: BatterySnapshot) {
        if (!snapshot.charging) return
        val counter = snapshot.counterMicroAh ?: return
        if (lastCalibrationCounter == null) {
            lastCalibrationCounter = counter
            lastCalibration = snapshot.timestamp
            return
        }
        val elapsedMs = snapshot.timestamp - lastCalibration
        if (elapsedMs < 120_000L) return
        val deltaMicroAh = counter - lastCalibrationCounter!!
        val rawMa = abs(snapshot.rawCurrentMa ?: 0.0)
        lastCalibration = snapshot.timestamp
        lastCalibrationCounter = counter
        if (deltaMicroAh <= 5_000L || rawMa <= 0.5) return
        val dtHours = elapsedMs / 3_600_000.0
        val observedMa = abs(deltaMicroAh / dtHours / 1000.0)
        if (observedMa < 20.0) return
        val ratio = observedMa / rawMa
        if (!ratio.isFinite() || ratio < 0.25 || ratio > 1000.0) return
        val old = store.autoCurrentScale()
        val weight = if (ratio >= 8.0 || ratio <= 0.5) 0.45 else 0.15
        val updated = (old * (1.0 - weight) + ratio * weight).coerceIn(0.25, 1000.0)
        if (abs(updated - old) >= 0.01) store.setAutoCurrentScale(updated)
    }

    private fun finishSession(snapshot: BatterySnapshot) {
        val start = sessionStartLevel ?: return
        val end = snapshot.level.coerceAtLeast(start)
        val delta = end - start
        val capacity = calculateCapacityMah(sessionMah, delta)
        if (delta >= 60 && capacity != null && capacity in 2500.0..6500.0) {
            val wear = calculateWearCycles(sessionMah)
            val efficiency = if (wear > 0.0) (delta / (wear * 100.0) * 100.0).coerceIn(0.0, 120.0) else 0.0
            store.addSession(
                ChargeSession(
                    startTime = sessionStartTime ?: snapshot.timestamp,
                    endTime = snapshot.timestamp,
                    startLevel = start,
                    endLevel = end,
                    chargedMah = sessionMah,
                    estimatedCapacityMah = capacity,
                    endVoltageV = sessionPeakVoltage,
                    wearCycles = wear,
                    efficiencyPercent = efficiency
                )
            )
        }
        clearSession()
    }

    private fun clearSession() {
        sessionStartLevel = null
        sessionStartTime = null
        sessionMah = 0.0
        sessionPeakVoltage = 0.0
        lastCheckpoint = 0L
        lastCalibrationCounter = null
        store.clearActiveChargeSession()
    }

    fun observe(snapshot: BatterySnapshot) {
        val previous = lastSample
        autoCalibrate(snapshot)

        if (snapshot.charging && sessionStartLevel == null && snapshot.level < 100) {
            sessionStartLevel = snapshot.level
            sessionStartTime = snapshot.timestamp
            sessionMah = 0.0
            sessionPeakVoltage = snapshot.voltageV
            store.saveActiveChargeSession(ActiveChargeSession(snapshot.timestamp, snapshot.level, 0.0, snapshot.voltageV))
        }

        if (previous != null && previous.charging && snapshot.charging && snapshot.currentMa != null) {
            val dtHours = (snapshot.timestamp - previous.timestamp).coerceAtLeast(0L) / 3_600_000.0
            if (dtHours in 0.0..0.10) sessionMah += abs(snapshot.currentMa) * dtHours
        }
        if (sessionStartLevel != null && snapshot.charging) {
            sessionPeakVoltage = maxOf(sessionPeakVoltage, snapshot.voltageV)
        }

        if (sessionStartLevel != null && snapshot.charging && snapshot.level >= 99) {
            finishSession(snapshot)
        } else if (sessionStartLevel != null && previous?.charging == true && !snapshot.charging) {
            finishSession(previous)
        }

        checkpoint(snapshot)

        if (previous == null || snapshot.timestamp - previous.timestamp >= 60_000L) {
            store.addSample(HistorySample(snapshot.timestamp, snapshot.level, snapshot.temperatureC, snapshot.voltageV, snapshot.currentMa))
        }
        lastSample = snapshot
    }
}
