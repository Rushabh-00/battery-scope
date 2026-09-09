package com.batteryscope.app

import android.content.Intent
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
import kotlin.math.abs

private val Accent = Color(0xFF76B900)
private const val CALIBRATION_SECONDS = 20

class CalibrationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = BatteryStore(this)
        if (store.calibrationCompleted()) {
            openMain()
            return
        }
        setContent { CalibrationScreen(onFinish = ::openMain) }
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }
}

@Composable
private fun CalibrationScreen(onFinish: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { BatteryStore(context) }
    var elapsed by remember { mutableIntStateOf(0) }
    var readings by remember { mutableStateOf(emptyList<Double>()) }
    var firstCounter by remember { mutableStateOf<Long?>(null) }
    var firstTime by remember { mutableStateOf<Long?>(null) }
    var scale by remember { mutableStateOf(store.autoCurrentScale()) }
    var direction by remember { mutableStateOf("Detecting…") }
    var result by remember { mutableStateOf("Sampling the battery sensor…") }
    var finished by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        store.clearCalibration()
        store.resetAutoCurrentScale()
        while (elapsed < CALIBRATION_SECONDS) {
            val snapshot = readBattery(context, 1.0)
            val raw = snapshot.rawCurrentMa
            direction = when {
                snapshot.charging -> "Charging"
                snapshot.status == "Discharging" -> "Discharging"
                else -> "Not charging"
            }
            if (raw != null && abs(raw) >= 0.5) {
                readings = (readings + abs(raw)).takeLast(12)
                val startCounter = firstCounter
                val startTime = firstTime
                if (startCounter == null && snapshot.counterMicroAh != null) {
                    firstCounter = snapshot.counterMicroAh
                    firstTime = snapshot.timestamp
                } else if (startCounter != null && startTime != null && snapshot.counterMicroAh != null) {
                    val hours = (snapshot.timestamp - startTime).coerceAtLeast(1L) / 3_600_000.0
                    val counterMa = abs(snapshot.counterMicroAh - startCounter) / 1000.0 / hours
                    val sensorMa = readings.average()
                    val ratio = if (sensorMa > 0.5) counterMa / sensorMa else Double.NaN
                    if (counterMa >= CURRENT_CALIBRATION_MIN_MA && ratio.isFinite() && ratio in 0.25..CURRENT_CALIBRATION_MAX_RATIO) {
                        scale = ratio.coerceIn(0.25, 1000.0)
                        store.setAutoCurrentScale(scale)
                    }
                }
            }
            elapsed += 2
            delay(2000L)
        }

        val usable = readings.size >= 3 && scale.isFinite() && scale in 0.25..1000.0
        result = if (usable) {
            "Current scale aligned from sensor readings and charge-counter movement. Power will use the corrected current and live voltage."
        } else {
            "The device did not expose enough charge-counter movement for a full scale correction. BatteryScope will continue refining the reading automatically in the background."
        }
        store.markCalibrationCompleted()
        finished = true
    }

    MaterialTheme(colorScheme = androidx.compose.material3.lightColorScheme(primary = Accent)) {
        Surface(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Text(if (finished) "Telemetry ready" else "Setting up telemetry", style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (finished) "Automatic current detection is complete. You can start using BatteryScope."
                        else "Disconnect the charger and leave the phone idle for a moment. BatteryScope is measuring current direction, sensor scale and charge-counter movement automatically.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                item {
                    CalibrationCard("Calibration progress") {
                        LinearProgressIndicator(
                            progress = { (elapsed.toFloat() / CALIBRATION_SECONDS).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(8.dp)
                        )
                        Text("${(elapsed * 100 / CALIBRATION_SECONDS).coerceAtMost(100)}%")
                    }
                }
                item {
                    CalibrationCard("Sensor readings") {
                        SensorLine("Direction", direction)
                        SensorLine("Current unit", "mA")
                        SensorLine("Latest current", readings.lastOrNull()?.let { "${format1(it)} mA" } ?: "—")
                        SensorLine("Average current", readings.takeIf { it.isNotEmpty() }?.average()?.let { "${format1(it)} mA" } ?: "—")
                        SensorLine("Samples", readings.size.toString())
                    }
                }
                item {
                    CalibrationCard("Automatic correction") {
                        SensorLine("Current scale", if (scale.isFinite()) format3(scale) else "—")
                        Text(result, style = MaterialTheme.typography.bodySmall)
                        Text("Power is calculated as corrected current × battery voltage, so A and W stay synchronized.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                item {
                    Button(onClick = onFinish, enabled = finished, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                        Text(if (finished) "CONTINUE" else "CALIBRATING…")
                    }
                }
            }
        }
    }
}

@Composable
private fun CalibrationCard(title: String, content: @Composable ColumnScope.() -> Unit) {
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

private fun format1(value: Double): String = "%.1f".format(java.util.Locale.US, value)
private fun format3(value: Double): String = "%.3f".format(java.util.Locale.US, value)
