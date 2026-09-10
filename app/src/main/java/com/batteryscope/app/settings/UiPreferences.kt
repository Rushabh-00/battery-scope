package com.batteryscope.app.settings

import android.content.Context

class UiPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var theme: Theme
        get() = Theme.fromValue(preferences.getString(KEY_THEME, Theme.SYSTEM.value))
        set(value) = preferences.edit().putString(KEY_THEME, value.value).apply()

    var colorMode: ColorMode
        get() = ColorMode.fromValue(preferences.getString(KEY_COLOR_MODE, ColorMode.AUTO.value))
        set(value) = preferences.edit().putString(KEY_COLOR_MODE, value.value).apply()

    var accentColorArgb: Long
        get() = preferences.getLong(KEY_ACCENT_COLOR, DEFAULT_ACCENT_COLOR)
        set(value) = preferences.edit().putLong(KEY_ACCENT_COLOR, value).apply()

    var colorStyle: ColorStyle
        get() = ColorStyle.fromValue(preferences.getString(KEY_COLOR_STYLE, ColorStyle.TONAL.value))
        set(value) = preferences.edit().putString(KEY_COLOR_STYLE, value.value).apply()

    enum class Theme(val value: String) {
        SYSTEM("System"),
        LIGHT("Light"),
        DARK("Dark"),
        OLED("OLED");

        companion object {
            fun fromValue(value: String?): Theme = when (value) {
                "Auto" -> SYSTEM
                else -> entries.firstOrNull { it.value == value } ?: SYSTEM
            }
        }
    }

    enum class ColorMode(val value: String) {
        AUTO("Auto"),
        CUSTOM("Custom");

        companion object {
            fun fromValue(value: String?): ColorMode = entries.firstOrNull { it.value == value } ?: AUTO
        }
    }

    enum class ColorStyle(val value: String) {
        TONAL("Tonal"),
        NEUTRAL("Neutral"),
        VIBRANT("Vibrant"),
        EXPRESSIVE("Expressive"),
        RAIN("Rain");

        companion object {
            fun fromValue(value: String?): ColorStyle = entries.firstOrNull { it.value == value } ?: TONAL
        }
    }

    private companion object {
        const val FILE_NAME = "batteryscope_ui_preferences"
        const val KEY_THEME = "theme"
        const val KEY_COLOR_MODE = "color_mode"
        const val KEY_ACCENT_COLOR = "accent_color"
        const val KEY_COLOR_STYLE = "color_style"
        const val DEFAULT_ACCENT_COLOR = 0xFF18B9C7L
    }
}
