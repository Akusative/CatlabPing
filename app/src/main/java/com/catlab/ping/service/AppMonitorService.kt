/*
 * CatlabPing - App使用监控前台服务
 * Copyright (C) 2026 沈菀 (Akusative) - AGPL-3.0
 *
 * 使用 UsageStatsManager 轮询前台App变化，
 * 检测到切换时自动POST到服务器的 /app/report 接口
 */

package com.catlab.ping.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.app.usage.UsageStatsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.catlab.ping.MainActivity
import com.catlab.ping.R
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class AppMonitorService : Service() {

    companion object {
        const val TAG = "AppMonitorService"
        const val CHANNEL_ID = "catlab_ping_app_monitor"
        const val NOTIFICATION_ID = 2001
        const val POLL_INTERVAL = 3000L // 3秒轮询一次
    }

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient()
    private var lastForegroundApp = ""
    private var isRunning = false

    // 监控App映射表：显示名称 -> 包名
    private val monitoredApps = mutableMapOf<String, String>()

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            checkForegroundApp()
            handler.postDelayed(this, POLL_INTERVAL)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("catlab_ping", MODE_PRIVATE)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 解析监控App列表
        parseMonitoredApps()

        // 启动前台服务
        val notification = buildNotification("📱 手机使用监控运行中")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // 开始轮询
        isRunning = true
        handler.post(pollRunnable)

        Log.i(TAG, "App监控服务已启动，监控 ${monitoredApps.size} 个App")
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(pollRunnable)
        Log.i(TAG, "App监控服务已停止")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun parseMonitoredApps() {
        monitoredApps.clear()
        val raw = prefs.getString("monitor_apps", "") ?: ""
        if (raw.isBlank()) return

        for (line in raw.split("\n")) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if ("|" in trimmed) {
                val parts = trimmed.split("|", limit = 2)
                val displayName = parts[0].trim()
                val packageName = parts[1].trim()
                if (displayName.isNotEmpty() && packageName.isNotEmpty()) {
                    monitoredApps[packageName] = displayName
                }
            }
        }
        Log.i(TAG, "已加载 ${monitoredApps.size} 个监控App: ${monitoredApps.values.joinToString(", ")}")
    }

    private fun checkForegroundApp() {
        try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val endTime = System.currentTimeMillis()
            val beginTime = endTime - 10000 // 最近10秒

            val usageStats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, beginTime, endTime
            )

            if (usageStats.isNullOrEmpty()) {
                Log.d(TAG, "无法获取使用统计，请检查权限")
                return
            }

            // 找到最近使用的App
            val recentApp = usageStats
                .filter { it.lastTimeUsed > 0 }
                .maxByOrNull { it.lastTimeUsed }

            if (recentApp != null && recentApp.packageName != lastForegroundApp) {
                val packageName = recentApp.packageName

                // 跳过自己
                if (packageName == applicationContext.packageName) return

                lastForegroundApp = packageName

                // 如果配置了监控列表，只上报列表中的App
                // 如果没配置（列表为空），上报所有App
                if (monitoredApps.isEmpty() || monitoredApps.containsKey(packageName)) {
                    val displayName = monitoredApps[packageName]
                        ?: getAppDisplayName(packageName)
                    reportAppUsage(displayName)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查前台App失败: ${e.message}")
        }
    }

    private fun getAppDisplayName(packageName: String): String {
        return try {
            val pm = applicationContext.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    private fun reportAppUsage(appName: String) {
        val serverUrl = prefs.getString("screenshot_server", "") ?: ""
        val port = prefs.getInt("screenshot_port", 2313)
        val deviceName = prefs.getString("device_name", "Android") ?: "Android"

        if (serverUrl.isBlank()) {
            Log.w(TAG, "未配置服务器地址，跳过上报")
            return
        }

        val url = "${serverUrl.trimEnd('/')}:${port}/app/report"

        val json = JSONObject().apply {
            put("app_name", appName)
            put("device", deviceName)
        }

        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "上报失败 ($appName): ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        Log.i(TAG, "上报成功: $appName -> $deviceName")
                    } else {
                        Log.w(TAG, "上报返回异常 ($appName): ${it.code}")
                    }
                }
            }
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "手机使用监控",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "CatlabPing 手机使用监控服务"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CatlabPing")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
