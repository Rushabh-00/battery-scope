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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.util.Locale
import kotlin.math.abs

class BatteryMonitorService : Service() {
    private lateinit var store: BatteryStore
    private lateinit var engine: MeasurementEngine
    private val handler = Handler(Looper.getMainLooper())
    private var iconBitmap: Bitmap? = null
    private var lastCharging: Boolean? = null
    private var screenOnStartElapsed = 0L
    private var screenOnTotalMs = 0L
    private var lastScreenPersistence = 0L
    private var screenSessionStartMs = 0L
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        style = Paint.Style.FILL
    }

    private val monitor = object : Runnable {
        override fun run() {
            val battery = readBattery(this@BatteryMonitorService, store.autoCurrentScale())
            updateChargingState(battery)
            updateScreenTime(battery)
            engine.observe(battery)
            publish(battery)
            handler.postDelayed(this, pollDelayMs(battery))
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = BatteryStore(this)
        if (!store.settings().notificationEnabled) {
            stopSelf()
            return
        }
        engine = MeasurementEngine(store)
        createChannel()
        restoreScreenTime()
        val battery = readBattery(this, store.autoCurrentScale())
        updateChargingState(battery)
        updateScreenTime(battery, forcePersist = true)
        engine.observe(battery)
        startForeground(NOTIFICATION_ID, buildNotification(battery))
        handler.postDelayed(monitor, pollDelayMs(battery))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!store.settings().notificationEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(monitor)
        flushScreenTime()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "BatteryScope • Live", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Persistent live battery telemetry"
                setShowBadge(false)
            }
        )
    }

    private fun pollDelayMs(battery: BatterySnapshot): Long {
        val configured = store.settings().updateIntervalSeconds * 1000L
        val power = getSystemService(POWER_SERVICE) as PowerManager
        return if (!power.isInteractive && !battery.charging) maxOf(configured, 15_000L) else configured
    }

    private fun updateChargingState(battery: BatterySnapshot) {
        when {
            battery.charging && lastCharging != true -> store.saveChargingSince(battery.timestamp)
            !battery.charging && lastCharging == true -> store.saveChargingSince(null)
        }
        lastCharging = battery.charging
    }

    private fun restoreScreenTime() {
        screenSessionStartMs = store.screenTimeSessionStartMillis()
        screenOnTotalMs = store.screenTimeMillis()
        if (screenSessionStartMs <= 0L || screenSessionStartMs > System.currentTimeMillis()) {
            screenSessionStartMs = System.currentTimeMillis()
            screenOnTotalMs = 0L
        }
    }

    private fun screenIsInteractive(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isInteractive

    private fun updateScreenTime(battery: BatterySnapshot, forcePersist: Boolean = false) {
        val plugged = battery.plugged ?: battery.charging
        val nowWall = battery.timestamp
        val nowElapsed = SystemClock.elapsedRealtime()

        if (plugged) {
            if (screenOnStartElapsed != 0L) {
                screenOnTotalMs += (nowElapsed - screenOnStartElapsed).coerceAtLeast(0L)
                screenOnStartElapsed = 0L
            }
        } else {
            if (screenSessionStartMs <= 0L) screenSessionStartMs = nowWall
            if (screenIsInteractive() && screenOnStartElapsed == 0L) screenOnStartElapsed = nowElapsed
            if (!screenIsInteractive() && screenOnStartElapsed != 0L) {
                screenOnTotalMs += (nowElapsed - screenOnStartElapsed).coerceAtLeast(0L)
                screenOnStartElapsed = 0L
            }
        }

        if (forcePersist || nowWall - lastScreenPersistence >= 60_000L) flushScreenTime(nowElapsed)
    }

    private fun flushScreenTime(nowElapsed: Long = SystemClock.elapsedRealtime()) {
        if (screenOnStartElapsed != 0L) {
            screenOnTotalMs += (nowElapsed - screenOnStartElapsed).coerceAtLeast(0L)
            screenOnStartElapsed = nowElapsed
        }
        store.saveScreenTime(screenOnTotalMs, screenSessionStartMs)
        lastScreenPersistence = System.currentTimeMillis()
    }

    private fun metricLabel(key: String): String = when (key) {
        "W" -> "Power"
        "A" -> "Current"
        "mAh" -> "Charge"
        "°C" -> "Temperature"
        "V" -> "Voltage"
        "Wh" -> "Energy"
        "%" -> "Battery"
        else -> key
    }

    private fun metricUnit(key: String, settings: UiSettings): String = when (key) {
        "A" -> settings.currentUnit
        "°C" -> if (settings.temperatureF) "°F" else "°C"
        else -> key
    }

    private fun metricValue(key: String, battery: BatterySnapshot, settings: UiSettings): String = when (key) {
        "W" -> format2(abs(battery.powerW))
        "A" -> battery.currentMa?.let { if (settings.currentUnit == "A") format3(abs(it) / 1000.0) else format1(abs(it)) } ?: "—"
        "mAh" -> battery.counterMicroAh?.takeIf { it >= 0 }?.let { format0(it / 1000.0) } ?: "—"
        "°C" -> if (settings.temperatureF) format1(battery.temperatureC * 9.0 / 5.0 + 32.0) else format1(battery.temperatureC)
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
        val fraction = battery.level / 100.0
        val energyWh = battery.energyCounterNWh?.takeIf { it > 0 }?.div(1_000_000_000.0)
        val powerW = abs(battery.powerW)
        if (fraction <= 0.01 || energyWh == null || powerW <= 0.0) return null
        val estimatedFullWh = energyWh / fraction
        val remainingWh = estimatedFullWh * (1.0 - fraction)
        return formatDuration((remainingWh / powerW * 3_600_000.0).toLong())
    }

    private fun screenTimeText(): String {
        val active = if (screenOnStartElapsed != 0L) (SystemClock.elapsedRealtime() - screenOnStartElapsed).coerceAtLeast(0L) else 0L
        return formatDurationShort(screenOnTotalMs + active)
    }

    private fun buildNotification(battery: BatterySnapshot): Notification {
        val settings = store.settings()
        val primary = settings.notificationIndicator.takeIf { it in METRICS } ?: "W"
        val primaryValue = metricValue(primary, battery, settings)
        val primaryUnit = metricUnit(primary, settings)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(this, 7002, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("$primaryValue $primaryUnit")
            .setSmallIcon(renderIcon(primaryValue, primaryUnit))
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)

        val extraKeys = settings.notificationEntries
            .filter { it in METRICS && it != primary }
            .sortedBy { METRIC_ORDER.indexOf(it) }
        val style = Notification.InboxStyle()
        extraKeys.forEach { key ->
            style.addLine("${metricLabel(key)}  ${metricValue(key, battery, settings)} ${metricUnit(key, settings)}")
        }
        if (battery.charging && settings.showChargeTime) chargeTimeText(battery)?.let { style.addLine("Full in  $it") }
        if (settings.showScreenState) style.addLine("Screen  ${if (screenIsInteractive()) "on" else "off"} • ${screenTimeText()}")
        if (battery.charging) store.chargingSinceMillis()?.let { since -> style.addLine("Charging since  ${formatDuration(System.currentTimeMillis() - since)}") }
        builder.setStyle(style)
        val compact = extraKeys.take(3).joinToString("  ") { "${metricValue(it, battery, settings)} ${metricUnit(it, settings)}" }
        builder.setContentText(compact.ifBlank { chargeTimeText(battery) ?: "Battery telemetry" })
        return builder.build()
    }

    private fun publish(battery: BatterySnapshot) {
        if (!store.settings().notificationEnabled) {
            stopSelf()
            return
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(battery))
    }

    private fun formatDuration(ms: Long): String {
        val minutes = (ms / 60_000L).coerceAtLeast(0L)
        val hours = minutes / 60L
        val mins = minutes % 60L
        return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
    }

    private fun formatDurationShort(ms: Long): String {
        val seconds = (ms / 1000L).coerceAtLeast(0L)
        return String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600L, (seconds / 60L) % 60L, seconds % 60L)
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
