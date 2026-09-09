package com.batteryscope.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.batteryscope.app.settings.AppSettings
import com.batteryscope.app.settings.UiPreferences
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val preferences = remember(context) { UiPreferences(context) }
            var theme by remember { mutableStateOf(preferences.theme) }
            BatteryScopeTheme(theme) {
                BatteryScopeScreen(
                    onThemeChanged = {
                        theme = it
                        preferences.theme = it
                    },
                )
            }
        }
    }
}

@Composable
private fun BatteryScopeScreen(onThemeChanged: (UiPreferences.Theme) -> Unit) {
    val context = LocalContext.current
    val reader = remember(context) { BatteryReader(context) }
    val settings = remember(context) { AppSettings(context) }
    var battery by remember { mutableStateOf<BatterySnapshot?>(null) }
    var temperatureF by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var invertPolarity by remember { mutableStateOf(settings.invertChargingPolarity) }

    LaunchedEffect(reader) {
        while (true) {
            battery = reader.read()
            delay(1500L)
        }
    }

    Scaffold { padding ->
        Surface(
            modifier = Modifier.fillMaxSize().padding(padding),
            color = MaterialTheme.colorScheme.background,
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            ) {
                item {
                    Header(
                        charging = battery?.charging == true,
                        onSettings = { showSettings = !showSettings },
                    )
                }
                if (showSettings) {
                    item {
                        SettingsCard(
                            theme = rememberThemeValue(),
                            onThemeChanged = onThemeChanged,
                            invertPolarity = invertPolarity,
                            onInvertPolarity = {
                                invertPolarity = it
                                settings.invertChargingPolarity = it
                            },
                        )
                    }
                }
                val current = battery
                if (current == null) {
                    item { LoadingState() }
                } else {
                    item { HeroCard(current, temperatureF) }
                    items(metricList(current, temperatureF)) { metric -> MetricCard(metric) }
                    item {
                        Text(
                            "Health analysis uses completed full-charge sessions.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberThemeValue(): UiPreferences.Theme {
    val context = LocalContext.current
    val preferences = remember(context) { UiPreferences(context) }
    return preferences.theme
}

@Composable
private fun Header(charging: Boolean, onSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text("Live", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                if (charging) "Charging now" else "Running on battery",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onSettings) { Text("Settings") }
    }
}

@Composable
private fun HeroCard(battery: BatterySnapshot, temperatureF: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Charge level", style = MaterialTheme.typography.labelLarge)
                    Text("${battery.levelPercent}%", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(if (battery.charging) "Charging" else "Discharging", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(battery.powerW?.let { "${format1(it)} W" } ?: "—", style = MaterialTheme.typography.bodyLarge)
                }
            }
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(
                progress = { battery.levelPercent / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SmallValue("Current", battery.currentA?.let { "${format1(it)} A" } ?: "—")
                SmallValue("Voltage", battery.voltageV?.let { "${format1(it)} V" } ?: "—")
                SmallValue("Temp", battery.temperatureC?.let { temperatureText(it, temperatureF) } ?: "—")
            }
        }
    }
}

@Composable
private fun SmallValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SettingsCard(
    theme: UiPreferences.Theme,
    onThemeChanged: (UiPreferences.Theme) -> Unit,
    invertPolarity: Boolean,
    onInvertPolarity: (Boolean) -> Unit,
) {
    Card {
        Column(Modifier.padding(18.dp)) {
            Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("Theme", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UiPreferences.Theme.entries.forEach { option ->
                    if (theme == option) {
                        Button(onClick = { onThemeChanged(option) }, modifier = Modifier.weight(1f)) { Text(option.value) }
                    } else {
                        OutlinedButton(onClick = { onThemeChanged(option) }, modifier = Modifier.weight(1f)) { Text(option.value) }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Invert charging polarity", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text("Changes current sign only. Power keeps the same sign as current.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = invertPolarity, onCheckedChange = onInvertPolarity)
            }
        }
    }
}

private data class Metric(val title: String, val value: String, val subtitle: String)

private fun metricList(battery: BatterySnapshot, temperatureF: Boolean): List<Metric> = listOf(
    Metric("Power", battery.powerW?.let { "${format1(it)} W" } ?: "Unavailable", "Live output"),
    Metric("Current", battery.currentA?.let { "${format1(it)} A" } ?: "Unavailable", "Battery current"),
    Metric("Voltage", battery.voltageV?.let { "${format1(it)} V" } ?: "Unavailable", "Battery voltage"),
    Metric("Temperature", battery.temperatureC?.let { temperatureText(it, temperatureF) } ?: "Unavailable", "Battery temperature"),
    Metric("Energy", energyText(battery), "Wh / Ah"),
    Metric("Charge level", "${battery.levelPercent}%", "Battery level"),
    Metric("Charging status", if (battery.charging) "Yes" else "No", "Current state"),
    Metric("Battery capacity", battery.batteryCapacityMah?.let { "${format0(it)} mAh" } ?: "Unavailable", "Reference capacity"),
    Metric("Remaining battery", battery.remainingMah?.let { "${format0(it)} mAh" } ?: "Unavailable", "Charge counter"),
    Metric("Estimated capacity", battery.estimatedCapacityMah?.let { "${format0(it)} mAh" } ?: "Waiting for full-charge data", "Learned measurement"),
)

@Composable
private fun MetricCard(metric: Metric) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(metric.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(metric.value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(metric.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LoadingState() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Reading battery…", style = MaterialTheme.typography.titleMedium)
    }
}

private fun temperatureText(celsius: Double, fahrenheit: Boolean): String =
    if (fahrenheit) "${((celsius * 9.0 / 5.0) + 32.0).roundToInt()} °F" else "${format1(celsius)} °C"

private fun energyText(battery: BatterySnapshot): String {
    if (battery.energyWh == null || battery.remainingMah == null) return "Unavailable"
    return "${format2(battery.energyWh)} Wh / ${format3(battery.remainingMah / 1000.0)} Ah"
}

private fun format0(value: Double): String = String.format(Locale.US, "%.0f", value)
private fun format1(value: Double): String = String.format(Locale.US, "%.1f", value)
private fun format2(value: Double): String = String.format(Locale.US, "%.2f", value)
private fun format3(value: Double): String = String.format(Locale.US, "%.3f", value)
