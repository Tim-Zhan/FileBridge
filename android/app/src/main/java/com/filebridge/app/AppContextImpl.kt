package com.filebridge.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import libtailscale.AppContext

/**
 * libtailscale 的 Java 侧上下文实现：设备信息 / 状态存取 / 日志等。
 * 精简实现，满足 Go 后端运行所需的最小能力。
 */
class AppContextImpl(private val ctx: Context) : AppContext {

    private val prefs: SharedPreferences =
        ctx.getSharedPreferences("tailscale_state", Context.MODE_PRIVATE)

    override fun log(tag: String, logLine: String) {
        Log.d("FileBridgeTailscale[$tag]", logLine)
    }

    override fun encryptToPref(key: String, value: String) {
        prefs.edit().putString("statestore-$key", value).apply()
    }

    override fun decryptFromPref(key: String): String {
        return prefs.getString("statestore-$key", "") ?: ""
    }

    override fun getStateStoreKeysJSON(): String {
        val keys = prefs.all.keys
            .filter { it.startsWith("statestore-") }
            .map { it.removePrefix("statestore-") }
        return keys.toString()
    }

    override fun getOSVersion(): String = Build.VERSION.RELEASE

    override fun getSDKInt(): Long = Build.VERSION.SDK_INT.toLong()

    override fun getDeviceName(): String =
        Build.MODEL.ifBlank { Build.DEVICE }

    override fun getInstallSource(): String = "unknown"

    override fun shouldUseGoogleDNSFallback(): Boolean = false

    override fun isChromeOS(): Boolean = false

    override fun isClientLoggingEnabled(): Boolean = false

    override fun getInterfacesAsJson(): String = "{}"

    override fun getPlatformDNSConfig(): String = ""

    override fun getSyspolicyStringValue(key: String): String = ""

    override fun getSyspolicyBooleanValue(key: String): Boolean = false

    override fun getSyspolicyStringArrayJSONValue(key: String): String = ""

    override fun hardwareAttestationKeySupported(): Boolean = false

    override fun hardwareAttestationKeyCreate(): String = throw IllegalStateException("not supported")

    override fun hardwareAttestationKeyRelease(id: String) {}

    override fun hardwareAttestationKeyPublic(id: String): ByteArray =
        throw IllegalStateException("not supported")

    override fun hardwareAttestationKeySign(id: String, data: ByteArray): ByteArray =
        throw IllegalStateException("not supported")

    override fun hardwareAttestationKeyLoad(id: String) {}

    override fun bindSocketToNetwork(fd: Int): Boolean = false

    override fun getUserCACertsPEM(): ByteArray = ByteArray(0)
}
