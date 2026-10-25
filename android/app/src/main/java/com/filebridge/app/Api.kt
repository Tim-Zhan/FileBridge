package com.filebridge.app

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 服务器返回的单个目录项 */
data class FileEntry(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modTime: String,
    val path: String
) {
    val ext: String get() = name.substringAfterLast('.', "").lowercase()
    val sizeText: String get() = if (isDir) "" else Api.formatSize(size)
}

class AuthException : IOException("用户名或密码错误")
class ServerNotReadyException : IOException("服务器尚未完成初始化设置")

/** 网络层：与服务端 API 严格配套（list / download，支持 ?token= 鉴权） */
object Api {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    private fun ServerInfo.token(): String =
        Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)

    private fun ServerInfo.authHeader(): String = "Basic ${token()}"

    private fun ServerInfo.encodePath(path: String): String =
        path.split("/").joinToString("/") { it.let { p -> java.net.URLEncoder.encode(p, "UTF-8") } }

    /** 列目录 */
    suspend fun list(server: ServerInfo, path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        val url = "${server.baseUrl}/api/list?path=${server.encodePath(path)}"
        val req = Request.Builder().url(url).header("Authorization", server.authHeader()).build()
        client.newCall(req).execute().use { resp ->
            handleError(resp)
            val obj = JSONObject(resp.body?.string() ?: "")
            val items = obj.getJSONArray("items")
            (0 until items.length()).map { i ->
                val o = items.getJSONObject(i)
                FileEntry(
                    name = o.getString("name"),
                    isDir = o.getBoolean("isDir"),
                    size = o.getLong("size"),
                    modTime = o.getString("modTime"),
                    path = o.getString("path")
                )
            }
        }
    }

    /** 下载到本地文件（流式，带进度回调） */
    suspend fun download(
        server: ServerInfo,
        entry: FileEntry,
        dest: File,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val url = "${server.baseUrl}/api/download?path=${server.encodePath(entry.path)}"
        val req = Request.Builder().url(url).header("Authorization", server.authHeader()).build()
        client.newCall(req).execute().use { resp ->
            handleError(resp)
            val body = resp.body ?: throw IOException("响应为空")
            val total = resp.header("Content-Length")?.toLongOrNull() ?: -1
            val source = body.source()
            dest.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = source.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    onProgress(done, total)
                }
            }
            dest
        }
    }

    /** 缩略图（列表微缩预览，图片类文件用） */
    suspend fun thumb(server: ServerInfo, entry: FileEntry): ByteArray = withContext(Dispatchers.IO) {
        val url = "${server.baseUrl}/api/thumb?path=${server.encodePath(entry.path)}"
        val req = Request.Builder().url(url).header("Authorization", server.authHeader()).build()
        client.newCall(req).execute().use { resp ->
            handleError(resp)
            resp.body?.bytes() ?: throw IOException("缩略图为空")
        }
    }

    /** 流式直链（视频/音频）：带 ?token= 的完整 URL，可直接交给系统播放器 */
    fun streamUrl(server: ServerInfo, entry: FileEntry): String =
        "${server.baseUrl}/api/download?path=${server.encodePath(entry.path)}&token=${server.token()}"

    /** 下载到用户指定的位置（SAF 输出流，长按文件→"下载到…"） */
    suspend fun downloadToUri(server: ServerInfo, entry: FileEntry, out: java.io.OutputStream): Boolean =
        withContext(Dispatchers.IO) {
            val url = "${server.baseUrl}/api/download?path=${server.encodePath(entry.path)}&token=${server.token()}"
            val req = Request.Builder().url(url).header("Authorization", server.authHeader()).build()
            client.newCall(req).execute().use { resp ->
                handleError(resp)
                val body = resp.body ?: return@use false
                val src = body.source()
                out.use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                    }
                }
                true
            }
        }

    /** 上传文件到服务器指定目录（流式，配合系统文件选择器使用） */
    suspend fun upload(
        server: ServerInfo,
        dirPath: String,
        fileName: String,
        input: java.io.InputStream,
        size: Long,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        val url = "${server.baseUrl}/api/upload?path=${server.encodePath(dirPath)}" +
            "&filename=${java.net.URLEncoder.encode(fileName, "UTF-8")}"
        val body = object : RequestBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = if (size >= 0) size else -1
            override fun writeTo(sink: okio.BufferedSink) {
                val buf = ByteArray(64 * 1024)
                var sent = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    sink.write(buf, 0, n)
                    sent += n
                    onProgress(sent, size)
                }
            }
        }
        val req = Request.Builder()
            .url(url)
            .header("Authorization", server.authHeader())
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            handleError(resp)
            resp.isSuccessful
        }
    }

    /** 探测服务是否可达（添加服务器时校验） */
    suspend fun ping(server: ServerInfo): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "${server.baseUrl}/api/list?path="
            val req = Request.Builder().url(url).header("Authorization", server.authHeader()).build()
            client.newCall(req).execute().use { resp ->
                resp.code in 200..299
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun handleError(resp: Response) {
        when (resp.code) {
            401 -> throw AuthException()
            403 -> {
                // 服务端返回无权限等具体原因（如系统目录需要管理员）
                val msg = resp.body?.string()?.take(120)?.trim().orEmpty()
                throw IOException(if (msg.isNotEmpty()) msg else "没有权限访问该文件夹")
            }
            else -> if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
        }
    }

    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }
}
