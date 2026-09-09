package com.batteryscope.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

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
        var tab by remember { mutableIntStateOf(0) }
        var monitoring by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            while (true) {
                battery = readBattery(this@MainActivity)
                kotlinx.coroutines.delay(2_000)
            }
        }

        val sessions = store.sessions()
        val health = estimateHealth(sessions)
        val samples = store.samples()

        MaterialTheme {
            Scaffold(
                topBar = {
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                        Text("BatteryScope", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("Realme 9 5G Speed Edition", style = MaterialTheme.typography.bodyMedium)
                    }
                },
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("⌂") }, label = { Text("Dashboard") })
                        NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("♥") }, label = { Text("Health") })
                        NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Text("≋") }, label = { Text("History") })
                    }
                }
            ) { padding ->
                Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
                    when (tab) {
                        0 -> Dashboard(battery, monitoring, { monitoring = true; startMonitoring() }, { monitoring = false; stopMonitoring() })
                        1 -> HealthScreen(health, sessions)
                        else -> HistoryScreen(samples)
                    }
                }
            }
        }
    }
}

@Composable
private fun Dashboard(battery: BatterySnapshot, monitoring: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Card(shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("Battery level", style = MaterialTheme.typography.labelLarge)
                    Text("${battery.level}%", style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
                    Text(battery.status, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("Temperature: ${"%.1f".format(battery.temperatureC)} °C • ${classifyTemperature(battery.temperatureC)}")
                }
            }
        }
        item { Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("Voltage", "${"%.3f".format(battery.voltageV)} V", Modifier.weight(1f))
            MetricCard("Current", battery.currentMa?.let { "${"%.0f".format(it)} mA" } ?: "Unavailable", Modifier.weight(1f))
        } }
        item { Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("Power", battery.powerW?.let { "${"%.2f".format(it)} W" } ?: "Unavailable", Modifier.weight(1f))
            MetricCard("Technology", battery.technology, Modifier.weight(1f))
        } }
        item { Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("Battery capacity", style = MaterialTheme.typography.labelLarge)
                Text("5,000 mAh", style = MaterialTheme.typography.headlineSmall)
                Text("Configured design capacity used as the reference for health. It is not a factory-measured value from Android.")
                battery.counterMicroAh?.let {
                    Text("Android charge counter: ${"%.0f".format(it / 1000.0)} mAh", style = MaterialTheme.typography.bodySmall)
                }
            }
        } }
        item { Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("Background monitoring", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(if (monitoring) "Running • samples are being collected every 30 seconds." else "Off • start it to keep collecting while the app is closed.")
                Spacer(Modifier.height(10.dp))
                if (monitoring) OutlinedButton(onClick = onStop) { Text("Stop monitoring") }
                else Button(onClick = onStart) { Text("Start monitoring") }
            }
        } }
        item { Text("Transparency", style = MaterialTheme.typography.titleMedium) }
        item { Text("Current, voltage, temperature and level are Android-reported values. Capacity, health, wear cycles and efficiency are model estimates built from repeated charging measurements.", style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun HealthScreen(health: HealthEstimate, sessions: List<ChargeSession>) {
    val recent = sessions.filter { it.endLevel - it.startLevel >= 60 }.takeLast(5)
    val averageWear = recent.map { it.wearCycles }.average().takeIf { !it.isNaN() }
    val averageEfficiency = recent.map { it.efficiencyPercent }.average().takeIf { !it.isNaN() }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Battery health", style = MaterialTheme.typography.labelLarge)
                Text(health.healthPercent?.let { "${"%.1f".format(it)}%" } ?: "Collecting data", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
                Text(health.capacityMah?.let { "Estimated capacity: ${"%.0f".format(it)} mAh" } ?: "Complete a charge covering at least 60% to start an estimate")
                Spacer(Modifier.height(8.dp))
                Text("Confidence: ${health.confidencePercent}%", fontWeight = FontWeight.SemiBold)
                Text("${health.completedSessions} usable recent session(s)")
            }
        } }
        item { Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("Recent wear", averageWear?.let { "${"%.2f".format(it)} cycles" } ?: "—", Modifier.weight(1f))
            MetricCard("Efficiency", averageEfficiency?.let { "${"%.0f".format(it)}%" } ?: "—", Modifier.weight(1f))
        } }
        item { Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("How BatteryScope estimates health", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text("During charging we integrate the measured charge current over time. The charge added is divided by the percentage gained to estimate full-charge capacity. Only sessions covering at least 60 percentage points are used, and the health result averages the most recent five usable sessions.")
                Spacer(Modifier.height(6.dp))
                Text("Health = estimated capacity ÷ 5,000 mAh × 100. The 5,000 mAh value is a configured model reference for this app profile.", style = MaterialTheme.typography.bodySmall)
            }
        } }
        item { Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("Wear model", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text("Wear is a modeled cycle-cost estimate based on the highest voltage reached during a charge. Higher end voltage receives a higher wear cost; lowering the end voltage reduces modeled wear. This is an estimate, not an Android-reported battery-health field.")
            }
        } }
        item { Text("Completed charge sessions", style = MaterialTheme.typography.titleMedium) }
        items(sessions.reversed()) { session -> Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("${session.startLevel}% → ${session.endLevel}%", fontWeight = FontWeight.SemiBold)
                Text("Added: ${"%.0f".format(session.chargedMah)} mAh • Capacity: ${"%.0f".format(session.estimatedCapacityMah)} mAh")
                Text("Wear: ${"%.2f".format(session.wearCycles)} cycles • Efficiency: ${"%.0f".format(session.efficiencyPercent)}%")
                Text("Peak voltage: ${"%.3f".format(session.endVoltageV)} V")
                Text(DateFormat.getDateTimeInstance().format(Date(session.endTime)), style = MaterialTheme.typography.bodySmall)
            }
        } }
    }
}

@Composable
private fun HistoryScreen(samples: List<HistorySample>) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Temperature & power history", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Stored locally on the device. Maximum 500 samples.")
        }
        item { HorizontalDivider() }
        items(samples.reversed().take(100)) { sample -> Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text("${sample.level}% • ${"%.1f".format(sample.temperatureC)} °C"); Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(sample.timestamp)), style = MaterialTheme.typography.bodySmall) }
                Column { Text("${"%.3f".format(sample.voltageV)} V"); Text(sample.currentMa?.let { "${"%.0f".format(it)} mA" } ?: "—", style = MaterialTheme.typography.bodySmall) }
            }
        } }
        if (samples.isEmpty()) item { Text("No history yet. Start monitoring to collect samples.") }
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
