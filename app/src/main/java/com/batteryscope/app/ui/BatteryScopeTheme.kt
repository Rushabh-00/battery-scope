package com.batteryscope.app.ui

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batteryscope.app.settings.UiPreferences

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun BatteryScopeTheme(
    mode: UiPreferences.Theme,
    colorMode: UiPreferences.ColorMode,
    accentColorArgb: Long,
    colorStyle: UiPreferences.ColorStyle,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        UiPreferences.Theme.SYSTEM -> systemDark
        UiPreferences.Theme.LIGHT -> false
        UiPreferences.Theme.DARK, UiPreferences.Theme.OLED -> true
    }
    val colors = if (colorMode == UiPreferences.ColorMode.AUTO) {
        when {
            mode == UiPreferences.Theme.OLED -> oledColorScheme()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
            dark -> darkColorScheme()
            else -> lightColorScheme()
        }
    } else {
        customColorScheme(argbColorToComposeColor(accentColorArgb), colorStyle, dark, mode == UiPreferences.Theme.OLED)
    }

    val themeKey = remember(mode, colorMode, accentColorArgb, colorStyle) {
        ThemeKey(mode, colorMode, accentColorArgb, colorStyle)
    }
    val typography = batteryScopeTypography()

    MaterialTheme(
        colorScheme = colors,
        typography = typography,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            AnimatedContent(
                targetState = themeKey,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    (fadeIn(tween(220)) + scaleIn(initialScale = 0.985f, animationSpec = tween(260))) togetherWith
                        (fadeOut(tween(120)) + scaleOut(targetScale = 1.015f, animationSpec = tween(180)))
                },
                contentKey = { it },
                label = "themeTransition",
            ) {
                Box(Modifier.fillMaxSize()) {
                    content()
                }
            }
        }
    }
}

private data class ThemeKey(
    val mode: UiPreferences.Theme,
    val colorMode: UiPreferences.ColorMode,
    val accentColorArgb: Long,
    val colorStyle: UiPreferences.ColorStyle,
)

private fun batteryScopeTypography(): Typography {
    val base = androidx.compose.material3.Typography()
    val family = FontFamily.SansSerif
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = family, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
        displayMedium = base.displayMedium.copy(fontFamily = family, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        displaySmall = base.displaySmall.copy(fontFamily = family, fontWeight = FontWeight.Bold),
        headlineLarge = base.headlineLarge.copy(fontFamily = family, fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontFamily = family, fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontFamily = family, fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontFamily = family, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontFamily = family, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontFamily = family, fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(fontFamily = family, lineHeight = 24.sp),
        bodyMedium = base.bodyMedium.copy(fontFamily = family, lineHeight = 21.sp),
        bodySmall = base.bodySmall.copy(fontFamily = family, lineHeight = 18.sp),
        labelLarge = base.labelLarge.copy(fontFamily = family, fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.copy(fontFamily = family, fontWeight = FontWeight.Medium),
        labelSmall = base.labelSmall.copy(fontFamily = family, fontWeight = FontWeight.Medium),
    )
}

private fun argbColorToComposeColor(value: Long): Color {
    val argb = value.toInt()
    return Color(
        red = android.graphics.Color.red(argb) / 255f,
        green = android.graphics.Color.green(argb) / 255f,
        blue = android.graphics.Color.blue(argb) / 255f,
        alpha = android.graphics.Color.alpha(argb) / 255f,
    )
}

private fun customColorScheme(accent: Color, style: UiPreferences.ColorStyle, dark: Boolean, oled: Boolean) = if (dark) {
    val primary = accentForStyle(accent, style, true)
    val secondary = accentForStyle(accent, style, true, secondaryHue(style))
    val tertiary = accentForStyle(accent, style, true, tertiaryHue(style))
    darkColorScheme(
        primary = primary, onPrimary = Color.Black,
        primaryContainer = mix(primary, if (oled) Color.Black else Color(0xFF161616), 0.68f), onPrimaryContainer = Color.White,
        secondary = secondary, onSecondary = Color.Black,
        secondaryContainer = mix(secondary, if (oled) Color.Black else Color(0xFF161616), 0.72f), onSecondaryContainer = Color.White,
        tertiary = tertiary, onTertiary = Color.Black,
        tertiaryContainer = mix(tertiary, if (oled) Color.Black else Color(0xFF161616), 0.72f), onTertiaryContainer = Color.White,
        background = if (oled) Color.Black else Color(0xFF101010), onBackground = Color.White,
        surface = if (oled) Color.Black else Color(0xFF101010), onSurface = Color.White,
        surfaceContainer = if (oled) Color.Black else Color(0xFF191919),
        surfaceContainerLow = if (oled) Color.Black else Color(0xFF141414),
        surfaceContainerHigh = if (oled) Color.Black else Color(0xFF202020),
        surfaceContainerHighest = if (oled) Color.Black else Color(0xFF262626),
        outline = primary.copy(alpha = 0.55f), outlineVariant = primary.copy(alpha = 0.25f),
    )
} else {
    val primary = accentForStyle(accent, style, false)
    val secondary = accentForStyle(accent, style, false, secondaryHue(style))
    val tertiary = accentForStyle(accent, style, false, tertiaryHue(style))
    lightColorScheme(
        primary = primary, onPrimary = Color.White,
        primaryContainer = mix(primary, Color.White, 0.78f), onPrimaryContainer = mix(primary, Color.Black, 0.22f),
        secondary = secondary, onSecondary = Color.White,
        secondaryContainer = mix(secondary, Color.White, 0.82f), onSecondaryContainer = mix(secondary, Color.Black, 0.25f),
        tertiary = tertiary, onTertiary = Color.White,
        tertiaryContainer = mix(tertiary, Color.White, 0.82f), onTertiaryContainer = mix(tertiary, Color.Black, 0.25f),
        background = Color(0xFFFAFAFA), surface = Color(0xFFFAFAFA), onSurface = Color(0xFF1A1A1A),
        surfaceContainer = Color.White, surfaceContainerLow = Color(0xFFF4F4F4), surfaceContainerHigh = Color.White,
        outline = primary.copy(alpha = 0.55f), outlineVariant = primary.copy(alpha = 0.28f),
    )
}

private fun oledColorScheme() = darkColorScheme(
    background = Color.Black, onBackground = Color.White,
    surface = Color.Black, onSurface = Color.White,
    surfaceContainer = Color.Black, surfaceContainerLow = Color.Black,
    surfaceContainerHigh = Color.Black, surfaceContainerHighest = Color.Black,
)

private fun accentForStyle(accent: Color, style: UiPreferences.ColorStyle, dark: Boolean, hueOffset: Float = 0f): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(android.graphics.Color.rgb((accent.red * 255).toInt(), (accent.green * 255).toInt(), (accent.blue * 255).toInt()), hsv)
    val saturation = when (style) {
        UiPreferences.ColorStyle.TONAL -> hsv[1] * 0.68f
        UiPreferences.ColorStyle.NEUTRAL -> 0.08f
        UiPreferences.ColorStyle.VIBRANT -> hsv[1].coerceAtLeast(0.82f)
        UiPreferences.ColorStyle.EXPRESSIVE -> hsv[1].coerceAtLeast(0.72f)
        UiPreferences.ColorStyle.RAIN -> hsv[1].coerceAtLeast(0.65f)
    }.coerceIn(0f, 1f)
    val hue = (hsv[0] + hueOffset + when (style) {
        UiPreferences.ColorStyle.EXPRESSIVE -> 18f
        UiPreferences.ColorStyle.RAIN -> -18f
        else -> 0f
    } + 360f) % 360f
    return Color.hsv(hue, saturation, if (dark) 0.78f else 0.42f)
}

private fun secondaryHue(style: UiPreferences.ColorStyle) = when (style) {
    UiPreferences.ColorStyle.TONAL -> 24f
    UiPreferences.ColorStyle.NEUTRAL -> 0f
    UiPreferences.ColorStyle.VIBRANT -> 52f
    UiPreferences.ColorStyle.EXPRESSIVE -> 140f
    UiPreferences.ColorStyle.RAIN -> 90f
}

private fun tertiaryHue(style: UiPreferences.ColorStyle) = when (style) {
    UiPreferences.ColorStyle.TONAL -> 72f
    UiPreferences.ColorStyle.NEUTRAL -> 0f
    UiPreferences.ColorStyle.VIBRANT -> 110f
    UiPreferences.ColorStyle.EXPRESSIVE -> 250f
    UiPreferences.ColorStyle.RAIN -> 180f
}

private fun mix(first: Color, second: Color, secondWeight: Float): Color = Color(
    red = first.red * (1f - secondWeight) + second.red * secondWeight,
    green = first.green * (1f - secondWeight) + second.green * secondWeight,
    blue = first.blue * (1f - secondWeight) + second.blue * secondWeight,
    alpha = 1f,
)

private fun tween(durationMillis: Int) = androidx.compose.animation.core.tween<Float>(durationMillis)
