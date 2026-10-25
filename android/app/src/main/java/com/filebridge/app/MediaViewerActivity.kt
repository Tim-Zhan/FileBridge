package com.filebridge.app

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.filebridge.app.databinding.ActivityMediaViewerBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 相册式媒体浏览：图片/视频左右滑动切换上一个/下一个。
 * 进入后按需下载当前目录的媒体到缓存，图片直接显示，视频用内置播放器播放；
 * 右上角"⋯"可随时改用手机上的其它软件打开当前文件。
 */
class MediaViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMediaViewerBinding
    private lateinit var server: ServerInfo
    private lateinit var entries: List<FileEntry>
    private var currentIndex = 0

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val adapter = MediaPagerAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMediaViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val serverId = intent.getStringExtra("serverId") ?: run { finish(); return }
        server = ServerStore.find(this, serverId) ?: run { finish(); return }
        val paths = intent.getStringArrayExtra("paths") ?: run { finish(); return }
        currentIndex = intent.getIntExtra("index", 0).coerceIn(0, paths.lastIndex)

        entries = paths.map { p ->
            FileEntry(
                name = p.substringAfterLast('/'),
                isDir = false,
                size = 0,
                modTime = "",
                path = p
            )
        }

        binding.viewPager.adapter = adapter
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                onPageChanged(position)
            }
        })
        binding.viewPager.setCurrentItem(currentIndex, false)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnExternal.setOnClickListener { openWithExternal() }

        onPageChanged(currentIndex)
    }

    private fun onPageChanged(index: Int) {
        currentIndex = index
        binding.fileName.text = entries[index].name
        binding.pageIndicator.text = "${index + 1} / ${entries.size}"
        adapter.onPageSelected(index)
    }

    /** 用手机上的其它软件打开当前文件（带记忆选择器） */
    private fun openWithExternal() {
        // 先给即时反馈，避免查询期间看起来"点不动"
        Toast.makeText(this, "正在查找可用的应用…", Toast.LENGTH_SHORT).show()
        val entry = entries[currentIndex]
        scope.launch {
            if (FileOpen.isVideo(entry.name)) {
                FileOpen.openStreamAsync(this@MediaViewerActivity, Api.streamUrl(server, entry), entry)
            } else {
                val f = cacheFile(entry)
                if (f.exists()) {
                    FileOpen.openLocalFileAsync(this@MediaViewerActivity, f)
                } else {
                    Toast.makeText(this@MediaViewerActivity, "文件加载中，请稍候", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 媒体缓存位置（按服务器+路径去重，避免同名文件串台） */
    private fun cacheFile(entry: FileEntry): File {
        val dir = File(cacheDir, "media/${server.id}")
        dir.mkdirs()
        return File(dir, entry.path.replace('/', '_'))
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[Job]?.cancel()
    }

    // ---------------- Pager ----------------

    inner class MediaPagerAdapter : RecyclerView.Adapter<MediaPagerAdapter.Holder>() {

        private var currentPos = 0
        private val holders = android.util.SparseArray<Holder>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_media_page, parent, false)
            return Holder(v)
        }

        override fun getItemCount(): Int = entries.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holders.put(position, holder)
            holder.reset()
            val entry = entries[position]
            holder.job = scope.launch {
                val file = withContext(Dispatchers.IO) {
                    val f = cacheFile(entry)
                    if (f.exists()) {
                        f
                    } else {
                        try {
                            Api.download(server, entry, f) { _, _ -> }
                            f
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
                if (file == null) {
                    holder.progress.visibility = View.GONE
                    return@launch
                }
                if (FileOpen.isImage(entry.name)) {
                    holder.progress.visibility = View.GONE
                    holder.image.visibility = View.VISIBLE
                    holder.image.setImageBitmap(
                        decodeSampledBitmap(file, 2048)
                    )
                } else if (position == currentPos) {
                    holder.progress.visibility = View.GONE
                    holder.playerView.visibility = View.VISIBLE
                    holder.play(file)
                } else {
                    holder.progress.visibility = View.GONE
                    holder.playerView.visibility = View.VISIBLE
                }
            }
        }

        override fun onViewRecycled(holder: Holder) {
            holder.release()
            holder.job?.cancel()
        }

        /** 页面切换：只保留当前页的播放器，其余释放 */
        fun onPageSelected(pos: Int) {
            currentPos = pos
            for (i in 0 until holders.size()) {
                val h = holders.valueAt(i)
                if (holders.keyAt(i) != pos) h.release()
            }
            val holder = holders[pos] ?: return
            val entry = entries[pos]
            if (FileOpen.isVideo(entry.name) && cacheFile(entry).exists()) {
                holder.progress.visibility = View.GONE
                holder.playerView.visibility = View.VISIBLE
                holder.play(cacheFile(entry))
            }
        }

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val progress: ProgressBar = v.findViewById(R.id.mediaProgress)
            val image: ImageView = v.findViewById(R.id.mediaImage)
            val playerView: PlayerView = v.findViewById(R.id.mediaPlayer)
            var player: ExoPlayer? = null
            var job: Job? = null

            fun reset() {
                release()
                job?.cancel()
                progress.visibility = View.VISIBLE
                image.visibility = View.GONE
                playerView.visibility = View.GONE
            }

            fun play(file: File) {
                if (player != null) return
                val p = ExoPlayer.Builder(this@MediaViewerActivity).build()
                player = p
                playerView.player = p
                p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                p.prepare()
                p.playWhenReady = true
            }

            fun release() {
                player?.release()
                player = null
                playerView.player = null
            }
        }
    }

    /** 采样解码大图，避免 OOM（目标长边上限像素） */
    private fun decodeSampledBitmap(file: File, maxEdge: Int): android.graphics.Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            val w = bounds.outWidth
            val h = bounds.outHeight
            while (w / sample > maxEdge * 2 || h / sample > maxEdge * 2) sample *= 2
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                }
            )
        } catch (e: Exception) {
            null
        }
    }
}
