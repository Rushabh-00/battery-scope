package com.batteryscope.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity2 : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startMonitoring()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BatteryScopeApp2() }
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) startMonitoring()
            else notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startMonitoring()
        }
    }

    private fun startMonitoring() {
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

    @Composable
    private fun BatteryScopeApp2() {
        val store = remember { BatteryStore(this) }
        var settings by remember { mutableStateOf(store.settings()) }
        var battery by remember { mutableStateOf(readBattery(this)) }
        var tab by remember { mutableIntStateOf(0) }
        var monitoring by remember { mutableStateOf(true) }
        val systemDark = isSystemInDarkTheme()
        val dark = when (settings.theme) {
            "DARK" -> true
            "LIGHT" -> false
            else -> systemDark
        }

        LaunchedEffect(settings.updateIntervalSeconds) {
            while (true) {
                battery = readBattery(this@MainActivity2)
                delay(settings.updateIntervalSeconds * 1000L)
            }
        }

        val sessions = store.sessions()
        val samples = store.samples()
        val health = estimateHealth(sessions)

        MaterialTheme(colorScheme = if (dark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()) {
            Scaffold(
                topBar = {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("BatteryScope", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("Battery telemetry & health", style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick = { tab = 4 }) { Text("⚙ Settings") }
                    }
                },
                bottomBar = {
                    NavigationBar {
                        NavItem(tab, 0, "⚡", "Charging") { tab = 0 }
                        NavItem(tab, 1, "↓", "Discharging") { tab = 1 }
                        NavItem(tab, 2, "♥", "Health") { tab = 2 }
                        NavItem(tab, 3, "◷", "History") { tab = 3 }
                        NavItem(tab, 4, "⚙", "Settings") { tab = 4 }
                    }
                }
            ) { padding ->
                Surface(Modifier.fillMaxSize().padding(padding)) {
                    when (tab) {
                        0 -> ChargingScreen2(battery, health, settings, monitoring) { monitoring = toggleMonitoring(monitoring) }
                        1 -> DischargingScreen2(battery, settings, monitoring) { monitoring = toggleMonitoring(monitoring) }
                        2 -> HealthScreen2(health, sessions, settings)
                        3 -> HistoryScreen2(samples, sessions, settings)
                        else -> SettingsScreen2(settings, { value -> settings = value; store.saveSettings(value) }, monitoring, { monitoring = toggleMonitoring(monitoring) }, { openNotificationSettings() })
                    }
                }
            }
        }
    }

    private fun toggleMonitoring(running: Boolean): Boolean {
        return if (running) {
            stopMonitoring(); false
        } else {
            startMonitoring(); true
        }
    }

    @Composable
    private fun RowScope.NavItem(tab: Int, value: Int, icon: String, label: String, onClick: () -> Unit) {
        NavigationBarItem(selected = tab == value, onClick = onClick, icon = { Text(icon) }, label = { Text(label) })
    }
}

@Composable
private fun ChargingScreen2(battery: BatterySnapshot, health: HealthEstimate, settings: UiSettings, monitoring: Boolean, toggle: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { StatusCard2(battery, settings, "Battery level") }
        item { TelemetryCard2(battery, settings) }
        item { InfoCard2("Battery capacity") {
            Text(health.capacityMah?.let { formatCapacity2(it, settings.chargeUnit) } ?: "Collecting charging data", style = MaterialTheme.typography.headlineSmall)
            Text("Remaining charge: ${formatCharge2(battery.counterMicroAh, settings.chargeUnit)}")
            Text("Design reference: ${formatCapacity2(DESIGN_CAPACITY_MAH, settings.chargeUnit)}")
            Text("Health: ${health.healthPercent?.let { format1_2(it) + "%" } ?: "Waiting for a usable session"}")
        } }
        item { MonitoringCard2(monitoring, toggle) }
    }
}

@Composable
private fun DischargingScreen2(battery: BatterySnapshot, settings: UiSettings, monitoring: Boolean, toggle: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { StatusCard2(battery, settings, "Charge level") }
        item { TelemetryCard2(battery, settings) }
        item { InfoCard2("Discharge") {
            Text("Current: ${formatCurrent2(battery.currentMa, settings.currentUnit)}")
            Text("Power: ${formatPower2(battery.powerW)}")
            Text("Remaining charge: ${formatCharge2(battery.counterMicroAh, settings.chargeUnit)}")
            Text("Energy counter: ${formatEnergy2(battery.energyCounterNWh)}")
        } }
        item { MonitoringCard2(monitoring, toggle) }
    }
}

@Composable
private fun StatusCard2(battery: BatterySnapshot, settings: UiSettings, title: String) {
    Card(Modifier.fillMaxWidth(), RoundedCornerShape(28.dp)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text("${battery.level}%", style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
            Text(battery.status, style = MaterialTheme.typography.titleLarge)
            Text("${formatTemp2(battery.temperatureC, settings.temperatureF)} • ${format3_2(battery.voltageV)} V")
            Text("Current: ${formatCurrent2(battery.currentMa, settings.currentUnit)}")
        }
    }
}

@Composable
private fun TelemetryCard2(battery: BatterySnapshot, settings: UiSettings) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard2("Power", formatPower2(battery.powerW), Modifier.weight(1f))
            MetricCard2("Current", formatCurrent2(battery.currentMa, settings.currentUnit), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard2("Voltage", "${format3_2(battery.voltageV)} V", Modifier.weight(1f))
            MetricCard2("Temperature", formatTemp2(battery.temperatureC, settings.temperatureF), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard2("Remaining charge", formatCharge2(battery.counterMicroAh, settings.chargeUnit), Modifier.weight(1f))
            MetricCard2("Energy", formatEnergy2(battery.energyCounterNWh), Modifier.weight(1f))
        }
    }
}

@Composable
private fun HealthScreen2(health: HealthEstimate, sessions: List<ChargeSession>, settings: UiSettings) {
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { InfoCard2("Battery health") {
            Text(health.healthPercent?.let { "${format1_2(it)}%" } ?: "No estimate yet", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
            Text("Estimated capacity: ${health.capacityMah?.let { formatCapacity2(it, settings.chargeUnit) } ?: "—"}")
            Text("Usable sessions: ${health.completedSessions} • Confidence: ${health.confidencePercent}%")
        } }
        item { InfoCard2("Capacity over time") {
            val recent = sessions.filter { it.endLevel - it.startLevel >= 60 }.takeLast(5)
            SimpleLineChart2(recent.map { it.estimatedCapacityMah })
            Text("Trend: ${regressionSlope2(recent.mapIndexed { i, s -> i.toDouble() to s.estimatedCapacityMah })?.let { "${format1_2(it)} mAh/session" } ?: "Not enough sessions"}")
        } }
        item { InfoCard2("Method") {
            Text("Charge added is integrated from measured current, divided by percentage gained, extrapolated to 100%, and averaged across recent usable sessions.")
            Text("A usable session covers at least 60 percentage points.", style = MaterialTheme.typography.bodySmall)
        } }
    }
}

@Composable
private fun HistoryScreen2(samples: List<HistorySample>, sessions: List<ChargeSession>, settings: UiSettings) {
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("History", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { Text("Charge sessions: ${sessions.size}") }
        items(sessions.asReversed()) { s -> InfoCard2(formatDate2(s.endTime)) {
            Text("${s.startLevel}% → ${s.endLevel}%")
            Text("Charged: ${formatChargeValue2(s.chargedMah, settings.chargeUnit)}")
            Text("Capacity: ${formatCapacity2(s.estimatedCapacityMah, settings.chargeUnit)}")
        } }
        item { HorizontalDivider() }
        item { Text("Telemetry samples: ${samples.size}", style = MaterialTheme.typography.titleMedium) }
        items(samples.asReversed().take(150)) { sample ->
            Text("${sample.level}% • ${formatCurrent2(sample.currentMa, settings.currentUnit)} • ${format3_2(sample.voltageV)} V • ${formatPower2(sample.powerW)}")
        }
    }
}

@Composable
private fun SettingsScreen2(settings: UiSettings, onChange: (UiSettings) -> Unit, monitoring: Boolean, toggleMonitoring: () -> Unit, openNotificationSettings: () -> Unit) {
    val metricKeys = listOf("W", "A", "mAh", "°C", "V", "Wh", "%")
    val tempLabel = if (settings.temperatureF) "°F" else "°C"
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { InfoCard2("Units") {
            Text("Current")
            OptionRow(listOf("A", "mA"), settings.currentUnit) { onChange(settings.copy(currentUnit = it)) }
            Text("Charge")
            OptionRow(listOf("Ah", "mAh"), settings.chargeUnit) { onChange(settings.copy(chargeUnit = it)) }
            Text("Temperature")
            OptionRow(listOf("°C", "°F"), tempLabel) { onChange(settings.copy(temperatureF = it == "°F")) }
            Text("Energy")
            Text("Wh")
        } }
        item { InfoCard2("Appearance") {
            OptionRow(listOf("Auto", "Light", "Dark"), when (settings.theme) { "LIGHT" -> "Light"; "DARK" -> "Dark"; else -> "Auto" }) { selected ->
                onChange(settings.copy(theme = when (selected) { "Light" -> "LIGHT"; "Dark" -> "DARK"; else -> "AUTO" }))
            }
        } }
        item { InfoCard2("Monitoring") {
            Text("Polling interval: ${settings.updateIntervalSeconds}s")
            Slider(value = settings.updateIntervalSeconds.toFloat(), onValueChange = { onChange(settings.copy(updateIntervalSeconds = it.toInt().coerceIn(2, 30))) }, valueRange = 2f..30f, steps = 27)
            Button(onClick = toggleMonitoring) { Text(if (monitoring) "Stop monitoring" else "Start monitoring") }
            Text("Background monitoring backs off while the screen is off and the battery is discharging.", style = MaterialTheme.typography.bodySmall)
        } }
        item { InfoCard2("Notification") {
            Text("Notification icon", style = MaterialTheme.typography.titleMedium)
            Text("Choose the live value shown in the status-bar notification icon.", style = MaterialTheme.typography.bodySmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(metricKeys) { key ->
                    val label = if (key == "°C" && settings.temperatureF) "°F" else key
                    if (settings.notificationIndicator == key) Button(onClick = { onChange(settings.copy(notificationIndicator = key, notificationEntries = settings.notificationEntries - key)) }) { Text(label) }
                    else OutlinedButton(onClick = { onChange(settings.copy(notificationIndicator = key)) }) { Text(label) }
                }
            }
            Text("Show in notification", style = MaterialTheme.typography.titleMedium)
            metricKeys.forEach { key ->
                val label = if (key == "°C" && settings.temperatureF) "°F" else key
                ToggleLine2(label, key in settings.notificationEntries || key == settings.notificationIndicator) { enabled ->
                    val updated = if (enabled) settings.notificationEntries + key else settings.notificationEntries - key
                    onChange(settings.copy(notificationEntries = updated))
                }
            }
            ToggleLine2("Charge-time estimate", settings.showChargeTime) { onChange(settings.copy(showChargeTime = it)) }
            ToggleLine2("Screen state", settings.showScreenState) { onChange(settings.copy(showScreenState = it)) }
            OutlinedButton(onClick = openNotificationSettings) { Text("Open system notification settings") }
        } }
    }
}

@Composable
private fun OptionRow(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options) { option ->
            if (selected == option) Button(onClick = { onSelect(option) }) { Text(option) }
            else OutlinedButton(onClick = { onSelect(option) }) { Text(option) }
        }
    }
}

@Composable
private fun ToggleLine2(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label)
        if (checked) Button(onClick = { onChange(false) }) { Text("ON") }
        else OutlinedButton(onClick = { onChange(true) }) { Text("OFF") }
    }
}

@Composable
private fun MonitoringCard2(monitoring: Boolean, toggle: () -> Unit) {
    InfoCard2("Background monitoring") {
        Text(if (monitoring) "Running" else "Stopped")
        if (monitoring) OutlinedButton(onClick = toggle) { Text("Stop") }
        else Button(onClick = toggle) { Text("Start") }
    }
}

@Composable
private fun InfoCard2(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun MetricCard2(title: String, value: String, modifier: Modifier) {
    Card(modifier, RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SimpleLineChart2(values: List<Double>) {
    if (values.isEmpty()) {
        Text("No usable session data yet.")
        return
    }
    val min = values.minOrNull() ?: 0.0
    val max = values.maxOrNull() ?: 1.0
    val range = (max - min).takeIf { it > 0.0 } ?: 1.0
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(150.dp).padding(vertical = 8.dp)) {
        val path = androidx.compose.ui.graphics.Path()
        values.forEachIndexed { index, value ->
            val x = if (values.size == 1) size.width / 2f else index.toFloat() / (values.size - 1) * size.width
            val y = size.height - ((value - min) / range).toFloat() * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = MaterialTheme.colorScheme.primary, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f))
    }
}

private fun formatCurrent2(ma: Double?, unit: String): String {
    if (ma == null) return "—"
    return if (unit == "mA") "${format1_2(abs(ma))} mA" else "${format3_2(abs(ma) / 1000.0)} A"
}

private fun formatPower2(w: Double): String = "${format2_2(abs(w))} W"
private fun formatCharge2(microAh: Long?, unit: String): String {
    if (microAh == null || microAh < 0) return "—"
    return formatChargeValue2(microAh / 1000.0, unit)
}
private fun formatChargeValue2(mah: Double, unit: String): String = if (unit == "mAh") "${format0_2(mah)} mAh" else "${format3_2(mah / 1000.0)} Ah"
private fun formatCapacity2(mah: Double, unit: String): String = formatChargeValue2(mah, unit)
private fun formatEnergy2(nWh: Long?): String = if (nWh == null || nWh < 0) "—" else "${format2_2(nWh / 1_000_000_000.0)} Wh"
private fun formatTemp2(c: Double, fahrenheit: Boolean): String = if (fahrenheit) "${format1_2(c * 9 / 5 + 32)} °F" else "${format1_2(c)} °C"
private fun format3_2(v: Double): String = String.format(Locale.US, "%.3f", v)
private fun format2_2(v: Double): String = String.format(Locale.US, "%.2f", v)
private fun format1_2(v: Double): String = String.format(Locale.US, "%.1f", v)
private fun format0_2(v: Double): String = String.format(Locale.US, "%.0f", v)
private fun formatDate2(t: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(t))
private fun regressionSlope2(points: List<Pair<Double, Double>>): Double? {
    if (points.size < 2) return null
    val meanX = points.map { it.first }.average()
    val meanY = points.map { it.second }.average()
    val numerator = points.sumOf { (it.first - meanX) * (it.second - meanY) }
    val denominator = points.sumOf { (it.first - meanX) * (it.first - meanX) }
    return if (denominator == 0.0) null else numerator / denominator
}
