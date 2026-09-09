package com.batteryscope.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
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
    private var lastLowAlarm = false
    private var lastFullAlarm = false
    private var lastHotAlarm = false

    private val monitor = object : Runnable {
        override fun run() {
            val battery = readBattery(this@BatteryMonitorService)
            addRecent(battery)
            engine.observe(battery)
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(battery))
            maybeAlarm(battery)
            handler.postDelayed(this, nextPollDelayMs(battery))
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = BatteryStore(this)
        engine = MeasurementEngine(store)
        createChannels()
        val battery = readBattery(this)
        addRecent(battery)
        engine.observe(battery)
        startForeground(NOTIFICATION_ID, buildNotification(battery))
        handler.postDelayed(monitor, nextPollDelayMs(battery))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onDestroy() { handler.removeCallbacks(monitor); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun nextPollDelayMs(battery: BatterySnapshot): Long {
        val configuredMs = store.settings().updateIntervalSeconds * 1000L
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val screenOffDischarging = !powerManager.isInteractive && !battery.charging
        return if (screenOffDischarging) maxOf(configuredMs, 15_000L) else configuredMs
    }

    private fun addRecent(snapshot: BatterySnapshot) {
        recent.addLast(snapshot)
        while (recent.size > 20) recent.removeFirst()
    }

    private fun averageCurrent(): Double? = recent.mapNotNull { it.currentMa }.takeIf { it.isNotEmpty() }?.average()

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Battery monitoring", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Persistent BatteryScope battery telemetry"
        })
        manager.createNotificationChannel(NotificationChannel(ALARM_CHANNEL_ID, "Battery alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Low battery, full charge and temperature alerts"
        })
    }

    private fun formatCurrent(ma: Double, unit: String): String {
        return if (unit == "A") {
            val amps = ma / 1000.0
            if (abs(amps) < 0.1) String.format(Locale.US, "%.3f A", amps) else String.format(Locale.US, "%.2f A", amps)
        } else {
            if (abs(ma) < 10.0) String.format(Locale.US, "%.1f mA", ma) else String.format(Locale.US, "%.0f mA", ma)
        }
    }

    private fun formatPower(w: Double, scalar: Float): String {
        val value = w * scalar
        return if (abs(value) < 0.1) String.format(Locale.US, "%.3f W", value) else String.format(Locale.US, "%.2f W", value)
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
        val remainingMah = battery.counterMicroAh?.div(1000.0)
        val energyWh = battery.energyCounterNWh?.div(1_000_000_000.0)
        val temp = if (settings.temperatureF) battery.temperatureC * 9.0 / 5.0 + 32.0 else battery.temperatureC
        val tempUnit = if (settings.temperatureF) "°F" else "°C"
        val entries = settings.notificationEntries

        val compact = buildString {
            append(if (battery.charging) "Charging" else "Discharging")
            if ("%" in entries) append(" • ${battery.level}%")
            if ("A" in entries) battery.currentMa?.let { append(" • ${formatCurrent(it, settings.currentUnit)}") }
            if ("W" in entries) append(" • ${formatPower(battery.powerW, settings.powerScalar)}")
            if ("°C" in entries) append(" • ${String.format(Locale.US, "%.1f%s", temp, tempUnit)}")
            if ("V" in entries) append(" • ${String.format(Locale.US, "%.3f V", battery.voltageV)}")
        }

        val detail = buildString {
            if ("A" in entries) append("Now: ${battery.currentMa?.let { formatCurrent(it, settings.currentUnit) } ?: "Unavailable"}")
            if ("W" in entries) append("${if (isNotEmpty()) " • " else ""}${formatPower(battery.powerW, settings.powerScalar)}")
            if ("A" in entries) average?.let { append("\nAvg: ${formatCurrent(it, settings.currentUnit)}") }
            if (settings.showScreenState) append("\nScreen: ${if (interactive) "on" else "off"}")
            if ("V" in entries) append("\nVoltage: ${String.format(Locale.US, "%.3f V", battery.voltageV)}")
            if ("°C" in entries) append("\nTemperature: ${String.format(Locale.US, "%.1f%s", temp, tempUnit)}")
            if ("Ah" in entries && remainingMah != null) append("\nRemaining charge: ${formatCharge(remainingMah, settings.chargeUnit)}")
            if ("Wh" in entries && energyWh != null) append("\nEnergy: ${String.format(Locale.US, "%.2f Wh", energyWh)}")
            if (battery.charging && settings.showChargeTime && battery.chargeTimeRemainingMs != null) append("\nFull in: ${formatDuration(battery.chargeTimeRemainingMs)}")
        }

        val openIntent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val pendingIntent = PendingIntent.getActivity(this, 7002, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle("BatteryScope • ${battery.level}%")
            .setContentText(compact.ifEmpty { "Battery telemetry" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail.ifEmpty { compact }))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    private fun formatCharge(mah: Double, unit: String): String = if (unit == "Ah") String.format(Locale.US, "%.3f Ah", mah / 1000.0) else String.format(Locale.US, "%.0f mAh", mah)

    private fun maybeAlarm(battery: BatterySnapshot) {
        val settings = store.settings()
        val manager = getSystemService(NotificationManager::class.java)
        val low = settings.lowBatteryAlarm && battery.level <= 15
        if (low && !lastLowAlarm) manager.notify(LOW_ALARM_ID, alarm("Low battery", "Battery is at ${battery.level}%"))
        if (!low) lastLowAlarm = false else lastLowAlarm = true

        val full = settings.fullBatteryAlarm && battery.status == "Full"
        if (full && !lastFullAlarm) manager.notify(FULL_ALARM_ID, alarm("Battery full", "Battery reached full charge"))
        if (!full) lastFullAlarm = false else lastFullAlarm = true

        val hot = settings.temperatureAlarm && battery.temperatureC >= 45.0
        if (hot && !lastHotAlarm) manager.notify(TEMP_ALARM_ID, alarm("High battery temperature", String.format(Locale.US, "Battery temperature is %.1f°C", battery.temperatureC)))
        if (!hot) lastHotAlarm = false else lastHotAlarm = true
    }

    private fun alarm(title: String, text: String): Notification = NotificationCompat.Builder(this, ALARM_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_alert)
        .setContentTitle(title)
        .setContentText(text)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .build()

    companion object {
        const val CHANNEL_ID = "battery_monitor"
        const val ALARM_CHANNEL_ID = "battery_alerts"
        const val NOTIFICATION_ID = 7001
        const val LOW_ALARM_ID = 7003
        const val FULL_ALARM_ID = 7004
        const val TEMP_ALARM_ID = 7005
    }
}
