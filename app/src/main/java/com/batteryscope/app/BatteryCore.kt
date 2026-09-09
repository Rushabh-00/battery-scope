package com.batteryscope.app

import android.content.Context
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

const val CURRENT_CALIBRATION_MIN_MA = 20.0
const val CURRENT_CALIBRATION_MAX_RATIO = 100.0

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
    val chargeAh: Double? get() = counterMicroAh?.takeIf { it >= 0L }?.div(1_000_000.0)
    val energyWh: Double? get() = chargeAh?.times(voltageV)
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

private fun chooseInitialCurrentMa(nowMa: Double?, averageMa: Double?): Double? {
    val now = nowMa?.let(::abs)?.takeIf { it >= 0.5 }
    val average = averageMa?.let(::abs)?.takeIf { it >= 0.5 }
    return when {
        now == null -> average
        average == null -> now
        average < 1.0 -> now
        now / average in 0.125..8.0 -> now
        else -> average
    }
}

fun readBattery(context: Context, currentScale: Double = 1.0): BatterySnapshot {
    val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    val intent = context.registerReceiver(null, IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
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
    val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)?.takeIf { it >= 0 }?.let { it != 0 }

    val rawNowUa = propertyOrNull(bm, BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
    val rawAverageUa = propertyOrNull(bm, BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
    val nowMa = normalizedCurrentMa(rawNowUa, charging)
    val averageMa = normalizedCurrentMa(rawAverageUa, charging)
    val baseMagnitudeMa = chooseInitialCurrentMa(nowMa, averageMa)
    val baseRawMa = baseMagnitudeMa?.let { if (charging) it else -it }
    val scale = currentScale.coerceIn(0.25, 1000.0)
    val effectiveMa = baseRawMa?.let { it * scale }
    val powerW = effectiveMa?.let { it * voltageV / 1000.0 } ?: 0.0
    val chargeTime = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) bm.computeChargeTimeRemaining().takeIf { it >= 0L } else null
    val cycleCount = if (Build.VERSION.SDK_INT >= 34) intent?.getIntExtra("android.os.extra.CYCLE_COUNT", -1)?.takeIf { it >= 0 } else null
    val chargeCounter = propertyOrNull(bm, BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.takeIf { it >= 0L }
    val estimatedEnergyNWh = chargeCounter?.let {
        ((it / 1_000_000.0) * voltageV * 1_000_000_000.0).toLong().takeIf { n -> n >= 0L }
    }

    return BatterySnapshot(
        timestamp = System.currentTimeMillis(),
        level = level,
        temperatureC = temperatureC,
        voltageV = voltageV,
        status = status,
        technology = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "Unknown",
        currentMa = effectiveMa,
        averageCurrentMa = averageMa?.let { (if (charging) it else -it) * scale },
        rawCurrentMa = baseRawMa,
        powerW = powerW,
        chargeTimeRemainingMs = chargeTime,
        cycleCount = cycleCount,
        counterMicroAh = chargeCounter,
        energyCounterNWh = estimatedEnergyNWh,
        plugged = plugged
    )
}

fun calculateCapacityMah(chargedMah: Double, percentageGain: Int): Double? =
    chargedMah.takeIf { it > 0.0 && percentageGain > 0 }?.let { it * 100.0 / percentageGain }

fun healthConfidence(sessionCount: Int): Int = when {
    sessionCount <= 0 -> 0
    sessionCount == 1 -> 20
    sessionCount == 2 -> 35
    sessionCount == 3 -> 50
    sessionCount == 4 -> 60
    sessionCount <= 7 -> 75
    else -> 90
}

fun estimateHealth(sessions: List<ChargeSession>, referenceCapacityMah: Double? = null): HealthEstimate {
    val valid = sessions.filter { it.endLevel - it.startLevel >= 60 && it.estimatedCapacityMah > 0.0 }
    if (valid.isEmpty()) return HealthEstimate(null, null, 0, 0, "Estimated from usable charging sessions")
    val recent = valid.takeLast(5)
    val capacity = recent.map { it.estimatedCapacityMah }.average()
    val reference = referenceCapacityMah?.takeIf { it > 0.0 }
    val health = reference?.let { (capacity / it * 100.0).coerceIn(0.0, 120.0) }
    return HealthEstimate(capacity, health, healthConfidence(recent.size), recent.size, "Rolling average of the last ${recent.size} usable sessions")
}

class MeasurementEngine(private val store: BatteryStore) {
    private var lastSample: BatterySnapshot? = null
    private var sessionStartLevel: Int? = null
    private var sessionStartTime: Long? = null
    private var sessionMah = 0.0
    private var sessionPeakVoltage = 0.0
    private var lastCheckpoint = 0L
    private var calibrationCounter: Long? = null
    private var calibrationTimestamp = 0L

    init {
        store.resetAutoCurrentScale()
        store.activeChargeSession()?.let { saved ->
            if (System.currentTimeMillis() - saved.startTime <= 24L * 60L * 60L * 1000L) {
                sessionStartLevel = saved.startLevel
                sessionStartTime = saved.startTime
                sessionMah = saved.chargedMah
                sessionPeakVoltage = saved.peakVoltageV
            } else store.clearActiveChargeSession()
        }
    }

    private fun checkpoint(snapshot: BatterySnapshot) {
        if (sessionStartLevel == null || snapshot.timestamp - lastCheckpoint < 60_000L) return
        store.saveActiveChargeSession(
            ActiveChargeSession(
                sessionStartTime ?: snapshot.timestamp,
                sessionStartLevel ?: snapshot.level,
                sessionMah,
                sessionPeakVoltage
            )
        )
        lastCheckpoint = snapshot.timestamp
    }

    private fun autoCalibrate(snapshot: BatterySnapshot) {
        val counter = snapshot.counterMicroAh ?: run {
            calibrationCounter = null
            calibrationTimestamp = 0L
            return
        }
        val previousCounter = calibrationCounter
        val previousTimestamp = calibrationTimestamp
        calibrationCounter = counter
        calibrationTimestamp = snapshot.timestamp
        if (previousCounter == null || previousTimestamp <= 0L) return

        val elapsedMs = snapshot.timestamp - previousTimestamp
        if (elapsedMs !in 30_000L..300_000L) return

        val counterDeltaMicroAh = counter - previousCounter
        val observedMa = abs(counterDeltaMicroAh / (elapsedMs / 3_600_000.0) / 1000.0)
        val rawMa = abs(snapshot.rawCurrentMa ?: return)
        if (counterDeltaMicroAh == 0L || observedMa < CURRENT_CALIBRATION_MIN_MA || rawMa < 0.5) return

        val ratio = observedMa / rawMa
        if (!ratio.isFinite() || ratio !in 0.25..CURRENT_CALIBRATION_MAX_RATIO) return

        val previousScale = store.autoCurrentScale()
        val smoothing = when {
            ratio / previousScale in 0.8..1.25 -> 0.30
            ratio / previousScale in 0.5..2.0 -> 0.20
            else -> 0.10
        }
        val updatedScale = (previousScale * (1.0 - smoothing) + ratio * smoothing).coerceIn(0.25, 1000.0)
        store.setAutoCurrentScale(updatedScale)
    }

    private fun finishSession(snapshot: BatterySnapshot) {
        val start = sessionStartLevel ?: return
        val end = snapshot.level.coerceAtLeast(start)
        val delta = end - start
        val capacity = calculateCapacityMah(sessionMah, delta)
        if (delta >= 60 && capacity != null && capacity > 0.0) {
            val reference = store.referenceCapacityMah()
            val wear = if (reference != null) (sessionMah / reference).coerceIn(0.0, 2.0) else 0.0
            val efficiency = if (wear > 0.0) (delta / (wear * 100.0) * 100.0).coerceIn(0.0, 120.0) else 0.0
            store.addSession(
                ChargeSession(
                    sessionStartTime ?: snapshot.timestamp,
                    snapshot.timestamp,
                    start,
                    end,
                    sessionMah,
                    capacity,
                    sessionPeakVoltage,
                    wear,
                    efficiency
                )
            )
            store.learnReferenceCapacity(capacity)
        }
        clearSession()
    }

    private fun clearSession() {
        sessionStartLevel = null
        sessionStartTime = null
        sessionMah = 0.0
        sessionPeakVoltage = 0.0
        lastCheckpoint = 0L
        calibrationCounter = null
        calibrationTimestamp = 0L
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
        if (sessionStartLevel != null && snapshot.charging) sessionPeakVoltage = max(sessionPeakVoltage, snapshot.voltageV)
        if (sessionStartLevel != null && snapshot.charging && snapshot.level >= 99) finishSession(snapshot)
        else if (sessionStartLevel != null && previous?.charging == true && !snapshot.charging) finishSession(previous)
        checkpoint(snapshot)
        if (previous == null || snapshot.timestamp - previous.timestamp >= 60_000L) {
            store.addSample(HistorySample(snapshot.timestamp, snapshot.level, snapshot.temperatureC, snapshot.voltageV, snapshot.currentMa))
        }
        lastSample = snapshot
    }
}
