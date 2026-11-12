package com.liquid.browser.browser

import com.liquid.browser.core.Http
import okhttp3.Request

/**
 * 沒開過的網站也抓得到圖示（起始頁的常用網站、經常瀏覽的網站）：
 * 讀網站首頁的 <link rel="apple-touch-icon"／"icon">，挑最大的一個（apple-touch-icon 沒寫大小時當作 180px，同 Tab 裡的判斷），
 * 都沒有就試 /apple-touch-icon.png、/favicon.ico。SVG 不用（圖片載入器不支援）。
 * 只負責找網址；存起來由呼叫端（BrowserViewModel）處理。
 */
object IconFetcher {
    private val LINK = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
    private fun attr(tag: String, name: String) =
        Regex("\\b$name\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE).find(tag)?.let { m ->
            m.groupValues[2].ifEmpty { m.groupValues[3].ifEmpty { m.groupValues[4] } }
        }

    /** 這個網站最好的圖示網址；找不到回傳 null（背景執行緒呼叫） */
    suspend fun find(siteUrl: String, userAgent: String): String? {
        val root = runCatching { java.net.URI(siteUrl).resolve("/").toString() }.getOrNull() ?: return null
        val html = runCatching {
            Http.exec(Request.Builder().url(root).header("User-Agent", userAgent).build()) { r ->
                if (!r.isSuccessful) "" else r.body.source().let { s -> s.request(262_144); s.buffer.clone().readUtf8() }
            }
        }.getOrDefault("")
        // 網站首頁可能轉址到別的網域（例如 xx.com → www.xx.com）：相對路徑以最終網址為準，這裡以 root 近似
        val candidates = LINK.findAll(html.substringBefore("</head>")).mapNotNull { m ->
            val tag = m.value
            val rel = attr(tag, "rel")?.lowercase() ?: return@mapNotNull null
            if (!rel.contains("icon")) return@mapNotNull null
            val href = attr(tag, "href")?.takeIf { it.isNotBlank() && !it.startsWith("data:") } ?: return@mapNotNull null
            if (href.substringBefore('?').endsWith(".svg", true) || attr(tag, "type")?.contains("svg", true) == true) return@mapNotNull null
            val size = attr(tag, "sizes")?.let { s -> Regex("(\\d+)x(\\d+)").find(s)?.groupValues?.get(1)?.toIntOrNull() }
                ?: if (rel.contains("apple-touch-icon")) 180 else 16
            val abs = runCatching { java.net.URI(root).resolve(href.replace(" ", "%20")).toString() }.getOrNull() ?: return@mapNotNull null
            abs to size
        }.sortedByDescending { it.second }.map { it.first }.toList()
        for (u in candidates + listOf(root + "apple-touch-icon.png", root + "favicon.ico")) {
            if (isImage(u, userAgent)) return u
        }
        return null
    }

    private suspend fun isImage(url: String, userAgent: String): Boolean = runCatching {
        Http.exec(Request.Builder().url(url).header("User-Agent", userAgent).build()) { r ->
            r.isSuccessful && (r.header("Content-Type").orEmpty().startsWith("image/") || url.substringBefore('?').endsWith(".ico", true))
        }
    }.getOrDefault(false)
}
