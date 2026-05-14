package com.catlab.ping.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.catlab.ping.R
import okhttp3.*
import java.io.IOException
import java.net.URI
import java.util.UUID

class BleHeartRateService : Service() {

    companion object {
        private const val TAG = "BleHeartRateService"
        private const val NOTIFICATION_ID = 4001
        private const val CHANNEL_ID = "ble_heart_rate_channel"
        
        val HR_SERVICE_UUID: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HR_MEASUREMENT_CHAR_UUID: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private lateinit var prefs: SharedPreferences
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false
    private val client = OkHttpClient()

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("catlab_ping", Context.MODE_PRIVATE)
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("心率监听直连中")
            .setContentText("正在扫描或连接 BLE 心率设备...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
            
        // Use FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE for Android 14+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        startBleProcess()

        return START_STICKY
    }

    private fun checkBlePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        } else {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true
    }

    private fun startBleProcess() {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Log.e(TAG, "蓝牙未开启或不支持")
            stopSelf()
            return
        }

        if (!checkBlePermission()) {
            Log.e(TAG, "缺少蓝牙权限")
            stopSelf()
            return
        }

        val targetMac = prefs.getString("ble_mac", "")?.trim()
        
        if (!targetMac.isNullOrEmpty() && BluetoothAdapter.checkBluetoothAddress(targetMac)) {
            // 如果指定了 MAC 地址，直接尝试连接
            Log.i(TAG, "使用指定 MAC 地址直连: $targetMac")
            val device = bluetoothAdapter!!.getRemoteDevice(targetMac)
            connectToDevice(device)
        } else {
            // 没有指定 MAC，开始扫描心率设备
            startScanning()
        }
    }

    private fun startScanning() {
        if (!checkBlePermission()) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "无法获取 BLE Scanner")
            return
        }

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(HR_SERVICE_UUID))
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()

        Log.i(TAG, "开始扫描心率设备...")
        isScanning = true
        scanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
        
        // 10秒扫描超时，如果没有扫到则过一会儿重试
        handler.postDelayed({
            if (isScanning) {
                Log.w(TAG, "扫描超时，未发现心率设备，重新扫描...")
                stopScanning()
                handler.postDelayed({ startScanning() }, 5000)
            }
        }, 10000)
    }

    private fun stopScanning() {
        if (!checkBlePermission()) return
        isScanning = false
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                Log.i(TAG, "发现心率设备: ${device.address} ${device.name}")
                stopScanning()
                connectToDevice(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE 扫描失败: $errorCode")
            isScanning = false
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (!checkBlePermission()) return
        Log.i(TAG, "尝试连接到设备: ${device.address}")
        
        // 为了提高连接成功率，在主线程调用 connectGatt
        handler.post {
            bluetoothGatt = device.connectGatt(this, true, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (!checkBlePermission()) return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "已连接到 GATT 服务器，开始发现服务...")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "与 GATT 服务器断开连接，尝试重新连接...")
                gatt.close()
                bluetoothGatt = null
                // 断线重连
                handler.postDelayed({ startBleProcess() }, 5000)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!checkBlePermission()) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val hrService = gatt.getService(HR_SERVICE_UUID)
                if (hrService != null) {
                    val hrCharacteristic = hrService.getCharacteristic(HR_MEASUREMENT_CHAR_UUID)
                    if (hrCharacteristic != null) {
                        Log.i(TAG, "找到心率特征值，正在订阅通知...")
                        gatt.setCharacteristicNotification(hrCharacteristic, true)

                        val descriptor = hrCharacteristic.getDescriptor(CCCD_UUID)
                        if (descriptor != null) {
                            // Android 13+ 需要使用 byte[] 版本
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            } else {
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                gatt.writeDescriptor(descriptor)
                            }
                        } else {
                            Log.e(TAG, "未找到 CCCD 描述符")
                        }
                    } else {
                        Log.e(TAG, "未找到心率特征值")
                    }
                } else {
                    Log.e(TAG, "未找到心率服务")
                }
            } else {
                Log.e(TAG, "服务发现失败: $status")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == HR_MEASUREMENT_CHAR_UUID) {
                val value = characteristic.value
                if (value != null) {
                    val bpm = extractHeartRate(value)
                    Log.i(TAG, "BLE 收到心率: $bpm BPM")
                    if (bpm > 0) {
                        uploadHeartRate(bpm)
                    }
                }
            }
        }
        
        // For Android 13+ (API 33)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == HR_MEASUREMENT_CHAR_UUID) {
                val bpm = extractHeartRate(value)
                Log.i(TAG, "BLE 收到心率(API 33+): $bpm BPM")
                if (bpm > 0) {
                    uploadHeartRate(bpm)
                }
            }
        }
    }

    private fun extractHeartRate(value: ByteArray): Int {
        if (value.isEmpty()) return 0
        // BLE Heart Rate Measurement format:
        // Byte 0: Flags (Bit 0 indicates whether HR format is UINT8 or UINT16)
        // Byte 1 (or 1-2): Heart Rate Measurement Value
        val flag = value[0].toInt()
        val isUint16 = (flag and 0x01) != 0
        
        return if (isUint16 && value.size >= 3) {
            val lower = value[1].toInt() and 0xFF
            val upper = value[2].toInt() and 0xFF
            (upper shl 8) + lower
        } else if (!isUint16 && value.size >= 2) {
            value[1].toInt() and 0xFF
        } else {
            0
        }
    }

    private var lastUploadTime = 0L
    private fun uploadHeartRate(bpm: Int) {
        // 防止上报过于频繁，限制1秒最多1次
        val now = System.currentTimeMillis()
        if (now - lastUploadTime < 1000) return
        lastUploadTime = now

        val heartrateServer = prefs.getString("heartrate_server", "") ?: ""
        var targetUrl = "http://192.168.x.x:3476/api/push?bpm=$bpm" // 默认回退URL

        if (heartrateServer.isNotBlank()) {
            val base = heartrateServer.trimEnd('/')
            targetUrl = if (base.startsWith("http")) {
                "$base/api/push?bpm=$bpm"
            } else {
                "http://$base/api/push?bpm=$bpm"
            }
        } else {
            // 后备位置服务器
            val locationServer = prefs.getString("location_server", "") ?: ""
            if (locationServer.isNotBlank()) {
                try {
                    val firstServer = locationServer.split(",")[0].trim()
                    val uri = URI(firstServer)
                    if (uri.host != null) {
                        targetUrl = "http://${uri.host}:3476/api/push?bpm=$bpm"
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "解析位置服务器失败", e)
                }
            }
        }

        val request = Request.Builder().url(targetUrl).get().build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "BLE 心率上报失败: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        Log.d(TAG, "BLE 心率上报成功: $bpm BPM")
                    } else {
                        Log.e(TAG, "BLE 心率上报异常，HTTP状态码: ${it.code}")
                    }
                }
            }
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "心率直连服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持后台运行以连接 BLE 心率设备"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (checkBlePermission()) {
            if (isScanning) {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            }
            bluetoothGatt?.close()
        }
        bluetoothGatt = null
        handler.removeCallbacksAndMessages(null)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
