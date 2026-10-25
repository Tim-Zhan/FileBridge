package com.filebridge.app

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.filebridge.app.databinding.ActivityMainBinding

/** 服务器列表：添加 / 编辑 / 删除 / 进入；顶部集成 Tailscale 组网助手 */
class MainActivity : AppCompatActivity(), TailnetManager.Listener {

    private lateinit var binding: ActivityMainBinding
    private val adapter = ServerAdapter { server -> openServer(server) }

    /** VPN 授权 Intent 引用（startActivityForResult 后置空） */
    var vpnPrepareRequest: Intent? = null

    private lateinit var tvNetState: TextView
    private lateinit var btnNetAction: Button
    private lateinit var btnNetLogout: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 启动时清理 24 小时前的预览缓存（防异常退出残留）
        CacheManager.cleanup(this, 24 * 60 * 60 * 1000L)

        binding.serverList.layoutManager = LinearLayoutManager(this)
        binding.serverList.adapter = adapter
        refreshList()

        binding.fabAdd.setOnClickListener { showServerDialog(null) }

        tvNetState = binding.netState
        btnNetAction = binding.btnNetAction
        btnNetLogout = binding.btnNetLogout

        btnNetAction.setOnClickListener {
            // 组网核心按需启动：点击按钮时才初始化（失败只影响组网，不影响 App）
            TailnetManager.ensureInit(this)
            when (TailnetManager.currentInfo().state) {
                TailnetManager.NetState.Ready,
                TailnetManager.NetState.NeedsLogin -> {
                    if (TailnetManager.currentInfo().state == TailnetManager.NetState.Ready) {
                        TailnetManager.startVpn(this)
                    } else {
                        TailnetManager.login()
                    }
                }
                TailnetManager.NetState.Connected -> TailnetManager.stopVpn()
                else -> TailnetManager.login()
            }
        }
        btnNetLogout.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("退出组网登录")
                .setMessage("将清除这台手机的组网登录状态并断开连接，确定？")
                .setPositiveButton("退出") { _, _ -> TailnetManager.logout() }
                .setNegativeButton("取消", null)
                .show()
        }

        // 诊断日志：闪退后重开 App，可导出 diag.log 用于定位
        binding.btnExportLog.setOnClickListener {
            exportDiagnosticLog()
        }

        TailnetManager.addListener(this)
        onNetInfoChanged(TailnetManager.currentInfo())
    }

    override fun onDestroy() {
        TailnetManager.removeListener(this)
        // 退出 App 时清理 30 分钟前的预览缓存（刚预览的最近文件保留，其余清掉）
        CacheManager.cleanup(this, 30 * 60 * 1000L)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN_PREPARE) {
            if (resultCode == RESULT_OK) {
                TailnetManager.onVpnPrepared(this)
            } else {
                Toast.makeText(this, "未授权 VPN，无法建立组网连接", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onNetInfoChanged(info: TailnetManager.NetInfo) {
        runOnUiThread {
            tvNetState.text = info.detail
            when (info.state) {
                TailnetManager.NetState.Connected -> {
                    btnNetAction.text = "断开组网"
                    btnNetLogout.visibility = View.GONE
                }
                TailnetManager.NetState.Ready -> {
                    btnNetAction.text = "连接组网"
                    btnNetLogout.visibility = View.VISIBLE
                }
                TailnetManager.NetState.NeedsLogin -> {
                    btnNetAction.text = "登录组网"
                    btnNetLogout.visibility = View.GONE
                }
                TailnetManager.NetState.Error -> {
                    btnNetAction.text = "登录组网"
                    btnNetLogout.visibility = View.GONE
                }
                else -> {
                    btnNetAction.text = "登录组网"
                    btnNetLogout.visibility = View.GONE
                }
            }
        }
    }

    private fun refreshList() {
        val servers = ServerStore.list(this)
        adapter.submit(servers)
        binding.emptyView.visibility = if (servers.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openServer(server: ServerInfo) {
        val intent = Intent(this, BrowseActivity::class.java)
        intent.putExtra("serverId", server.id)
        startActivity(intent)
    }

    /** 添加 / 编辑服务器 */
    private fun showServerDialog(existing: ServerInfo?) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_server, null)
        val etName = view.findViewById<EditText>(R.id.etName)
        val etHost = view.findViewById<EditText>(R.id.etHost)
        val etPort = view.findViewById<EditText>(R.id.etPort)
        val etUser = view.findViewById<EditText>(R.id.etUser)
        val etPass = view.findViewById<EditText>(R.id.etPass)

        if (existing != null) {
            etName.setText(existing.name)
            etHost.setText(existing.host)
            etPort.setText(existing.port.toString())
            etUser.setText(existing.username)
            etPass.setText(existing.password)
        }

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "添加服务器" else "编辑服务器")
            .setView(view)
            .setPositiveButton("保存") { _, _ ->
                val name = etName.text.toString().trim()
                val host = etHost.text.toString().trim()
                val port = etPort.text.toString().trim().toIntOrNull() ?: 8123
                val user = etUser.text.toString().trim()
                val pass = etPass.text.toString()
                if (name.isEmpty() || host.isEmpty() || user.isEmpty() || pass.isEmpty()) {
                    Toast.makeText(this, "请完整填写名称、地址、用户名、密码", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val server = ServerInfo(
                    id = existing?.id ?: ServerStore.newId(),
                    name = name,
                    host = host,
                    port = port,
                    username = user,
                    password = pass
                )
                if (existing == null) ServerStore.add(this, server) else ServerStore.update(this, server)
                refreshList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    companion object {
        const val REQ_VPN_PREPARE = 9001
    }

    /** 通过系统分享导出诊断日志（直接发文本，不依赖文件路径，最稳） */
    private fun exportDiagnosticLog() {
        try {
            val logFile = CrashLog.getLogFile() ?: run {
                Toast.makeText(this, "暂无诊断日志", Toast.LENGTH_SHORT).show()
                return
            }
            if (!logFile.exists()) {
                Toast.makeText(this, "暂无诊断日志", Toast.LENGTH_SHORT).show()
                return
            }
            val all = logFile.readText()
            val lines = all.lines()
            val tail = lines.takeLast(200).joinToString("\n")
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(
                    Intent.EXTRA_TEXT,
                    "FileBridge 诊断日志（共 ${lines.size} 行，取末尾 200 行）：\n\n$tail"
                )
            }
            startActivity(Intent.createChooser(share, "导出诊断日志"))
        } catch (e: Exception) {
            CrashLog.log("ExportLog", "导出失败: ${e.message}\n${e.stackTraceToString()}")
            Toast.makeText(this, "导出失败，已把错误写入日志: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    inner class ServerAdapter(
        private val onClick: (ServerInfo) -> Unit
    ) : RecyclerView.Adapter<ServerAdapter.Holder>() {

        private var items: List<ServerInfo> = emptyList()

        fun submit(list: List<ServerInfo>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_server, parent, false)
            return Holder(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val s = items[position]
            holder.name.text = s.name
            holder.addr.text = "${s.displayAddr}  (${s.username})"
            holder.itemView.setOnClickListener { onClick(s) }
            holder.itemView.setOnLongClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(s.name)
                    .setItems(arrayOf("编辑", "删除")) { _, which ->
                        when (which) {
                            0 -> showServerDialog(s)
                            1 -> {
                                ServerStore.remove(this@MainActivity, s.id)
                                refreshList()
                            }
                        }
                    }
                    .show()
                true
            }
        }

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.serverName)
            val addr: TextView = v.findViewById(R.id.serverAddr)
        }
    }
}
