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
 * 廣告／追蹤阻擋，兩層：
 *  - Brave 的 adblock-rust（[AdEngine]）：EasyList＋EasyPrivacy＋EasyList China＋AdGuard DNS 清單，完整語法（路徑規則、資源類型、
 *    第三方、例外，以及各網站專屬的元素隱藏）。解析好的引擎存成二進位快取，下次啟動數十毫秒就載入。
 *  - 網域比對（原本的 DNS 層過濾）：內建精簡清單（assets/adblock_builtin.txt）＋AdGuard DNS 清單。原生引擎載入失敗時的備援，
 *    也用來攔「點廣告跳轉」（[blocks]）。
 * 清單每 7 天更新；查詢都在 WebView 的網路執行緒上進行。
 */
object AdBlocker {
    /** 清單檔名 → 下載網址（依序嘗試） */
    private val lists = linkedMapOf(
        "adguard_filter.txt" to listOf(
            "https://cdn.jsdelivr.net/gh/AdguardTeam/AdGuardSDNSFilter@gh-pages/Filters/filter.txt",
            "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt",
        ),
        "easylist.txt" to listOf("https://easylist.to/easylist/easylist.txt"),
        "easyprivacy.txt" to listOf("https://easylist.to/easylist/easyprivacy.txt"),
        "easylistchina.txt" to listOf("https://easylist-downloads.adblockplus.org/easylistchina.txt"),
    )
    /** 引擎快取檔名帶 adblock-rust 版本：升級原生程式庫後舊快取格式不符，自動用清單重建 */
    private const val ENGINE_FILE = "adblock_engine_0.13.5.bin"
    private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var blocked: Set<String> = emptySet()
    @Volatile private var allowed: Set<String> = emptySet()
    @Volatile private var loaded = false
    /** 原生引擎已載入清單（否則用網域比對） */
    @Volatile private var engineReady = false
    @Volatile private var engineRules = 0

    /** 清單規則數（設定頁顯示用） */
    val ruleCount: Int get() = if (engineReady) engineRules else blocked.size
    val usingEngine: Boolean get() = engineReady

    /** 載入內建清單與快取的清單、引擎快取；清單太舊或不存在就背景下載後重建 */
    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val app = context.applicationContext
        scope.launch {
            val builtin = app.assets.open("adblock_builtin.txt").bufferedReader().readLines()
            val files = lists.keys.map { File(app.filesDir, it) }
            fun adguard() = File(app.filesDir, "adguard_filter.txt").takeIf { it.exists() }?.readLines().orEmpty()
            apply(builtin + adguard())
            if (AdEngine.available) {
                val bin = File(app.filesDir, ENGINE_FILE)
                val newestList = files.filter { it.exists() }.maxOfOrNull { it.lastModified() } ?: 0L
                val cached = bin.exists() && bin.lastModified() >= newestList &&
                    runCatching { AdEngine.nativeLoad(bin.readBytes()) }.getOrDefault(false)
                if (cached) {
                    engineRules = app.getSharedPreferences("adblock", Context.MODE_PRIVATE).getInt("rules", 0)
                    engineReady = true
                } else if (files.any { it.exists() }) build(app, builtin, files)
            }
            var changed = false
            for ((name, urls) in lists) {
                val f = File(app.filesDir, name)
                if (f.exists() && System.currentTimeMillis() - f.lastModified() <= MAX_AGE_MS) continue
                download(urls)?.let { f.writeText(it); changed = true }
            }
            if (changed) {
                apply(builtin + adguard())
                if (AdEngine.available) build(app, builtin, files)
            }
        }
    }

    /** 所有清單接在一起交給原生引擎解析，存成二進位快取 */
    private fun build(app: Context, builtin: List<String>, files: List<File>) {
        val started = android.os.SystemClock.uptimeMillis()
        val text = buildString {
            // 內建清單是一行一個網域：轉成 Adblock 語法的網域規則
            builtin.forEach { if (it.isNotBlank() && !it.startsWith("#")) append("||").append(it.trim()).append("^\n") }
            files.filter { it.exists() }.forEach { append(it.readText()).append('\n') }
        }
        if (!runCatching { AdEngine.nativeBuild(text) }.getOrDefault(false)) return
        engineRules = text.lineSequence().count { it.isNotBlank() && !it.startsWith("!") && !it.startsWith("[") }
        engineReady = true
        app.getSharedPreferences("adblock", Context.MODE_PRIVATE).edit().putInt("rules", engineRules).apply()
        runCatching { AdEngine.nativeSerialize()?.let { File(app.filesDir, ENGINE_FILE).writeBytes(it) } }
        com.liquid.browser.BugLog.event("adblock", "引擎重建 $engineRules 條規則，${android.os.SystemClock.uptimeMillis() - started}ms")
    }

    /**
     * 子資源請求是否阻擋（WebView 網路執行緒）。原生引擎：完整規則比對；備援：網域在清單內且不是網站自己的網域。
     * [type] 見 [resourceType]。
     */
    fun blocksRequest(url: String, host: String, pageHost: String, type: String): Boolean =
        if (engineReady) AdEngine.nativeCheck(url, "https://$pageHost/", type) == 1
        else host.isNotEmpty() && blocks(host) && baseDomain(host) != baseDomain(pageHost)

    /** 請求的資源類型（Adblock 規則的 $script、$image… 選項用）：優先看 Sec-Fetch-Dest，其次 Accept 與副檔名 */
    fun resourceType(r: android.webkit.WebResourceRequest): String {
        when (r.requestHeaders["Sec-Fetch-Dest"]) {
            "script" -> return "script"
            "style" -> return "stylesheet"
            "image" -> return "image"
            "iframe", "frame" -> return "sub_frame"
            "font" -> return "font"
            "video", "audio", "track" -> return "media"
            "empty" -> return "xmlhttprequest"
        }
        val accept = r.requestHeaders["Accept"].orEmpty()
        val path = r.url.path.orEmpty().lowercase()
        return when {
            accept.startsWith("text/css") || path.endsWith(".css") -> "stylesheet"
            accept.startsWith("image/") || IMAGE.containsMatchIn(path) -> "image"
            accept.startsWith("text/html") -> "sub_frame"
            path.endsWith(".js") || path.endsWith(".mjs") -> "script"
            FONT.containsMatchIn(path) -> "font"
            MEDIA.containsMatchIn(path) -> "media"
            else -> "other"
        }
    }
    private val IMAGE = Regex("\\.(png|jpe?g|gif|webp|svg|avif|ico|bmp)$")
    private val FONT = Regex("\\.(woff2?|ttf|otf|eot)$")
    private val MEDIA = Regex("\\.(mp4|webm|m3u8|mpd|ts|m4s|mp3|aac|ogg)$")

    /**
     * 頁面開始載入時注入的元素隱藏：網站專屬規則（原生引擎）＋常見廣告版位（[COSMETIC_JS]，網站有 $generichide 例外時不加）。
     * 每個選擇器各自一條 CSS 規則：清單裡偶有瀏覽器不支援的選擇器，合在一起會讓整條規則失效。
     */
    fun cosmeticJs(url: String): String {
        if (!engineReady) return COSMETIC_JS
        val res = runCatching { org.json.JSONObject(AdEngine.nativeCosmetic(url)) }.getOrNull() ?: return COSMETIC_JS
        val selectors = res.optJSONArray("hide_selectors")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
        val css = selectors.joinToString("") { "$it{display:none!important}" }
        return buildString {
            if (!res.optBoolean("generichide")) append(COSMETIC_JS)
            if (css.isNotEmpty()) append("(function(){var s=document.createElement('style');s.textContent=")
                .append(org.json.JSONObject.quote(css)).append(";(function add(){var p=document.head||document.documentElement;p?p.appendChild(s):setTimeout(add,10)})()})();")
            res.optString("injected_script").takeIf { it.isNotBlank() }?.let { append("try{").append(it).append("}catch(e){}") }
        }
    }

    private fun download(urls: List<String>): String? = urls.firstNotNullOfOrNull { u ->
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
      // 頁面剛開始載入時文件可能還沒有根元素：等它出現再加（以前直接 appendChild 會丟錯，這次載入就沒隱藏）
      (function add(){ var p = document.head || document.documentElement; if (p) p.appendChild(s); else setTimeout(add, 10); })();
    })();"""
}

/** 近似的「主網域」（eTLD+1），判斷跳轉是否換了網站：example.co.uk、example.com.tw 這類兩層後綴也處理 */
fun baseDomain(host: String): String {
    val parts = host.lowercase().removePrefix("www.").split('.')
    if (parts.size <= 2) return parts.joinToString(".")
    val secondLevel = setOf("co", "com", "net", "org", "gov", "edu", "ac", "or", "ne", "go")
    return if (parts[parts.size - 2] in secondLevel && parts.last().length == 2) parts.takeLast(3).joinToString(".") else parts.takeLast(2).joinToString(".")
}
