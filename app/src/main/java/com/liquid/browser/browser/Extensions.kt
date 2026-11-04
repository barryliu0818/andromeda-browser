package com.liquid.browser.browser

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * 擴充功能（參考 Firefox 的 WebExtensions）：
 *  - Firefox（.xpi）、Chrome（.crx）擴充的 content_scripts（JS＋CSS）：去廣告、深色模式、網頁增強這類大多只靠這個
 *  - 使用者腳本（.user.js，Tampermonkey／Greasemonkey 格式）
 * 背景頁、工具列彈出視窗、chrome.tabs 等需要瀏覽器外殼的 API 不支援；內容腳本拿到的是精簡版 chrome.*（runtime、storage、i18n）。
 * 所有已啟用的腳本組成一段「開頁腳本」，每個頁面（含 iframe）開始載入時執行，依網址比對決定跑哪些。
 */
object Extensions {
    class Script(val matches: List<Regex>, val excludes: List<Regex>, val js: String, val css: String, val runAt: String, val allFrames: Boolean)

    class Ext(
        val id: String, val name: String, val version: String, val description: String,
        val userScript: Boolean, val source: String, val scripts: List<Script>,
        val messages: JSONObject, val manifest: JSONObject, val enabled: Boolean,
    )

    /** 已安裝的擴充（設定頁顯示） */
    val list = mutableStateListOf<Ext>()

    /** 開頁腳本改變（安裝、移除、開關）時通知分頁重新註冊 */
    var onChanged: (() -> Unit)? = null

    /**
     * getURL() 給出的擴充資源網址：網頁自己網站下的這個路徑，由 [resource] 從擴充資料夾回應。
     * 不用另外的網域：網站的 CSP 多半只允許自己的網域（'self'），另外的網域會被擋（維基百科就是）
     */
    const val RES_PATH = "/__orea_ext__/"
    private const val MAX_BYTES = 30 * 1024 * 1024
    private lateinit var root: File

    fun init(ctx: Context) {
        root = File(ctx.filesDir, "extensions").apply { mkdirs() }
        list.clear()
        root.listFiles()?.sortedBy { it.lastModified() }?.forEach { dir -> runCatching { load(dir) }.getOrNull()?.let(list::add) }
    }

    // ---------- 安裝 ----------

    /** 商店網頁或檔案網址 → 實際的下載網址；不是擴充就回傳 null */
    fun packageUrl(url: String): String? {
        val u = Uri.parse(url)
        val host = u.host.orEmpty()
        val path = u.path.orEmpty()
        return when {
            path.endsWith(".user.js") || path.endsWith(".xpi") || path.endsWith(".crx") -> url
            host == "addons.mozilla.org" -> Regex("/addon/([^/]+)").find(path)?.let { "amo:" + it.groupValues[1] }
            host == "chromewebstore.google.com" || (host == "chrome.google.com" && path.startsWith("/webstore")) ->
                Regex("/([a-p]{32})(/|$)").find(path)?.let {
                    "https://clients2.google.com/service/update2/crx?response=redirect&prodversion=159.0&acceptformat=crx2,crx3&x=id%3D${it.groupValues[1]}%26uc"
                }
            host.endsWith("greasyfork.org") || host.endsWith("sleazyfork.org") ->
                Regex("/scripts/(\\d+)").find(path)?.let { "https://$host/scripts/${it.groupValues[1]}/code/script.user.js" }
            else -> null
        }
    }

    /** 下載並安裝（背景執行緒）。失敗丟出例外，訊息可直接顯示給使用者 */
    fun install(url: String): Ext {
        // 認不出來的網址（例如下載轉址）當成套件直接下載，格式不對時 installBytes 會說明
        var src = packageUrl(url) ?: url
        if (src.startsWith("amo:")) {
            // Firefox 附加元件商店：用公開 API 取得目前版本的 .xpi
            val json = JSONObject(String(download("https://addons.mozilla.org/api/v5/addons/addon/${src.removePrefix("amo:")}/")))
            src = json.getJSONObject("current_version").getJSONObject("file").getString("url")
        }
        return installBytes(download(src), url)
    }

    fun installBytes(bytes: ByteArray, source: String): Ext {
        val text = if (bytes.size < 4 || bytes[0] == 'P'.code.toByte() || String(bytes, 0, 4) == "Cr24") null else String(bytes)
        val dir = if (text != null && text.contains("==UserScript==")) installUserScript(text, source) else installPackage(bytes, source)
        val ext = load(dir)
        list.removeAll { it.id == ext.id }
        list.add(ext)
        onChanged?.invoke()
        return ext
    }

    private fun installPackage(bytes: ByteArray, source: String): File {
        // .crx 是檔頭＋zip：跳到第一個 zip 本地檔頭
        val start = (0..bytes.size - 4).firstOrNull { bytes[it] == 0x50.toByte() && bytes[it + 1] == 0x4B.toByte() && bytes[it + 2] == 3.toByte() && bytes[it + 3] == 4.toByte() }
            ?: error("檔案格式不對")
        val tmp = File(root, ".tmp").apply { deleteRecursively(); mkdirs() }
        ZipInputStream(ByteArrayInputStream(bytes, start, bytes.size - start)).use { zip ->
            val base = tmp.canonicalPath + File.separator
            while (true) {
                val e = zip.nextEntry ?: break
                // Windows 壓的 zip 可能用「\」分隔路徑
                val out = File(tmp, e.name.replace('\\', '/'))
                // zip slip：路徑跑出資料夾的項目不解
                if (!out.canonicalPath.startsWith(base)) continue
                if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { zip.copyTo(it) } }
            }
        }
        val manifest = runCatching { JSONObject(File(tmp, "manifest.json").readText()) }.getOrNull() ?: error("找不到 manifest.json")
        if ((manifest.optJSONArray("content_scripts")?.length() ?: 0) == 0)
            error("這個擴充功能需要工具列按鈕或背景程式，Orea 目前只支援會修改網頁的擴充功能")
        val gecko = (manifest.optJSONObject("browser_specific_settings") ?: manifest.optJSONObject("applications"))?.optJSONObject("gecko")?.optString("id")
        val id = gecko?.takeIf { it.isNotBlank() } ?: manifest.optString("name") + "@" + source.substringBefore('?')
        File(tmp, META).writeText(JSONObject().put("id", id).put("source", source).put("enabled", true).toString())
        return moveInto(tmp, id)
    }

    private fun installUserScript(text: String, source: String): File {
        val meta = userScriptMeta(text)
        val name = meta["name"]?.firstOrNull() ?: error("使用者腳本缺少 @name")
        if (meta["match"].isNullOrEmpty() && meta["include"].isNullOrEmpty()) error("使用者腳本沒有指定適用的網站")
        val tmp = File(root, ".tmp").apply { deleteRecursively(); mkdirs() }
        // @require 的程式庫（jQuery 等）安裝時一起下載，接在腳本前面
        val required = meta["require"].orEmpty().joinToString("\n;\n") { String(download(it)) }
        File(tmp, "script.user.js").writeText(if (required.isEmpty()) text else "$required\n;\n$text")
        val id = (meta["namespace"]?.firstOrNull() ?: source) + "/" + name
        File(tmp, META).writeText(JSONObject().put("id", id).put("source", source).put("enabled", true).put("userScript", true).toString())
        return moveInto(tmp, id)
    }

    private fun moveInto(tmp: File, id: String): File {
        val dir = File(root, dirName(id))
        dir.deleteRecursively()
        if (!tmp.renameTo(dir)) error("無法寫入擴充功能資料夾")
        return dir
    }

    private fun dirName(id: String) = java.security.MessageDigest.getInstance("SHA-1").digest(id.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    private fun download(url: String): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true }
        try {
            if (conn.responseCode !in 200..299) error("下載失敗（HTTP ${conn.responseCode}）")
            val out = java.io.ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) error("檔案太大")
                }
            }
            return out.toByteArray()
        } finally { conn.disconnect() }
    }

    fun setEnabled(ext: Ext, on: Boolean) {
        val dir = File(root, dirName(ext.id))
        val meta = JSONObject(File(dir, META).readText()).put("enabled", on)
        File(dir, META).writeText(meta.toString())
        val i = list.indexOfFirst { it.id == ext.id }
        if (i >= 0) list[i] = load(dir)
        onChanged?.invoke()
    }

    fun remove(ext: Ext) {
        File(root, dirName(ext.id)).deleteRecursively()
        list.removeAll { it.id == ext.id }
        onChanged?.invoke()
    }

    // ---------- 讀取 ----------

    private const val META = "orea.json"

    private fun load(dir: File): Ext {
        val meta = JSONObject(File(dir, META).readText())
        val id = meta.getString("id")
        val enabled = meta.optBoolean("enabled", true)
        if (meta.optBoolean("userScript")) {
            val text = File(dir, "script.user.js").readText()
            val m = userScriptMeta(text)
            val matches = m["match"].orEmpty().mapNotNull(::matchPattern) + m["include"].orEmpty().mapNotNull(::globOrRegex)
            val excludes = m["exclude"].orEmpty().mapNotNull(::globOrRegex) + m["exclude-match"].orEmpty().mapNotNull(::matchPattern)
            val runAt = when (m["run-at"]?.firstOrNull()) { "document-start" -> "document_start"; "document-idle" -> "document_idle"; else -> "document_end" }
            return Ext(
                id, m["name"]?.firstOrNull().orEmpty(), m["version"]?.firstOrNull().orEmpty(), m["description"]?.firstOrNull().orEmpty(),
                true, meta.optString("source"), listOf(Script(matches, excludes, text, "", runAt, m.containsKey("noframes").not())),
                JSONObject(), JSONObject().put("name", m["name"]?.firstOrNull()).put("version", m["version"]?.firstOrNull()), enabled,
            )
        }
        val manifest = JSONObject(File(dir, "manifest.json").readText())
        val messages = localeMessages(dir, manifest.optString("default_locale"))
        fun msg(s: String) = Regex("__MSG_(\\w+)__").replace(s) { r -> messages.optJSONObject(r.groupValues[1])?.optString("message") ?: messages.keys().asSequence().firstOrNull { it.equals(r.groupValues[1], true) }?.let { messages.getJSONObject(it).optString("message") } ?: r.value }
        val scripts = manifest.optJSONArray("content_scripts").objects().map { cs ->
            Script(
                cs.optJSONArray("matches").strings().mapNotNull(::matchPattern),
                cs.optJSONArray("exclude_matches").strings().mapNotNull(::matchPattern) + cs.optJSONArray("exclude_globs").strings().mapNotNull(::globOrRegex),
                cs.optJSONArray("js").strings().joinToString("\n;\n") { File(dir, it.trimStart('/')).takeIf(File::exists)?.readText().orEmpty() },
                cs.optJSONArray("css").strings().joinToString("\n") { File(dir, it.trimStart('/')).takeIf(File::exists)?.readText().orEmpty() },
                cs.optString("run_at", "document_idle"), cs.optBoolean("all_frames"),
            )
        }
        return Ext(id, msg(manifest.optString("name")), manifest.optString("version"), msg(manifest.optString("description")), false, meta.optString("source"), scripts, messages, manifest, enabled)
    }

    /** _locales：依系統語言挑（zh_TW → zh → 預設語言 → en） */
    private fun localeMessages(dir: File, default: String): JSONObject {
        val loc = java.util.Locale.getDefault()
        val tries = listOf("${loc.language}_${loc.country}", loc.language, default, "en")
        for (l in tries) {
            val f = File(dir, "_locales/$l/messages.json")
            if (f.exists()) return runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
        }
        return JSONObject()
    }

    /** 使用者腳本的 ==UserScript== 檔頭：@key → 值（同一個 key 可以有多行） */
    fun userScriptMeta(text: String): Map<String, List<String>> {
        val block = text.substringAfter("==UserScript==", "").substringBefore("==/UserScript==")
        val out = linkedMapOf<String, MutableList<String>>()
        Regex("^\\s*//\\s*@([\\w:-]+)\\s*(.*?)\\s*$", RegexOption.MULTILINE).findAll(block).forEach { m ->
            // 本地化名稱（@name:zh-TW）只在沒有預設值時採用
            val key = m.groupValues[1].substringBefore(':')
            if (m.groupValues[1].contains(':') && out.containsKey(key)) return@forEach
            out.getOrPut(key) { mutableListOf() }.add(m.groupValues[2])
        }
        return out
    }

    /** Chrome／Firefox 的比對樣式（match pattern）→ 正規表示式 */
    fun matchPattern(p: String): Regex? {
        if (p == "<all_urls>") return Regex("^(https?|file|ftp)://.*")
        val m = Regex("^(\\*|https?|file|ftp|wss?)://([^/]*)(/.*)$").matchEntire(p.trim()) ?: return null
        val scheme = if (m.groupValues[1] == "*") "https?" else Regex.escape(m.groupValues[1])
        val h = m.groupValues[2]
        val host = when {
            h == "*" -> "[^/]*"
            h.startsWith("*.") -> "([^/]*\\.)?" + Regex.escape(h.drop(2))
            else -> Regex.escape(h)
        }
        val path = m.groupValues[3].split("*").joinToString(".*") { if (it.isEmpty()) "" else Regex.escape(it) }
        return Regex("^$scheme://$host(:\\d+)?$path$")
    }

    /** 使用者腳本的 @include／@exclude：/正規表示式/ 或萬用字元（* 任意字元） */
    fun globOrRegex(p: String): Regex? {
        val s = p.trim()
        if (s.length > 2 && s.startsWith("/") && s.endsWith("/")) return runCatching { Regex(s.substring(1, s.length - 1)) }.getOrNull()
        if (s == "*") return Regex(".*")
        return Regex("^" + s.split("*").joinToString(".*") { if (it.isEmpty()) "" else Regex.escape(it) } + "$")
    }

    // ---------- 注入 ----------

    /**
     * 開頁腳本：每個頁面（含 iframe）載入時執行，依網址比對跑對應的擴充；沒有啟用的擴充回傳 null。
     * ponytail: 所有擴充的程式碼都帶進每個頁面再比對網址，擴充很多（數 MB）時每頁多一點解析成本；要更省再改成依網域分組註冊
     */
    fun documentStartScript(): String? {
        val active = list.filter { it.enabled && it.scripts.isNotEmpty() }
        if (active.isEmpty()) return null
        return buildString {
            append("(function(){if(window.__oreaExt)return;window.__oreaExt=1;var u=location.href,top=window.top===window;")
            append("function when(r,f){if(r==='document_start')return f();var go=function(){r==='document_idle'?setTimeout(f,0):f()};")
            append("document.readyState==='loading'?document.addEventListener('DOMContentLoaded',go,{once:true}):go()}")
            append("function css(t){var s=document.createElement('style');s.textContent=t;var p=document.head||document.documentElement;p?p.appendChild(s):document.addEventListener('DOMContentLoaded',function(){document.head.appendChild(s)})}")
            for (ext in active) {
                ext.scripts.forEach { s ->
                    if (s.matches.isEmpty()) return@forEach
                    val inc = s.matches.joinToString(",") { "/" + jsRegex(it) + "/" }
                    val exc = s.excludes.joinToString(",") { "/" + jsRegex(it) + "/" }
                    append("\nif((top||").append(s.allFrames).append(")&&[").append(inc).append("].some(function(r){return r.test(u)})&&![").append(exc).append("].some(function(r){return r.test(u)})){")
                    if (s.css.isNotBlank()) append("when('document_start',function(){css(").append(JSONObject.quote(s.css)).append(")});")
                    if (s.js.isNotBlank()) {
                        append("when(").append(JSONObject.quote(s.runAt)).append(",function(){try{")
                        if (ext.userScript) append("(function(GM_info,GM,GM_addStyle,GM_getValue,GM_setValue,GM_deleteValue,GM_listValues,GM_xmlhttpRequest,GM_openInTab,GM_setClipboard,GM_registerMenuCommand,GM_notification,unsafeWindow){")
                            .append(s.js).append("\n}).apply(window,").append(gmShim(ext)).append(");")
                        else append("(function(chrome,browser){").append(s.js).append("\n}).apply(window,(function(){var c=").append(chromeShim(ext)).append(";return[c,c]})());")
                        append("}catch(e){console.error('[Orea 擴充] ").append(ext.name.replace("'", "")).append("',e)}});")
                    }
                    append("}")
                }
            }
            append("})();")
        }
    }

    // Kotlin（Java）的 \Q…\E 轉成 JS 也懂的逐字跳脫；「/」也要跳脫才能放進 /…/
    internal fun jsRegex(r: Regex): String =
        Regex("\\\\Q(.*?)\\\\E").replace(r.pattern) { m -> m.groupValues[1].replace(Regex("[.*+?^\${}()|\\[\\]\\\\/]")) { "\\" + it.value } }
            .replace(Regex("(?<!\\\\)/"), "\\\\/")

    /** 內容腳本用的精簡 chrome.*：runtime（getURL、getManifest、訊息為空操作）、storage（存在網站的 localStorage）、i18n */
    private fun chromeShim(ext: Ext): String {
        val key = JSONObject.quote("__oreaExt:" + dirName(ext.id))
        val base = JSONObject.quote("$RES_PATH${dirName(ext.id)}/")
        return """(function(){var K=$key,B=location.origin+$base,M=${ext.messages},MF=${ext.manifest};
function ld(){try{return JSON.parse(localStorage.getItem(K)||'{}')}catch(e){return{}}}function sv(o){try{localStorage.setItem(K,JSON.stringify(o))}catch(e){}}
function cb(f,v){if(typeof f==='function')setTimeout(function(){f(v)},0);return Promise.resolve(v)}
var area={get:function(k,f){var o=ld(),r={};if(k==null)r=o;else if(typeof k==='string')k in o&&(r[k]=o[k]);else if(Array.isArray(k))k.forEach(function(x){x in o&&(r[x]=o[x])});else for(var x in k)r[x]=x in o?o[x]:k[x];return cb(f,r)},
set:function(v,f){var o=ld();for(var x in v)o[x]=v[x];sv(o);return cb(f)},remove:function(k,f){var o=ld();[].concat(k).forEach(function(x){delete o[x]});sv(o);return cb(f)},clear:function(f){sv({});return cb(f)}};
var ev={addListener:function(){},removeListener:function(){},hasListener:function(){return false}};
return{runtime:{id:K,getURL:function(p){return B+String(p).replace(/^\//,'')},getManifest:function(){return MF},sendMessage:function(){var a=arguments;return cb(typeof a[a.length-1]==='function'?a[a.length-1]:null)},onMessage:ev,connect:function(){return{postMessage:function(){},onMessage:ev,onDisconnect:ev,disconnect:function(){}}},lastError:undefined},
storage:{local:area,sync:area,session:area,onChanged:ev},
i18n:{getMessage:function(n,s){var m=M[n]||M[Object.keys(M).find(function(x){return x.toLowerCase()===String(n).toLowerCase()})];if(!m)return'';var t=m.message;s=[].concat(s==null?[]:s);
if(m.placeholders)for(var p in m.placeholders){var c=m.placeholders[p].content||'';t=t.split('$'+p+'$').join(c).split('$'+p.toUpperCase()+'$').join(c)}
return t.replace(/\$(\d)/g,function(_,d){return s[d-1]==null?'':s[d-1]})},getUILanguage:function(){return navigator.language}},
extension:{getURL:function(p){return B+String(p).replace(/^\//,'')}}}})()"""
    }

    /** 使用者腳本的 GM_* 函式（值存在網站的 localStorage；GM_xmlhttpRequest 改用 fetch，跨網站時受 CORS 限制） */
    private fun gmShim(ext: Ext): String {
        val key = JSONObject.quote("__oreaGM:" + dirName(ext.id))
        val info = JSONObject().put("script", JSONObject().put("name", ext.name).put("version", ext.version)).put("scriptHandler", "Orea").put("version", "1")
        return """(function(){var K=$key;function ld(){try{return JSON.parse(localStorage.getItem(K)||'{}')}catch(e){return{}}}function sv(o){try{localStorage.setItem(K,JSON.stringify(o))}catch(e){}}
var add=function(t){var s=document.createElement('style');s.textContent=t;(function a(){var p=document.head||document.documentElement;p?p.appendChild(s):setTimeout(a,10)})();return s};
var get=function(k,d){var o=ld();return k in o?o[k]:d},set=function(k,v){var o=ld();o[k]=v;sv(o)},del=function(k){var o=ld();delete o[k];sv(o)},keys=function(){return Object.keys(ld())};
var xhr=function(d){fetch(d.url,{method:d.method||'GET',headers:d.headers,body:d.data,credentials:d.anonymous?'omit':'include'}).then(function(r){return r.text().then(function(t){var x={status:r.status,statusText:r.statusText,responseText:t,response:t,finalUrl:r.url,readyState:4,responseHeaders:''};
if(d.responseType==='json'){try{x.response=JSON.parse(t)}catch(e){}}d.onload&&d.onload(x)})}).catch(function(e){d.onerror&&d.onerror(e)});return{abort:function(){}}};
var open=function(u){return window.open(u,'_blank')},clip=function(t){navigator.clipboard&&navigator.clipboard.writeText(String(t))},menu=function(){},note=function(){};
var info=$info;var GM={info:info,addStyle:function(t){return Promise.resolve(add(t))},getValue:function(k,d){return Promise.resolve(get(k,d))},setValue:function(k,v){return Promise.resolve(set(k,v))},
deleteValue:function(k){return Promise.resolve(del(k))},listValues:function(){return Promise.resolve(keys())},xmlHttpRequest:xhr,openInTab:open,setClipboard:clip,registerMenuCommand:menu,notification:note};
return[info,GM,add,get,set,del,keys,xhr,open,clip,menu,note,window]})()"""
    }

    /** chrome.runtime.getURL() 的資源：從擴充資料夾讀檔（只回應已安裝擴充、而且在資料夾裡的檔案） */
    fun resource(url: Uri): WebResourceResponse? {
        val path = url.path.orEmpty()
        if (!path.startsWith(RES_PATH)) return null
        val parts = path.removePrefix(RES_PATH).split('/', limit = 2)
        if (parts.size < 2) return null
        val dir = File(root, parts[0])
        val f = File(dir, parts[1])
        if (!f.canonicalPath.startsWith(dir.canonicalPath + File.separator) || !f.isFile) return null
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: when (f.extension.lowercase()) {
            "js" -> "text/javascript"; "json" -> "application/json"; "css" -> "text/css"; "svg" -> "image/svg+xml"; else -> "application/octet-stream"
        }
        return WebResourceResponse(mime, "utf-8", 200, "OK", mapOf("Access-Control-Allow-Origin" to "*"), f.inputStream())
    }

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else List(length()) { optString(it) }
    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else List(length()) { optJSONObject(it) }.filterNotNull()
}
