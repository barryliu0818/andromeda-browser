package com.liquid.browser.browser

import com.liquid.browser.core.t

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.liquid.browser.core.Http
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * 網頁翻譯引擎。
 *  - Tencent：騰訊交互翻譯（TranSmart）公開端點，免金鑰，中國與海外都能用（預設）；
 *  - Free：Google 翻譯的公開端點，免金鑰（中國無法連線，失敗時自動改用騰訊）；
 *  - 通義千問、DeepSeek、智譜 GLM：都經由阿里雲百煉的 OpenAI 相容端點呼叫，共用一把金鑰，在設定頁貼上即可。
 * model 為預設模型名稱，設定頁可以改（各家模型更新很快）。
 */
/** 阿里雲百煉（千問 MaaS，北京）的 OpenAI 相容端點：sk-ws 開頭的工作空間金鑰走這裡 */
private const val BAILIAN = "https://cn-beijing.maas.qianwenaiapi.com/compatible-mode/v1/chat/completions"

enum class TranslateEngine(private val labelKey: String, val endpoint: String, val model: String, val keyHint: String) {
    Tencent("免費（騰訊交互翻譯，中國可用）", "https://transmart.qq.com/api/imt", "", ""),
    Free("免費（Google，中國無法使用）", "https://translate.googleapis.com/translate_a/t", "", ""),
    DeepSeek("DeepSeek V4.1 Flash", BAILIAN, "deepseek-v4.1-flash", ""),
    Qwen("通義千問 3.8 Flash", BAILIAN, "qwen3.8-flash", ""),
    // 百煉上的智譜模型名稱帶廠商前綴（ZHIPU/）；翻譯關掉思考（enable_thinking=false）求快。
    // 需先在百煉控制台開通，否則會回「Access denied」（此時自動改用免費翻譯）
    Glm("智譜 GLM-5.3 Flash", BAILIAN, "ZHIPU/GLM-5.3-Flash", ""),
    ;

    /** 顯示名稱（依介面語言翻譯） */
    val label: String get() = t(labelKey)

    /** 免金鑰的免費引擎 */
    val isFree: Boolean get() = this == Tencent || this == Free

    /** 金鑰歸屬：三個 AI 引擎都經由阿里雲百煉呼叫，共用同一把金鑰（存在 Qwen 名下） */
    val keyOwner: TranslateEngine get() = if (isFree) this else Qwen
}

/** 翻譯目標語言：各引擎使用的語言代碼與給 AI 的語言描述 */
/** tencent＝null：騰訊交互翻譯不支援（回 Unsupported-Language），免費翻譯改用 Google */
enum class TargetLang(val label: String, val google: String, val tencent: String?, val prompt: String) {
    ZhTW("繁體中文", "zh-TW", "zh-TW", "繁體中文（台灣用語）"),
    ZhCN("簡體中文", "zh-CN", "zh", "简体中文"),
    En("English", "en", "en", "English"),
    Ja("日本語", "ja", "ja", "日本語"),
    Ko("한국어", "ko", "ko", "한국어"),
    Es("Español", "es", "es", "español"),
    Ru("Русский", "ru", "ru", "русский язык"),
    Pt("Português", "pt", "pt", "português"),
    It("Italiano", "it", "it", "italiano"),
    La("Latina", "la", null, "lingua Latina"),
    Hi("हिन्दी", "hi", null, "हिन्दी"),
    Vi("Tiếng Việt", "vi", "vi", "tiếng Việt"),
}

object Translator {
    // 逐行「編號|文字」比 JSON 物件省 token（不用鍵的引號、逗號與跳脫字元，送出與回傳都省）
    private fun prompt(target: TargetLang) =
        "你是專業的網頁翻譯。每行是「編號|網頁文字」，把每行文字翻譯成${target.prompt}（常見詞句也要翻），" +
            "逐行輸出「編號|譯文」，編號與行數不變，不要其他文字。" +
            "很多行是同一句話被連結切開的片段：每行只翻譯自己的內容，不可把字詞移到別行或合併行，片段譯文可以不成句。網址、程式碼、數字、單位與專有名稱維持原樣；已經是${target.prompt}的原樣輸出。"

    /** 送給模型的內容：一段一行，段內換行改成空白（網頁文字的換行本來就顯示成空白） */
    fun encodeLines(texts: List<String>): String =
        texts.withIndex().joinToString("\n") { (i, s) -> "$i|" + s.replace(spaces, " ").trim() }

    private val spaces = Regex("\\s+")

    /** 解析模型回的「編號|譯文」；沒對到的編號為 null（之後由免費引擎補翻） */
    fun parseLines(content: String, size: Int): List<String?> {
        val out = arrayOfNulls<String>(size)
        content.lineSequence().forEach { l -> parseLine(l, size)?.let { (i, v) -> out[i] = v } }
        return out.toList()
    }

    private val lineRe = Regex("""^\s*(\d+)\s*[|｜]\s?(.*)$""")

    private fun parseLine(l: String, size: Int): Pair<Int, String>? {
        val m = lineRe.find(l) ?: return null
        val i = m.groupValues[1].toIntOrNull()?.takeIf { it in 0 until size } ?: return null
        return m.groupValues[2].trim().takeIf { it.isNotEmpty() }?.let { i to it }
    }

    /** onLine：AI 引擎串流時每翻好一段就回報（第幾段, 譯文），可在 IO 執行緒呼叫 */
    suspend fun translate(texts: List<String>, engine: TranslateEngine, key: String, model: String, target: TargetLang, onLine: ((Int, String) -> Unit)? = null): List<String> =
        withContext(Dispatchers.IO) {
            when {
                engine == TranslateEngine.Tencent -> if (target.tencent == null) google(texts, target) else tencent(texts, target)
                // Google 在中國連不上：失敗時改用騰訊
                engine == TranslateEngine.Free -> runCatching { google(texts, target) }.getOrElse { if (target.tencent == null) throw it else tencent(texts, target) }
                // AI 引擎沒填金鑰：用騰訊免費翻譯
                key.isBlank() -> if (target.tencent == null) google(texts, target) else tencent(texts, target)
                else -> llm(texts, engine, key, model.ifBlank { engine.model }, target, onLine)
            }
        }

    /**
     * 測試 AI 引擎的連線與金鑰：送一句短句，成功回傳耗時（毫秒）；失敗丟出帶原因的例外
     * （HTTP 狀態碼與服務回的訊息，例如金鑰無效、模型沒開通、連不上）。
     */
    suspend fun check(engine: TranslateEngine, key: String, model: String): Long = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        llm(listOf("Hello"), engine, key, model.ifBlank { engine.model }, TargetLang.ZhTW)
        (System.nanoTime() - start) / 1_000_000
    }

    private const val UA = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
    private val JSON_TYPE = "application/json".toMediaType()

    /** 騰訊交互翻譯（TranSmart）：一次送多段，來源語言自動偵測 */
    suspend fun tencent(texts: List<String>, target: TargetLang): List<String> {
        val payload = JSONObject()
            .put("header", JSONObject().put("fn", "auto_translation").put("client_key", "browser-chrome-140.0.0-Android-liquid"))
            .put("type", "plain")
            .put("model_category", "normal")
            .put("source", JSONObject().put("lang", "auto").put("text_list", JSONArray(texts)))
            .put("target", JSONObject().put("lang", target.tencent ?: target.google))
        val req = Request.Builder().url(TranslateEngine.Tencent.endpoint)
            .header("User-Agent", UA)
            .post(payload.toString().toRequestBody(JSON_TYPE))
            .build()
        return Http.exec(req) { r ->
            if (r.code != 200) throw IOException(t("騰訊翻譯 HTTP %1\$s", r.code))
            val res = JSONObject(r.body.string())
            val arr = res.optJSONArray("auto_translation") ?: throw IOException("騰訊翻譯：${res.optJSONObject("header")?.optString("ret_code")}")
            List(texts.size) { i -> arr.optString(i, texts[i]).ifEmpty { texts[i] } }
        }
    }

    /** 被限流（HTTP 429）時等一下再試：1.5 秒、3 秒 */
    private suspend fun google(texts: List<String>, target: TargetLang): List<String> {
        for (wait in listOf(1500L, 3000L)) {
            try { return googleOnce(texts, target) } catch (e: RateLimited) { kotlinx.coroutines.delay(wait) }
        }
        return googleOnce(texts, target)
    }

    private class RateLimited : IOException(t("Google 翻譯暫時限流，請稍後再試"))

    /** Google 公開端點：一次送多個 q，回傳每項的譯文（sl=auto 時每項為 [譯文, 偵測到的語言]） */
    private suspend fun googleOnce(texts: List<String>, target: TargetLang): List<String> {
        val form = FormBody.Builder(Charsets.UTF_8).apply { texts.forEach { add("q", it) } }.build()
        // 預設的 OkHttp User-Agent 會被導到「異常流量」頁（302／429），改用一般瀏覽器的 User-Agent
        val req = Request.Builder().url("${TranslateEngine.Free.endpoint}?client=gtx&sl=auto&tl=${target.google}")
            .header("User-Agent", UA)
            .post(form)
            .build()
        return Http.exec(req) { r ->
            if (r.code == 429 || r.code == 302) throw RateLimited()
            if (r.code != 200) throw IOException("Google 翻譯 HTTP ${r.code}")
            val text = r.body.string().trim()
            // 單一 q 時回傳字串或 [譯文, 語言]；多個 q 時回傳陣列
            if (texts.size == 1) {
                listOf(if (text.startsWith("[")) JSONArray(text).let { a -> (a.opt(0) as? JSONArray)?.optString(0) ?: a.optString(0) } else JSONArray("[$text]").optString(0))
            } else {
                val arr = JSONArray(text)
                List(texts.size) { i -> (arr.opt(i) as? JSONArray)?.optString(0) ?: arr.optString(i, texts[i]) }
            }
        }
    }

    /** OpenAI 相容的 Chat Completions（DeepSeek、千問、GLM） */
    private suspend fun llm(texts: List<String>, engine: TranslateEngine, key: String, model: String, target: TargetLang, onLine: ((Int, String) -> Unit)? = null): List<String> =
        try {
            // 百煉上的 GLM 不接受 enable_thinking=false（HTTP 400、沒有錯誤訊息）：GLM 不送這個參數
            llmOnce(texts, engine, key, model, target, thinkingOff = engine != TranslateEngine.Glm, onLine)
        } catch (e: IOException) {
            // 其他模型若也不接受關閉思考（400 或訊息提到 enable_thinking）：拿掉這個參數再試一次
            if (engine != TranslateEngine.Glm && (e.message?.contains("enable_thinking") == true || e.message?.contains("HTTP 400") == true))
                llmOnce(texts, engine, key, model, target, thinkingOff = false, onLine) else throw e
        }

    private suspend fun llmOnce(texts: List<String>, engine: TranslateEngine, key: String, model: String, target: TargetLang, thinkingOff: Boolean, onLine: ((Int, String) -> Unit)?): List<String> {
        val payload = JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
            // 串流：模型每寫完一行就先貼回網頁，不用等整批寫完（畫面內的段落排在最前面，最先出現）
            .put("stream", true)
            // 百煉上的模型預設會先思考：翻譯關掉思考（實測 qwen3.8-flash 4.4→0.7 秒）
            .apply { if (thinkingOff) put("enable_thinking", false) }
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", prompt(target)))
                    .put(JSONObject().put("role", "user").put("content", encodeLines(texts))),
            )
        val req = Request.Builder().url(engine.endpoint)
            .header("Authorization", "Bearer $key")
            .post(payload.toString().toRequestBody(JSON_TYPE))
            .build()
        val t0 = System.nanoTime(); var tFirst = 0L
        val content = StringBuilder()
        Http.exec(req) { r ->
            if (r.code != 200) {
                val err = r.body.string()
                val msg = runCatching { JSONObject(err).optJSONObject("error")?.optString("message") }.getOrNull()
                val hint = if (msg?.contains("Access denied", true) == true) t("（請先在阿里雲百煉控制台開通 %1\$s）", model) else ""
                throw IOException("${engine.label} HTTP ${r.code}${msg?.let { "：$it" }.orEmpty()}$hint")
            }
            var lineStart = 0
            r.body.charStream().buffered().useLines { sse ->
                for (ev in sse) {
                    if (!ev.startsWith("data:")) continue
                    val data = ev.substring(5).trim()
                    if (data == "[DONE]") break
                    val delta = runCatching { JSONObject(data).getJSONArray("choices").getJSONObject(0).optJSONObject("delta")?.optString("content") }.getOrNull()
                    if (delta.isNullOrEmpty() || delta == "null") continue
                    if (tFirst == 0L) tFirst = System.nanoTime()
                    content.append(delta)
                    // 寫完的行立刻回報
                    while (true) {
                        val nl = content.indexOf("\n", lineStart)
                        if (nl < 0) break
                        if (onLine != null) parseLine(content.substring(lineStart, nl), texts.size)?.let { (i, v) -> onLine(i, v) }
                        lineStart = nl + 1
                    }
                }
            }
        }
        // 以編號對回原文，不會錯位
        android.util.Log.i("OreaTr", "llm ${texts.size}段 in=${texts.sumOf { it.length }} out=${content.length} first=${(tFirst - t0) / 1_000_000}ms total=${(System.nanoTime() - t0) / 1_000_000}ms")
        val out = parseLines(content.toString(), texts.size).toMutableList()
        // 模型漏掉的段落改用免費引擎補翻
        val missing = out.indices.filter { out[it] == null }
        if (missing.isNotEmpty()) {
            val filled = runCatching { tencent(missing.map { texts[it] }, target) }.getOrNull()
            missing.forEachIndexed { k, i -> out[i] = filled?.getOrNull(k) ?: texts[i] }
        }
        return out.map { it!! }
    }
}
