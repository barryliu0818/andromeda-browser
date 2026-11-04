package com.liquid.browser.browser

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.liquid.browser.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Orea 帳號：郵箱＋驗證碼註冊／登入（Supabase Auth 的 email OTP，免密碼）。
 *  - 註冊：送驗證碼到郵箱（create_user），輸入正確的驗證碼才算註冊成功
 *  - 登入：只送給已註冊的郵箱
 * 登入狀態（refresh token）用 Android Keystore 的硬體金鑰加密存在 App 私有資料夾；開 App 時在背景換新的 token，
 * 伺服器說帳號已失效才登出（沒網路時維持登入）。
 * 服務網址與公開金鑰（anon key）從 local.properties 的 SUPABASE_URL、SUPABASE_ANON_KEY 編進 App，不進 git。
 * ponytail: 只在 App 端擋功能（AI 翻譯、安裝擴充），翻譯 API 不經過自己的伺服器；要防破解改版就得讓翻譯走伺服器驗 token
 */
object Account {
    /** 已登入的郵箱；null＝未登入 */
    var email by mutableStateOf<String?>(null)
        private set
    val signedIn: Boolean get() = email != null
    /** App 有編入帳號服務的設定 */
    val configured: Boolean get() = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    private const val ALIAS = "orea_account"
    private lateinit var file: File

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "account.enc")
        email = runCatching { JSONObject(decrypt(file.readBytes())).getString("email") }.getOrNull()
    }

    /** 開 App 時換新 token：伺服器回 400／401（帳號被刪、token 被撤銷）就登出，其他錯誤（沒網路）維持登入 */
    suspend fun refresh() {
        if (!signedIn || !configured) return
        val saved = runCatching { JSONObject(decrypt(file.readBytes())) }.getOrNull() ?: return
        runCatching { post("token?grant_type=refresh_token", JSONObject().put("refresh_token", saved.getString("refresh_token"))) }
            .onSuccess { save(it) }
            .onFailure { if (it is HttpError && it.code in 400..401) signOut() }
    }

    /**
     * 送驗證碼。register＝true：新郵箱建立帳號；false：只送給已註冊的郵箱。
     * 失敗丟出例外，訊息可直接顯示（經過 t() 翻譯）
     */
    suspend fun sendCode(address: String, register: Boolean) {
        check(configured) { "尚未設定帳號服務" }
        try {
            post("otp", JSONObject().put("email", address).put("create_user", register))
        } catch (e: HttpError) {
            throw IllegalStateException(friendly(e, register))
        }
    }

    /** 驗證碼正確：登入（註冊時也就是註冊成功） */
    suspend fun verify(address: String, code: String) {
        check(configured) { "尚未設定帳號服務" }
        val res = try {
            post("verify", JSONObject().put("type", "email").put("email", address).put("token", code))
        } catch (e: HttpError) {
            throw IllegalStateException(friendly(e, false))
        }
        save(res)
    }

    fun signOut() {
        runCatching { file.delete() }
        email = null
    }

    private fun save(session: JSONObject) {
        val mail = session.optJSONObject("user")?.optString("email")?.takeIf { it.isNotBlank() } ?: email ?: return
        val data = JSONObject().put("email", mail).put("refresh_token", session.getString("refresh_token"))
        file.writeBytes(encrypt(data.toString().toByteArray()))
        email = mail
    }

    // 伺服器錯誤 → 使用者看得懂的說明（GoTrue 的 error_code）
    private fun friendly(e: HttpError, register: Boolean): String {
        val code = runCatching { JSONObject(e.body) }.getOrNull()?.let { it.optString("error_code").ifBlank { it.optString("code") } }.orEmpty()
        val msg = runCatching { JSONObject(e.body) }.getOrNull()?.let { it.optString("msg").ifBlank { it.optString("error_description") } }.orEmpty()
        return when {
            e.code == 429 || code.contains("rate_limit") -> "傳送太頻繁，請稍後再試"
            code == "otp_expired" -> "驗證碼錯誤或已過期"
            code == "otp_disabled" || code == "signup_disabled" || (!register && msg.contains("Signups not allowed", true)) -> "這個郵箱還沒註冊"
            code == "validation_failed" || code == "email_address_invalid" -> "郵箱格式不正確"
            else -> msg.ifBlank { "伺服器錯誤（HTTP ${e.code}）" }
        }
    }

    class HttpError(val code: Int, val body: String) : Exception("HTTP $code")

    private suspend fun post(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val conn = (URL(BuildConfig.SUPABASE_URL.trimEnd('/') + "/auth/v1/" + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val ok = conn.responseCode in 200..299
            val text = (if (ok) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (!ok) throw HttpError(conn.responseCode, text)
            if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally { conn.disconnect() }
    }

    // Keystore 的 AES-256-GCM（同 PasswordVault）；檔案格式：12 位元組 IV＋密文
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return c.iv + c.doFinal(plain)
    }

    private fun decrypt(bytes: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12)) }
        return String(c.doFinal(bytes, 12, bytes.size - 12))
    }
}
