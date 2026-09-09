package com.batteryscope.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.util.ArrayDeque
import java.util.Locale

class BatteryMonitorService : Service() {
    private lateinit var store: BatteryStore
    private lateinit var engine: MeasurementEngine
    private val handler = Handler(Looper.getMainLooper())
    private val recent = ArrayDeque<BatterySnapshot>()

    private val monitor = object : Runnable {
        override fun run() {
            val battery = readBattery(this@BatteryMonitorService)
            addRecent(battery)
            engine.observe(battery)
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                buildNotification(battery)
            )
            handler.postDelayed(this, 15_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = BatteryStore(this)
        engine = MeasurementEngine(store)
        createChannel()
        val battery = readBattery(this)
        addRecent(battery)
        engine.observe(battery)
        startForeground(NOTIFICATION_ID, buildNotification(battery))
        handler.postDelayed(monitor, 15_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(monitor)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun addRecent(snapshot: BatterySnapshot) {
        recent.addLast(snapshot)
        while (recent.size > 20) recent.removeFirst()
    }

    private fun averageCurrent(): Double? {
        val values = recent.mapNotNull { it.currentMa }
        return values.takeIf { it.isNotEmpty() }?.average()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Battery monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent BatteryScope battery telemetry"
            }
        )
    }

    private fun formatCurrent(ma: Double): String {
        return if (kotlin.math.abs(ma) >= 1000.0) {
            String.format(Locale.US, "%.2f A", ma / 1000.0)
        } else {
            String.format(Locale.US, "%.0f mA", ma)
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalMinutes = (ms / 60_000L).coerceAtLeast(0L)
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    private fun buildNotification(battery: BatterySnapshot): Notification {
        val average = averageCurrent()
        val interactive = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        val remainingMah = battery.counterMicroAh?.div(1000L)
        val energyWh = battery.energyCounterNWh?.div(1_000_000_000.0)

        val firstLine = buildString {
            append(if (battery.charging) "Charging" else "Discharging")
            append(" • ${battery.level}%")
            battery.currentMa?.let { append(" • ${formatCurrent(it)}") }
        }
        val secondLine = buildString {
            battery.powerW?.let { append(String.format(Locale.US, "%.2f W", it)) }
            if (battery.powerW != null) append(" • ")
            append(String.format(Locale.US, "%.3f V", battery.voltageV))
            append(" • ")
            append(String.format(Locale.US, "%.1f°C", battery.temperatureC))
        }

        val detail = buildString {
            append("Now: ")
            append(battery.currentMa?.let(::formatCurrent) ?: "Unavailable")
            battery.powerW?.let { append(" • ${String.format(Locale.US, "%.2f W", it)}") }
            append("\nAvg: ")
            append(average?.let(::formatCurrent) ?: "Unavailable")
            append("\nScreen: ")
            append(if (interactive) "on" else "off")
            remainingMah?.let { append("\nRemaining charge: ${it} mAh") }
            energyWh?.let { append(" • ${String.format(Locale.US, "%.1f Wh", it)}") }
            if (battery.charging && battery.chargeTimeRemainingMs != null) {
                append("\nFull in: ${formatDuration(battery.chargeTimeRemainingMs)}")
            }
        }

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            7002,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle("BatteryScope • ${battery.level}%")
            .setContentText("$firstLine • $secondLine")
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "battery_monitor"
        const val NOTIFICATION_ID = 7001
    }
}
