package com.liquid.browser.browser

import android.content.Context
import android.webkit.WebResourceResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 廣告／追蹤阻擋（類似 AdGuard 的 DNS 層過濾）：比對請求的網域（含上層網域）是否在阻擋清單中。
 *  - 內建精簡清單（assets/adblock_builtin.txt），離線也有效；
 *  - 開啟後下載 AdGuard DNS 過濾清單（約 5 萬條，jsDelivr 鏡像，中國也能下載），每 7 天更新；
 *  - 清單裡的例外規則（@@||網域^）不阻擋。
 * 比對在 WebView 的網路執行緒上進行：用 HashSet 查網域與各層上層網域，成本是常數時間。
 */
object AdBlocker {
    private val urls = listOf(
        "https://cdn.jsdelivr.net/gh/AdguardTeam/AdGuardSDNSFilter@gh-pages/Filters/filter.txt",
        "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt",
    )
    private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var blocked: Set<String> = emptySet()
    @Volatile private var allowed: Set<String> = emptySet()
    @Volatile private var loaded = false

    /** 清單規則數（設定頁顯示用） */
    val ruleCount: Int get() = blocked.size

    /** 載入內建清單與快取的完整清單；快取太舊或不存在就背景下載 */
    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val app = context.applicationContext
        scope.launch {
            val cache = File(app.filesDir, "adguard_filter.txt")
            val builtin = app.assets.open("adblock_builtin.txt").bufferedReader().readLines()
            apply(builtin + (if (cache.exists()) cache.readLines() else emptyList()))
            if (!cache.exists() || System.currentTimeMillis() - cache.lastModified() > MAX_AGE_MS) {
                download()?.let { text ->
                    cache.writeText(text)
                    apply(builtin + text.lines())
                }
            }
        }
    }

    private fun download(): String? = urls.firstNotNullOfOrNull { u ->
        runCatching {
            val conn = (URL(u).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 30_000 }
            try { if (conn.responseCode == 200) conn.inputStream.bufferedReader().readText().takeIf { it.length > 10_000 } else null } finally { conn.disconnect() }
        }.getOrNull()
    }

    /** 解析 Adblock 語法中的網域規則：||example.com^（阻擋）與 @@||example.com^（例外）；純網域行也接受 */
    private fun apply(lines: List<String>) {
        val block = HashSet<String>(lines.size)
        val allow = HashSet<String>()
        val rule = Regex("^(@@)?\\|\\|([a-z0-9.-]+)\\^(\\\$important)?$")
        for (raw in lines) {
            val line = raw.trim().lowercase()
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("#")) continue
            val m = rule.matchEntire(line)
            when {
                m != null -> if (m.groupValues[1].isNotEmpty()) allow += m.groupValues[2] else block += m.groupValues[2]
                // 內建清單：一行一個網域
                Regex("^[a-z0-9.-]+\\.[a-z]{2,}$").matches(line) -> block += line
            }
        }
        blocked = block
        allowed = allow
    }

    /** 網域（或其上層網域）是否應阻擋 */
    fun blocks(host: String): Boolean {
        val b = blocked
        if (b.isEmpty()) return false
        var h = host.lowercase().removePrefix("www.")
        while (true) {
            if (h in allowed) return false
            if (h in b) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false // 只剩頂級網域就停
            h = h.substring(dot + 1)
        }
    }

    /** 被阻擋請求的回應：空內容 */
    fun emptyResponse() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    /** 常見廣告版位的元素隱藏（cosmetic filter），開啟阻擋時注入 */
    const val COSMETIC_JS = """(function(){
      if (window.__lqCosmetic) return; window.__lqCosmetic = true;
      var s = document.createElement('style');
      s.textContent = 'ins.adsbygoogle,[id^="google_ads"],[id^="div-gpt-ad"],iframe[src*="doubleclick.net"],iframe[src*="googlesyndication"],' +
        '[class*="ad-banner"],[class*="adsbox"],[id*="ad-container"],[class*="sponsored-"],.ad-slot,.adsense,[data-ad-slot],[data-google-query-id],' +
        'a[href*="pos.baidu.com"],iframe[src*="pos.baidu.com"],[id^="cpro_"],iframe[src*="tanx.com"]{display:none!important}';
      (document.head || document.documentElement).appendChild(s);
    })();"""
}

/** 近似的「主網域」（eTLD+1），判斷跳轉是否換了網站：example.co.uk、example.com.tw 這類兩層後綴也處理 */
fun baseDomain(host: String): String {
    val parts = host.lowercase().removePrefix("www.").split('.')
    if (parts.size <= 2) return parts.joinToString(".")
    val secondLevel = setOf("co", "com", "net", "org", "gov", "edu", "ac", "or", "ne", "go")
    return if (parts[parts.size - 2] in secondLevel && parts.last().length == 2) parts.takeLast(3).joinToString(".") else parts.takeLast(2).joinToString(".")
}
