package com.batteryscope.app

import android.os.BatteryManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BatteryScopeApp()
        }
    }
}

private data class BatterySnapshot(
    val level: Int,
    val temperatureC: Float,
    val voltageV: Float,
    val status: String,
    val technology: String,
    val currentMa: Int?
)

private fun readBatterySnapshot(activity: ComponentActivity): BatterySnapshot {
    val manager = activity.getSystemService(BATTERY_SERVICE) as BatteryManager
    val intent = activity.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
    val temp = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
    val voltage = (intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0) / 1000f
    val statusCode = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val status = when (statusCode) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
        BatteryManager.BATTERY_STATUS_FULL -> "Full"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
        else -> "Unknown"
    }
    val current = if (android.os.Build.VERSION.SDK_INT >= 21) {
        manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).takeIf { it != Int.MIN_VALUE }?.let { it / 1000 }
    } else null
    return BatterySnapshot(
        level = level,
        temperatureC = temp,
        voltageV = voltage,
        status = status,
        technology = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "Unknown",
        currentMa = current
    )
}

@Composable
private fun BatteryScopeApp() {
    val activity = androidx.compose.ui.platform.LocalContext.current as ComponentActivity
    val battery = remember { readBatterySnapshot(activity) }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("BatteryScope", style = MaterialTheme.typography.headlineMedium)
                Text("Realme 9 5G Speed Edition", style = MaterialTheme.typography.bodyMedium)

                Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Text("Battery level", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(4.dp))
                        Text("${battery.level}%", style = MaterialTheme.typography.displayMedium)
                        Text(battery.status, style = MaterialTheme.typography.bodyLarge)
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetricCard("Temperature", "%.1f °C".format(battery.temperatureC), Modifier.weight(1f))
                    MetricCard("Voltage", "%.3f V".format(battery.voltageV), Modifier.weight(1f))
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetricCard("Current", battery.currentMa?.let { "${it} mA" } ?: "Unavailable", Modifier.weight(1f))
                    MetricCard("Technology", battery.technology, Modifier.weight(1f))
                }

                Text(
                    "Capacity and health estimates will be added after the live dashboard is verified.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}
