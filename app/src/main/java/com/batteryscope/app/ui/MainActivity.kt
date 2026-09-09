package com.batteryscope.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.batteryscope.app.battery.BatteryReader
import com.batteryscope.app.battery.BatterySnapshot
import com.batteryscope.app.battery.CapacityPreferences
import com.batteryscope.app.settings.AppSettings
import com.batteryscope.app.settings.UiPreferences
import kotlinx.coroutines.delay
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
            val uiPrefs = remember(context) { UiPreferences(context) }
            val settings = remember(context) { AppSettings(context) }
            var theme by remember { mutableStateOf(uiPrefs.theme) }
            BatteryScopeTheme(theme) {
                BatteryScopeApp(
                    theme = theme,
                    settings = settings,
                    onThemeChanged = { theme = it; uiPrefs.theme = it },
                )
            }
        }
    }
}

@Composable
private fun BatteryScopeApp(
    theme: UiPreferences.Theme,
    settings: AppSettings,
    onThemeChanged: (UiPreferences.Theme) -> Unit,
) {
    var screen by remember { mutableStateOf(AppScreen.LIVE) }
    when (screen) {
        AppScreen.LIVE -> LiveScreen(settings) { screen = AppScreen.SETTINGS }
        AppScreen.SETTINGS -> SettingsScreen(theme, onThemeChanged, settings) { screen = AppScreen.LIVE }
    }
}

private enum class AppScreen { LIVE, SETTINGS }

@Composable
private fun LiveScreen(settings: AppSettings, onSettings: () -> Unit) {
    val context = LocalContext.current
    val reader = remember(context) { BatteryReader(context) }
    var battery by remember { mutableStateOf<BatterySnapshot?>(null) }
    val updateIntervalMs = settings.updateIntervalMs

    LaunchedEffect(reader, updateIntervalMs) {
        while (true) {
            battery = reader.read()
            delay(updateIntervalMs)
        }
    }

    Scaffold { padding ->
        Surface(Modifier.fillMaxSize().padding(padding), color = MaterialTheme.colorScheme.background) {
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(18.dp),
            ) {
                item { Header(battery?.charging == true, onSettings) }
                val value = battery
                if (value == null) item { LoadingCard() }
                else {
                    item { Hero(value, settings) }
                    items(metrics(value, settings)) { pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            MetricCard(pair.first, Modifier.weight(1f))
                            pair.second?.let { MetricCard(it, Modifier.weight(1f)) } ?: Spacer(Modifier.weight(1f))
                        }
                    }
                    value.sessionAnalysis?.let { analysis ->
                        item { CapacityCard(value, analysis) }
                        item { FlowCard(analysis) }
                        item { HistoryCard(analysis) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(charging: Boolean, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("BatteryScope", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(if (charging) "Live telemetry • charging" else "Live telemetry • on battery", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = onSettings) { Text("Settings") }
    }
}

@Composable
private fun Hero(battery: BatterySnapshot, settings: AppSettings) {
    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(22.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Column {
                    Text("Battery", style = MaterialTheme.typography.labelLarge)
                    Text("${battery.levelPercent}%", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.End) {
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
}

@Composable
private fun Mini(label: String, value: String) = Column {
    Text(label, style = MaterialTheme.typography.labelMedium)
    Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

private data class Metric(val title: String, val value: String, val detail: String)
private fun metrics(b: BatterySnapshot, s: AppSettings): List<Pair<Metric, Metric?>> {
    val all = listOf(
        Metric("Power", b.powerW?.let { "${f1(it)} W" } ?: "Unavailable", "Same sign as current"),
        Metric("Current", b.currentA?.let { currentText(it, s.currentUnit) } ?: "Unavailable", "Live battery current"),
        Metric("Voltage", b.voltageV?.let { "${f1(it)} V" } ?: "Unavailable", "Battery voltage"),
        Metric("Temperature", b.temperatureC?.let { tempText(it, s.temperatureUnit) } ?: "Unavailable", "Battery temperature"),
        Metric("Energy", energyText(b), "Wh / Ah"),
        Metric("Charge level", "${b.levelPercent}%", "Battery level"),
        Metric("Charging", if (b.charging) "Yes" else "No", "Current state"),
        Metric("Design capacity", b.batteryCapacityMah?.let { "${f0(it)} mAh" } ?: "Unavailable", "Design capacity"),
        Metric("Remaining", b.remainingMah?.let { "${f0(it)} mAh" } ?: "Unavailable", "Charge counter"),
        Metric("Estimated capacity", b.estimatedCapacityMah?.let { "${f0(it)} mAh" } ?: "Awaiting completed session", "Session learned only"),
    )
    return all.chunked(2).map { it[0] to it.getOrNull(1) }
}

@Composable
private fun MetricCard(m: Metric, modifier: Modifier = Modifier) = Card(modifier, shape = RoundedCornerShape(22.dp)) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(m.title, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(m.value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(m.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CapacityCard(b: BatterySnapshot, a: com.batteryscope.app.battery.BatterySessionAnalyzer.Analysis) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text("Capacity & health", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Stat("Design capacity", b.batteryCapacityMah?.let { "${f0(it)} mAh" } ?: "Not detected / not set")
            Stat("Learned capacity", a.learnedCapacityMah?.let { "${f0(it)} mAh" } ?: "Awaiting completed session")
            Stat("Latest full-charge", a.latestSessionCapacityMah?.let { "${f0(it)} mAh" } ?: "—")
            Stat("Battery health", a.healthPercent?.let { "${f0(it)}%" } ?: "Awaiting completed session")
            Stat("Wear", a.wearMah?.let { "${f0(it)} mAh" } ?: "—")
            Text("A session is valid only after the battery has been sampled at 15% or lower while discharging, then charged to full. Health averages up to five completed sessions.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FlowCard(a: com.batteryscope.app.battery.BatterySessionAnalyzer.Analysis) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text("Charge & discharge", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Flow("Charge", "${f0(a.chargeMah)} mAh", duration(a.chargeTimeMs), Modifier.weight(1f))
                Flow("Discharge", "${f0(a.dischargeMah)} mAh", duration(a.dischargeTimeMs), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Flow(title: String, amount: String, time: String, modifier: Modifier) = Card(modifier) {
    Column(Modifier.padding(14.dp)) {
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(amount, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HistoryCard(a: com.batteryscope.app.battery.BatterySessionAnalyzer.Analysis) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text("Full-charge session history", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (a.fullChargeSessions.isEmpty()) Text("No completed sessions yet. Start at 15% or lower, then charge to full.")
            else a.fullChargeSessions.takeLast(5).reversed().forEachIndexed { i, session ->
                if (i > 0) Spacer(Modifier.height(10.dp))
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${f0(session.estimatedCapacityMah)} mAh", fontWeight = FontWeight.SemiBold)
                        Text(date(session.completedAtMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${f0(session.chargedMah)} mAh charged • ${duration(session.durationMs)} • started ${session.startLevelPercent}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(theme: UiPreferences.Theme, onThemeChanged: (UiPreferences.Theme) -> Unit, settings: AppSettings, onBack: () -> Unit) {
    val context = LocalContext.current
    var currentUnit by remember { mutableStateOf(settings.currentUnit) }
    var tempUnit by remember { mutableStateOf(settings.temperatureUnit) }
    var invert by remember { mutableStateOf(settings.invertChargingPolarity) }
    var updateIntervalMs by remember { mutableStateOf(settings.updateIntervalMs) }
    var capacity by remember { mutableStateOf(CapacityPreferences(context).designCapacityMah?.let(::f0) ?: "") }
    var message by remember { mutableStateOf("") }

    Scaffold { padding ->
        Surface(Modifier.fillMaxSize().padding(padding), color = MaterialTheme.colorScheme.background) {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(18.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onBack) { Text("Back") }
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text("Customize BatteryScope", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item { Section("Appearance") { Choice("Theme", UiPreferences.Theme.entries.map { it.value }, theme.value) { onThemeChanged(UiPreferences.Theme.fromValue(it)) } } }
                item { Section("Units") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Choice("Current", AppSettings.CurrentUnit.entries.map { it.value }, currentUnit.value) { currentUnit = AppSettings.CurrentUnit.fromValue(it); settings.currentUnit = currentUnit }
                        }
                        Column(Modifier.weight(1f)) {
                            Choice("Temperature", AppSettings.TemperatureUnit.entries.map { it.value }, tempUnit.value) { tempUnit = AppSettings.TemperatureUnit.fromValue(it); settings.temperatureUnit = tempUnit }
                        }
                    }
                } }
                item { Section("Battery capacity") {
                    Text("Design capacity", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = capacity, onValueChange = { capacity = it; message = "" }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Battery capacity (mAh)") }, placeholder = { Text("Example: 4500") })
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val value = capacity.toDoubleOrNull()
                            if (value != null && value in 100.0..30_000.0) { CapacityPreferences(context).designCapacityMah = value; message = "Saved" }
                            else message = "Enter 100–30,000 mAh"
                        }) { Text("Save capacity") }
                        OutlinedButton(onClick = { CapacityPreferences(context).designCapacityMah = null; capacity = ""; message = "Cleared" }) { Text("Clear") }
                    }
                    if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("This design capacity is editable and is used only for health/wear. Learned capacity is never shown until a valid full-charge session completes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                item { Section("Telemetry") {
                    Text("Update interval", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    Text("How often BatteryScope refreshes live battery readings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text(formatInterval(updateIntervalMs), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Slider(
                        value = updateIntervalMs.toFloat(),
                        onValueChange = { value ->
                            val rounded = (value / 250f).roundToInt() * 250L
                            updateIntervalMs = rounded.coerceIn(1_250L, 10_000L)
                            settings.updateIntervalMs = updateIntervalMs
                        },
                        valueRange = 1_250f..10_000f,
                        steps = 34,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("1.25 s", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("10 s", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 16.dp)) {
                            Text("Invert charging polarity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                            Text("Changes current sign live. Power always follows current sign.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = invert, onCheckedChange = { invert = it; settings.invertChargingPolarity = it })
                    }
                } }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable Column.() -> Unit) = Card(shape = RoundedCornerShape(22.dp)) {
    Column(Modifier.fillMaxWidth().padding(18.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(14.dp))
        content()
    }
}

@Composable
private fun Choice(title: String, options: List<String>, selected: String, onSelected: (String) -> Unit) {
    Column {
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { FilterChip(selected = selected == it, onClick = { onSelected(it) }, label = { Text(it) }) }
        }
    }
}

@Composable
private fun Stat(title: String, value: String) = Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, fontWeight = FontWeight.Medium)
}

@Composable
private fun LoadingCard() = Card(shape = RoundedCornerShape(22.dp)) {
    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text("Reading battery telemetry…") }
}

private fun currentText(v: Double, u: AppSettings.CurrentUnit) = if (u == AppSettings.CurrentUnit.AMPERE) "${f1(v)} A" else "${f0(v * 1000)} mA"
private fun tempText(v: Double, u: AppSettings.TemperatureUnit) = if (u == AppSettings.TemperatureUnit.CELSIUS) "${f1(v)} °C" else "${f1(v * 9 / 5 + 32)} °F"
private fun energyText(b: BatterySnapshot): String {
    if (b.energyWh == null || b.remainingMah == null) return "Unavailable"
    return "${f2(b.energyWh)} Wh / ${f3(b.remainingMah / 1000)} Ah"
}
private fun formatInterval(ms: Long) = if (ms % 1000L == 0L) "${ms / 1000L} s" else String.format(Locale.US, "%.2f s", ms / 1000f)
private fun duration(ms: Long): String {
    if (ms <= 0) return "0 min"
    val mins = ms / 60000
    val h = mins / 60
    val m = mins % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
private fun date(ms: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))
private fun f0(v: Double) = String.format(Locale.US, "%.0f", v)
private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
private fun f2(v: Double) = String.format(Locale.US, "%.2f", v)
private fun f3(v: Double) = String.format(Locale.US, "%.3f", v)
