/*
 * CatlabPing - 位置查岗设置页
 * Copyright (C) 2026 沈菀 (Akusative) - AGPL-3.0
 *
 * 使用原生 LocationManager，兼容华为/OPPO 等无 GMS 设备
 */

package com.catlab.ping.ui

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.catlab.ping.R
import com.google.android.material.button.MaterialButton

class LocationSettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var locationManager: LocationManager
    private lateinit var etHomeLat: EditText
    private lateinit var etHomeLng: EditText
    private val handler = Handler(Looper.getMainLooper())
    private var locationReceived = false

    // 一次性定位监听器
    private val oneTimeLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!locationReceived) {
                locationReceived = true
                etHomeLat.setText(String.format("%.6f", location.latitude))
                etHomeLng.setText(String.format("%.6f", location.longitude))
                Toast.makeText(this@LocationSettingsActivity, "✅ 已获取当前位置", Toast.LENGTH_SHORT).show()
                // 拿到一次就移除监听
                locationManager.removeUpdates(this)
            }
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in API level 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_location_settings)

        prefs = getSharedPreferences("catlab_ping", MODE_PRIVATE)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val etServerUrl = findViewById<EditText>(R.id.et_location_server)
        val etInterval = findViewById<EditText>(R.id.et_location_interval)
        etHomeLat = findViewById(R.id.et_home_lat)
        etHomeLng = findViewById(R.id.et_home_lng)
        val etAlertDistance = findViewById<EditText>(R.id.et_alert_distance)
        val btnGetLocation = findViewById<MaterialButton>(R.id.btn_get_current_location)
        val btnSave = findViewById<MaterialButton>(R.id.btn_location_save)

        // 恢复已保存的值
        etServerUrl.setText(prefs.getString("location_server", ""))
        etInterval.setText(prefs.getInt("location_interval", 10).toString())
        etHomeLat.setText(prefs.getString("home_lat", ""))
        etHomeLng.setText(prefs.getString("home_lng", ""))
        etAlertDistance.setText(prefs.getInt("alert_distance", 500).toString())

        // 📍 获取当前位置按钮
        btnGetLocation.setOnClickListener {
            getCurrentLocation()
        }

        btnSave.setOnClickListener {
            prefs.edit().apply {
                putString("location_server", etServerUrl.text.toString().trim())
                putInt("location_interval", etInterval.text.toString().toIntOrNull() ?: 10)
                putString("home_lat", etHomeLat.text.toString().trim())
                putString("home_lng", etHomeLng.text.toString().trim())
                putInt("alert_distance", etAlertDistance.text.toString().toIntOrNull() ?: 500)
                apply()
            }
            Toast.makeText(this, "✅ 设置已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun getCurrentLocation() {
        // 检查定位权限
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "请先在主页开启位置查岗以授予定位权限", Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(this, "📍 正在获取当前位置...", Toast.LENGTH_SHORT).show()
        locationReceived = false

        // 不检查isProviderEnabled，直接请求——某些国产ROM返回false但实际能定位
        // 用try-catch兜底，请求失败不影响流程
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                0L, 0f,
                oneTimeLocationListener,
                Looper.getMainLooper()
            )
        } catch (e: Exception) {
            // GPS不可用，忽略
        }

        try {
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                0L, 0f,
                oneTimeLocationListener,
                Looper.getMainLooper()
            )
        } catch (e: Exception) {
            // 网络定位不可用，忽略
        }

        // 先立即尝试获取最后已知位置，不等回调
        val lastGps = try { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (e: Exception) { null }
        val lastNet = try { locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch (e: Exception) { null }
        val immediate = lastGps ?: lastNet
        if (immediate != null && !locationReceived) {
            locationReceived = true
            etHomeLat.setText(String.format("%.6f", immediate.latitude))
            etHomeLng.setText(String.format("%.6f", immediate.longitude))
            Toast.makeText(this, "✅ 已获取当前位置", Toast.LENGTH_SHORT).show()
            try { locationManager.removeUpdates(oneTimeLocationListener) } catch (_: Exception) {}
            return
        }

        // 15秒超时：如果还没拿到位置就提示手动填写
        handler.postDelayed({
            if (!locationReceived) {
                try { locationManager.removeUpdates(oneTimeLocationListener) } catch (_: Exception) {}
                Toast.makeText(this, "❌ 获取位置超时，请手动填写坐标（在地图App中查看）", Toast.LENGTH_LONG).show()
            }
        }, 15000)
    }

    override fun onDestroy() {
        try {
            locationManager.removeUpdates(oneTimeLocationListener)
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
