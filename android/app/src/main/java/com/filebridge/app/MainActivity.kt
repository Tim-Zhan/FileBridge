package com.filebridge.app

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.filebridge.app.databinding.ActivityMainBinding

/** 服务器列表：添加 / 编辑 / 删除 / 进入 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val adapter = ServerAdapter { server -> openServer(server) }

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
    }

    override fun onDestroy() {
        super.onDestroy()
        // 退出 App 时清理 30 分钟前的预览缓存（刚预览的最近文件保留，其余清掉）
        CacheManager.cleanup(this, 30 * 60 * 1000L)
    }

    override fun onResume() {
        super.onResume()
        refreshList()
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
