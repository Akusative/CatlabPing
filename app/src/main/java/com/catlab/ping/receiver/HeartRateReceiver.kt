package com.catlab.ping.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import okhttp3.*
import java.io.IOException
import java.net.URI

class HeartRateReceiver : BroadcastReceiver() {
    private val client = OkHttpClient()

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i("HeartRateReceiver", "收到心率广播: $action")

        val prefs = context.getSharedPreferences("catlab_ping", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("heartrate_enabled", false)
        if (!isEnabled) {
            Log.d("HeartRateReceiver", "心率监听开关未开启，丢弃广播")
            return
        }

        var bpm: Int? = null

        // 尝试从不同的可能的 extra key 中提取心率值
        // 多数第三方插件(如 Notify for Mi Band)会将数据放在 VALUE, value 或 heartRate 等键中
        val possibleKeys = listOf("VALUE", "value", "heartrate", "heartRate", "bpm", "hr")
        val extras = intent.extras
        if (extras != null) {
            for (key in possibleKeys) {
                if (extras.containsKey(key)) {
                    val value = extras.get(key)
                    if (value is Int) {
                        bpm = value
                        break
                    } else if (value is String) {
                        bpm = value.toIntOrNull()
                        if (bpm != null) break
                    } else if (value is Float || value is Double) {
                        bpm = (value as Number).toInt()
                        break
                    }
                }
            }
            
            // 如果上述键都没找到，兜底遍历所有键看看有没有像心率的数字
            if (bpm == null) {
                for (key in extras.keySet()) {
                    val value = extras.get(key)
                    if (value is Int && value in 30..250) {
                        bpm = value
                        break
                    }
                }
            }
        }

        if (bpm == null || bpm <= 0) {
            Log.w("HeartRateReceiver", "未能从广播中提取到有效的心率数值")
            return
        }

        Log.i("HeartRateReceiver", "成功提取心率: $bpm BPM，准备上报...")

        // 获取心率服务器地址
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
            // 如果未单独配置心率服务端，尝试从位置查岗服务器中提取域名/IP作为后备方案
            val locationServer = prefs.getString("location_server", "") ?: ""
            if (locationServer.isNotBlank()) {
                try {
                    val firstServer = locationServer.split(",")[0].trim()
                    val uri = URI(firstServer)
                    if (uri.host != null) {
                        targetUrl = "http://${uri.host}:3476/api/push?bpm=$bpm"
                    }
                } catch (e: Exception) {
                    Log.e("HeartRateReceiver", "回退解析位置服务器地址失败", e)
                }
            }
        }

        Log.d("HeartRateReceiver", "请求地址: $targetUrl")

        val request = Request.Builder().url(targetUrl).get().build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("HeartRateReceiver", "心率上报失败: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        Log.i("HeartRateReceiver", "心率上报成功: $bpm BPM")
                    } else {
                        Log.e("HeartRateReceiver", "心率上报异常，HTTP状态码: ${it.code}")
                    }
                }
            }
        })
    }
}
