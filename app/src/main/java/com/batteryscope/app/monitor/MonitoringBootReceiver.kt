package com.batteryscope.app.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.batteryscope.app.settings.AppSettings

class MonitoringBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val settings = AppSettings(context)
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                if (settings.notificationEnabled && settings.startOnBoot) {
                    runCatching { BatteryMonitoringController.start(context) }
                }
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (settings.notificationEnabled) {
                    runCatching { BatteryMonitoringController.start(context) }
                }
            }
        }
    }
}
