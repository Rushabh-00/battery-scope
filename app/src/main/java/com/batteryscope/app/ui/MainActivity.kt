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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.batteryscope.app.battery.BatteryReader
import com.batteryscope.app.battery.BatterySnapshot
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BatteryScopeScreen() }
    }
}

@Composable
private fun BatteryScopeScreen() {
    val context = LocalContext.current
    val reader = remember(context) { BatteryReader(context) }
    var battery by remember { mutableStateOf<BatterySnapshot?>(null) }
    var temperatureF by remember { mutableStateOf(false) }

    LaunchedEffect(reader) {
        while (true) {
            battery = reader.read()
            delay(1500L)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("BatteryScope", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                },
                actions = {
                    TextButton(onClick = { temperatureF = !temperatureF }) {
                        Text(if (temperatureF) "°F" else "°C")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Surface(Modifier.fillMaxSize().padding(padding), color = MaterialTheme.colorScheme.background) {
            val current = battery
            if (current == null) {
                LoadingState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { BatterySummaryCard(current, temperatureF) }
                    items(
                        listOf(
                            Metric("Power", current.powerW?.let { "${format1(it)} W" } ?: "Unavailable", "Live output"),
                            Metric("Current", current.currentA?.let { "${format1(it)} A" } ?: "Unavailable", "Battery current"),
                            Metric("Voltage", current.voltageV?.let { "${format1(it)} V" } ?: "Unavailable", "Battery voltage"),
                            Metric("Temperature", current.temperatureC?.let { temperatureText(it, temperatureF) } ?: "Unavailable", "Battery temperature"),
                            Metric("Energy", energyText(current), "Wh / Ah"),
                            Metric("Charge level", "${current.levelPercent}%", "Battery level"),
                            Metric("Charging", if (current.charging) "Yes" else "No", "Current state"),
                            Metric("Battery capacity", current.batteryCapacityMah?.let { "${format0(it)} mAh" } ?: "Unavailable", "Reference capacity"),
                            Metric("Remaining battery", current.remainingMah?.let { "${format0(it)} mAh" } ?: "Unavailable", "Charge counter"),
                            Metric("Estimated capacity", current.estimatedCapacityMah?.let { "${format0(it)} mAh" } ?: "Waiting for full-charge data", "Learned from measurements"),
                        )
                    ) { metric -> MetricCard(metric) }
                    item {
                        Spacer(Modifier.height(4.dp))
                        Text("Battery health will be calculated from completed full-charge sessions.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private data class Metric(val title: String, val value: String, val subtitle: String)

@Composable
private fun BatterySummaryCard(battery: BatterySnapshot, temperatureF: Boolean) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Charge level", style = MaterialTheme.typography.labelLarge)
                    Text("${battery.levelPercent}%", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(if (battery.charging) "Charging" else "Discharging", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(battery.currentA?.let { "${format1(it)} A" } ?: "—", style = MaterialTheme.typography.bodyLarge)
                }
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SummaryValue("Power", battery.powerW?.let { "${format1(it)} W" } ?: "—")
                SummaryValue("Voltage", battery.voltageV?.let { "${format1(it)} V" } ?: "—")
                SummaryValue("Temp", battery.temperatureC?.let { temperatureText(it, temperatureF) } ?: "—")
            }
        }
    }
}

@Composable
private fun SummaryValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MetricCard(metric: Metric) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text(metric.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(4.dp))
            Text(metric.value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(2.dp))
            Text(metric.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LoadingState() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
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
