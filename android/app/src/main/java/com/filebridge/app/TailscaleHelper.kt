package com.filebridge.app

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Tailscale 组网助手：不内嵌 Tailscale 核心。
 * 「组网」交给官方 Tailscale App（未安装则引导下载安装），
 * FileBridge 只负责检测状态、一键跳转，保持轻量稳定。
 */
object TailscaleHelper {

    const val TS_PACKAGE = "com.tailscale.ipn"
    const val TS_DOWNLOAD_URL = "https://github.com/tailscale/tailscale-android/releases/latest"

    /** 官方 Tailscale App 是否已安装 */
    fun isTailscaleInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(TS_PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }

    /** VPN 通道是否开启（Tailscale 组网成功的标志） */
    fun isVpnActive(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    } catch (_: Exception) {
        false
    }

    /** 启动官方 Tailscale App（未安装返回 false） */
    fun launchTailscale(ctx: Context): Boolean = try {
        val intent = ctx.packageManager.getLaunchIntentForPackage(TS_PACKAGE)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            true
        } else {
            false
        }
    } catch (_: Exception) {
        false
    }

    /** 打开下载页 */
    fun openDownloadPage(ctx: Context): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(TS_DOWNLOAD_URL))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }

    /** 从 assets 内置安装包弹系统安装器（捆绑安装） */
    fun installBundledApk(ctx: Context): Boolean = try {
        val apkFile = File(ctx.cacheDir, "tailscale.apk")
        ctx.assets.open("tailscale.apk").use { input ->
            apkFile.outputStream().use { output -> input.copyTo(output) }
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }
}
