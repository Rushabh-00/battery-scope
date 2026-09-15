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
    private lateinit var openIntent: PendingIntent
    private var iconBitmap: Bitmap? = null
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        style = Paint.Style.FILL
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        openIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        createNotificationChannel()
        startForegroundCompat(buildNotification(null))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!settings.notificationEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == BatteryMonitoringController.ACTION_REFRESH) {
            scope.launch {
                runCatching { BatteryRuntime.read(this@BatteryMonitoringService) }
                    .onSuccess { snapshot -> updateNotification(snapshot) }
            }
        }
        startMonitoring()
        return START_STICKY
    }

    private fun startMonitoring() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch {
            while (isActive && settings.notificationEnabled) {
                runCatching { BatteryRuntime.read(this@BatteryMonitoringService) }
                    .onSuccess { snapshot -> updateNotification(snapshot) }
                delay(settings.updateIntervalMs)
            }
            stopSelf()
        }
    }

    private fun updateNotification(snapshot: BatterySnapshot) {
        getSystemService(NotificationManager::class.java)?.notify(
            NOTIFICATION_ID,
            buildNotification(snapshot),
        )
    }

    private fun buildNotification(snapshot: BatterySnapshot?): Notification {
        val iconMetric = settings.notificationIcon
        val currentUnit = settings.currentUnit
        val temperatureUnit = settings.temperatureUnit
        val includeChargeTime = settings.notificationChargeTimeEstimate
        val entryMetrics = settings.notificationEntries
            .asSequence()
            .filter { it != iconMetric }
            .sortedBy { it.ordinal }
            .toList()
        val detailLines = snapshot?.let { value ->
            buildList {
                entryMetrics.forEach { add(formatMetric(value, it, currentUnit, temperatureUnit)) }
                if (includeChargeTime) formatChargeTimeEstimate(value)?.let(::add)
            }
        }.orEmpty()
        val contentText = when {
            detailLines.isEmpty() -> "BatteryScope • monitoring"
            detailLines.size == 1 -> detailLines.first()
            else -> detailLines.joinToString(" • ")
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(snapshot?.let { renderMetricIcon(it, iconMetric, currentUnit, temperatureUnit) } ?: renderPlaceholderIcon(iconMetric, currentUnit, temperatureUnit))
            .setContentTitle("BatteryScope")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(openIntent)
            .apply {
                if (detailLines.size > 1) {
                    setStyle(Notification.BigTextStyle().bigText(detailLines.joinToString("\n")))
                }
            }
            .build()
    }

    private fun renderMetricIcon(
        snapshot: BatterySnapshot,
        metric: AppSettings.NotificationMetric,
        currentUnit: AppSettings.CurrentUnit,
        temperatureUnit: AppSettings.TemperatureUnit,
    ): Icon {
        val (value, unit) = iconParts(snapshot, metric, currentUnit, temperatureUnit)
        return renderIcon(value, unit)
    }

    private fun renderPlaceholderIcon(
        metric: AppSettings.NotificationMetric,
        currentUnit: AppSettings.CurrentUnit,
        temperatureUnit: AppSettings.TemperatureUnit,
    ): Icon = renderIcon("—", iconUnit(metric, currentUnit, temperatureUnit))

    private fun renderIcon(value: String, unit: String): Icon {
        val density = resources.displayMetrics.density
        val size = (96f * density).toInt().coerceAtLeast(96)
        val bitmap = iconBitmap?.takeIf { it.width == size && !it.isRecycled } ?: Bitmap.createBitmap(
            size,
            size,
            Bitmap.Config.ARGB_8888,
        ).also { iconBitmap = it }
        bitmap.eraseColor(Color.TRANSPARENT)

        val scale = settings.notificationIconSizePercent / 100f
        val canvas = Canvas(bitmap)
        val maxWidth = size * 0.82f
        val centerX = size / 2f

        iconPaint.textSize = 34f * density * scale
        val measuredValue = iconPaint.measureText(value)
        if (measuredValue > maxWidth && measuredValue > 0f) {
            iconPaint.textSize *= maxWidth / measuredValue
        }
        canvas.drawText(value, centerX, size * 0.58f, iconPaint)

        iconPaint.textSize = 7.5f * density * scale
        val measuredUnit = iconPaint.measureText(unit)
        if (measuredUnit > maxWidth && measuredUnit > 0f) {
            iconPaint.textSize *= maxWidth / measuredUnit
        }
        canvas.drawText(unit, centerX, size * 0.78f, iconPaint)
        return Icon.createWithBitmap(bitmap)
    }

    private fun iconParts(
        snapshot: BatterySnapshot,
        metric: AppSettings.NotificationMetric,
        currentUnit: AppSettings.CurrentUnit,
        temperatureUnit: AppSettings.TemperatureUnit,
    ): Pair<String, String> = when (metric) {
        AppSettings.NotificationMetric.POWER -> (snapshot.powerW?.let { f1(it) } ?: "—") to "W"
        AppSettings.NotificationMetric.CURRENT -> (snapshot.currentA?.let { currentValue(it, currentUnit) } ?: "—") to currentUnit.value
        AppSettings.NotificationMetric.CHARGE -> (snapshot.remainingMah?.let { f1(it / 1000.0) } ?: "—") to "Ah"
        AppSettings.NotificationMetric.TEMPERATURE -> (snapshot.temperatureC?.let { temperatureValue(it, temperatureUnit) } ?: "—") to temperatureUnit.value
        AppSettings.NotificationMetric.VOLTAGE -> (snapshot.voltageV?.let { f1(it) } ?: "—") to "V"
        AppSettings.NotificationMetric.ENERGY -> (snapshot.energyWh?.let { f1(it) } ?: "—") to "Wh"
        AppSettings.NotificationMetric.PERCENT -> (snapshot.levelPercent.toString()) to "%"
    }

    private fun iconUnit(
        metric: AppSettings.NotificationMetric,
        currentUnit: AppSettings.CurrentUnit,
        temperatureUnit: AppSettings.TemperatureUnit,
    ): String = when (metric) {
        AppSettings.NotificationMetric.POWER -> "W"
        AppSettings.NotificationMetric.CURRENT -> currentUnit.value
        AppSettings.NotificationMetric.CHARGE -> "Ah"
        AppSettings.NotificationMetric.TEMPERATURE -> temperatureUnit.value
        AppSettings.NotificationMetric.VOLTAGE -> "V"
        AppSettings.NotificationMetric.ENERGY -> "Wh"
        AppSettings.NotificationMetric.PERCENT -> "%"
    }

    private fun formatMetric(
        snapshot: BatterySnapshot,
        metric: AppSettings.NotificationMetric,
        currentUnit: AppSettings.CurrentUnit,
        temperatureUnit: AppSettings.TemperatureUnit,
    ): String = when (metric) {
        AppSettings.NotificationMetric.POWER -> "Power ${snapshot.powerW?.let { "${f1(it)} W" } ?: "—"}"
        AppSettings.NotificationMetric.CURRENT -> "Current ${snapshot.currentA?.let { currentText(it, currentUnit) } ?: "—"}"
        AppSettings.NotificationMetric.CHARGE -> "Charge ${snapshot.remainingMah?.let { "${f2(it / 1000.0)} Ah" } ?: "—"}"
        AppSettings.NotificationMetric.TEMPERATURE -> "Temperature ${snapshot.temperatureC?.let { "${temperatureText(it, temperatureUnit)}" } ?: "—"}"
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

    private fun currentValue(value: Double, unit: AppSettings.CurrentUnit): String = when (unit) {
        AppSettings.CurrentUnit.AMPERE -> f1(value)
        AppSettings.CurrentUnit.MILLIAMPERE -> f0(value * 1000.0)
    }

    private fun currentText(value: Double, unit: AppSettings.CurrentUnit): String = "${currentValue(value, unit)} ${unit.value}"

    private fun temperatureValue(valueC: Double, unit: AppSettings.TemperatureUnit): String = when (unit) {
        AppSettings.TemperatureUnit.CELSIUS -> f1(valueC)
        AppSettings.TemperatureUnit.FAHRENHEIT -> f1(valueC * 9.0 / 5.0 + 32.0)
    }

    private fun temperatureText(valueC: Double, unit: AppSettings.TemperatureUnit): String = "${temperatureValue(valueC, unit)} ${unit.value}"

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
                    description = "Ongoing status for optional battery notifications"
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

    private fun f0(value: Double) = String.format(Locale.US, "%.0f", value)
    private fun f1(value: Double) = String.format(Locale.US, "%.1f", value)
    private fun f2(value: Double) = String.format(Locale.US, "%.2f", value)

    private companion object {
        const val CHANNEL_ID = "battery_monitoring"
        const val NOTIFICATION_ID = 2104
    }
}
