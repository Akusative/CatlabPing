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
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
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

    private lateinit var prefs: SharedPreferences

    private lateinit var switchLocation: MaterialSwitch
    private lateinit var tvLocationStatus: TextView
    private lateinit var btnLocationSettings: MaterialButton

    private lateinit var switchScreenshot: MaterialSwitch
    private lateinit var tvScreenshotStatus: TextView
    private lateinit var btnScreenshotSettings: MaterialButton

    // 权限请求
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (fineGranted) {
            // 请求后台定位权限
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
            // 即使没有后台权限也可以启动，只是后台可能被杀
            startLocationService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("catlab_ping", MODE_PRIVATE)

        // 绑定视图
        switchLocation = findViewById(R.id.switch_location)
        tvLocationStatus = findViewById(R.id.tv_location_status)
        btnLocationSettings = findViewById(R.id.btn_location_settings)

        switchScreenshot = findViewById(R.id.switch_screenshot)
        tvScreenshotStatus = findViewById(R.id.tv_screenshot_status)
        btnScreenshotSettings = findViewById(R.id.btn_screenshot_settings)

        // 恢复开关状态
        switchLocation.isChecked = prefs.getBoolean("location_enabled", false)
        switchScreenshot.isChecked = prefs.getBoolean("screenshot_enabled", false)

        updateLocationStatus(switchLocation.isChecked)
        updateScreenshotStatus(switchScreenshot.isChecked)

        // 位置查岗开关
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

        // 手机使用监控开关
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

        // 设置按钮
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

        // 从使用统计权限设置页返回后，检查是否已授权
        if (switchScreenshot.isChecked && hasUsageStatsPermission()) {
            startAppMonitorService()
            updateScreenshotStatus(true)
        }
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
            startAppMonitorService()
            updateScreenshotStatus(true)
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
