package com.batteryscope.app.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.batteryscope.app.battery.BatteryReader
import com.batteryscope.app.battery.ChargeDischargeTracker
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : android.app.Activity() {
    private lateinit var reader: BatteryReader
    private val tracker = ChargeDischargeTracker()
    private val handler = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 5_000L)
        }
    }
    private var temperatureF = false
    private lateinit var values: List<TextView>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reader = BatteryReader(this)
        buildUi()
        refresh()
    }

    override fun onStart() {
        super.onStart()
        handler.post(refreshTask)
    }

    override fun onStop() {
        handler.removeCallbacks(refreshTask)
        super.onStop()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val labels = listOf(
            "Power",
            "Current",
            "Voltage",
            "Temperature",
            "Energy",
            "Charge level",
            "Charging status",
            "Battery capacity",
            "Remaining battery",
            "Estimated capacity",
            "Charge",
            "Discharge",
            "Battery health"
        )

        values = labels.map { label ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 10, 0, 10)
            }
            row.addView(TextView(this).apply {
                text = label
                textSize = 14f
            })
            TextView(this).also { value ->
                value.textSize = 20f
                value.gravity = Gravity.START
                row.addView(value)
                list.addView(row)
                value
            }
        }

        val tempButton = TextView(this).apply {
            text = "Temperature"
            textSize = 14f
            setPadding(0, 20, 0, 20)
            setOnClickListener {
                temperatureF = !temperatureF
                refresh()
            }
        }
        list.addView(tempButton, 0)
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun refresh() {
        val b = reader.read()
        tracker.update(b.remainingMah, b.charging)
        val temp = b.temperatureC?.let {
            if (temperatureF) "${((it * 9.0 / 5.0) + 32.0).roundToInt()} °F"
            else "${format1(it)} °C"
        } ?: "Unavailable"

        val energy = if (b.energyWh != null && b.remainingMah != null) {
            "${format2(b.energyWh)} Wh / ${format3(b.remainingMah / 1000.0)} Ah"
        } else {
            "Unavailable"
        }

        val rendered = listOf(
            b.powerW?.let { "${format1(it)} W" } ?: "Unavailable",
            b.currentA?.let { "${format1(it)} A" } ?: "Unavailable",
            b.voltageV?.let { "${format1(it)} V" } ?: "Unavailable",
            temp,
            energy,
            "${b.levelPercent}%",
            if (b.charging) "Yes" else "No",
            b.batteryCapacityMah?.let { "${format0(it)} mAh" } ?: "Unavailable",
            b.remainingMah?.let { "${format0(it)} mAh" } ?: "Unavailable",
            b.estimatedCapacityMah?.let { "${format0(it)} mAh" } ?: "Learning / unavailable",
            "${format0(tracker.chargedMah())} mAh",
            "${format0(tracker.dischargedMah())} mAh",
            "Not calculated yet",
        )

        values.forEachIndexed { index, view -> view.text = rendered[index] }
    }

    private fun format0(value: Double): String = String.format(Locale.US, "%.0f", value)
    private fun format1(value: Double): String = String.format(Locale.US, "%.1f", value)
    private fun format2(value: Double): String = String.format(Locale.US, "%.2f", value)
    private fun format3(value: Double): String = String.format(Locale.US, "%.3f", value)
}
