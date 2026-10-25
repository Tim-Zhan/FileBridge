package com.filebridge.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import libtailscale.Application
import libtailscale.InputStream
import libtailscale.Libtailscale
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * Tailscale 组网管理器：App 内直接登录 Tailscale、建立 VPN、暴露本机组网 IP。
 * 用户不再需要单独安装 Tailscale App——FileBridge 自带完整组网能力。
 */
object TailnetManager {

    private const val TAG = "TailnetMgr"
    private const val CHANNEL_ID = "filebridge_vpn"

    /** 组网状态 */
    enum class NetState {
        /** 未初始化 / 从未登录过 */
        Idle,
        /** 需要登录（或登录失效） */
        NeedsLogin,
        /** 登录成功，等待建立 VPN */
        Ready,
        /** VPN 已建立，流量走组网 */
        Connected,
        /** 出错 */
        Error
    }

    data class NetInfo(
        val state: NetState,
        val ip: String = "",
        val detail: String = "",
        val authUrl: String = ""
    )

    interface Listener {
        fun onNetInfoChanged(info: NetInfo)
    }

    @Volatile
    private var application: Application? = null

    @Volatile
    private var context: Context? = null

    @Volatile
    private var initFailedMsg: String? = null

    @Volatile
    private var service: FileBridgeVpnService? = null

    @Volatile
    private var vpnActive = false

    @Volatile
    var underlyingNetwork: Network? = null
        private set

    private val infoRef = AtomicReference(NetInfo(NetState.Idle, detail = "组网未初始化"))
    private val listeners = mutableListOf<Listener>()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var pollJob: Job? = null

    /**
     * 按需初始化组网核心（幂等）。只在用户真正使用组网功能时调用，
     * 全程 try-catch：失败不会崩溃，只会让组网不可用，其余功能不受影响。
     */
    fun ensureInit(ctx: Context) {
        if (application != null) return
        if (initFailedMsg != null) return
        synchronized(this) {
            if (application != null) return
            context = ctx.applicationContext
            createNotificationChannel(ctx)
            try {
                val dataDir = ctx.applicationContext.filesDir.absolutePath + "/tailscale"
                application = Libtailscale.start(
                    dataDir,
                    "",
                    false,
                    AppContextImpl(ctx.applicationContext)
                )
                Log.d(TAG, "Tailscale 核心已启动")
                scope.launch {
                    refreshStatus()
                    startPolling()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Tailscale 核心启动失败: ${e.message}")
                markInitFailed(e.message ?: "未知错误")
            }
        }
    }

    /** 组网核心启动失败时置位（App 降级为局域网模式，不闪退） */
    fun markInitFailed(msg: String) {
        initFailedMsg = msg
        infoRef.set(NetInfo(NetState.Error, detail = "组网不可用：$msg"))
        notifyListeners()
    }

    /** 每 2 秒轮询一次组网状态 */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(2000)
                try {
                    refreshStatus()
                } catch (_: Exception) {
                }
            }
        }
    }

    fun addListener(l: Listener) {
        synchronized(listeners) {
            if (!listeners.contains(l)) listeners.add(l)
        }
        l.onNetInfoChanged(infoRef.get())
    }

    fun removeListener(l: Listener) {
        synchronized(listeners) { listeners.remove(l) }
    }

    private fun notifyListeners() {
        val info = infoRef.get()
        synchronized(listeners) {
            listeners.forEach { it.onNetInfoChanged(info) }
        }
    }

    fun currentInfo(): NetInfo = infoRef.get()

    /** 触发交互式登录（浏览器打开 Tailscale 授权页） */
    fun login() {
        val app = application ?: return
        scope.launch {
            CrashLog.log("Tailnet", "login: 开始（state=${TailnetManager.currentInfo().state}）")
            try {
                infoRef.set(NetInfo(NetState.NeedsLogin, detail = "正在启动组网并打开授权页…"))
                notifyListeners()
                // 官方登录流程：先 editPrefs 取完整配置，再 start 启动后端，最后 login-interactive
                CrashLog.log("Tailnet", "login: 调用 PATCH /localapi/v0/prefs")
                val prefsResp = app.callLocalAPI(
                    30000, "PATCH", "/localapi/v0/prefs", StringBody("{}")
                )
                val prefsBody = String(prefsResp.bodyBytes() ?: ByteArray(0))
                Log.d(TAG, "prefs: ${prefsResp.statusCode()} $prefsBody")
                CrashLog.log("Tailnet", "login: prefs 返回 ${prefsResp.statusCode()} $prefsBody")
                if (prefsResp.statusCode() !in 200L..299L) {
                    CrashLog.log("Tailnet", "prefs 非 2xx，body=$prefsBody")
                    infoRef.set(NetInfo(NetState.Error, detail = "读取组网配置失败 (${prefsResp.statusCode()}): ${prefsBody.take(200)}"))
                    notifyListeners()
                    return@launch
                }
                // 把返回的完整 Prefs 加 WantRunning=true 作为 start 的 UpdatePrefs（与官方一致）
                val prefsJson = JSONObject(prefsBody)
                prefsJson.put("WantRunning", true)
                val opts = JSONObject()
                    .put("UpdatePrefs", prefsJson)
                    .put("AuthKey", JSONObject.NULL)
                CrashLog.log("Tailnet", "login: 调用 /localapi/v0/start")
                val startResp = app.callLocalAPI(
                    30000, "POST", "/localapi/v0/start", StringBody(opts.toString())
                )
                val startBody = String(startResp.bodyBytes() ?: ByteArray(0))
                Log.d(TAG, "start: ${startResp.statusCode()} $startBody")
                CrashLog.log("Tailnet", "login: start 返回 ${startResp.statusCode()} $startBody")
                if (startResp.statusCode() !in 200L..299L) {
                    CrashLog.log("Tailnet", "start 非 2xx，body=$startBody")
                    infoRef.set(NetInfo(NetState.Error, detail = "组网启动失败 (${startResp.statusCode()}): ${startBody.take(200)}"))
                    notifyListeners()
                    return@launch
                }
                CrashLog.log("Tailnet", "login: 调用 /localapi/v0/login-interactive")
                val resp = app.callLocalAPI(30000, "POST", "/localapi/v0/login-interactive", null)
                val body = String(resp.bodyBytes() ?: ByteArray(0))
                Log.d(TAG, "login-interactive: ${resp.statusCode()} $body")
                CrashLog.log("Tailnet", "login: login-interactive 返回 ${resp.statusCode()} $body")
                if (resp.statusCode() !in 200L..299L) {
                    infoRef.set(NetInfo(NetState.Error, detail = "登录请求失败 (${resp.statusCode()})"))
                    notifyListeners()
                    return@launch
                }
                // 打开授权页（轮询会检测到 AuthURL）
                var triedBrowser = false
                repeat(20) {
                    if (!isActive) return@launch
                    delay(1500)
                    val info = refreshStatusInternal()
                    if (info.authUrl.isNotEmpty() && !triedBrowser) {
                        triedBrowser = true
                        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(info.authUrl))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context?.startActivity(intent)
                    }
                    if (info.state == NetState.Ready || info.state == NetState.Connected) return@launch
                }
            } catch (e: Exception) {
                Log.e(TAG, "login 异常: ${e.message}")
                infoRef.set(NetInfo(NetState.Error, detail = "登录出错: ${e.message}"))
                notifyListeners()
            }
        }
    }

    /** 读取组网状态（本地 API，不经网络） */
    fun refreshStatus() {
        val info = refreshStatusInternal()
        infoRef.set(info)
        notifyListeners()
    }

    private fun refreshStatusInternal(): NetInfo {
        val app = application ?: return NetInfo(NetState.Idle, detail = "未初始化")
        return try {
            val resp = app.callLocalAPI(10000, "GET", "/localapi/v0/status", null)
            val body = String(resp.bodyBytes() ?: ByteArray(0))
            val json = JSONObject(body)
            val state = json.optString("BackendState", "Unknown")
            val authUrl = json.optString("AuthURL", "")
            var ip = ""
            val self = json.optJSONObject("Self")
            val ips = self?.optJSONArray("TailscaleIPs")
            if (ips != null && ips.length() > 0) {
                ip = ips.getString(0)
            }
            when (state) {
                "Running" -> {
                    val detail = if (vpnActive) "已连接 · $ip" else "已登录 · $ip（点“连接组网”开启 VPN）"
                    NetInfo(
                        if (vpnActive) NetState.Connected else NetState.Ready,
                        ip = ip,
                        detail = detail
                    )
                }
                "NeedsLogin", "NoState" -> {
                    if (authUrl.isNotEmpty()) {
                        NetInfo(NetState.NeedsLogin, detail = "点击“登录组网”完成授权", authUrl = authUrl)
                    } else {
                        NetInfo(NetState.NeedsLogin, detail = "尚未登录组网")
                    }
                }
                "Starting" -> NetInfo(NetState.NeedsLogin, detail = "正在连接…")
                else -> NetInfo(NetState.Error, detail = "组网状态: $state")
            }
        } catch (e: Exception) {
            NetInfo(NetState.Error, detail = "读取状态失败: ${e.message}")
        }
    }

    /** 请求开启 VPN（会先弹系统授权确认） */
    fun startVpn(activity: MainActivity) {
        val prepareIntent = VpnService.prepare(activity)
        if (prepareIntent != null) {
            activity.vpnPrepareRequest = prepareIntent
            activity.startActivityForResult(prepareIntent, MainActivity.REQ_VPN_PREPARE)
        } else {
            doStartVpn(activity)
        }
    }

    fun onVpnPrepared(activity: MainActivity) {
        doStartVpn(activity)
    }

    private fun doStartVpn(activity: MainActivity) {
        val intent = Intent(activity, FileBridgeVpnService::class.java).apply {
            action = FileBridgeVpnService.ACTION_START_VPN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.startForegroundService(intent)
        } else {
            activity.startService(intent)
        }
    }

    fun stopVpn() {
        val ctx = context ?: return
        val intent = Intent(ctx, FileBridgeVpnService::class.java).apply {
            action = FileBridgeVpnService.ACTION_STOP_VPN
        }
        ctx.startService(intent)
    }

    fun onVpnStarting(svc: FileBridgeVpnService) {
        service = svc
        registerNetworkCallback(svc)
        vpnActive = true
        refreshStatus()
    }

    fun onVpnStatusChanged(active: Boolean) {
        vpnActive = active
        refreshStatus()
    }

    fun onVpnStopped() {
        vpnActive = false
        service = null
        refreshStatus()
    }

    fun onVpnRevoked() {
        vpnActive = false
        service = null
        refreshStatus()
    }

    private fun registerNetworkCallback(svc: FileBridgeVpnService) {
        try {
            val cm = svc.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val req = android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    underlyingNetwork = network
                }

                override fun onLost(network: Network) {
                    if (underlyingNetwork === network) underlyingNetwork = null
                }
            })
        } catch (_: Exception) {
        }
    }

    fun buildVpnNotification(ctx: Context): Notification {
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(ctx, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(ctx)
            }
        val info = infoRef.get()
        val contentIntent = PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            ctx,
            1,
            Intent(ctx, FileBridgeVpnService::class.java).apply {
                action = FileBridgeVpnService.ACTION_STOP_VPN
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.setContentTitle("FileBridge 组网已连接")
            .setContentText("本机组网地址 ${info.ip.ifBlank { "…" }}，可访问电脑文件")
            .setSmallIcon(R.drawable.ic_add)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(0, "断开", stopIntent)
        return builder.build()
    }

    private fun createNotificationChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "组网连接",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    /** 退出组网（清除登录态并断开） */
    fun logout() {
        val app = application ?: return
        scope.launch {
            try {
                app.callLocalAPI(15000, "POST", "/localapi/v0/logout", null)
            } catch (_: Exception) {
            }
            infoRef.set(NetInfo(NetState.Idle, detail = "已退出组网"))
            notifyListeners()
        }
    }

    /** 释放资源（一般用不到，进程随 App 退出） */
    fun shutdown() {
        pollJob?.cancel()
        stopVpn()
    }

    /** 把字符串包装成 gomobile InputStream（callLocalAPI 的请求体） */
    private class StringBody(private val content: String) : InputStream {
        private val data = content.toByteArray(Charsets.UTF_8)
        private var consumed = 0

        override fun read(): ByteArray? {
            if (consumed >= data.size) return null
            val chunk = data.copyOfRange(consumed, data.size)
            consumed = data.size
            return chunk
        }

        override fun close() {
        }
    }
}
