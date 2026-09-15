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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batteryscope.app.BuildConfig
import com.batteryscope.app.monitor.BatteryMonitoringController
import com.batteryscope.app.settings.AppSettings
import com.batteryscope.app.update.GitHubUpdateManager
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun NotificationSettingsSection(settings: AppSettings) {
    val context = LocalContext.current
    val activity = context as? Activity
    var enabled by remember { mutableStateOf(settings.notificationEnabled) }
    var icon by remember { mutableStateOf(settings.notificationIcon) }
    var iconSize by remember { mutableStateOf(settings.notificationIconSizePercent) }
    var entries by remember { mutableStateOf(settings.notificationEntries - settings.notificationIcon) }
    var chargeTime by remember { mutableStateOf(settings.notificationChargeTimeEstimate) }
    var automaticUpdateCheck by remember { mutableStateOf(settings.automaticUpdateCheck) }
    var automaticUpdateMessage by remember { mutableStateOf("") }
    var optimizationIgnored by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    val metrics = AppSettings.NotificationMetric.entries

    LaunchedEffect(automaticUpdateCheck) {
        if (!automaticUpdateCheck) {
            automaticUpdateMessage = ""
        } else {
            automaticUpdateMessage = "Checking GitHub for updates…"
            val result = runCatching { GitHubUpdateManager.checkLatest(BuildConfig.VERSION_NAME) }
            result.onSuccess { release ->
                automaticUpdateMessage = release?.let {
                    "Update available: ${it.versionName}. Use App update below to install it."
                } ?: "You're up to date."
            }.onFailure {
                automaticUpdateMessage = "Automatic check failed. You can still check manually below."
            }
        }
    }

    Card(shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                            } else {
                                BatteryMonitoringController.start(context)
                            }
                        } else {
                            BatteryMonitoringController.stop(context)
                        }
                    },
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            SettingGroupTitle("Status bar icon", "Choose what the compact icon shows and make it easier to read.")
            Spacer(Modifier.height(10.dp))
            Text("Icon metric", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            NotificationMetricRow(metrics, icon, { it == icon }, emptySet()) { metric ->
                icon = metric
                entries = entries - metric
                settings.notificationIcon = metric
                settings.notificationEntries = entries
            }
            Spacer(Modifier.height(12.dp))
            NotificationIconPreview(icon, iconSize)
            Spacer(Modifier.height(12.dp))
            Text("Icon size", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Use the slider when the number looks too small or too large on your phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("70%", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = iconSize.toFloat(),
                    onValueChange = { value -> iconSize = (value / 5f).roundToInt() * 5 },
                    onValueChangeFinished = { settings.notificationIconSizePercent = iconSize },
                    valueRange = 70f..140f,
                    steps = 13,
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                )
                Text("140%", style = MaterialTheme.typography.labelSmall)
            }
            Text("$iconSize%", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)

            Spacer(Modifier.height(20.dp))
            SettingGroupTitle("Notification details", "Extra values appear when you expand the notification.")
            Spacer(Modifier.height(10.dp))
            Text("Extra entries", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            NotificationMetricRow(metrics, icon, { it in entries }, setOf(icon)) { metric ->
                entries = if (metric in entries) entries - metric else entries + metric
                settings.notificationEntries = entries
            }
            Spacer(Modifier.height(12.dp))
            SettingToggle(
                title = "Charge time estimate",
                body = "Show an estimated time remaining until full while charging.",
                checked = chargeTime,
            ) {
                chargeTime = it
                settings.notificationChargeTimeEstimate = it
            }

            Spacer(Modifier.height(20.dp))
            SettingGroupTitle("Background reliability", "Android and some phone brands may restrict background apps.")
            Spacer(Modifier.height(10.dp))
            Text(
                "Sampling interval: ${formatInterval(settings.updateIntervalMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    context.startActivity(intent)
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
            ) {
                Text(if (optimizationIgnored) "Battery optimization is already relaxed" else "Allow unrestricted battery use")
            }
            Text(
                "Some phones also have an OEM Auto-start switch. Enable it when your device stops background monitoring.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))
            SettingGroupTitle("Update checks", "Keep automatic checking optional; you can always use App update below.")
            Spacer(Modifier.height(10.dp))
            SettingToggle(
                title = "Automatic update check",
                body = "Checks GitHub when Settings opens. Off by default.",
                checked = automaticUpdateCheck,
            ) {
                automaticUpdateCheck = it
                settings.automaticUpdateCheck = it
            }
            if (automaticUpdateMessage.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    automaticUpdateMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun NotificationIconPreview(metric: AppSettings.NotificationMetric, sizePercent: Int) {
    val preview = when (metric) {
        AppSettings.NotificationMetric.POWER -> "12.4 W"
        AppSettings.NotificationMetric.CURRENT -> "1.8 A"
        AppSettings.NotificationMetric.CHARGE -> "3.9 Ah"
        AppSettings.NotificationMetric.TEMPERATURE -> "36 °C"
        AppSettings.NotificationMetric.VOLTAGE -> "4.2 V"
        AppSettings.NotificationMetric.ENERGY -> "16.8 Wh"
        AppSettings.NotificationMetric.PERCENT -> "31%"
    }
    Card(
        colors = androidx.compose.material3.CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                val value = preview.substringBefore(' ')
                Text(
                    value,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = (MaterialTheme.typography.labelLarge.fontSize.value * sizePercent / 100f).sp,
                    ),
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Preview", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(preview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun NotificationMetricRow(
    metrics: List<AppSettings.NotificationMetric>,
    selected: AppSettings.NotificationMetric,
    checked: (AppSettings.NotificationMetric) -> Boolean,
    disabled: Set<AppSettings.NotificationMetric>,
    onSelected: (AppSettings.NotificationMetric) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        metrics.forEach { metric ->
            val active = checked(metric)
            val locked = metric in disabled
            val borderColor = if (locked) {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.outline
            }
            val fill = when {
                active -> MaterialTheme.colorScheme.primaryContainer
                locked -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                else -> Color.Transparent
            }
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(13.dp))
                    .background(fill)
                    .border(1.dp, borderColor, RoundedCornerShape(13.dp))
                    .then(
                        if (!locked) {
                            Modifier.selectable(
                                selected = active,
                                onClick = { onSelected(metric) },
                                role = Role.Checkbox,
                            )
                        } else {
                            Modifier
                        },
                    )
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    metric.value,
                    color = if (locked) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

private fun requestBatteryOptimization(context: android.content.Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
    }.recoverCatching {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

private fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean = runCatching {
    val manager = context.getSystemService(PowerManager::class.java)
    Build.VERSION.SDK_INT < 23 || manager?.isIgnoringBatteryOptimizations(context.packageName) == true
}.getOrDefault(false)

private fun formatInterval(ms: Long) = if (ms == 1_250L) "1.25 s" else if (ms % 1000L == 0L) "${ms / 1000}s" else Locale.US.let { String.format(it, "%.2f s", ms / 1000.0) }
