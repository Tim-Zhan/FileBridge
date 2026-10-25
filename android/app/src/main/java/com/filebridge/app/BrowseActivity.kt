package com.filebridge.app

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.LruCache
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.filebridge.app.databinding.ActivityBrowseBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 目录浏览：进入文件夹、下载并打开文件、视频/音频直链播放、上传文件到当前目录 */
class BrowseActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBrowseBinding
    private lateinit var server: ServerInfo

    private val adapter = FileAdapter(
        { entry -> onEntryClick(entry) },
        { entry -> showFileActions(entry) },
        { uploadLauncher.launch(arrayOf("*/*")) }
    )
    private var currentPath = ""
    private var downloadDialog: AlertDialog? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    /** 系统文件选择器：选中的文件直接上传到当前浏览的目录 */
    private val uploadLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { startUpload(it, currentPath) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val serverId = intent.getStringExtra("serverId")
        server = ServerStore.find(this, serverId ?: "")
            ?: run { finish(); return }

        binding.toolbar.title = server.name
        binding.toolbar.setNavigationOnClickListener { goUp() }
        binding.fileList.layoutManager = LinearLayoutManager(this)
        binding.fileList.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener { loadDir(currentPath) }
        loadDir("")
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_browse, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_upload -> {
                uploadLauncher.launch(arrayOf("*/*"))
                true
            }
            R.id.action_clear_memory -> {
                OpenMemory.clearAll(this)
                Toast.makeText(this, getString(R.string.clear_memory_done), Toast.LENGTH_SHORT).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadDialog?.dismiss()
    }

    private fun loadDir(path: String) {
        currentPath = path
        binding.currentPath.text = if (path.isEmpty()) "/" else "/$path/"
        binding.swipeRefresh.isRefreshing = true
        scope.launch {
            try {
                val items = withContext(Dispatchers.IO) { Api.list(server, path) }
                adapter.submit(items)
            } catch (e: AuthException) {
                Toast.makeText(this@BrowseActivity, getString(R.string.auth_failed), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                // 服务端返回的具体原因（中文）优先展示，否则显示通用网络错误
                val m = e.message ?: ""
                val hasCn = m.any { it.code in 0x4E00..0x9FFF }
                if (m.isNotEmpty() && m.length < 100 && hasCn) {
                    Toast.makeText(this@BrowseActivity, m, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@BrowseActivity, getString(R.string.net_error), Toast.LENGTH_LONG).show()
                }
            }
            binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun goUp() {
        val idx = currentPath.lastIndexOf('/')
        loadDir(if (idx < 0) "" else currentPath.substring(0, idx))
    }

    override fun onBackPressed() {
        if (currentPath.isNotEmpty()) goUp() else super.onBackPressed()
    }

    private fun onEntryClick(entry: FileEntry) {
        if (entry.isDir) {
            loadDir(entry.path)
        } else if (FileOpen.isMedia(entry)) {
            // 图片/视频：进入相册式浏览，左右滑动切换
            openMediaViewer(entry)
        } else {
            // 其他：下载到缓存后交给系统软件打开（可勾选记住打开方式）
            downloadAndOpen(entry)
        }
    }

    /** 打开相册式浏览：只取当前目录的图片/视频，从当前文件开始左右滑动 */
    private fun openMediaViewer(current: FileEntry) {
        val media = adapter.currentItems().filter { FileOpen.isMedia(it) }
        if (media.isEmpty()) return
        val index = media.indexOfFirst { it.path == current.path }.coerceAtLeast(0)
        val intent = Intent(this, MediaViewerActivity::class.java).apply {
            putExtra("serverId", server.id)
            putExtra("paths", media.map { it.path }.toTypedArray())
            putExtra("index", index)
        }
        startActivity(intent)
    }

    // ---------------- 长按菜单：预览 / 用其他应用打开 / 下载到… ----------------

    private fun showFileActions(entry: FileEntry) {
        if (entry.isDir) return
        AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setItems(arrayOf(
                getString(R.string.preview),
                getString(R.string.open_with_other),
                getString(R.string.save_to)
            )) { _, which ->
                when (which) {
                    0 -> {
                        if (FileOpen.isMedia(entry)) openMediaViewer(entry)
                        else downloadAndOpen(entry) // 预览 = 缓存 + 打开，不占用户存储
                    }
                    1 -> openWithExternalApp(entry)
                    2 -> startSaveTo(entry)
                }
            }
            .show()
    }

    /** 用其它应用打开：强制弹出应用选择器（忽略已记忆的默认应用） */
    private fun openWithExternalApp(entry: FileEntry) {
        if (FileOpen.isMedia(entry)) {
            // 图片/视频已缓存则直接用本地文件弹选择器，未缓存提示先预览
            val f = mediaCacheFile(entry)
            if (f.exists()) {
                scope.launch {
                    FileOpen.openWithPickerAsync(
                        this@BrowseActivity,
                        androidx.core.content.FileProvider.getUriForFile(this@BrowseActivity, "$packageName.fileprovider", f),
                        FileOpen.guessMime(entry.name)
                    )
                }
            } else {
                Toast.makeText(this, getString(R.string.preview_first), Toast.LENGTH_SHORT).show()
            }
            return
        }
        // 非媒体：先下载到缓存，再弹选择器
        val dialog = AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setMessage(getString(R.string.downloading))
            .setCancelable(false)
            .create()
        dialog.show()
        val dest = File(cacheDir, "downloads/${entry.name}").apply { parentFile?.mkdirs() }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { Api.download(server, entry, dest) { _, _ -> } }
                dialog.dismiss()
                FileOpen.openWithPickerAsync(
                    this@BrowseActivity,
                    androidx.core.content.FileProvider.getUriForFile(this@BrowseActivity, "$packageName.fileprovider", dest),
                    FileOpen.guessMime(entry.name)
                )
            } catch (e: AuthException) {
                dialog.dismiss()
                Toast.makeText(this@BrowseActivity, getString(R.string.auth_failed), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                dialog.dismiss()
                Toast.makeText(this@BrowseActivity, "下载失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 与 MediaViewer 相同的媒体缓存路径规则 */
    private fun mediaCacheFile(entry: FileEntry): File {
        val dir = File(cacheDir, "media/${server.id}")
        dir.mkdirs()
        return File(dir, entry.path.replace('/', '_'))
    }

    /** 保存到用户指定位置（系统文件对话框，默认文件名 = 原文件名，可修改） */
    private var pendingSave: FileEntry? = null

    private val saveLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.data
            val entry = pendingSave ?: return@registerForActivityResult
            uri?.let { startSave(entry, it) }
        }

    private fun startSaveTo(entry: FileEntry) {
        pendingSave = entry
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = FileOpen.guessMime(entry.name)
            // 预填原名（含扩展名），格式不会再变成"无名文件"
            putExtra(Intent.EXTRA_TITLE, entry.name)
        }
        saveLauncher.launch(intent)
    }

    private fun startSave(entry: FileEntry, uri: android.net.Uri) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setMessage(getString(R.string.saving))
            .setCancelable(false)
            .create()
        dialog.show()
        scope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    val out = contentResolver.openOutputStream(uri)
                    if (out == null) false else Api.downloadToUri(server, entry, out)
                }
                dialog.dismiss()
                Toast.makeText(
                    this@BrowseActivity,
                    if (ok) getString(R.string.saved) else getString(R.string.save_failed),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: AuthException) {
                dialog.dismiss()
                Toast.makeText(this@BrowseActivity, getString(R.string.auth_failed), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                dialog.dismiss()
                Toast.makeText(this@BrowseActivity, "${getString(R.string.save_failed)}：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun downloadAndOpen(entry: FileEntry) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("${entry.name}")
            .setMessage("下载中…")
            .setCancelable(false)
            .create()
        downloadDialog = dialog
        dialog.show()

        val dest = File(cacheDir, "downloads/${entry.name}").apply {
            parentFile?.mkdirs()
        }
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    Api.download(server, entry, dest) { _, _ ->
                        // 进度回调（如需实时进度可在此更新 UI）
                    }
                }
                dialog.dismiss()
                val ok = FileOpen.openLocalFileAsync(this@BrowseActivity, file)
                if (!ok) Toast.makeText(this@BrowseActivity, getString(R.string.no_app), Toast.LENGTH_SHORT).show()
            } catch (e: AuthException) {
                dialog.dismiss()
                Toast.makeText(this@BrowseActivity, getString(R.string.auth_failed), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                dialog.dismiss()
                Toast.makeText(this@BrowseActivity, "下载失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 上传：读取系统选中的文件 → 流式上传到指定共享目录 → 刷新 */
    private fun startUpload(uri: Uri, dirPath: String) {
        val (name, size) = queryMeta(uri)
        if (name.isNullOrEmpty()) {
            Toast.makeText(this, getString(R.string.read_file_failed), Toast.LENGTH_SHORT).show()
            return
        }
        val input = contentResolver.openInputStream(uri)
            ?: run {
                Toast.makeText(this, getString(R.string.read_file_failed), Toast.LENGTH_SHORT).show()
                return
            }
        val dialog = AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage(getString(R.string.uploading))
            .setCancelable(false)
            .create()
        dialog.show()
        scope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    Api.upload(server, dirPath, name, input, size) { _, _ -> }
                }
                dialog.dismiss()
                if (ok) {
                    Toast.makeText(this@BrowseActivity, getString(R.string.upload_done), Toast.LENGTH_SHORT).show()
                    loadDir(currentPath)
                } else {
                    Toast.makeText(this@BrowseActivity, getString(R.string.upload_failed), Toast.LENGTH_LONG).show()
                }
            } catch (e: AuthException) {
                dialog.dismiss()
                Toast.makeText(this@BrowseActivity, getString(R.string.auth_failed), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                dialog.dismiss()
                Toast.makeText(this@BrowseActivity, "${getString(R.string.upload_failed)}：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 从系统 URI 读取文件名与大小 */
    private fun queryMeta(uri: Uri): Pair<String?, Long> {
        var name: String? = null
        var size = -1L
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) name = c.getString(nameIdx)
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                }
            }
        } catch (_: Exception) {
        }
        return name to size
    }

    inner class FileAdapter(
        private val onClick: (FileEntry) -> Unit,
        private val onLongClick: (FileEntry) -> Unit = {},
        private val onUploadClick: () -> Unit = {}
    ) : RecyclerView.Adapter<FileAdapter.Holder>() {

        private var items: List<FileEntry> = emptyList()

        fun submit(list: List<FileEntry>) {
            items = list
            notifyDataSetChanged()
        }

        fun currentItems(): List<FileEntry> = items

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
            return Holder(v)
        }

        override fun getItemCount(): Int = items.size + 1 // 末尾始终有一个"上传"条目

        override fun onBindViewHolder(holder: Holder, position: Int) {
            if (position == items.size) {
                // 列表底部：上传按钮（上传到当前浏览的文件夹）
                holder.thumbJob?.cancel()
                holder.thumb.visibility = View.GONE
                holder.icon.visibility = View.VISIBLE
                holder.icon.text = "➕"
                holder.name.text = "上传文件到此目录"
                holder.meta.text = "选择手机里的文件，传到当前文件夹"
                holder.itemView.setOnClickListener { onUploadClick() }
                holder.itemView.setOnLongClickListener { false }
                return
            }
            val e = items[position]
            holder.name.text = e.name
            holder.meta.text = if (e.isDir) {
                "文件夹"
            } else {
                "${Api.formatSize(e.size)} · ${formatTime(e.modTime)}"
            }
            // 图片类文件 → 微缩预览图；其它 → 类型图标
            val isImage = !e.isDir && e.ext in IMG_EXTS
            holder.thumbJob?.cancel()
            if (isImage) {
                holder.icon.visibility = View.GONE
                holder.thumb.visibility = View.VISIBLE
                holder.thumb.setImageBitmap(null)
                holder.thumbJob = scope.launch {
                    val bit = withContext(Dispatchers.IO) { loadThumb(e) }
                    if (bit != null) {
                        withContext(Dispatchers.Main) {
                            val p = holder.bindingAdapterPosition
                            if (p != RecyclerView.NO_POSITION && p < items.size && items[p].path == e.path) {
                                holder.thumb.setImageBitmap(bit)
                            }
                        }
                    }
                }
            } else {
                holder.thumb.visibility = View.GONE
                holder.icon.visibility = View.VISIBLE
                holder.icon.text = when {
                    e.isDir -> "📁"
                    e.ext in setOf("mp4", "mkv", "avi", "mov", "webm") -> "🎬"
                    e.ext in setOf("mp3", "wav", "flac", "aac", "ogg") -> "🎵"
                    e.ext == "pdf" -> "📕"
                    e.ext in setOf("doc", "docx") -> "📘"
                    e.ext in setOf("xls", "xlsx") -> "📗"
                    e.ext in setOf("ppt", "pptx") -> "📙"
                    e.ext in setOf("zip", "rar", "7z", "tar", "gz") -> "🗜️"
                    e.ext in setOf("txt", "md", "log", "json", "xml", "csv") -> "📄"
                    else -> "📄"
                }
            }
            holder.itemView.setOnClickListener { onClick(e) }
            holder.itemView.setOnLongClickListener {
                onLongClick(e)
                true
            }
        }

        /** 缩略图加载：内存缓存 → 磁盘缓存 → 服务端请求 */
        private suspend fun loadThumb(e: FileEntry): Bitmap? {
            val key = "${server.id}/${e.path}"
            thumbMem.get(key)?.let { return it }
            val f = File(cacheDir, "thumbs/${server.id}/${e.path.replace('/', '_')}")
            try {
                if (f.exists()) {
                    val b = BitmapFactory.decodeFile(f.absolutePath)
                    if (b != null) {
                        thumbMem.put(key, b)
                        return b
                    }
                }
                val bytes = Api.thumb(server, e)
                f.parentFile?.mkdirs()
                f.writeBytes(bytes)
                val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (b != null) {
                    thumbMem.put(key, b)
                }
                return b
            } catch (_: Exception) {
                return null
            }
        }

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val icon: TextView = v.findViewById(R.id.fileIcon)
            val thumb: ImageView = v.findViewById(R.id.fileThumb)
            val name: TextView = v.findViewById(R.id.fileName)
            val meta: TextView = v.findViewById(R.id.fileMeta)
            var thumbJob: Job? = null
        }
    }

    companion object {
        private const val IMG_EXTS =
            "jpg jpeg png gif webp bmp heic heif"
        private val thumbMem = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }
    }

    private fun formatTime(iso: String): String {
        return try {
            val parsed = java.time.OffsetDateTime.parse(iso)
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(Date.from(parsed.toInstant()))
        } catch (e: Exception) {
            iso
        }
    }
}
