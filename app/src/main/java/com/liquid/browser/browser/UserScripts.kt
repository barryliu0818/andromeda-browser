package com.liquid.browser.browser

import android.util.Base64
import android.webkit.JavascriptInterface
import com.liquid.browser.core.Http
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 使用者腳本（Tampermonkey／Violentmonkey 格式）需要瀏覽器幫忙的 GM_* 功能，照 Violentmonkey 的行為：
 *  - GM_getValue／setValue：每個腳本一份、存在 App 裡（values.json），所有網站共用；網頁本身讀不到
 *  - GM_xmlhttpRequest：由 App 代發請求，不受網頁的 CORS 限制，預設帶目標網站的 Cookie（anonymous 時不帶）
 *  - GM_getResourceText／GM_getResourceURL：@resource 在安裝時下載好
 *  - GM_registerMenuCommand：指令列在頁面選單的「使用者腳本指令」
 *  - GM_openInTab、GM_setClipboard、GM_notification、GM_download：交給 App
 *
 * 安全：網頁上的腳本也能呼叫 LiquidBridge 與 [Native]，所以每個請求都要帶 [token]。token 每次開 App 隨機產生，
 * 只寫在開頁腳本裡（網頁讀不到），而且開頁腳本在網頁任何程式執行前就把要用的函式抓在自己的閉包裡，網頁事後換掉也沒用。
 * 已知限制（同 Violentmonkey 的「網頁模式」）：App 回傳給腳本的結果經過 LiquidBridge，網頁若也監聽 LiquidBridge 看得到；
 * 但只看得到腳本自己發的請求的結果，不能自己發。
 */
object UserScripts {
    /** 這次開 App 的通行碼 */
    val token: String = java.util.UUID.randomUUID().toString().replace("-", "")

    private lateinit var root: File

    fun init(extRoot: File) { root = extRoot }

    /** 存值、寫檔用的背景執行緒（依序執行，後寫的不會被先寫的蓋掉） */
    val io: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor()

    // ---------- 儲存（每個腳本一份 values.json；記憶體裡一份，寫入時存檔） ----------
    private val values = ConcurrentHashMap<String, JSONObject>()

    private fun dir(sid: String): File? = File(root, sid).takeIf { it.isDirectory && sid.matches(Regex("[0-9a-f]{16}")) }

    private fun valuesOf(sid: String): JSONObject = values.getOrPut(sid) {
        runCatching { JSONObject(File(dir(sid) ?: return@getOrPut JSONObject(), "values.json").readText()) }.getOrDefault(JSONObject())
    }

    @Synchronized
    fun setValue(sid: String, key: String, json: String?) {
        val d = dir(sid) ?: return
        val o = valuesOf(sid)
        if (json == null) o.remove(key) else o.put(key, json)
        runCatching { File(d, "values.json").writeText(o.toString()) }
    }

    /** 移除腳本時一起清掉記憶體裡的值 */
    fun forget(sid: String) { values.remove(sid) }

    /** 頁面上給腳本同步讀取用的介面（WebView.addJavascriptInterface，名稱 __oreaGM）；每個方法都核對 token */
    class Native {
        /** 這個腳本的全部值：{key: 值的 JSON 字串} */
        @JavascriptInterface
        fun values(t: String?, sid: String?): String = if (t != token || sid == null) "{}" else valuesOf(sid).toString()

        /** @resource：asUrl＝回傳 data: 網址（GM_getResourceURL），否則回傳文字（GM_getResourceText） */
        @JavascriptInterface
        fun resource(t: String?, sid: String?, name: String?, asUrl: Boolean): String? {
            if (t != token || sid == null || name == null) return null
            val d = dir(sid) ?: return null
            val meta = runCatching { JSONObject(File(d, "resources.json").readText()) }.getOrNull() ?: return null
            val r = meta.optJSONObject(name) ?: return null
            val f = File(d, "res_" + r.optString("file"))
            if (!f.isFile) return null
            return if (asUrl) "data:${r.optString("mime", "application/octet-stream")};base64," + Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
            else f.readText()
        }
    }

    // ---------- @resource：安裝時下載 ----------
    fun saveResources(dir: File, resources: List<Pair<String, String>>, download: (String) -> ByteArray) {
        val meta = JSONObject()
        resources.forEachIndexed { i, (name, url) ->
            val bytes = download(url)
            val file = "$i"
            File(dir, "res_$file").writeBytes(bytes)
            val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase()
            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
                "js" -> "text/javascript"; "css" -> "text/css"; "json" -> "application/json"; "svg" -> "image/svg+xml"; else -> "application/octet-stream"
            }
            meta.put(name, JSONObject().put("file", file).put("url", url).put("mime", mime))
        }
        File(dir, "resources.json").writeText(meta.toString())
    }

    // ---------- GM_xmlhttpRequest ----------
    private val calls = ConcurrentHashMap<String, Call>()

    /**
     * 代發請求。req：method、url、headers、data（文字）、dataB64（二進位）、responseType、timeout、anonymous、overrideMimeType。
     * 結果用 reply 回傳（App 呼叫，任何執行緒）：{gm:'xhr', id, type:'load'|'error'|'timeout'|'abort', ...}
     */
    fun xhr(id: String, req: JSONObject, pageUrl: String, userAgent: String, incognito: Boolean, reply: (JSONObject) -> Unit) {
        val url = runCatching { java.net.URI(pageUrl).resolve(req.optString("url")).toString() }.getOrDefault(req.optString("url"))
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            reply(JSONObject().put("gm", "xhr").put("id", id).put("type", "error").put("error", "unsupported url"))
            return
        }
        val method = req.optString("method", "GET").uppercase()
        val b = Request.Builder().url(url)
        val headers = req.optJSONObject("headers")
        var hasUa = false
        var contentType: String? = null
        headers?.keys()?.forEach { k ->
            val v = headers.optString(k)
            when {
                k.equals("user-agent", true) -> { hasUa = true; b.header(k, v) }
                k.equals("content-type", true) -> { contentType = v; b.header(k, v) }
                // 瀏覽器不讓網頁改的標頭，Tampermonkey 可以：照送
                else -> runCatching { b.header(k, v) }
            }
        }
        if (!hasUa) b.header("User-Agent", userAgent)
        if (!req.optBoolean("anonymous")) {
            Downloads.cookieManager(incognito).getCookie(url)?.takeIf { it.isNotEmpty() }?.let { if (headers?.has("Cookie") != true) b.header("Cookie", it) }
        }
        val body = when {
            req.has("dataB64") -> Base64.decode(req.optString("dataB64"), Base64.DEFAULT).toRequestBody(contentType?.toMediaTypeOrNull())
            req.has("data") -> req.optString("data").toRequestBody((contentType ?: "text/plain;charset=UTF-8").toMediaTypeOrNull())
            method == "POST" || method == "PUT" || method == "PATCH" -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        b.method(method, if (method == "GET" || method == "HEAD") null else body)
        val timeout = req.optLong("timeout", 0)
        val client = if (timeout > 0) Http.client.newBuilder().callTimeout(timeout, TimeUnit.MILLISECONDS).build() else Http.client
        val call = client.newCall(b.build())
        calls[id] = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                calls.remove(id)
                val type = when {
                    call.isCanceled() -> "abort"
                    e is java.io.InterruptedIOException -> "timeout"
                    else -> "error"
                }
                reply(JSONObject().put("gm", "xhr").put("id", id).put("type", type).put("error", e.message.orEmpty()))
            }

            override fun onResponse(call: Call, response: Response) {
                calls.remove(id)
                response.use { r ->
                    val out = JSONObject().put("gm", "xhr").put("id", id).put("type", "load")
                        .put("status", r.code).put("statusText", r.message).put("finalUrl", r.request.url.toString())
                        .put("responseHeaders", r.headers.joinToString("\r\n") { (k, v) -> "$k: $v" })
                    // 回應的 Set-Cookie 寫回 WebView（之後網頁與腳本的請求都看得到，跟瀏覽器一樣）
                    if (!req.optBoolean("anonymous")) r.headers("Set-Cookie").forEach { Downloads.cookieManager(incognito).setCookie(r.request.url.toString(), it) }
                    val bytes = runCatching { r.body.bytes() }.getOrElse { reply(out.put("type", "error").put("error", it.message.orEmpty())); return }
                    when (req.optString("responseType")) {
                        "arraybuffer", "blob" -> out.put("bodyB64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                        else -> out.put("text", String(bytes, r.body.contentType()?.charset() ?: Charsets.UTF_8))
                    }
                    out.put("mime", req.optString("overrideMimeType").ifEmpty { r.header("Content-Type").orEmpty() })
                    reply(out)
                }
            }
        })
    }

    fun abort(id: String) { calls.remove(id)?.cancel() }

    /** 頁面選單上的一個腳本指令 */
    class MenuCommand(val script: String, val name: String, val id: String, val send: () -> Unit)
}
