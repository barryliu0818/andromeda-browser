package com.liquid.browser.browser

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
    /** main：在網頁本身的環境執行（world: MAIN），不給 chrome.* */
    class Script(val matches: List<Regex>, val excludes: List<Regex>, val js: String, val css: String, val runAt: String, val allFrames: Boolean, val main: Boolean = false)

    class Ext(
        val id: String, val name: String, val version: String, val description: String,
        val userScript: Boolean, val source: String, val scripts: List<Script>,
        val messages: JSONObject, val manifest: JSONObject, val enabled: Boolean,
        /** 使用者腳本的固定編號（GM_info.script.uuid）：安裝時產生，更新後不變 */
        val uuid: String = "",
        /** false：靠瀏覽器內建的網路過濾或腳本管理功能運作，Orea 執行不了（見 [unsupported]） */
        val works: Boolean = true,
        /** 有背景程式（MV3 service_worker、MV2 scripts／page）：由 [ExtensionHost] 在看不見的頁面執行 */
        val background: Boolean = false,
        /** 工具列按鈕的彈出視窗、選項頁（相對路徑） */
        val popup: String? = null,
        val options: String? = null,
        /** 安裝時間（更新後背景程式要重新啟動） */
        val stamp: Long = 0,
        /** 剛安裝（"install"）或剛更新（"update"）：背景程式啟動後觸發 runtime.onInstalled 一次 */
        val fresh: String = "",
        /** Firefox 附加元件（.xpi）：有 browser.*、manifest 保留 Firefox 的設定；Chrome 擴充沒有這兩樣（同 Chrome） */
        val firefox: Boolean = false,
    ) {
        val sid: String get() = dirName(id)
        /** 給擴充看的 manifest（chrome.runtime.getManifest）：Chrome 不給 Firefox 專用的欄位，有些擴充靠這個判斷自己在哪個瀏覽器 */
        val jsManifest: JSONObject get() = if (firefox) manifest else JSONObject(manifest.toString()).apply { remove("browser_specific_settings"); remove("applications") }
        /** chrome.runtime.id：跟 Chrome 一樣是 32 個 a～p 的字母 */
        val chromeId: String get() = java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).take(16)
            .joinToString("") { b -> "%02x".format(b) }.map { 'a' + Character.digit(it, 16) }.joinToString("")
    }

    /**
     * 執行不了的擴充：只靠瀏覽器內建的網路過濾（declarativeNetRequest、webRequestBlocking、proxy）
     * 或使用者腳本管理功能（userScripts）、又沒有內容腳本的（uBlock Origin Lite、Tampermonkey 本身），
     * 以及什麼都沒有的（沒有內容腳本、背景程式、彈出視窗、選項頁）
     */
    private fun unsupported(m: JSONObject): Boolean {
        if ((m.optJSONArray("content_scripts")?.length() ?: 0) > 0) return false
        val perms = m.optJSONArray("permissions").strings()
        if (perms.any { it in NET_ONLY }) return true
        return !hasBackground(m) && popupOf(m) == null && optionsOf(m) == null
    }
    private val NET_ONLY = setOf("declarativeNetRequest", "declarativeNetRequestWithHostAccess", "userScripts", "webRequestBlocking", "proxy")

    private fun hasBackground(m: JSONObject): Boolean {
        val bg = m.optJSONObject("background") ?: return false
        return bg.optString("service_worker").isNotEmpty() || bg.optString("page").isNotEmpty() || (bg.optJSONArray("scripts")?.length() ?: 0) > 0
    }
    private fun popupOf(m: JSONObject): String? =
        (m.optJSONObject("action") ?: m.optJSONObject("browser_action") ?: m.optJSONObject("page_action"))?.optString("default_popup")?.takeIf { it.isNotBlank() }
    private fun optionsOf(m: JSONObject): String? =
        m.optJSONObject("options_ui")?.optString("page")?.takeIf { it.isNotBlank() } ?: m.optString("options_page").takeIf { it.isNotBlank() }

    /** 擴充資料夾（代號不對或不存在回傳 null） */
    fun dirOf(sid: String): File? = File(root, sid).takeIf { sid.matches(Regex("[0-9a-f]{16}")) && it.isDirectory }

    fun bySid(sid: String): Ext? = list.firstOrNull { it.sid == sid }

    /** runtime.onInstalled 已觸發：清掉剛安裝的記號 */
    fun clearFresh(sid: String) {
        val f = File(dirOf(sid) ?: return, META)
        runCatching { val m = JSONObject(f.readText()); if (m.has("fresh")) { m.remove("fresh"); f.writeText(m.toString()) } }
        val i = list.indexOfFirst { it.sid == sid }
        if (i >= 0) runCatching { list[i] = load(File(root, sid)) }
    }

    /** chrome.scripting.registerContentScripts 等：動態內容腳本存在 dynamic.json，載入時跟 manifest 的合併 */
    fun dynamicScripts(sid: String): JSONArray = runCatching { JSONArray(File(dirOf(sid)!!, "dynamic.json").readText()) }.getOrDefault(JSONArray())

    fun setDynamicScripts(sid: String, arr: JSONArray) {
        val dir = dirOf(sid) ?: return
        File(dir, "dynamic.json").writeText(arr.toString())
        val i = list.indexOfFirst { it.sid == sid }
        if (i >= 0) runCatching { list[i] = load(dir) }
        onChanged?.invoke()
    }

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

    /** 使用者腳本的 GM_info 自稱 Tampermonkey（記號檔存在＝開） */
    var asTampermonkey by mutableStateOf(false)
        private set
    private const val TM_VERSION = "5.3.3"
    private val tmFlag get() = File(root, ".as_tampermonkey")

    fun useTampermonkeyName(on: Boolean) {
        if (on) tmFlag.writeText("1") else tmFlag.delete()
        asTampermonkey = on
        onChanged?.invoke()
    }

    fun init(ctx: Context) {
        root = File(ctx.filesDir, "extensions").apply { mkdirs() }
        asTampermonkey = tmFlag.exists()
        UserScripts.init(root)
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
            host == "addons.mozilla.org" -> AMO_ID.find(path)?.let { "amo:" + it.groupValues[1] }
            host == "chromewebstore.google.com" || (host == "chrome.google.com" && path.startsWith("/webstore")) ->
                CWS_ID.find(path)?.let {
                    "https://clients2.google.com/service/update2/crx?response=redirect&prodversion=159.0&acceptformat=crx2,crx3&x=id%3D${it.groupValues[1]}%26uc"
                }
            host.endsWith("greasyfork.org") || host.endsWith("sleazyfork.org") ->
                GREASY_ID.find(path)?.let { "https://$host/scripts/${it.groupValues[1]}/code/script.user.js" }
            else -> null
        }
    }

    // packageUrl 每次換頁都會跑：正規表示式只編譯一次
    private val AMO_ID = Regex("/addon/([^/]+)")
    private val CWS_ID = Regex("/([a-p]{32})(/|$)")
    private val GREASY_ID = Regex("/scripts/(\\d+)")

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
        val manifest = runCatching { JSONObject(File(tmp, "manifest.json").readText().removePrefix("\uFEFF")) }.getOrNull() ?: error("找不到 manifest.json")
        if (unsupported(manifest)) error("這個擴充功能要靠瀏覽器內建的網路過濾或腳本管理功能，Orea 無法執行")
        val gecko = (manifest.optJSONObject("browser_specific_settings") ?: manifest.optJSONObject("applications"))?.optJSONObject("gecko")?.optString("id")
        val id = gecko?.takeIf { it.isNotBlank() } ?: manifest.optString("name") + "@" + source.substringBefore('?')
        val fresh = if (File(root, dirName(id)).isDirectory) "update" else "install"
        val kind = if (bytes.size > 4 && String(bytes, 0, 4) == "Cr24") "chrome" else if (!gecko.isNullOrBlank() || source.contains(".xpi") || source.contains("addons.mozilla.org")) "firefox" else "chrome"
        File(tmp, META).writeText(JSONObject().put("id", id).put("source", source).put("enabled", true).put("fresh", fresh).put("kind", kind).put("installed", System.currentTimeMillis()).toString())
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
        // @resource 名稱 網址：安裝時下載好（GM_getResourceText／GM_getResourceURL 同步讀取）
        val resources = meta["resource"].orEmpty().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }
        if (resources.isNotEmpty()) UserScripts.saveResources(tmp, resources, ::download)
        val id = (meta["namespace"]?.firstOrNull() ?: source) + "/" + name
        File(tmp, META).writeText(
            JSONObject().put("id", id).put("source", source).put("enabled", true).put("userScript", true)
                .put("checked", System.currentTimeMillis()).put("uuid", java.util.UUID.randomUUID().toString()).toString(),
        )
        return moveInto(tmp, id)
    }

    private fun moveInto(tmp: File, id: String): File {
        val dir = File(root, dirName(id))
        // 更新（重裝同一個腳本）：保留腳本存的值（GM_setValue），也保留原本的開關
        File(dir, "values.json").takeIf { it.isFile }?.copyTo(File(tmp, "values.json"), overwrite = true)
        // 也保留原本的開關與固定編號（綁定會員的腳本靠編號認機器，更新後變了就要重新激活）
        runCatching { JSONObject(File(dir, META).readText()) }.getOrNull()?.let { old ->
            val m = File(tmp, META)
            if (m.isFile) {
                val n = JSONObject(m.readText())
                if (!old.optBoolean("enabled", true)) n.put("enabled", false)
                old.optString("uuid").takeIf { it.isNotEmpty() }?.let { n.put("uuid", it) }
                m.writeText(n.toString())
            }
        }
        UserScripts.forget(dirName(id))
        dir.deleteRecursively()
        if (!tmp.renameTo(dir)) error("無法寫入擴充功能資料夾")
        return dir
    }

    // ---------- 使用者腳本更新（同 Tampermonkey：每天檢查一次 @updateURL，版本較新就下載 @downloadURL 重裝） ----------
    private const val UPDATE_EVERY_MS = 24L * 3600 * 1000

    /** 檢查所有使用者腳本的更新（背景執行緒）；回傳更新了幾個 */
    fun checkUpdates(force: Boolean = false): Int {
        var updated = 0
        for (ext in list.toList().filter { it.userScript }) {
            val dir = File(root, dirName(ext.id))
            val metaFile = File(dir, META)
            val meta = runCatching { JSONObject(metaFile.readText()) }.getOrNull() ?: continue
            if (!force && System.currentTimeMillis() - meta.optLong("checked") < UPDATE_EVERY_MS) continue
            runCatching {
                val m = userScriptMeta(File(dir, "script.user.js").readText())
                val source = meta.optString("source")
                val download = m["downloadURL"]?.firstOrNull()?.takeIf { it.startsWith("http") } ?: source.takeIf { it.startsWith("http") } ?: return@runCatching
                val check = m["updateURL"]?.firstOrNull()?.takeIf { it.startsWith("http") } ?: download
                val remote = userScriptMeta(String(download(check)))["version"]?.firstOrNull() ?: return@runCatching
                if (newerVersion(remote, m["version"]?.firstOrNull().orEmpty())) {
                    installBytes(download(download), source)
                    updated++
                    com.liquid.browser.BugLog.event("extension", "使用者腳本更新：${ext.name} → $remote")
                }
            }.onFailure { com.liquid.browser.BugLog.event("extension", "檢查更新失敗 ${ext.name}：$it") }
            // 重裝後的資料夾是新的：檢查時間記在新的 META
            runCatching { val f = File(dir, META); f.writeText(JSONObject(f.readText()).put("checked", System.currentTimeMillis()).toString()) }
        }
        return updated
    }

    /** a 比 b 新（點分版本，逐段比數字，數字相同再比文字） */
    internal fun newerVersion(a: String, b: String): Boolean {
        val x = a.split('.', '-', '+'); val y = b.split('.', '-', '+')
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { "0" }; val q = y.getOrElse(i) { "0" }
            val pn = p.takeWhile(Char::isDigit).toLongOrNull() ?: 0; val qn = q.takeWhile(Char::isDigit).toLongOrNull() ?: 0
            if (pn != qn) return pn > qn
            val c = p.dropWhile(Char::isDigit).compareTo(q.dropWhile(Char::isDigit))
            if (c != 0) return c > 0
        }
        return false
    }

    /** 這個擴充的資料夾名稱（使用者腳本的 GM 儲存也用它當腳本代號） */
    fun sidOf(ext: Ext) = dirName(ext.id)

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
        UserScripts.forget(dirName(ext.id))
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
            // 舊版安裝的腳本沒有編號：補一個寫回去
            val uuid = meta.optString("uuid").ifEmpty { java.util.UUID.randomUUID().toString().also { runCatching { File(dir, META).writeText(meta.put("uuid", it).toString()) } } }
            val text = File(dir, "script.user.js").readText()
            val m = userScriptMeta(text)
            val matches = m["match"].orEmpty().mapNotNull(::matchPattern) + m["include"].orEmpty().mapNotNull(::globOrRegex)
            val excludes = m["exclude"].orEmpty().mapNotNull(::globOrRegex) + m["exclude-match"].orEmpty().mapNotNull(::matchPattern)
            val runAt = when (m["run-at"]?.firstOrNull()) { "document-start" -> "document_start"; "document-idle" -> "document_idle"; else -> "document_end" }
            return Ext(
                id, m["name"]?.firstOrNull().orEmpty(), m["version"]?.firstOrNull().orEmpty(), m["description"]?.firstOrNull().orEmpty(),
                true, meta.optString("source"), listOf(Script(matches, excludes, text, "", runAt, m.containsKey("noframes").not())),
                JSONObject(), JSONObject().put("name", m["name"]?.firstOrNull()).put("version", m["version"]?.firstOrNull()), enabled, uuid,
            )
        }
        val manifest = JSONObject(File(dir, "manifest.json").readText().removePrefix("\uFEFF"))
        val messages = localeMessages(dir, manifest.optString("default_locale"))
        fun msg(s: String) = Regex("__MSG_(\\w+)__").replace(s) { r -> messages.optJSONObject(r.groupValues[1])?.optString("message") ?: messages.keys().asSequence().firstOrNull { it.equals(r.groupValues[1], true) }?.let { messages.getJSONObject(it).optString("message") } ?: r.value }
        fun file(p: String) = File(dir, p.removePrefix("./").trimStart('/')).takeIf(File::isFile)?.readText().orEmpty()
        val manifestScripts = manifest.optJSONArray("content_scripts").objects().map { cs ->
            Script(
                cs.optJSONArray("matches").strings().mapNotNull(::matchPattern),
                cs.optJSONArray("exclude_matches").strings().mapNotNull(::matchPattern) + cs.optJSONArray("exclude_globs").strings().mapNotNull(::globOrRegex),
                cs.optJSONArray("js").strings().joinToString("\n;\n", transform = ::file),
                cs.optJSONArray("css").strings().joinToString("\n", transform = ::file),
                cs.optString("run_at", "document_idle"), cs.optBoolean("all_frames"), cs.optString("world") == "MAIN",
            )
        }
        // chrome.scripting.registerContentScripts 註冊的（格式同 Chrome 的 RegisteredContentScript）
        val dynamic = dynamicScripts(dir.name).objects().map { cs ->
            Script(
                cs.optJSONArray("matches").strings().mapNotNull(::matchPattern),
                cs.optJSONArray("excludeMatches").strings().mapNotNull(::matchPattern),
                cs.optJSONArray("js").strings().joinToString("\n;\n", transform = ::file),
                cs.optJSONArray("css").strings().joinToString("\n", transform = ::file),
                cs.optString("runAt", "document_idle"), cs.optBoolean("allFrames"), cs.optString("world") == "MAIN",
            )
        }
        return Ext(
            id, msg(manifest.optString("name")), manifest.optString("version"), msg(manifest.optString("description")), false, meta.optString("source"),
            manifestScripts + dynamic, messages, manifest, enabled, works = !unsupported(manifest),
            background = hasBackground(manifest), popup = popupOf(manifest), options = optionsOf(manifest),
            stamp = meta.optLong("installed"), fresh = meta.optString("fresh"),
            firefox = meta.optString("kind").let { k -> if (k.isNotEmpty()) k == "firefox" else meta.optString("source").let { s -> s.contains(".xpi") || s.contains("addons.mozilla.org") } },
        )
    }

    /** _locales：依系統語言挑（zh_TW → zh → 預設語言 → en） */
    private fun localeMessages(dir: File, default: String): JSONObject {
        val loc = java.util.Locale.getDefault()
        val tries = listOf("${loc.language}_${loc.country}", loc.language, default, "en")
        for (l in tries) {
            val f = File(dir, "_locales/$l/messages.json")
            if (f.exists()) return runCatching { JSONObject(f.readText().removePrefix("\uFEFF")) }.getOrDefault(JSONObject())
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
        val active = list.filter { it.enabled && it.works && it.scripts.isNotEmpty() }
        if (active.isEmpty()) return null
        return buildString {
            append("(function(){if(window.__oreaExt)return;window.__oreaExt=1;var u=location.href,top=window.top===window;")
            // Trusted Types（YouTube 等網站開著）：Chrome 的擴充在獨立的環境執行、不受網頁限制；Orea 的在網頁裡執行，
            // 有擴充要跑的網頁先放一個照單全收的預設政策（網頁沒設預設政策、CSP 也允許時），擴充設 innerHTML 等才不會被擋
            append("var TT=0;function tt(){if(TT)return;TT=1;try{if(window.trustedTypes&&!trustedTypes.defaultPolicy){var p=function(s){return s};trustedTypes.createPolicy('default',{createHTML:p,createScript:p,createScriptURL:p})}}catch(e){}}")
            // 使用者腳本跟 App 溝通用的通道：在網頁任何程式執行前就把 LiquidBridge、JSON、__oreaGM 抓進閉包（網頁事後換掉也影響不到），
            // 每個請求都帶 token；App 的回覆依 gm＋id 分給等待中的回呼
            append(gmChannel())
            // 擴充功能的內容腳本：每個擴充在每個框架一份 chrome.*（同一個擴充的內容腳本共用監聽器）；訊息走 O 通道
            if (active.any { !it.userScript }) {
                append(ExtensionHost.apiJs)
                append("var X={};function ch(s,f){return X[s]||(X[s]=__oreaMkChrome(f,function(d){O.send({type:'ext',sid:s,d:d})},function(h){O.on('ext:'+s,function(m){h(m.d)})}))}")
                append(GLOBAL_PROXY)
                // 擴充用 import() 載入自己的模組（模組在網頁的全域執行，拿不到內容腳本的 chrome）：模組開頭會用這次開 App 的金鑰跟這裡拿（見 [resource]）
                append("var K={};try{Object.defineProperty(window,'__oreaApi',{value:function(k){return K[k]}})}catch(e){}")
            }
            append("function when(r,f){if(r==='document_start')return f();var go=function(){r==='document_idle'?setTimeout(f,0):f()};")
            append("document.readyState==='loading'?document.addEventListener('DOMContentLoaded',go,{once:true}):go()}")
            append("function css(t){var s=document.createElement('style');s.textContent=t;var p=document.head||document.documentElement;p?p.appendChild(s):document.addEventListener('DOMContentLoaded',function(){document.head.appendChild(s)})}")
            for ((n, ext) in active.withIndex()) {
                // 這個擴充的設定只在第一次用到時建立（manifest、語系字串可能不小）
                if (!ext.userScript) append("\nfunction C$n(){var c=ch(").append(JSONObject.quote(ext.sid)).append(",").append(csConfig(ext)).append(");")
                    .append("var k=").append(JSONObject.quote(moduleKey(ext.sid))).append(";K[k]||(K[k]={c:c,b:").append(if (ext.firefox) "c" else "undefined")
                    .append(",g:gp(c,").append(ext.firefox).append(")});return c}")
                ext.scripts.forEach { s ->
                    if (s.matches.isEmpty()) return@forEach
                    val inc = s.matches.joinToString(",") { "/" + jsRegex(it) + "/" }
                    val exc = s.excludes.joinToString(",") { "/" + jsRegex(it) + "/" }
                    append("\nif((top||").append(s.allFrames).append(")&&[").append(inc).append("].some(function(r){return r.test(u)})&&![").append(exc).append("].some(function(r){return r.test(u)})){")
                    append("tt();")
                    if (s.css.isNotBlank()) append("when('document_start',function(){css(").append(JSONObject.quote(s.css)).append(")});")
                    if (s.js.isNotBlank()) {
                        append("when(").append(JSONObject.quote(s.runAt)).append(",function(){try{")
                        if (ext.userScript) append("(function(").append(GM_PARAMS).append("){")
                            .append(s.js).append("\n}).apply(window,").append(gmShim(ext, s.js)).append(");")
                        else if (s.main) append("(function(){").append(s.js).append("\n}).call(window);")
                        else append("(function(chrome,browser,globalThis){").append(s.js).append("\n}).call(window,C$n(),")
                            .append(if (ext.firefox) "C$n()" else "undefined").append(",gp(C$n(),").append(ext.firefox).append("));")
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

    /**
     * chrome.scripting.executeScript／tabs.executeScript：在分頁執行擴充的程式（跟內容腳本一樣有 chrome.*），
     * 回傳值是最後一個運算式的值（evaluateJavascript 的結果）
     */
    fun injectionScript(ext: Ext, code: String, main: Boolean): String {
        if (main) return "(0,eval)(" + JSONObject.quote(code) + ")"
        val s = JSONObject.quote(ext.sid)
        return "(function(){" + gmChannel() + ExtensionHost.apiJs +
            GLOBAL_PROXY + "var c=__oreaMkChrome(" + csConfig(ext) + ",function(d){O.send({type:'ext',sid:$s,d:d})},function(h){O.on('ext:'+$s,function(m){h(m.d)})});" +
            "return(function(chrome,browser,globalThis){return eval(" + JSONObject.quote(code) + ")}).call(window,c," + (if (ext.firefox) "c" else "undefined") + ",gp(c," + ext.firefox + "))})()"
    }

    /**
     * 擴充程式看到的 globalThis：Chrome 的內容腳本在獨立的環境，globalThis.chrome 就是擴充的 API（webextension-polyfill 等靠這個判斷自己在擴充裡）。
     * Orea 的內容腳本跑在網頁裡，不能把 chrome 放到真的 window（網頁就能冒充內容腳本跟擴充要資料），
     * 所以給擴充一個 window 的代理：讀 chrome／browser 拿到擴充的，其他照 window（原生函式綁回 window 才能呼叫），寫入寫到 window
     */
    private const val GLOBAL_PROXY = "var FTS=Function.prototype.toString,BND=new WeakMap();function gp(c,ff){return c.__g||(Object.defineProperty(c,'__g',{value:new Proxy(window,{" +
        "get:function(t,k){if(k==='chrome')return c;if(k==='browser')return ff?c:undefined;var v=t[k];" +
        "if(typeof v==='function'&&!v.prototype){var b=BND.get(v);if(!b){try{b=/\\[native code\\]/.test(FTS.call(v))?v.bind(t):v}catch(e){b=v}BND.set(v,b)}return b}return v}," +
        "set:function(t,k,v){t[k]=v;return true},has:function(t,k){return k==='chrome'||k in t}})}),c.__g)}"

    /** 內容腳本的 chrome.* 設定（JS 運算式）：getURL 指向網頁自己網站下的 [RES_PATH]（見 [resource]） */
    private fun csConfig(ext: Ext): String {
        val cfg = JSONObject().put("sid", ext.sid).put("id", ext.chromeId).put("manifest", ext.jsManifest).put("messages", ext.messages)
            .put("lang", java.util.Locale.getDefault().toLanguageTag()).put("page", false).put("firefox", ext.firefox)
        return "Object.assign(" + cfg + ",{base:location.origin+" + JSONObject.quote(RES_PATH + ext.sid + "/") + "})"
    }

    /** 使用者腳本函式的參數名稱（腳本裡直接用這些名字，跟 Tampermonkey／Violentmonkey 一樣不必加 window.） */
    private const val GM_PARAMS = "GM_info,GM,GM_addStyle,GM_getValue,GM_setValue,GM_deleteValue,GM_listValues,GM_xmlhttpRequest," +
        "GM_openInTab,GM_setClipboard,GM_registerMenuCommand,GM_unregisterMenuCommand,GM_notification,GM_download," +
        "GM_getResourceText,GM_getResourceURL,GM_addElement,GM_addValueChangeListener,GM_removeValueChangeListener,GM_log," +
        "GM_getValues,GM_setValues,GM_deleteValues,unsafeWindow"

    /** 開頁時建立的通道（見 documentStartScript）：變數 O */
    private fun gmChannel(): String = """var O=(function(){var B=window.LiquidBridge,N=window.__oreaGM,S=JSON.stringify,P=JSON.parse,pm=B&&B.postMessage?B.postMessage.bind(B):null,cb={},seq=0,T=TOKEN;
if(B&&B.addEventListener)B.addEventListener('message',function(e){var m;try{m=P(e.data)}catch(x){return}if(!m||!m.gm)return;var f=cb[m.gm+':'+m.id];if(f)f(m)});
return{T:T,S:S,P:P,N:N,send:function(o){o.token=T;if(pm)pm(S(o))},on:function(k,f){cb[k]=f},off:function(k){delete cb[k]},id:function(){return(++seq)+'_'+Math.random().toString(36).slice(2)}}})();""".replace("TOKEN", JSONObject.quote(UserScripts.token))

    /**
     * 使用者腳本的 GM_* 與 GM.*（照 Violentmonkey）：值存在 App（每個腳本一份、所有網站共用）、
     * GM_xmlhttpRequest 由 App 代發（不受 CORS 限制，預設帶 Cookie）、選單指令列在頁面選單、@resource 安裝時已下載。
     * 回傳的陣列依 [GM_PARAMS] 的順序
     */
    private fun gmShim(ext: Ext, text: String): String {
        val m = userScriptMeta(text)
        val script = JSONObject()
            .put("name", ext.name).put("namespace", m["namespace"]?.firstOrNull().orEmpty()).put("version", ext.version)
            .put("description", ext.description).put("matches", JSONArray(m["match"].orEmpty())).put("includes", JSONArray(m["include"].orEmpty()))
            .put("excludes", JSONArray(m["exclude"].orEmpty())).put("grant", JSONArray(m["grant"].orEmpty()))
            .put("runAt", m["run-at"]?.firstOrNull() ?: "document-end")
            .put("resources", JSONArray(m["resource"].orEmpty().map { r -> r.trim().split(Regex("\\s+"), limit = 2).let { JSONObject().put("name", it[0]).put("url", it.getOrElse(1) { "" }) } }))
        val header = text.substringAfter("// ==UserScript==", "").substringBefore("// ==/UserScript==")
        script.put("uuid", ext.uuid)
        // 有些付費腳本只認 Tampermonkey：開了「以 Tampermonkey 名義執行」就照 Tampermonkey 的名稱與版本回報
        val info = JSONObject().put("script", script).put("uuid", ext.uuid).put("scriptMetaStr", header)
            .put("scriptHandler", if (asTampermonkey) "Tampermonkey" else "Orea").put("version", if (asTampermonkey) TM_VERSION else "1.0")
            .put("scriptWillUpdate", true).put("isIncognito", false)
        return GM_SHIM.replace("SID", JSONObject.quote(dirName(ext.id))).replace("INFO", info.toString())
    }

    private const val GM_SHIM = """(function(){var sid=SID,T=O.T,S=O.S,P=O.P,N=O.N,vals={},info=INFO;
try{vals=P(N.values(T,sid))}catch(e){}
var lis={},lseq=0;function fire(k,o,n){for(var i in lis){var l=lis[i];if(l.k===k)try{l.f(k,o,n,false)}catch(e){}}}
var get=function(k,d){if(!(k in vals))return d;try{return P(vals[k])}catch(e){return d}};
var del=function(k){var o=get(k);delete vals[k];O.send({type:'gm_set',sid:sid,key:String(k)});fire(k,o,undefined)};
var set=function(k,v){var o=get(k),j=S(v);if(j===undefined)return del(k);vals[k]=j;O.send({type:'gm_set',sid:sid,key:String(k),value:j});fire(k,o,v)};
var keys=function(){return Object.keys(vals)};
var gets=function(ks){var o={},arr=Array.isArray(ks);(arr?ks:Object.keys(ks||{})).forEach(function(k){o[k]=get(k,arr?undefined:ks[k])});return o};
var sets=function(o){for(var k in o)set(k,o[k])},dels=function(ks){(ks||[]).forEach(del)};
var place=function(e,p){(function a(){var q=p||document.head||document.body||document.documentElement;q?q.appendChild(e):setTimeout(a,10)})();return e};
var add=function(t){var s=document.createElement('style');s.textContent=t;return place(s)};
var addEl=function(p,tag,a){if(typeof p==='string'){a=tag;tag=p;p=null}var e=document.createElement(tag);for(var k in a||{}){if(k==='textContent')e.textContent=a[k];else e.setAttribute(k,a[k])}return place(e,p)};
function b64(a){var u=new Uint8Array(a),s='';for(var i=0;i<u.length;i+=32768)s+=String.fromCharCode.apply(null,u.subarray(i,i+32768));return btoa(s)}
var xhr=function(d){var id=O.id(),q={method:d.method,url:String(d.url),headers:d.headers,responseType:d.responseType,timeout:d.timeout,anonymous:d.anonymous,overrideMimeType:d.overrideMimeType};
var go=function(){O.on('xhr:'+id,function(m){O.off('xhr:'+id);var r={readyState:4,status:m.status||0,statusText:m.statusText||'',finalUrl:m.finalUrl||d.url,responseHeaders:m.responseHeaders||'',context:d.context};
if(m.type==='load'){if(m.bodyB64!=null){var bin=atob(m.bodyB64),u=new Uint8Array(bin.length);for(var i=0;i<bin.length;i++)u[i]=bin.charCodeAt(i);r.response=d.responseType==='blob'?new Blob([u],{type:m.mime||''}):u.buffer;r.responseText=''}
else{var t=m.text||'';r.responseText=t;r.response=t;if(d.responseType==='json'){try{r.response=P(t)}catch(e){r.response=null}}else if(d.responseType==='document'){try{r.response=new DOMParser().parseFromString(t,(m.mime||'text/html').split(';')[0].trim()||'text/html')}catch(e){}}
Object.defineProperty(r,'responseXML',{get:function(){try{return new DOMParser().parseFromString(t,'text/xml')}catch(e){return null}}})}
d.onreadystatechange&&d.onreadystatechange(r);d.onprogress&&d.onprogress(r);d.onload&&d.onload(r)}
else if(m.type==='timeout'){d.ontimeout&&d.ontimeout(r)}else if(m.type==='abort'){d.onabort&&d.onabort(r)}else{r.error=m.error;d.onerror&&d.onerror(r)}
d.onloadend&&d.onloadend(r)});O.send({type:'gm_xhr',id:id,req:q})};
var data=d.data,h=function(o){q.headers=Object.assign({},q.headers||{},o)};
if(data==null)go();else if(typeof data==='string'){q.data=data;go()}
else if(typeof URLSearchParams!=='undefined'&&data instanceof URLSearchParams){q.data=data.toString();h({'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8'});go()}
else if(typeof FormData!=='undefined'&&data instanceof FormData){var b=new Response(data);h({'Content-Type':b.headers.get('Content-Type')});b.arrayBuffer().then(function(a){q.dataB64=b64(a);go()})}
else if(data instanceof Blob||data instanceof ArrayBuffer||ArrayBuffer.isView(data)){(data instanceof Blob?data.arrayBuffer():Promise.resolve(data.buffer||data)).then(function(a){q.dataB64=b64(a);go()})}
else{q.data=S(data);go()}
return{abort:function(){O.send({type:'gm_abort',id:id})}}};
var open=function(u,o){var bg=typeof o==='boolean'?o:!!(o&&(o.active===false||o.loadInBackground));O.send({type:'gm_open',url:new URL(u,location.href).href,bg:bg});return{close:function(){},closed:false,onclose:null}};
var clip=function(t){O.send({type:'gm_clip',text:String(t)})};
var menus={},mseq=0,sync=function(){var a=[];for(var k in menus)a.push({id:k,name:menus[k].n});O.send({type:'gm_menu',sid:sid,script:info.script.name,cmds:a})};
O.on('menu:'+sid,function(m){var c=menus[m.cmd];if(c)try{c.f()}catch(e){console.error(e)}});
var reg=function(n,f){var id=String(++mseq);menus[id]={n:String(n),f:f};sync();return id},unreg=function(id){delete menus[id];sync()};
var note=function(a,b){var o=typeof a==='object'&&a?a:{text:a,title:b};O.send({type:'gm_note',title:o.title||info.script.name,text:String(o.text||'')});o.ondone&&setTimeout(o.ondone,0)};
var dl=function(a,b){var o=typeof a==='object'&&a?a:{url:a,name:b};O.send({type:'gm_dl',url:new URL(o.url,location.href).href,name:o.name||''});o.onload&&setTimeout(o.onload,0)};
var resT=function(n){try{return N.resource(T,sid,n,false)}catch(e){return null}},resU=function(n){try{return N.resource(T,sid,n,true)}catch(e){return null}};
var addL=function(k,f){var id=++lseq;lis[id]={k:k,f:f};return id},remL=function(id){delete lis[id]};
var pr=function(f){return function(){var a=arguments;return new Promise(function(ok,no){try{ok(f.apply(null,a))}catch(e){no(e)}})}};
var GM={info:info,addStyle:pr(add),addElement:pr(addEl),getValue:pr(get),setValue:pr(set),deleteValue:pr(del),listValues:pr(keys),getValues:pr(gets),setValues:pr(sets),deleteValues:pr(dels),
xmlHttpRequest:function(d){var c,p=new Promise(function(ok,no){c=xhr(Object.assign({},d,{onload:function(r){d.onload&&d.onload(r);ok(r)},onerror:function(r){d.onerror&&d.onerror(r);no(r)},ontimeout:function(r){d.ontimeout&&d.ontimeout(r);no(r)},onabort:function(r){d.onabort&&d.onabort(r);no(r)}}))});p.abort=function(){c&&c.abort()};return p},
openInTab:open,setClipboard:pr(clip),registerMenuCommand:reg,unregisterMenuCommand:unreg,notification:pr(note),download:pr(dl),getResourceText:pr(resT),getResourceUrl:pr(resU),getResourceURL:pr(resU),
addValueChangeListener:addL,removeValueChangeListener:remL,log:console.log.bind(console)};
return[info,GM,add,get,set,del,keys,xhr,open,clip,reg,unreg,note,dl,resT,resU,addEl,addL,remL,console.log.bind(console),gets,sets,dels,window]})()"""

    /** chrome.runtime.getURL() 的資源：從擴充資料夾讀檔（只回應已安裝擴充、而且在資料夾裡的檔案） */
    fun resource(url: Uri, method: String = "GET", headers: Map<String, String> = emptyMap()): WebResourceResponse? {
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
        moduleWithApi(parts[0], url, f, method, headers)?.let { return WebResourceResponse(mime, "utf-8", 200, "OK", mapOf("Access-Control-Allow-Origin" to "*"), it.byteInputStream()) }
        return WebResourceResponse(mime, "utf-8", 200, "OK", mapOf("Access-Control-Allow-Origin" to "*"), f.inputStream())
    }

    /** 這次開 App、這個擴充的模組金鑰（網頁讀不到：只寫在開頁腳本與模組開頭） */
    private fun moduleKey(sid: String) = java.security.MessageDigest.getInstance("SHA-256").digest((UserScripts.token + sid).toByteArray()).take(16).joinToString("") { "%02x".format(it) }

    /**
     * 擴充的 ES 模組（import() 或模組裡的 import）：開頭加上取得 chrome、browser、globalThis 的那一行。
     * 只給同網站、GET、帶 Origin 的請求（瀏覽器載入模組才會這樣送；網頁用 fetch 讀同網站的檔案不帶 Origin、別的網站來讀 Origin 不同），
     * 網頁才讀不到金鑰；一般（非模組）腳本不加，免得把 chrome 放進網頁的全域
     */
    private fun moduleWithApi(sid: String, url: Uri, f: File, method: String, headers: Map<String, String>): String? {
        val ext = f.extension.lowercase()
        if (ext != "js" && ext != "mjs") return null
        if (!method.equals("GET", true)) return null
        val origin = headers.entries.firstOrNull { it.key.equals("Origin", true) }?.value ?: return null
        if (origin != "${url.scheme}://${url.authority}") return null
        val accept = headers.entries.firstOrNull { it.key.equals("Accept", true) }?.value
        if (accept != null && accept != "*/*") return null
        if (bySid(sid)?.let { it.enabled && !it.userScript } != true) return null
        val text = f.readText()
        if (!ES_MODULE.containsMatchIn(text)) return null
        return "const{c:chrome,b:browser,g:globalThis}=(typeof __oreaApi==='function'&&__oreaApi(" + JSONObject.quote(moduleKey(sid)) + "))||{};" + text
    }
    private val ES_MODULE = Regex("""(^|[\n;}])\s*(export\s*[{*]|export\s+(default|const|let|var|function|class|async)\b|import\s*[{*"']|import\s+\w)""")

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else List(length()) { optString(it) }
    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else List(length()) { optJSONObject(it) }.filterNotNull()
}
