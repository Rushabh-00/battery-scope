package com.batteryscope.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class BatteryMonitorService : Service() {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var job: Job? = null
    private lateinit var store: BatteryStore
    private lateinit var engine: MeasurementEngine

    override fun onCreate() {
        super.onCreate()
        store = BatteryStore(this)
        engine = MeasurementEngine(store)
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(readBattery(this)))
        job = scope.launch {
            while (isActive) {
                val battery = readBattery(this@BatteryMonitorService)
                engine.observe(battery)
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(battery))
                delay(30_000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        job?.cancel()
        scope.coroutineContext.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Battery monitoring", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Persistent BatteryScope monitoring"
            }
        )
    }

    private fun buildNotification(battery: BatterySnapshot): Notification {
        val line = buildString {
            append("${battery.level}% • ${"%.1f".format(battery.temperatureC)}°C")
            if (battery.currentMa != null) append(" • ${"%.0f".format(battery.currentMa)} mA")
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle("BatteryScope")
            .setContentText(line)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "battery_monitor"
        const val NOTIFICATION_ID = 7001
    }
}
