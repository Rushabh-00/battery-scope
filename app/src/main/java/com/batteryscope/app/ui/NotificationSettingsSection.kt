package com.batteryscope.app.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.batteryscope.app.settings.AppSettings

@Composable
fun NotificationSettingsSection(settings: AppSettings) {
    var icon by remember { mutableStateOf(settings.notificationIcon) }
    var entries by remember { mutableStateOf(settings.notificationEntries - settings.notificationIcon) }
    var chargeTime by remember { mutableStateOf(settings.notificationChargeTimeEstimate) }
    val metrics = AppSettings.NotificationMetric.entries

    Card(shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.fillMaxWidth().padding(22.dp)) {
            Text("Notifications", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(14.dp))

            Text("Notification icon", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(
                "Shows the selected live value and unit in the notification icon.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            NotificationMetricRow(metrics, icon, { it == icon }, emptySet()) { metric ->
                icon = metric
                entries = entries - metric
                settings.notificationIcon = metric
                settings.notificationEntries = entries
            }

            Spacer(Modifier.height(16.dp))
            Text("Notification entries", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(
                "Add extra values to the notification. The icon value is kept out automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            NotificationMetricRow(metrics, icon, { it in entries }, setOf(icon)) { metric ->
                entries = if (metric in entries) entries - metric else entries + metric
                settings.notificationEntries = entries
            }

            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Charge time estimate", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    Text(
                        "Show an estimated time remaining until full charge.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = chargeTime,
                    onCheckedChange = {
                        chargeTime = it
                        settings.notificationChargeTimeEstimate = it
                    },
                )
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (active) {
                        Text("✓", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(4.dp))
                    }
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
}
