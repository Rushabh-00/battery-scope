package com.batteryscope.app.settings

import android.content.Context

/** Persistent user preferences for BatteryScope. UI is intentionally added later. */
class AppSettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var currentUnit: CurrentUnit
        get() = CurrentUnit.fromValue(preferences.getString(KEY_CURRENT_UNIT, CurrentUnit.AMPERE.value))
        set(value) = preferences.edit().putString(KEY_CURRENT_UNIT, value.value).apply()

    var chargeUnit: ChargeUnit
        get() = ChargeUnit.fromValue(preferences.getString(KEY_CHARGE_UNIT, ChargeUnit.MILLIAMP_HOUR.value))
        set(value) = preferences.edit().putString(KEY_CHARGE_UNIT, value.value).apply()

    var temperatureUnit: TemperatureUnit
        get() = TemperatureUnit.fromValue(preferences.getString(KEY_TEMPERATURE_UNIT, TemperatureUnit.CELSIUS.value))
        set(value) = preferences.edit().putString(KEY_TEMPERATURE_UNIT, value.value).apply()

    var theme: ThemeMode
        get() = ThemeMode.fromValue(preferences.getString(KEY_THEME, ThemeMode.AUTO.value))
        set(value) = preferences.edit().putString(KEY_THEME, value.value).apply()

    enum class CurrentUnit(val value: String) {
        AMPERE("A"),
        MILLIAMPERE("mA");

        companion object {
            fun fromValue(value: String?): CurrentUnit = entries.firstOrNull { it.value == value } ?: AMPERE
        }
    }

    enum class ChargeUnit(val value: String) {
        AMPERE_HOUR("Ah"),
        MILLIAMP_HOUR("mAh");

        companion object {
            fun fromValue(value: String?): ChargeUnit = entries.firstOrNull { it.value == value } ?: MILLIAMP_HOUR
        }
    }

    enum class TemperatureUnit(val value: String) {
        CELSIUS("°C"),
        FAHRENHEIT("°F");

        companion object {
            fun fromValue(value: String?): TemperatureUnit = entries.firstOrNull { it.value == value } ?: CELSIUS
        }
    }

    enum class ThemeMode(val value: String) {
        AUTO("Auto"),
        LIGHT("Light"),
        DARK("Dark");

        companion object {
            fun fromValue(value: String?): ThemeMode = entries.firstOrNull { it.value == value } ?: AUTO
        }
    }

    private companion object {
        const val FILE_NAME = "battery_scope_settings"
        const val KEY_CURRENT_UNIT = "current_unit"
        const val KEY_CHARGE_UNIT = "charge_unit"
        const val KEY_TEMPERATURE_UNIT = "temperature_unit"
        const val KEY_THEME = "theme"
    }
}
