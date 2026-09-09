package com.batteryscope.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { BatteryScopeApp() }
    }

    private fun startMonitoring() {
        val intent = Intent(this, BatteryMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    private fun stopMonitoring() = stopService(Intent(this, BatteryMonitorService::class.java))

    @Composable
    private fun BatteryScopeApp() {
        val store = remember { BatteryStore(this) }
        var settings by remember { mutableStateOf(store.settings()) }
        var battery by remember { mutableStateOf(readBattery(this)) }
        var tab by remember { mutableIntStateOf(0) }
        var monitoring by remember { mutableStateOf(false) }
        val systemDark = isSystemInDarkTheme()
        val dark = when (settings.theme) {
            "DARK" -> true
            "LIGHT" -> false
            else -> systemDark
        }

        LaunchedEffect(settings.updateIntervalSeconds) {
            while (true) {
                battery = readBattery(this@MainActivity)
                delay(settings.updateIntervalSeconds * 1000L)
            }
        }

        val sessions = store.sessions()
        val samples = store.samples()
        val health = estimateHealth(sessions)

        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
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
                        OutlinedButton(onClick = { tab = 3 }) { Text("⚙ Settings") }
                    }
                },
                bottomBar = {
                    NavigationBar {
                        NavItem(tab, 0, "⚡", "Charging") { tab = 0 }
                        NavItem(tab, 1, "↓", "Discharging") { tab = 1 }
                        NavItem(tab, 2, "♥", "Health") { tab = 2 }
                        NavItem(tab, 4, "◷", "History") { tab = 4 }
                    }
                }
            ) { padding ->
                Surface(Modifier.fillMaxSize().padding(padding)) {
                    when (tab) {
                        0 -> ChargingScreen(battery, health, settings, monitoring, { monitoring = true; startMonitoring() }, { monitoring = false; stopMonitoring() })
                        1 -> DischargingScreen(battery, settings, monitoring, { monitoring = true; startMonitoring() }, { monitoring = false; stopMonitoring() })
                        2 -> HealthScreen(health, sessions, settings)
                        3 -> SettingsScreen(settings, { settings = it; store.saveSettings(it) }, { openBatteryUsage() }, { openAppSettings() })
                        else -> HistoryScreen(samples, sessions, settings)
                    }
                }
            }
        }
    }

    private fun openBatteryUsage() {
        runCatching { startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) }
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            })
        }
    }

    @Composable
    private fun NavItem(tab: Int, value: Int, icon: String, label: String, onClick: () -> Unit) {
        NavigationBarItem(selected = tab == value, onClick = onClick, icon = { Text(icon) }, label = { Text(label) })
    }
}

@Composable
private fun ChargingScreen(
    battery: BatterySnapshot,
    health: HealthEstimate,
    settings: UiSettings,
    monitoring: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { StatusCard(battery, settings, "Battery level") }
        item { TelemetryGrid(battery, settings) }
        item { InfoCard("Battery capacity") {
            Text(health.capacityMah?.let { formatCapacity(it, settings.chargeUnit) } ?: "Collecting usable charge data", style = MaterialTheme.typography.headlineSmall)
            Text("Estimated full-charge capacity from measured charging current + percentage gain.")
            Text("Design reference: ${formatCapacity(DESIGN_CAPACITY_MAH, settings.chargeUnit)}")
            Text("Remaining charge: ${formatCharge(battery.counterMicroAh, settings.chargeUnit)}")
            Text("Health: ${health.healthPercent?.let { "${format1(it)}%" } ?: "Waiting for usable sessions"}")
        } }
        item { InfoCard("Charge time") {
            Text(if (settings.showChargeTime && battery.charging && battery.chargeTimeRemainingMs != null) formatDuration(battery.chargeTimeRemainingMs) else "Unavailable from Android", style = MaterialTheme.typography.headlineSmall)
            Text("Operating-system estimate while charging.")
        } }
        item { MonitoringCard(monitoring, onStart, onStop) }
    }
}

@Composable
private fun DischargingScreen(
    battery: BatterySnapshot,
    settings: UiSettings,
    monitoring: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { StatusCard(battery, settings, "Charge level") }
        item { TelemetryGrid(battery, settings) }
        item { InfoCard("Discharge summary") {
            Text("Average draw: ${formatCurrent(battery.averageCurrentMa, settings.currentUnit)}")
            Text("Power now: ${formatPower(battery.powerW, settings.powerScalar)}")
            Text("Remaining charge: ${formatCharge(battery.counterMicroAh, settings.chargeUnit)}")
            Text("Energy remaining: ${formatEnergy(battery.energyCounterNWh)}")
            Text("Negative current/power means energy is leaving the battery.", style = MaterialTheme.typography.bodySmall)
        } }
        item { MonitoringCard(monitoring, onStart, onStop) }
    }
}

@Composable
private fun StatusCard(battery: BatterySnapshot, settings: UiSettings, title: String) {
    Card(Modifier.fillMaxWidth(), RoundedCornerShape(28.dp)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text("${battery.level}%", style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
            Text(battery.status, style = MaterialTheme.typography.titleLarge)
            if (settings.showTemperature) Text("Temperature: ${formatTemp(battery.temperatureC, settings.temperatureF)} • ${classifyTemperature(battery.temperatureC)}")
            Text("Technology: ${battery.technology}")
            battery.cycleCount?.let { Text("Cycle count: $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun TelemetryGrid(battery: BatterySnapshot, settings: UiSettings) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MetricRow {
            if (settings.showPower) MetricCard("Power", formatPower(battery.powerW, settings.powerScalar), Modifier.weight(1f))
            if (settings.showCurrent) MetricCard("Current", formatCurrent(battery.currentMa, settings.currentUnit), Modifier.weight(1f))
        }
        MetricRow {
            if (settings.showVoltage) MetricCard("Voltage", "${format3(battery.voltageV)} V", Modifier.weight(1f))
            if (settings.showTemperature) MetricCard("Temperature", formatTemp(battery.temperatureC, settings.temperatureF), Modifier.weight(1f))
        }
        MetricRow {
            if (settings.showRemainingCharge) MetricCard("Used charge", formatCharge(battery.counterMicroAh, settings.chargeUnit), Modifier.weight(1f))
            if (settings.showEnergy) MetricCard("Energy", formatEnergy(battery.energyCounterNWh), Modifier.weight(1f))
        }
    }
}

@Composable
private fun MetricRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable
private fun MonitoringCard(monitoring: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    InfoCard("Background monitoring") {
        Text(if (monitoring) "Running • collecting samples in the background." else "Off • start monitoring to collect sessions while the app is closed.")
        if (monitoring) OutlinedButton(onClick = onStop) { Text("Stop monitoring") }
        else Button(onClick = onStart) { Text("Start monitoring") }
    }
}

@Composable
private fun HealthScreen(health: HealthEstimate, sessions: List<ChargeSession>, settings: UiSettings) {
    val valid = sessions.filter { it.endLevel - it.startLevel >= 60 }
    val recent = valid.takeLast(5)
    val avgWear = recent.map { it.wearCycles }.average().takeIf { !it.isNaN() }
    val avgEfficiency = recent.map { it.efficiencyPercent }.average().takeIf { !it.isNaN() }
    val slope = regressionSlope(recent.mapIndexed { index, s -> index.toDouble() to s.estimatedCapacityMah })

    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            InfoCard("Battery health") {
                Text(health.healthPercent?.let { "${format1(it)}%" } ?: "No usable estimate yet", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
                HealthBar(health.healthPercent)
                Text("Estimated capacity: ${health.capacityMah?.let { formatCapacity(it, settings.chargeUnit) } ?: "—"}")
                Text("Design capacity: ${formatCapacity(DESIGN_CAPACITY_MAH, settings.chargeUnit)}")
                Text("${health.completedSessions} usable session(s) • confidence ${health.confidencePercent}%")
            }
        }
        item { InfoCard("Battery capacity over time") {
            Text("Moving average of the latest five usable charging sessions.", style = MaterialTheme.typography.bodySmall)
            SimpleLineChart(recent.map { it.estimatedCapacityMah }, DESIGN_CAPACITY_MAH * 0.5, DESIGN_CAPACITY_MAH * 1.05, "mAh")
            Text("Trend: ${slope?.let { "%.1f mAh/session".format(it) } ?: "Not enough sessions"}", style = MaterialTheme.typography.bodySmall)
        } }
        item { InfoCard("Selected session") {
            val last = recent.lastOrNull()
            if (last == null) Text("No usable session yet. Keep monitoring while charging.") else {
                Text(formatDate(last.endTime))
                Text("Battery level: ${last.startLevel}% → ${last.endLevel}%")
                Text("Charged: ${formatChargeValue(last.chargedMah, settings.chargeUnit)}")
                Text("Estimated capacity: ${formatCapacity(last.estimatedCapacityMah, settings.chargeUnit)}")
                Text("Health: ${format1(last.estimatedCapacityMah / DESIGN_CAPACITY_MAH * 100.0)}%")
                Text("Peak voltage: ${format3(last.endVoltageV)} V")
            }
        } }
        item { MetricRow {
            MetricCard("Recent wear", avgWear?.let { "${format2(it)} cycles" } ?: "—", Modifier.weight(1f))
            MetricCard("Efficiency", avgEfficiency?.let { "${format0(it)}%" } ?: "—", Modifier.weight(1f))
        } }
        item { InfoCard("Battery wear") {
            Text("Voltage-based modeled wear. Estimate only; not a factory battery-health reading.", style = MaterialTheme.typography.bodySmall)
            SimpleLineChart(recent.map { it.wearCycles }, 0.0, maxOf(2.0, recent.maxOfOrNull { it.wearCycles } ?: 2.0), "cycles")
        } }
        item { WearPeriods(sessions) }
        item { InfoCard("How health is calculated") {
            Text("Measured charging current is integrated over time to estimate charge added. Charge added is divided by percentage gained and extrapolated to 100% capacity, then recent usable sessions are averaged.")
            Text("A usable session covers at least 60 percentage points. Health = estimated capacity ÷ design reference × 100.", style = MaterialTheme.typography.bodySmall)
        } }
    }
}

@Composable
private fun WearPeriods(sessions: List<ChargeSession>) {
    val now = System.currentTimeMillis()
    fun total(days: Long) = sessions.filter { it.endTime >= now - days * 86_400_000L }.sumOf { it.wearCycles }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Battery wear by period", style = MaterialTheme.typography.titleMedium)
        MetricRow {
            MetricCard("Past 7 days", "${format2(total(7))} cycles", Modifier.weight(1f))
            MetricCard("Past 30 days", "${format2(total(30))} cycles", Modifier.weight(1f))
        }
        MetricRow {
            MetricCard("Past year", "${format2(total(365))} cycles", Modifier.weight(1f))
            MetricCard("All time", "${format2(sessions.sumOf { it.wearCycles })} cycles", Modifier.weight(1f))
        }
    }
}

@Composable
private fun HistoryScreen(samples: List<HistorySample>, sessions: List<ChargeSession>, settings: UiSettings) {
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("History", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { Text("Locally stored telemetry and charge sessions.", style = MaterialTheme.typography.bodySmall) }
        item { HorizontalDivider() }
        item { Text("Charge sessions (${sessions.size})", style = MaterialTheme.typography.titleMedium) }
        items(sessions.asReversed()) { SessionCard(it, settings) }
        item { Text("Telemetry (${samples.size} samples)", style = MaterialTheme.typography.titleMedium) }
        items(samples.asReversed().take(150)) { sample ->
            InfoCard("${sample.level}% • ${formatTemp(sample.temperatureC, settings.temperatureF)}") {
                Text("${format3(sample.voltageV)} V")
                Text(formatCurrent(sample.currentMa, settings.currentUnit))
                Text(DateFormat.getDateTimeInstance().format(Date(sample.timestamp)), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (sessions.isEmpty() && samples.isEmpty()) item { Text("No history yet. Start background monitoring.") }
    }
}

@Composable
private fun SessionCard(session: ChargeSession, settings: UiSettings) {
    InfoCard("${session.startLevel}% → ${session.endLevel}%") {
        Text("Charged: ${formatChargeValue(session.chargedMah, settings.chargeUnit)}")
        Text("Estimated capacity: ${formatCapacity(session.estimatedCapacityMah, settings.chargeUnit)}")
        Text("Health: ${format1(session.estimatedCapacityMah / DESIGN_CAPACITY_MAH * 100.0)}%")
        Text("Wear: ${format2(session.wearCycles)} cycles • Efficiency: ${format0(session.efficiencyPercent)}%")
        Text("Peak: ${format3(session.endVoltageV)} V • ${formatDate(session.endTime)}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SettingsScreen(settings: UiSettings, onChange: (UiSettings) -> Unit, onOpenBatteryUsage: () -> Unit, onOpenAppSettings: () -> Unit) {
    val scalars = listOf(0.001f, 0.5f, 1f, 2f, 1000f)
    val scalarLabels = listOf("0.001×", "0.5×", "1×", "2×", "1000×")
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { InfoCard("Appearance") {
            Text("Choose app theme")
            SegmentedRow(listOf("AUTO", "LIGHT", "DARK"), settings.theme) { onChange(settings.copy(theme = it)) }
            Text("Auto follows your Android system theme.", style = MaterialTheme.typography.bodySmall)
        } }
        item { InfoCard("Units") {
            Text("Current")
            SegmentedRow(listOf("A", "mA"), settings.currentUnit) { onChange(settings.copy(currentUnit = it)) }
            Text("Charge")
            SegmentedRow(listOf("Ah", "mAh"), settings.chargeUnit) { onChange(settings.copy(chargeUnit = it)) }
            Text("Energy")
            SegmentedRow(listOf("Wh"), "Wh") { }
        } }
        item { InfoCard("Workarounds") {
            Text("Power scalar: ${scalarLabels[scalars.indexOfFirst { abs(it - settings.powerScalar) < 0.0001f }.coerceAtLeast(0)]}")
            SegmentedRow(scalarLabels, scalarLabels[scalars.indexOfFirst { abs(it - settings.powerScalar) < 0.0001f }.coerceAtLeast(0)]) {
                onChange(settings.copy(powerScalar = scalars[scalarLabels.indexOf(it)]))
            }
            ToggleButtonRow("Invert charging indicator", settings.invertCharging) { onChange(settings.copy(invertCharging = it)) }
            ToggleButtonRow("Fahrenheit", settings.temperatureF) { onChange(settings.copy(temperatureF = it)) }
        } }
        item { InfoCard("Update interval") {
            Text("${settings.updateIntervalSeconds}s")
            Slider(value = settings.updateIntervalSeconds.toFloat(), onValueChange = { onChange(settings.copy(updateIntervalSeconds = it.toInt().coerceIn(1, 10))) }, valueRange = 1f..10f, steps = 8)
            Text("Faster sampling uses more battery.", style = MaterialTheme.typography.bodySmall)
        } }
        item { InfoCard("Notification") {
            Text("Notification entries")
            listOf("W", "A", "Ah", "°C", "V", "Wh", "%").forEach { key ->
                ToggleButtonRow(key, key in settings.notificationEntries) {
                    val next = settings.notificationEntries.toMutableSet().apply { if (it) add(key) else remove(key) }
                    onChange(settings.copy(notificationEntries = next))
                }
            }
            ToggleButtonRow("Charge time estimate", settings.showChargeTime) { onChange(settings.copy(showChargeTime = it)) }
            ToggleButtonRow("Screen state", settings.showScreenState) { onChange(settings.copy(showScreenState = it)) }
        } }
        item { InfoCard("Battery alarms") {
            ToggleButtonRow("Low battery alert", settings.lowBatteryAlarm) { onChange(settings.copy(lowBatteryAlarm = it)) }
            ToggleButtonRow("Full charge alert", settings.fullBatteryAlarm) { onChange(settings.copy(fullBatteryAlarm = it)) }
            ToggleButtonRow("High temperature alert", settings.temperatureAlarm) { onChange(settings.copy(temperatureAlarm = it)) }
        } }
        item { InfoCard("System") {
            OutlinedButton(onClick = onOpenBatteryUsage) { Text("Open system battery usage") }
            OutlinedButton(onClick = onOpenAppSettings) { Text("Open BatteryScope app settings") }
            Text("Use these shortcuts when the phone restricts background monitoring.", style = MaterialTheme.typography.bodySmall)
        } }
    }
}

@Composable
private fun SegmentedRow(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            if (option == selected) Button(onClick = { onSelect(option) }, Modifier.weight(1f)) { Text(option) }
            else OutlinedButton(onClick = { onSelect(option) }, Modifier.weight(1f)) { Text(option) }
        }
    }
}

@Composable
private fun ToggleButtonRow(title: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        OutlinedButton(onClick = { onToggle(!checked) }) { Text(if (checked) "ON" else "OFF") }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun HealthBar(percent: Double?) {
    val fraction = ((percent ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
    Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.fillMaxWidth(fraction).height(10.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.primary))
    }
}

@Composable
private fun SimpleLineChart(values: List<Double>, minValue: Double, maxValue: Double, label: String) {
    val data = values.takeLast(60)
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column {
        Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (data.size < 2) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Not enough data yet") }
            } else {
                Canvas(Modifier.fillMaxSize().padding(12.dp)) {
                    val range = (maxValue - minValue).coerceAtLeast(1.0)
                    for (i in 1..4) {
                        val y = size.height * i / 5f
                        drawLine(grid, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 1f)
                    }
                    val path = Path()
                    data.forEachIndexed { index, value ->
                        val x = index.toFloat() / data.lastIndex.coerceAtLeast(1) * size.width
                        val normalized = ((value - minValue) / range).coerceIn(0.0, 1.0).toFloat()
                        val y = size.height - normalized * size.height
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, primary, style = Stroke(width = 4f, cap = StrokeCap.Round))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(format0(minValue), style = MaterialTheme.typography.bodySmall)
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(format0(maxValue), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun regressionSlope(points: List<Pair<Double, Double>>): Double? {
    if (points.size < 2) return null
    val xMean = points.map { it.first }.average()
    val yMean = points.map { it.second }.average()
    val numerator = points.sumOf { (it.first - xMean) * (it.second - yMean) }
    val denominator = points.sumOf { (it.first - xMean) * (it.first - xMean) }
    return numerator.takeIf { denominator != 0.0 }?.div(denominator)
}

private fun formatCurrent(ma: Double?, unit: String): String {
    if (ma == null) return "Unavailable"
    return if (unit == "A") {
        val a = ma / 1000.0
        if (abs(a) < 0.1) "${"%.3f".format(a)} A" else "${"%.2f".format(a)} A"
    } else {
        if (abs(ma) < 10.0) "${"%.1f".format(ma)} mA" else "${"%.0f".format(ma)} mA"
    }
}

private fun formatPower(w: Double?, scalar: Float): String {
    if (w == null) return "Unavailable"
    val value = w * scalar
    return when {
        abs(value) < 0.01 -> "${"%.3f".format(value)} W"
        abs(value) < 0.1 -> "${"%.3f".format(value)} W"
        else -> "${"%.2f".format(value)} W"
    }
}

private fun formatTemp(c: Double, fahrenheit: Boolean): String = if (fahrenheit) "${"%.1f".format(c * 9.0 / 5.0 + 32.0)} °F" else "${"%.1f".format(c)} °C"
private fun formatCharge(counterMicroAh: Long?, unit: String): String {
    if (counterMicroAh == null) return "Unavailable"
    return formatChargeValue(counterMicroAh / 1000.0, unit)
}
private fun formatChargeValue(mah: Double, unit: String): String = if (unit == "Ah") "${"%.3f".format(mah / 1000.0)} Ah" else "${"%.0f".format(mah)} mAh"
private fun formatCapacity(mah: Double, unit: String): String = if (unit == "Ah") "${"%.2f".format(mah / 1000.0)} Ah" else "${"%.0f".format(mah)} mAh"
private fun formatEnergy(nWh: Long?): String {
    if (nWh == null) return "Unavailable"
    return "${"%.2f".format(nWh / 1_000_000_000.0)} Wh"
}
private fun formatDuration(ms: Long): String {
    val minutes = (ms / 60_000L).coerceAtLeast(0L)
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "$minutes min"
}
private fun format1(v: Double): String = "%.1f".format(v)
private fun format2(v: Double): String = "%.2f".format(v)
private fun format3(v: Double): String = "%.3f".format(v)
private fun format0(v: Double): String = "%.0f".format(v)
private fun formatDate(ts: Long): String = DateFormat.getDateTimeInstance().format(Date(ts))
