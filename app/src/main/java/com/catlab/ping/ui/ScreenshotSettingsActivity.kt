/*
 * CatlabPing - 手机使用监控设置页
 * Copyright (C) 2026 沈菀 (Akusative) - AGPL-3.0
 */

package com.catlab.ping.ui

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.catlab.ping.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

class ScreenshotSettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var tvMonitorAppsInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screenshot_settings)

        prefs = getSharedPreferences("catlab_ping", MODE_PRIVATE)

        val etServerUrl = findViewById<EditText>(R.id.et_screenshot_server)
        val etPort = findViewById<EditText>(R.id.et_screenshot_port)
        val etDeviceName = findViewById<EditText>(R.id.et_device_name)
        val switchScreenshotCapture = findViewById<MaterialSwitch>(R.id.switch_screenshot_capture)
        val btnSelectApps = findViewById<MaterialButton>(R.id.btn_select_monitor_apps)
        tvMonitorAppsInfo = findViewById(R.id.tv_monitor_apps_info)
        val btnSave = findViewById<MaterialButton>(R.id.btn_screenshot_save)

        // 截屏排除名单
        val etExcludeApps: EditText? = try {
            findViewById(R.id.et_screenshot_exclude_apps)
        } catch (e: Exception) { null }

        // 恢复已保存的值
        etServerUrl.setText(prefs.getString("screenshot_server", ""))
        etPort.setText(prefs.getInt("screenshot_port", 2313).toString())
        etDeviceName.setText(prefs.getString("device_name", "Android"))
        switchScreenshotCapture.isChecked = prefs.getBoolean("screenshot_capture_enabled", true)
        etExcludeApps?.setText(prefs.getString("screenshot_exclude_apps",
            "支付宝|com.eg.android.AlipayGphone\n微信支付|com.tencent.mm\n中国银行|com.chinamworld.main\n工商银行|com.icbc\n建设银行|com.chinamworld.bocmbci\n招商银行|cmb.pb"))

        updateMonitorAppsInfo()

        // 点击选择监控App
        btnSelectApps.setOnClickListener {
            startActivity(Intent(this, AppSelectorActivity::class.java))
        }

        btnSave.setOnClickListener {
            prefs.edit().apply {
                putString("screenshot_server", etServerUrl.text.toString().trim())
                putInt("screenshot_port", etPort.text.toString().toIntOrNull() ?: 2313)
                putString("device_name", etDeviceName.text.toString().trim())
                putBoolean("screenshot_capture_enabled", switchScreenshotCapture.isChecked)
                etExcludeApps?.let {
                    putString("screenshot_exclude_apps", it.text.toString().trim())
                }
                apply()
            }
            Toast.makeText(this, "✅ 设置已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        updateMonitorAppsInfo()
    }

    private fun updateMonitorAppsInfo() {
        val saved = prefs.getString("monitor_apps", "") ?: ""
        if (saved.isBlank()) {
            tvMonitorAppsInfo.text = "未设置（监控所有App）"
            tvMonitorAppsInfo.setTextColor(0xFF999999.toInt())
        } else {
            val count = saved.lines().filter { it.trim().isNotEmpty() }.size
            val names = saved.lines()
                .filter { it.trim().isNotEmpty() }
                .take(5)
                .joinToString("、") { it.split("|")[0].trim() }
            val suffix = if (count > 5) " 等${count}个应用" else "（共${count}个）"
            tvMonitorAppsInfo.text = "$names$suffix"
            tvMonitorAppsInfo.setTextColor(0xFF4CAF50.toInt())
        }
    }
}
