package com.batteryscope.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private val Accent = Color(0xFFFFB6C1)
private val OledSurface = Color(0xFF0A0A0A)
private val OledCard = Color(0xFF111111)

private val OledDark = androidx.compose.material3.darkColorScheme(
    primary = Accent,
    onPrimary = Color.Black,
    background = Color.Black,
    onBackground = Color(0xFFF4E9EC),
    surface = OledCard,
    onSurface = Color(0xFFF4E9EC),
    surfaceVariant = Color(0xFF181515),
    outline = Color(0xFF817477),
)

class MainActivity : ComponentActivity() {
    private lateinit var store: BatteryStore

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startMonitoring()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = BatteryStore(this)
        ensureMonitoringPermissionAndStart()
        setContent { BatteryScopeRoot() }
    }

    private fun ensureMonitoringPermissionAndStart() {
        if (!store.settings().notificationEnabled) return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startMonitoring()
        }
    }

    private fun startMonitoring() {
        if (!store.settings().notificationEnabled) return
        val intent = Intent(this, BatteryMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    private fun stopMonitoring() = stopService(Intent(this, BatteryMonitorService::class.java))

    private fun openNotificationSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            })
        }.onFailure {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun openBatterySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) }
            .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun openAppBatterySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun applySettings(value: UiSettings) {
        store.saveSettings(value)
        if (value.notificationEnabled) ensureMonitoringPermissionAndStart() else stopMonitoring()
    }

    @Composable
    private fun BatteryScopeRoot() {
        var settings by remember { mutableStateOf(store.settings()) }
        var battery by remember { mutableStateOf(readBattery(this@MainActivity, store.autoCurrentScale())) }
        var sessions by remember { mutableStateOf(store.sessions()) }
        var samples by remember { mutableStateOf(store.samples()) }
        var tab by remember { mutableIntStateOf(0) }

        LaunchedEffect(settings.updateIntervalSeconds) {
            while (true) {
                val currentSettings = store.settings()
                settings = currentSettings
                battery = readBattery(this@MainActivity, store.autoCurrentScale())
                sessions = store.sessions()
                samples = store.samples()
                delay(currentSettings.updateIntervalSeconds * 1000L)
            }
        }

        val dark = when (settings.theme) {
            "DARK" -> true
            "LIGHT" -> false
            else -> isSystemInDarkTheme()
        }
        val health = estimateHealth(sessions)
        val screenTime = store.screenTimeMillis()
        val chargingSince = store.chargingSinceMillis()

        MaterialTheme(colorScheme = if (dark) OledDark else androidx.compose.material3.lightColorScheme()) {
            androidx.compose.runtime.SideEffect {
                window.statusBarColor = if (dark) AndroidColor.BLACK else AndroidColor.WHITE
                window.navigationBarColor = if (dark) AndroidColor.BLACK else AndroidColor.WHITE
            }
            Scaffold(
                topBar = {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("BatteryScope", style = MaterialTheme.typography.headlineSmall)
                            Text("Battery telemetry & health", style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick = { tab = 3 }) { Text("Settings") }
                    }
                },
                bottomBar = {
                    NavigationBar {
                        NavItem(tab, 0, "◉", "Live") { tab = 0 }
                        NavItem(tab, 1, "♥", "Health") { tab = 1 }
                        NavItem(tab, 2, "◷", "History") { tab = 2 }
                    }
                }
            ) { padding ->
                Surface(Modifier.fillMaxSize().padding(padding)) {
                    when (tab) {
                        0 -> LiveScreen(battery, settings, screenTime, chargingSince)
                        1 -> HealthScreen(health, sessions, settings)
                        2 -> HistoryScreen(samples, sessions, settings)
                        else -> SettingsScreen(
                            settings = settings,
                            onChange = { value -> settings = value; applySettings(value) },
                            onNotificationSettings = ::openNotificationSettings,
                            onBatterySettings = ::openBatterySettings,
                            onAppBatterySettings = ::openAppBatterySettings
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun RowScope.NavItem(tab: Int, value: Int, icon: String, label: String, onClick: () -> Unit) {
        NavigationBarItem(
            selected = tab == value,
            onClick = onClick,
            icon = { Text(icon) },
            label = { Text(label) }
        )
    }
}

@Composable
private fun LiveScreen(
    battery: BatterySnapshot,
    settings: UiSettings,
    screenTime: Long,
    chargingSince: Long?
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(30.dp)),
                shape = RoundedCornerShape(30.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        formatPowerSigned(battery.powerW),
                        style = MaterialTheme.typography.displayLarge
                    )
                    LinearProgressIndicator(
                        progress = { battery.level / 100f },
                        modifier = Modifier.fillMaxWidth(0.62f).height(7.dp).clip(RoundedCornerShape(99.dp))
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("${battery.level}%", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            battery.status == "Full" -> "Full"
                            battery.charging -> "Charging"
                            else -> "Discharging"
                        },
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }
        }
        item {
            InfoCard("Live telemetry") {
                MetricLine("Power", formatPowerSigned(battery.powerW))
                MetricLine("Current", formatCurrent(battery.currentMa, settings.currentUnit))
                MetricLine("Voltage", "${format3(battery.voltageV)} V")
                MetricLine("Temperature", formatTemp(battery.temperatureC, settings.temperatureF))
                MetricLine("Remaining charge", formatCharge(battery.counterMicroAh, settings.chargeUnit))
                MetricLine("Energy", formatEnergy(battery.energyCounterNWh))
            }
        }
        item {
            InfoCard("Session") {
                if (battery.charging && battery.chargeTimeRemainingMs != null) {
                    MetricLine("Full in", formatDuration(battery.chargeTimeRemainingMs))
                }
                if (chargingSince != null && battery.charging) {
                    MetricLine("Charging since", formatDuration(System.currentTimeMillis() - chargingSince))
                }
                MetricLine("Screen time", formatDuration(screenTime))
                Text(
                    "Current readings use Android BatteryManager properties; capacity and health are calculated from measured charging sessions.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        item {
            InfoCard("Monitoring") {
                Text(if (settings.notificationEnabled) "Persistent notification is on." else "Persistent notification is off.")
                Text("Automatic device calibration is applied in the background when battery charge-counter data is reliable.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun HealthScreen(health: HealthEstimate, sessions: List<ChargeSession>, settings: UiSettings) {
    val valid = sessions.filter { it.endLevel - it.startLevel >= 60 }
    val recent = valid.takeLast(8)
    val slope = regressionSlope(recent.mapIndexed { index, s -> index.toDouble() to s.estimatedCapacityMah })
    val latest = recent.lastOrNull()

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            InfoCard("Battery health") {
                Text(health.healthPercent?.let { "${format1(it)}%" } ?: "Learning…", style = MaterialTheme.typography.displayMedium)
                Text("Estimated capacity: ${health.capacityMah?.let { formatCapacity(it, settings.chargeUnit) } ?: "—"}")
                Text("Design reference: ${formatCapacity(DESIGN_CAPACITY_MAH, settings.chargeUnit)}")
                Text("Usable sessions: ${health.completedSessions} • Confidence: ${health.confidencePercent}%")
            }
        }
        item {
            InfoCard("Capacity over time") {
                CapacityChart(recent.map { it.estimatedCapacityMah })
                Text("Rolling average is calculated from recent usable sessions.", style = MaterialTheme.typography.bodySmall)
                Text("Trend: ${slope?.let { "${format1(it)} mAh/session" } ?: "Not enough data"}")
            }
        }
        item {
            InfoCard("Latest usable session") {
                if (latest == null) {
                    Text("Keep the monitor running through a large charge increase to build the first estimate.")
                } else {
                    Text(formatDate(latest.endTime))
                    MetricLine("Battery", "${latest.startLevel}% → ${latest.endLevel}%")
                    MetricLine("Charge added", formatChargeValue(latest.chargedMah, settings.chargeUnit))
                    MetricLine("Estimated capacity", formatCapacity(latest.estimatedCapacityMah, settings.chargeUnit))
                    MetricLine("Cycle fraction", format2(latest.wearCycles))
                }
            }
        }
        item {
            InfoCard("Wear") {
                val wear7 = sessions.filter { it.endTime >= System.currentTimeMillis() - 7L * 86_400_000L }.sumOf { it.wearCycles }
                val wear30 = sessions.filter { it.endTime >= System.currentTimeMillis() - 30L * 86_400_000L }.sumOf { it.wearCycles }
                MetricLine("Past 7 days", "${format2(wear7)} cycles")
                MetricLine("Past 30 days", "${format2(wear30)} cycles")
                MetricLine("All time", "${format2(sessions.sumOf { it.wearCycles })} cycles")
            }
        }
        item {
            InfoCard("How it is calculated") {
                Text("Charge added is integrated from the measured battery current over time. The measured charge is divided by percentage gained and extrapolated to 100%.")
                Text("Only sessions covering at least 60 percentage points are used for the health estimate. BatteryScope reports an estimate and confidence, not a factory battery-health value.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun HistoryScreen(samples: List<HistorySample>, sessions: List<ChargeSession>, settings: UiSettings) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Text("Charge sessions (${sessions.size})", style = MaterialTheme.typography.titleLarge) }
        items(sessions.asReversed()) { session ->
            InfoCard(formatDate(session.endTime)) {
                MetricLine("Level", "${session.startLevel}% → ${session.endLevel}%")
                MetricLine("Charge added", formatChargeValue(session.chargedMah, settings.chargeUnit))
                MetricLine("Capacity", formatCapacity(session.estimatedCapacityMah, settings.chargeUnit))
                MetricLine("Wear", "${format2(session.wearCycles)} cycles")
            }
        }
        item { HorizontalDivider() }
        item { Text("Telemetry samples (${samples.size})", style = MaterialTheme.typography.titleLarge) }
        items(samples.asReversed().take(200)) { sample ->
            InfoCard("${sample.level}% • ${formatDate(sample.timestamp)}") {
                Text("${formatCurrent(sample.currentMa, settings.currentUnit)} • ${format3(sample.voltageV)} V • ${formatPowerSigned(sample.powerW)} • ${formatTemp(sample.temperatureC, settings.temperatureF)}")
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    settings: UiSettings,
    onChange: (UiSettings) -> Unit,
    onNotificationSettings: () -> Unit,
    onBatterySettings: () -> Unit,
    onAppBatterySettings: () -> Unit
) {
    val metrics = listOf("W", "A", "mAh", "°C", "V", "Wh", "%")
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium) }
        item {
            InfoCard("Notification") {
                SettingSwitch("Persistent notification", "Keep live battery telemetry available in the notification shade.", settings.notificationEnabled) { onChange(settings.copy(notificationEnabled = it)) }
                Spacer(Modifier.height(4.dp))
                Text("Notification icon", style = MaterialTheme.typography.titleMedium)
                OptionRow(metrics, displayMetricLabel(settings.notificationIndicator, settings.temperatureF), settings.notificationIndicator) { key ->
                    onChange(settings.copy(notificationIndicator = key, notificationEntries = settings.notificationEntries + key))
                }
                Spacer(Modifier.height(6.dp))
                Text("Notification entries", style = MaterialTheme.typography.titleMedium)
                OptionRow(metrics, "", "") { }
                metrics.forEach { key ->
                    val label = displayMetricLabel(key, settings.temperatureF)
                    SettingSwitch(label, "", key in settings.notificationEntries || key == settings.notificationIndicator) { enabled ->
                        val updated = if (enabled) settings.notificationEntries + key else settings.notificationEntries - key
                        onChange(settings.copy(notificationEntries = updated))
                    }
                }
                SettingSwitch("Charge time estimate", "Show time remaining while charging when Android provides it.", settings.showChargeTime) { onChange(settings.copy(showChargeTime = it)) }
                SettingSwitch("Screen state", "Show whether the display is interactive in the notification.", settings.showScreenState) { onChange(settings.copy(showScreenState = it)) }
                OutlinedButton(onClick = onNotificationSettings) { Text("System notification settings") }
            }
        }
        item {
            InfoCard("Units") {
                Text("Current")
                OptionRow(listOf("A", "mA"), settings.currentUnit, settings.currentUnit) { onChange(settings.copy(currentUnit = it)) }
                Text("Charge")
                OptionRow(listOf("Ah", "mAh"), settings.chargeUnit, settings.chargeUnit) { onChange(settings.copy(chargeUnit = it)) }
                Text("Temperature")
                val temp = if (settings.temperatureF) "°F" else "°C"
                OptionRow(listOf("°C", "°F"), temp, temp) { onChange(settings.copy(temperatureF = it == "°F")) }
                Text("Energy")
                Text("Wh")
            }
        }
        item {
            InfoCard("Appearance") {
                val selected = when (settings.theme) { "LIGHT" -> "Light"; "DARK" -> "Dark"; else -> "Auto" }
                OptionRow(listOf("Auto", "Light", "Dark"), selected, selected) { label ->
                    onChange(settings.copy(theme = when (label) { "Light" -> "LIGHT"; "Dark" -> "DARK"; else -> "AUTO" }))
                }
            }
        }
        item {
            InfoCard("Monitoring") {
                Text("Polling interval: ${settings.updateIntervalSeconds}s")
                Slider(value = settings.updateIntervalSeconds.toFloat(), onValueChange = { onChange(settings.copy(updateIntervalSeconds = it.toInt().coerceIn(2, 30))) }, valueRange = 2f..30f, steps = 27)
                Text("BatteryScope automatically uses a slower interval while the screen is off and the battery is discharging to reduce RAM/CPU wakeups.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onBatterySettings) { Text("System battery settings") }
                OutlinedButton(onClick = onAppBatterySettings) { Text("App battery / background settings") }
            }
        }
    }
}

@Composable
private fun OptionRow(options: List<String>, selectedLabel: String, selectedKey: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            val key = option
            if (option == selectedLabel || option == selectedKey) Button(onClick = { onSelect(key) }) { Text(option) }
            else OutlinedButton(onClick = { onSelect(key) }) { Text(option) }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(24.dp)),
        RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun MetricLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun CapacityChart(values: List<Double>) {
    if (values.isEmpty()) {
        Text("No usable sessions yet.")
        return
    }
    val lineColor = MaterialTheme.colorScheme.primary
    val pointColor = MaterialTheme.colorScheme.onSurface
    val min = values.minOrNull() ?: 0.0
    val max = values.maxOrNull() ?: 1.0
    val range = (max - min).takeIf { it > 0.0 } ?: 1.0
    Canvas(Modifier.fillMaxWidth().height(150.dp).padding(vertical = 8.dp)) {
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = if (values.size == 1) size.width / 2f else index.toFloat() / (values.size - 1) * size.width
            val y = size.height - ((value - min) / range).toFloat() * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = lineColor, style = Stroke(width = 4f, cap = StrokeCap.Round))
        values.forEachIndexed { index, value ->
            val x = if (values.size == 1) size.width / 2f else index.toFloat() / (values.size - 1) * size.width
            val y = size.height - ((value - min) / range).toFloat() * size.height
            drawCircle(pointColor, 4f, androidx.compose.ui.geometry.Offset(x, y))
        }
    }
    Text("${format0(values.first())} mAh → ${format0(values.last())} mAh", style = MaterialTheme.typography.bodySmall)
}

private fun displayMetricLabel(key: String, fahrenheit: Boolean): String = if (key == "°C" && fahrenheit) "°F" else key
private fun formatCurrent(ma: Double?, unit: String): String {
    if (ma == null) return "—"
    return if (unit == "mA") "${format1(abs(ma))} mA" else "${format3(abs(ma) / 1000.0)} A"
}
private fun formatPowerSigned(watts: Double): String = if (watts >= 0) "+${format2(abs(watts))} W" else "-${format2(abs(watts))} W"
private fun formatCharge(microAh: Long?, unit: String): String {
    if (microAh == null || microAh < 0) return "—"
    return formatChargeValue(microAh / 1000.0, unit)
}
private fun formatChargeValue(mah: Double, unit: String): String = if (unit == "mAh") "${format0(mah)} mAh" else "${format3(mah / 1000.0)} Ah"
private fun formatCapacity(mah: Double, unit: String): String = formatChargeValue(mah, unit)
private fun formatEnergy(counterNWh: Long?): String = if (counterNWh == null || counterNWh < 0) "—" else "${format2(counterNWh / 1_000_000_000.0)} Wh"
private fun formatTemp(celsius: Double, fahrenheit: Boolean): String = if (fahrenheit) "${format1(celsius * 9 / 5 + 32)} °F" else "${format1(celsius)} °C"
private fun formatDuration(ms: Long): String {
    val totalMinutes = (ms / 60_000L).coerceAtLeast(0L)
    val h = totalMinutes / 60L
    val m = totalMinutes % 60L
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
private fun format3(value: Double): String = String.format(Locale.US, "%.3f", value)
private fun format2(value: Double): String = String.format(Locale.US, "%.2f", value)
private fun format1(value: Double): String = String.format(Locale.US, "%.1f", value)
private fun format0(value: Double): String = String.format(Locale.US, "%.0f", value)
private fun formatDate(time: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))
private fun regressionSlope(points: List<Pair<Double, Double>>): Double? {
    if (points.size < 2) return null
    val meanX = points.map { it.first }.average()
    val meanY = points.map { it.second }.average()
    val numerator = points.sumOf { (it.first - meanX) * (it.second - meanY) }
    val denominator = points.sumOf { (it.first - meanX) * (it.first - meanX) }
    return if (denominator == 0.0) null else numerator / denominator
}
