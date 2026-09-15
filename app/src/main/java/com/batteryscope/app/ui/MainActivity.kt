package com.batteryscope.app.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.batteryscope.app.BuildConfig
import com.batteryscope.app.battery.BatteryRuntime
import com.batteryscope.app.battery.BatterySessionAnalyzer
import com.batteryscope.app.battery.BatterySnapshot
import com.batteryscope.app.battery.CapacityPreferences
import com.batteryscope.app.monitor.BatteryMonitoringController
import com.batteryscope.app.settings.AppSettings
import com.batteryscope.app.settings.UiPreferences
import com.batteryscope.app.update.GitHubUpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
            var screen by rememberSaveable { mutableStateOf("live") }
            var theme by remember { mutableStateOf(ui.theme) }
            var colorMode by remember { mutableStateOf(ui.colorMode) }
            var accent by remember { mutableStateOf(ui.accentColorArgb) }
            var colorStyle by remember { mutableStateOf(ui.colorStyle) }

            BatteryScopeTheme(theme, colorMode, accent, colorStyle) {
                when (screen) {
                    "settings" -> SettingsScreen(
                        theme = theme,
                        colorMode = colorMode,
                        accent = accent,
                        colorStyle = colorStyle,
                        settings = settings,
                        onTheme = { theme = it; ui.theme = it },
                        onColorMode = { colorMode = it; ui.colorMode = it },
                        onAccent = { accent = it; ui.accentColorArgb = it },
                        onColorStyle = { colorStyle = it; ui.colorStyle = it },
                        onBack = { screen = "live" },
                    )
                    else -> LiveScreen(settings) { screen = "settings" }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val settings = AppSettings(this)
        if (!settings.notificationEnabled) return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            if (!settings.notificationPermissionRequested) {
                settings.notificationPermissionRequested = true
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            }
        } else {
            BatteryMonitoringController.start(this)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED &&
            AppSettings(this).notificationEnabled
        ) {
            BatteryMonitoringController.start(this)
        }
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 4001
    }
}

@Composable
private fun LiveScreen(settings: AppSettings, openSettings: () -> Unit) {
    val context = LocalContext.current
    var battery by remember { mutableStateOf<BatterySnapshot?>(BatteryRuntime.latest()) }
    var lastUpdatedAt by remember { mutableStateOf(if (battery != null) System.currentTimeMillis() else 0L) }
    var readError by remember { mutableStateOf(false) }
    val interval = settings.updateIntervalMs
    val notifications = settings.notificationEnabled

    LaunchedEffect(notifications, interval) {
        if (BatteryRuntime.latest() == null) {
            runCatching { withContext(Dispatchers.IO) { BatteryRuntime.read(context) } }
                .onSuccess { battery = it; lastUpdatedAt = System.currentTimeMillis(); readError = false }
                .onFailure { readError = true }
        }
        while (isActive) {
            if (notifications) {
                BatteryRuntime.latest()?.let { battery = it; lastUpdatedAt = System.currentTimeMillis(); readError = false }
                delay(500)
            } else {
                runCatching { withContext(Dispatchers.IO) { BatteryRuntime.read(context) } }
                    .onSuccess { battery = it; lastUpdatedAt = System.currentTimeMillis(); readError = false }
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
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("BatteryScope", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text(
                                if (battery?.charging == true) "Live telemetry • charging" else "Live telemetry • on battery",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = openSettings) { Text("Settings") }
                    }
                }
                if (readError && battery == null) {
                    item { InfoCard("Battery data unavailable", "BatteryScope will retry automatically.") }
                }
                battery?.let { value ->
                    item { HeroCard(value, settings) }
                    item { LiveMeta(value, lastUpdatedAt, interval, readError, notifications) }
                    value.sessionAnalysis?.let { analysis ->
                        item { HealthCard(value, analysis) }
                        item { HistoryCard(analysis) }
                    }
                }
                if (battery == null && !readError) {
                    item { InfoCard("Reading battery telemetry…", "The first sample will appear here.") }
                }
            }
        }
    }
}

@Composable
private fun HeroCard(battery: BatterySnapshot, settings: AppSettings) = Card(
    shape = RoundedCornerShape(28.dp),
    colors = CardDefaults.cardColors(MaterialTheme.colorScheme.primaryContainer),
) {
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
            MiniStat("Current", battery.currentA?.let { currentText(it, settings.currentUnit) } ?: "—")
            MiniStat("Voltage", battery.voltageV?.let { "${f1(it)} V" } ?: "—")
            MiniStat("Temp", battery.temperatureC?.let { temperatureText(it, settings.temperatureUnit) } ?: "—")
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String) = Column {
    Text(label, style = MaterialTheme.typography.labelMedium)
    Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun LiveMeta(
    battery: BatterySnapshot,
    lastUpdatedAt: Long,
    interval: Long,
    readError: Boolean,
    notifications: Boolean,
) {
    val age = ((System.currentTimeMillis() - lastUpdatedAt) / 1000L).coerceAtLeast(0L)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            when {
                readError -> "Last good telemetry retained"
                notifications -> "Shared notification telemetry"
                age == 0L -> "Updated just now"
                age == 1L -> "Updated 1 second ago"
                else -> "Updated ${age}s ago"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (battery.charging) "Charging • ${formatInterval(interval)}" else "Every ${formatInterval(interval)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HealthCard(battery: BatterySnapshot, analysis: BatterySessionAnalyzer.Analysis) = Card(shape = RoundedCornerShape(24.dp)) {
    Column(Modifier.fillMaxWidth().padding(18.dp)) {
        Text("Capacity & health", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        StatLine("Design capacity", battery.batteryCapacityMah?.let { "${f0(it)} mAh" } ?: "Not detected")
        StatLine("Learned capacity", analysis.learnedCapacityMah?.let { "${f0(it)} mAh" } ?: "Awaiting session")
        StatLine("Battery health", analysis.healthPercent?.let { "${f0(it)}%" } ?: "Awaiting session")
        StatLine("Health confidence", analysis.healthPercent?.let { "${analysis.healthConfidencePercent}%" } ?: "—")
        StatLine("Wear", analysis.wearMah?.let { "${f0(it)} mAh" } ?: "—")
        StatLine("Remaining", battery.remainingMah?.let { "${f0(it)} mAh" } ?: "Unavailable")
        Spacer(Modifier.height(10.dp))
        HorizontalDivider()
        Spacer(Modifier.height(10.dp))
        Text("Valid health sessions start at 15% or lower and finish at full.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HistoryCard(analysis: BatterySessionAnalyzer.Analysis) = Card(shape = RoundedCornerShape(24.dp)) {
    Column(Modifier.fillMaxWidth().padding(18.dp)) {
        Text("Recent full-charge sessions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (analysis.fullChargeSessions.isEmpty()) {
            Text("No completed sessions yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            analysis.fullChargeSessions.takeLast(4).reversed().forEach { session ->
                StatLine("${f0(session.estimatedCapacityMah)} mAh", "${session.qualityPercent}% • ${date(session.completedAtMs)}")
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) = Card(shape = RoundedCornerShape(24.dp)) {
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatLine(label: String, value: String) = Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(label, Modifier.weight(1f))
    Text(value, Modifier.weight(1f), textAlign = TextAlign.End, fontWeight = FontWeight.Medium)
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
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val capacityPreferences = remember(context) { CapacityPreferences(context) }
    val updateScope = rememberCoroutineScope()
    var currentUnit by remember { mutableStateOf(settings.currentUnit) }
    var temperatureUnit by remember { mutableStateOf(settings.temperatureUnit) }
    var invert by remember { mutableStateOf(settings.invertChargingPolarity) }
    var interval by remember { mutableStateOf(settings.updateIntervalMs) }
    var boot by remember { mutableStateOf(settings.startOnBoot) }
    var capacity by remember { mutableStateOf(capacityPreferences.designCapacityMah?.let(::f0) ?: "") }
    var checking by remember { mutableStateOf(false) }
    var release by remember { mutableStateOf<GitHubUpdateManager.Release?>(null) }
    var updateMessage by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(-1) }

    Scaffold { padding ->
        Surface(Modifier.fillMaxSize().padding(padding), color = MaterialTheme.colorScheme.background) {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(18.dp)) {
                item {
                    Column {
                        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("Everything that changes monitoring or appearance stays here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item { NotificationSettingsSection(settings) }
                item {
                    SettingsCard("Start on boot") {
                        SettingRow("Start on boot", "Start notification monitoring after boot or app update.", boot) { boot = it; settings.startOnBoot = it }
                    }
                }
                item {
                    SettingsCard("Telemetry") {
                        Text("Sampling interval", fontWeight = FontWeight.SemiBold)
                        Text(formatInterval(interval), style = MaterialTheme.typography.titleLarge)
                        Slider(
                            value = interval.toFloat(),
                            onValueChange = { interval = ((it / 250f).roundToInt() * 250L).coerceIn(1_250L, 10_000L) },
                            onValueChangeFinished = { settings.updateIntervalMs = interval },
                            valueRange = 1_250f..10_000f,
                            steps = 34,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("1.25 s"); Text("10 s") }
                        Spacer(Modifier.height(10.dp))
                        SettingRow("Invert charging polarity", "Change current sign live.", invert) { invert = it; settings.invertChargingPolarity = it }
                    }
                }
                item {
                    SettingsCard("Battery capacity") {
                        OutlinedTextField(value = capacity, onValueChange = { capacity = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Design capacity (mAh)") })
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { capacity.toDoubleOrNull()?.takeIf { it in 100.0..30_000.0 }?.let { capacityPreferences.designCapacityMah = it } }) { Text("Save") }
                            OutlinedButton(onClick = { capacityPreferences.designCapacityMah = null; capacity = "" }) { Text("Clear") }
                        }
                    }
                }
                item {
                    SettingsCard("Units") {
                        ChoiceButtons("Current", AppSettings.CurrentUnit.entries.map { it.value }, currentUnit.value) { currentUnit = AppSettings.CurrentUnit.fromValue(it); settings.currentUnit = currentUnit }
                        Spacer(Modifier.height(12.dp))
                        ChoiceButtons("Temperature", AppSettings.TemperatureUnit.entries.map { it.value }, temperatureUnit.value) { temperatureUnit = AppSettings.TemperatureUnit.fromValue(it); settings.temperatureUnit = temperatureUnit }
                    }
                }
                item {
                    SettingsCard("Appearance") {
                        ChoiceButtons("Theme", UiPreferences.Theme.entries.map { it.value }, theme.value) { onTheme(UiPreferences.Theme.fromValue(it)) }
                        Spacer(Modifier.height(12.dp))
                        ChoiceButtons("Theme colour", UiPreferences.ColorMode.entries.map { it.value }, colorMode.value) { onColorMode(UiPreferences.ColorMode.fromValue(it)) }
                        if (colorMode == UiPreferences.ColorMode.CUSTOM) {
                            Spacer(Modifier.height(12.dp))
                            AccentPalette(accent, onAccent)
                            Spacer(Modifier.height(12.dp))
                            ChoiceButtons("Colour style", UiPreferences.ColorStyle.entries.map { it.value }, colorStyle.value) { onColorStyle(UiPreferences.ColorStyle.fromValue(it)) }
                        }
                    }
                }
                item {
                    SettingsCard("App update") {
                        Text("Current version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Checks the latest GitHub release and downloads the signed APK.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !checking && progress < 0,
                                onClick = {
                                    checking = true
                                    release = null
                                    updateMessage = "Checking GitHub…"
                                    updateScope.launch {
                                        runCatching { GitHubUpdateManager.checkLatest(BuildConfig.VERSION_NAME) }
                                            .onSuccess { found ->
                                                release = found
                                                updateMessage = found?.let { "Version ${it.versionName} is available." } ?: "You're up to date."
                                            }
                                            .onFailure { updateMessage = it.message ?: "Update check failed." }
                                        checking = false
                                    }
                                },
                            ) { Text(if (checking) "Checking…" else "Check for updates") }
                            release?.let { found ->
                                OutlinedButton(
                                    enabled = progress < 0,
                                    onClick = {
                                        progress = 0
                                        updateMessage = "Downloading ${found.versionName}…"
                                        updateScope.launch {
                                            runCatching {
                                                GitHubUpdateManager.downloadAndInstall(context, found) { downloaded, total ->
                                                    progress = if (total > 0) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else -1
                                                }
                                            }.onSuccess {
                                                progress = 100
                                                updateMessage = "Installer opened. Confirm the update."
                                            }.onFailure {
                                                progress = -1
                                                updateMessage = it.message ?: "Update installation failed."
                                            }
                                        }
                                    },
                                ) { Text("Download & install") }
                            }
                        }
                        if (progress >= 0) {
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(4.dp))
                            Text("Download $progress%", style = MaterialTheme.typography.bodySmall)
                        }
                        if (updateMessage.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(updateMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) = Card(
    shape = RoundedCornerShape(24.dp),
    colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer),
) {
    Column(Modifier.fillMaxWidth().padding(18.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun SettingRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ChoiceButtons(title: String, options: List<String>, selected: String, onSelected: (String) -> Unit) {
    Text(title, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(7.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            Box(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (option == selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .border(1.dp, if (option == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                TextButton(onClick = { onSelected(option) }, modifier = Modifier.fillMaxWidth()) { Text(option) }
            }
        }
    }
}

@Composable
private fun AccentPalette(accent: Long, onAccent: (Long) -> Unit) {
    val colors = listOf(0xFFFF6F91, 0xFFFF8A65, 0xFFFFC857, 0xFF66BB6A, 0xFF29B6F6, 0xFF7E57C2, 0xFFEC407A, 0xFF26A69A)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        colors.forEach { color ->
            Box(
                Modifier.size(32.dp)
                    .clip(CircleShape)
                    .background(Color(color))
                    .border(2.dp, if (accent == (0xFF000000L or color)) Color.White else Color.Transparent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                TextButton(onClick = { onAccent(0xFF000000L or color) }) { Text("") }
            }
        }
    }
}

private fun formatInterval(ms: Long) = when {
    ms == 1_250L -> "1.25 s"
    ms % 1000L == 0L -> "${ms / 1000}s"
    else -> String.format(Locale.US, "%.2f s", ms / 1000.0)
}

private fun f0(value: Double) = String.format(Locale.US, "%.0f", value)
private fun f1(value: Double) = String.format(Locale.US, "%.1f", value)
private fun f2(value: Double) = String.format(Locale.US, "%.2f", value)
private fun currentText(value: Double, unit: AppSettings.CurrentUnit) = if (unit == AppSettings.CurrentUnit.AMPERE) "${f1(value)} A" else "${f0(value * 1000.0)} mA"
private fun temperatureText(valueC: Double, unit: AppSettings.TemperatureUnit) = if (unit == AppSettings.TemperatureUnit.CELSIUS) "${f1(valueC)} °C" else "${f1(valueC * 9.0 / 5.0 + 32.0)} °F"
private fun date(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(Date(ms))
