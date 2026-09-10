package com.batteryscope.app.battery

import android.content.Context
import java.util.Locale
import kotlin.math.abs

/** Persists charge/discharge flow and only creates a capacity measurement from a valid low-to-full charge session. */
class CapacitySessionTracker(context: Context) {
    data class FullChargeSession(
        val estimatedCapacityMah: Double,
        val chargedMah: Double,
        val durationMs: Long,
        val startedAtMs: Long,
        val completedAtMs: Long,
        val startLevelPercent: Int,
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
    )

    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private var lastTimeMs = 0L
    private var lastRemainingMah: Double? = null
    private var lastCharging = false
    private var armedForFullCharge = prefs.getBoolean(KEY_ARMED, false)
    private var armedStartLevelPercent = prefs.getInt(KEY_ARMED_START_LEVEL, -1)

    private var chargeMah = prefs.getString(KEY_CHARGE_MAH, "0")?.toDoubleOrNull() ?: 0.0
    private var dischargeMah = prefs.getString(KEY_DISCHARGE_MAH, "0")?.toDoubleOrNull() ?: 0.0
    private var chargeTimeMs = prefs.getLong(KEY_CHARGE_TIME_MS, 0L)
    private var dischargeTimeMs = prefs.getLong(KEY_DISCHARGE_TIME_MS, 0L)

    private var activeChargeStartedAtMs = 0L
    private var activeChargeMah = 0.0
    private var activeChargeDurationMs = 0L
    private var activeChargeStartLevelPercent = -1
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
            if (!charging && levelPercent <= 15) armForFullCharge(levelPercent)
            return state()
        }

        val deltaMs = (nowMs - lastTimeMs).coerceIn(0L, MAX_SAMPLE_GAP_MS)
        val hours = deltaMs / 3_600_000.0
        val currentBasedMah = currentA?.let { abs(it) * hours }?.takeIf { it.isFinite() } ?: 0.0
        val remainingDeltaMah = if (remainingMah != null && lastRemainingMah != null) {
            remainingMah - lastRemainingMah!!
        } else {
            0.0
        }
        val intervalMah = when {
            charging && remainingDeltaMah > MIN_RELIABLE_REMAINING_DELTA_MAH -> remainingDeltaMah
            !charging && remainingDeltaMah < -MIN_RELIABLE_REMAINING_DELTA_MAH -> -remainingDeltaMah
            else -> currentBasedMah
        }.coerceAtLeast(0.0)

        if (charging) {
            if (!lastCharging) {
                resetDischargeTotals()
                startChargeSession(nowMs)
            }
            chargeMah += intervalMah
            chargeTimeMs += deltaMs
            activeChargeMah += intervalMah
            activeChargeDurationMs += deltaMs

            if (full && armedForFullCharge) {
                finalizeFullCharge(nowMs, levelPercent)
            }
        } else {
            if (lastCharging) resetChargeTotals()
            dischargeMah += intervalMah
            dischargeTimeMs += deltaMs
            if (levelPercent <= 15) armForFullCharge(levelPercent)
            resetActiveChargeIfNeeded()
        }

        persistTotals()
        lastTimeMs = nowMs
        lastRemainingMah = remainingMah
        lastCharging = charging
        return state()
    }

    private fun state(): State = State(
        fullChargeSessions = sessions.toList(),
        totals = FlowTotals(chargeMah, dischargeMah, chargeTimeMs, dischargeTimeMs),
    )

    fun latestEstimatedCapacityMah(): Double? = sessions.lastOrNull()?.estimatedCapacityMah

    fun learnedCapacityMah(): Double? = sessions.takeLast(MAX_HEALTH_SESSIONS)
        .map { it.estimatedCapacityMah }
        .averageOrNull()

    private fun startChargeSession(nowMs: Long) {
        activeChargeStartedAtMs = nowMs
        activeChargeMah = 0.0
        activeChargeDurationMs = 0L
        activeChargeStartLevelPercent = if (armedStartLevelPercent >= 0) armedStartLevelPercent else 0
    }

    private fun finalizeFullCharge(nowMs: Long, levelPercent: Int) {
        val startLevel = activeChargeStartLevelPercent.takeIf { it in 0..15 } ?: return
        val denominator = (levelPercent - startLevel).coerceAtLeast(1)
        val measured = (activeChargeMah * 100.0 / denominator)
            .takeIf { it in MIN_CAPACITY_MAH..MAX_CAPACITY_MAH }
            ?: return

        sessions.add(
            FullChargeSession(
                estimatedCapacityMah = measured,
                chargedMah = activeChargeMah,
                durationMs = activeChargeDurationMs,
                startedAtMs = activeChargeStartedAtMs,
                completedAtMs = nowMs,
                startLevelPercent = startLevel,
            )
        )
        while (sessions.size > MAX_STORED_SESSIONS) sessions.removeAt(0)
        persistSessions()

        armedForFullCharge = false
        armedStartLevelPercent = -1
        prefs.edit()
            .putBoolean(KEY_ARMED, false)
            .remove(KEY_ARMED_START_LEVEL)
            .apply()
        resetActiveChargeIfNeeded()
    }

    private fun armForFullCharge(levelPercent: Int) {
        if (!armedForFullCharge) {
            armedForFullCharge = true
            armedStartLevelPercent = levelPercent
            prefs.edit()
                .putBoolean(KEY_ARMED, true)
                .putInt(KEY_ARMED_START_LEVEL, levelPercent)
                .apply()
        } else if (armedStartLevelPercent < 0) {
            armedStartLevelPercent = levelPercent
            prefs.edit().putInt(KEY_ARMED_START_LEVEL, levelPercent).apply()
        }
    }

    private fun resetActiveChargeIfNeeded() {
        if (!lastCharging) return
        resetActiveCharge()
    }

    private fun resetActiveCharge() {
        activeChargeStartedAtMs = 0L
        activeChargeMah = 0.0
        activeChargeDurationMs = 0L
        activeChargeStartLevelPercent = -1
    }

    private fun resetChargeTotals() {
        chargeMah = 0.0
        chargeTimeMs = 0L
    }

    private fun resetDischargeTotals() {
        dischargeMah = 0.0
        dischargeTimeMs = 0L
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
                it.startLevelPercent,
            ).joinToString(",")
        }
        prefs.edit().putString(KEY_SESSIONS, encoded).apply()
    }

    private fun loadSessions(): List<FullChargeSession> {
        val raw = prefs.getString(KEY_SESSIONS, null) ?: return emptyList()
        return raw.split(';').mapNotNull { item ->
            val parts = item.split(',')
            if (parts.size !in 5..6) return@mapNotNull null
            val capacity = parts[0].toDoubleOrNull() ?: return@mapNotNull null
            val charged = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            val duration = parts[2].toLongOrNull() ?: return@mapNotNull null
            val started = parts[3].toLongOrNull() ?: return@mapNotNull null
            val completed = parts[4].toLongOrNull() ?: return@mapNotNull null
            val startLevel = parts.getOrNull(5)?.toIntOrNull() ?: 15
            FullChargeSession(capacity, charged, duration, started, completed, startLevel)
        }
    }

    private fun List<Double>.averageOrNull(): Double? = takeIf { isNotEmpty() }?.average()

    companion object {
        private const val FILE_NAME = "battery_scope_sessions"
        private const val KEY_ARMED = "armed_for_full_charge"
        private const val KEY_ARMED_START_LEVEL = "armed_start_level"
        private const val KEY_CHARGE_MAH = "charge_mah"
        private const val KEY_DISCHARGE_MAH = "discharge_mah"
        private const val KEY_CHARGE_TIME_MS = "charge_time_ms"
        private const val KEY_DISCHARGE_TIME_MS = "discharge_time_ms"
        private const val KEY_SESSIONS = "full_charge_sessions"
        private const val MAX_SAMPLE_GAP_MS = 60_000L
        private const val MIN_RELIABLE_REMAINING_DELTA_MAH = 0.05
        private const val MAX_STORED_SESSIONS = 30
        private const val MAX_HEALTH_SESSIONS = 5
        private const val MIN_CAPACITY_MAH = 100.0
        private const val MAX_CAPACITY_MAH = 30_000.0
    }
}
