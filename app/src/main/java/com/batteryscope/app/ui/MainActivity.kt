package com.batteryscope.app.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.batteryscope.app.battery.BatteryReader
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : android.app.Activity() {
    private lateinit var reader: BatteryReader
    private var temperatureF = false
    private lateinit var temperatureLabel: TextView
    private lateinit var values: List<TextView>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reader = BatteryReader(this)
        buildUi()
        refresh()
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
                if (label == "Temperature") {
                    temperatureLabel = this
                    setOnClickListener {
                        temperatureF = !temperatureF
                        refresh()
                    }
                }
            })
            TextView(this).also { value ->
                value.textSize = 20f
                value.gravity = Gravity.START
                row.addView(value)
                list.addView(row)
                value
            }
        }

        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun refresh() {
        val b = reader.read()
        temperatureLabel.text = if (temperatureF) "Temperature (°F)" else "Temperature (°C)"

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
            "Unavailable",
            b.remainingMah?.let { "${format0(it)} mAh" } ?: "Unavailable",
            b.estimatedCapacityMah?.let { "${format0(it)} mAh" } ?: "Learning / unavailable",
            "Not calculated yet",
        )

        values.forEachIndexed { index, view -> view.text = rendered[index] }
    }

    private fun format0(value: Double): String = String.format(Locale.US, "%.0f", value)
    private fun format1(value: Double): String = String.format(Locale.US, "%.1f", value)
    private fun format2(value: Double): String = String.format(Locale.US, "%.2f", value)
    private fun format3(value: Double): String = String.format(Locale.US, "%.3f", value)
}
