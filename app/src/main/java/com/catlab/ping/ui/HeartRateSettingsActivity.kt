package com.catlab.ping.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.catlab.ping.R
import com.catlab.ping.service.BleHeartRateService
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

class HeartRateSettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_heartrate_settings)

        prefs = getSharedPreferences("catlab_ping", Context.MODE_PRIVATE)

        val etHeartrateServer = findViewById<EditText>(R.id.et_heartrate_server)
        val switchBleDirect = findViewById<MaterialSwitch>(R.id.switch_ble_direct)
        val etBleMac = findViewById<EditText>(R.id.et_ble_mac)
        val btnSave = findViewById<MaterialButton>(R.id.btn_heartrate_save)

        // 恢复已保存的值
        etHeartrateServer.setText(prefs.getString("heartrate_server", ""))
        switchBleDirect.isChecked = prefs.getBoolean("ble_direct_enabled", false)
        etBleMac.setText(prefs.getString("ble_mac", ""))

        btnSave.setOnClickListener {
            val isBleDirect = switchBleDirect.isChecked
            if (isBleDirect) {
                checkAndRequestBlePermissions {
                    saveAndRestartService(true, etHeartrateServer.text.toString(), etBleMac.text.toString())
                }
            } else {
                saveAndRestartService(false, etHeartrateServer.text.toString(), etBleMac.text.toString())
            }
        }
    }

    private fun saveAndRestartService(isBleDirect: Boolean, server: String, mac: String) {
        prefs.edit().apply {
            putBoolean("ble_direct_enabled", isBleDirect)
            putString("heartrate_server", server.trim())
            putString("ble_mac", mac.trim())
            apply()
        }

        Toast.makeText(this, "✅ 心率设置已保存", Toast.LENGTH_SHORT).show()

        // 停止旧的服务
        stopService(Intent(this, BleHeartRateService::class.java))

        // 如果开启了BLE直连，且主开关已开启，启动服务
        if (isBleDirect && prefs.getBoolean("heartrate_enabled", false)) {
            val serviceIntent = Intent(this, BleHeartRateService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }

        finish()
    }

    private fun checkAndRequestBlePermissions(onGranted: () -> Unit) {
        val permissions = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1001)
        } else {
            onGranted()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                // 再次点击保存以继续
                Toast.makeText(this, "权限已授予，请再次点击保存", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "需要蓝牙权限才能使用直接连接功能", Toast.LENGTH_LONG).show()
            }
        }
    }
}
