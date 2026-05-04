/*
 * CatlabPing - MediaProjection 授权持有者
 * Copyright (C) 2026 沈菀 (Akusative) - AGPL-3.0
 *
 * 持有 MediaProjection 授权数据和实例，支持一次授权多次截屏。
 * MediaProjection 实例在第一次截屏时创建，之后一直保持活跃复用。
 * Callback 在创建实例时一次性注册，不重复注册。
 */

package com.catlab.ping

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

object ProjectionHolder {
    const val TAG = "ProjectionHolder"

    var resultCode: Int = Activity.RESULT_CANCELED
        private set
    var resultData: Intent? = null
        private set
    var mediaProjection: MediaProjection? = null
        private set

    /**
     * 保存授权结果（仅保存原始数据）
     */
    fun save(code: Int, data: Intent?) {
        resultCode = code
        resultData = data?.clone() as? Intent
        Log.i(TAG, "MediaProjection 授权数据已缓存 (resultCode=$code)")
    }

    /**
     * 获取或创建 MediaProjection 实例
     * 第一次调用时创建并注册 Callback，之后复用同一个实例
     * 必须在前台服务启动之后调用
     */
    fun getOrCreateProjection(context: Context): MediaProjection? {
        // 如果已有实例，直接复用，不再注册 Callback
        if (mediaProjection != null) {
            Log.i(TAG, "复用已有的 MediaProjection 实例")
            return mediaProjection
        }

        // 没有实例，从授权数据创建
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            Log.w(TAG, "没有可用的授权数据")
            return null
        }

        return try {
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = manager.getMediaProjection(resultCode, resultData!!)

            // 创建后立即清除原始数据（token 已被消费，不能再用）
            resultData = null

            // Android 14+ 要求在 createVirtualDisplay 之前注册 Callback
            // 只在创建实例时注册一次
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                projection.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.i(TAG, "MediaProjection onStop 回调触发")
                        onProjectionStopped()
                    }
                }, Handler(Looper.getMainLooper()))
                Log.i(TAG, "已注册 MediaProjection Callback (Android 14+)")
            }

            mediaProjection = projection
            Log.i(TAG, "MediaProjection 实例创建成功")
            mediaProjection
        } catch (e: Exception) {
            Log.e(TAG, "创建 MediaProjection 失败: ${e.message}")
            null
        }
    }

    /**
     * 检查是否有可用的授权（有实例或有未消费的授权数据）
     */
    fun isAuthorized(): Boolean {
        return mediaProjection != null || (resultCode == Activity.RESULT_OK && resultData != null)
    }

    /**
     * 当系统通过 Callback.onStop() 主动停止 MediaProjection 时调用
     */
    fun onProjectionStopped() {
        mediaProjection = null
        Log.i(TAG, "MediaProjection 被系统停止，实例已清除")
    }

    /**
     * 完全清除授权（用户主动关闭监控时调用）
     */
    fun clear() {
        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "停止 MediaProjection 时异常: ${e.message}")
        }
        mediaProjection = null
        resultCode = Activity.RESULT_CANCELED
        resultData = null
        Log.i(TAG, "MediaProjection 授权已完全清除")
    }
}
