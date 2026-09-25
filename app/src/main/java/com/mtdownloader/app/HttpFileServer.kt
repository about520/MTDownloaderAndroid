package com.mtdownloader.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * 极简 HTTP 服务，零第三方依赖（用 ServerSocket 手写）。
 *
 * 路由和 iOS 版完全一致，两边才能互通：
 *   GET  /ping                    -> "OK"
 *   GET  /list                    -> [{"name":"x","size":123}]
 *   GET  /file?filename=x         -> 文件二进制，支持 Range（206）
 *   POST /upload?filename=x       -> body 就是文件内容
 */
class HttpFileServer(private val dir: File) {

    companion object {
        const val SERVICE_TYPE = "_mtdl._tcp"
        private val BAD = Regex("[/\\\\:*?\"<>|]")

        fun safeName(raw: String): String {
            val c = raw.replace(BAD, "_").trim()
            return if (c.isEmpty()) "unnamed" else c
        }
    }

    var onEvent: ((String) -> Unit)? = null

    @Volatile private var running = false
    private var server: ServerSocket? = null

    fun isRunning() = running
    fun port(): Int = server?.localPort ?: 0

    fun start(): Int {
        if (running) return port()
        return try {
            if (!dir.exists()) dir.mkdirs()
            val ss = ServerSocket(0)   // 0 = 让系统分配端口
            server = ss
            running = true
            Thread {
                while (running) {
                    try {
                        val c = ss.accept()
                        Thread { handle(c) }.apply { isDaemon = true }.start()
                    } catch (_: Exception) {
                    }
                }
            }.apply { isDaemon = true }.start()
            ss.localPort
        } catch (e: Exception) {
            onEvent?.invoke("开启失败：${e.message}")
            0
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    // MARK: 请求处理

    private fun handle(c: Socket) {
        try {
            c.soTimeout = 60000
            val input = BufferedInputStream(c.getInputStream())

            // 一直读到 \r\n\r\n，即请求头结束
            val headBuf = ByteArrayOutputStream()
            var last4 = 0
            while (true) {
                val b = input.read()
                if (b < 0) break
                headBuf.write(b)
                last4 = ((last4 shl 8) or (b and 0xFF))
                if (last4 == 0x0D0A0D0A) break
            }
            val header = String(headBuf.toByteArray(), Charsets.UTF_8)
            val lines = header.split("\r\n").filter { it.isNotBlank() }
            if (lines.isEmpty()) { c.close(); return }

            val reqLine = lines[0].split(" ")
            if (reqLine.size < 2) { c.close(); return }
            val method = reqLine[0].uppercase()
            val target = reqLine[1]

            var contentLength = 0
            var range: String? = null
            for (l in lines.drop(1)) {
                val low = l.lowercase()
                if (low.startsWith("content-length:")) {
                    contentLength = l.substringAfter(":").trim().toIntOrNull() ?: 0
                } else if (low.startsWith("range:")) {
                    range = l.substringAfter(":").trim()
                }
            }

            val out = c.getOutputStream()
            route(method, target, contentLength, range, input, out)
            out.flush()
            c.close()
        } catch (_: Exception) {
            try { c.close() } catch (_: Exception) {}
        }
    }

    private fun route(method: String, target: String, contentLength: Int,
                      range: String?, input: BufferedInputStream, out: OutputStream) {
        val path = target.substringBefore("?")
        val fileName = target.substringAfter("?", "")
            .split("&")
            .firstOrNull { it.startsWith("filename=") }
            ?.substringAfter("filename=")
            ?.let { URLDecoder.decode(it, "UTF-8") }

        when {
            method == "GET" && path == "/ping" -> {
                sendBytes(out, 200, "text/plain; charset=utf-8", "OK".toByteArray())
            }

            method == "GET" && path == "/list" -> {
                val arr = JSONArray()
                for (f in listFiles()) {
                    val o = JSONObject()
                    o.put("name", f.name)
                    o.put("size", f.length())
                    arr.put(o)
                }
                sendBytes(out, 200, "application/json", arr.toString().toByteArray())
            }

            method == "GET" && path == "/file" && fileName != null -> {
                sendFile(out, File(dir, safeName(fileName)), range)
            }

            method == "POST" && path == "/upload" && fileName != null -> {
                val body = ByteArray(contentLength)
                var got = 0
                while (got < contentLength) {
                    val n = input.read(body, got, contentLength - got)
                    if (n <= 0) break
                    got += n
                }
                val f = File(dir, safeName(fileName))
                f.writeBytes(body)
                onEvent?.invoke("收到文件：${f.name}")
                sendBytes(out, 200, "text/plain; charset=utf-8", "OK".toByteArray())
            }

            else -> sendBytes(out, 404, "text/plain; charset=utf-8", "Not Found".toByteArray())
        }
    }

    private fun sendFile(out: OutputStream, f: File, range: String?) {
        if (!f.exists() || !f.isFile) {
            sendBytes(out, 404, "text/plain; charset=utf-8", "文件不存在".toByteArray())
            return
        }
        val total = f.length()
        var start = 0L
        var end = total - 1
        var status = 200

        if (!range.isNullOrEmpty()) {
            val spec = range.substringAfter("=", "")
            val parts = spec.split("-")
            start = parts[0].trim().toLongOrNull() ?: 0
            if (parts.size > 1 && parts[1].isNotBlank()) {
                end = (parts[1].trim().toLongOrNull() ?: (total - 1)).coerceAtMost(total - 1)
            }
            status = 206
        }
        if (start < 0 || start >= total) {
            sendBytes(out, 416, "text/plain; charset=utf-8", "范围无效".toByteArray())
            return
        }
        end = end.coerceAtMost(total - 1)
        val length = end - start + 1

        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(status).append(' ')
            .append(if (status == 206) "Partial Content" else "OK").append("\r\n")
        head.append("Content-Type: application/octet-stream\r\n")
        head.append("Content-Length: ").append(length).append("\r\n")
        head.append("Accept-Ranges: bytes\r\n")
        if (status == 206) {
            head.append("Content-Range: bytes ").append(start).append('-').append(end)
                .append('/').append(total).append("\r\n")
        }
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))

        FileInputStream(f).use { fis ->
            fis.skip(start)
            val buf = ByteArray(256 * 1024)
            var remain = length
            while (remain > 0) {
                val toRead = minOf(remain, buf.size.toLong()).toInt()
                val n = fis.read(buf, 0, toRead)
                if (n <= 0) break
                out.write(buf, 0, n)
                remain -= n
            }
        }
    }

    private fun sendBytes(out: OutputStream, status: Int, type: String, body: ByteArray) {
        val head = "HTTP/1.1 $status OK\r\n" +
                "Content-Type: $type\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Accept-Ranges: bytes\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
    }

    fun listFiles(): List<File> {
        if (!dir.exists()) dir.mkdirs()
        val all = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".mtprog") } ?: emptyList()
        return all.sortedBy { it.name }
    }
}
