package com.filebridge.app

import android.app.Application
import android.util.Log

/**
 * FileBridge Application。
 * 注意：组网核心【不在启动时初始化】——改为用户点击"组网"按钮时才启动，
 * 从根本上保证 App 打开永不闪退（组网失败只影响组网功能本身）。
 * 同时挂载全局崩溃捕获，把异常写入诊断日志便于定位。
 */
class FileBridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                CrashLog.init(this)
                CrashLog.log("FATAL", "线程 ${thread.name} 崩溃: ${throwable.message}")
                for ((i, el) in throwable.stackTrace.take(15).withIndex()) {
                    CrashLog.log("FATAL", "  $i: $el")
                }
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(thread, throwable)
        }
        CrashLog.init(this)
        CrashLog.log("FileBridgeApp", "App 启动（组网未初始化，按需启动）")
        Log.d("FileBridgeApp", "FileBridge 启动")
    }
}
