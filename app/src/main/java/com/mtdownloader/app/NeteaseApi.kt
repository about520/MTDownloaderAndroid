package com.mtdownloader.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 网易云音乐 Web 接口（与 iOS 版同一套实测结论）：
 * - 取链必须带 br 参数，依次 999000/320000/192000/128000 回退
 * - 几乎所有歌都能拿到直链；fee=8 会员歌多数是完整 320k，
 *   fee=1 付费单曲常是 30 秒试听（约 481KB）
 * - 试听判定：size > 时长/1000 * br/8 * 0.75 才算完整版
 * - 试听时自动搜同名歌找完整版本替代
 * - 会员 Cookie（含 MUSIC_U）可解锁完整版/无损
 */
object NeteaseApi {

    private const val BASE = "https://music.163.com/api"
    private val UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 " +
        "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    private val LADDER = intArrayOf(999000, 320000, 192000, 128000)

    data class Song(
        val id: Long,
        val name: String,
        val artists: String,
        val album: String,
        val durationMs: Long,
        val fee: Int
    ) {
        val durationText: String
            get() {
                val s = durationMs / 1000
                return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
            }

        val feeText: String
            get() = when (fee) {
                0 -> "免费"; 1 -> "付费单曲"; 4 -> "需购专辑"; 8 -> "会员"; else -> "受限"
            }

        fun isFull(size: Long, br: Int): Boolean {
            if (durationMs <= 0 || br <= 0 || size <= 0) return true
            val expected = durationMs / 1000.0 * (br / 8.0)
            return size > expected * 0.75
        }

        fun fileName(): String {
            val raw = if (artists.isEmpty()) name else "$name - $artists"
            return MtDownloader.safeName("$raw.mp3")
        }
    }

    data class Artist(val id: Long, val name: String)
    data class Playlist(val id: Long, val name: String, val trackCount: Int)

    data class Link(
        val url: String,
        val size: Long,
        val br: Int,
        val full: Boolean,
        val song: Song,
        val swapped: Boolean
    ) {
        val qualityText: String get() = "${br / 1000}k"
    }

    // MARK: 基础请求

    private fun get(urlStr: String, cookie: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 20000
            conn.readTimeout = 25000
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Referer", "https://music.163.com/")
            conn.setRequestProperty("Cookie",
                if (cookie.isBlank()) "appver=2.9.7; os=pc" else cookie)
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun optArr(o: JSONObject?, key: String): JSONArray? = o?.optJSONArray(key)

    // MARK: 解析

    private fun parseSongs(arr: JSONArray?): List<Song> {
        if (arr == null) return emptyList()
        val out = ArrayList<Song>()
        for (i in 0 until arr.length()) {
            val d = arr.optJSONObject(i) ?: continue
            val id = d.optLong("id", 0)
            if (id <= 0) continue
            var artists = ""
            val ars = d.optJSONArray("artists")
            if (ars != null) {
                val parts = ArrayList<String>()
                for (k in 0 until ars.length()) {
                    val n = ars.optJSONObject(k)?.optString("name") ?: ""
                    if (n.isNotEmpty()) parts.add(n)
                }
                artists = parts.joinToString("、")
            }
            if (artists.isEmpty()) {
                val n = d.optJSONObject("album")?.optJSONObject("artist")?.optString("name")
                if (!n.isNullOrEmpty()) artists = n
            }
            val album = d.optJSONObject("album")?.optString("name") ?: ""
            out.add(Song(id, d.optString("name", "未知曲目"), artists, album,
                d.optLong("duration", 0), d.optInt("fee", 1)))
        }
        return out
    }

    private fun searchJson(kw: String, type: Int, ck: String): JSONObject? {
        val t = get("$BASE/search/get/web?s=${enc(kw)}&type=$type&offset=0&limit=40", ck)
        return t?.let { try { JSONObject(it) } catch (_: Exception) { null } }
    }

    // MARK: 搜索

    fun searchSongs(kw: String, ck: String): List<Song> =
        parseSongs(optArr(searchJson(kw, 1, ck)?.optJSONObject("result"), "songs"))

    fun searchArtists(kw: String, ck: String): List<Artist> {
        val arr = optArr(searchJson(kw, 100, ck)?.optJSONObject("result"), "artists") ?: return emptyList()
        val out = ArrayList<Artist>()
        for (i in 0 until arr.length()) {
            val d = arr.optJSONObject(i) ?: continue
            val id = d.optLong("id", 0)
            if (id > 0) out.add(Artist(id, d.optString("name", "未知歌手")))
        }
        return out
    }

    fun searchPlaylists(kw: String, ck: String): List<Playlist> {
        val arr = optArr(searchJson(kw, 1000, ck)?.optJSONObject("result"), "playlists") ?: return emptyList()
        val out = ArrayList<Playlist>()
        for (i in 0 until arr.length()) {
            val d = arr.optJSONObject(i) ?: continue
            val id = d.optLong("id", 0)
            if (id > 0) out.add(Playlist(id, d.optString("name", "未知歌单"), d.optInt("trackCount", 0)))
        }
        return out
    }

    // MARK: 下钻

    fun artistSongs(artistId: Long, ck: String): List<Song> {
        val j = get("$BASE/artist/top/song?id=$artistId", ck) ?: return emptyList()
        val root = try { JSONObject(j) } catch (_: Exception) { return emptyList() }
        return parseSongs(root.optJSONArray("songs"))
    }

    fun playlistSongs(playlistId: Long, ck: String): List<Song> {
        val j = get("$BASE/playlist/detail?id=$playlistId", ck) ?: return emptyList()
        val root = try { JSONObject(j) } catch (_: Exception) { return emptyList() }
        return parseSongs(root.optJSONObject("result")?.optJSONArray("tracks"))
    }

    // MARK: 取直链

    private fun fetchRaw(id: Long, ck: String): Triple<String, Long, Int>? {
        for (br in LADDER) {
            val t = get("$BASE/song/enhance/player/url?br=$br&ids=%5B$id%5D", ck) ?: continue
            val d = try { JSONObject(t) } catch (_: Exception) { continue }
                .optJSONArray("data")?.optJSONObject(0) ?: continue
            val u = d.optString("url")
            if (u.isNotEmpty()) {
                var brOut = d.optInt("br", br)
                if (brOut <= 0) brOut = br
                return Triple(u, d.optLong("size", 0), brOut)
            }
        }
        return null
    }

    /** 主入口：取最佳直链；试听片段自动换同名完整版 */
    fun fetchLink(song: Song, ck: String): Link? {
        val raw = fetchRaw(song.id, ck) ?: return null
        if (song.isFull(raw.second, raw.third)) {
            return Link(raw.first, raw.second, raw.third, true, song, false)
        }
        val alt = findFullVersion(song, ck)
        return alt ?: Link(raw.first, raw.second, raw.third, false, song, false)
    }

    private fun findFullVersion(song: Song, ck: String): Link? {
        val cands = searchSongs(song.name, ck).filter { it.id != song.id }.take(8)
        for (c in cands) {
            val r = fetchRaw(c.id, ck) ?: continue
            if (c.isFull(r.second, r.third)) {
                return Link(r.first, r.second, r.third, true, c, true)
            }
        }
        return null
    }

    // MARK: 账号

    fun checkLogin(ck: String): String {
        if (ck.isBlank()) return "未登录"
        val t = get("$BASE/w/nuser/account/get", ck) ?: return "校验失败，检查网络"
        val root = try { JSONObject(t) } catch (_: Exception) { return "Cookie 无效" }
        val p = root.optJSONObject("profile") ?: return "Cookie 无效或已过期"
        val nick = p.optString("nickname", "未知")
        val vip = p.optInt("vipType", 0)
        return "$nick · ${if (vip > 0) "会员 v$vip" else "非会员"}"
    }
}
