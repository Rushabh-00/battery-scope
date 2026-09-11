package com.batteryscope.app.monitor

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

object BatteryMonitoringController {
    fun start(context: Context) {
        val appContext = context.applicationContext
        ContextCompat.startForegroundService(appContext, Intent(appContext, BatteryMonitoringService::class.java))
    }

    fun stop(context: Context) {
        context.applicationContext.stopService(Intent(context.applicationContext, BatteryMonitoringService::class.java))
    }
}
