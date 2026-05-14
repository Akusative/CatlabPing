/*
 * CatlabPing - 截屏前台服务
 * Copyright (C) 2026 沈菀 (Akusative) - AGPL-3.0
 *
 * 从 ProjectionHolder 获取或复用 MediaProjection 实例，
 * 执行截屏并上传到服务器。
 * Callback 已在 ProjectionHolder 创建实例时注册，此处不再重复注册。
 *
 * [调试版] 关键节点增加 Toast 提示
 */

package com.catlab.ping.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import java.io.ByteArrayOutputStream
import java.io.IOException

class ScreenCaptureService : Service() {

    companion object {
        const val TAG = "ScreenCaptureService"
        const val CHANNEL_ID = "catlab_ping_screen_capture"
        const val NOTIFICATION_ID = 2002
    }

    private lateinit var prefs: SharedPreferences
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val client = OkHttpClient()
    private val handler = Handler(Looper.getMainLooper())

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
        if (!ProjectionHolder.isAuthorized()) {
            Log.e(TAG, "没有可用的 MediaProjection 授权")
            toast("📸 截屏失败: 没有授权")
            stopSelf()
            return START_NOT_STICKY
        }

        // 先启动前台服务
        val notification = buildNotification("📸 正在截屏...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // 获取或复用 MediaProjection 实例（Callback 已在 ProjectionHolder 内注册）
        val projection = ProjectionHolder.getOrCreateProjection(this)
        if (projection == null) {
            Log.e(TAG, "无法获取 MediaProjection 实例")
            toast("📸 截屏失败: MediaProjection 为空")
            stopSelf()
            return START_NOT_STICKY
        }

        Log.i(TAG, "MediaProjection 就绪，开始截屏")
        toast("📸 开始截屏...")

        handler.postDelayed({
            captureScreen(projection)
        }, 500)

        return START_NOT_STICKY
    }

    private fun captureScreen(projection: MediaProjection) {
        try {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)

            val screenWidth = metrics.widthPixels
            val screenHeight = metrics.heightPixels
            val screenDensity = metrics.densityDpi

            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

            virtualDisplay = projection.createVirtualDisplay(
                "CatlabPingCapture",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface, null, handler
            )

            handler.postDelayed({
                processImage(screenWidth, screenHeight)
            }, 300)

        } catch (e: Exception) {
            Log.e(TAG, "截屏失败: ${e.message}")
            toast("📸 截屏异常: ${e.message}")
            cleanupCapture()
        }
    }

    private fun processImage(width: Int, height: Int) {
        try {
            val image = imageReader?.acquireLatestImage()
            if (image == null) {
                Log.w(TAG, "未能获取到屏幕图像")
                toast("📸 截屏失败: 获取图像为空")
                cleanupCapture()
                return
            }

            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width

            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
            if (croppedBitmap != bitmap) {
                bitmap.recycle()
            }

            val outputStream = ByteArrayOutputStream()
            croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            val imageBytes = outputStream.toByteArray()
            croppedBitmap.recycle()

            val sizeKb = imageBytes.size / 1024
            Log.i(TAG, "截屏成功，图片大小: ${sizeKb}KB")
            toast("📸 截屏成功 ${sizeKb}KB，正在上传...")

            uploadScreenshot(imageBytes)

        } catch (e: Exception) {
            Log.e(TAG, "处理截屏图像失败: ${e.message}")
            toast("📸 处理图像失败: ${e.message}")
            cleanupCapture()
        }
    }

    private fun uploadScreenshot(imageBytes: ByteArray) {
        val serverUrl = prefs.getString("screenshot_server", "") ?: ""
        val port = prefs.getInt("screenshot_port", 2313)
        val deviceName = prefs.getString("device_name", "Android") ?: "Android"

        if (serverUrl.isBlank()) {
            Log.w(TAG, "未配置服务器地址，跳过上传")
            toast("📸 上传跳过: 未配置服务器地址")
            cleanupCapture()
            return
        }

        val url = "${serverUrl.trimEnd('/')}:${port}/screenshot/upload"
        Log.i(TAG, "上传地址: $url")

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("device", deviceName)
            .addFormDataPart(
                "screenshot", "screenshot_${System.currentTimeMillis()}.jpg",
                imageBytes.toRequestBody("image/jpeg".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "截屏上传失败: ${e.message}")
                toast("📸 上传失败: ${e.message}")
                cleanupCapture()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        Log.i(TAG, "截屏上传成功！")
                        toast("📸 上传成功！")
                    } else {
                        Log.w(TAG, "截屏上传返回异常: ${it.code}")
                        toast("📸 上传异常: HTTP ${it.code}")
                    }
                }
                cleanupCapture()
            }
        })
    }

    /**
     * 只清理本次截屏的资源，不销毁 MediaProjection
     */
    private fun cleanupCapture() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        stopSelf()
    }

    override fun onDestroy() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "截屏服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "CatlabPing 截屏服务"
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
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(false)
            .setSilent(true)
            .build()
    }
}
