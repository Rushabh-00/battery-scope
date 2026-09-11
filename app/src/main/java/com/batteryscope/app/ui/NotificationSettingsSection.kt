package com.batteryscope.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
    var entries by remember { mutableStateOf(settings.notificationEntries) }
    var chargeTime by remember { mutableStateOf(settings.notificationChargeTimeEstimate) }
    val metrics = AppSettings.NotificationMetric.entries

    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(22.dp)) {
            Text("Notifications", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.padding(top = 1.dp))
            Text(
                "Choose what BatteryScope keeps visible in the ongoing notification.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(top = 10.dp))
            HorizontalDivider()
            Spacer(Modifier.padding(top = 14.dp))

            Text("Notification Icon", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.padding(top = 8.dp))
            NotificationMetricRow(
                metrics = metrics,
                selected = icon,
                multiSelect = false,
                checked = { it == icon },
                onSelected = {
                    icon = it
                    settings.notificationIcon = it
                },
            )

            Spacer(Modifier.padding(top = 18.dp))
            Text("Notification Entries", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.padding(top = 8.dp))
            NotificationMetricRow(
                metrics = metrics,
                selected = icon,
                multiSelect = true,
                checked = { it in entries },
                onSelected = { metric ->
                    entries = if (metric in entries) entries - metric else entries + metric
                    settings.notificationEntries = entries
                },
            )

            Spacer(Modifier.padding(top = 18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Charge Time Estimate", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Show time remaining until fully charged in the notification.",
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
    multiSelect: Boolean,
    checked: (AppSettings.NotificationMetric) -> Boolean,
    onSelected: (AppSettings.NotificationMetric) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        metrics.forEach { metric ->
            val active = checked(metric)
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(13.dp))
                    .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(13.dp))
                    .selectable(
                        selected = active,
                        onClick = { onSelected(metric) },
                        role = if (multiSelect) Role.Checkbox else Role.RadioButton,
                    )
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (active) {
                        Text("✓", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(metric.value, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}
