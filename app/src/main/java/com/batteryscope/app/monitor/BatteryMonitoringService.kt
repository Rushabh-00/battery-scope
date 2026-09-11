package com.batteryscope.app.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import com.batteryscope.app.battery.BatteryRuntime
import com.batteryscope.app.battery.BatterySnapshot
import com.batteryscope.app.settings.AppSettings
import com.batteryscope.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

class BatteryMonitoringService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private lateinit var settings: AppSettings
    private var iconBitmap: Bitmap? = null
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        style = Paint.Style.FILL
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        createNotificationChannel()
        startForegroundCompat(buildNotification(null))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!settings.backgroundMonitoringEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        startMonitoring()
        return START_STICKY
    }

    private fun startMonitoring() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch {
            while (isActive && settings.backgroundMonitoringEnabled) {
                runCatching { BatteryRuntime.read(this@BatteryMonitoringService) }
                    .onSuccess { snapshot ->
                        getSystemService(NotificationManager::class.java)?.notify(
                            NOTIFICATION_ID,
                            buildNotification(snapshot),
                        )
                    }
                delay(settings.updateIntervalMs)
            }
            stopSelf()
        }
    }

    private fun buildNotification(snapshot: BatterySnapshot?): Notification {
        val iconMetric = settings.notificationIcon
        val entryMetrics = settings.notificationEntries
            .filter { it != iconMetric }
            .sortedBy { it.ordinal }
        val headline = snapshot?.let { formatCompactMetric(it, iconMetric) } ?: "Monitoring"
        val detailLines = snapshot?.let { value ->
            buildList {
                entryMetrics.forEach { add(formatMetric(value, it)) }
                if (settings.notificationChargeTimeEstimate) formatChargeTimeEstimate(value)?.let(::add)
            }
        }.orEmpty()

        val openIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(snapshot?.let { renderMetricIcon(it, iconMetric) } ?: renderPlaceholderIcon(iconMetric))
            .setContentTitle(headline)
            .setContentText(entryMetrics.joinToString("   ") { formatCompactMetric(snapshot, it) }.ifBlank { "BatteryScope • monitoring" })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(openIntent)

        if (detailLines.isNotEmpty()) {
            builder.setStyle(Notification.BigTextStyle().bigText(detailLines.joinToString("\n")))
        }
        return builder.build()
    }

    private fun renderMetricIcon(
        snapshot: BatterySnapshot,
        metric: AppSettings.NotificationMetric,
    ): Icon = renderIcon(iconParts(snapshot, metric).first, iconParts(snapshot, metric).second)

    private fun renderPlaceholderIcon(metric: AppSettings.NotificationMetric): Icon =
        renderIcon("—", iconUnit(metric))

    private fun renderIcon(value: String, unit: String): Icon {
        val density = resources.displayMetrics.density
        val size = (48f * density).toInt()
        val bitmap = iconBitmap?.takeIf { it.width == size } ?: Bitmap.createBitmap(
            size,
            size,
            Bitmap.Config.ALPHA_8,
        ).also { iconBitmap = it }
        bitmap.eraseColor(Color.TRANSPARENT)

        val canvas = Canvas(bitmap)
        val maxWidth = size * 0.92f

        iconPaint.textSize = 40f * density
        val measured = iconPaint.measureText(value)
        if (measured > maxWidth && measured > 0f) {
            iconPaint.textSize *= maxWidth / measured
        }
        canvas.drawText(value, size / 2f, size * 0.62f, iconPaint)

        iconPaint.textSize = 18f * density
        canvas.drawText(unit, size / 2f, size * 0.94f, iconPaint)
        return Icon.createWithBitmap(bitmap)
    }

    private fun iconParts(
        snapshot: BatterySnapshot,
        metric: AppSettings.NotificationMetric,
    ): Pair<String, String> = when (metric) {
        AppSettings.NotificationMetric.POWER ->
            (snapshot.powerW?.let { f1(it) } ?: "—") to "W"
        AppSettings.NotificationMetric.CURRENT ->
            (snapshot.currentA?.let { f1(it) } ?: "—") to "A"
        AppSettings.NotificationMetric.CHARGE ->
            (snapshot.remainingMah?.let { f1(it / 1000.0) } ?: "—") to "Ah"
        AppSettings.NotificationMetric.TEMPERATURE ->
            (snapshot.temperatureC?.let { f1(it) } ?: "—") to "°C"
        AppSettings.NotificationMetric.VOLTAGE ->
            (snapshot.voltageV?.let { f1(it) } ?: "—") to "V"
        AppSettings.NotificationMetric.ENERGY ->
            (snapshot.energyWh?.let { f1(it) } ?: "—") to "Wh"
        AppSettings.NotificationMetric.PERCENT ->
            (snapshot.levelPercent?.toString() ?: "—") to "%"
    }

    private fun iconUnit(metric: AppSettings.NotificationMetric): String = when (metric) {
        AppSettings.NotificationMetric.POWER -> "W"
        AppSettings.NotificationMetric.CURRENT -> "A"
        AppSettings.NotificationMetric.CHARGE -> "Ah"
        AppSettings.NotificationMetric.TEMPERATURE -> "°C"
        AppSettings.NotificationMetric.VOLTAGE -> "V"
        AppSettings.NotificationMetric.ENERGY -> "Wh"
        AppSettings.NotificationMetric.PERCENT -> "%"
    }

    private fun formatCompactMetric(snapshot: BatterySnapshot?, metric: AppSettings.NotificationMetric): String {
        if (snapshot == null) return "— ${metric.value}"
        return when (metric) {
            AppSettings.NotificationMetric.POWER -> "${snapshot.powerW?.let { f1(it) } ?: "—"} W"
            AppSettings.NotificationMetric.CURRENT -> "${snapshot.currentA?.let { currentText(it) } ?: "—"}"
            AppSettings.NotificationMetric.CHARGE -> "${snapshot.remainingMah?.let { f2(it / 1000.0) } ?: "—"} Ah"
            AppSettings.NotificationMetric.TEMPERATURE -> "${snapshot.temperatureC?.let { f0(it) } ?: "—"} °C"
            AppSettings.NotificationMetric.VOLTAGE -> "${snapshot.voltageV?.let { f1(it) } ?: "—"} V"
            AppSettings.NotificationMetric.ENERGY -> "${snapshot.energyWh?.let { f1(it) } ?: "—"} Wh"
            AppSettings.NotificationMetric.PERCENT -> "${snapshot.levelPercent}%"
        }
    }

    private fun formatMetric(snapshot: BatterySnapshot, metric: AppSettings.NotificationMetric): String = when (metric) {
        AppSettings.NotificationMetric.POWER -> "Power ${snapshot.powerW?.let { "${f1(it)} W" } ?: "—"}"
        AppSettings.NotificationMetric.CURRENT -> "Current ${snapshot.currentA?.let { currentText(it) } ?: "—"}"
        AppSettings.NotificationMetric.CHARGE -> "Charge ${snapshot.remainingMah?.let { "${f2(it / 1000.0)} Ah" } ?: "—"}"
        AppSettings.NotificationMetric.TEMPERATURE -> "Temperature ${snapshot.temperatureC?.let { "${f1(it)} °C" } ?: "—"}"
        AppSettings.NotificationMetric.VOLTAGE -> "Voltage ${snapshot.voltageV?.let { "${f1(it)} V" } ?: "—"}"
        AppSettings.NotificationMetric.ENERGY -> "Energy ${snapshot.energyWh?.let { "${f1(it)} Wh" } ?: "—"}"
        AppSettings.NotificationMetric.PERCENT -> "Charge level ${snapshot.levelPercent}%"
    }

    private fun formatChargeTimeEstimate(snapshot: BatterySnapshot): String? {
        if (!snapshot.charging || snapshot.full) return null
        val remainingMah = snapshot.remainingMah ?: return null
        val capacityMah = snapshot.batteryCapacityMah ?: return null
        val currentA = abs(snapshot.currentA ?: return null)
        if (currentA < 0.01) return null
        val missingMah = (capacityMah - remainingMah).coerceAtLeast(0.0)
        if (missingMah <= 0.0) return null
        val minutes = (missingMah / (currentA * 1000.0) * 60.0).toLong().coerceAtLeast(1L)
        val hours = minutes / 60
        val remainder = minutes % 60
        return if (hours > 0) "Charge time ≈ ${hours}h ${remainder}m" else "Charge time ≈ ${remainder}m"
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Battery monitoring", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Ongoing status for optional background battery monitoring"
                    setShowBadge(false)
                },
            )
        }
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        scope.cancel()
        iconBitmap?.recycle()
        iconBitmap = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun currentText(value: Double) = String.format(Locale.US, "%.2f A", value)
    private fun f0(value: Double) = String.format(Locale.US, "%.0f", value)
    private fun f1(value: Double) = String.format(Locale.US, "%.1f", value)
    private fun f2(value: Double) = String.format(Locale.US, "%.2f", value)

    private companion object {
        const val CHANNEL_ID = "battery_monitoring"
        const val NOTIFICATION_ID = 2104
    }
}
