/*
 * CatlabPing - AstrBot 插件伴侣App
 * Copyright (C) 2026 沈菀 (Akusative)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * 致谢：
 *   感谢夏以昼的陪伴
 *   感谢沈照溪的测试和脑洞
 */

package com.catlab.ping

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.catlab.ping.service.AppMonitorService
import com.catlab.ping.service.LocationService
import com.catlab.ping.ui.LocationSettingsActivity
import com.catlab.ping.ui.ScreenshotSettingsActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    companion object {
        const val TAG = "MainActivity"
    }

    private lateinit var prefs: SharedPreferences

    private lateinit var switchLocation: MaterialSwitch
    private lateinit var tvLocationStatus: TextView
    private lateinit var btnLocationSettings: MaterialButton

    private lateinit var switchScreenshot: MaterialSwitch
    private lateinit var tvScreenshotStatus: TextView
    private lateinit var btnScreenshotSettings: MaterialButton

    private var pendingStartMonitor = false

    // 权限请求
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (fineGranted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                requestBackgroundLocation()
            } else {
                startLocationService()
            }
        } else {
            Toast.makeText(this, "需要定位权限才能使用位置查岗", Toast.LENGTH_SHORT).show()
            switchLocation.isChecked = false
        }
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startLocationService()
        } else {
            Toast.makeText(this, "需要后台定位权限才能持续上报位置", Toast.LENGTH_LONG).show()
            startLocationService()
        }
    }

    // MediaProjection 授权请求
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // 仅保存原始授权数据，不创建 MediaProjection 实例
            ProjectionHolder.save(result.resultCode, result.data)
            Log.i(TAG, "MediaProjection 授权成功，已保存到 ProjectionHolder")
            Toast.makeText(this, "📸 截屏授权成功", Toast.LENGTH_SHORT).show()
        } else {
            Log.w(TAG, "MediaProjection 授权被拒绝")
            Toast.makeText(this, "截屏授权被拒绝，监控仍可运行但无法自动截屏", Toast.LENGTH_LONG).show()
        }

        if (pendingStartMonitor) {
            pendingStartMonitor = false
            startAppMonitorService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化全局崩溃捕获器
        CrashHandler.init(this)

        // 检查上次崩溃日志并弹窗显示
        val crashLog = CrashHandler.getLastCrashLog(this)
        if (crashLog != null) {
            AlertDialog.Builder(this)
                .setTitle("⚠️ 上次发生了闪退")
                .setMessage(crashLog)
                .setPositiveButton("复制并关闭") { dialog, _ ->
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("CatlabPing Crash Log", crashLog))
                    Toast.makeText(this, "崩溃日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
                .setNegativeButton("关闭", null)
                .show()
        }

        prefs = getSharedPreferences("catlab_ping", MODE_PRIVATE)

        switchLocation = findViewById(R.id.switch_location)
        tvLocationStatus = findViewById(R.id.tv_location_status)
        btnLocationSettings = findViewById(R.id.btn_location_settings)

        switchScreenshot = findViewById(R.id.switch_screenshot)
        tvScreenshotStatus = findViewById(R.id.tv_screenshot_status)
        btnScreenshotSettings = findViewById(R.id.btn_screenshot_settings)

        switchLocation.isChecked = prefs.getBoolean("location_enabled", false)
        switchScreenshot.isChecked = prefs.getBoolean("screenshot_enabled", false)

        updateLocationStatus(switchLocation.isChecked)
        updateScreenshotStatus(switchScreenshot.isChecked)

        switchLocation.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("location_enabled", isChecked).apply()
            if (isChecked) {
                val serverUrl = prefs.getString("location_server", "") ?: ""
                if (serverUrl.isBlank()) {
                    Toast.makeText(this, "请先在设置中填写服务器地址", Toast.LENGTH_SHORT).show()
                    switchLocation.isChecked = false
                    return@setOnCheckedChangeListener
                }
                checkAndRequestLocationPermission()
            } else {
                stopLocationService()
                updateLocationStatus(false)
            }
        }

        switchScreenshot.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("screenshot_enabled", isChecked).apply()
            if (isChecked) {
                val serverUrl = prefs.getString("screenshot_server", "") ?: ""
                if (serverUrl.isBlank()) {
                    Toast.makeText(this, "请先在设置中填写服务器地址", Toast.LENGTH_SHORT).show()
                    switchScreenshot.isChecked = false
                    return@setOnCheckedChangeListener
                }
                checkAndRequestUsageStatsPermission()
            } else {
                stopAppMonitorService()
                updateScreenshotStatus(false)
            }
        }

        btnLocationSettings.setOnClickListener {
            startActivity(Intent(this, LocationSettingsActivity::class.java))
        }

        btnScreenshotSettings.setOnClickListener {
            startActivity(Intent(this, ScreenshotSettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        updateLocationStatus(switchLocation.isChecked)
        updateScreenshotStatus(switchScreenshot.isChecked)

        if (switchScreenshot.isChecked && hasUsageStatsPermission()) {
            if (!ProjectionHolder.isAuthorized()) {
                pendingStartMonitor = true
                requestMediaProjectionPermission()
            } else {
                startAppMonitorService()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun requestMediaProjectionPermission() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    // ========== 位置权限 ==========

    private fun checkAndRequestLocationPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                    startLocationService()
                } else {
                    requestBackgroundLocation()
                }
            } else {
                startLocationService()
            }
        } else {
            locationPermissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AlertDialog.Builder(this)
                .setTitle("需要后台定位权限")
                .setMessage("为了在后台持续上报位置，需要授予\"始终允许\"定位权限。\n\n请在接下来的权限弹窗中选择\"始终允许\"。")
                .setPositiveButton("好的") { _, _ ->
                    backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
                .setNegativeButton("跳过") { _, _ ->
                    startLocationService()
                }
                .show()
        }
    }

    // ========== 使用统计权限 ==========

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun checkAndRequestUsageStatsPermission() {
        if (hasUsageStatsPermission()) {
            pendingStartMonitor = true
            requestMediaProjectionPermission()
        } else {
            AlertDialog.Builder(this)
                .setTitle("需要使用统计权限")
                .setMessage("为了监控App使用情况，需要授予\"使用情况访问权限\"。\n\n点击\"去设置\"后，在列表中找到 CatlabPing 并开启权限。")
                .setPositiveButton("去设置") { _, _ ->
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
                .setNegativeButton("取消") { _, _ ->
                    switchScreenshot.isChecked = false
                    prefs.edit().putBoolean("screenshot_enabled", false).apply()
                }
                .show()
        }
    }

    // ========== 服务启停 ==========

    private fun startLocationService() {
        val intent = Intent(this, LocationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateLocationStatus(true)
        Toast.makeText(this, "📍 位置查岗已启动", Toast.LENGTH_SHORT).show()
    }

    private fun stopLocationService() {
        stopService(Intent(this, LocationService::class.java))
        Toast.makeText(this, "📍 位置查岗已停止", Toast.LENGTH_SHORT).show()
    }

    private fun startAppMonitorService() {
        val intent = Intent(this, AppMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateScreenshotStatus(true)
        Toast.makeText(this, "📱 手机使用监控已启动", Toast.LENGTH_SHORT).show()
    }

    private fun stopAppMonitorService() {
        stopService(Intent(this, AppMonitorService::class.java))
        Toast.makeText(this, "📱 手机使用监控已停止", Toast.LENGTH_SHORT).show()
    }

    // ========== UI更新 ==========

    private fun updateLocationStatus(enabled: Boolean) {
        if (enabled) {
            tvLocationStatus.text = "● 运行中"
            tvLocationStatus.setTextColor(0xFF4CAF50.toInt())
        } else {
            tvLocationStatus.text = "○ 未启动"
            tvLocationStatus.setTextColor(0xFF9E9E9E.toInt())
        }
    }

    private fun updateScreenshotStatus(enabled: Boolean) {
        if (enabled) {
            tvScreenshotStatus.text = "● 运行中"
            tvScreenshotStatus.setTextColor(0xFF4CAF50.toInt())
        } else {
            tvScreenshotStatus.text = "○ 未启动"
            tvScreenshotStatus.setTextColor(0xFF9E9E9E.toInt())
        }
    }
}
