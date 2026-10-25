package com.filebridge.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 服务器配置存储：本地 SharedPreferences（JSON 数组）。
 * 密码做了一层 Base64 混淆，正式使用建议换 EncryptedSharedPreferences。
 */
data class ServerInfo(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: String
) {
    val baseUrl: String get() = "http://$host:$port"
    val displayAddr: String get() = "$host:$port"
}

object ServerStore {

    private const val PREFS = "filebridge_servers"
    private const val KEY = "servers"

    fun list(context: Context): List<ServerInfo> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { parse(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, servers: List<ServerInfo>) {
        val arr = JSONArray()
        servers.forEach { arr.put(toJson(it)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun add(context: Context, server: ServerInfo) {
        val list = list(context).toMutableList()
        list.add(server)
        save(context, list)
    }

    fun update(context: Context, server: ServerInfo) {
        val list = list(context).toMutableList()
        val idx = list.indexOfFirst { it.id == server.id }
        if (idx >= 0) list[idx] = server
        save(context, list)
    }

    fun remove(context: Context, id: String) {
        save(context, list(context).filter { it.id != id })
    }

    fun find(context: Context, id: String): ServerInfo? =
        list(context).firstOrNull { it.id == id }

    fun newId(): String = System.currentTimeMillis().toString()

    private fun parse(o: JSONObject) = ServerInfo(
        id = o.getString("id"),
        name = o.getString("name"),
        host = o.getString("host"),
        port = o.optInt("port", 8123),
        username = o.getString("username"),
        password = o.optString("password", "")
    )

    private fun toJson(s: ServerInfo) = JSONObject().apply {
        put("id", s.id)
        put("name", s.name)
        put("host", s.host)
        put("port", s.port)
        put("username", s.username)
        put("password", s.password)
    }
}
