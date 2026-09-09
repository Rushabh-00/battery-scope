package com.batteryscope.app.battery

import android.content.Context

class BatterySessionAnalyzer(context: Context) {
    data class Analysis(
        val learnedCapacityMah: Double?,
        val latestSessionCapacityMah: Double?,
        val healthPercent: Double?,
        val wearMah: Double?,
        val fullChargeSessionCount: Int,
        val chargeMah: Double,
        val dischargeMah: Double,
        val chargeTimeMs: Long,
        val dischargeTimeMs: Long,
        val eligibleForFullMeasurement: Boolean,
        val activeChargeMah: Double,
        val activeChargeTimeMs: Long,
        val activeChargeStartLevelPercent: Int?,
        val fullChargeSessions: List<CapacitySessionTracker.FullChargeSession>,
    )

    private val tracker = CapacitySessionTracker(context)
    private val capacityPreferences = CapacityPreferences(context)

    fun update(snapshot: BatterySnapshot, nowMs: Long = System.currentTimeMillis()): Analysis {
        val state = tracker.update(
            nowMs = nowMs,
            levelPercent = snapshot.levelPercent,
            charging = snapshot.charging,
            remainingMah = snapshot.remainingMah,
            currentA = snapshot.currentA,
            full = snapshot.full,
        )

        val referenceCapacity = snapshot.batteryCapacityMah ?: capacityPreferences.designCapacityMah
        val health = BatteryHealthCalculator.calculate(
            designCapacityMah = referenceCapacity,
            fullChargeCapacitiesMah = state.fullChargeSessions.map { it.estimatedCapacityMah },
        )
        return Analysis(
            learnedCapacityMah = tracker.learnedCapacityMah(),
            latestSessionCapacityMah = tracker.latestEstimatedCapacityMah(),
            healthPercent = health.healthPercent,
            wearMah = health.wearMah,
            fullChargeSessionCount = state.fullChargeSessions.size,
            chargeMah = state.totals.chargeMah,
            dischargeMah = state.totals.dischargeMah,
            chargeTimeMs = state.totals.chargeTimeMs,
            dischargeTimeMs = state.totals.dischargeTimeMs,
            eligibleForFullMeasurement = state.eligibleForFullMeasurement,
            activeChargeMah = state.activeChargeMah,
            activeChargeTimeMs = state.activeChargeTimeMs,
            activeChargeStartLevelPercent = state.activeChargeStartLevelPercent,
            fullChargeSessions = state.fullChargeSessions,
        )
    }

    fun setManualDesignCapacityMah(value: Double?) {
        capacityPreferences.designCapacityMah = value
    }
}
