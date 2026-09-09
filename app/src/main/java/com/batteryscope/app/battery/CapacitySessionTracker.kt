package com.batteryscope.app.battery

import android.content.Context
import java.util.Locale
import kotlin.math.abs

/**
 * Persists charge/discharge sessions and learns full capacity from charge
 * sessions that begin after the battery has reached <=15% while discharging.
 * The tracker is intentionally UI-independent so the future monitor service
 * can feed it the same samples.
 */
class CapacitySessionTracker(context: Context) {
    data class FullChargeSession(
        val estimatedCapacityMah: Double,
        val chargedMah: Double,
        val durationMs: Long,
        val startedAtMs: Long,
        val completedAtMs: Long,
    )

    data class FlowTotals(
        val chargeMah: Double,
        val dischargeMah: Double,
        val chargeTimeMs: Long,
        val dischargeTimeMs: Long,
    )

    data class State(
        val fullChargeSessions: List<FullChargeSession>,
        val totals: FlowTotals,
        val eligibleForFullMeasurement: Boolean,
    )

    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private var lastTimeMs = 0L
    private var lastRemainingMah: Double? = null
    private var lastCharging = false
    private var lastLevel = -1
    private var armedForFullCharge = prefs.getBoolean(KEY_ARMED, false)

    private var chargeMah = prefs.getString(KEY_CHARGE_MAH, "0")?.toDoubleOrNull() ?: 0.0
    private var dischargeMah = prefs.getString(KEY_DISCHARGE_MAH, "0")?.toDoubleOrNull() ?: 0.0
    private var chargeTimeMs = prefs.getLong(KEY_CHARGE_TIME_MS, 0L)
    private var dischargeTimeMs = prefs.getLong(KEY_DISCHARGE_TIME_MS, 0L)

    private var activeChargeStartedAtMs = 0L
    private var activeChargeMah = 0.0
    private var activeChargeDurationMs = 0L
    private val sessions = loadSessions().toMutableList()

    fun update(
        nowMs: Long,
        levelPercent: Int,
        charging: Boolean,
        remainingMah: Double?,
        currentA: Double?,
        full: Boolean,
    ): State {
        if (lastTimeMs == 0L) {
            lastTimeMs = nowMs
            lastRemainingMah = remainingMah
            lastCharging = charging
            lastLevel = levelPercent
            if (!charging && levelPercent in 0..15) armForFullCharge()
            return state()
        }

        val deltaMs = (nowMs - lastTimeMs).coerceIn(0L, MAX_SAMPLE_GAP_MS)
        val hours = deltaMs / 3_600_000.0
        val intervalMah = currentA?.let { abs(it) * hours }?.takeIf { it.isFinite() } ?: 0.0

        if (charging) {
            chargeMah += intervalMah
            chargeTimeMs += deltaMs
            activeChargeMah += intervalMah
            activeChargeDurationMs += deltaMs
            if (!lastCharging) {
                activeChargeStartedAtMs = nowMs
                activeChargeMah = 0.0
                activeChargeDurationMs = 0L
            }
            if (full && armedForFullCharge) {
                finalizeFullCharge(nowMs, levelPercent, remainingMah)
            }
        } else {
            dischargeMah += intervalMah
            dischargeTimeMs += deltaMs
            if (levelPercent in 0..15) armForFullCharge()
            if (lastCharging) {
                activeChargeStartedAtMs = 0L
                activeChargeMah = 0.0
                activeChargeDurationMs = 0L
            }
        }

        persistTotals()
        lastTimeMs = nowMs
        lastRemainingMah = remainingMah
        lastCharging = charging
        lastLevel = levelPercent
        return state()
    }

    fun state(): State = State(
        fullChargeSessions = sessions.toList(),
        totals = FlowTotals(chargeMah, dischargeMah, chargeTimeMs, dischargeTimeMs),
        eligibleForFullMeasurement = armedForFullCharge,
    )

    fun latestEstimatedCapacityMah(): Double? = sessions.lastOrNull()?.estimatedCapacityMah

    fun learnedCapacityMah(): Double? =
        sessions.takeLast(MAX_HEALTH_SESSIONS).map { it.estimatedCapacityMah }.averageOrNull()

    private fun finalizeFullCharge(nowMs: Long, levelPercent: Int, remainingMah: Double?) {
        val measured = when {
            remainingMah != null && remainingMah > 0.0 && levelPercent >= 95 -> remainingMah
            lastRemainingMah != null && remainingMah != null && levelPercent >= 95 -> {
                val start = lastRemainingMah ?: 0.0
                (start + activeChargeMah).takeIf { it > 0.0 }
            }
            else -> null
        }?.takeIf { it in MIN_CAPACITY_MAH..MAX_CAPACITY_MAH }

        if (measured == null) return

        val startedAt = if (activeChargeStartedAtMs > 0L) activeChargeStartedAtMs else nowMs - activeChargeDurationMs
        sessions.add(
            FullChargeSession(
                estimatedCapacityMah = measured,
                chargedMah = activeChargeMah,
                durationMs = activeChargeDurationMs,
                startedAtMs = startedAt,
                completedAtMs = nowMs,
            )
        )
        while (sessions.size > MAX_STORED_SESSIONS) sessions.removeAt(0)
        persistSessions()
        armedForFullCharge = false
        prefs.edit().putBoolean(KEY_ARMED, false).apply()
        activeChargeStartedAtMs = 0L
        activeChargeMah = 0.0
        activeChargeDurationMs = 0L
    }

    private fun armForFullCharge() {
        if (!armedForFullCharge) {
            armedForFullCharge = true
            prefs.edit().putBoolean(KEY_ARMED, true).apply()
        }
    }

    private fun persistTotals() {
        prefs.edit()
            .putString(KEY_CHARGE_MAH, String.format(Locale.US, "%.6f", chargeMah))
            .putString(KEY_DISCHARGE_MAH, String.format(Locale.US, "%.6f", dischargeMah))
            .putLong(KEY_CHARGE_TIME_MS, chargeTimeMs)
            .putLong(KEY_DISCHARGE_TIME_MS, dischargeTimeMs)
            .apply()
    }

    private fun persistSessions() {
        val encoded = sessions.joinToString(";") {
            listOf(
                it.estimatedCapacityMah,
                it.chargedMah,
                it.durationMs,
                it.startedAtMs,
                it.completedAtMs,
            ).joinToString(",")
        }
        prefs.edit().putString(KEY_SESSIONS, encoded).apply()
    }

    private fun loadSessions(): List<FullChargeSession> {
        val raw = prefs.getString(KEY_SESSIONS, null) ?: return emptyList()
        return raw.split(';').mapNotNull { item ->
            val parts = item.split(',')
            if (parts.size != 5) return@mapNotNull null
            val capacity = parts[0].toDoubleOrNull() ?: return@mapNotNull null
            val charged = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            val duration = parts[2].toLongOrNull() ?: return@mapNotNull null
            val started = parts[3].toLongOrNull() ?: return@mapNotNull null
            val completed = parts[4].toLongOrNull() ?: return@mapNotNull null
            FullChargeSession(capacity, charged, duration, started, completed)
        }
    }

    private fun List<Double>.averageOrNull(): Double? = takeIf { isNotEmpty() }?.average()

    companion object {
        private const val FILE_NAME = "battery_scope_sessions"
        private const val KEY_ARMED = "armed_for_full_charge"
        private const val KEY_CHARGE_MAH = "charge_mah"
        private const val KEY_DISCHARGE_MAH = "discharge_mah"
        private const val KEY_CHARGE_TIME_MS = "charge_time_ms"
        private const val KEY_DISCHARGE_TIME_MS = "discharge_time_ms"
        private const val KEY_SESSIONS = "full_charge_sessions"
        private const val MAX_SAMPLE_GAP_MS = 60_000L
        private const val MAX_STORED_SESSIONS = 30
        private const val MAX_HEALTH_SESSIONS = 5
        private const val MIN_CAPACITY_MAH = 100.0
        private const val MAX_CAPACITY_MAH = 30_000.0
    }
}
