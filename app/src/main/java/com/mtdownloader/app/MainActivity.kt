package com.mtdownloader.app

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import java.io.File

interface Page {
    fun view(): View
    fun onShow()
}

class MainActivity : AppCompatActivity() {

    lateinit var storageDir: File
        private set
    lateinit var downloader: MtDownloader
        private set

    private var lanPage: LanPage? = null
    private lateinit var pages: List<Page>
    private var current = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        storageDir = File(getExternalFilesDir(null), "MTDownloader")
        if (!storageDir.exists()) storageDir.mkdirs()
        downloader = MtDownloader(storageDir)

        val content = findViewById<FrameLayout>(R.id.content)
        val inf = layoutInflater

        val downloadPage = DownloadPage(this, inf.inflate(R.layout.page_download, content, false))
        val browserPage = BrowserPage(this, inf.inflate(R.layout.page_browser, content, false))
        val lan = LanPage(this, inf.inflate(R.layout.page_lan, content, false))
        val musicPage = MusicPage(this, inf.inflate(R.layout.page_music, content, false))
        val filesPage = FilesPage(this, inf.inflate(R.layout.page_files, content, false))
        lanPage = lan

        pages = listOf(downloadPage, browserPage, lan, musicPage, filesPage)
        for (p in pages) {
            content.addView(p.view())
            p.view().visibility = View.GONE
        }

        val tabs = listOf(R.id.tabDownload, R.id.tabBrowser, R.id.tabLan, R.id.tabMusic, R.id.tabFiles)
        for ((i, id) in tabs.withIndex()) {
            findViewById<Button>(id).setOnClickListener { switchTo(i) }
        }
        switchTo(0)
    }

    fun switchTo(i: Int) {
        if (i !in pages.indices) return
        pages[current].view().visibility = View.GONE
        current = i
        pages[i].view().visibility = View.VISIBLE
        pages[i].onShow()
    }

    /** 浏览器页抓到直链后，让下载页直接用上并跳过去 */
    fun startDownload(url: String, threads: Int = 8) {
        (pages[0] as? DownloadPage)?.setUrlAndStart(url, threads)
        switchTo(0)
    }

    override fun onDestroy() {
        lanPage?.shutdown()
        super.onDestroy()
    }
}
