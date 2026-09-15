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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
        automaticUpdateMessage = if (!automaticUpdateCheck) {
            ""
        } else {
            val result = runCatching { GitHubUpdateManager.checkLatest(BuildConfig.VERSION_NAME) }
            result.fold(
                onSuccess = { release ->
                    release?.let { "Update available: ${it.versionName}. Use App update below to install it." }
                        ?: "You're up to date."
                },
                onFailure = { "Automatic check failed. You can still check manually below." },
            )
        }
    }

    Card(
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.animateContentSize(),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            SettingsSectionHeader(
                title = "Notifications",
                body = if (enabled) {
                    "Live battery status is active outside BatteryScope."
                } else {
                    "Turn this on to keep battery monitoring available in the status bar."
                },
                trailing = {
                    Switch(
                        checked = enabled,
                        onCheckedChange = { value ->
                            enabled = value
                            settings.notificationEnabled = value
                            if (value) {
                                if (Build.VERSION.SDK_INT >= 33 &&
                                    activity != null &&
                                    activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                                ) {
                                    activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4001)
                                } else {
                                    BatteryMonitoringController.start(context)
                                }
                            } else {
                                BatteryMonitoringController.stop(context)
                            }
                        },
                    )
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(18.dp))

            SettingsGroup("Status bar icon", "Pick one metric. Only that metric's size control appears below.") {
                AnimatedContent(
                    targetState = selectedMetric,
                    transitionSpec = {
                        (fadeIn() + slideInVertically { it / 4 }) togetherWith
                            (fadeOut() + slideOutVertically { -it / 4 })
                    },
                    label = "selectedMetric",
                ) { metric ->
                    Column {
                        MetricLabel(metric)
                        Spacer(Modifier.height(10.dp))
                        NotificationIconPreview(metric, iconSizes[metric] ?: 100)
                        Spacer(Modifier.height(10.dp))
                        IconSizeControl(
                            metric = metric,
                            value = iconSizes[metric] ?: 100,
                            onValueChange = { value -> iconSizes[metric] = value },
                            onValueFinished = {
                                settings.setNotificationIconSizePercent(metric, iconSizes[metric] ?: 100)
                                BatteryMonitoringController.refresh(context)
                            },
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    "Choose notification icon",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                NotificationMetricGrid(
                    metrics = metrics,
                    selected = selectedMetric,
                    checked = { it == selectedMetric },
                    disabled = emptySet(),
                    onSelected = { metric ->
                        selectedMetric = metric
                        entries = entries - metric
                        settings.notificationIcon = metric
                        settings.notificationEntries = entries
                        BatteryMonitoringController.refresh(context)
                    },
                )
            }

            Spacer(Modifier.height(18.dp))
            SettingsGroup("Notification details", "Keep extra values in the expanded notification.") {
                Text("Extra metrics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                NotificationMetricGrid(
                    metrics = metrics,
                    selected = selectedMetric,
                    checked = { it in entries },
                    disabled = setOf(selectedMetric),
                    onSelected = { metric ->
                        entries = if (metric in entries) entries - metric else entries + metric
                        settings.notificationEntries = entries
                        BatteryMonitoringController.refresh(context)
                    },
                )
                Spacer(Modifier.height(14.dp))
                SettingToggle(
                    "Charge time estimate",
                    "Show estimated time remaining while charging.",
                    chargeTime,
                ) {
                    chargeTime = it
                    settings.notificationChargeTimeEstimate = it
                    BatteryMonitoringController.refresh(context)
                }
            }

            Spacer(Modifier.height(18.dp))
            SettingsGroup("Background reliability", "Reduce the chance that Android pauses monitoring.") {
                InfoPill("Sampling interval", formatInterval(settings.updateIntervalMs))
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
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
                    "Some devices also have an OEM Auto-start switch. Enable it when your phone stops background monitoring.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Spacer(Modifier.height(18.dp))
            SettingsGroup("Update checks", "Automatic checks are optional and off by default.") {
                SettingToggle(
                    "Automatic update check",
                    "Checks GitHub when Settings opens.",
                    automaticUpdateCheck,
                ) {
                    automaticUpdateCheck = it
                    settings.automaticUpdateCheck = it
                }
                AnimatedVisibility(
                    visible = automaticUpdateMessage.isNotEmpty(),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
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
private fun SettingsSectionHeader(
    title: String,
    body: String,
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

@Composable
private fun SettingsGroup(title: String, body: String, content: @Composable () -> Unit) {
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
private fun MetricLabel(metric: AppSettings.NotificationMetric) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text("Selected icon", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(metric.value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Text("LIVE", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun IconSizeControl(
    metric: AppSettings.NotificationMetric,
    value: Int,
    onValueChange: (Int) -> Unit,
    onValueFinished: () -> Unit,
) {
    val sliderValue = value / 3f
    Card(
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Icon size", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Control 0–100 • actual icon scale 0–300%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("$value%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = sliderValue,
                onValueChange = { raw ->
                    onValueChange((raw * 3f).toInt().coerceIn(0, 300))
                },
                onValueChangeFinished = onValueFinished,
                valueRange = 0f..100f,
                steps = 99,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("0%", style = MaterialTheme.typography.labelSmall)
                Text("100 control", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("300%", style = MaterialTheme.typography.labelSmall)
            }
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
    Card(
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Live preview", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text("${metric.value} • $sizePercent%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(
                    Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.Black)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (sizePercent > 0) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(value, color = Color.White, fontSize = (18f * scale).coerceAtLeast(1f).sp, fontWeight = FontWeight.Bold)
                            Text(unit, color = Color.White, fontSize = (8f * scale).coerceAtLeast(1f).sp, fontWeight = FontWeight.Bold)
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
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                when {
                                    active -> MaterialTheme.colorScheme.primaryContainer
                                    locked -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                    else -> Color.Transparent
                                },
                            )
                            .border(
                                1.dp,
                                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(14.dp),
                            )
                            .then(
                                if (!locked) {
                                    Modifier.selectable(
                                        selected = active,
                                        onClick = { onSelected(metric) },
                                        role = Role.RadioButton,
                                    )
                                } else {
                                    Modifier
                                },
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
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun InfoPill(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontWeight = FontWeight.SemiBold)
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun requestBatteryOptimization(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"),
            ),
        )
    }.recoverCatching {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

private fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean = runCatching {
    val manager = context.getSystemService(PowerManager::class.java)
    Build.VERSION.SDK_INT < 23 || manager?.isIgnoringBatteryOptimizations(context.packageName) == true
}.getOrDefault(false)

private fun formatInterval(ms: Long) = if (ms == 1_250L) "1.25 s" else if (ms % 1000L == 0L) "${ms / 1000}s" else String.format(Locale.US, "%.2f s", ms / 1000.0)
