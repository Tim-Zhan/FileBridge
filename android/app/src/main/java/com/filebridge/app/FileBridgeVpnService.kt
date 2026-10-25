package com.filebridge.app

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import android.system.OsConstants
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import libtailscale.Libtailscale
import libtailscale.VPNServiceBuilder
import java.util.UUID

/**
 * 组网 VPN 服务：系统级流量走 Tailscale 网络。
 * Go 后端通过 newBuilder() 配置并建立虚拟网卡，建立后手机所有 App
 * （含系统播放器 / WPS / 浏览器）都能直接访问 100.x 组网地址。
 */
class FileBridgeVpnService : VpnService(), libtailscale.IPNService {

    private val tag = "FileBridgeVpn"
    private val instanceId: String = UUID.randomUUID().toString()
    private var closed = false

    override fun id(): String = instanceId

    override fun protect(fd: Int): Boolean {
        return try {
            super.protect(fd)
        } catch (e: Exception) {
            Log.e(tag, "protect 失败: ${e.message}")
            false
        }
    }

    override fun newBuilder(): VPNServiceBuilder {
        val b: Builder = Builder()
            .setConfigureIntent(configIntent())
            .allowFamily(OsConstants.AF_INET)
            .allowFamily(OsConstants.AF_INET6)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            b.setMetered(false)
        }
        val defaultNetwork = TailnetManager.underlyingNetwork
        b.setUnderlyingNetworks(defaultNetwork?.let { arrayOf(it) } ?: emptyArray())
        return FbVpnBuilder(b)
    }

    private fun configIntent(): PendingIntent {
        return PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        disconnectVPN()
        Libtailscale.serviceDisconnect(this)
    }

    override fun disconnectVPN() {
        TailnetManager.onVpnStopped()
        stopSelf()
    }

    override fun updateVpnStatus(status: Boolean) {
        TailnetManager.onVpnStatusChanged(status)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_VPN -> {
                close()
                return START_NOT_STICKY
            }
            else -> {
                TailnetManager.onVpnStarting(this)
                // 前台服务通知（组网期间避免进程被回收）
                try {
                    startForeground(1, TailnetManager.buildVpnNotification(this))
                } catch (_: Exception) {
                }
                Libtailscale.requestVPN(this)
                return START_STICKY
            }
        }
    }

    override fun onRevoke() {
        TailnetManager.onVpnRevoked()
        closed = true
        super.onRevoke()
    }

    override fun onDestroy() {
        close()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_VPN = "com.filebridge.app.START_VPN"
        const val ACTION_STOP_VPN = "com.filebridge.app.STOP_VPN"
    }
}

/** Android VpnService.Builder 的薄封装，供 Go 侧配置虚拟网卡 */
private class FbVpnBuilder(private val builder: VpnService.Builder) : VPNServiceBuilder {
    override fun setMTU(mtu: Int) {
        builder.setMtu(mtu)
    }

    override fun addDNSServer(server: String) {
        builder.addDnsServer(server)
    }

    override fun addSearchDomain(domain: String) {
        builder.addSearchDomain(domain)
    }

    override fun addRoute(route: String, prefixLength: Int) {
        builder.addRoute(route, prefixLength)
    }

    override fun excludeRoute(route: String, prefixLength: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            builder.excludeRoute(
                android.net.IpPrefix(java.net.InetAddress.getByName(route), prefixLength)
            )
        }
    }

    override fun addAddress(address: String, prefixLength: Int) {
        builder.addAddress(address, prefixLength)
    }

    override fun establish(): libtailscale.ParcelFileDescriptor? {
        return builder.establish()?.let { FbParcelFileDescriptor(it) }
    }
}

private class FbParcelFileDescriptor(
    private val fd: android.os.ParcelFileDescriptor
) : libtailscale.ParcelFileDescriptor {
    override fun detach(): Int = fd.detachFd()
}
