package com.filebridge.app

import android.app.Application

/**
 * FileBridge Application：进程启动即初始化 Tailscale 组网核心（登录态持久化在私有目录）
 */
class FileBridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TailnetManager.init(this)
    }
}
