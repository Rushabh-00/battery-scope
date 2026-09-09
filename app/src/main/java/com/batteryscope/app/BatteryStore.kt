package com.batteryscope.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class BatteryStore(context: Context) {
    private val prefs = context.getSharedPreferences("battery_scope", Context.MODE_PRIVATE)

    @Synchronized
    fun addSample(sample: HistorySample) {
        val list = samples().toMutableList()
        list.add(sample)
        val trimmed = list.takeLast(500)
        val array = JSONArray()
        trimmed.forEach {
            array.put(JSONObject().apply {
                put("t", it.timestamp); put("l", it.level); put("temp", it.temperatureC); put("v", it.voltageV)
                put("i", it.currentMa ?: JSONObject.NULL)
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
                    add(HistorySample(o.getLong("t"), o.getInt("l"), o.getDouble("temp"), o.getDouble("v"),
                        if (o.isNull("i")) null else o.getDouble("i")))
                }
            }
        }.getOrElse { emptyList() }
    }

    @Synchronized
    fun addSession(session: ChargeSession) {
        if (sessions().any { kotlin.math.abs(it.startTime - session.startTime) < 5_000L }) return
        val list = sessions().toMutableList().apply { add(session) }
        val array = JSONArray()
        list.takeLast(20).forEach {
            array.put(JSONObject().apply {
                put("start", it.startTime); put("end", it.endTime); put("sl", it.startLevel); put("el", it.endLevel)
                put("mah", it.chargedMah); put("cap", it.estimatedCapacityMah)
                put("endV", it.endVoltageV); put("wear", it.wearCycles); put("eff", it.efficiencyPercent)
            })
        }
        prefs.edit().putString("sessions", array.toString()).apply()
    }

    fun sessions(): List<ChargeSession> {
        val raw = prefs.getString("sessions", null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            buildList {
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val capacity = o.getDouble("cap")
                    val endLevel = o.getInt("el")
                    val startLevel = o.getInt("sl")
                    val legacyEndVoltage = o.optDouble("endV", Double.NaN)
                    val wear = o.optDouble(
                        "wear",
                        if (legacyEndVoltage.isNaN()) 0.0 else estimateWearCycles(legacyEndVoltage, endLevel)
                    )
                    val efficiency = o.optDouble(
                        "eff",
                        if (wear > 0.0) (endLevel - startLevel) / (wear * 100.0) * 100.0 else 0.0
                    )
                    add(
                        ChargeSession(
                            o.getLong("start"), o.getLong("end"), startLevel, endLevel,
                            o.getDouble("mah"), capacity, legacyEndVoltage.takeUnless { it.isNaN() } ?: 0.0,
                            wear, efficiency
                        )
                    )
                }
            }
        }.getOrElse { emptyList() }
    }
}
