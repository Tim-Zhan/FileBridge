package com.filebridge.app

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 轻量诊断日志：把组网流程的关键步骤写入私有目录 diag.log。
 * 若发生闪退，用户重开 App 后日志仍在，可回传定位崩溃点。
 */
object CrashLog {
    @Volatile
    private var dir: File? = null

    fun init(ctx: Context) {
        val f = File(ctx.applicationContext.filesDir, "diag.log")
        if (!f.exists()) {
            f.createNewFile()
        }
        dir = f
        log("CrashLog", "===== 启动 ${System.currentTimeMillis()} =====")
    }

    fun getLogFile(): File? = dir

    fun log(tag: String, msg: String) {
        Log.d("FB[$tag]", msg)
        val f = dir ?: return
        try {
            val line = "[${System.currentTimeMillis()}] [$tag] $msg\n"
            synchronized(this) {
                f.appendText(line)
            }
        } catch (_: Exception) {
        }
    }
}
