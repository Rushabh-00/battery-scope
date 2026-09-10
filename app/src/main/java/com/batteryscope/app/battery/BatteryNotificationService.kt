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
import java.util.concurrent.TimeUnit

/** Keeps a lightweight live battery notification available while monitoring is enabled. */
class BatteryNotificationService : Service() {
    private lateinit var reader: BatteryReader
    private lateinit var settings: AppSettings
    private var executor: ScheduledExecutorService? = null

    override fun onCreate() {
        super.onCreate()
        reader = BatteryReader(this)
        settings = AppSettings(this)
        createNotificationChannel()
        startForegroundCompat(buildNotification(null))
        executor = Executors.newSingleThreadScheduledExecutor().also { worker ->
            worker.scheduleAtFixedRate(::refreshNotification, 0L, UPDATE_INTERVAL_SECONDS, TimeUnit.SECONDS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            settings.notificationEnabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
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

    private fun refreshNotification() {
        if (!settings.notificationEnabled) {
            stopSelf()
            return
        }
        val snapshot = runCatching { reader.read() }.getOrNull()
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
        val stopIntent = Intent(this, BatteryNotificationService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val title: String
        val text: String
        if (snapshot == null) {
            title = "BatteryScope"
            text = "Reading battery telemetry…"
        } else {
            val state = if (snapshot.charging) "Charging" else "Discharging"
            val current = snapshot.currentA?.let { formatCurrent(it, settings.currentUnit) } ?: "—"
            val power = snapshot.powerW?.let { String.format(Locale.US, "%.1f W", it) } ?: "—"
            val voltage = snapshot.voltageV?.let { String.format(Locale.US, "%.1f V", it) } ?: "—"
            val temperature = snapshot.temperatureC?.let { formatTemperature(it, settings.temperatureUnit) } ?: "—"
            title = "${snapshot.levelPercent}% • $state"
            text = "$power • $current • $voltage • $temperature"
        }

        return builder
            .setSmallIcon(R.drawable.ic_stat_battery)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText("BatteryScope")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(Notification.PRIORITY_LOW)
            .addAction(Notification.Action.Builder(null, "Stop", stopPendingIntent).build())
            .build()
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

    companion object {
        const val ACTION_STOP = "com.batteryscope.app.action.STOP_NOTIFICATION"
        private const val CHANNEL_ID = "battery_telemetry"
        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_OPEN = 1002
        private const val REQUEST_STOP = 1003
        private const val UPDATE_INTERVAL_SECONDS = 5L

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
