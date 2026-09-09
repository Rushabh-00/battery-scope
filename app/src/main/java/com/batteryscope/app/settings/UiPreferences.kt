package com.batteryscope.app.settings

import android.content.Context

class UiPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var theme: Theme
        get() = Theme.fromValue(preferences.getString(KEY_THEME, Theme.AUTO.value))
        set(value) = preferences.edit().putString(KEY_THEME, value.value).apply()

    enum class Theme(val value: String) {
        AUTO("Auto"),
        LIGHT("Light"),
        DARK("Dark"),
        OLED("OLED");

        companion object {
            fun fromValue(value: String?): Theme = entries.firstOrNull { it.value == value } ?: AUTO
        }
    }

    private companion object {
        const val FILE_NAME = "batteryscope_ui_preferences"
        const val KEY_THEME = "theme"
    }
}
