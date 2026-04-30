/*
 * CatlabPing - 手机使用监控设置页
 * Copyright (C) 2026 沈菀 (Akusative) - AGPL-3.0
 */

package com.catlab.ping.ui

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.catlab.ping.R
import com.google.android.material.button.MaterialButton

class ScreenshotSettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screenshot_settings)

        prefs = getSharedPreferences("catlab_ping", MODE_PRIVATE)

        val etServerUrl = findViewById<EditText>(R.id.et_screenshot_server)
        val etPort = findViewById<EditText>(R.id.et_screenshot_port)
        val etDeviceName = findViewById<EditText>(R.id.et_device_name)
        val etMonitorApps = findViewById<EditText>(R.id.et_monitor_apps)
        val btnSave = findViewById<MaterialButton>(R.id.btn_screenshot_save)

        // 恢复已保存的值
        etServerUrl.setText(prefs.getString("screenshot_server", ""))
        etPort.setText(prefs.getInt("screenshot_port", 2313).toString())
        etDeviceName.setText(prefs.getString("device_name", "Android"))
        etMonitorApps.setText(prefs.getString("monitor_apps", ""))

        btnSave.setOnClickListener {
            prefs.edit().apply {
                putString("screenshot_server", etServerUrl.text.toString().trim())
                putInt("screenshot_port", etPort.text.toString().toIntOrNull() ?: 2313)
                putString("device_name", etDeviceName.text.toString().trim())
                putString("monitor_apps", etMonitorApps.text.toString().trim())
                apply()
            }
            Toast.makeText(this, "✅ 设置已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
