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
import kotlin.math.abs

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
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(battery))
            handler.postDelayed(this, store.settings().updateIntervalSeconds * 1000L)
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
        handler.postDelayed(monitor, store.settings().updateIntervalSeconds * 1000L)
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

    private fun averageCurrent(): Double? = recent.mapNotNull { it.currentMa }.takeIf { it.isNotEmpty() }?.average()

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Battery monitoring", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Persistent BatteryScope battery telemetry"
            }
        )
    }

    private fun formatCurrent(ma: Double, unit: String): String {
        return if (unit == "A") {
            val amps = ma / 1000.0
            if (abs(amps) < 0.1) String.format(Locale.US, "%.3f A", amps) else String.format(Locale.US, "%.2f A", amps)
        } else {
            if (abs(ma) < 10.0) String.format(Locale.US, "%.1f mA", ma) else String.format(Locale.US, "%.0f mA", ma)
        }
    }

    private fun formatPower(w: Double): String {
        return if (abs(w) < 0.1) String.format(Locale.US, "%.3f W", w) else String.format(Locale.US, "%.2f W", w)
    }

    private fun formatDuration(ms: Long): String {
        val totalMinutes = (ms / 60_000L).coerceAtLeast(0L)
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    private fun buildNotification(battery: BatterySnapshot): Notification {
        val settings = store.settings()
        val average = averageCurrent()
        val interactive = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        val remaining = battery.counterMicroAh?.div(1000.0)
        val energyWh = battery.energyCounterNWh?.div(1_000_000_000.0)
        val temp = if (settings.temperatureF) battery.temperatureC * 9.0 / 5.0 + 32.0 else battery.temperatureC
        val tempUnit = if (settings.temperatureF) "°F" else "°C"

        val compact = buildString {
            append(if (battery.charging) "Charging" else "Discharging")
            append(" • ${battery.level}%")
            if (settings.showCurrent) battery.currentMa?.let { append(" • ${formatCurrent(it, settings.currentUnit)}") }
            if (settings.showPower) battery.powerW?.let { append(" • ${formatPower(it)}") }
        }

        val detail = buildString {
            append("Now: ${battery.currentMa?.let { formatCurrent(it, settings.currentUnit) } ?: "Unavailable"}")
            if (settings.showPower) battery.powerW?.let { append(" • ${formatPower(it)}") }
            append("\nAvg: ${average?.let { formatCurrent(it, settings.currentUnit) } ?: "Unavailable"}")
            append("\nScreen: ${if (interactive) "on" else "off"}")
            if (settings.showVoltage) append("\nVoltage: ${String.format(Locale.US, "%.3f V", battery.voltageV)}")
            if (settings.showTemperature) append("\nTemperature: ${String.format(Locale.US, "%.1f%s", temp, tempUnit)}")
            if (settings.showRemainingCharge && remaining != null) {
                append("\nRemaining charge: ")
                append(if (settings.chargeUnit == "Ah") String.format(Locale.US, "%.3f Ah", remaining / 1000.0) else String.format(Locale.US, "%.0f mAh", remaining))
            }
            if (settings.showEnergy && energyWh != null) {
                append("\nEnergy: ")
                append(if (settings.energyUnit == "kWh") String.format(Locale.US, "%.3f kWh", energyWh / 1000.0) else String.format(Locale.US, "%.2f Wh", energyWh))
            }
            if (battery.charging && settings.showChargeTime && battery.chargeTimeRemainingMs != null) {
                append("\nFull in: ${formatDuration(battery.chargeTimeRemainingMs)}")
            }
        }

        val openIntent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val pendingIntent = PendingIntent.getActivity(this, 7002, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle("BatteryScope • ${battery.level}%")
            .setContentText(compact)
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
