package com.batteryscope.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.batteryscope.app.BuildConfig
import com.batteryscope.app.monitor.BatteryMonitoringController
import com.batteryscope.app.settings.AppSettings
import com.batteryscope.app.update.GitHubUpdateManager

@Composable
fun NotificationSettingsSection(settings: AppSettings) {
    val context = LocalContext.current
    val activity = context as? Activity
    val metrics = AppSettings.NotificationMetric.entries
    var enabled by remember { mutableStateOf(settings.notificationEnabled) }
    var selectedMetric by remember { mutableStateOf(settings.notificationIcon) }
    var entries by remember { mutableStateOf(settings.notificationEntries - selectedMetric) }
    var chargeTime by remember { mutableStateOf(settings.notificationChargeTimeEstimate) }
    var automaticUpdateCheck by remember { mutableStateOf(settings.automaticUpdateCheck) }
    var automaticUpdateMessage by remember { mutableStateOf("") }
    var optimizationIgnored by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    LaunchedEffect(automaticUpdateCheck) {
        automaticUpdateMessage = if (!automaticUpdateCheck) "" else {
            runCatching { GitHubUpdateManager.checkLatest(BuildConfig.VERSION_NAME) }.fold(
                onSuccess = { release -> release?.let { "Update available: ${it.versionName}." } ?: "You're up to date." },
                onFailure = { "Automatic check failed. Manual update check is still available." },
            )
        }
    }

    Card(
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.animateContentSize(),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Notifications", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        if (enabled) "Live battery status stays available in the notification shade." else "Turn this on for optional background battery monitoring.",
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

            Spacer(Modifier.height(18.dp))
            HorizontalDivider()
            Spacer(Modifier.height(18.dp))

            GroupCard("Status bar icon", "Choose the one live metric shown inside Android's fixed notification icon slot.") {
                AnimatedContent(
                    targetState = selectedMetric,
                    transitionSpec = {
                        (fadeIn() + scaleIn(initialScale = 0.96f) + slideInVertically { it / 5 }) togetherWith
                            (fadeOut() + scaleOut(targetScale = 1.02f) + slideOutVertically { -it / 5 })
                    },
                    label = "selectedNotificationMetric",
                ) { metric ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Selected metric", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(metric.value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("Fixed native-size rendering for a cleaner, consistent status-bar icon.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("96%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(14.dp))
                NotificationMetricGrid(metrics, selectedMetric, emptySet()) { metric ->
                    selectedMetric = metric
                    entries = entries - metric
                    settings.notificationIcon = metric
                    settings.notificationEntries = entries
                    BatteryMonitoringController.refresh(context)
                }
            }

            Spacer(Modifier.height(16.dp))
            GroupCard("Notification details", "Extra metrics are shown in the expanded notification, like a compact battery dashboard.") {
                NotificationMetricGrid(metrics, selectedMetric, setOf(selectedMetric), checked = { it in entries }) { metric ->
                    entries = if (metric in entries) entries - metric else entries + metric
                    settings.notificationEntries = entries
                    BatteryMonitoringController.refresh(context)
                }
                Spacer(Modifier.height(14.dp))
                SettingToggle("Charge time estimate", "Show an estimate only while charging and only when reliable.", chargeTime) {
                    chargeTime = it
                    settings.notificationChargeTimeEstimate = it
                    BatteryMonitoringController.refresh(context)
                }
            }

            Spacer(Modifier.height(16.dp))
            GroupCard("Background reliability", "Reduce the chance that Android or an OEM pauses monitoring.") {
                InfoPill("Sampling interval", formatInterval(settings.updateIntervalMs))
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) },
                    Modifier.fillMaxWidth(),
                ) { Text("Open app battery settings") }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        requestBatteryOptimization(context)
                        optimizationIgnored = isIgnoringBatteryOptimizations(context)
                    },
                    enabled = !optimizationIgnored,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (optimizationIgnored) "Battery optimization relaxed" else "Allow unrestricted battery use") }
                Text(
                    "On some phones also enable Auto-start when background monitoring is stopped by the device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
            GroupCard("Update checks", "Optional GitHub update checks. Installation always requires your action.") {
                SettingToggle("Automatic update check", "Check when Settings opens.", automaticUpdateCheck) {
                    automaticUpdateCheck = it
                    settings.automaticUpdateCheck = it
                }
                if (automaticUpdateMessage.isNotEmpty()) {
                    Text(
                        automaticUpdateMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupCard(title: String, body: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun NotificationMetricGrid(
    metrics: List<AppSettings.NotificationMetric>,
    selected: AppSettings.NotificationMetric,
    disabled: Set<AppSettings.NotificationMetric>,
    checked: (AppSettings.NotificationMetric) -> Boolean = { it == selected },
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
                            .then(
                                if (!locked) Modifier.selectable(
                                    selected = active,
                                    onClick = { onSelected(metric) },
                                    role = Role.RadioButton,
                                ) else Modifier
                            )
                            .background(
                                if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                RoundedCornerShape(14.dp),
                            )
                            .border(
                                1.dp,
                                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(14.dp),
                            )
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(metric.value, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
                    }
                }
                repeat(4 - rowMetrics.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SettingToggle(title: String, body: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun InfoPill(title: String, value: String) {
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(value, fontWeight = FontWeight.Bold)
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(PowerManager::class.java) ?: return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        powerManager.isIgnoringBatteryOptimizations(context.packageName)
    } else {
        true
    }
}

private fun requestBatteryOptimization(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}

private fun formatInterval(ms: Long): String = when {
    ms == 1_250L -> "1.25 s"
    ms % 1000L == 0L -> "${ms / 1000L} s"
    else -> String.format(java.util.Locale.US, "%.2f s", ms / 1000.0)
}
