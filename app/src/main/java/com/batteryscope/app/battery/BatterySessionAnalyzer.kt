package com.batteryscope.app.battery

import android.content.Context
import android.os.SystemClock

class BatterySessionAnalyzer(context: Context) {
    data class Analysis(
        val learnedCapacityMah: Double?,
        val latestSessionCapacityMah: Double?,
        val healthPercent: Double?,
        val healthConfidencePercent: Int,
        val wearMah: Double?,
        val chargeMah: Double,
        val dischargeMah: Double,
        val chargeTimeMs: Long,
        val dischargeTimeMs: Long,
        val fullChargeSessions: List<CapacitySessionTracker.FullChargeSession>,
    )

    private val tracker = CapacitySessionTracker(context)

    fun learnedCapacityMah(): Double? = tracker.learnedCapacityMah()
    fun persistedTotals(): CapacitySessionTracker.FlowTotals = tracker.currentTotals()

    fun update(snapshot: BatterySnapshot): Analysis {
        val state = tracker.update(
            elapsedNowMs = SystemClock.elapsedRealtime(),
            wallNowMs = System.currentTimeMillis(),
            levelPercent = snapshot.levelPercent,
            charging = snapshot.charging,
            remainingMah = snapshot.remainingMah,
            currentA = snapshot.currentA,
            full = snapshot.full,
        )
        val health = BatteryHealthCalculator.calculate(
            designCapacityMah = snapshot.batteryCapacityMah,
            fullChargeCapacitiesMah = state.fullChargeSessions.map { it.estimatedCapacityMah },
        )
        return Analysis(
            learnedCapacityMah = tracker.learnedCapacityMah(),
            latestSessionCapacityMah = tracker.latestEstimatedCapacityMah(),
            healthPercent = health.healthPercent,
            healthConfidencePercent = health.confidencePercent,
            wearMah = health.wearMah,
            chargeMah = state.totals.chargeMah,
            dischargeMah = state.totals.dischargeMah,
            chargeTimeMs = state.totals.chargeTimeMs,
            dischargeTimeMs = state.totals.dischargeTimeMs,
            fullChargeSessions = state.fullChargeSessions,
        )
    }
}
