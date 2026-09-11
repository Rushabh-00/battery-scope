package com.batteryscope.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.batteryscope.app.battery.BatteryRuntime
import com.batteryscope.app.battery.BatterySessionAnalyzer
import com.batteryscope.app.battery.BatterySnapshot
import com.batteryscope.app.battery.CapacityPreferences
import com.batteryscope.app.monitor.BatteryMonitoringController
import com.batteryscope.app.settings.AppSettings
import com.batteryscope.app.settings.UiPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val ui = remember(context) { UiPreferences(context) }
            val settings = remember(context) { AppSettings(context) }
            var theme by remember { mutableStateOf(ui.theme) }
            var colorMode by remember { mutableStateOf(ui.colorMode) }
            var accent by remember { mutableStateOf(ui.accentColorArgb) }
            var colorStyle by remember { mutableStateOf(ui.colorStyle) }
            BatteryScopeTheme(theme, colorMode, accent, colorStyle) {
                BatteryScopeApp(
                    theme, colorMode, accent, colorStyle, settings,
                    onTheme = { theme = it; ui.theme = it },
                    onColorMode = { colorMode = it; ui.colorMode = it },
                    onAccent = { accent = it; ui.accentColorArgb = it },
                    onColorStyle = { colorStyle = it; ui.colorStyle = it },
                )
            }
        }
    }
}

private enum class AppScreen { LIVE, SETTINGS }

@Composable
private fun BatteryScopeApp(
    theme: UiPreferences.Theme,
    colorMode: UiPreferences.ColorMode,
    accent: Long,
    colorStyle: UiPreferences.ColorStyle,
    settings: AppSettings,
    onTheme: (UiPreferences.Theme) -> Unit,
    onColorMode: (UiPreferences.ColorMode) -> Unit,
    onAccent: (Long) -> Unit,
    onColorStyle: (UiPreferences.ColorStyle) -> Unit,
) {
    var screen by remember { mutableStateOf(AppScreen.LIVE) }
    when (screen) {
        AppScreen.LIVE -> LiveScreen(settings) { screen = AppScreen.SETTINGS }
        AppScreen.SETTINGS -> SettingsScreen(theme, colorMode, accent, colorStyle, settings, onTheme, onColorMode, onAccent, onColorStyle) { screen = AppScreen.LIVE }
    }
}

@Composable
private fun LiveScreen(settings: AppSettings, openSettings: () -> Unit) {
    val context = LocalContext.current
    var battery by remember { mutableStateOf<BatterySnapshot?>(BatteryRuntime.latest()) }
    var lastUpdatedAt by remember { mutableStateOf(if (battery != null) System.currentTimeMillis() else 0L) }
    var readError by remember { mutableStateOf(false) }
    val interval = settings.updateIntervalMs
    val backgroundMonitoring = settings.backgroundMonitoringEnabled

    LaunchedEffect(backgroundMonitoring, interval) {
        if (BatteryRuntime.latest() == null) {
            runCatching { withContext(Dispatchers.IO) { BatteryRuntime.read(context) } }
                .onSuccess {
                    battery = it
                    lastUpdatedAt = System.currentTimeMillis()
                    readError = false
                }
                .onFailure { readError = true }
        }

        while (isActive) {
            if (backgroundMonitoring) {
                BatteryRuntime.latest()?.let {
                    battery = it
                    lastUpdatedAt = System.currentTimeMillis()
                    readError = false
                }
                delay(500)
            } else {
                runCatching { withContext(Dispatchers.IO) { BatteryRuntime.read(context) } }
                    .onSuccess {
                        battery = it
                        lastUpdatedAt = System.currentTimeMillis()
                        readError = false
                    }
                    .onFailure { readError = true }
                delay(interval)
            }
        }
    }

    Scaffold { padding ->
        Surface(Modifier.fillMaxSize().padding(padding), color = MaterialTheme.colorScheme.background) {
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(18.dp),
            ) {
                item { Header(battery?.charging == true, backgroundMonitoring, openSettings) }
                item { MonitoringBanner(settings, battery) }
                if (readError && battery == null) {
                    item { EmptyCard("Battery data unavailable", "BatteryScope will retry automatically.") }
                }
                val value = battery
                if (value == null) {
                    if (!readError) item { EmptyCard("Reading battery telemetry…", "The first sample will appear here.") }
                } else {
                    item { Hero(value, settings) }
                    item { LiveMeta(lastUpdatedAt, interval, readError, backgroundMonitoring) }
                    value.sessionAnalysis?.let { analysis ->
                        item { CapacityHealthCard(value, analysis) }
                        item { HistoryCard(analysis) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(charging: Boolean, backgroundMonitoring: Boolean, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("BatteryScope", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(if (charging) "Live telemetry • charging" else "Live telemetry • on battery", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(if (backgroundMonitoring) "Monitoring on" else "Live only", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = onSettings) { Text("Settings") }
        }
    }
}

@Composable
private fun MonitoringBanner(settings: AppSettings, battery: BatterySnapshot?) = Card(
    shape = RoundedCornerShape(22.dp),
    colors = CardDefaults.cardColors(if (settings.backgroundMonitoringEnabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant),
) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(if (settings.backgroundMonitoringEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline))
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(if (settings.backgroundMonitoringEnabled) "Background monitoring is active" else "Background monitoring is off", fontWeight = FontWeight.SemiBold)
            Text(
                if (settings.backgroundMonitoringEnabled) "${battery?.levelPercent ?: "—"}% • ${formatInterval(settings.updateIntervalMs)} sampling • persistent status notification"
                else "Enable it from Settings for optional monitoring after you leave the app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyCard(title: String, body: String) = Card(shape = RoundedCornerShape(24.dp)) {
    Column(Modifier.fillMaxWidth().padding(22.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Hero(battery: BatterySnapshot, settings: AppSettings) = Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.primaryContainer)) {
    Column(Modifier.fillMaxWidth().padding(22.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("Battery", style = MaterialTheme.typography.labelLarge)
                Text("${battery.levelPercent}%", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Stored energy", style = MaterialTheme.typography.labelLarge)
                Text(battery.energyWh?.let { "${f2(it)} Wh" } ?: "—", style = MaterialTheme.typography.titleLarge)
                Text(battery.remainingMah?.let { "${f2(it / 1000.0)} Ah" } ?: "—")
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(if (battery.charging) "Charging" else "Discharging", fontWeight = FontWeight.Bold)
                Text(battery.powerW?.let { "${f1(it)} W" } ?: "—", style = MaterialTheme.typography.titleLarge)
            }
        }
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator({ battery.levelPercent / 100f }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Mini("Current", battery.currentA?.let { currentText(it, settings.currentUnit) } ?: "—")
            Mini("Voltage", battery.voltageV?.let { "${f1(it)} V" } ?: "—")
            Mini("Temp", battery.temperatureC?.let { tempText(it, settings.temperatureUnit) } ?: "—")
        }
    }
}

@Composable
private fun Mini(label: String, value: String) = Column {
    Text(label, style = MaterialTheme.typography.labelMedium)
    Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun LiveMeta(lastUpdatedAt: Long, interval: Long, readError: Boolean, backgroundMonitoring: Boolean) {
    val age = if (lastUpdatedAt > 0) ((System.currentTimeMillis() - lastUpdatedAt) / 1000L).coerceAtLeast(0L) else 0L
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(
            when {
                readError -> "Last good telemetry retained"
                backgroundMonitoring -> "Reading shared background telemetry"
                age == 0L -> "Updated just now"
                age == 1L -> "Updated 1 second ago"
                else -> "Updated ${age}s ago"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(if (backgroundMonitoring) "Background • ${formatInterval(interval)} interval" else "Every ${formatInterval(interval)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CapacityHealthCard(battery: BatterySnapshot, analysis: BatterySessionAnalyzer.Analysis) = Card(shape = RoundedCornerShape(24.dp)) {
    Column(Modifier.fillMaxWidth().padding(18.dp)) {
        Text("Capacity & health", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Stat("Design capacity", battery.batteryCapacityMah?.let { "${f0(it)} mAh" } ?: "Not detected / not set")
        Stat("Learned capacity", analysis.learnedCapacityMah?.let { "${f0(it)} mAh" } ?: "Awaiting completed session")
        Stat("Latest full-charge", analysis.latestSessionCapacityMah?.let { "${f0(it)} mAh" } ?: "—")
        Stat("Session quality", analysis.latestSessionQualityPercent?.let { "$it%" } ?: "—")
        Stat("Battery health", analysis.healthPercent?.let { "${f0(it)}%" } ?: "Awaiting completed session")
        Stat("Health confidence", if (analysis.healthPercent != null) "${analysis.healthConfidencePercent}%" else "—")
        Stat("Wear", analysis.wearMah?.let { "${f0(it)} mAh" } ?: "—")
        Stat("Remaining charge", battery.remainingMah?.let { "${f0(it)} mAh" } ?: "Unavailable")
        analysis.capacityTrend?.let { trend ->
            val label = when (trend.direction) {
                com.batteryscope.app.battery.CapacityTrendCalculator.Direction.IMPROVING -> "Improving"
                com.batteryscope.app.battery.CapacityTrendCalculator.Direction.STABLE -> "Stable"
                com.batteryscope.app.battery.CapacityTrendCalculator.Direction.DECLINING -> "Declining"
            }
            Stat("Capacity trend", "$label • ${if (trend.changeMah >= 0) "+" else ""}${f0(trend.changeMah)} mAh")
        }
        Stat("Charge", "${f0(analysis.chargeMah)} mAh • ${duration(analysis.chargeTimeMs)}")
        Stat("Discharge", "${f0(analysis.dischargeMah)} mAh • ${duration(analysis.dischargeTimeMs)}")
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text(
            "A valid session starts at 15% or lower and completes at full. Health uses the quality-weighted median of recent independent sessions.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Stat(label: String, value: String) = Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(label, Modifier.weight(1f))
    Text(value, Modifier.weight(1f), textAlign = TextAlign.End, fontWeight = FontWeight.Medium)
}

@Composable
private fun HistoryCard(analysis: BatterySessionAnalyzer.Analysis) = Card(shape = RoundedCornerShape(24.dp)) {
    Column(Modifier.fillMaxWidth().padding(18.dp)) {
        Text("Full-charge session history", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (analysis.fullChargeSessions.isEmpty()) {
            Text("No completed sessions yet. Start at 15% or lower, then charge to full.")
        } else {
            analysis.fullChargeSessions.takeLast(5).reversed().forEachIndexed { index, session ->
                if (index > 0) Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${f0(session.estimatedCapacityMah)} mAh", fontWeight = FontWeight.SemiBold)
                    Text(date(session.completedAtMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("${f0(session.chargedMah)} mAh • quality ${session.qualityPercent}% • ${duration(session.durationMs)} • started ${session.startLevelPercent}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    theme: UiPreferences.Theme,
    colorMode: UiPreferences.ColorMode,
    accent: Long,
    colorStyle: UiPreferences.ColorStyle,
    settings: AppSettings,
    onTheme: (UiPreferences.Theme) -> Unit,
    onColorMode: (UiPreferences.ColorMode) -> Unit,
    onAccent: (Long) -> Unit,
    onColorStyle: (UiPreferences.ColorStyle) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val activity = context as? Activity
    val capacityPreferences = remember(context) { CapacityPreferences(context) }
    var currentUnit by remember { mutableStateOf(settings.currentUnit) }
    var tempUnit by remember { mutableStateOf(settings.temperatureUnit) }
    var invert by remember { mutableStateOf(settings.invertChargingPolarity) }
    var interval by remember { mutableStateOf(settings.updateIntervalMs) }
    var backgroundEnabled by remember { mutableStateOf(settings.backgroundMonitoringEnabled) }
    var startOnBoot by remember { mutableStateOf(settings.startOnBoot) }
    var capacity by remember { mutableStateOf(capacityPreferences.designCapacityMah?.let(::f0) ?: "") }
    var message by remember { mutableStateOf("") }
    var showColorDialog by remember { mutableStateOf(false) }
    var optimizationIgnored by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    Scaffold { padding ->
        Surface(Modifier.fillMaxSize().padding(padding), color = MaterialTheme.colorScheme.background) {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(18.dp)) {
                item {
                    Column {
                        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("Make BatteryScope look better and run exactly as much as you want.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item {
                    Section("Background monitoring") {
                        ToggleRow(
                            "Background monitoring",
                            "Keeps telemetry alive outside the app with an ongoing low-priority notification.",
                            backgroundEnabled,
                        ) {
                            backgroundEnabled = it
                            settings.backgroundMonitoringEnabled = it
                            if (it) {
                                requestNotificationPermission(activity)
                                BatteryMonitoringController.start(context)
                            } else {
                                BatteryMonitoringController.stop(context)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Uses the same update interval as Foreground telemetry: ${formatInterval(interval)}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                            context.startActivity(intent)
                        }, Modifier.fillMaxWidth()) { Text("Open app battery settings") }
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            requestBatteryOptimization(context)
                            optimizationIgnored = isIgnoringBatteryOptimizations(context)
                        }, Modifier.fillMaxWidth(), enabled = !optimizationIgnored) {
                            Text(if (optimizationIgnored) "Battery optimization already relaxed" else "Allow unrestricted battery use")
                        }
                        Text(
                            "Some manufacturers also provide their own Auto-start/background-activity switch. BatteryScope cannot change those OEM-only controls automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item {
                    Section("Start on boot") {
                        ToggleRow(
                            "Start on boot",
                            "Starts BatteryScope background monitoring after boot or an app update.",
                            startOnBoot,
                        ) {
                            startOnBoot = it
                            settings.startOnBoot = it
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (backgroundEnabled) "Background monitoring is currently enabled, so this switch can start it automatically after boot."
                            else "Background monitoring is off. Enable it above for this switch to have an effect at boot.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item { NotificationSettingsSection(settings) }
                item {
                    Section("Appearance") {
                        SegmentedChoice("Theme Mode", UiPreferences.Theme.entries.map { it.value }, theme.value) { onTheme(UiPreferences.Theme.fromValue(it)) }
                        Spacer(Modifier.height(16.dp))
                        SegmentedChoice("Theme Colour", UiPreferences.ColorMode.entries.map { it.value }, colorMode.value) { onColorMode(UiPreferences.ColorMode.fromValue(it)) }
                        if (colorMode == UiPreferences.ColorMode.CUSTOM) {
                            Spacer(Modifier.height(14.dp))
                            ThemeColorPalette(accent, onAccent)
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(onClick = { showColorDialog = true }, Modifier.fillMaxWidth()) { Text("Custom Colour") }
                            Spacer(Modifier.height(12.dp))
                            SegmentedChoice("Colour Style", UiPreferences.ColorStyle.entries.map { it.value }, colorStyle.value) { selected -> onColorStyle(UiPreferences.ColorStyle.entries.firstOrNull { it.value == selected } ?: UiPreferences.ColorStyle.TONAL) }
                        }
                    }
                }
                item {
                    Section("Units") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) { ChoiceRow("Current", AppSettings.CurrentUnit.entries.map { it.value }, currentUnit.value) { currentUnit = AppSettings.CurrentUnit.fromValue(it); settings.currentUnit = currentUnit } }
                            Column(Modifier.weight(1f)) { ChoiceRow("Temperature", AppSettings.TemperatureUnit.entries.map { it.value }, tempUnit.value) { tempUnit = AppSettings.TemperatureUnit.fromValue(it); settings.temperatureUnit = tempUnit } }
                        }
                    }
                }
                item {
                    Section("Battery capacity") {
                        Text("Design capacity", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = capacity, onValueChange = { capacity = it; message = "" }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Battery capacity (mAh)") }, placeholder = { Text("Example: 4500") })
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { val value = capacity.toDoubleOrNull(); if (value != null && value in 100.0..30_000.0) { capacityPreferences.designCapacityMah = value; message = "Saved" } else message = "Enter 100–30,000 mAh" }) { Text("Save capacity") }
                            OutlinedButton(onClick = { capacityPreferences.designCapacityMah = null; capacity = ""; message = "Cleared" }) { Text("Clear") }
                        }
                        if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text("Used for health and wear comparison. Learned capacity appears only after a valid full-charge session.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item {
                    Section("Telemetry") {
                        Text("Foreground update interval", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        Text("Controls live telemetry refresh and background monitoring.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text(formatInterval(interval), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Slider(value = interval.toFloat(), onValueChange = { value -> interval = ((value / 250f).roundToInt() * 250L).coerceIn(1_250L, 10_000L) }, onValueChangeFinished = { settings.updateIntervalMs = interval }, valueRange = 1_250f..10_000f, steps = 34)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("1.25 s"); Text("10 s") }
                        Spacer(Modifier.height(10.dp))
                        ToggleRow("Invert charging polarity", "Changes current sign live. Power follows the sign.", invert) { invert = it; settings.invertChargingPolarity = it }
                    }
                }
            }
        }
    }
    if (showColorDialog) {
        var hex by remember { mutableStateOf(String.format(Locale.US, "#%06X", accent and 0xFFFFFFL)) }
        AlertDialog(
            onDismissRequest = { showColorDialog = false },
            title = { Text("Custom Colour") },
            text = { OutlinedTextField(value = hex, onValueChange = { hex = it }, singleLine = true, label = { Text("Hex colour") }, placeholder = { Text("#18B9C7") }) },
            confirmButton = { TextButton(onClick = { parseHex(hex)?.let(onAccent); showColorDialog = false }) { Text("Apply") } },
            dismissButton = { TextButton(onClick = { showColorDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ToggleRow(title: String, body: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) = Card(shape = RoundedCornerShape(28.dp)) {
    Column(Modifier.fillMaxWidth().padding(22.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(14.dp))
        content()
    }
}

@Composable
private fun ChoiceRow(title: String, options: List<String>, selected: String, onSelected: (String) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        options.forEach { option ->
            val active = option == selected
            Box(Modifier.weight(1f).clip(RoundedCornerShape(13.dp)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(13.dp)).background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent).selectable(selected = active, onClick = { onSelected(option) }, role = Role.RadioButton).padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                Text(option, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun SegmentedChoice(title: String, options: List<String>, selected: String, onSelected: (String) -> Unit) = ChoiceRow(title, options, selected, onSelected)

@Composable
private fun ThemeColorPalette(selected: Long, onSelected: (Long) -> Unit) {
    val colors = longArrayOf(0xFFE97D7DL, 0xFFE78A45L, 0xFFD39A1FL, 0xFFA7A832L, 0xFF79B45EL, 0xFF3BAF8BL, 0xFF12AAB5L, 0xFF3AA7D0L, 0xFF6F9DDFL, 0xFF918BE0L, 0xFFB47BCFL, 0xFFD276A4L)
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { colors.take(6).forEach { ColorDot(it, selected, onSelected) } }
        Spacer(Modifier.height(9.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { colors.drop(6).forEach { ColorDot(it, selected, onSelected) } }
    }
}

@Composable
private fun ColorDot(value: Long, selected: Long, onSelected: (Long) -> Unit) {
    val color = Color(value.toInt())
    Box(Modifier.size(40.dp).clip(CircleShape).background(color).border(if (value == selected) 3.dp else 0.dp, if (value == selected) Color.White else Color.Transparent, CircleShape).selectable(selected = value == selected, onClick = { onSelected(value) }, role = Role.RadioButton).padding(6.dp)) {}
}

private fun requestNotificationPermission(activity: Activity?) {
    if (Build.VERSION.SDK_INT >= 33 && activity != null && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
        activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4001)
    }
}

private fun requestBatteryOptimization(context: android.content.Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
    }.recoverCatching {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

private fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean = runCatching {
    val manager = context.getSystemService(PowerManager::class.java)
    Build.VERSION.SDK_INT < 23 || manager?.isIgnoringBatteryOptimizations(context.packageName) == true
}.getOrDefault(false)

private fun currentText(value: Double, unit: AppSettings.CurrentUnit) = if (unit == AppSettings.CurrentUnit.AMPERE) "${f1(value)} A" else "${f0(value * 1000)} mA"
private fun tempText(value: Double, unit: AppSettings.TemperatureUnit) = if (unit == AppSettings.TemperatureUnit.CELSIUS) "${f1(value)} °C" else "${f1(value * 9 / 5 + 32)} °F"
private fun duration(ms: Long): String { if (ms <= 0) return "0 min"; val minutes = ms / 60_000; val hours = minutes / 60; return if (hours > 0) "${hours}h ${minutes % 60}m" else "${minutes}m" }
private fun formatInterval(ms: Long) = if (ms == 1_250L) "1.25 s" else if (ms % 1000L == 0L) "${ms / 1000}s" else String.format(Locale.US, "%.2f s", ms / 1000.0)
private fun date(ms: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))
private fun f0(value: Double) = String.format(Locale.US, "%.0f", value)
private fun f1(value: Double) = String.format(Locale.US, "%.1f", value)
private fun f2(value: Double) = String.format(Locale.US, "%.2f", value)
private fun parseHex(value: String): Long? = runCatching { android.graphics.Color.parseColor(value.trim().let { if (it.startsWith("#")) it else "#$it" }).toLong() and 0xFFFFFFFFL }.getOrNull()
