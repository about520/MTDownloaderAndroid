package com.mtdownloader.app

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MusicPage(private val act: MainActivity, private val v: View) : Page {

    private enum class Kind(val label: String, val code: Int) {
        SONG("歌曲", 1), ARTIST("歌手", 100), PLAYLIST("歌单", 1000)
    }

    private var kind = Kind.SONG

    /** 行模型：song != null 表示可以直接下载，否则是歌手/歌单入口（点进去下钻） */
    private class Row(val song: NeteaseApi.Song?, val title: String, val sub: String, val drill: Long)

    private val rows = ArrayList<Row>()
    private var inDrillDown = false
    private val searchRows = ArrayList<Row>()   // 缓存搜索结果，返回时直接恢复

    private val tvStatus: TextView = v.findViewById(R.id.tvMusicStatus)
    private val btnBack: Button = v.findViewById(R.id.btnMusicBack)
    private val lv: ListView = v.findViewById(R.id.lvMusic)
    private val adapter = MusicAdapter()

    override fun view() = v

    override fun onShow() {}

    init {
        // 类型选择
        val kinds = Kind.values().map { it.label }
        val sp = v.findViewById<Spinner>(R.id.spinKind)
        sp.adapter = ArrayAdapter(act, android.R.layout.simple_spinner_dropdown_item, kinds)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, s: View?, pos: Int, id: Long) {
                kind = Kind.values()[pos]
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val et = v.findViewById<EditText>(R.id.etMusicKeyword)
        v.findViewById<Button>(R.id.btnMusicSearch).setOnClickListener { search(et.text.toString()) }
        et.setOnEditorActionListener { _, _, _ ->
            search(et.text.toString())
            true
        }

        btnBack.setOnClickListener {
            rows.clear(); rows.addAll(searchRows); adapter.notifyDataSetChanged()
            inDrillDown = false
            btnBack.visibility = View.GONE
            setStatus("回到搜索结果（${searchRows.size} 条）")
        }

        v.findViewById<Button>(R.id.btnCookie).setOnClickListener { showCookieDialog() }

        lv.adapter = adapter
        lv.setOnItemClickListener { _, _, pos, _ ->
            if (pos !in rows.indices) return@setOnItemClickListener
            val r = rows[pos]
            if (r.song == null && r.drill > 0) {
                if (kind == Kind.ARTIST) drillArtist(r.drill, r.title) else drillPlaylist(r.drill, r.title)
            }
        }
    }

    private fun cookie(): String =
        act.getSharedPreferences("mt", Context.MODE_PRIVATE)
            .getString("netease_cookie", "") ?: ""

    // MARK: 搜索与下钻

    private fun search(kwRaw: String) {
        val kw = kwRaw.trim()
        if (kw.isEmpty()) { setStatus("先输入关键词"); return }
        setStatus("搜索中…")
        inDrillDown = false
        btnBack.visibility = View.GONE
        Thread {
            val ck = cookie()
            val newRows = when (kind) {
                Kind.SONG -> NeteaseApi.searchSongs(kw, ck).map {
                    Row(it, it.name, "${it.artists}  ${it.durationText}  ${it.feeText}", 0)
                }
                Kind.ARTIST -> NeteaseApi.searchArtists(kw, ck).map {
                    Row(null, it.name, "歌手 · 点进看热门歌曲", it.id)
                }
                Kind.PLAYLIST -> NeteaseApi.searchPlaylists(kw, ck).map {
                    Row(null, it.name, "歌单 · ${it.trackCount} 首 · 点进看曲目", it.id)
                }
            }
            act.runOnUiThread {
                rows.clear(); rows.addAll(newRows)
                searchRows.clear(); searchRows.addAll(newRows)
                adapter.notifyDataSetChanged()
                setStatus(if (newRows.isEmpty()) "没搜到结果"
                          else "找到 ${newRows.size} 条，点下载自动取最佳音质")
            }
        }.start()
    }

    private fun drillArtist(id: Long, name: String) {
        setStatus("加载 $name 的热门歌曲…")
        Thread {
            val list = NeteaseApi.artistSongs(id, cookie()).map {
                Row(it, it.name, "${it.artists}  ${it.durationText}  ${it.feeText}", 0)
            }
            act.runOnUiThread {
                rows.clear(); rows.addAll(list); adapter.notifyDataSetChanged()
                inDrillDown = true
                btnBack.visibility = View.VISIBLE
                setStatus(if (list.isEmpty()) "没拿到歌曲" else "$name 热门 ${list.size} 首")
            }
        }.start()
    }

    private fun drillPlaylist(id: Long, name: String) {
        setStatus("加载歌单…")
        Thread {
            val list = NeteaseApi.playlistSongs(id, cookie()).map {
                Row(it, it.name, "${it.artists}  ${it.durationText}  ${it.feeText}", 0)
            }
            act.runOnUiThread {
                rows.clear(); rows.addAll(list); adapter.notifyDataSetChanged()
                inDrillDown = true
                btnBack.visibility = View.VISIBLE
                setStatus(if (list.isEmpty()) "没拿到歌曲（部分歌单只返回部分曲目）"
                          else "歌单共 ${list.size} 首")
            }
        }.start()
    }

    // MARK: 下载

    private fun download(song: NeteaseApi.Song, btn: Button) {
        btn.isEnabled = false
        btn.text = "取链…"
        Thread {
            val link = NeteaseApi.fetchLink(song, cookie())
            act.runOnUiThread {
                btn.isEnabled = true
                btn.text = "下载"
                if (link == null) {
                    setStatus("「${song.name}」取直链失败")
                    return@runOnUiThread
                }
                act.downloader.start(link.url, 8, object : MtDownloader.Callback {
                    override fun onInfo(totalBytes: Long, fileName: String) {
                        act.runOnUiThread {
                            setStatus("下载中：$fileName（${link.qualityText}${if (link.full) "" else " 试听"}）")
                        }
                    }
                    override fun onProgress(done: Long, total: Long, speed: Long) {
                        act.runOnUiThread {
                            val pct = if (total > 0) " ${done * 100 / total}%" else ""
                            setStatus("下载中：${Utils.fmtBytes(done)}$pct  ${Utils.fmtBytes(speed)}/s")
                        }
                    }
                    override fun onDone(file: File) {
                        act.runOnUiThread { setStatus("已保存：${file.name}") }
                    }
                    override fun onError(msg: String) {
                        act.runOnUiThread { setStatus("出错：$msg") }
                    }
                }, preferredName = link.song.fileName())

                if (link.full && !link.swapped) {
                    setStatus("开始下载「${song.name}」（${link.qualityText}）")
                } else if (link.swapped) {
                    setStatus("原版仅试听，已换完整版：${link.song.name}（${link.qualityText}）")
                } else {
                    setStatus("「${song.name}」只有 30 秒试听，没找到完整版；填会员 Cookie 可解锁")
                }
            }
        }.start()
    }

    // MARK: Cookie

    private fun showCookieDialog() {
        val box = LinearLayout(act)
        box.setPadding(48, 24, 48, 0)
        box.orientation = LinearLayout.VERTICAL
        val et = EditText(act)
        et.hint = "粘贴含 MUSIC_U 的 Cookie"
        et.setTextSize(11f)
        et.minLines = 4
        et.setText(cookie())
        box.addView(et)
        val tip = TextView(act)
        tip.text = "电脑浏览器登录 music.163.com → F12 → Network → 任选一条请求 → 复制 Request Headers 里 Cookie 整行。不填也能用：多数会员歌本身就有完整 320k，付费单曲会自动换完整版本。"
        tip.setTextSize(11f)
        tip.setTextColor(0xFF888888.toInt())
        tip.setPadding(0, 16, 0, 0)
        box.addView(tip)

        AlertDialog.Builder(act)
            .setTitle("会员 Cookie")
            .setView(box)
            .setPositiveButton("保存并校验") { _, _ ->
                val ck = et.text.toString().trim()
                act.getSharedPreferences("mt", Context.MODE_PRIVATE)
                    .edit().putString("netease_cookie", ck).apply()
                setStatus("校验中…")
                Thread {
                    val s = NeteaseApi.checkLogin(ck)
                    act.runOnUiThread { setStatus(s) }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setStatus(s: String) { tvStatus.text = s }

    // MARK: 列表适配器

    private inner class MusicAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(pos: Int) = rows[pos]
        override fun getItemId(pos: Int) = pos.toLong()

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
            val holder: VH
            val view: View
            if (convertView == null) {
                view = LayoutInflater.from(act).inflate(R.layout.item_song, parent, false)
                holder = VH(view)
                view.tag = holder
            } else {
                view = convertView
                holder = view.tag as VH
            }
            val r = rows[pos]
            holder.tvName.text = r.title
            holder.tvSub.text = r.sub

            if (r.song != null) {
                holder.btnDl.visibility = View.VISIBLE
                holder.btnDl.isEnabled = true
                holder.btnDl.text = "下载"
                holder.btnDl.setOnClickListener { btn ->
                    download(r.song, btn as Button)
                }
            } else {
                holder.btnDl.visibility = View.GONE
            }
            return view
        }

        private class VH(view: View) {
            val tvName: TextView = view.findViewById(R.id.tvSongName)
            val tvSub: TextView = view.findViewById(R.id.tvSongSub)
            val btnDl: Button = view.findViewById(R.id.btnDl)
        }
    }
}
