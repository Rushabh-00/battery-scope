package com.batteryscope.app.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.batteryscope.app.settings.AppSettings
import java.io.File

class BatteryReader(context: Context) {
    private val appContext = context.applicationContext
    private val batteryManager = appContext.getSystemService(BatteryManager::class.java)
    private val settings = AppSettings(appContext)
    private val capacityReader = BatteryCapacityReader()
    private val capacityEstimator = CapacityEstimator(appContext)

    fun read(): BatterySnapshot {
        val intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val levelPercent = if (scale > 0) ((level * 100f) / scale).toInt().coerceIn(0, 100) else 0
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val voltageV = voltageMv.takeIf { it > 0 }?.div(1000.0)
        val tempTenthsC = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val temperatureC = tempTenthsC?.takeIf { it != Int.MIN_VALUE }?.div(10.0)

        val remainingMah = readChargeCounterMah()
        val currentA = CurrentReader(batteryManager, settings.invertChargingPolarity).readAmps()
        val capacity = capacityReader.read(remainingMah, levelPercent, voltageV)
        val estimatedCapacityMah = capacityEstimator.estimate(
            remainingMah = remainingMah,
            levelPercent = levelPercent,
            directFullMah = capacity.fullMah ?: capacity.designMah,
        )

        val powerW = if (currentA != null && voltageV != null) kotlin.math.abs(currentA) * voltageV else null
        val energyWh = if (remainingMah != null && voltageV != null) remainingMah / 1000.0 * voltageV else null

        return BatterySnapshot(
            levelPercent = levelPercent,
            charging = charging,
            voltageV = voltageV,
            currentA = currentA,
            temperatureC = temperatureC,
            remainingMah = remainingMah,
            batteryCapacityMah = capacity.designMah,
            estimatedCapacityMah = estimatedCapacityMah,
            powerW = powerW,
            energyWh = energyWh,
        )
    }

    private fun readChargeCounterMah(): Double? {
        val microAh = batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            ?: Long.MIN_VALUE
        return microAh.takeIf { it > 0 }?.toDouble()?.div(1000.0)
    }
}

class BatteryCapacityReader {
    data class Result(
        val designMah: Double?,
        val fullMah: Double?,
    )

    fun read(remainingMah: Double?, levelPercent: Int, voltageV: Double?): Result {
        val designMah = firstReadableMah(DESIGN_NAMES)
        val fullMah = firstReadableMah(FULL_NAMES)

        // Some devices expose energy capacity instead of charge capacity.
        val designFromEnergy = if (designMah == null && voltageV != null && voltageV > 0.0) {
            firstReadableEnergyMah(ENERGY_DESIGN_NAMES, voltageV)
        } else null
        val fullFromEnergy = if (fullMah == null && voltageV != null && voltageV > 0.0) {
            firstReadableEnergyMah(ENERGY_FULL_NAMES, voltageV)
        } else null

        // Do not use a guessed fixed battery size. A measured estimate is handled separately.
        return Result(
            designMah = designMah ?: designFromEnergy,
            fullMah = fullMah ?: fullFromEnergy,
        )
    }

    private fun firstReadableMah(names: List<String>): Double? {
        val roots = powerSupplyRoots()
        for (root in roots) {
            for (name in names) {
                val value = readMah(File(root, name))
                if (value != null) return value
            }
        }
        return null
    }

    private fun firstReadableEnergyMah(names: List<String>, voltageV: Double): Double? {
        val roots = powerSupplyRoots()
        for (root in roots) {
            for (name in names) {
                val raw = File(root, name).takeIf { it.isFile && it.canRead() }
                    ?.readText()?.trim()?.toDoubleOrNull() ?: continue
                if (raw <= 0.0) continue
                val microWh = raw.takeIf { it < 1_000_000_000.0 } ?: continue
                val mah = (microWh / 1000.0) / voltageV
                if (mah in MIN_MAH..MAX_MAH) return mah
            }
        }
        return null
    }

    private fun readMah(file: File): Double? {
        val raw = file.takeIf { it.isFile && it.canRead() }
            ?.readText()?.trim()?.toDoubleOrNull() ?: return null
        if (raw <= 0.0) return null
        val mah = when {
            raw > 1_000_000.0 -> raw / 1000.0
            raw > 30_000.0 -> raw / 1000.0
            else -> raw
        }
        return mah.takeIf { it in MIN_MAH..MAX_MAH }
    }

    private fun powerSupplyRoots(): List<File> {
        val root = File("/sys/class/power_supply")
        return root.listFiles()?.filter { it.isDirectory && it.canRead() } ?: emptyList()
    }

    companion object {
        private const val MIN_MAH = 100.0
        private const val MAX_MAH = 30_000.0
        private val DESIGN_NAMES = listOf("charge_full_design")
        private val FULL_NAMES = listOf("charge_full")
        private val ENERGY_DESIGN_NAMES = listOf("energy_full_design")
        private val ENERGY_FULL_NAMES = listOf("energy_full")
    }
}
