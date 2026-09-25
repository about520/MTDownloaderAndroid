package com.mtdownloader.app

import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicLong

/**
 * 多线程分片下载器。
 * 和 iOS 版行为保持一致：先探测大小，支持 Range 就分片并发，写入按偏移 seek。
 * 断点续传靠一个 .mtprog 文件记录每个分片已完成多少。
 */
class MtDownloader(private val dir: File) {

    interface Callback {
        fun onInfo(totalBytes: Long, fileName: String)
        fun onProgress(doneBytes: Long, totalBytes: Long, speedBps: Long)
        fun onDone(file: File)
        fun onError(msg: String)
    }

    @Volatile private var paused = false
    @Volatile private var cancelled = false
    @Volatile private var running = false

    private val doneBytes = AtomicLong(0)
    private var totalBytes = 0L
    private var chunkDone = LongArray(0)
    private var progFile: File? = null

    fun isRunning() = running

    fun pause() { paused = true }

    fun resume() { paused = false }

    fun cancel() {
        cancelled = true
        paused = false
    }

    fun start(urlStr: String, threads: Int, cb: Callback, preferredName: String? = null) {
        if (running) {
            cb.onError("已经有任务在跑了")
            return
        }
        running = true
        paused = false
        cancelled = false
        doneBytes.set(0)

        Thread {
            try {
                val name = preferredName?.takeIf { it.isNotBlank() } ?: guessName(urlStr)
                val out = File(dir, safeName(name))
                val prog = File(dir, safeName(name) + ".mtprog")

                val size = probeSize(urlStr)
                val canRange = size > 0 && probeRange(urlStr)
                val n = if (canRange) threads.coerceIn(1, 32) else 1

                chunkDone = LongArray(n)
                progFile = prog
                totalBytes = size

                // 续传：读回每个分片已完成量
                val resumed = readProgress(prog, n)
                var already = 0L
                for (v in resumed) already += v
                chunkDone = resumed
                doneBytes.set(already)

                cb.onInfo(size, out.name)
                startTicker(cb)

                // 预分配文件，避免写的时候磁盘满了才发现
                RandomAccessFile(out, "rw").use { it.setLength(size.coerceAtLeast(0)) }

                if (!canRange || size <= 0) {
                    downloadWhole(urlStr, out, cb)
                } else {
                    val per = size / n
                    val jobs = ArrayList<Thread>()
                    for (i in 0 until n) {
                        val s = i * per
                        val e = if (i == n - 1) size - 1 else (i + 1) * per - 1
                        val t = Thread {
                            downloadChunk(urlStr, out, s, e, i, cb)
                        }
                        t.isDaemon = true
                        jobs.add(t)
                        t.start()
                    }
                    for (t in jobs) t.join()
                }

                if (cancelled) {
                    cb.onError("已取消")
                } else {
                    prog.delete()
                    cb.onDone(out)
                }
            } catch (e: Exception) {
                cb.onError(e.message ?: "下载失败")
            } finally {
                running = false
            }
        }.apply { isDaemon = true }.start()
    }

    private fun startTicker(cb: Callback) {
        var last = doneBytes.get()
        var lastT = System.currentTimeMillis()
        Thread {
            while (running) {
                try { Thread.sleep(1000) } catch (_: Exception) { break }
                val now = doneBytes.get()
                val t = System.currentTimeMillis()
                val dt = (t - lastT) / 1000.0
                val speed = if (dt > 0) ((now - last) / dt).toLong() else 0L
                last = now
                lastT = t
                cb.onProgress(now, totalBytes, speed)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun downloadWhole(urlStr: String, out: File, cb: Callback) {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", UA)
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.connect()
        RandomAccessFile(out, "rw").use { raf ->
            conn.inputStream.use { input ->
                val buf = ByteArray(BUF)
                while (true) {
                    if (cancelled) break
                    while (paused && !cancelled) {
                        try { Thread.sleep(300) } catch (_: Exception) {}
                    }
                    val n = input.read(buf)
                    if (n <= 0) break
                    raf.write(buf, 0, n)
                    doneBytes.addAndGet(n.toLong())
                }
            }
        }
        conn.disconnect()
    }

    private fun downloadChunk(urlStr: String, out: File, start: Long, end: Long,
                              index: Int, cb: Callback) {
        var offset = start + chunkDone[index]
        RandomAccessFile(out, "rw").use { raf ->
            raf.seek(offset)
            var guard = 0
            while (offset <= end) {
                if (cancelled) return
                while (paused && !cancelled) {
                    try { Thread.sleep(300) } catch (_: Exception) {}
                }
                if (cancelled) return
                try {
                    val conn = URL(urlStr).openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("Range", "bytes=$offset-$end")
                    conn.setRequestProperty("User-Agent", UA)
                    conn.connectTimeout = 15000
                    conn.readTimeout = 30000
                    conn.connect()
                    val code = conn.responseCode
                    if (code != 206 && code != 200) {
                        conn.disconnect()
                        return
                    }
                    conn.inputStream.use { input ->
                        val buf = ByteArray(BUF)
                        while (true) {
                            if (cancelled) { conn.disconnect(); return }
                            if (paused) {
                                try { Thread.sleep(300) } catch (_: Exception) {}
                                continue
                            }
                            val n = input.read(buf)
                            if (n <= 0) break
                            raf.write(buf, 0, n)
                            offset += n
                            doneBytes.addAndGet(n.toLong())
                        }
                    }
                    conn.disconnect()
                    chunkDone[index] = offset - start
                    writeProgress()
                    if (offset > end) break
                } catch (e: Exception) {
                    chunkDone[index] = offset - start
                    writeProgress()
                    if (cancelled) return
                    guard++
                    if (guard > 8) {
                        cb.onError("分片 $index 重试多次仍失败：${e.message}")
                        return
                    }
                    try { Thread.sleep(1500) } catch (_: Exception) {}
                }
            }
        }
    }

    @Synchronized
    private fun writeProgress() {
        val f = progFile ?: return
        try {
            f.writeText(chunkDone.joinToString(","))
        } catch (_: Exception) {}
    }

    private fun readProgress(f: File, n: Int): LongArray {
        val arr = LongArray(n)
        if (!f.exists()) return arr
        return try {
            val parts = f.readText().split(",")
            for (i in 0 until n) {
                if (i < parts.size) arr[i] = parts[i].trim().toLongOrNull() ?: 0L
            }
            arr
        } catch (_: Exception) { arr }
    }

    private fun probeSize(urlStr: String): Long {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "HEAD"
        conn.setRequestProperty("User-Agent", UA)
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.connect()
        val len = conn.getHeaderFieldLong("Content-Length", -1L)
        conn.disconnect()
        return len
    }

    private fun probeRange(urlStr: String): Boolean {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Range", "bytes=0-0")
            conn.setRequestProperty("User-Agent", UA)
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.connect()
            val code = conn.responseCode
            val cr = conn.getHeaderField("Content-Range")
            conn.disconnect()
            code == 206 || (cr != null && cr.contains("bytes"))
        } catch (_: Exception) { false }
    }

    companion object Names {
        const val UA = "MTDownloader/1.0"
        private const val BUF = 64 * 1024

        /** 优先取 URL 里的 filename= 参数，其次取路径最后一段 */
        fun guessName(urlStr: String): String {
            return try {
                val q = URL(urlStr).query
                if (!q.isNullOrEmpty()) {
                    for (p in q.split("&")) {
                        val kv = p.split("=", limit = 2)
                        if (kv.size == 2 && (kv[0] == "filename" || kv[0] == "fn")) {
                            return URLDecoder.decode(kv[1], "UTF-8")
                        }
                    }
                }
                val path = URL(urlStr).path
                val last = path.substringAfterLast('/', "")
                if (last.isNotEmpty()) return URLDecoder.decode(last, "UTF-8")
                URL(urlStr).host ?: "download.bin"
            } catch (_: Exception) { "download.bin" }
        }

        private val BAD = Regex("[/\\\\:*?\"<>|]")

        fun safeName(raw: String): String {
            val c = raw.replace(BAD, "_").trim()
            return if (c.isEmpty()) "download.bin" else c
        }
    }
}
