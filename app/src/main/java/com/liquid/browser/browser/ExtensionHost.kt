package com.liquid.browser.browser

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

/**
 * 擴充功能的「擴充頁面」與訊息轉送（照 Chrome 的擴充架構）：
 *  - 背景程式（MV3 service worker、MV2 背景頁）在看不見的 WebView 裡執行，網址是這個擴充自己的來源
 *    https://<代號>.oreaext.invalid/，檔案由 [serve] 從擴充資料夾回應
 *  - 彈出視窗、選項頁也是同一個來源的頁面（彈出視窗開在「擴展工具管理」，選項頁開成分頁）
 *  - 擴充頁面拿到完整的 chrome.*（assets/ext_api.js），內容腳本拿到 runtime／storage／i18n
 *  - 所有訊息都經過這裡：runtime.sendMessage、tabs.sendMessage、連線（Port）、storage（存在 App，所有地方共用一份）
 *
 * 執行緒：WebView 只能在主執行緒操作，這裡的狀態也都只在主執行緒讀寫（JavascriptInterface 的呼叫先轉到主執行緒）。
 */
object ExtensionHost {
    const val HOST_SUFFIX = ".oreaext.invalid"
    private const val BG_PAGE = "/__orea_bg.html"

    fun originOf(sid: String) = "https://$sid$HOST_SUFFIX"
    fun sidOfHost(host: String?): String? =
        host?.takeIf { it.endsWith(HOST_SUFFIX) }?.removeSuffix(HOST_SUFFIX)?.takeIf { it.matches(Regex("[0-9a-f]{16}")) }

    /** App 這邊的瀏覽器功能（BrowserViewModel 實作） */
    interface Browser {
        val allTabs: List<Tab>
        val currentTab: Tab?
        fun openTab(url: String, active: Boolean): Tab
        fun closeTab(tab: Tab)
        fun selectTab(tab: Tab)
        fun toast(text: String)
        fun download(tab: Tab?, url: String, name: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private var browser: Browser? = null

    /** assets/ext_api.js：__oreaMkChrome() */
    lateinit var apiJs: String
        private set
    private lateinit var pageShim: String

    fun init(ctx: Context, b: Browser) {
        app = ctx.applicationContext
        browser = b
        if (!::apiJs.isInitialized) {
            apiJs = ctx.assets.open("ext_api.js").bufferedReader().use { it.readText() }
            pageShim = apiJs + PAGE_PRELUDE.replace("TOKEN", JSONObject.quote(UserScripts.token))
        }
    }

    // ---------- 端點：擴充頁面（背景程式、彈出視窗、選項頁）與網頁框架裡的內容腳本 ----------
    private abstract class End(val sid: String) {
        abstract val tab: Tab?
        abstract fun deliver(d: JSONObject)
    }

    private class PageEnd(sid: String, val view: WebView, override val tab: Tab?) : End(sid) {
        override fun deliver(d: JSONObject) {
            main.post { runCatching { view.evaluateJavascript("window.__oreaRecv&&__oreaRecv($d)", null) } }
        }
    }

    private class FrameEnd(sid: String, override val tab: Tab, val reply: JavaScriptReplyProxy, val frameId: Int, var url: String) : End(sid) {
        override fun deliver(d: JSONObject) {
            main.post { runCatching { reply.postMessage(JSONObject().put("gm", "ext").put("id", sid).put("d", d).toString()) } }
        }
    }

    private val pages = mutableListOf<PageEnd>()
    private val frames = mutableListOf<FrameEnd>()
    private var frameSeq = 0

    /** 擴充頁面用的 JavascriptInterface（名稱 __oreaXP）：每個 WebView 一個，知道訊息是從哪個 WebView 來的 */
    class PageBridge(private val view: () -> WebView?, private val tab: Tab?) {
        @JavascriptInterface
        fun cfg(t: String?, host: String?): String? {
            if (t != UserScripts.token) return null
            val sid = sidOfHost(host) ?: return null
            val ext = Extensions.bySid(sid)?.takeIf { it.enabled } ?: return null
            return pageConfig(ext).toString()
        }

        @JavascriptInterface
        fun post(t: String?, sid: String?, json: String?) {
            if (t != UserScripts.token || sid == null || json == null) return
            val d = runCatching { JSONObject(json) }.getOrNull() ?: return
            main.post {
                val v = view() ?: return@post
                // 只收這個擴充自己的頁面送來的
                if (sidOfHost(Uri.parse(v.url ?: "").host) != sid) return@post
                val end = pages.firstOrNull { it.view === v && it.sid == sid } ?: PageEnd(sid, v, tab).also { pages.add(it) }
                handle(end, d)
            }
        }
    }

    private fun pageConfig(ext: Extensions.Ext) = JSONObject()
        .put("sid", ext.sid).put("id", ext.chromeId).put("manifest", ext.jsManifest).put("messages", ext.messages)
        .put("lang", java.util.Locale.getDefault().toLanguageTag()).put("page", true).put("firefox", ext.firefox).put("base", originOf(ext.sid) + "/")

    /** 內容腳本送來的訊息（Tab 的 LiquidBridge，已核對 token；主執行緒） */
    fun fromFrame(tab: Tab, reply: JavaScriptReplyProxy, mainFrame: Boolean, sid: String, d: JSONObject) {
        if (Extensions.bySid(sid)?.enabled != true) return
        val end = frames.firstOrNull { it.tab === tab && it.reply === reply && it.sid == sid } ?: run {
            // 同一個框架的不同擴充用同一個框架編號；最上層是 0（同 Chrome）
            val fid = if (mainFrame) 0 else frames.firstOrNull { it.tab === tab && it.reply === reply }?.frameId ?: ++frameSeq
            FrameEnd(sid, tab, reply, fid, tab.url).also { frames.add(it) }
        }
        handle(end, d)
    }

    // ---------- 訊息轉送 ----------
    private class Pending(val from: End, val rid: String, var left: Int)
    private class Port(val a: End, val b: MutableList<End>)

    private val pending = HashMap<String, Pending>()
    private val ports = HashMap<String, Port>()
    private var seq = 0

    private fun res(rid: String, value: Any?): JSONObject = JSONObject().put("t", "res").put("rid", rid).also { if (value != null) it.put("val", value) }
    private fun err(rid: String, message: String): JSONObject = JSONObject().put("t", "res").put("rid", rid).put("err", message)

    private fun handle(from: End, d: JSONObject) {
        val rid = d.optString("rid")
        when (d.optString("t")) {
            "hello" -> if (from is FrameEnd) from.url = d.optString("url", from.url)
            "ready" -> backgroundReady(from)
            "send" -> {
                if (waitForBackground(from, d)) return
                val targets = targets(from, d)
                if (targets.isEmpty()) { from.deliver(err(rid, "Could not establish connection. Receiving end does not exist.")); return }
                val id = "m" + (++seq)
                pending[id] = Pending(from, rid, targets.size)
                val m = JSONObject().put("t", "msg").put("rid", id).put("msg", d.opt("msg")).put("sender", senderOf(from))
                targets.forEach { it.deliver(m) }
            }
            "reply" -> {
                val p = pending[rid] ?: return
                if (d.optBoolean("has")) { pending.remove(rid); p.from.deliver(res(p.rid, d.opt("resp"))) }
                else if (--p.left <= 0) { pending.remove(rid); p.from.deliver(res(p.rid, null)) }
            }
            "connect" -> {
                if (waitForBackground(from, d)) return
                val pid = d.optString("pid")
                val targets = targets(from, d)
                if (targets.isEmpty()) {
                    from.deliver(JSONObject().put("t", "pdisc").put("pid", pid).put("err", "Could not establish connection. Receiving end does not exist."))
                    return
                }
                ports[pid] = Port(from, targets.toMutableList())
                val m = JSONObject().put("t", "connect").put("pid", pid).put("name", d.optString("name")).put("sender", senderOf(from))
                targets.forEach { it.deliver(m) }
            }
            "pmsg" -> {
                val pid = d.optString("pid")
                val port = ports[pid] ?: return
                val m = JSONObject().put("t", "pmsg").put("pid", pid).put("msg", d.opt("msg"))
                if (from === port.a) port.b.forEach { it.deliver(m) } else port.a.deliver(m)
            }
            "pdisc" -> {
                val pid = d.optString("pid")
                val port = ports[pid] ?: return
                val m = JSONObject().put("t", "pdisc").put("pid", pid)
                if (from === port.a) { ports.remove(pid); port.b.forEach { it.deliver(m) } }
                else { port.b.remove(from); if (port.b.isEmpty()) { ports.remove(pid); port.a.deliver(m) } }
            }
            "storage" -> from.deliver(runCatching { res(rid, storage(from.sid, d)) }.getOrElse { err(rid, it.message ?: "storage error") })
            "api" -> api(from, d.optString("name"), d.optJSONArray("args") ?: JSONArray()) { value, e ->
                from.deliver(if (e != null) err(rid, e) else res(rid, value))
            }
            "fetch" -> {
                val req = d.optJSONObject("req") ?: return
                val ua = WebSettings.getDefaultUserAgent(app)
                UserScripts.xhr("ext" + (++seq), req, originOf(from.sid) + "/", ua, false) { out -> from.deliver(res(rid, out)) }
            }
        }
    }

    // 背景程式還沒載入完（剛開 App、剛安裝）：給背景程式的訊息先排隊，載入完再送（同 Chrome 收到訊息時喚醒 service worker）
    private val readyBg = HashSet<String>()
    private val queued = HashMap<String, MutableList<Pair<End, JSONObject>>>()

    private fun waitForBackground(from: End, d: JSONObject): Boolean {
        if (d.optBoolean("toCs") || from.sid in readyBg) return false
        val ext = Extensions.bySid(from.sid) ?: return false
        if (!ext.enabled || !ext.works || !ext.background) return false
        queued.getOrPut(from.sid) { mutableListOf() }.add(from to d)
        if (from.sid !in backgrounds) sync()
        return true
    }

    /** 訊息送給誰：內容腳本（tabs.sendMessage／tabs.connect）或這個擴充的其他頁面（runtime.sendMessage／connect） */
    private fun targets(from: End, d: JSONObject): List<End> =
        if (d.optBoolean("toCs")) {
            val tabId = d.optLong("tab", -1)
            val fid = if (d.isNull("frameId") || !d.has("frameId")) null else d.optInt("frameId")
            frames.filter { it.sid == from.sid && it.tab.id == tabId && (fid == null || it.frameId == fid) }
        } else pages.filter { it.sid == from.sid && it !== from }

    private fun senderOf(e: End): JSONObject {
        val ext = Extensions.bySid(e.sid)
        val s = JSONObject().put("id", ext?.chromeId ?: e.sid)
        when (e) {
            is FrameEnd -> {
                s.put("url", e.url).put("frameId", e.frameId).put("tab", tabInfo(e.tab)).put("documentLifecycle", "active")
                runCatching { Uri.parse(e.url).let { u -> s.put("origin", "${u.scheme}://${u.authority}") } }
            }
            is PageEnd -> {
                s.put("url", e.view.url ?: originOf(e.sid)).put("origin", originOf(e.sid))
                e.tab?.let { s.put("tab", tabInfo(it)).put("frameId", 0) }
            }
        }
        return s
    }

    /** 端點消失（換頁、關分頁、關彈出視窗）：等它回覆的訊息算沒有回覆，連線斷開 */
    private fun gone(ends: List<End>) {
        if (ends.isEmpty()) return
        pages.removeAll(ends.toSet()); frames.removeAll(ends.toSet())
        pending.entries.toList().forEach { (k, p) -> if (p.from in ends) pending.remove(k) }
        ports.entries.toList().forEach { (pid, port) ->
            val m = JSONObject().put("t", "pdisc").put("pid", pid)
            if (port.a in ends) { ports.remove(pid); port.b.filter { it !in ends }.forEach { it.deliver(m) } }
            else if (port.b.removeAll(ends.toSet()) && port.b.isEmpty()) { ports.remove(pid); port.a.deliver(m) }
        }
    }

    /** 分頁換頁或關閉：舊網頁的內容腳本、分頁裡的擴充頁面都不在了 */
    fun tabNavigated(tab: Tab) = gone(frames.filter { it.tab === tab } + pages.filter { it.tab === tab })

    /** 彈出視窗關閉 */
    fun pageClosed(view: WebView) = gone(pages.filter { it.view === view })

    // ---------- 儲存（chrome.storage：每個擴充、每個區域一個 JSON 檔；session 只在記憶體） ----------
    private val stores = HashMap<String, JSONObject>()

    private fun store(sid: String, area: String): JSONObject = stores.getOrPut("$sid/$area") {
        if (area == "session" || area == "managed") JSONObject()
        else runCatching { JSONObject(File(Extensions.dirOf(sid)!!, "storage_$area.json").readText()) }.getOrDefault(JSONObject())
    }

    private fun saveStore(sid: String, area: String) {
        if (area == "session" || area == "managed") return
        val text = store(sid, area).toString()
        val dir = Extensions.dirOf(sid) ?: return
        UserScripts.io.execute { runCatching { File(dir, "storage_$area.json").writeText(text) } }
    }

    private fun storage(sid: String, d: JSONObject): Any? {
        val area = d.optString("area", "local").let { if (it in setOf("local", "sync", "session", "managed")) it else "local" }
        val s = store(sid, area)
        val arg = d.opt("arg")
        val changes = JSONObject()
        when (d.optString("op")) {
            "get" -> {
                val out = JSONObject()
                when (arg) {
                    null, JSONObject.NULL -> s.keys().forEach { out.put(it, s.get(it)) }
                    is String -> if (s.has(arg)) out.put(arg, s.get(arg))
                    is JSONArray -> for (i in 0 until arg.length()) arg.optString(i).let { if (s.has(it)) out.put(it, s.get(it)) }
                    is JSONObject -> arg.keys().forEach { k -> out.put(k, if (s.has(k)) s.get(k) else arg.get(k)) }
                }
                return out
            }
            "set" -> {
                if (area == "managed") error("This is a read-only store.")
                val items = arg as? JSONObject ?: return null
                items.keys().forEach { k ->
                    val c = JSONObject().put("newValue", items.get(k))
                    if (s.has(k)) c.put("oldValue", s.get(k))
                    s.put(k, items.get(k))
                    changes.put(k, c)
                }
            }
            "remove" -> {
                val keys = when (arg) { is String -> listOf(arg); is JSONArray -> List(arg.length()) { arg.optString(it) }; else -> emptyList() }
                keys.forEach { k -> if (s.has(k)) { changes.put(k, JSONObject().put("oldValue", s.get(k))); s.remove(k) } }
            }
            "clear" -> s.keys().asSequence().toList().forEach { k -> changes.put(k, JSONObject().put("oldValue", s.get(k))); s.remove(k) }
        }
        if (changes.length() > 0) {
            saveStore(sid, area)
            val m = JSONObject().put("t", "schanged").put("area", area).put("changes", changes)
            (pages.filter { it.sid == sid } + frames.filter { it.sid == sid }).forEach { it.deliver(m) }
        }
        return null
    }

    // ---------- 其他 API（tabs、scripting、windows、notifications、downloads、cookies） ----------
    private fun tabInfo(t: Tab): JSONObject {
        val b = browser
        return JSONObject().put("id", t.id).put("index", b?.allTabs?.filter { !it.incognito }?.indexOf(t) ?: 0).put("windowId", 1)
            .put("active", b?.currentTab === t).put("highlighted", b?.currentTab === t).put("selected", b?.currentTab === t)
            .put("url", t.url).put("pendingUrl", t.url).put("title", t.title).put("status", if (t.progress < 100) "loading" else "complete")
            .put("incognito", false).put("pinned", false).put("audible", t.audible).put("discarded", false).put("autoDiscardable", true)
            .put("mutedInfo", JSONObject().put("muted", false)).put("groupId", -1).put("favIconUrl", "")
    }

    private fun visibleTabs() = browser?.allTabs?.filter { !it.incognito }.orEmpty()
    private fun tabById(id: Long) = visibleTabs().firstOrNull { it.id == id }
    private fun tabArg(from: End, v: Any?): Tab? = when (v) {
        is Number -> tabById(v.toLong())
        else -> from.tab ?: browser?.currentTab?.takeIf { !it.incognito }
    }

    private fun resolveUrl(sid: String, u: String): String = runCatching { java.net.URI(originOf(sid) + "/").resolve(u).toString() }.getOrDefault(u)

    private fun api(from: End, name: String, args: JSONArray, done: (Any?, String?) -> Unit) {
        val b = browser ?: return done(null, "unavailable")
        val ext = Extensions.bySid(from.sid) ?: return done(null, "extension removed")
        try {
            when (name) {
                "tabs.query" -> {
                    val q = args.optJSONObject(0) ?: JSONObject()
                    val urls = when (val u = q.opt("url")) { is String -> listOf(u); is JSONArray -> List(u.length()) { u.optString(it) }; else -> emptyList() }
                        .mapNotNull(Extensions::matchPattern)
                    val out = JSONArray()
                    visibleTabs().filter { t ->
                        (!q.has("active") || q.optBoolean("active") == (b.currentTab === t)) &&
                            (!q.has("highlighted") || q.optBoolean("highlighted") == (b.currentTab === t)) &&
                            (!q.has("audible") || q.optBoolean("audible") == t.audible) &&
                            (!q.has("status") || q.optString("status") == (if (t.progress < 100) "loading" else "complete")) &&
                            (urls.isEmpty() || urls.any { it.containsMatchIn(t.url) })
                    }.forEach { out.put(tabInfo(it)) }
                    done(out, null)
                }
                "tabs.get" -> tabById(args.optLong(0, -1))?.let { done(tabInfo(it), null) } ?: done(null, "No tab with id: ${args.opt(0)}.")
                "tabs.getCurrent" -> done(from.tab?.let(::tabInfo), null)
                "tabs.create", "windows.create" -> {
                    val o = args.optJSONObject(0) ?: JSONObject()
                    val u = when (val x = o.opt("url")) { is JSONArray -> x.optString(0); is String -> x; else -> "" }
                    val t = b.openTab(if (u.isEmpty()) "about:blank" else resolveUrl(from.sid, u), o.optBoolean("active", true))
                    done(if (name == "windows.create") JSONObject().put("id", 1).put("focused", true).put("type", "normal").put("tabs", JSONArray().put(tabInfo(t))) else tabInfo(t), null)
                }
                "tabs.update" -> {
                    val (t, o) = if (args.opt(0) is JSONObject) tabArg(from, null) to args.optJSONObject(0) else tabArg(from, args.opt(0)) to (args.optJSONObject(1) ?: JSONObject())
                    if (t == null) return done(null, "No tab")
                    o.optString("url").takeIf { it.isNotEmpty() }?.let { t.load(resolveUrl(from.sid, it)) }
                    if (o.optBoolean("active") || o.optBoolean("highlighted")) b.selectTab(t)
                    done(tabInfo(t), null)
                }
                "tabs.remove" -> {
                    val ids = when (val x = args.opt(0)) { is JSONArray -> List(x.length()) { x.optLong(it) }; is Number -> listOf(x.toLong()); else -> emptyList() }
                    ids.mapNotNull(::tabById).forEach(b::closeTab)
                    done(null, null)
                }
                "tabs.reload" -> { tabArg(from, args.opt(0))?.webView?.reload(); done(null, null) }
                "tabs.duplicate" -> tabArg(from, args.opt(0))?.let { done(tabInfo(b.openTab(it.url, true)), null) } ?: done(null, "No tab")
                "tabs.executeScript", "tabs.insertCSS" -> {
                    val (t, o) = if (args.opt(0) is JSONObject) tabArg(from, null) to args.optJSONObject(0) else tabArg(from, args.opt(0)) to (args.optJSONObject(1) ?: JSONObject())
                    if (t == null) return done(null, "No tab")
                    val code = o.optString("code").ifEmpty { o.optString("file").takeIf { it.isNotEmpty() }?.let { readFile(from.sid, it) }.orEmpty() }
                    if (name == "tabs.insertCSS") { t.webView.evaluateJavascript(cssJs(code), null); return done(null, null) }
                    t.webView.evaluateJavascript(Extensions.injectionScript(ext, code, false)) { r -> done(JSONArray().put(parse(r)), null) }
                }
                "scripting.executeScript" -> {
                    val o = args.optJSONObject(0) ?: JSONObject()
                    val t = tabArg(from, o.optJSONObject("target")?.opt("tabId")) ?: return done(null, "No tab")
                    val code = o.optString("code").ifEmpty {
                        o.optJSONArray("files")?.let { f -> List(f.length()) { readFile(from.sid, f.optString(it)) }.joinToString("\n;\n") }.orEmpty()
                    }
                    t.webView.evaluateJavascript(Extensions.injectionScript(ext, code, o.optString("world") == "MAIN")) { r ->
                        done(JSONArray().put(JSONObject().put("frameId", 0).put("documentId", "").put("result", parse(r))), null)
                    }
                }
                "scripting.insertCSS" -> {
                    val o = args.optJSONObject(0) ?: JSONObject()
                    val t = tabArg(from, o.optJSONObject("target")?.opt("tabId")) ?: return done(null, "No tab")
                    val css = o.optString("css").ifEmpty { o.optJSONArray("files")?.let { f -> List(f.length()) { readFile(from.sid, f.optString(it)) }.joinToString("\n") }.orEmpty() }
                    t.webView.evaluateJavascript(cssJs(css), null)
                    done(null, null)
                }
                "scripting.registerContentScripts", "scripting.updateContentScripts" -> {
                    val cur = Extensions.dynamicScripts(from.sid)
                    val add = args.optJSONArray(0) ?: JSONArray()
                    val byId = LinkedHashMap<String, JSONObject>()
                    for (i in 0 until cur.length()) cur.optJSONObject(i)?.let { byId[it.optString("id")] = it }
                    for (i in 0 until add.length()) add.optJSONObject(i)?.let { n ->
                        val id = n.optString("id")
                        if (name == "scripting.updateContentScripts") byId[id]?.let { old -> n.keys().forEach { k -> old.put(k, n.get(k)) } }
                        else byId[id] = n
                    }
                    Extensions.setDynamicScripts(from.sid, JSONArray(byId.values))
                    done(null, null)
                }
                "scripting.unregisterContentScripts" -> {
                    val ids = args.optJSONObject(0)?.optJSONArray("ids")?.let { a -> List(a.length()) { a.optString(it) }.toSet() }
                    val cur = Extensions.dynamicScripts(from.sid)
                    val keep = JSONArray()
                    if (ids != null) for (i in 0 until cur.length()) cur.optJSONObject(i)?.takeIf { it.optString("id") !in ids }?.let(keep::put)
                    Extensions.setDynamicScripts(from.sid, keep)
                    done(null, null)
                }
                "scripting.getRegisteredContentScripts" -> {
                    val ids = args.optJSONObject(0)?.optJSONArray("ids")?.let { a -> List(a.length()) { a.optString(it) }.toSet() }
                    val cur = Extensions.dynamicScripts(from.sid)
                    val out = JSONArray()
                    for (i in 0 until cur.length()) cur.optJSONObject(i)?.takeIf { ids == null || it.optString("id") in ids }?.let(out::put)
                    done(out, null)
                }
                "runtime.openOptionsPage" -> {
                    ext.options?.let { b.openTab(originOf(from.sid) + "/" + it.trimStart('/'), true) }
                    done(null, null)
                }
                "notifications.create" -> {
                    val o = args.optJSONObject(1) ?: JSONObject()
                    b.toast(listOf(o.optString("title"), o.optString("message")).filter { it.isNotBlank() }.joinToString("：").ifEmpty { ext.name })
                    done(args.optString(0), null)
                }
                "downloads.download" -> {
                    val o = args.optJSONObject(0) ?: JSONObject()
                    o.optString("url").takeIf { it.startsWith("http") }?.let { b.download(b.currentTab, it, o.optString("filename").substringAfterLast('/')) }
                    done(1, null)
                }
                "cookies.getAll", "cookies.get" -> {
                    val o = args.optJSONObject(0) ?: JSONObject()
                    val u = o.optString("url").ifEmpty { o.optString("domain").takeIf { it.isNotEmpty() }?.let { "https://" + it.trimStart('.') + "/" }.orEmpty() }
                    val host = Uri.parse(u).host.orEmpty()
                    val list = CookieManager.getInstance().getCookie(u).orEmpty().split(';').mapNotNull { p ->
                        val i = p.indexOf('='); if (i <= 0) null else p.substring(0, i).trim() to p.substring(i + 1).trim()
                    }.filter { !o.has("name") || it.first == o.optString("name") }.map { (k, v) ->
                        JSONObject().put("name", k).put("value", v).put("domain", host).put("hostOnly", true).put("path", "/")
                            .put("secure", u.startsWith("https")).put("httpOnly", false).put("session", true).put("storeId", "0").put("sameSite", "unspecified")
                    }
                    done(if (name == "cookies.get") list.firstOrNull() else JSONArray(list), null)
                }
                "cookies.set", "cookies.remove" -> {
                    val o = args.optJSONObject(0) ?: JSONObject()
                    val u = o.optString("url")
                    val cookie = if (name == "cookies.remove") "${o.optString("name")}=; Max-Age=0; Path=/"
                    else buildString {
                        append(o.optString("name")).append('=').append(o.optString("value"))
                        append("; Path=").append(o.optString("path", "/"))
                        if (o.has("domain")) append("; Domain=").append(o.optString("domain"))
                        if (o.has("expirationDate")) append("; Max-Age=").append(((o.optDouble("expirationDate") * 1000 - System.currentTimeMillis()) / 1000).toLong().coerceAtLeast(0))
                        if (o.optBoolean("secure")) append("; Secure")
                    }
                    CookieManager.getInstance().setCookie(u, cookie)
                    done(if (name == "cookies.set") o else JSONObject().put("url", u).put("name", o.optString("name")), null)
                }
                else -> done(null, null)
            }
        } catch (e: Exception) {
            done(null, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun readFile(sid: String, path: String): String {
        val dir = Extensions.dirOf(sid) ?: return ""
        val f = File(dir, path.removePrefix("./").trimStart('/'))
        return if (f.canonicalPath.startsWith(dir.canonicalPath + File.separator) && f.isFile) f.readText() else ""
    }

    private fun cssJs(css: String) =
        "(function(){var s=document.createElement('style');s.textContent=${JSONObject.quote(css)};(document.head||document.documentElement).appendChild(s)})()"

    private fun parse(r: String?): Any? = runCatching { JSONTokener(r ?: "null").nextValue() }.getOrNull()?.takeIf { it != JSONObject.NULL }

    // ---------- 分頁事件（tabs.onUpdated 等，送給所有擴充頁面） ----------
    private fun tabEvent(name: String, args: JSONArray) {
        if (pages.isEmpty()) return
        val m = JSONObject().put("t", "ev").put("name", name).put("args", args)
        pages.forEach { it.deliver(m) }
    }

    fun tabLoading(tab: Tab, url: String) {
        if (tab.incognito || pages.isEmpty()) return
        val info = tabInfo(tab).put("status", "loading").put("url", url)
        tabEvent("webNavigation.onBeforeNavigate", JSONArray().put(JSONObject().put("tabId", tab.id).put("url", url).put("frameId", 0).put("parentFrameId", -1).put("timeStamp", System.currentTimeMillis())))
        tabEvent("tabs.onUpdated", JSONArray().put(tab.id).put(JSONObject().put("status", "loading").put("url", url)).put(info))
        tabEvent("webNavigation.onCommitted", JSONArray().put(JSONObject().put("tabId", tab.id).put("url", url).put("frameId", 0).put("parentFrameId", -1).put("transitionType", "link").put("transitionQualifiers", JSONArray()).put("timeStamp", System.currentTimeMillis())))
    }

    fun tabComplete(tab: Tab) {
        if (tab.incognito || pages.isEmpty()) return
        val info = tabInfo(tab).put("status", "complete")
        tabEvent("tabs.onUpdated", JSONArray().put(tab.id).put(JSONObject().put("status", "complete").put("title", tab.title)).put(info))
        tabEvent("webNavigation.onCompleted", JSONArray().put(JSONObject().put("tabId", tab.id).put("url", tab.url).put("frameId", 0).put("parentFrameId", -1).put("timeStamp", System.currentTimeMillis())))
    }

    fun tabCreated(tab: Tab) = tabEvent("tabs.onCreated", JSONArray().put(tabInfo(tab)))

    fun tabActivated(tab: Tab) {
        if (tab.incognito) return
        tabEvent("tabs.onActivated", JSONArray().put(JSONObject().put("tabId", tab.id).put("windowId", 1)))
    }

    fun tabRemoved(tab: Tab) {
        tabNavigated(tab)
        if (tab.incognito) return
        tabEvent("tabs.onRemoved", JSONArray().put(tab.id).put(JSONObject().put("windowId", 1).put("isWindowClosing", false)))
    }

    /** 沒有彈出視窗的擴充：在「擴展工具管理」點它＝按工具列按鈕（action.onClicked） */
    fun clickAction(ext: Extensions.Ext) {
        val t = browser?.currentTab?.takeIf { !it.incognito } ?: return
        val m = JSONObject().put("t", "ev").put("args", JSONArray().put(tabInfo(t)))
        pages.filter { it.sid == ext.sid }.forEach { p ->
            listOf("action.onClicked", "browserAction.onClicked", "pageAction.onClicked").forEach { n -> p.deliver(JSONObject(m.toString()).put("name", n)) }
        }
    }

    // ---------- 背景程式 ----------
    private val backgrounds = HashMap<String, WebView>()
    private val bgStamp = HashMap<String, Long>()
    private val started = HashSet<String>()

    /** 依目前安裝、啟用的擴充啟動或停止背景程式（主執行緒；安裝、更新、開關、移除後呼叫） */
    fun sync() {
        if (!::app.isInitialized) return
        val want = Extensions.list.filter { it.enabled && it.works && it.background }.associateBy { it.sid }
        backgrounds.keys.toList().forEach { sid -> if (want[sid]?.stamp != bgStamp[sid]) stop(sid) }
        want.values.forEach { if (it.sid !in backgrounds) start(it) }
        // 移除的擴充：記憶體裡的儲存也丟掉（重裝是全新的）
        val installed = Extensions.list.map { it.sid }.toSet()
        stores.keys.toList().forEach { k -> if (k.substringBefore('/') !in installed) stores.remove(k) }
    }

    private fun start(ext: Extensions.Ext) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val w = WebView(app)
        setupPageWebView(w, null, background = true)
        backgrounds[ext.sid] = w
        bgStamp[ext.sid] = ext.stamp
        val page = ext.manifest.optJSONObject("background")?.optString("page")?.takeIf { it.isNotBlank() }
        w.loadUrl(originOf(ext.sid) + (page?.let { "/" + it.trimStart('/') } ?: BG_PAGE))
    }

    private fun stop(sid: String) {
        val w = backgrounds.remove(sid) ?: return
        readyBg.remove(sid)
        bgStamp.remove(sid)
        pageClosed(w)
        runCatching { w.destroy() }
    }

    private fun backgroundReady(from: End) {
        if (from !is PageEnd || backgrounds[from.sid] !== from.view) return
        readyBg.add(from.sid)
        queued.remove(from.sid)?.forEach { (f, d) -> if (f is PageEnd || frames.contains(f)) handle(f, d) }
        val ext = Extensions.bySid(from.sid) ?: return
        val m = JSONObject().put("t", "ev")
        if (ext.fresh.isNotEmpty()) {
            from.deliver(JSONObject(m.toString()).put("name", "runtime.onInstalled").put("args", JSONArray().put(JSONObject().put("reason", ext.fresh))))
            Extensions.clearFresh(ext.sid)
        } else if (started.add(ext.sid)) {
            from.deliver(JSONObject(m.toString()).put("name", "runtime.onStartup").put("args", JSONArray()))
        }
        started.add(ext.sid)
    }

    /** 背景程式的頁面：依 manifest 載入 service worker（可能是 ES 模組）或 MV2 的背景腳本 */
    private fun backgroundHtml(ext: Extensions.Ext): String {
        val bg = ext.manifest.optJSONObject("background") ?: JSONObject()
        val sw = bg.optString("service_worker").takeIf { it.isNotBlank() }
        val module = bg.optString("type") == "module"
        val scripts = if (sw != null) listOf(sw) else bg.optJSONArray("scripts")?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty()
        return "<!doctype html><meta charset=utf-8><title>${ext.name.replace("<", "")}</title>" +
            scripts.joinToString("") { s -> "<script" + (if (module) " type=module" else "") + " src=\"/" + s.removePrefix("./").trimStart('/').replace("\"", "%22") + "\"></script>" }
    }

    /** 擴充來源的檔案（任何 WebView 的 shouldInterceptRequest 都先問這裡） */
    fun serve(url: Uri): WebResourceResponse? {
        val sid = sidOfHost(url.host) ?: return null
        val ext = Extensions.bySid(sid)
        val dir = Extensions.dirOf(sid)
        if (ext == null || dir == null) return notFound()
        val path = Uri.decode(url.path.orEmpty()).ifEmpty { "/" }
        if (path == BG_PAGE) return WebResourceResponse("text/html", "utf-8", backgroundHtml(ext).byteInputStream())
        val f = File(dir, path.trimStart('/').ifEmpty { "index.html" })
        if (!f.canonicalPath.startsWith(dir.canonicalPath + File.separator) || !f.isFile || f.name.startsWith("storage_") || f.name == "orea.json") return notFound()
        val ext2 = f.extension.lowercase()
        val mime = when (ext2) {
            "js", "mjs" -> "text/javascript"; "json" -> "application/json"; "css" -> "text/css"; "html", "htm" -> "text/html"
            "svg" -> "image/svg+xml"; "wasm" -> "application/wasm"
            else -> android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext2) ?: "application/octet-stream"
        }
        return WebResourceResponse(mime, "utf-8", 200, "OK", mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-cache"), f.inputStream())
    }

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), "".byteInputStream())

    /**
     * 擴充頁面用的 WebView 設定（背景程式、彈出視窗）：讀擴充資料夾的檔案、注入 chrome.*。
     * onClose：彈出視窗呼叫 window.close() 或點了要開新分頁的連結時關閉
     */
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    fun setupPageWebView(w: WebView, tab: Tab?, background: Boolean = false, onClose: (() -> Unit)? = null) {
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.addJavascriptInterface(PageBridge({ w }, tab), "__oreaXP")
        addPageShim(w)
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = serve(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (sidOfHost(request.url.host) != null) return false
                if (background) return true
                // 彈出視窗裡的一般連結：開成新分頁（同 Chrome 的彈出視窗）
                if (request.url.scheme == "http" || request.url.scheme == "https") browser?.openTab(request.url.toString(), true)
                onClose?.invoke()
                return true
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) android.util.Log.w("OreaExt", "${m.message()} @${m.sourceId()}:${m.lineNumber()}")
                return true
            }

            override fun onCloseWindow(window: WebView) { onClose?.invoke() }
        }
    }

    /** 分頁也能開擴充頁面（選項頁）：在分頁的 WebView 加上擴充頁面的 chrome.*（只對擴充來源生效） */
    fun addPageShim(w: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) && ::pageShim.isInitialized)
            WebViewCompat.addDocumentStartJavaScript(w, pageShim, setOf("https://*$HOST_SUFFIX"))
    }

    fun popupUrl(ext: Extensions.Ext): String? = ext.popup?.let { originOf(ext.sid) + "/" + it.removePrefix("./").trimStart('/') }
    fun optionsUrl(ext: Extensions.Ext): String? = ext.options?.let { originOf(ext.sid) + "/" + it.removePrefix("./").trimStart('/') }

    /**
     * 擴充頁面開頁時執行（接在 ext_api.js 後面）：建立 chrome.*、把其他網站的 fetch 交給 App（擴充有網站權限，不受 CORS 限制，同 Chrome）、
     * 補上 service worker 才有的全域（importScripts 等），載入完通知 App（背景程式要觸發 runtime.onInstalled／onStartup）
     */
    private const val PAGE_PRELUDE = """
;(function(){
if(window.__oreaExtReady)return;var N=window.__oreaXP;if(!N)return;var T=TOKEN,cfg;
try{cfg=JSON.parse(N.cfg(T,location.host))}catch(e){return}if(!cfg)return;
Object.defineProperty(window,'__oreaExtReady',{value:1});
var h=null,q=[];
Object.defineProperty(window,'__oreaRecv',{value:function(m){h?h(m):q.push(m)}});
var c=__oreaMkChrome(cfg,function(d){N.post(T,cfg.sid,JSON.stringify(d))},function(f){h=f;q.splice(0).forEach(f)});
try{Object.defineProperty(window,'chrome',{value:c,writable:true,configurable:true})}catch(e){window.chrome=c}
if(cfg.firefox)window.browser=c;
function b64(a){var u=new Uint8Array(a),s='';for(var i=0;i<u.length;i+=32768)s+=String.fromCharCode.apply(null,u.subarray(i,i+32768));return btoa(s)}
var nf=window.fetch.bind(window);
window.fetch=function(input,init){
var req;try{req=new Request(input,init)}catch(e){return nf(input,init)}
var u=new URL(req.url,location.href);
if(u.origin===location.origin||!/^https?:$/.test(u.protocol))return nf(input,init);
var hd={};req.headers.forEach(function(v,k){hd[k]=v});
var body=(req.method==='GET'||req.method==='HEAD')?Promise.resolve(null):req.arrayBuffer();
return body.then(function(b){
var r={method:req.method,url:u.href,headers:hd,responseType:'arraybuffer',anonymous:req.credentials==='omit'};
if(b&&b.byteLength)r.dataB64=b64(b);
return c.__call({t:'fetch',req:r})}).then(function(r){
if(!r||r.type!=='load')throw new TypeError('Failed to fetch');
var bin=atob(r.bodyB64||''),a=new Uint8Array(bin.length);for(var i=0;i<bin.length;i++)a[i]=bin.charCodeAt(i);
var h2=new Headers();(r.responseHeaders||'').split('\r\n').forEach(function(l){var i=l.indexOf(':');if(i>0)try{h2.append(l.slice(0,i).trim(),l.slice(i+1).trim())}catch(e){}});
var st=r.status>=200&&r.status<=599?r.status:200,nb=st===204||st===205||st===304||req.method==='HEAD';
var res=new Response(nb?null:a,{status:st,statusText:r.statusText||'',headers:h2});
try{Object.defineProperty(res,'url',{value:r.finalUrl||u.href})}catch(e){}
return res})};
if(typeof window.importScripts!=='function')window.importScripts=function(){for(var i=0;i<arguments.length;i++){var x=new XMLHttpRequest();x.open('GET',new URL(arguments[i],location.href).href,false);x.send();(0,eval)(x.responseText+'\n//# sourceURL='+arguments[i])}};
if(!window.clients)window.clients={matchAll:function(){return Promise.resolve([])},claim:function(){return Promise.resolve()},get:function(){return Promise.resolve(undefined)},openWindow:function(u){return c.tabs.create({url:u})}};
if(!window.registration)window.registration={scope:location.origin+'/',showNotification:function(t,o){return c.notifications.create({title:t,message:(o&&o.body)||''})},getNotifications:function(){return Promise.resolve([])}};
if(!window.skipWaiting)window.skipWaiting=function(){return Promise.resolve()};
addEventListener('load',function(){N.post(T,cfg.sid,JSON.stringify({t:'ready'}))});
})();"""
}
