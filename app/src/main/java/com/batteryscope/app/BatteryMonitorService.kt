package com.batteryscope.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class BatteryMonitorService : Service() {
    private lateinit var store: BatteryStore
    private lateinit var engine: MeasurementEngine
    private val handler = Handler(Looper.getMainLooper())

    private val monitor = object : Runnable {
        override fun run() {
            val battery = readBattery(this@BatteryMonitorService)
            engine.observe(battery)
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(battery))
            handler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = BatteryStore(this)
        engine = MeasurementEngine(store)
        createChannel()
        val battery = readBattery(this)
        engine.observe(battery)
        startForeground(NOTIFICATION_ID, buildNotification(battery))
        handler.postDelayed(monitor, 30_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(monitor)
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
        val text = buildString {
            append("${battery.level}% • ${"%.1f".format(battery.temperatureC)}°C")
            if (battery.currentMa != null) append(" • ${"%.0f".format(battery.currentMa)} mA")
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle("BatteryScope")
            .setContentText(text)
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
