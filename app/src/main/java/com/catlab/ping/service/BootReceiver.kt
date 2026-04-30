/*
 * CatlabPing - 开机自启广播接收器
 * Copyright (C) 2026 沈菀 (Akusative) - AGPL-3.0
 *
 * 开机后自动恢复之前开启的服务
 */

package com.catlab.ping.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("catlab_ping", Context.MODE_PRIVATE)

        // 恢复位置查岗服务
        if (prefs.getBoolean("location_enabled", false)) {
            Log.i(TAG, "开机自启：恢复位置查岗服务")
            val locationIntent = Intent(context, LocationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(locationIntent)
            } else {
                context.startService(locationIntent)
            }
        }

        // 恢复App监控服务
        if (prefs.getBoolean("screenshot_enabled", false)) {
            Log.i(TAG, "开机自启：恢复App监控服务")
            val monitorIntent = Intent(context, AppMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(monitorIntent)
            } else {
                context.startService(monitorIntent)
            }
        }
    }
}
