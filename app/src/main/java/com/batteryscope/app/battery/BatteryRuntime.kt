package com.batteryscope.app.battery

import android.content.Context

/** Single in-process telemetry source used by the live UI and optional background monitor. */
object BatteryRuntime {
    private var reader: BatteryReader? = null
    private var latest: BatterySnapshot? = null

    @Synchronized
    fun read(context: Context): BatterySnapshot {
        val value = (reader ?: BatteryReader(context.applicationContext).also { reader = it }).read()
        latest = value
        return value
    }

    @Synchronized
    fun latest(): BatterySnapshot? = latest
}
