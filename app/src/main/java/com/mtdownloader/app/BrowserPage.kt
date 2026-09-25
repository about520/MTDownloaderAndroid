package com.mtdownloader.app

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

class BrowserPage(private val act: MainActivity, private val v: View) : Page {

    companion object {
        private const val HOME = "https://www.123pan.com"
        // 网页自身的资源，不可能是下载文件
        private val BLOCK = setOf(
            "js", "css", "png", "jpg", "jpeg", "gif", "svg", "ico", "webp", "bmp",
            "woff", "woff2", "ttf", "otf", "eot", "html", "htm", "php", "asp",
            "json", "xml", "txt", "map", "manifest"
        )
        private val KEYWORDS = listOf("download", "dlink", "file", "/d/", "attachment")
    }

    private val web = v.findViewById<WebView>(R.id.webview)
    private val etAddr = v.findViewById<EditText>(R.id.etAddr)
    private val lv = v.findViewById<ListView>(R.id.lvLinks)
    private val tvCount = v.findViewById<TextView>(R.id.tvCount)

    private val links = ArrayList<String>()
    private lateinit var adapter: ArrayAdapter<String>

    override fun view() = v
    override fun onShow() {}

    init {
        adapter = ArrayAdapter(act, android.R.layout.simple_list_item_1, links)
        lv.adapter = adapter

        v.findViewById<Button>(R.id.btnBack).setOnClickListener { if (web.canGoBack()) web.goBack() }
        v.findViewById<Button>(R.id.btnForward).setOnClickListener { if (web.canGoForward()) web.goForward() }
        v.findViewById<Button>(R.id.btnGo).setOnClickListener { go(etAddr.text.toString()) }
        v.findViewById<Button>(R.id.btnClear).setOnClickListener {
            links.clear(); adapter.notifyDataSetChanged(); updateCount()
        }
        v.findViewById<Button>(R.id.btnLinks).setOnClickListener {
            lv.visibility = if (lv.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        lv.setOnItemClickListener { _, _, pos, _ ->
            if (pos in links.indices) showAction(links[pos])
        }

        setupWeb()
    }

    private fun go(text: String) {
        var t = text.trim()
        if (t.isEmpty()) return
        if (!t.startsWith("http://") && !t.startsWith("https://")) t = "https://$t"
        web.loadUrl(t)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb() {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        web.addJavascriptInterface(JsBridge(), "MTLink")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                addLink(request.url.toString())
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                etAddr.setText(url)
                // 拦截 <a> 点击和 window.open
                web.evaluateJavascript(JS, null)
            }
        }

        // 点击「下载」这类按钮时系统会回调这里，直链最可靠的来源
        web.setDownloadListener { url, _, _, _, _ -> addLink(url) }

        web.loadUrl(HOME)
    }

    private val JS = """
        (function(){
          if (window.__mtInjected) return;
          window.__mtInjected = true;
          document.addEventListener('click', function(e){
            var t = e.target;
            var a = (t && t.closest) ? t.closest('a') : null;
            if (a && a.href) { window.MTLink.sniff(a.href); }
          }, true);
          var _o = window.open;
          window.open = function(u){ window.MTLink.sniff(u); return _o.apply(window, arguments); };
        })();
    """.trimIndent()

    private inner class JsBridge {
        @JavascriptInterface
        fun sniff(url: String) {
            addLink(url)
        }
    }

    private fun looksLikeDownload(u: String): Boolean {
        if (!u.startsWith("http://") && !u.startsWith("https://")) return false
        val path = u.substringBefore("?").substringBefore("#")
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty() && ext.length <= 5) {
            return !BLOCK.contains(ext)
        }
        val low = u.lowercase()
        return KEYWORDS.any { low.contains(it) }
    }

    private fun addLink(u: String) {
        val url = u.trim()
        if (!looksLikeDownload(url)) return
        act.runOnUiThread {
            if (links.contains(url)) return@runOnUiThread
            links.add(0, url)
            if (links.size > 60) links.removeAt(links.size - 1)
            adapter.notifyDataSetChanged()
            updateCount()
        }
    }

    private fun updateCount() {
        tvCount.text = "抓到 ${links.size} 条直链"
    }

    private fun showAction(url: String) {
        val items = arrayOf("复制直链", "下载（8 线程）", "取消")
        android.app.AlertDialog.Builder(act)
            .setTitle(url.take(70))
            .setItems(items) { d, which ->
                when (which) {
                    0 -> {
                        val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("直链", url))
                        Toast.makeText(act, "已复制直链", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        lv.visibility = View.GONE
                        act.startDownload(url, 8)
                    }
                }
                d.dismiss()
            }
            .show()
    }
}
