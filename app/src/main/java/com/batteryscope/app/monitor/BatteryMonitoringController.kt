package com.batteryscope.app.monitor

import android.content.Context
import android.content.Intent
import android.os.Build

object BatteryMonitoringController {
    fun start(context: Context) {
        val appContext = context.applicationContext
        val intent = Intent(appContext, BatteryMonitoringService::class.java)
        if (Build.VERSION.SDK_INT >= 26) appContext.startForegroundService(intent) else appContext.startService(intent)
    }

    fun stop(context: Context) {
        context.applicationContext.stopService(Intent(context.applicationContext, BatteryMonitoringService::class.java))
    }
}
