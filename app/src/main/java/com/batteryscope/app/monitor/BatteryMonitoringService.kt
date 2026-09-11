package com.batteryscope.app.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.batteryscope.app.battery.BatteryRuntime
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

class BatteryMonitoringService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private lateinit var settings: AppSettings

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
                        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(snapshot))
                    }
                delay(settings.backgroundUpdateIntervalMs)
            }
            stopSelf()
        }
    }

    private fun buildNotification(snapshot: com.batteryscope.app.battery.BatterySnapshot?): Notification {
        val text = snapshot?.let {
            buildString {
                append("${it.levelPercent}%")
                it.powerW?.let { power -> append(" • ${String.format(java.util.Locale.US, "%.1f", power)} W") }
                append(if (it.charging) " • charging" else " • on battery")
            }
        } ?: "Background battery monitoring is starting"
        val openIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.batteryscope.app.R.drawable.ic_stat_battery)
            .setContentTitle("BatteryScope • monitoring")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(openIntent)
            .build()
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
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val CHANNEL_ID = "battery_monitoring"
        const val NOTIFICATION_ID = 2104
    }
}
