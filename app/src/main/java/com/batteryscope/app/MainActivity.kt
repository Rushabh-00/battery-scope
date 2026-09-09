package com.batteryscope.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.text.SimpleDateFormat
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
        var battery by remember { mutableStateOf(readBattery(this)) }
        var settings by remember { mutableStateOf(store.settings()) }
        var tab by remember { mutableIntStateOf(0) }
        var monitoring by remember { mutableStateOf(false) }

        LaunchedEffect(settings.updateIntervalSeconds) {
            while (true) {
                battery = readBattery(this@MainActivity)
                delay(settings.updateIntervalSeconds * 1000L)
            }
        }

        val sessions = store.sessions()
        val samples = store.samples()
        val health = estimateHealth(sessions)

        MaterialTheme {
            Scaffold(
                topBar = {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("BatteryScope", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("Realme 9 5G Speed Edition", style = MaterialTheme.typography.bodyMedium)
                        }
                        OutlinedButton(onClick = { tab = 3 }) { Text("Settings") }
                    }
                },
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("⌁") }, label = { Text("Charging") })
                        NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("↓") }, label = { Text("Discharging") })
                        NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Text("♥") }, label = { Text("Health") })
                        NavigationBarItem(selected = tab == 4, onClick = { tab = 4 }, icon = { Text("◷") }, label = { Text("History") })
                    }
                }
            ) { padding ->
                Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
                    when (tab) {
                        0 -> ChargingScreen(battery, health, settings, monitoring, { monitoring = true; startMonitoring() }, { monitoring = false; stopMonitoring() })
                        1 -> DischargingScreen(battery, settings, monitoring, { monitoring = true; startMonitoring() }, { monitoring = false; stopMonitoring() })
                        2 -> HealthScreen(health, sessions)
                        3 -> SettingsScreen(settings) { next -> settings = next; store.saveSettings(next) }
                        else -> HistoryScreen(samples, settings)
                    }
                }
            }
        }
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
    LazyColumn(modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { StatusCard(battery, settings, "Battery level") }
        item { MetricsRow(settings.showPower) { if (settings.showPower) MetricCard("Power", formatPower(battery.powerW), Modifier.weight(1f)) }; if (settings.showCurrent) MetricCard("Current", formatCurrent(battery.currentMa, settings.currentUnit), Modifier.weight(1f)) } }
        item { MetricsRow(settings.showVoltage) { if (settings.showVoltage) MetricCard("Voltage", "${format3(battery.voltageV)} V", Modifier.weight(1f)); if (settings.showTemperature) MetricCard("Temperature", formatTemp(battery.temperatureC, settings.temperatureF), Modifier.weight(1f)) } }
        item { MetricsRow(settings.showRemainingCharge) { if (settings.showRemainingCharge) MetricCard("Remaining charge", formatCharge(battery.counterMicroAh, settings.chargeUnit), Modifier.weight(1f)); if (settings.showEnergy) MetricCard("Energy remaining", formatEnergy(battery.energyCounterNWh, settings.energyUnit), Modifier.weight(1f)) } }
        item { CapacityCard(battery, health, settings) }
        item { MonitoringCard(monitoring, onStart, onStop) }
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("Charge time estimate", style = MaterialTheme.typography.titleMedium)
                    Text(if (settings.showChargeTime && battery.chargeTimeRemainingMs != null) formatDuration(battery.chargeTimeRemainingMs) else "Unavailable from Android", style = MaterialTheme.typography.headlineSmall)
                    Text("Time remaining until the operating system expects a full charge.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
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
    LazyColumn(modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { StatusCard(battery, settings, "Charge level") }
        item { Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { MetricCard("Power", formatSignedPower(battery.powerW), Modifier.weight(1f)); MetricCard("Current", formatCurrent(battery.currentMa, settings.currentUnit), Modifier.weight(1f)) } }
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Discharge summary", style = MaterialTheme.typography.titleMedium)
                    Text("Average draw", style = MaterialTheme.typography.labelMedium)
                    Text(formatCurrent(battery.averageCurrentMa, settings.currentUnit), style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text("Power now: ${formatPower(battery.powerW)}")
                    Text("Remaining charge: ${formatCharge(battery.counterMicroAh, settings.chargeUnit)}")
                    Text("Energy remaining: ${formatEnergy(battery.energyCounterNWh, settings.energyUnit)}")
                }
            }
        }
        item { MonitoringCard(monitoring, onStart, onStop) }
        item { Text("Negative current and power mean energy is leaving the battery.", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun MetricsRow(showFirst: Boolean, content: @Composable RowScope.() -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
private fun StatusCard(battery: BatterySnapshot, settings: UiSettings, title: String) {
    Card(shape = RoundedCornerShape(30.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(26.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text("${battery.level}%", style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
            Text(battery.status, style = MaterialTheme.typography.titleLarge)
            if (settings.showTemperature) Text("Temperature: ${formatTemp(battery.temperatureC, settings.temperatureF)} • ${classifyTemperature(battery.temperatureC)}")
            Spacer(Modifier.height(8.dp))
            Text("Technology: ${battery.technology}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CapacityCard(battery: BatterySnapshot, health: HealthEstimate, settings: UiSettings) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Battery capacity estimate", style = MaterialTheme.typography.titleMedium)
            Text(health.capacityMah?.let { formatCapacity(it, settings.chargeUnit) } ?: "Collecting usable charge data", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("Estimated full-charge capacity from measured charging current and percentage gained.")
            Spacer(Modifier.height(8.dp))
            Text("Design reference: ${formatCapacity(DESIGN_CAPACITY_MAH, settings.chargeUnit)}")
            Text("Remaining charge: ${formatCharge(battery.counterMicroAh, settings.chargeUnit)}", style = MaterialTheme.typography.bodySmall)
            health.healthPercent?.let { Text("Battery health: ${format1(it)}%", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun MonitoringCard(monitoring: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Background monitoring", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(if (monitoring) "Running • samples are being collected in the background." else "Off • start monitoring to continue collecting while the app is closed.")
            Spacer(Modifier.height(10.dp))
            if (monitoring) OutlinedButton(onClick = onStop) { Text("Stop monitoring") } else Button(onClick = onStart) { Text("Start monitoring") }
        }
    }
}

@Composable
private fun HealthScreen(health: HealthEstimate, sessions: List<ChargeSession>) {
    val recent = sessions.filter { it.endLevel - it.startLevel >= 60 }.takeLast(5)
    val avgWear = recent.map { it.wearCycles }.average().takeIf { !it.isNaN() }
    val avgEfficiency = recent.map { it.efficiencyPercent }.average().takeIf { !it.isNaN() }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp)) {
                Column(modifier = Modifier.padding(22.dp)) {
                    Text("Battery health", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(health.healthPercent?.let { "${format1(it)}%" } ?: "No full estimate yet", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
                    Text(health.capacityMah?.let { "Estimated capacity  ${format0(it)} mAh" } ?: health.source)
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        Box(Modifier.fillMaxWidth(((health.healthPercent ?: 0.0).coerceIn(0.0, 100.0) / 100.0).toFloat()).height(10.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Confidence ${health.confidencePercent}% • ${health.completedSessions} usable session(s)")
                }
            }
        }
        item { Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { MetricCard("Estimated capacity", health.capacityMah?.let { "${format0(it)} mAh" } ?: "—", Modifier.weight(1f)); MetricCard("Design capacity", "${format0(DESIGN_CAPACITY_MAH)} mAh", Modifier.weight(1f)) } }
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("Battery capacity over time", style = MaterialTheme.typography.titleMedium)
                    Text("Moving average based on the latest five usable charge sessions.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    SimpleLineChart(recent.map { it.estimatedCapacityMah }, DESIGN_CAPACITY_MAH * 0.5, DESIGN_CAPACITY_MAH * 1.05, "mAh")
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("Battery wear", style = MaterialTheme.typography.titleMedium)
                    Text("Modeled cycle cost based on charge voltage and session depth.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    SimpleLineChart(recent.map { it.wearCycles }, 0.0, maxOf(2.0, recent.maxOfOrNull { it.wearCycles } ?: 2.0), "cycles")
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { MetricCard("Recent wear", avgWear?.let { "${format2(it)} cycles" } ?: "—", Modifier.weight(1f)); MetricCard("Efficiency", avgEfficiency?.let { "${format0(it)}%" } ?: "—", Modifier.weight(1f)) }
                }
            }
        }
        item { Text("Charge sessions", style = MaterialTheme.typography.titleMedium) }
        items(sessions.asReversed()) { session ->
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("${session.startLevel}% → ${session.endLevel}%", fontWeight = FontWeight.SemiBold)
                    Text("Charged ${format0(session.chargedMah)} mAh • Estimate ${format0(session.estimatedCapacityMah)} mAh")
                    Text("Wear ${format2(session.wearCycles)} cycles • Efficiency ${format0(session.efficiencyPercent)}%")
                    Text("Peak ${format3(session.endVoltageV)} V • ${formatDate(session.endTime)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (sessions.isEmpty()) item { Text("No usable charge sessions yet. Keep monitoring while charging.") }
    }
}

@Composable
private fun SimpleLineChart(values: List<Double>, minValue: Double, maxValue: Double, label: String) {
    Column {
        Canvas(modifier = Modifier.fillMaxWidth().height(150.dp)) {
            val left = 8f; val right = size.width - 8f; val top = 10f; val bottom = size.height - 14f
            val range = (maxValue - minValue).coerceAtLeast(1.0)
            val gridPaint = Paint().apply { color = android.graphics.Color.LTGRAY; strokeWidth = 1f }
            for (i in 0..4) {
                val y = top + (bottom - top) * i / 4f
                drawContext.canvas.nativeCanvas.drawLine(left, y, right, y, gridPaint)
            }
            if (values.size >= 2) {
                val path = Path()
                values.forEachIndexed { index, value ->
                    val x = left + (right - left) * index / (values.size - 1).coerceAtLeast(1)
                    val normalized = ((value - minValue) / range).coerceIn(0.0, 1.0)
                    val y = bottom - (bottom - top) * normalized.toFloat()
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color = MaterialTheme.colorScheme.primary, style = Stroke(width = 4f))
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(format0(minValue), style = MaterialTheme.typography.bodySmall); Text(label, style = MaterialTheme.typography.bodySmall); Text(format0(maxValue), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun HistoryScreen(samples: List<HistorySample>, settings: UiSettings) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("History", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("Locally stored telemetry. Up to 1,000 samples and 50 charge sessions.") }
        item { HorizontalDivider() }
        items(samples.asReversed().take(150)) { sample ->
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Text("${sample.level}% • ${formatTemp(sample.temperatureC, settings.temperatureF)}"); Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(sample.timestamp)), style = MaterialTheme.typography.bodySmall) }
                    Column(horizontalAlignment = Alignment.End) { Text("${format3(sample.voltageV)} V"); Text(formatCurrent(sample.currentMa, settings.currentUnit), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        if (samples.isEmpty()) item { Text("No history yet. Start monitoring to collect samples.") }
    }
}

@Composable
private fun SettingsScreen(settings: UiSettings, onChange: (UiSettings) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("Choose the units and information BatteryScope shows. Default current is A.") }
        item { UnitSection("Current", listOf("A", "mA"), settings.currentUnit) { onChange(settings.copy(currentUnit = it)) } }
        item { UnitSection("Charge", listOf("Ah", "mAh"), settings.chargeUnit) { onChange(settings.copy(chargeUnit = it)) } }
        item { UnitSection("Energy", listOf("Wh", "kWh"), settings.energyUnit) { onChange(settings.copy(energyUnit = it)) } }
        item { UnitSection("Temperature", listOf("°C", "°F"), if (settings.temperatureF) "°F" else "°C") { onChange(settings.copy(temperatureF = it == "°F")) } }
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("Visible telemetry", style = MaterialTheme.typography.titleMedium)
                    ToggleLine("Power (W)", settings.showPower) { onChange(settings.copy(showPower = it)) }
                    ToggleLine("Current", settings.showCurrent) { onChange(settings.copy(showCurrent = it)) }
                    ToggleLine("Voltage (V)", settings.showVoltage) { onChange(settings.copy(showVoltage = it)) }
                    ToggleLine("Temperature", settings.showTemperature) { onChange(settings.copy(showTemperature = it)) }
                    ToggleLine("Remaining charge", settings.showRemainingCharge) { onChange(settings.copy(showRemainingCharge = it)) }
                    ToggleLine("Energy remaining", settings.showEnergy) { onChange(settings.copy(showEnergy = it)) }
                    ToggleLine("Charge time", settings.showChargeTime) { onChange(settings.copy(showChargeTime = it)) }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("Update interval", style = MaterialTheme.typography.titleMedium)
                    Text("${settings.updateIntervalSeconds}s")
                    Slider(value = settings.updateIntervalSeconds.toFloat(), onValueChange = { onChange(settings.copy(updateIntervalSeconds = it.toInt().coerceIn(1, 10))) }, valueRange = 1f..10f, steps = 8)
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("Health model", style = MaterialTheme.typography.titleMedium)
                    Text("Design reference: ${format0(DESIGN_CAPACITY_MAH)} mAh")
                    Text("Health = estimated full-charge capacity ÷ design reference × 100.", style = MaterialTheme.typography.bodySmall)
                    Text("The reference capacity is configurable model data, not a factory-measured Android value.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun UnitSection(title: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { value ->
                    if (value == selected) Button(onClick = { onSelect(value) }, modifier = Modifier.weight(1f)) { Text(value) }
                    else OutlinedButton(onClick = { onSelect(value) }, modifier = Modifier.weight(1f)) { Text(value) }
                }
            }
        }
    }
}

@Composable
private fun ToggleLine(title: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title)
        OutlinedButton(onClick = { onToggle(!checked) }) { Text(if (checked) "ON" else "OFF") }
    }
}

@Composable
private fun MetricCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(20.dp)) { Column(modifier = Modifier.padding(16.dp)) { Text(title, style = MaterialTheme.typography.labelMedium); Spacer(Modifier.height(6.dp)); Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) } }
}

private fun formatCurrent(ma: Double?, unit: String): String {
    if (ma == null) return "Unavailable"
    return if (unit == "A") {
        val a = ma / 1000.0
        if (abs(a) < 0.1) "${"%.3f".format(a)} A" else "${"%.2f".format(a)} A"
    } else if (abs(ma) < 10.0) "${"%.1f".format(ma)} mA" else "${"%.0f".format(ma)} mA"
}

private fun formatPower(w: Double?): String {
    if (w == null) return "Unavailable"
    if (abs(w) < 0.01) return "${"%.3f".format(w)} W"
    return if (abs(w) < 0.1) "${"%.3f".format(w)} W" else "${"%.2f".format(w)} W"
}

private fun formatSignedPower(w: Double?): String = formatPower(w)
private fun formatTemp(c: Double, fahrenheit: Boolean): String = if (fahrenheit) "${"%.1f".format(c * 9.0 / 5.0 + 32.0)} °F" else "${"%.1f".format(c)} °C"
private fun formatCharge(microAh: Long?, unit: String): String {
    if (microAh == null) return "Unavailable"
    val mah = microAh / 1000.0
    return if (unit == "Ah") "${"%.3f".format(mah / 1000.0)} Ah" else "${"%.0f".format(mah)} mAh"
}
private fun formatCapacity(mah: Double, unit: String): String = if (unit == "Ah") "${"%.2f".format(mah / 1000.0)} Ah" else "${"%.0f".format(mah)} mAh"
private fun formatEnergy(nWh: Long?, unit: String): String {
    if (nWh == null) return "Unavailable"
    val wh = nWh / 1_000_000_000.0
    return if (unit == "kWh") "${"%.3f".format(wh / 1000.0)} kWh" else "${"%.2f".format(wh)} Wh"
}
private fun formatDuration(ms: Long): String { val minutes = ms / 60_000L; return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m remaining" else "$minutes min remaining" }
private fun format1(v: Double): String = "%.1f".format(v)
private fun format2(v: Double): String = "%.2f".format(v)
private fun format3(v: Double): String = "%.3f".format(v)
private fun format0(v: Double): String = "%.0f".format(v)
private fun formatDate(ts: Long): String = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(ts))
