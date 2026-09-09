package com.batteryscope.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import com.batteryscope.app.settings.AppSettings

@Composable
fun BatteryScopeTheme(
    mode: AppSettings.ThemeMode,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        AppSettings.ThemeMode.AUTO -> isSystemInDarkTheme()
        AppSettings.ThemeMode.LIGHT -> false
        AppSettings.ThemeMode.DARK -> true
        AppSettings.ThemeMode.OLED -> true
    }

    MaterialTheme(
        colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
