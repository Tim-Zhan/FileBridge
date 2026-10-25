package com.filebridge.app

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 打开方式记忆：按 MIME 类型记住用户选择的应用。
 * 同一类型文件（如 PDF、图片）下次直接打开，不再弹选择器。
 */
object OpenMemory {
    private const val PREFS = "open_memory"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(c: Context, mime: String): String? = prefs(c).getString(mime, null)

    fun set(c: Context, mime: String, pkg: String) {
        prefs(c).edit().putString(mime, pkg).apply()
    }

    fun clearAll(c: Context) {
        prefs(c).edit().clear().apply()
    }
}

/**
 * 分发端口：把文件/直链交给手机上的软件打开。
 * - 本地文件：FileProvider content:// + MIME → ACTION_VIEW
 * - 流媒体：http 直链（带 token）→ 播放器（服务端支持 Range）
 * - 记忆机制：勾选"总是使用"后，同类文件直接打开不再弹窗
 */
object FileOpen {

    /** 扩展名 → MIME 映射（与电脑端服务配套，系统据此弹出对应软件） */
    fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif" -> "image/*"
        "mp4", "mkv", "avi", "mov", "webm", "3gp", "ts", "m4v" -> "video/*"
        "mp3", "wav", "flac", "aac", "ogg", "m4a", "opus" -> "audio/*"
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "txt", "md", "log", "json", "xml", "csv", "ini", "conf" -> "text/plain"
        "html", "htm" -> "text/html"
        "zip", "rar", "7z", "tar", "gz", "bz2" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        "epub" -> "application/epub+zip"
        "mobi" -> "application/x-mobipocket-ebook"
        else -> "application/octet-stream"
    }

    /** 图片或视频（APP 内滑动浏览） */
    fun isMedia(entry: FileEntry): Boolean =
        !entry.isDir && (isImage(entry.name) || isVideo(entry.name))

    fun isImage(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in
            setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")

    fun isVideo(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in
            setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "ts", "m4v")

    /** 打开本地下载好的文件（系统选择器，带记忆） */
    suspend fun openLocalFileAsync(context: Context, file: File): Boolean {
        val uri: Uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            return false
        }
        return dispatchAsync(context, uri, guessMime(file.name), force = false)
    }

    /** 流式打开：视频/音频直链交给播放器 */
    suspend fun openStreamAsync(context: Context, url: String, entry: FileEntry): Boolean =
        dispatchAsync(context, Uri.parse(url), guessMime(entry.name), force = false)

    /**
     * 记忆键：把 MIME 归一化成"大类"，让"同一类型文件"符合直觉——
     * 比如所有 Word 文档（.doc/.docx）共用一条记忆，选一次以后都直接开。
     */
    fun memoryKey(mime: String): String = when {
        mime.startsWith("image/") -> "image/*"
        mime.startsWith("video/") -> "video/*"
        mime.startsWith("audio/") -> "audio/*"
        mime == "application/pdf" -> "application/pdf"
        mime.contains("wordprocessing") || mime == "application/msword" -> "word"
        mime.contains("spreadsheet") || mime == "application/vnd.ms-excel" -> "excel"
        mime.contains("presentation") || mime == "application/vnd.ms-powerpoint" -> "ppt"
        mime.startsWith("text/") -> "text/*"
        mime == "application/zip" || mime == "application/x-zip-compressed" -> "zip"
        mime == "application/vnd.android.package-archive" -> "apk"
        else -> "other"
    }

    /** 忽略记忆，强制弹选择器（长按文件 → "用其他应用打开"） */
    suspend fun openWithPickerAsync(context: Context, uri: Uri, mime: String): Boolean =
        dispatchAsync(context, uri, mime, force = true)

    /**
     * 核心分发：有记忆 → 直接打开；无记忆 → 直接交给系统原生"打开方式"面板。
     * 完全使用手机系统的选择器（和文件管理器打开文件一致），不做自定义面板。
     */
    suspend fun dispatchAsync(context: Context, uri: Uri, mime: String, force: Boolean): Boolean {
        val saved = if (force) null else OpenMemory.get(context, memoryKey(mime))
        if (saved != null) {
            return tryLaunch(context, uri, mime, saved)
        }
        return launchSystemPicker(context, uri, mime)
    }

    /** 直接调用系统原生"打开方式"（系统自己弹应用列表，与文件管理器行为一致） */
    private fun launchSystemPicker(context: Context, uri: Uri, mime: String): Boolean {
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "没有应用能打开此文件，请先安装对应的阅读软件", Toast.LENGTH_LONG).show()
            false
        } catch (e: Exception) {
            Toast.makeText(context, "打开失败：${e.message}", Toast.LENGTH_SHORT).show()
            false
        }
    }

    private fun tryLaunch(context: Context, uri: Uri, mime: String, pkg: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            setPackage(pkg)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            // 记忆的应用已被卸载：清除记忆，重新选择
            OpenMemory.clearAll(context)
            Toast.makeText(context, "上次选择的应用不可用，已清除记忆", Toast.LENGTH_LONG).show()
            false
        } catch (e: Exception) {
            Toast.makeText(context, "打开失败：${e.message}", Toast.LENGTH_SHORT).show()
            false
        }
    }
}
