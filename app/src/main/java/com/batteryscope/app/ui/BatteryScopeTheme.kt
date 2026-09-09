package com.batteryscope.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.batteryscope.app.settings.UiPreferences

@Composable
fun BatteryScopeTheme(
    mode: UiPreferences.Theme,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        UiPreferences.Theme.AUTO -> systemDark
        UiPreferences.Theme.LIGHT -> false
        UiPreferences.Theme.DARK, UiPreferences.Theme.OLED -> true
    }
    val colors = when (mode) {
        UiPreferences.Theme.OLED -> darkColorScheme(
            surface = Color.Black,
            background = Color.Black,
            surfaceContainer = Color(0xFF090909),
        )
        else -> if (dark) darkColorScheme() else lightColorScheme()
    }

    MaterialTheme(colorScheme = colors, content = content)
}
