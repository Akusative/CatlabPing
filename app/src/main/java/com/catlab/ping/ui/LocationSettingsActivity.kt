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
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import android.util.Log

class LocationSettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var locationManager: LocationManager
    private lateinit var etHomeLat: EditText
    private lateinit var etHomeLng: EditText
    private val handler = Handler(Looper.getMainLooper())
    private var locationReceived = false
    private val client = OkHttpClient()

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

        // 📤 手动上报按钮
        val btnManualReport = findViewById<MaterialButton>(R.id.btn_manual_report)
        btnManualReport.setOnClickListener {
            manualReport()
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

    /**
     * 手动上报当前位置到服务器
     * 检查家坐标和服务器地址是否已填写，获取当前GPS坐标后POST上报
     */
    private fun manualReport() {
        val serverUrl = prefs.getString("location_server", "") ?: ""
        val homeLat = prefs.getString("home_lat", "")
        val homeLng = prefs.getString("home_lng", "")

        // 检查服务器地址
        if (serverUrl.isBlank()) {
            Toast.makeText(this, "❌ 请先填写服务器地址", Toast.LENGTH_SHORT).show()
            return
        }

        // 检查家坐标是否已填写
        val lat = homeLat?.toDoubleOrNull()
        val lng = homeLng?.toDoubleOrNull()
        if (lat == null || lng == null || (lat == 0.0 && lng == 0.0)) {
            Toast.makeText(this, "❌ 请先设置家的位置（点击「获取当前位置」或手动填写坐标）", Toast.LENGTH_LONG).show()
            return
        }

        // 检查定位权限
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "请先在主页开启位置查岗以授予定位权限", Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(this, "📤 正在获取位置并上报...", Toast.LENGTH_SHORT).show()

        // 获取当前位置
        val lastGps = try { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (e: Exception) { null }
        val lastNet = try { locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch (e: Exception) { null }
        val location = lastGps ?: lastNet

        if (location == null) {
            Toast.makeText(this, "❌ 无法获取当前位置，请确认GPS已开启", Toast.LENGTH_LONG).show()
            return
        }

        // 构建上报数据
        val json = JSONObject().apply {
            put("lat", location.latitude)
            put("lng", location.longitude)
            put("accuracy", location.accuracy)
            put("provider", location.provider ?: "unknown")
            put("device", "android")
            put("timestamp", System.currentTimeMillis() / 1000)
            put("event", "")
            put("weather", "")
            put("temperature", "")
        }

        // POST到服务器
        val url = "${serverUrl.trimEnd('/')}/api/location/report"
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("LocationSettings", "手动上报失败: ${e.message}")
                handler.post {
                    Toast.makeText(this@LocationSettingsActivity, "❌ 上报失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val respBody = it.body?.string() ?: ""
                    Log.i("LocationSettings", "手动上报响应: ${it.code} $respBody")
                    handler.post {
                        if (it.isSuccessful) {
                            Toast.makeText(this@LocationSettingsActivity,
                                "✅ 上报成功！坐标: ${String.format("%.4f", location.latitude)}, ${String.format("%.4f", location.longitude)}",
                                Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this@LocationSettingsActivity, "❌ 服务器返回异常: ${it.code}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        })
    }
}
