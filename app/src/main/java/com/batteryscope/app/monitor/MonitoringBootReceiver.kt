package com.batteryscope.app.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.batteryscope.app.settings.AppSettings

class MonitoringBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (AppSettings(context).backgroundMonitoringEnabled) {
            runCatching { BatteryMonitoringController.start(context) }
        }
    }
}
