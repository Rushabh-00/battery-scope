package com.batteryscope.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs

private val Accent = Color(0xFF4F7CFF)
private const val SETUP_SECONDS = 30

class CalibrationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = BatteryStore(this)
        if (store.calibrationCompleted()) {
            openMain()
            return
        }
        setContent { BatterySetupScreen(::openMain) }
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }
}

@Composable
private fun BatterySetupScreen(onFinish: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { BatteryStore(context) }
    var elapsed by remember { mutableIntStateOf(0) }
    var latest by remember { mutableStateOf<BatterySnapshot?>(null) }
    var readings by remember { mutableStateOf(emptyList<Double>()) }
    var connectedSeen by remember { mutableStateOf(false) }
    var calibrationScale by remember { mutableStateOf(1.0) }
    var stableCounterStart by remember { mutableStateOf<Long?>(null) }
    var stableTimeStart by remember { mutableStateOf<Long?>(null) }
    var message by remember { mutableStateOf("Reading the battery interface…") }
    var finished by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        store.clearCalibration()
        var previous: BatterySnapshot? = null
        while (elapsed < SETUP_SECONDS) {
            val snapshot = readBattery(context, 1.0)
            latest = snapshot
            connectedSeen = connectedSeen || snapshot.charging
            snapshot.sensorCurrentMa?.takeIf { abs(it) >= 0.5 }?.let { value ->
                readings = (readings + abs(value)).takeLast(15)
            }

            val counter = snapshot.counterMicroAh
            val prev = previous
            if (snapshot.charging && counter != null && prev?.charging == true && prev.counterMicroAh != null) {
                val dtMs = (snapshot.timestamp - prev.timestamp).coerceAtLeast(1L)
                val deltaMicroAh = counter - prev.counterMicroAh
                if (deltaMicroAh > 0L && dtMs >= 2_000L) {
                    val observedMa = deltaMicroAh / (dtMs / 3_600_000.0) / 1000.0
                    val rawMa = abs(snapshot.rawCurrentMa ?: 0.0)
                    if (observedMa >= CURRENT_CALIBRATION_MIN_MA && rawMa >= 0.5) {
                        val ratio = (observedMa / rawMa).takeIf { it.isFinite() && it in 0.25..CURRENT_CALIBRATION_MAX_RATIO }
                        if (ratio != null) {
                            calibrationScale = calibrationScale * 0.65 + ratio * 0.35
                            store.setAutoCurrentScale(calibrationScale)
                            message = "Current scale calibrated from charge-counter movement."
                        }
                    }
                }
            }
            previous = snapshot
            if (snapshot.counterMicroAh != null && snapshot.level in 20..99) {
                val projected = snapshot.projectedCapacityMah
                if (projected != null) {
                    val existing = store.startupCapacityMah()
                    val smoothed = if (existing == null) projected else existing * 0.80 + projected * 0.20
                    store.saveStartupCapacityMah(smoothed)
                }
            }
            stableCounterStart = stableCounterStart ?: snapshot.counterMicroAh
            stableTimeStart = stableTimeStart ?: snapshot.timestamp
            elapsed += 2
            if (!snapshot.charging) {
                message = if (connectedSeen) "Charger removed; finalizing the measured calibration." else "Keep the phone idle. Connect the charger during setup to calibrate current scale more precisely."
            }
            delay(2_000L)
        }

        finished = true
        message = when {
            connectedSeen && readings.size >= 3 -> "Setup complete. Current and power are now tied to the device readings."
            readings.isNotEmpty() -> "Current sensor detected. More charge-counter data will refine the correction automatically."
            else -> "Current data is unavailable from this device; BatteryScope will use the best available Android reading."
        }
        store.markCalibrationCompleted()
    }

    MaterialTheme(colorScheme = androidx.compose.material3.lightColorScheme(primary = Accent)) {
        Surface(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Text(if (finished) "Battery setup complete" else "Battery setup", style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "BatteryScope automatically detects the available current sensor, charge counter and battery capacity estimate. Power is calculated from the corrected current and live voltage.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                item {
                    SetupCard("Device") {
                        SensorLine("Android", "${Build.VERSION.RELEASE ?: "—"}")
                        SensorLine("Battery level", latest?.let { "${it.level}%" } ?: "—")
                        SensorLine("Voltage", latest?.let { format3(it.voltageV) + " V" } ?: "—")
                        SensorLine("Temperature", latest?.let { format1(it.temperatureC) + " °C" } ?: "—")
                    }
                }
                item {
                    SetupCard("Battery capacity") {
                        SensorLine("Charge counter", latest?.chargeAh?.let { format3(it) + " Ah" } ?: "Not available")
                        SensorLine("Estimated full capacity", latest?.projectedCapacityMah?.let { format0(it) + " mAh" } ?: store.startupCapacityMah()?.let { format0(it) + " mAh" } ?: "Learning…")
                        Text("The capacity shown here is measured/estimated from Android charge-counter data. It is refined again after real charging sessions.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                item {
                    SetupCard("Current calibration") {
                        SensorLine("Sensor reading", latest?.sensorCurrentMa?.let { format1(abs(it)) + " mA" } ?: "—")
                        SensorLine("Normalized current", latest?.currentMa?.let { format1(abs(it)) + " mA" } ?: "—")
                        SensorLine("Power", latest?.let { format2(abs(it.powerW)) + " W" } ?: "—")
                        SensorLine("Charging polarity", when {
                            latest?.charging != true -> "Idle / discharge"
                            latest?.sensorCurrentMa == null -> "Detected from status"
                            latest.sensorCurrentMa > 0 -> "Positive sensor sign"
                            latest.sensorCurrentMa < 0 -> "Negative sensor sign"
                            else -> "Zero"
                        })
                        SensorLine("Correction", format3(calibrationScale) + "×")
                        Text(message, style = MaterialTheme.typography.bodySmall)
                    }
                }
                item {
                    LinearProgressIndicator(
                        progress = { (elapsed.toFloat() / SETUP_SECONDS).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(8.dp)
                    )
                    Text("${(elapsed * 100 / SETUP_SECONDS).coerceAtMost(100)}% • ${readings.size} current samples")
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = onFinish, enabled = finished, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                            Text(if (finished) "OPEN BATTERYSCOPE" else "SETTING UP…")
                        }
                        OutlinedButton(onClick = onFinish, enabled = finished, modifier = Modifier.fillMaxWidth()) {
                            Text("SKIP TO APP")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(24.dp)),
        RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SensorLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

private fun format0(value: Double): String = String.format(Locale.US, "%.0f", value)
private fun format1(value: Double): String = String.format(Locale.US, "%.1f", value)
private fun format2(value: Double): String = String.format(Locale.US, "%.2f", value)
private fun format3(value: Double): String = String.format(Locale.US, "%.3f", value)
