package com.batteryscope.app.battery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.batteryscope.app.R
import com.batteryscope.app.settings.AppSettings
import com.batteryscope.app.ui.MainActivity
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Keeps a lightweight live battery notification available while monitoring is enabled. */
class BatteryNotificationService : Service() {
    private lateinit var reader: BatteryReader
    private lateinit var settings: AppSettings
    private var executor: ScheduledExecutorService? = null
    private var refreshFuture: ScheduledFuture<*>? = null

    override fun onCreate() {
        super.onCreate()
        reader = BatteryReader(this)
        settings = AppSettings(this)
        createNotificationChannel()
        startForegroundCompat(buildNotification(null))
        executor = Executors.newSingleThreadScheduledExecutor()
        scheduleRefresh(0L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!settings.notificationEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        scheduleRefresh(0L)
        return START_STICKY
    }

    override fun onDestroy() {
        refreshFuture?.cancel(true)
        refreshFuture = null
        executor?.shutdownNow()
        executor = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun scheduleRefresh(delayMs: Long) {
        val worker = executor ?: return
        refreshFuture?.cancel(false)
        refreshFuture = worker.schedule({
            refreshNotification()
            if (settings.notificationEnabled) {
                scheduleRefresh(settings.updateIntervalMs)
            } else {
                stopSelf()
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun refreshNotification() {
        if (!settings.notificationEnabled) return
        val snapshot = runCatching { reader.read(trackSession = false) }.getOrNull()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(snapshot))
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(snapshot: BatterySnapshot?): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val title: String
        val lines = ArrayList<String>()
        if (snapshot == null) {
            title = "BatteryScope"
            lines.add("Reading battery telemetry…")
        } else {
            title = if (snapshot.full) "100% • Full" else "${snapshot.levelPercent}% • ${if (snapshot.charging) "Charging" else "Discharging"}"
            val selected = settings.notificationEntries
            for (entry in AppSettings.NotificationEntry.entries) {
                if (entry !in selected) continue
                when (entry) {
                    AppSettings.NotificationEntry.BATTERY_LEVEL -> lines.add("Battery level: ${snapshot.levelPercent}%")
                    AppSettings.NotificationEntry.POWER -> lines.add("Power: ${snapshot.powerW?.let { String.format(Locale.US, "%.1f W", it) } ?: "—"}")
                    AppSettings.NotificationEntry.CURRENT -> lines.add("Current: ${snapshot.currentA?.let { formatCurrent(it, settings.currentUnit) } ?: "—"}")
                    AppSettings.NotificationEntry.VOLTAGE -> lines.add("Voltage: ${snapshot.voltageV?.let { String.format(Locale.US, "%.1f V", it) } ?: "—"}")
                    AppSettings.NotificationEntry.TEMPERATURE -> lines.add("Temperature: ${snapshot.temperatureC?.let { formatTemperature(it, settings.temperatureUnit) } ?: "—"}")
                    AppSettings.NotificationEntry.CHARGE_TIME -> lines.add("Charge time: ${snapshot.chargeTimeRemainingMs?.let(::durationText) ?: "—"}")
                }
            }
            if (lines.isEmpty()) lines.add("No notification entries selected")
        }

        val style = Notification.InboxStyle()
        lines.forEach(style::addLine)

        return builder
            .setSmallIcon(notificationIcon(settings.notificationIcon))
            .setContentTitle(title)
            .setContentText(lines.first())
            .setStyle(style)
            .setSubText("BatteryScope")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(Notification.PRIORITY_LOW)
            .build()
    }

    private fun notificationIcon(icon: AppSettings.NotificationIcon): Int = when (icon) {
        AppSettings.NotificationIcon.BATTERY -> R.drawable.ic_stat_battery
        AppSettings.NotificationIcon.BOLT -> R.drawable.ic_stat_bolt
        AppSettings.NotificationIcon.GAUGE -> R.drawable.ic_stat_gauge
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Battery telemetry",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Live BatteryScope battery readings"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun formatCurrent(value: Double, unit: AppSettings.CurrentUnit): String =
        if (unit == AppSettings.CurrentUnit.AMPERE) {
            String.format(Locale.US, "%.1f A", value)
        } else {
            String.format(Locale.US, "%.0f mA", value * 1000.0)
        }

    private fun formatTemperature(value: Double, unit: AppSettings.TemperatureUnit): String =
        if (unit == AppSettings.TemperatureUnit.CELSIUS) {
            String.format(Locale.US, "%.1f °C", value)
        } else {
            String.format(Locale.US, "%.1f °F", value * 9.0 / 5.0 + 32.0)
        }

    private fun durationText(ms: Long): String {
        if (ms <= 0L) return "Now"
        val totalMinutes = ms / 60_000L
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    companion object {
        private const val CHANNEL_ID = "battery_telemetry"
        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_OPEN = 1002

        fun start(context: Context) {
            val intent = Intent(context, BatteryNotificationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BatteryNotificationService::class.java))
        }
    }
}
