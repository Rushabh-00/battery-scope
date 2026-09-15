package com.batteryscope.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.graphicsLayer
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
    val metrics = AppSettings.NotificationMetric.entries
    var enabled by remember { mutableStateOf(settings.notificationEnabled) }
    var selectedMetric by remember { mutableStateOf(settings.notificationIcon) }
    var entries by remember { mutableStateOf(settings.notificationEntries - selectedMetric) }
    var chargeTime by remember { mutableStateOf(settings.notificationChargeTimeEstimate) }
    var automaticUpdateCheck by remember { mutableStateOf(settings.automaticUpdateCheck) }
    var automaticUpdateMessage by remember { mutableStateOf("") }
    var optimizationIgnored by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    val iconSizes = remember {
        mutableStateMapOf<AppSettings.NotificationMetric, Int>().apply {
            metrics.forEach { put(it, settings.notificationIconSizePercent(it)) }
        }
    }

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
                        if (enabled) "Live status is active in the status bar and notification shade." else "Turn this on for optional background battery monitoring.",
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

            Spacer(Modifier.height(18.dp))
            HorizontalDivider()
            Spacer(Modifier.height(18.dp))

            GroupCard("Status bar icon", "Choose one metric and preview its real 24dp notification slot.") {
                AnimatedContent(
                    targetState = selectedMetric,
                    transitionSpec = {
                        (fadeIn() + scaleIn(initialScale = 0.96f) + slideInVertically { it / 5 }) togetherWith
                            (fadeOut() + scaleOut(targetScale = 1.02f) + slideOutVertically { -it / 5 })
                    },
                    label = "selectedNotificationMetric",
                ) { metric ->
                    Column(Modifier.animateContentSize()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("Selected", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(metric.value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            }
                            Box(Modifier.size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                        }
                        Spacer(Modifier.height(12.dp))
                        NotificationIconPreview(metric, iconSizes[metric] ?: 100)
                        Spacer(Modifier.height(12.dp))
                        IconSizeControl(
                            value = iconSizes[metric] ?: 100,
                            onValueChange = { iconSizes[metric] = it },
                            onValueFinished = {
                                settings.setNotificationIconSizePercent(metric, iconSizes[metric] ?: 100)
                                BatteryMonitoringController.refresh(context)
                            },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("Notification icon", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                NotificationMetricGrid(metrics, selectedMetric, emptySet()) { metric ->
                    selectedMetric = metric
                    entries = entries - metric
                    settings.notificationIcon = metric
                    settings.notificationEntries = entries
                    BatteryMonitoringController.refresh(context)
                }
            }

            Spacer(Modifier.height(16.dp))
            GroupCard("Notification details", "Extra metrics are shown in the expanded notification.") {
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
                AnimatedVisibility(automaticUpdateMessage.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
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
private fun IconSizeControl(value: Int, onValueChange: (Int) -> Unit, onValueFinished: () -> Unit) {
    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.secondaryContainer), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Icon size", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Controls the content inside a fixed 24dp status-bar slot.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("$value%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = value.toFloat(),
                onValueChange = { onValueChange(it.toInt().coerceIn(0, 100)) },
                onValueChangeFinished = onValueFinished,
                valueRange = 0f..100f,
                steps = 99,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("0", style = MaterialTheme.typography.labelSmall)
                Text("50", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("100", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun NotificationIconPreview(metric: AppSettings.NotificationMetric, sizePercent: Int) {
    val (value, unit) = notificationPreviewValue(metric)
    val scale = (sizePercent / 100f).coerceIn(0f, 1f)
    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Status-bar preview", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text("Android keeps the slot size; this slider scales only the metric drawing.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("24dp slot", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF080808))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("10:04", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    PreviewIcon(value, unit, scale)
                    Spacer(Modifier.width(5.dp))
                    Text("5G", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(5.dp))
                    Text("8", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(5.dp))
                    Box(Modifier.size(width = 18.dp, height = 9.dp).border(1.dp, Color.White, RoundedCornerShape(2.dp))) {
                        Box(Modifier.fillMaxWidth(0.8f).height(7.dp).padding(1.dp).background(Color.White, RoundedCornerShape(1.dp)))
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewIcon(value: String, unit: String, scale: Float) {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer(scaleX = scale, scaleY = scale),
        ) {
            Text(value, color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(unit, color = Color.White, fontSize = 5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

private fun notificationPreviewValue(metric: AppSettings.NotificationMetric): Pair<String, String> = when (metric) {
    AppSettings.NotificationMetric.PERCENT -> "85.0" to "%"
    AppSettings.NotificationMetric.POWER -> "12" to "W"
    AppSettings.NotificationMetric.CURRENT -> "3.2" to "A"
    AppSettings.NotificationMetric.CHARGE -> "04" to "Ah"
    AppSettings.NotificationMetric.TEMPERATURE -> "36" to "°C"
    AppSettings.NotificationMetric.VOLTAGE -> "04" to "V"
    AppSettings.NotificationMetric.ENERGY -> "16" to "Wh"
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
                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                            .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .border(1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                            .then(if (!locked) Modifier.selectable(selected = active, onClick = { onSelected(metric) }, role = Role.RadioButton) else Modifier)
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(metric.value, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium) }
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
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun InfoPill(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)).padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontWeight = FontWeight.SemiBold)
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

private fun formatInterval(ms: Long) = if (ms == 1_250L) "1.25 s" else if (ms % 1000L == 0L) "${ms / 1000}s" else String.format(Locale.US, "%.2f s", ms / 1000.0)
