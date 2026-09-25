package com.mtdownloader.app

import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class RemoteFile(val name: String, val size: Long)

/**
 * 访问局域网里另一台设备。协议和 iOS 版完全一致：
 *   GET  /list              -> JSON 文件列表
 *   GET  /file?filename=x   -> 文件（交给 MtDownloader 多线程拉）
 *   POST /upload?filename=x -> 把本地文件推过去
 */
object LanClient {

    private fun enc(name: String): String {
        // URLEncoder 把空格编成 '+'，但 iOS 端用 URLComponents 解析时
        // 不会把 '+' 还原成空格，所以这里手动换成 %20
        return URLEncoder.encode(name, "UTF-8").replace("+", "%20")
    }

    fun fileUrl(host: String, port: Int, name: String): String =
        "http://$host:$port/file?filename=" + enc(name)

    fun fetchList(host: String, port: Int, timeoutMs: Int = 6000): List<RemoteFile> {
        val conn = URL("http://$host:$port/list").openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.requestMethod = "GET"
        conn.connect()
        val text = conn.inputStream.bufferedReader().readText()
        conn.disconnect()

        val arr = JSONArray(text)
        val out = ArrayList<RemoteFile>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(RemoteFile(o.optString("name", ""), o.optLong("size", 0L)))
        }
        return out
    }

    fun upload(file: File, host: String, port: Int) {
        val url = "http://$host:$port/upload?filename=" + enc(file.name)
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(file.length())
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        conn.setRequestProperty("User-Agent", MtDownloader.UA)
        conn.connectTimeout = 10000
        conn.readTimeout = 120000
        conn.connect()

        file.inputStream().use { input ->
            conn.outputStream.use { output ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                }
            }
        }
        val code = conn.responseCode
        conn.disconnect()
        if (code !in 200..299) {
            throw Exception("对方返回 HTTP $code")
        }
    }
}
