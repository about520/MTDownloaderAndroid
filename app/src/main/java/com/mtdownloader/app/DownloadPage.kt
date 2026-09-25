package com.mtdownloader.app

import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File

class DownloadPage(private val act: MainActivity, private val v: View) : Page {

    private val etUrl = v.findViewById<EditText>(R.id.etUrl)
    private val sb = v.findViewById<SeekBar>(R.id.sbThreads)
    private val tvThreads = v.findViewById<TextView>(R.id.tvThreads)
    private val pb = v.findViewById<ProgressBar>(R.id.pb)
    private val tvProgress = v.findViewById<TextView>(R.id.tvProgress)
    private val tvStatus = v.findViewById<TextView>(R.id.tvStatus)
    private val tvDevice = v.findViewById<TextView>(R.id.tvDevice)

    override fun view() = v

    override fun onShow() {
        tvDevice.text = Utils.deviceInfo(act)
    }

    init {
        tvThreads.text = (sb.progress + 1).toString()
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                tvThreads.text = (p + 1).toString()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        v.findViewById<Button>(R.id.btnStart).setOnClickListener {
            val u = etUrl.text.toString().trim()
            if (!u.startsWith("http://") && !u.startsWith("https://")) {
                toast("请填 http/https 直链")
                return@setOnClickListener
            }
            start(u, sb.progress + 1)
        }
        v.findViewById<Button>(R.id.btnPause).setOnClickListener {
            act.downloader.pause()
            tvStatus.text = "状态：已暂停"
        }
        v.findViewById<Button>(R.id.btnResume).setOnClickListener {
            act.downloader.resume()
            tvStatus.text = "状态：下载中"
        }

        tvDevice.text = Utils.deviceInfo(act)
    }

    fun setUrlAndStart(url: String, threads: Int) {
        etUrl.setText(url)
        start(url, threads)
    }

    private fun start(url: String, threads: Int) {
        tvStatus.text = "状态：准备中"
        act.downloader.start(url, threads, object : MtDownloader.Callback {
            override fun onInfo(totalBytes: Long, fileName: String) {
                act.runOnUiThread {
                    tvStatus.text = "状态：下载中"
                    tvProgress.text = "文件：$fileName"
                }
            }

            override fun onProgress(doneBytes: Long, totalBytes: Long, speedBps: Long) {
                act.runOnUiThread {
                    if (totalBytes > 0) {
                        pb.progress = ((doneBytes * 1000L) / totalBytes).toInt()
                        tvProgress.text = "${Utils.fmtBytes(doneBytes)} / ${Utils.fmtBytes(totalBytes)}　${Utils.fmtBytes(speedBps)}/s"
                    } else {
                        tvProgress.text = "已下载 ${Utils.fmtBytes(doneBytes)}"
                    }
                }
            }

            override fun onDone(file: File) {
                act.runOnUiThread {
                    pb.progress = 1000
                    tvStatus.text = "状态：已完成"
                    tvProgress.text = "已保存：${file.name}"
                }
            }

            override fun onError(msg: String) {
                act.runOnUiThread { tvStatus.text = "状态：出错 - $msg" }
            }
        })
    }

    private fun toast(msg: String) {
        Toast.makeText(act, msg, Toast.LENGTH_SHORT).show()
    }
}
