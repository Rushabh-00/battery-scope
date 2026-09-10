package com.batteryscope.app.settings

import android.content.Context

/** Persistent user preferences for BatteryScope. */
class AppSettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var currentUnit: CurrentUnit
        get() = CurrentUnit.fromValue(preferences.getString(KEY_CURRENT_UNIT, CurrentUnit.AMPERE.value))
        set(value) = preferences.edit().putString(KEY_CURRENT_UNIT, value.value).apply()

    var temperatureUnit: TemperatureUnit
        get() = TemperatureUnit.fromValue(preferences.getString(KEY_TEMPERATURE_UNIT, TemperatureUnit.CELSIUS.value))
        set(value) = preferences.edit().putString(KEY_TEMPERATURE_UNIT, value.value).apply()

    var theme: ThemeMode
        get() = ThemeMode.fromValue(preferences.getString(KEY_THEME, ThemeMode.AUTO.value))
        set(value) = preferences.edit().putString(KEY_THEME, value.value).apply()

    var invertChargingPolarity: Boolean
        get() = preferences.getBoolean(KEY_INVERT_CHARGING_POLARITY, true)
        set(value) = preferences.edit().putBoolean(KEY_INVERT_CHARGING_POLARITY, value).apply()

    var updateIntervalMs: Long
        get() = preferences.getLong(KEY_UPDATE_INTERVAL_MS, DEFAULT_UPDATE_INTERVAL_MS)
            .coerceIn(MIN_UPDATE_INTERVAL_MS, MAX_UPDATE_INTERVAL_MS)
        set(value) = preferences.edit().putLong(KEY_UPDATE_INTERVAL_MS, value.coerceIn(MIN_UPDATE_INTERVAL_MS, MAX_UPDATE_INTERVAL_MS)).apply()

    enum class CurrentUnit(val value: String) {
        AMPERE("A"),
        MILLIAMPERE("mA");
        companion object { fun fromValue(value: String?): CurrentUnit = entries.firstOrNull { it.value == value } ?: AMPERE }
    }

    enum class TemperatureUnit(val value: String) {
        CELSIUS("°C"),
        FAHRENHEIT("°F");
        companion object { fun fromValue(value: String?): TemperatureUnit = entries.firstOrNull { it.value == value } ?: CELSIUS }
    }

    enum class ThemeMode(val value: String) {
        AUTO("Auto"),
        LIGHT("Light"),
        DARK("Dark");
        companion object { fun fromValue(value: String?): ThemeMode = entries.firstOrNull { it.value == value } ?: AUTO }
    }

    private companion object {
        const val FILE_NAME = "battery_scope_settings"
        const val KEY_CURRENT_UNIT = "current_unit"
        const val KEY_TEMPERATURE_UNIT = "temperature_unit"
        const val KEY_THEME = "theme"
        const val KEY_INVERT_CHARGING_POLARITY = "invert_charging_polarity"
        const val KEY_UPDATE_INTERVAL_MS = "update_interval_ms"
        const val MIN_UPDATE_INTERVAL_MS = 1_250L
        const val DEFAULT_UPDATE_INTERVAL_MS = 1_250L
        const val MAX_UPDATE_INTERVAL_MS = 10_000L
    }
}
