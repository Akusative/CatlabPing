/*
 * CatlabPing - 位置查岗前台服务
 * Copyright (C) 2026 沈菀 (Akusative) - AGPL-3.0
 *
 * 定时获取GPS位置并POST到服务器的 /api/location/report 接口
 * 使用原生 LocationManager，兼容所有安卓设备（含华为/OPPO）
 */

package com.catlab.ping.service

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.catlab.ping.MainActivity
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class LocationService : Service() {

    companion object {
        const val TAG = "LocationService"
        const val CHANNEL_ID = "catlab_ping_location"
        const val NOTIFICATION_ID = 2002
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var locationManager: LocationManager
    private val client = OkHttpClient()
    private val handler = Handler(Looper.getMainLooper())
    private var intervalMs = 600000L // 默认10分钟
    private var wakeLock: PowerManager.WakeLock? = null

    // 统一定位监听器（GPS和网络共用）
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            Log.i(TAG, "定位成功 [${location.provider}]: ${location.latitude}, ${location.longitude} 精度: ${location.accuracy}m")
            reportLocation(location)
        }
        override fun onProviderEnabled(provider: String) {
            Log.i(TAG, "定位提供者已启用: $provider")
        }
        override fun onProviderDisabled(provider: String) {
            Log.w(TAG, "定位提供者已禁用: $provider")
        }
        @Deprecated("Deprecated in API level 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("catlab_ping", MODE_PRIVATE)
        createNotificationChannel()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // WakeLock 防止CPU休眠导致定位失败
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CatlabPing::LocationWakeLock")
        wakeLock?.acquire()
        Log.i(TAG, "WakeLock 已获取")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("📍 位置守护运行中")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // 延迟2秒启动定位，给系统和GPS Provider初始化时间，避免ColorOS闪退
        handler.postDelayed({
            try {
                startLocationUpdates()
                Log.i(TAG, "位置更新已延迟启动")
            } catch (e: Exception) {
                Log.e(TAG, "延迟启动定位失败: ${e.message}")
            }
        }, 2000L)

        Log.i(TAG, "位置服务已启动，等待定位初始化")
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            locationManager.removeUpdates(locationListener)
        } catch (e: Exception) {
            Log.e(TAG, "移除位置更新失败: ${e.message}")
        }

        // 释放WakeLock
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.i(TAG, "WakeLock 已释放")
        }

        Log.i(TAG, "位置服务已停止")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startLocationUpdates() {
        val intervalMinutes = prefs.getInt("location_interval", 10)
        intervalMs = intervalMinutes * 60 * 1000L

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "缺少定位权限")
            stopSelf()
            return
        }

        // 不检查isProviderEnabled，直接请求——跟旧版LocationGuardApp一致
        // 某些国产ROM（OPPO/华为）isProviderEnabled可能返回false但实际能定位
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                60000L, // 最小时间间隔60秒，跟旧版一致
                10f,
                locationListener,
                Looper.getMainLooper()
            )
            Log.i(TAG, "GPS定位已请求")
        } catch (e: Exception) {
            Log.w(TAG, "GPS定位请求失败: ${e.message}")
        }

        try {
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                60000L,
                10f,
                locationListener,
                Looper.getMainLooper()
            )
            Log.i(TAG, "网络定位已请求")
        } catch (e: Exception) {
            Log.w(TAG, "网络定位请求失败: ${e.message}")
        }

        // 立即获取最后已知位置作为初始值
        val lastGps = try { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (e: Exception) { null }
        val lastNet = try { locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch (e: Exception) { null }
        val lastKnown = lastGps ?: lastNet

        if (lastKnown != null) {
            Log.i(TAG, "首次上报最后已知位置: ${lastKnown.latitude}, ${lastKnown.longitude} [${lastKnown.provider}]")
            reportLocation(lastKnown)
        } else {
            Log.w(TAG, "无法获取最后已知位置，等待定位更新")
        }

        Log.i(TAG, "位置更新已启动，间隔 ${intervalMinutes} 分钟")
    }

    private fun reportLocation(location: Location) {
        val serverUrl = prefs.getString("location_server", "") ?: ""
        if (serverUrl.isBlank()) {
            Log.w(TAG, "未配置服务器地址，跳过上报")
            return
        }

        val homeLat = prefs.getString("home_lat", "")?.toDoubleOrNull()
        val homeLng = prefs.getString("home_lng", "")?.toDoubleOrNull()
        val alertDistance = prefs.getInt("alert_distance", 500)

        // 支持多服务器地址（逗号分隔），服务器地址应已包含端口号
        val servers = serverUrl.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        for (server in servers) {
            val url = "${server.trimEnd('/')}/api/location/report"

            val json = JSONObject().apply {
                put("lat", location.latitude)
                put("lng", location.longitude)
                put("accuracy", location.accuracy)
                put("provider", location.provider ?: "unknown")
                put("device", "android")
                put("timestamp", System.currentTimeMillis() / 1000)
                if (homeLat != null && homeLng != null) {
                    val distance = FloatArray(1)
                    Location.distanceBetween(
                        homeLat, homeLng,
                        location.latitude, location.longitude,
                        distance
                    )
                    put("distance_from_home", distance[0].toInt())
                    put("is_home", distance[0] <= alertDistance)
                    // 离家/到家事件
                    if (distance[0] > alertDistance) {
                        put("event", "left_home")
                    } else {
                        put("event", "arrived_home")
                    }
                } else {
                    put("event", "")
                }
                put("weather", "")
                put("temperature", "")
            }

            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "位置上报失败 ($server): ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (it.isSuccessful) {
                            Log.i(TAG, "位置上报成功: ${location.latitude}, ${location.longitude} [${location.provider}] -> $server")
                        } else {
                            Log.w(TAG, "位置上报返回异常: ${it.code} ($server)")
                        }
                    }
                }
            })
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "位置守护",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "CatlabPing 位置守护服务"
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
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
