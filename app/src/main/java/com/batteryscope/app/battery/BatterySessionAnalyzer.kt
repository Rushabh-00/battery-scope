package com.batteryscope.app.battery

import android.content.Context
import android.os.SystemClock

class BatterySessionAnalyzer(context: Context) {
    data class Analysis(
        val learnedCapacityMah: Double?,
        val latestSessionCapacityMah: Double?,
        val latestSessionQualityPercent: Int?,
        val healthPercent: Double?,
        val healthConfidencePercent: Int,
        val wearMah: Double?,
        val capacityTrend: CapacityTrendCalculator.Trend?,
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
            fullChargeSessions = state.fullChargeSessions,
        )
        val latest = state.fullChargeSessions.lastOrNull()
        return Analysis(
            learnedCapacityMah = tracker.learnedCapacityMah(),
            latestSessionCapacityMah = latest?.estimatedCapacityMah,
            latestSessionQualityPercent = latest?.qualityPercent,
            healthPercent = health.healthPercent,
            healthConfidencePercent = health.confidencePercent,
            wearMah = health.wearMah,
            capacityTrend = CapacityTrendCalculator.calculate(state.fullChargeSessions),
            chargeMah = state.totals.chargeMah,
            dischargeMah = state.totals.dischargeMah,
            chargeTimeMs = state.totals.chargeTimeMs,
            dischargeTimeMs = state.totals.dischargeTimeMs,
            fullChargeSessions = state.fullChargeSessions,
        )
    }
}
