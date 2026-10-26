package com.liquid.browser.browser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL

/** 下載選單的一個選項：畫質（高度，0＝未知）與檔案大小（位元組，-1＝未知） */
data class MediaOption(val url: String, val height: Int, val bytes: Long, val kind: String)

/**
 * 分析網頁上偵測到的影片，列出可下載的畫質與大小：
 *  - HLS 主清單：每個畫質一個選項，大小＝位元率 × 總長度（讀其中一個子清單加總 #EXTINF）；
 *  - HLS 子清單：大小＝第一個片段大小 ÷ 片段長度 × 總長度（估算）；
 *  - MP4 等直連：向伺服器查 Content-Length，畫質從網址判斷（720p、_1080 這類）。
 * 每個網址最多等 4 秒，查不到的照樣列出（大小未知）。順序依傳入的網址（網頁上由上到下）。
 */
object MediaProbe {
    suspend fun options(urls: List<String>, headers: Map<String, String>): List<MediaOption> = withContext(Dispatchers.IO) {
        coroutineScope {
            urls.map { u -> async { withTimeoutOrNull(4_000) { runCatching { probe(u, headers) }.getOrNull() } ?: listOf(MediaOption(u, guessHeight(u), -1, kindOf(u))) } }
                // 依傳入順序（網頁上由上到下）分組；同一部影片的不同畫質排在一起，畫質高的在前
                .awaitAll().flatMap { group -> group.sortedWith(compareByDescending<MediaOption> { it.height }.thenByDescending { it.bytes }) }
                .distinctBy { it.url }
        }
    }

    private fun probe(url: String, headers: Map<String, String>): List<MediaOption> {
        if (!url.substringBefore('?').lowercase().endsWith(".m3u8")) return listOf(MediaOption(url, guessHeight(url), contentLength(url, headers), kindOf(url)))
        val text = get(url, headers)
        if ("#EXT-X-STREAM-INF" !in text) {
            val (dur, segs) = playlist(text, url)
            val first = segs.firstOrNull()
            val bytes = if (first != null && dur > 0) contentLength(first.first, headers).takeIf { it > 0 }?.let { (it / first.second * dur).toLong() } ?: -1 else -1
            return listOf(MediaOption(url, guessHeight(url), bytes, "HLS"))
        }
        // 主清單：#EXT-X-STREAM-INF:BANDWIDTH=…,RESOLUTION=1280x720 下一行是子清單網址
        val lines = text.lines()
        val variants = lines.mapIndexedNotNull { i, l ->
            if (!l.startsWith("#EXT-X-STREAM-INF")) return@mapIndexedNotNull null
            val next = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return@mapIndexedNotNull null
            val bw = Regex("[,:]BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0
            val h = Regex("RESOLUTION=\\d+x(\\d+)").find(l)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            Triple(URL(URL(url), next.trim()).toString(), h, bw)
        }
        val dur = variants.firstOrNull()?.let { runCatching { playlist(get(it.first, headers), it.first).first }.getOrNull() } ?: 0.0
        return variants.map { (u, h, bw) -> MediaOption(u, h, if (bw > 0 && dur > 0) (bw / 8 * dur).toLong() else -1, "HLS") }
    }

    /** 子清單的總長度（秒）與每個片段（網址、長度） */
    private fun playlist(text: String, base: String): Pair<Double, List<Pair<String, Double>>> {
        val segs = ArrayList<Pair<String, Double>>()
        var d = 0.0
        for (l in text.lines()) {
            if (l.startsWith("#EXTINF:")) d = l.removePrefix("#EXTINF:").substringBefore(',').toDoubleOrNull() ?: 0.0
            else if (l.isNotBlank() && !l.startsWith("#")) { segs += URL(URL(base), l.trim()).toString() to d; d = 0.0 }
        }
        return segs.sumOf { it.second } to segs
    }

    private fun open(url: String, headers: Map<String, String>) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 3_000; readTimeout = 3_000
        headers.forEach { (k, v) -> if (v.isNotEmpty()) setRequestProperty(k, v) }
    }

    private fun get(url: String, headers: Map<String, String>): String {
        val c = open(url, headers)
        try { return c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }

    /** 檔案大小：先 HEAD，不支援時用 Range 0-0 讀 Content-Range 的總長度 */
    private fun contentLength(url: String, headers: Map<String, String>): Long {
        val head = open(url, headers).apply { requestMethod = "HEAD" }
        try { if (head.responseCode in 200..299 && head.contentLengthLong > 0) return head.contentLengthLong } finally { head.disconnect() }
        val c = open(url, headers).apply { setRequestProperty("Range", "bytes=0-0") }
        try { return c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1 } finally { c.disconnect() }
    }

    private fun kindOf(url: String) = url.substringBefore('?').substringAfterLast('.', "").uppercase().takeIf { it.length in 2..4 } ?: "VIDEO"

    /** 從網址判斷畫質：720p、1080P、_480.mp4、-2160- 這類 */
    fun guessHeight(url: String): Int {
        val u = url.substringBefore('?')
        Regex("(\\d{3,4})[pP](?![a-zA-Z])").find(u)?.groupValues?.get(1)?.toIntOrNull()?.let { if (it in 144..4320) return it }
        Regex("[_\\-/.](144|240|360|480|540|720|1080|1440|2160)(?=[_\\-/.])").find(u)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return 0
    }
}
