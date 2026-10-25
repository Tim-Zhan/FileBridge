package com.filebridge.app

import android.content.Context
import java.io.File

/**
 * 缓存管理：预览产生的缓存（downloads/、media/）都是临时文件。
 *  - 启动 App 时清理 24 小时前的旧缓存（防异常退出残留）
 *  - 退出 App 时清理 30 分钟前的缓存（正在预览的最近文件保留，其余清掉）
 */
object CacheManager {

    fun cleanup(context: Context, maxAgeMs: Long) {
        try {
            val cutoff = System.currentTimeMillis() - maxAgeMs
            val dirs = listOf(
                File(context.cacheDir, "downloads"),
                File(context.cacheDir, "media"),
                File(context.cacheDir, "open_cache")
            )
            for (d in dirs) {
                if (!d.exists()) continue
                d.listFiles()?.forEach { f ->
                    if (f.isFile && f.lastModified() < cutoff) {
                        f.delete()
                    }
                }
            }
        } catch (_: Exception) {
        }
    }
}
