/*
 * CatlabPing - App使用监控前台服务（含截屏功能）
 * Copyright (C) 2026 沈菀 (Akusative) - AGPL-3.0
 *
 * 使用 UsageStatsManager 轮询前台App变化，
 * 检测到切换时自动POST到服务器的 /app/report 接口。
 * 服务器返回 screenshot=true 时，从常驻的 VirtualDisplay 中取一帧截屏上传。
 *
 * 核心策略：MediaProjection + VirtualDisplay 在服务启动时创建，一直保持活跃，
 * 截屏时只从 ImageReader 取图，不创建/销毁底层资源，避免 Android 14 的一次性 token 限制。
 *
 * [调试版] 关键节点增加 Toast 提示
 */

package com.catlab.ping.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.app.usage.UsageStatsManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.catlab.ping.MainActivity
import com.catlab.ping.ProjectionHolder
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException

class AppMonitorService : Service() {

    companion object {
        const val TAG = "AppMonitorService"
        const val CHANNEL_ID = "catlab_ping_app_monitor"
        const val NOTIFICATION_ID = 2001
        const val POLL_INTERVAL = 3000L
    }

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient()
    private var lastForegroundApp = ""
    private var isRunning = false
    private var isCapturing = false

    // 常驻截屏资源（一直保持活跃）
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 0
    private var screenHeight = 0

    private val monitoredApps = mutableMapOf<String, String>()

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            checkForegroundApp()
            handler.postDelayed(this, POLL_INTERVAL)
        }
    }

    private fun toast(msg: String) {
        handler.post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("catlab_ping", MODE_PRIVATE)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        parseMonitoredApps()

        // 启动前台服务
        val notification = buildNotification("📱 手机使用监控运行中")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceType = if (ProjectionHolder.isAuthorized()) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // 创建 MediaProjection 并初始化常驻 VirtualDisplay
        if (ProjectionHolder.isAuthorized() && virtualDisplay == null) {
            initPersistentCapture()
        }

        isRunning = true
        handler.post(pollRunnable)

        val screenshotReady = virtualDisplay != null
        Log.i(TAG, "App监控服务已启动，监控 ${monitoredApps.size} 个App，截屏${if (screenshotReady) "就绪" else "未授权"}")
        return START_STICKY
    }

    /**
     * 初始化常驻截屏资源：创建 MediaProjection → 注册 Callback → 创建 VirtualDisplay
     * 这些资源在服务运行期间一直保持活跃，不会被销毁
     */
    private fun initPersistentCapture() {
        val projection = ProjectionHolder.getOrCreateProjection(this)
        if (projection == null) {
            Log.w(TAG, "无法创建 MediaProjection 实例")
            return
        }

        try {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)

            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            val screenDensity = metrics.densityDpi

            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

            virtualDisplay = projection.createVirtualDisplay(
                "CatlabPingCapture",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface, null, handler
            )

            Log.i(TAG, "常驻 VirtualDisplay 已创建 (${screenWidth}x${screenHeight})")
            toast("📸 截屏功能就绪")

        } catch (e: Exception) {
            Log.e(TAG, "初始化截屏资源失败: ${e.message}")
            toast("📸 截屏初始化失败: ${e.message}")
            imageReader?.close()
            imageReader = null
        }
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(pollRunnable)

        // 服务停止时才释放截屏资源
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null

        Log.i(TAG, "App监控服务已停止，截屏资源已释放")
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
            val beginTime = endTime - 10000

            val usageStats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, beginTime, endTime
            )

            if (usageStats.isNullOrEmpty()) return

            val recentApp = usageStats
                .filter { it.lastTimeUsed > 0 }
                .maxByOrNull { it.lastTimeUsed }

            if (recentApp != null && recentApp.packageName != lastForegroundApp) {
                val packageName = recentApp.packageName
                if (packageName == applicationContext.packageName) return

                lastForegroundApp = packageName

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

        if (serverUrl.isBlank()) return

        // 支持多服务器地址（逗号分隔）
        val servers = serverUrl.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        for (server in servers) {
            val url = "${server.trimEnd(':')}:${port}/app/report"

            val json = JSONObject().apply {
                put("app_name", appName)
                put("device", deviceName)
            }

            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(url).post(body).build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "上报失败 ($appName -> $server): ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    val responseBody = response.body?.string() ?: ""
                    val code = response.code
                    response.close()

                    if (code in 200..299) {
                        Log.i(TAG, "上报成功: $appName -> $deviceName ($server)")
                        try {
                            if (responseBody.isNotEmpty()) {
                                val respJson = JSONObject(responseBody)
                                // 处理服务器推送消息
                                if (respJson.has("push_messages")) {
                                    val pushMessages = respJson.getJSONArray("push_messages")
                                    for (i in 0 until pushMessages.length()) {
                                        val pushMsg = pushMessages.getJSONObject(i)
                                        val title = pushMsg.optString("title", "CatlabPing")
                                        val msgBody = pushMsg.optString("body", "")
                                        if (msgBody.isNotEmpty()) {
                                            showPushNotification(title, msgBody)
                                        }
                                    }
                                }
                                if (respJson.optBoolean("screenshot", false)) {
                                    Log.i(TAG, "服务器请求截屏！")
                                    // 检查截屏排除名单
                                    if (!isAppExcludedFromScreenshot(lastForegroundApp)) {
                                        performScreenCapture()
                                    } else {
                                        Log.i(TAG, "当前App在截屏排除名单中，跳过截屏")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "解析服务器返回失败: ${e.message}")
                        }
                    }
                }
            })
        }
    }

    /**
     * 检查当前App是否在截屏排除名单中
     */
    private fun isAppExcludedFromScreenshot(packageName: String): Boolean {
        val excludeList = prefs.getString("screenshot_exclude_apps", "") ?: ""
        if (excludeList.isBlank()) return false
        for (line in excludeList.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val pkg = if ("|" in trimmed) trimmed.split("|", limit = 2)[1].trim() else trimmed
            if (pkg == packageName) return true
        }
        return false
    }

    /**
     * 弹出推送通知
     */
    private fun showPushNotification(title: String, body: String) {
        handler.post {
            try {
                val channelId = "catlab_ping_push"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel = NotificationChannel(
                        channelId,
                        "查岗推送",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "CatlabPing 查岗推送通知"
                        enableVibration(true)
                    }
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.createNotificationChannel(channel)
                }

                val intent = Intent(this@AppMonitorService, MainActivity::class.java)
                val pendingIntent = PendingIntent.getActivity(
                    this@AppMonitorService, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val notification = NotificationCompat.Builder(this@AppMonitorService, channelId)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .build()

                val notificationId = (System.currentTimeMillis() % 100000).toInt() + 3000
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(notificationId, notification)
                Log.i(TAG, "推送通知已弹出: $body")
            } catch (e: Exception) {
                Log.e(TAG, "弹出推送通知失败: ${e.message}")
            }
        }
    }

    // ========== 截屏功能 ==========

    /**
     * 从常驻的 VirtualDisplay/ImageReader 中取一帧截屏
     * 先弹出3秒倒计时通知，倒计时结束后再截图上传，避免截到黑屏
     */
    private fun performScreenCapture() {
        if (isCapturing) {
            Log.w(TAG, "正在截屏中，跳过本次请求")
            return
        }

        // 检查截屏开关
        val captureEnabled = prefs.getBoolean("screenshot_capture_enabled", true)
        if (!captureEnabled) {
            Log.i(TAG, "截屏功能已关闭，跳过")
            return
        }

        if (virtualDisplay == null || imageReader == null) {
            Log.w(TAG, "截屏资源未初始化，跳过")
            toast("📸 截屏跳过: 需要重新授权")
            return
        }

        isCapturing = true

        // 倒计时3秒后截图
        Log.i(TAG, "截屏倒计时开始")
        showCountdownNotification(3)
        handler.postDelayed({
            showCountdownNotification(2)
            handler.postDelayed({
                showCountdownNotification(1)
                handler.postDelayed({
                    // 倒计时结束，清除倒计时通知
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.cancel(NOTIFICATION_ID + 100)
                    Log.i(TAG, "倒计时结束，开始截屏")
                    // 延迟200ms让ImageReader缓冲区刷新到最新帧
                    handler.postDelayed({
                        grabFrame()
                    }, 200)
                }, 1000)
            }, 1000)
        }, 1000)
    }

    /**
     * 显示截屏倒计时通知
     */
    private fun showCountdownNotification(seconds: Int) {
        val channelId = "catlab_ping_countdown"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "截屏倒计时",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "CatlabPing 截屏倒计时提示"
                enableVibration(true)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("📸 截屏倒计时")
            .setContentText("${seconds} 秒后截屏...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setSilent(false)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID + 100, notification)
        toast("📸 截屏倒计时: ${seconds}")
    }

    private var grabRetryCount = 0

    private fun grabFrame() {
        try {
            val image = imageReader?.acquireLatestImage()
            if (image == null) {
                // 重试最多3次，每次间隔500ms，等待 VirtualDisplay 刷新缓冲区
                if (grabRetryCount < 3) {
                    grabRetryCount++
                    Log.i(TAG, "获取图像为空，第${grabRetryCount}次重试...")
                    handler.postDelayed({ grabFrame() }, 500)
                    return
                }
                Log.w(TAG, "获取图像为空，已重试3次仍失败")
                toast("📸 截屏失败: 获取图像为空")
                grabRetryCount = 0
                isCapturing = false
                return
            }
            grabRetryCount = 0

            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
            if (croppedBitmap != bitmap) {
                bitmap.recycle()
            }

            val outputStream = ByteArrayOutputStream()
            croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            val imageBytes = outputStream.toByteArray()
            croppedBitmap.recycle()

            val sizeKb = imageBytes.size / 1024
            Log.i(TAG, "截屏成功，图片大小: ${sizeKb}KB")
            toast("📸 截屏成功 ${sizeKb}KB")

            uploadScreenshot(imageBytes)

        } catch (e: Exception) {
            Log.e(TAG, "截屏失败: ${e.message}")
            toast("📸 截屏异常: ${e.message}")
            isCapturing = false
        }
    }

    private fun uploadScreenshot(imageBytes: ByteArray) {
        val serverUrl = prefs.getString("screenshot_server", "") ?: ""
        val port = prefs.getInt("screenshot_port", 2313)
        val deviceName = prefs.getString("device_name", "Android") ?: "Android"

        if (serverUrl.isBlank()) {
            isCapturing = false
            return
        }

        // 支持多服务器地址（逗号分隔）
        val servers = serverUrl.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        var uploadCount = 0
        val totalServers = servers.size

        for (server in servers) {
            val url = "${server.trimEnd(':')}:${port}/screenshot/upload"

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("device", deviceName)
                .addFormDataPart(
                    "screenshot", "screenshot_${System.currentTimeMillis()}.jpg",
                    imageBytes.toRequestBody("image/jpeg".toMediaType())
                )
                .build()

            val request = Request.Builder().url(url).post(body).build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "上传失败 ($server): ${e.message}")
                    toast("📸 上传失败 ($server): ${e.message}")
                    synchronized(this@AppMonitorService) {
                        uploadCount++
                        if (uploadCount >= totalServers) isCapturing = false
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (it.isSuccessful) {
                            Log.i(TAG, "截屏上传成功！($server)")
                        } else {
                            Log.w(TAG, "上传异常 ($server): HTTP ${it.code}")
                        }
                    }
                    synchronized(this@AppMonitorService) {
                        uploadCount++
                        if (uploadCount >= totalServers) {
                            isCapturing = false
                            toast("📸 上传完成")
                        }
                    }
                }
            })
        }
    }

    // ========== 通知 ==========

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
