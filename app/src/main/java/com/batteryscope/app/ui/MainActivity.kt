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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import com.batteryscope.app.settings.AppSettings
import com.batteryscope.app.settings.UiPreferences
import kotlinx.coroutines.delay
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val uiPreferences = remember(context) { UiPreferences(context) }
            val appSettings = remember(context) { AppSettings(context) }
            var theme by remember { mutableStateOf(uiPreferences.theme) }

            BatteryScopeTheme(theme) {
                BatteryScopeApp(
                    theme = theme,
                    onThemeChanged = {
                        theme = it
                        uiPreferences.theme = it
                    },
                    settings = appSettings,
                )
            }
        }
    }
}

@Composable
private fun BatteryScopeApp(
    theme: UiPreferences.Theme,
    onThemeChanged: (UiPreferences.Theme) -> Unit,
    settings: AppSettings,
) {
    var showSettings by remember { mutableStateOf(false) }

    if (showSettings) {
        SettingsScreen(
            theme = theme,
            onThemeChanged = onThemeChanged,
            settings = settings,
            onBack = { showSettings = false },
        )
    } else {
        LiveScreen(settings = settings, onSettings = { showSettings = true })
    }
}

@Composable
private fun LiveScreen(settings: AppSettings, onSettings: () -> Unit) {
    val context = LocalContext.current
    val reader = remember(context) { BatteryReader(context) }
    var battery by remember { mutableStateOf<BatterySnapshot?>(null) }

    LaunchedEffect(reader) {
        while (true) {
            battery = reader.read()
            delay(1200L)
        }
    }

    Scaffold { padding ->
        Surface(
            modifier = Modifier.fillMaxSize().padding(padding),
            color = MaterialTheme.colorScheme.background,
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(18.dp),
            ) {
                item { DashboardHeader(charging = battery?.charging == true, onSettings = onSettings) }
                val current = battery
                if (current == null) {
                    item { LoadingCard() }
                } else {
                    item { BatteryHero(current, settings) }
                    items(metricPairs(current, settings)) { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            MetricCard(pair.first, Modifier.weight(1f))
                            if (pair.second != null) MetricCard(pair.second!!, Modifier.weight(1f))
                            else Spacer(Modifier.weight(1f))
                        }
                    }
                    item {
                        Text(
                            "Health and learned capacity use completed full-charge sessions.",
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
private fun DashboardHeader(charging: Boolean, onSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("BatteryScope", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                if (charging) "Live telemetry • charging" else "Live telemetry • on battery",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onSettings) { Text("Settings") }
    }
}

@Composable
private fun BatteryHero(battery: BatterySnapshot, settings: AppSettings) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(22.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column {
                    Text("Battery", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "${battery.levelPercent}%",
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        if (battery.charging) "Charging" else "Discharging",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(battery.powerW?.let { "${format1(it)} W" } ?: "—", style = MaterialTheme.typography.titleLarge)
                }
            }
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = { battery.levelPercent / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                HeroValue("Current", battery.currentA?.let { formatCurrent(it, settings.currentUnit) } ?: "—")
                HeroValue("Voltage", battery.voltageV?.let { "${format1(it)} V" } ?: "—")
                HeroValue("Temp", battery.temperatureC?.let { formatTemperature(it, settings.temperatureUnit) } ?: "—")
            }
        }
    }
}

@Composable
private fun HeroValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

private data class Metric(val title: String, val value: String, val subtitle: String)

private fun metricPairs(battery: BatterySnapshot, settings: AppSettings): List<Pair<Metric, Metric?>> {
    val all = listOf(
        Metric("Power", battery.powerW?.let { "${format1(it)} W" } ?: "Unavailable", "Signed with current"),
        Metric("Current", battery.currentA?.let { formatCurrent(it, settings.currentUnit) } ?: "Unavailable", "Live battery current"),
        Metric("Voltage", battery.voltageV?.let { "${format1(it)} V" } ?: "Unavailable", "Battery voltage"),
        Metric("Temperature", battery.temperatureC?.let { formatTemperature(it, settings.temperatureUnit) } ?: "Unavailable", "Battery temperature"),
        Metric("Energy", energyText(battery, settings.chargeUnit), "Wh / selected charge unit"),
        Metric("Charge level", "${battery.levelPercent}%", "Battery level"),
        Metric("Charging", if (battery.charging) "Yes" else "No", "Current state"),
        Metric("Battery capacity", battery.batteryCapacityMah?.let { "${format0(it)} mAh" } ?: "Unavailable", "Reference capacity"),
        Metric("Remaining", battery.remainingMah?.let { "${format0(it)} mAh" } ?: "Unavailable", "Charge counter"),
        Metric("Estimated capacity", battery.estimatedCapacityMah?.let { "${format0(it)} mAh" } ?: "Waiting for full-charge data", "Learned from sessions"),
    )
    return all.chunked(2).map { it[0] to it.getOrNull(1) }
}

@Composable
private fun MetricCard(metric: Metric, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(metric.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(5.dp))
            Text(metric.value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(metric.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LoadingCard() {
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Reading battery telemetry…", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun SettingsScreen(
    theme: UiPreferences.Theme,
    onThemeChanged: (UiPreferences.Theme) -> Unit,
    settings: AppSettings,
    onBack: () -> Unit,
) {
    Scaffold { padding ->
        Surface(
            modifier = Modifier.fillMaxSize().padding(padding),
            color = MaterialTheme.colorScheme.background,
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(18.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onBack) { Text("Back") }
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text("Customize BatteryScope", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    SettingsSection("Appearance") {
                        SettingChoiceRow(
                            title = "Theme",
                            options = UiPreferences.Theme.entries.map { it.value },
                            selected = theme.value,
                            onSelected = { onThemeChanged(UiPreferences.Theme.fromValue(it)) },
                        )
                    }
                }
                item {
                    SettingsSection("Units") {
                        SettingChoiceRow("Current", AppSettings.CurrentUnit.entries.map { it.value }, settings.currentUnit.value) {
                            settings.currentUnit = AppSettings.CurrentUnit.fromValue(it)
                        }
                        Spacer(Modifier.height(12.dp))
                        SettingChoiceRow("Charge", AppSettings.ChargeUnit.entries.map { it.value }, settings.chargeUnit.value) {
                            settings.chargeUnit = AppSettings.ChargeUnit.fromValue(it)
                        }
                        Spacer(Modifier.height(12.dp))
                        SettingChoiceRow("Temperature", AppSettings.TemperatureUnit.entries.map { it.value }, settings.temperatureUnit.value) {
                            settings.temperatureUnit = AppSettings.TemperatureUnit.fromValue(it)
                        }
                    }
                }
                item {
                    SettingsSection("Telemetry") {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                                Text("Invert charging polarity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                                Text(
                                    "Charging current becomes positive and discharge negative when enabled. Power always follows the same sign.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = settings.invertChargingPolarity,
                                onCheckedChange = { settings.invertChargingPolarity = it },
                            )
                        }
                    }
                }
                item {
                    Text(
                        "More battery-health, capacity, session, display, and monitoring settings can be added here later.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable Column.() -> Unit) {
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun SettingChoiceRow(
    title: String,
    options: List<String>,
    selected: String,
    onSelected: (String) -> Unit,
) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(selected = selected == option, onClick = { onSelected(option) }, label = { Text(option) })
            }
        }
    }
}

private fun formatCurrent(value: Double, unit: AppSettings.CurrentUnit): String =
    if (unit == AppSettings.CurrentUnit.AMPERE) "${format1(value)} A" else "${format0(value * 1000.0)} mA"

private fun formatTemperature(value: Double, unit: AppSettings.TemperatureUnit): String =
    if (unit == AppSettings.TemperatureUnit.CELSIUS) "${format1(value)} °C" else "${format1((value * 9.0 / 5.0) + 32.0)} °F"

private fun energyText(battery: BatterySnapshot, unit: AppSettings.ChargeUnit): String {
    if (battery.energyWh == null || battery.remainingMah == null) return "Unavailable"
    val charge = if (unit == AppSettings.ChargeUnit.MILLIAMP_HOUR) "${format0(battery.remainingMah)} mAh" else "${format3(battery.remainingMah / 1000.0)} Ah"
    return "${format2(battery.energyWh)} Wh / $charge"
}

private fun format0(value: Double): String = String.format(Locale.US, "%.0f", value)
private fun format1(value: Double): String = String.format(Locale.US, "%.1f", value)
private fun format2(value: Double): String = String.format(Locale.US, "%.2f", value)
private fun format3(value: Double): String = String.format(Locale.US, "%.3f", value)
