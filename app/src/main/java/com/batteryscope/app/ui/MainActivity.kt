package com.batteryscope.app.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.batteryscope.app.battery.BatteryReader
import kotlin.math.roundToInt

class MainActivity : android.app.Activity() {
    private lateinit var reader: BatteryReader
    private var temperatureF = false
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
        val title = TextView(this).apply {
            text = "BatteryScope"
            textSize = 28f
            setPadding(0, 0, 0, 24)
        }
        root.addView(title)

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val labels = listOf(
            "Power (Watt)",
            "Current (Ampere)",
            "Voltage (V)",
            "Temperature",
            "Energy (Wh / Ah)",
            "Charge level",
            "Charging status",
            "Battery capacity (mAh)",
            "Remaining battery (mAh)",
            "Estimated capacity (mAh)",
            "Battery health"
        )
        values = labels.map { label ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 10, 0, 10)
            }
            row.addView(TextView(this).apply { text = label; textSize = 14f })
            TextView(this).also { value ->
                value.textSize = 20f
                value.gravity = Gravity.START
                row.addView(value)
                list.addView(row)
                value
            }
        }
        val tempButton = TextView(this).apply {
            text = "Temperature: °C"
            textSize = 14f
            setPadding(0, 20, 0, 20)
            setOnClickListener {
                temperatureF = !temperatureF
                text = if (temperatureF) "Temperature: °F" else "Temperature: °C"
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
        val temp = b.temperatureC?.let {
            if (temperatureF) "${((it * 9.0 / 5.0) + 32.0).roundToInt()} °F" else "%.1f °C".format(it)
        } ?: "Unavailable"
        val capacity = "Unavailable"
        val health = "Not calculated yet"
        val energy = if (b.energyWh != null && b.remainingMah != null) {
            "%.2f Wh / %.0f mAh".format(b.energyWh, b.remainingMah)
        } else "Unavailable"

        val rendered = listOf(
            b.powerW?.let { "%.2f W".format(it) } ?: "Unavailable",
            b.currentA?.let { "%.3f A".format(it) } ?: "Unavailable",
            b.voltageV?.let { "%.3f V".format(it) } ?: "Unavailable",
            temp,
            energy,
            "${b.levelPercent}%",
            if (b.charging) "Yes" else "No",
            capacity,
            b.remainingMah?.let { "%.0f mAh".format(it) } ?: "Unavailable",
            b.estimatedCapacityMah?.let { "%.0f mAh".format(it) } ?: "Learning / unavailable",
            health,
        )
        values.forEachIndexed { index, view -> view.text = rendered[index] }
    }
}
