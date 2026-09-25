package com.mtdownloader.app

import android.content.Context
import android.net.wifi.WifiManager
import android.view.View
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class LanPage(private val act: MainActivity, private val v: View) : Page {

    private val cbShare = v.findViewById<CheckBox>(R.id.cbShare)
    private val tvSelf = v.findViewById<TextView>(R.id.tvSelf)
    private val btnScan = v.findViewById<Button>(R.id.btnScan)
    private val tvScan = v.findViewById<TextView>(R.id.tvScan)
    private val lvPeers = v.findViewById<ListView>(R.id.lvPeers)
    private val lvRemote = v.findViewById<ListView>(R.id.lvRemote)
    private val lvLocal = v.findViewById<ListView>(R.id.lvLocal)
    private val tvMsg = v.findViewById<TextView>(R.id.tvLanMsg)

    private lateinit var server: HttpFileServer
    private lateinit var discovery: LanDiscovery
    private var mcLock: WifiManager.MulticastLock? = null

    private val peers = ArrayList<Peer>()
    private val remoteFiles = ArrayList<RemoteFile>()
    private val localFiles = ArrayList<File>()
    private var selected: Peer? = null

    override fun view() = v

    override fun onShow() {
        refreshLocal()
    }

    init {
        server = HttpFileServer(act.storageDir)
        discovery = LanDiscovery(act)

        server.onEvent = { msg ->
            act.runOnUiThread { tvMsg.text = msg }
        }

        discovery.listener = object : LanDiscovery.Listener {
            override fun onPeersChanged(list: List<Peer>) {
                act.runOnUiThread {
                    peers.clear()
                    peers.addAll(list)
                    (lvPeers.adapter as? BaseAdapter)?.notifyDataSetChanged()
                    tvScan.text = if (list.isEmpty()) "没找到其他设备" else "找到 ${list.size} 台设备"
                }
            }

            override fun onStatus(text: String) {
                act.runOnUiThread { tvScan.text = text }
            }
        }

        lvPeers.adapter = ArrayAdapter(act, android.R.layout.simple_list_item_1, peers)
        lvRemote.adapter = ArrayAdapter(act, android.R.layout.simple_list_item_1, remoteFiles)
        lvLocal.adapter = ArrayAdapter(act, android.R.layout.simple_list_item_1, localFiles)

        lvPeers.setOnItemClickListener { _, _, pos, _ ->
            if (pos !in peers.indices) return@setOnItemClickListener
            selected = peers[pos]
            tvMsg.text = "已选择 ${peers[pos].name}，正在读取对方文件…"
            fetchRemote(peers[pos])
        }

        // 对方的文件：点一下拉过来（走多线程下载器）
        lvRemote.setOnItemClickListener { _, _, pos, _ ->
            if (pos !in remoteFiles.indices) return@setOnItemClickListener
            val p = selected
            if (p == null) {
                toast("先在上面选一台设备")
                return@setOnItemClickListener
            }
            val url = LanClient.fileUrl(p.host, p.port, remoteFiles[pos].name)
            act.startDownload(url, 8)
        }

        // 我的文件：长按发送
        lvLocal.setOnItemLongClickListener { _, _, pos, _ ->
            if (pos in localFiles.indices) upload(localFiles[pos])
            true
        }

        btnScan.setOnClickListener { discovery.discover() }

        cbShare.setOnCheckedChangeListener { _, checked ->
            if (checked) startShare() else stopShare()
        }

        tvSelf.text = "本机：${LanDiscovery.deviceName()}\n目录：${act.storageDir.absolutePath}"

        // 默认开启共享并扫一次
        cbShare.isChecked = true
        discovery.discover()
    }

    private fun startShare() {
        acquireMulticastLock()
        val port = server.start()
        if (port <= 0) {
            cbShare.isChecked = false
            tvSelf.text = "本机：开启失败"
            return
        }
        discovery.register(port)
        tvSelf.text = "本机：${LanDiscovery.deviceName()}　端口 $port\n目录：${act.storageDir.absolutePath}"
    }

    private fun stopShare() {
        discovery.unregister()
        server.stop()
        releaseMulticastLock()
        tvSelf.text = "本机：已关闭共享"
    }

    private fun acquireMulticastLock() {
        try {
            val wm = act.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            mcLock = wm.createMulticastLock("mtdl-lock")
            mcLock?.acquire()
        } catch (_: Exception) {
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (mcLock?.isHeld == true) mcLock?.release()
        } catch (_: Exception) {
        }
        mcLock = null
    }

    private fun refreshLocal() {
        localFiles.clear()
        localFiles.addAll(server.listFiles())
        (lvLocal.adapter as? BaseAdapter)?.notifyDataSetChanged()
    }

    private fun fetchRemote(p: Peer) {
        Thread {
            try {
                val list = LanClient.fetchList(p.host, p.port)
                act.runOnUiThread {
                    remoteFiles.clear()
                    remoteFiles.addAll(list)
                    (lvRemote.adapter as? BaseAdapter)?.notifyDataSetChanged()
                    tvMsg.text = if (list.isEmpty()) "${p.name} 还没有文件" else "${p.name}：${list.size} 个文件"
                }
            } catch (e: Exception) {
                act.runOnUiThread { tvMsg.text = "连不上 ${p.name}：${e.message}" }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun upload(f: File) {
        val p = selected
        if (p == null) {
            toast("先在上面选一台设备")
            return
        }
        Thread {
            try {
                act.runOnUiThread { tvMsg.text = "正在发送 ${f.name}…" }
                LanClient.upload(f, p.host, p.port)
                act.runOnUiThread { tvMsg.text = "已发送 ${f.name}" }
            } catch (e: Exception) {
                act.runOnUiThread { tvMsg.text = "发送失败：${e.message}" }
            }
        }.apply { isDaemon = true }.start()
    }

    fun shutdown() {
        discovery.unregister()
        discovery.stopDiscover()
        server.stop()
        releaseMulticastLock()
    }

    private fun toast(msg: String) {
        Toast.makeText(act, msg, Toast.LENGTH_SHORT).show()
    }
}
