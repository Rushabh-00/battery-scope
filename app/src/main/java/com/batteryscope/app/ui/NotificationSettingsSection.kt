package com.batteryscope.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batteryscope.app.BuildConfig
import com.batteryscope.app.monitor.BatteryMonitoringController
import com.batteryscope.app.settings.AppSettings
import com.batteryscope.app.update.GitHubUpdateManager
import java.util.Locale

@Composable
fun NotificationSettingsSection(settings: AppSettings) {
    val context = LocalContext.current
    val activity = context as? Activity
    var enabled by remember { mutableStateOf(settings.notificationEnabled) }
    var icon by remember { mutableStateOf(settings.notificationIcon) }
    var entries by remember { mutableStateOf(settings.notificationEntries - settings.notificationIcon) }
    var chargeTime by remember { mutableStateOf(settings.notificationChargeTimeEstimate) }
    var automaticUpdateCheck by remember { mutableStateOf(settings.automaticUpdateCheck) }
    var optimizationIgnored by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    var automaticUpdateMessage by remember { mutableStateOf("") }
    val metrics = AppSettings.NotificationMetric.entries
    val iconSizes = remember { mutableStateMapOf<AppSettings.NotificationMetric, Int>().apply {
        metrics.forEach { put(it, settings.notificationIconSizePercent(it)) }
    } }

    LaunchedEffect(automaticUpdateCheck) {
        automaticUpdateMessage = if (!automaticUpdateCheck) "" else {
            val result = runCatching { GitHubUpdateManager.checkLatest(BuildConfig.VERSION_NAME) }
            result.fold(
                onSuccess = { release -> release?.let { "Update available: ${it.versionName}. Use App update below to install it." } ?: "You're up to date." },
                onFailure = { "Automatic check failed. You can still check manually below." },
            )
        }
    }

    Card(shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Notifications", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        if (enabled) "Live battery status is available in the status bar."
                        else "Turn this on to keep battery monitoring running outside the app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { value ->
                        enabled = value
                        settings.notificationEnabled = value
                        if (value) {
                            if (Build.VERSION.SDK_INT >= 33 && activity != null && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4001)
                            } else BatteryMonitoringController.start(context)
                        } else BatteryMonitoringController.stop(context)
                    },
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            SettingGroupTitle("Status bar icon", "Pick the metric and give every metric its own 0–100 size setting.")
            Spacer(Modifier.height(10.dp))
            Text("Icon metric", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            NotificationMetricGrid(metrics, icon, { it == icon }, emptySet()) { metric ->
                icon = metric
                entries = entries - metric
                settings.notificationIcon = metric
                settings.notificationEntries = entries
                BatteryMonitoringController.refresh(context)
            }

            Spacer(Modifier.height(14.dp))
            NotificationIconPreview(icon, iconSizes[icon] ?: 100)
            Spacer(Modifier.height(14.dp))
            Text("Individual icon sizes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Adjust W, A, Ah, °C, V, Wh and % separately. Each slider is remembered independently.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            metrics.forEach { metric ->
                val value = iconSizes[metric] ?: 100
                IconSizeControl(
                    metric = metric,
                    value = value,
                    selected = metric == icon,
                    onSelect = {
                        icon = metric
                        entries = entries - metric
                        settings.notificationIcon = metric
                        settings.notificationEntries = entries
                        BatteryMonitoringController.refresh(context)
                    },
                    onValueChange = { newValue -> iconSizes[metric] = newValue },
                    onValueFinished = {
                        settings.setNotificationIconSizePercent(metric, iconSizes[metric] ?: 100)
                        BatteryMonitoringController.refresh(context)
                    },
                )
                Spacer(Modifier.height(6.dp))
            }

            Spacer(Modifier.height(18.dp))
            SettingGroupTitle("Notification details", "Extra values appear when you expand the notification.")
            Spacer(Modifier.height(10.dp))
            NotificationMetricGrid(metrics, icon, { it in entries }, setOf(icon)) { metric ->
                entries = if (metric in entries) entries - metric else entries + metric
                settings.notificationEntries = entries
                BatteryMonitoringController.refresh(context)
            }
            Spacer(Modifier.height(12.dp))
            SettingToggle("Charge time estimate", "Show an estimated time remaining until full while charging.", chargeTime) {
                chargeTime = it
                settings.notificationChargeTimeEstimate = it
                BatteryMonitoringController.refresh(context)
            }

            Spacer(Modifier.height(20.dp))
            SettingGroupTitle("Background reliability", "Android and some phone brands may restrict background apps.")
            Spacer(Modifier.height(10.dp))
            Text("Sampling interval: ${formatInterval(settings.updateIntervalMs)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                },
                Modifier.fillMaxWidth(),
            ) { Text("Open battery & background settings") }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    requestBatteryOptimization(context)
                    optimizationIgnored = isIgnoringBatteryOptimizations(context)
                },
                Modifier.fillMaxWidth(),
                enabled = !optimizationIgnored,
            ) { Text(if (optimizationIgnored) "Battery optimization is already relaxed" else "Allow unrestricted battery use") }
            Text("Some phones also have an OEM Auto-start switch. Enable it when your device stops background monitoring.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(20.dp))
            SettingGroupTitle("Update checks", "Keep automatic checking optional; you can always use App update below.")
            Spacer(Modifier.height(10.dp))
            SettingToggle("Automatic update check", "Checks GitHub when Settings opens. Off by default.", automaticUpdateCheck) {
                automaticUpdateCheck = it
                settings.automaticUpdateCheck = it
            }
            if (automaticUpdateMessage.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(automaticUpdateMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SettingGroupTitle(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(2.dp))
    Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SettingToggle(title: String, body: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun IconSizeControl(
    metric: AppSettings.NotificationMetric,
    value: Int,
    selected: Boolean,
    onSelect: () -> Unit,
    onValueChange: (Int) -> Unit,
    onValueFinished: () -> Unit,
) {
    val title = metric.value
    Card(
        colors = CardDefaults.cardColors(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text("$value%", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = value.toFloat(),
                onValueChange = { onValueChange(it.toInt().coerceIn(0, 100)) },
                onValueChangeFinished = onValueFinished,
                valueRange = 0f..100f,
                steps = 99,
            )
            Text(
                when {
                    value == 0 -> "Hidden"
                    value < 35 -> "Very small"
                    value < 70 -> "Small"
                    value < 100 -> "Medium"
                    else -> "Maximum"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NotificationIconPreview(metric: AppSettings.NotificationMetric, sizePercent: Int) {
    val (value, unit) = when (metric) {
        AppSettings.NotificationMetric.POWER -> "12.4" to "W"
        AppSettings.NotificationMetric.CURRENT -> "1.8" to "A"
        AppSettings.NotificationMetric.CHARGE -> "3.9" to "Ah"
        AppSettings.NotificationMetric.TEMPERATURE -> "36.6" to "°C"
        AppSettings.NotificationMetric.VOLTAGE -> "4.2" to "V"
        AppSettings.NotificationMetric.ENERGY -> "16.8" to "Wh"
        AppSettings.NotificationMetric.PERCENT -> "45" to "%"
    }
    val scale = sizePercent / 100f
    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Live preview", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text("${metric.value} • $sizePercent%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(Modifier.size(70.dp).clip(RoundedCornerShape(18.dp)).background(Color.Black).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (scale > 0f) {
                            Text(value, color = Color.White, fontSize = (18f * scale).sp, lineHeight = (18f * scale).sp, fontWeight = FontWeight.Bold)
                            Text(unit, color = Color.White, fontSize = (8f * scale).sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationMetricGrid(
    metrics: List<AppSettings.NotificationMetric>,
    selected: AppSettings.NotificationMetric,
    checked: (AppSettings.NotificationMetric) -> Boolean,
    disabled: Set<AppSettings.NotificationMetric>,
    onSelected: (AppSettings.NotificationMetric) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        metrics.chunked(4).forEach { rowMetrics ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowMetrics.forEach { metric ->
                    val active = checked(metric)
                    val locked = metric in disabled
                    Box(
                        Modifier.weight(1f)
                            .clip(RoundedCornerShape(13.dp))
                            .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .border(1.dp, if (locked) MaterialTheme.colorScheme.outline.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outline, RoundedCornerShape(13.dp))
                            .then(if (!locked) Modifier.selectable(selected = active, onClick = { onSelected(metric) }, role = Role.Checkbox) else Modifier)
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(metric.value, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal) }
                }
                repeat(4 - rowMetrics.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private fun requestBatteryOptimization(context: android.content.Context) {
    runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) }
        .recoverCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
}

private fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean = runCatching {
    val manager = context.getSystemService(PowerManager::class.java)
    Build.VERSION.SDK_INT < 23 || manager?.isIgnoringBatteryOptimizations(context.packageName) == true
}.getOrDefault(false)

private fun formatInterval(ms: Long) = if (ms == 1_250L) "1.25 s" else if (ms % 1000L == 0L) "${ms / 1000}s" else String.format(Locale.US, "%.2f s", ms / 1000.0)
