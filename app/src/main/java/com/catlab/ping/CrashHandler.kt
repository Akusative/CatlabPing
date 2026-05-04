/*
 * CatlabPing - 全局崩溃捕获器
 * 闪退时将错误信息保存到文件，下次启动时弹窗显示
 */

package com.catlab.ping

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

class CrashHandler private constructor(private val context: Context) : Thread.UncaughtExceptionHandler {

    companion object {
        private const val TAG = "CrashHandler"
        private const val CRASH_LOG_FILE = "crash_log.txt"

        fun init(context: Context) {
            val handler = CrashHandler(context.applicationContext)
            Thread.setDefaultUncaughtExceptionHandler(handler)
            Log.i(TAG, "全局崩溃捕获器已初始化")
        }

        /**
         * 读取上次崩溃日志，读完后删除文件
         */
        fun getLastCrashLog(context: Context): String? {
            val file = File(context.filesDir, CRASH_LOG_FILE)
            if (!file.exists()) return null
            val content = file.readText()
            file.delete()
            return if (content.isBlank()) null else content
        }
    }

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            throwable.printStackTrace(pw)
            val stackTrace = sw.toString()

            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())

            val crashInfo = buildString {
                appendLine("=== CatlabPing 崩溃日志 ===")
                appendLine("时间: $timestamp")
                appendLine("线程: ${thread.name}")
                appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine()
                appendLine("异常信息:")
                appendLine(throwable.toString())
                appendLine()
                appendLine("堆栈跟踪:")
                appendLine(stackTrace)
            }

            // 保存到文件
            val file = File(context.filesDir, CRASH_LOG_FILE)
            file.writeText(crashInfo)
            Log.e(TAG, "崩溃日志已保存: ${file.absolutePath}")
            Log.e(TAG, crashInfo)

        } catch (e: Exception) {
            Log.e(TAG, "保存崩溃日志失败: ${e.message}")
        }

        // 交给系统默认处理（杀进程）
        defaultHandler?.uncaughtException(thread, throwable)
    }
}
