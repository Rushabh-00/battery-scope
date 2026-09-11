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

    data class FlowTotals(val chargeMah: Double, val dischargeMah: Double, val chargeTimeMs: Long, val dischargeTimeMs: Long)
    data class State(val fullChargeSessions: List<FullChargeSession>, val totals: FlowTotals)

    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private var lastTimeMs = 0L
    private var lastRemainingMah: Double? = null
    private var lastCharging = prefs.getBoolean(KEY_LAST_CHARGING, false)
    private var armedForFullCharge = prefs.getBoolean(KEY_ARMED, false)
    private var armedStartLevelPercent = prefs.getInt(KEY_ARMED_START_LEVEL, -1)
    private var chargeMah = prefs.getString(KEY_CHARGE_MAH, "0")?.toDoubleOrNull()?.takeIf { it >= 0.0 } ?: 0.0
    private var dischargeMah = prefs.getString(KEY_DISCHARGE_MAH, "0")?.toDoubleOrNull()?.takeIf { it >= 0.0 } ?: 0.0
    private var chargeTimeMs = prefs.getLong(KEY_CHARGE_TIME_MS, 0L).coerceAtLeast(0L)
    private var dischargeTimeMs = prefs.getLong(KEY_DISCHARGE_TIME_MS, 0L).coerceAtLeast(0L)
    private var activeChargeStartedAtMs = prefs.getLong(KEY_ACTIVE_CHARGE_STARTED_AT_MS, 0L).coerceAtLeast(0L)
    private var activeChargeMah = prefs.getString(KEY_ACTIVE_CHARGE_MAH, "0")?.toDoubleOrNull()?.takeIf { it >= 0.0 } ?: 0.0
    private var activeChargeDurationMs = prefs.getLong(KEY_ACTIVE_CHARGE_DURATION_MS, 0L).coerceAtLeast(0L)
    private var activeChargeStartLevelPercent = prefs.getInt(KEY_ACTIVE_CHARGE_START_LEVEL, -1)
    private val sessions = loadSessions().toMutableList()
    private var sessionSnapshot: List<FullChargeSession> = sessions.toList()
    private var cachedLearnedCapacityMah: Double? = calculateLearnedCapacity(sessionSnapshot)
    private var lastPersistedAtMs = 0L

    fun currentTotals(): FlowTotals = FlowTotals(chargeMah, dischargeMah, chargeTimeMs, dischargeTimeMs)

    fun update(nowMs: Long, levelPercent: Int, charging: Boolean, remainingMah: Double?, currentA: Double?, full: Boolean): State {
        if (lastTimeMs == 0L) {
            if (charging != lastCharging) {
                if (charging) resetDischargeTotals() else resetChargeTotals()
            }

            if (charging && armedForFullCharge && activeChargeStartedAtMs == 0L) {
                startChargeSession(nowMs)
            }

            lastTimeMs = nowMs
            lastRemainingMah = remainingMah
            lastCharging = charging
            if (!charging && levelPercent <= 15) armForFullCharge(levelPercent)
            persistTotals(nowMs)
            return state()
        }

        val modeChanged = charging != lastCharging
        val deltaMs = (nowMs - lastTimeMs).coerceIn(0L, MAX_SAMPLE_GAP_MS)
        val hours = deltaMs / 3_600_000.0
        val currentBasedMah = currentA?.let { abs(it) * hours }?.takeIf { it.isFinite() } ?: 0.0
        val remainingDeltaMah = if (remainingMah != null && lastRemainingMah != null) remainingMah - lastRemainingMah!! else 0.0
        val intervalMah = when {
            charging && remainingDeltaMah > MIN_RELIABLE_REMAINING_DELTA_MAH -> remainingDeltaMah
            !charging && remainingDeltaMah < -MIN_RELIABLE_REMAINING_DELTA_MAH -> -remainingDeltaMah
            else -> currentBasedMah
        }.coerceAtLeast(0.0)

        var completedSession = false
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
                completedSession = finalizeFullCharge(nowMs, levelPercent)
            }
        } else {
            if (lastCharging) resetChargeTotals()
            dischargeMah += intervalMah
            dischargeTimeMs += deltaMs
            if (levelPercent <= 15) armForFullCharge(levelPercent)
            resetActiveChargeIfNeeded()
        }

        lastTimeMs = nowMs
        lastRemainingMah = remainingMah
        lastCharging = charging
        if (modeChanged || completedSession || nowMs - lastPersistedAtMs >= PERSIST_INTERVAL_MS) {
            persistTotals(nowMs)
        }
        return state()
    }

    private fun state(): State = State(sessionSnapshot, FlowTotals(chargeMah, dischargeMah, chargeTimeMs, dischargeTimeMs))

    fun latestEstimatedCapacityMah(): Double? = sessionSnapshot.lastOrNull()?.estimatedCapacityMah

    fun learnedCapacityMah(): Double? = cachedLearnedCapacityMah

    private fun startChargeSession(nowMs: Long) {
        activeChargeStartedAtMs = nowMs
        activeChargeMah = 0.0
        activeChargeDurationMs = 0L
        activeChargeStartLevelPercent = if (armedStartLevelPercent >= 0) armedStartLevelPercent else 0
    }

    private fun finalizeFullCharge(nowMs: Long, levelPercent: Int): Boolean {
        val startLevel = activeChargeStartLevelPercent
        val measured = CapacitySessionEstimator.estimate(activeChargeMah, startLevel, levelPercent) ?: return false
        sessions.add(FullChargeSession(measured, activeChargeMah, activeChargeDurationMs, activeChargeStartedAtMs, nowMs, startLevel))
        while (sessions.size > MAX_STORED_SESSIONS) sessions.removeAt(0)
        sessionSnapshot = sessions.toList()
        cachedLearnedCapacityMah = calculateLearnedCapacity(sessionSnapshot)
        persistSessions()
        armedForFullCharge = false
        armedStartLevelPercent = -1
        prefs.edit().putBoolean(KEY_ARMED, false).remove(KEY_ARMED_START_LEVEL).apply()
        resetActiveCharge()
        return true
    }

    private fun armForFullCharge(levelPercent: Int) {
        if (!armedForFullCharge) {
            armedForFullCharge = true
            armedStartLevelPercent = levelPercent
            prefs.edit().putBoolean(KEY_ARMED, true).putInt(KEY_ARMED_START_LEVEL, levelPercent).apply()
        } else if (armedStartLevelPercent < 0) {
            armedStartLevelPercent = levelPercent
            prefs.edit().putInt(KEY_ARMED_START_LEVEL, levelPercent).apply()
        }
    }

    private fun resetActiveChargeIfNeeded() {
        if (lastCharging) resetActiveCharge()
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

    private fun persistTotals(nowMs: Long) {
        prefs.edit()
            .putBoolean(KEY_LAST_CHARGING, lastCharging)
            .putString(KEY_CHARGE_MAH, String.format(Locale.US, "%.6f", chargeMah))
            .putString(KEY_DISCHARGE_MAH, String.format(Locale.US, "%.6f", dischargeMah))
            .putLong(KEY_CHARGE_TIME_MS, chargeTimeMs)
            .putLong(KEY_DISCHARGE_TIME_MS, dischargeTimeMs)
            .putLong(KEY_ACTIVE_CHARGE_STARTED_AT_MS, activeChargeStartedAtMs)
            .putString(KEY_ACTIVE_CHARGE_MAH, String.format(Locale.US, "%.6f", activeChargeMah))
            .putLong(KEY_ACTIVE_CHARGE_DURATION_MS, activeChargeDurationMs)
            .putInt(KEY_ACTIVE_CHARGE_START_LEVEL, activeChargeStartLevelPercent)
            .apply()
        lastPersistedAtMs = nowMs
    }

    private fun persistSessions() {
        prefs.edit().putString(KEY_SESSIONS, sessions.joinToString(";") { listOf(it.estimatedCapacityMah, it.chargedMah, it.durationMs, it.startedAtMs, it.completedAtMs, it.startLevelPercent).joinToString(",") }).apply()
    }

    private fun loadSessions(): List<FullChargeSession> {
        val raw = prefs.getString(KEY_SESSIONS, null) ?: return emptyList()
        return raw.split(';').mapNotNull { item ->
            val parts = item.split(',')
            if (parts.size !in 5..6) return@mapNotNull null
            val capacity = parts[0].toDoubleOrNull()?.takeIf { it in MIN_CAPACITY_MAH..MAX_CAPACITY_MAH } ?: return@mapNotNull null
            val charged = parts[1].toDoubleOrNull()?.takeIf { it >= 0.0 } ?: return@mapNotNull null
            val duration = parts[2].toLongOrNull()?.takeIf { it >= 0L } ?: return@mapNotNull null
            val started = parts[3].toLongOrNull()?.takeIf { it >= 0L } ?: return@mapNotNull null
            val completed = parts[4].toLongOrNull()?.takeIf { it >= started } ?: return@mapNotNull null
            val startLevel = parts.getOrNull(5)?.toIntOrNull() ?: 15
            if (startLevel !in 0..15) return@mapNotNull null
            FullChargeSession(capacity, charged, duration, started, completed, startLevel)
        }.takeLast(MAX_STORED_SESSIONS)
    }

    private fun calculateLearnedCapacity(items: List<FullChargeSession>): Double? {
        val count = minOf(items.size, MAX_HEALTH_SESSIONS)
        if (count == 0) return null
        var total = 0.0
        for (index in items.size - count until items.size) {
            total += items[index].estimatedCapacityMah
        }
        return total / count
    }

    companion object {
        private const val FILE_NAME = "battery_scope_sessions"
        private const val KEY_ARMED = "armed_for_full_charge"
        private const val KEY_ARMED_START_LEVEL = "armed_start_level"
        private const val KEY_LAST_CHARGING = "last_charging"
        private const val KEY_CHARGE_MAH = "charge_mah"
        private const val KEY_DISCHARGE_MAH = "discharge_mah"
        private const val KEY_CHARGE_TIME_MS = "charge_time_ms"
        private const val KEY_DISCHARGE_TIME_MS = "discharge_time_ms"
        private const val KEY_ACTIVE_CHARGE_STARTED_AT_MS = "active_charge_started_at_ms"
        private const val KEY_ACTIVE_CHARGE_MAH = "active_charge_mah"
        private const val KEY_ACTIVE_CHARGE_DURATION_MS = "active_charge_duration_ms"
        private const val KEY_ACTIVE_CHARGE_START_LEVEL = "active_charge_start_level"
        private const val KEY_SESSIONS = "full_charge_sessions"
        private const val MAX_SAMPLE_GAP_MS = 60_000L
        private const val PERSIST_INTERVAL_MS = 15_000L
        private const val MIN_RELIABLE_REMAINING_DELTA_MAH = 0.05
        private const val MAX_STORED_SESSIONS = 30
        private const val MAX_HEALTH_SESSIONS = 5
        private const val MIN_CAPACITY_MAH = 100.0
        private const val MAX_CAPACITY_MAH = 30_000.0
    }
}
