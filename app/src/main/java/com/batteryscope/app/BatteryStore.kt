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
    val showCurrent: Boolean = true,
    val showPower: Boolean = true,
    val showVoltage: Boolean = true,
    val showTemperature: Boolean = true,
    val showRemainingCharge: Boolean = true,
    val showEnergy: Boolean = true,
    val showChargeTime: Boolean = true,
    val showScreenState: Boolean = false,
    val invertCharging: Boolean = false,
    val powerScalar: Float = 1f,
    val updateIntervalSeconds: Int = 2,
    val notificationEntries: Set<String> = setOf("W", "A", "V", "%"),
    val lowBatteryAlarm: Boolean = true,
    val fullBatteryAlarm: Boolean = false,
    val temperatureAlarm: Boolean = true
)

class BatteryStore(context: Context) {
    private val prefs = context.getSharedPreferences("battery_scope", Context.MODE_PRIVATE)

    fun settings(): UiSettings = UiSettings(
        currentUnit = prefs.getString("currentUnit", "A") ?: "A",
        chargeUnit = prefs.getString("chargeUnit", "Ah") ?: "Ah",
        energyUnit = "Wh",
        temperatureF = prefs.getBoolean("temperatureF", false),
        theme = prefs.getString("theme", "AUTO") ?: "AUTO",
        showCurrent = prefs.getBoolean("showCurrent", true),
        showPower = prefs.getBoolean("showPower", true),
        showVoltage = prefs.getBoolean("showVoltage", true),
        showTemperature = prefs.getBoolean("showTemperature", true),
        showRemainingCharge = prefs.getBoolean("showRemainingCharge", true),
        showEnergy = prefs.getBoolean("showEnergy", true),
        showChargeTime = prefs.getBoolean("showChargeTime", true),
        showScreenState = prefs.getBoolean("showScreenState", false),
        invertCharging = prefs.getBoolean("invertCharging", false),
        powerScalar = prefs.getFloat("powerScalar", 1f).coerceIn(0.5f, 2f),
        updateIntervalSeconds = prefs.getInt("updateIntervalSeconds", 2).coerceIn(1, 30),
        notificationEntries = prefs.getStringSet("notificationEntries", setOf("W", "A", "V", "%")) ?: setOf("W", "A", "V", "%"),
        lowBatteryAlarm = prefs.getBoolean("lowBatteryAlarm", true),
        fullBatteryAlarm = prefs.getBoolean("fullBatteryAlarm", false),
        temperatureAlarm = prefs.getBoolean("temperatureAlarm", true)
    )

    @Synchronized
    fun saveSettings(value: UiSettings) {
        prefs.edit()
            .putString("currentUnit", value.currentUnit)
            .putString("chargeUnit", value.chargeUnit)
            .putBoolean("temperatureF", value.temperatureF)
            .putString("theme", value.theme)
            .putBoolean("showCurrent", value.showCurrent)
            .putBoolean("showPower", value.showPower)
            .putBoolean("showVoltage", value.showVoltage)
            .putBoolean("showTemperature", value.showTemperature)
            .putBoolean("showRemainingCharge", value.showRemainingCharge)
            .putBoolean("showEnergy", value.showEnergy)
            .putBoolean("showChargeTime", value.showChargeTime)
            .putBoolean("showScreenState", value.showScreenState)
            .putBoolean("invertCharging", value.invertCharging)
            .putFloat("powerScalar", value.powerScalar.coerceIn(0.5f, 2f))
            .putInt("updateIntervalSeconds", value.updateIntervalSeconds.coerceIn(1, 30))
            .putStringSet("notificationEntries", value.notificationEntries)
            .putBoolean("lowBatteryAlarm", value.lowBatteryAlarm)
            .putBoolean("fullBatteryAlarm", value.fullBatteryAlarm)
            .putBoolean("temperatureAlarm", value.temperatureAlarm)
            .apply()
    }

    @Synchronized
    fun addSample(sample: HistorySample) {
        val list = samples().toMutableList().apply { add(sample) }
        val array = JSONArray()
        list.takeLast(1000).forEach { s ->
            array.put(JSONObject().apply {
                put("t", s.timestamp); put("l", s.level); put("temp", s.temperatureC); put("v", s.voltageV)
                put("i", s.currentMa ?: JSONObject.NULL)
            })
        }
        prefs.edit().putString("samples", array.toString()).apply()
    }

    @Synchronized
    fun samples(): List<HistorySample> {
        val raw = prefs.getString("samples", null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            buildList {
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    add(HistorySample(o.getLong("t"), o.getInt("l"), o.getDouble("temp"), o.getDouble("v"), if (o.isNull("i")) null else o.getDouble("i")))
                }
            }
        }.getOrElse { emptyList() }
    }

    @Synchronized
    fun addSession(session: ChargeSession) {
        if (sessions().any { kotlin.math.abs(it.startTime - session.startTime) < 5_000L }) return
        val list = sessions().toMutableList().apply { add(session) }
        val array = JSONArray()
        list.takeLast(50).forEach { s ->
            array.put(JSONObject().apply {
                put("start", s.startTime); put("end", s.endTime); put("sl", s.startLevel); put("el", s.endLevel)
                put("mah", s.chargedMah); put("cap", s.estimatedCapacityMah); put("endV", s.endVoltageV)
                put("wear", s.wearCycles); put("eff", s.efficiencyPercent)
            })
        }
        prefs.edit().putString("sessions", array.toString()).apply()
    }

    @Synchronized
    fun sessions(): List<ChargeSession> {
        val raw = prefs.getString("sessions", null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            buildList {
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val endLevel = o.getInt("el")
                    val startLevel = o.getInt("sl")
                    val endV = o.optDouble("endV", 0.0)
                    val wear = o.optDouble("wear", estimateWearCycles(endV, endLevel))
                    val efficiency = o.optDouble("eff", if (wear > 0.0) (endLevel - startLevel) / (wear * 100.0) * 100.0 else 0.0)
                    add(ChargeSession(o.getLong("start"), o.getLong("end"), startLevel, endLevel, o.getDouble("mah"), o.getDouble("cap"), endV, wear, efficiency))
                }
            }
        }.getOrElse { emptyList() }
    }
}
