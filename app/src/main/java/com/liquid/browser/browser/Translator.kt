package com.liquid.browser.browser

import com.liquid.browser.core.t

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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
    // 需先在百煉控制台開通 glm-5.3-flash，否則會回「Access denied」（此時自動改用免費翻譯）
    Glm("智譜 GLM-5.3 Flash", BAILIAN, "glm-5.3-flash", ""),
    ;

    /** 顯示名稱（依介面語言翻譯） */
    val label: String get() = t(labelKey)

    /** 免金鑰的免費引擎 */
    val isFree: Boolean get() = this == Tencent || this == Free

    /** 金鑰歸屬：三個 AI 引擎都經由阿里雲百煉呼叫，共用同一把金鑰（存在 Qwen 名下） */
    val keyOwner: TranslateEngine get() = if (isFree) this else Qwen
}

/** 翻譯目標語言：各引擎使用的語言代碼與給 AI 的語言描述 */
enum class TargetLang(val label: String, val google: String, val tencent: String, val prompt: String) {
    ZhTW("繁體中文", "zh-TW", "zh-TW", "繁體中文（台灣用語）"),
    ZhCN("簡體中文", "zh-CN", "zh", "简体中文"),
    En("English", "en", "en", "English"),
    Ja("日本語", "ja", "ja", "日本語"),
}

object Translator {
    private fun prompt(target: TargetLang) =
        "你是專業的網頁翻譯。使用者會給一個 JSON 物件，鍵是編號、值是網頁上的一段文字。" +
            "每一個值都必須翻譯成${target.prompt}，包括常見的詞句，回傳鍵完全相同的 JSON 物件，不要任何說明或 Markdown。" +
            "網址、程式碼、數字、單位與專有名稱維持原樣；已經是${target.prompt}的值原樣回傳。"

    suspend fun translate(texts: List<String>, engine: TranslateEngine, key: String, model: String, target: TargetLang): List<String> =
        withContext(Dispatchers.IO) {
            when {
                engine == TranslateEngine.Tencent -> tencent(texts, target)
                // Google 在中國連不上：失敗時改用騰訊
                engine == TranslateEngine.Free -> runCatching { google(texts, target) }.getOrElse { tencent(texts, target) }
                // AI 引擎沒填金鑰：用騰訊免費翻譯
                key.isBlank() -> tencent(texts, target)
                else -> llm(texts, engine, key, model.ifBlank { engine.model }, target)
            }
        }

    /** 騰訊交互翻譯（TranSmart）：一次送多段，來源語言自動偵測 */
    fun tencent(texts: List<String>, target: TargetLang): List<String> {
        val payload = JSONObject()
            .put("header", JSONObject().put("fn", "auto_translation").put("client_key", "browser-chrome-140.0.0-Android-liquid"))
            .put("type", "plain")
            .put("model_category", "normal")
            .put("source", JSONObject().put("lang", "auto").put("text_list", JSONArray(texts)))
            .put("target", JSONObject().put("lang", target.tencent))
        val conn = (URL(TranslateEngine.Tencent.endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36")
        }
        try {
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            if (conn.responseCode != 200) throw IOException(t("騰訊翻譯 HTTP %1\$s", conn.responseCode))
            val res = JSONObject(conn.inputStream.bufferedReader().readText())
            val arr = res.optJSONArray("auto_translation") ?: throw IOException("騰訊翻譯：${res.optJSONObject("header")?.optString("ret_code")}")
            return List(texts.size) { i -> arr.optString(i, texts[i]).ifEmpty { texts[i] } }
        } finally { conn.disconnect() }
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
    private fun googleOnce(texts: List<String>, target: TargetLang): List<String> {
        val body = texts.joinToString("&") { "q=" + URLEncoder.encode(it, "UTF-8") }
        val conn = (URL("${TranslateEngine.Free.endpoint}?client=gtx&sl=auto&tl=${target.google}").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
            // 預設的 Dalvik User-Agent 會被導到「異常流量」頁（302／429），改用一般瀏覽器的 User-Agent
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode == 429 || conn.responseCode == 302) throw RateLimited()
            if (conn.responseCode != 200) throw IOException("Google 翻譯 HTTP ${conn.responseCode}")
            val text = conn.inputStream.bufferedReader().readText().trim()
            // 單一 q 時回傳字串或 [譯文, 語言]；多個 q 時回傳陣列
            if (texts.size == 1) {
                return listOf(if (text.startsWith("[")) JSONArray(text).let { a -> (a.opt(0) as? JSONArray)?.optString(0) ?: a.optString(0) } else JSONArray("[$text]").optString(0))
            }
            val arr = JSONArray(text)
            return List(texts.size) { i -> (arr.opt(i) as? JSONArray)?.optString(0) ?: arr.optString(i, texts[i]) }
        } finally { conn.disconnect() }
    }

    /** OpenAI 相容的 Chat Completions（DeepSeek、千問、GLM） */
    private fun llm(texts: List<String>, engine: TranslateEngine, key: String, model: String, target: TargetLang): List<String> =
        try {
            llmOnce(texts, engine, key, model, target, thinkingOff = true)
        } catch (e: IOException) {
            // 有些模型必須思考、不接受關閉：拿掉這個參數再試一次
            if (e.message?.contains("enable_thinking") == true) llmOnce(texts, engine, key, model, target, thinkingOff = false) else throw e
        }

    private fun llmOnce(texts: List<String>, engine: TranslateEngine, key: String, model: String, target: TargetLang, thinkingOff: Boolean): List<String> {
        val payload = JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
            .put("stream", false)
            // 百煉上的模型預設會先思考：翻譯關掉思考（實測 qwen3.8-flash 4.4→0.7 秒）
            .apply { if (thinkingOff) put("enable_thinking", false) }
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", prompt(target)))
                    .put(JSONObject().put("role", "user").put("content", JSONObject().apply { texts.forEachIndexed { i, t -> put("$i", t) } }.toString())),
            )
        val conn = (URL(engine.endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $key")
        }
        try {
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            val code = conn.responseCode
            if (code != 200) {
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                val msg = runCatching { JSONObject(err).optJSONObject("error")?.optString("message") }.getOrNull()
                val hint = if (msg?.contains("Access denied", true) == true) t("（請先在阿里雲百煉控制台開通 %1\$s）", model) else ""
                throw IOException("${engine.label} HTTP $code${msg?.let { "：$it" }.orEmpty()}$hint")
            }
            val content = JSONObject(conn.inputStream.bufferedReader().readText())
                .getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            // 以編號對回原文，不會錯位；模型偶爾會包 ```json：取第一個 { 到最後一個 }
            val obj = runCatching { JSONObject(content.substring(content.indexOf('{'), content.lastIndexOf('}') + 1)) }.getOrNull() ?: JSONObject()
            val out = MutableList<String?>(texts.size) { i -> obj.optString("$i").takeIf { it.isNotEmpty() } }
            // 模型漏掉的段落改用免費引擎補翻
            val missing = out.indices.filter { out[it] == null }
            if (missing.isNotEmpty()) {
                val filled = runCatching { tencent(missing.map { texts[it] }, target) }.getOrNull()
                missing.forEachIndexed { k, i -> out[i] = filled?.getOrNull(k) ?: texts[i] }
            }
            return out.map { it!! }
        } finally { conn.disconnect() }
    }
}
