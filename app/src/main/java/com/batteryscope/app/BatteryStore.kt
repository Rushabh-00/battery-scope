package com.batteryscope.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class UiSettings(
    val currentUnit: String = "A",
    val chargeUnit: String = "Ah",
    val energyUnit: String = "Wh",
    val temperatureF: Boolean = false,
    val theme: String = "AUTO",
    val showChargeTime: Boolean = true,
    val showScreenState: Boolean = false,
    val updateIntervalSeconds: Int = 5,
    val notificationEnabled: Boolean = true,
    val notificationIndicator: String = "W",
    val notificationEntries: Set<String> = setOf("W", "A", "mAh", "°C", "V", "Wh", "%")
)

data class ActiveChargeSession(
    val startTime: Long,
    val startLevel: Int,
    val chargedMah: Double,
    val peakVoltageV: Double
)

class BatteryStore(context: Context) {
    private val prefs = context.getSharedPreferences("battery_scope", Context.MODE_PRIVATE)

    fun settings(): UiSettings = UiSettings(
        currentUnit = prefs.getString("currentUnit", "A") ?: "A",
        chargeUnit = prefs.getString("chargeUnit", "Ah") ?: "Ah",
        energyUnit = "Wh",
        temperatureF = prefs.getBoolean("temperatureF", false),
        theme = prefs.getString("theme", "AUTO") ?: "AUTO",
        showChargeTime = prefs.getBoolean("showChargeTime", true),
        showScreenState = prefs.getBoolean("showScreenState", false),
        updateIntervalSeconds = prefs.getInt("updateIntervalSeconds", 5).coerceIn(2, 30),
        notificationEnabled = prefs.getBoolean("notificationEnabled", true),
        notificationIndicator = prefs.getString("notificationIndicator", "W") ?: "W",
        notificationEntries = prefs.getStringSet("notificationEntries", setOf("W", "A", "mAh", "°C", "V", "Wh", "%")) ?: setOf("W", "A", "mAh", "°C", "V", "Wh", "%")
    )

    @Synchronized
    fun saveSettings(value: UiSettings) {
        prefs.edit().putString("currentUnit", value.currentUnit).putString("chargeUnit", value.chargeUnit)
            .putBoolean("temperatureF", value.temperatureF).putString("theme", value.theme)
            .putBoolean("showChargeTime", value.showChargeTime).putBoolean("showScreenState", value.showScreenState)
            .putInt("updateIntervalSeconds", value.updateIntervalSeconds.coerceIn(2, 30))
            .putBoolean("notificationEnabled", value.notificationEnabled).putString("notificationIndicator", value.notificationIndicator)
            .putStringSet("notificationEntries", value.notificationEntries).apply()
    }

    fun autoCurrentScale(): Double = prefs.getFloat("autoCurrentScale", 1f).toDouble().coerceIn(0.25, 1000.0)
    @Synchronized fun setAutoCurrentScale(value: Double) = prefs.edit().putFloat("autoCurrentScale", value.coerceIn(0.25, 1000.0).toFloat()).apply()
    @Synchronized fun resetAutoCurrentScale() = prefs.edit().remove("autoCurrentScale").apply()

    fun calibrationCompleted(): Boolean = prefs.getBoolean("calibrationCompleted", false)
    @Synchronized fun markCalibrationCompleted() = prefs.edit().putBoolean("calibrationCompleted", true).apply()
    @Synchronized fun clearCalibration() = prefs.edit().putBoolean("calibrationCompleted", false).remove("autoCurrentScale").apply()

    fun referenceCapacityMah(): Double? = prefs.getString("referenceCapacityMah", null)?.toDoubleOrNull()?.takeIf { it in 1000.0..20000.0 }
    @Synchronized fun learnReferenceCapacity(capacityMah: Double) {
        if (!capacityMah.isFinite() || capacityMah !in 1000.0..20000.0) return
        val previous = referenceCapacityMah()
        val updated = if (previous == null) capacityMah else previous * 0.85 + capacityMah * 0.15
        prefs.edit().putString("referenceCapacityMah", updated.toString()).apply()
    }

    fun screenTimeMillis(): Long = prefs.getLong("screenTimeMillis", 0L).coerceAtLeast(0L)
    fun screenTimeSessionStartMillis(): Long = prefs.getLong("screenTimeSessionStartMillis", 0L)
    fun chargingSinceMillis(): Long? = prefs.getLong("chargingSinceMillis", 0L).takeIf { it > 0L }
    @Synchronized fun saveScreenTime(totalMillis: Long, sessionStartMillis: Long) = prefs.edit().putLong("screenTimeMillis", totalMillis.coerceAtLeast(0L)).putLong("screenTimeSessionStartMillis", sessionStartMillis.coerceAtLeast(0L)).apply()
    @Synchronized fun saveChargingSince(value: Long?) { val edit = prefs.edit(); if (value == null) edit.remove("chargingSinceMillis") else edit.putLong("chargingSinceMillis", value); edit.apply() }

    fun activeChargeSession(): ActiveChargeSession? {
        val start = prefs.getLong("activeSessionStartTime", 0L)
        if (start <= 0L) return null
        return ActiveChargeSession(start, prefs.getInt("activeSessionStartLevel", 0), prefs.getString("activeSessionMah", "0")?.toDoubleOrNull() ?: 0.0, prefs.getString("activeSessionPeakVoltage", "0")?.toDoubleOrNull() ?: 0.0)
    }
    @Synchronized fun saveActiveChargeSession(session: ActiveChargeSession) = prefs.edit().putLong("activeSessionStartTime", session.startTime).putInt("activeSessionStartLevel", session.startLevel).putString("activeSessionMah", session.chargedMah.toString()).putString("activeSessionPeakVoltage", session.peakVoltageV.toString()).apply()
    @Synchronized fun clearActiveChargeSession() = prefs.edit().remove("activeSessionStartTime").remove("activeSessionStartLevel").remove("activeSessionMah").remove("activeSessionPeakVoltage").apply()

    @Synchronized fun addSample(sample: HistorySample) {
        val list = samples().toMutableList().apply { add(sample) }
        val array = JSONArray()
        list.takeLast(1000).forEach { s -> array.put(JSONObject().apply { put("t", s.timestamp); put("l", s.level); put("temp", s.temperatureC); put("v", s.voltageV); put("i", s.currentMa ?: JSONObject.NULL) }) }
        prefs.edit().putString("samples", array.toString()).apply()
    }
    @Synchronized fun samples(): List<HistorySample> {
        val raw = prefs.getString("samples", null) ?: return emptyList()
        return runCatching { val a = JSONArray(raw); buildList { for (i in 0 until a.length()) { val o = a.getJSONObject(i); add(HistorySample(o.getLong("t"), o.getInt("l"), o.getDouble("temp"), o.getDouble("v"), if (o.isNull("i")) null else o.getDouble("i"))) } } }.getOrElse { emptyList() }
    }
    @Synchronized fun addSession(session: ChargeSession) {
        if (sessions().any { kotlin.math.abs(it.startTime - session.startTime) < 5_000L }) return
        val list = sessions().toMutableList().apply { add(session) }
        val array = JSONArray()
        list.takeLast(50).forEach { s -> array.put(JSONObject().apply { put("start", s.startTime); put("end", s.endTime); put("sl", s.startLevel); put("el", s.endLevel); put("mah", s.chargedMah); put("cap", s.estimatedCapacityMah); put("endV", s.endVoltageV); put("wear", s.wearCycles); put("eff", s.efficiencyPercent) }) }
        prefs.edit().putString("sessions", array.toString()).apply()
    }
    @Synchronized fun sessions(): List<ChargeSession> {
        val raw = prefs.getString("sessions", null) ?: return emptyList()
        return runCatching { val a = JSONArray(raw); buildList { for (i in 0 until a.length()) { val o = a.getJSONObject(i); add(ChargeSession(o.getLong("start"), o.getLong("end"), o.getInt("sl"), o.getInt("el"), o.getDouble("mah"), o.getDouble("cap"), o.optDouble("endV", 0.0), o.optDouble("wear", 0.0), o.optDouble("eff", 0.0))) } } }.getOrElse { emptyList() }
    }
}
