package com.filebridge.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import libtailscale.AppContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface

/**
 * libtailscale 的 Java 侧上下文实现：设备信息 / 状态存取 / 日志等。
 * 返回值严格对齐官方 App.kt 实现（JSON 格式），避免 Go 侧解析崩溃。
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

    /** 官方要求 JSON 数组字符串（如 ["key1","key2"]），解析失败会让 Go 侧 store 读不到数据 */
    override fun getStateStoreKeysJSON(): String {
        val keys = prefs.all.keys
            .filter { it.startsWith("statestore-") }
            .map { it.removePrefix("statestore-") }
        return JSONArray(keys).toString()
    }

    override fun getOSVersion(): String = Build.VERSION.RELEASE

    override fun getSDKInt(): Long = Build.VERSION.SDK_INT.toLong()

    override fun getDeviceName(): String =
        Build.MODEL.ifBlank { Build.DEVICE }

    override fun getInstallSource(): String = "unknown"

    override fun shouldUseGoogleDNSFallback(): Boolean = false

    override fun isChromeOS(): Boolean = false

    override fun isClientLoggingEnabled(): Boolean = false

    /** 真实网络接口 JSON，格式与官方一致；无接口时返回 []（空数组合法） */
    override fun getInterfacesAsJson(): String {
        val arr = JSONArray()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return arr.toString()
            for (nif in interfaces) {
                try {
                    val obj = JSONObject()
                    obj.put("name", nif.name)
                    obj.put("index", nif.index)
                    obj.put("mtu", nif.mtu)
                    obj.put("up", nif.isUp)
                    obj.put("broadcast", nif.supportsMulticast())
                    obj.put("loopback", nif.isLoopback)
                    obj.put("pointToPoint", nif.isPointToPoint)
                    obj.put("multicast", nif.supportsMulticast())
                    val addrs = JSONArray()
                    for (ia in nif.interfaceAddresses) {
                        val addr = ia.address ?: continue
                        val host = addr.hostAddress ?: continue
                        val a = JSONObject()
                        a.put("ip", host)
                        a.put("prefixLen", ia.networkPrefixLength.toInt())
                        addrs.put(a)
                    }
                    obj.put("addrs", addrs)
                    arr.put(obj)
                } catch (_: Exception) {
                    // 单个接口失败不影响整体
                }
            }
        } catch (_: Exception) {
        }
        return arr.toString()
    }

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
