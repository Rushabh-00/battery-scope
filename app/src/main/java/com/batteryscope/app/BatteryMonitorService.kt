package com.batteryscope.app

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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.abs

class BatteryMonitorService : Service() {
    private lateinit var store: BatteryStore
    private lateinit var engine: MeasurementEngine
    private val handler = Handler(Looper.getMainLooper())
    private val recent = ArrayDeque<BatterySnapshot>()
    private var lastCharging: Boolean? = null
    private var chargingSinceMs: Long? = null
    private var iconBitmap: Bitmap? = null
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        style = Paint.Style.FILL
    }

    private val monitor = object : Runnable {
        override fun run() {
            val battery = readBattery(this@BatteryMonitorService)
            addRecent(battery)
            updateChargingSince(battery)
            engine.observe(battery)
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(battery))
            handler.postDelayed(this, nextPollDelayMs(battery))
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = BatteryStore(this)
        engine = MeasurementEngine(store)
        createChannel()
        val battery = readBattery(this)
        addRecent(battery)
        updateChargingSince(battery)
        engine.observe(battery)
        startForeground(NOTIFICATION_ID, buildNotification(battery))
        handler.postDelayed(monitor, nextPollDelayMs(battery))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(monitor)
        super.onDestroy()
    }

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

    private fun updateChargingSince(battery: BatterySnapshot) {
        val charging = battery.charging
        when {
            charging && lastCharging != true -> chargingSinceMs = battery.timestamp
            !charging && lastCharging == true -> chargingSinceMs = null
        }
        lastCharging = charging
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "BatteryScope • Live", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Persistent live battery telemetry"
                setShowBadge(false)
            }
        )
    }

    private fun metricLabel(key: String, settings: UiSettings): String = when (key) {
        "W" -> "Power"
        "A" -> "Current"
        "mAh" -> "Charge"
        "Ah" -> "Charge"
        "°C" -> "Temperature"
        "V" -> "Voltage"
        "Wh" -> "Energy"
        "%" -> "Battery"
        else -> key
    }

    private fun metricUnit(key: String, settings: UiSettings): String = when (key) {
        "A" -> settings.currentUnit
        "mAh" -> "mAh"
        "Ah" -> "Ah"
        "°C" -> if (settings.temperatureF) "°F" else "°C"
        else -> key
    }

    private fun metricValue(key: String, battery: BatterySnapshot, settings: UiSettings): String = when (key) {
        "W" -> format2(abs(battery.powerW))
        "A" -> battery.currentMa?.let { if (settings.currentUnit == "A") format3(abs(it) / 1000.0) else format1(abs(it)) } ?: "—"
        "mAh" -> battery.counterMicroAh?.takeIf { it >= 0 }?.let { format0(it / 1000.0) } ?: "—"
        "Ah" -> battery.counterMicroAh?.takeIf { it >= 0 }?.let { format3(it / 1_000_000.0) } ?: "—"
        "°C" -> if (settings.temperatureF) format1(battery.temperatureC * 9 / 5 + 32) else format1(battery.temperatureC)
        "V" -> format3(battery.voltageV)
        "Wh" -> battery.energyCounterNWh?.takeIf { it >= 0 }?.let { format2(it / 1_000_000_000.0) } ?: "—"
        "%" -> battery.level.toString()
        else -> "—"
    }

    private fun renderIcon(value: String, unit: String): Icon {
        val density = resources.displayMetrics.density
        val size = (48f * density).toInt().coerceAtLeast(48)
        val bitmap = iconBitmap?.takeIf { it.width == size } ?: Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8).also { iconBitmap = it }
        bitmap.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bitmap)
        val maxWidth = size * 0.92f
        iconPaint.textSize = 40f * density
        val measured = iconPaint.measureText(value)
        if (measured > maxWidth && measured > 0f) iconPaint.textSize *= maxWidth / measured
        canvas.drawText(value, size / 2f, size * 0.62f, iconPaint)
        iconPaint.textSize = 18f * density
        canvas.drawText(unit, size / 2f, size * 0.94f, iconPaint)
        return Icon.createWithBitmap(bitmap)
    }

    private fun chargeTimeText(battery: BatterySnapshot): String? {
        if (!battery.charging) return null
        val direct = battery.chargeTimeRemainingMs
        if (direct != null && direct > 0L) return formatDuration(direct)
        if (battery.level >= 100) return "Full"
        val levelFraction = battery.level / 100.0
        val energyWh = battery.energyCounterNWh?.takeIf { it > 0 }?.div(1_000_000_000.0)
        val powerW = abs(battery.powerW)
        if (levelFraction <= 0.01 || energyWh == null || energyWh <= 0.0 || powerW <= 0.0) return null
        val estimatedFullWh = energyWh / levelFraction
        val remainingWh = estimatedFullWh * (1.0 - levelFraction)
        return formatDuration((remainingWh / powerW * 3_600_000.0).toLong())
    }

    private fun buildNotification(battery: BatterySnapshot): Notification {
        val settings = store.settings()
        val primary = settings.notificationIndicator.takeIf { it in METRICS } ?: "W"
        val primaryUnit = metricUnit(primary, settings)
        val primaryValue = metricValue(primary, battery, settings)
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 7002, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("$primaryValue $primaryUnit")
            .setSmallIcon(renderIcon(primaryValue, primaryUnit))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)

        val extras = settings.notificationEntries
            .filter { it in METRICS && it != primary }
            .sortedBy { METRIC_ORDER.indexOf(it) }
        val style = Notification.InboxStyle()
        extras.forEach { key ->
            style.addLine("${metricLabel(key, settings)}  ${metricValue(key, battery, settings)} ${metricUnit(key, settings)}")
        }
        if (battery.charging && settings.showChargeTime) {
            chargeTimeText(battery)?.let { style.addLine("Full in  $it") }
        }
        if (settings.showScreenState) {
            val interactive = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
            style.addLine("Screen  ${if (interactive) "on" else "off"}")
        }
        if (battery.charging && chargingSinceMs != null) {
            style.addLine("Charging since  ${formatDuration(System.currentTimeMillis() - chargingSinceMs!!)}")
        }
        if (extras.isNotEmpty() || battery.charging || settings.showScreenState) {
            val compact = extras.take(3).joinToString("  ") { key -> "${metricValue(key, battery, settings)} ${metricUnit(key, settings)}" }
            builder.setStyle(style)
            builder.setContentText(compact.ifBlank { chargeTimeText(battery) ?: "Battery telemetry" })
        } else {
            builder.setContentText("Battery telemetry")
        }
        return builder.build()
    }

    private fun formatDuration(ms: Long): String {
        val totalMinutes = (ms / 60_000L).coerceAtLeast(0L)
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    private fun format3(value: Double): String = String.format(Locale.US, "%.3f", value)
    private fun format2(value: Double): String = String.format(Locale.US, "%.2f", value)
    private fun format1(value: Double): String = String.format(Locale.US, "%.1f", value)
    private fun format0(value: Double): String = String.format(Locale.US, "%.0f", value)

    companion object {
        const val CHANNEL_ID = "battery_monitor"
        const val NOTIFICATION_ID = 7001
        private val METRICS = setOf("W", "A", "mAh", "°C", "V", "Wh", "%")
        private val METRIC_ORDER = listOf("W", "A", "mAh", "°C", "V", "Wh", "%")
    }
}
