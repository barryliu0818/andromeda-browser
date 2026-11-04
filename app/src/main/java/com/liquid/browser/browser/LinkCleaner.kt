package com.liquid.browser.browser

import android.net.Uri

/**
 * 移除網址上的追蹤參數（Safari 的「連結追蹤保護」）：只拿掉用來跨網站辨識你的參數
 * （廣告點擊編號、行銷活動標記），網頁本身需要的參數（搜尋字、頁碼、商品編號）原樣保留。
 * 預設只在無痕分頁套用；設定可以改成所有瀏覽都套用。
 */
object LinkCleaner {
    /** 任何網站都拿掉的參數 */
    private val Global = setOf(
        // Google Analytics／Ads 行銷活動與點擊編號
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id", "utm_name", "utm_reader", "utm_brand", "utm_social", "utm_social-type",
        "gclid", "dclid", "gbraid", "wbraid", "gclsrc", "_ga", "_gl",
        // Facebook／Instagram、Microsoft、Yandex、TikTok、X、LinkedIn 的點擊編號
        "fbclid", "igshid", "msclkid", "yclid", "ttclid", "twclid", "li_fat_id",
        // 電子報與行銷平台（Mailchimp、HubSpot、Marketo、Oracle Eloqua、Vero、Omeda）
        "mc_cid", "mc_eid", "_hsenc", "_hsmi", "__hssc", "__hstc", "__hsfp", "hsctatracking", "mkt_tok", "elqTrackId", "elqTrack", "vero_id", "vero_conv", "oly_anon_id", "oly_enc_id",
        // 其他常見的分享追蹤
        "s_cid", "srsltid",
    )

    /** 只在特定網站拿掉的參數（同名參數在別的網站可能有用途） */
    private val PerSite = mapOf(
        "youtube.com" to setOf("si", "pp", "feature"),
        "youtu.be" to setOf("si", "feature"),
        "spotify.com" to setOf("si", "context"),
        "instagram.com" to setOf("igsh"),
        "twitter.com" to setOf("s", "t"),
        "x.com" to setOf("s", "t"),
        "amazon.com" to setOf("ref_", "pd_rd_r", "pd_rd_w", "pd_rd_wg", "pf_rd_p", "pf_rd_r", "pd_rd_i", "content-id"),
    )

    /** 回傳拿掉追蹤參數後的網址；沒有可拿掉的就原樣回傳（同一個字串物件） */
    fun clean(url: String): String {
        if (!url.startsWith("http") || '?' !in url) return url
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return url
        val host = uri.host.orEmpty()
        val site = PerSite.entries.firstOrNull { (d, _) -> host == d || host.endsWith(".$d") }?.value.orEmpty()
        val names = runCatching { uri.queryParameterNames }.getOrNull() ?: return url
        val drop = names.filter { it in Global || it in site || it.startsWith("utm_") }
        if (drop.isEmpty()) return url
        // 保留其他參數的原始寫法與順序（不重新編碼，免得改壞網頁自己的參數）
        val kept = uri.encodedQuery.orEmpty().split('&').filter { part ->
            val name = Uri.decode(part.substringBefore('='))
            name !in drop
        }
        return uri.buildUpon().encodedQuery(kept.joinToString("&").ifEmpty { null }).build().toString()
    }
}

/**
 * 跳過追蹤跳轉頁（Brave 的 debounce）：連結先經過 Google、Facebook、知乎、微博…的轉址頁記錄你點了什麼才跳過去；
 * 網址裡已經帶著真正的目的地，直接開目的地。
 */
object Debounce {
    /** 主網域 + 路徑開頭 → 放目的地的參數名稱 */
    private val Rules = listOf(
        Triple("google.com", "/url", listOf("q", "url")),
        Triple("facebook.com", "/l.php", listOf("u")),
        Triple("youtube.com", "/redirect", listOf("q")),
        Triple("zhihu.com", "/", listOf("target")),           // link.zhihu.com/?target=
        Triple("weibo.cn", "/sinaurl", listOf("u", "toasturl")),
        Triple("jianshu.com", "/go-wild", listOf("url")),
        Triple("douban.com", "/link2", listOf("url")),
        Triple("csdn.net", "/", listOf("target")),            // link.csdn.net/?target=
        Triple("juejin.cn", "/", listOf("target")),           // link.juejin.cn/?target=
        Triple("steamcommunity.com", "/linkfilter", listOf("url", "u")),
        Triple("reddit.com", "/", listOf("url")),             // out.reddit.com/…?url=
        Triple("vk.com", "/away.php", listOf("to")),
        Triple("instagram.com", "/", listOf("u")),            // l.instagram.com/?u=
    )

    /** 回傳真正的目的地；不是追蹤跳轉頁就回傳 null */
    fun target(url: String): String? {
        if (!url.startsWith("http") || '?' !in url) return null
        // 用 java.net.URI（單元測試也能跑）；查詢參數自己拆、解碼
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        val host = uri.host.orEmpty().lowercase()
        val path = uri.rawPath.orEmpty().ifEmpty { "/" }
        val query = uri.rawQuery.orEmpty().split('&').mapNotNull { p ->
            val k = p.substringBefore('=', "")
            if (k.isEmpty()) null else k to runCatching { java.net.URLDecoder.decode(p.substringAfter('='), "UTF-8") }.getOrDefault("")
        }.toMap()
        for ((domain, prefix, params) in Rules) {
            if (host != domain && !host.endsWith(".$domain")) continue
            if (!path.startsWith(prefix)) continue
            // 知乎、CSDN、掘金、Instagram 的轉址在子網域（link.／l.／out.）上，主站同名參數不碰
            if (prefix == "/" && host.substringBefore('.') !in setOf("link", "l", "out")) continue
            val dest = params.firstNotNullOfOrNull { query[it] } ?: continue
            if (dest.startsWith("https://") || dest.startsWith("http://")) return dest
        }
        return null
    }
}
