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
 * Orea 帳號（Supabase Auth）：郵箱＋密碼。
 *  - 註冊：郵箱＋密碼（輸入兩次）→ 寄驗證碼到郵箱，輸入正確的驗證碼才算註冊成功
 *  - 登入：郵箱＋密碼，不用驗證碼；忘記密碼：郵箱驗證碼 → 設定新密碼
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

    /** 密碼規則：至少 8 個字元，要有英文字母也要有數字；符合回傳 null，否則回傳說明 */
    fun passwordProblem(pw: String): String? =
        if (pw.length >= 8 && pw.any { it in 'a'..'z' || it in 'A'..'Z' } && pw.any(Char::isDigit)) null
        else "密碼至少 8 個字元，要有英文字母和數字"

    // 所有請求：失敗丟出 IllegalStateException，訊息可直接顯示（經過 t() 翻譯）
    private suspend fun call(path: String, body: JSONObject, method: String = "POST", bearer: String? = null): JSONObject {
        check(configured) { "尚未設定帳號服務" }
        return try { post(path, body, method, bearer) } catch (e: HttpError) { throw IllegalStateException(friendly(e)) }
    }

    /** 登入：郵箱＋密碼（不用驗證碼） */
    suspend fun signIn(address: String, password: String) =
        save(call("token?grant_type=password", JSONObject().put("email", address).put("password", password)))

    /** 註冊第一步：建立帳號並寄驗證碼到郵箱（帳號要等驗證碼正確才啟用） */
    suspend fun startSignUp(address: String, password: String) {
        val res = call("signup", JSONObject().put("email", address).put("password", password))
        // 郵箱已經註冊過：伺服器為了不洩漏帳號是否存在，回一個沒有登入方式（identities 空）的假帳號、也不寄信
        val identities = (res.optJSONObject("user") ?: res).optJSONArray("identities")
        if (identities != null && identities.length() == 0) throw IllegalStateException("這個郵箱已經註冊，請直接登入")
    }

    /** 註冊第二步：驗證碼正確才算註冊成功，同時登入 */
    suspend fun finishSignUp(address: String, code: String) =
        save(call("verify", JSONObject().put("type", "signup").put("email", address).put("token", code)))

    /** 忘記密碼第一步：寄驗證碼到郵箱 */
    suspend fun startReset(address: String) { call("recover", JSONObject().put("email", address)) }

    /** 忘記密碼第二步：驗證碼正確後設定新密碼，並登入 */
    suspend fun finishReset(address: String, code: String, newPassword: String) {
        val session = call("verify", JSONObject().put("type", "recovery").put("email", address).put("token", code))
        call("user", JSONObject().put("password", newPassword), "PUT", session.getString("access_token"))
        save(session)
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
    private fun friendly(e: HttpError): String {
        val json = runCatching { JSONObject(e.body) }.getOrNull()
        val code = json?.let { it.optString("error_code").ifBlank { it.optString("error") } }.orEmpty()
        val msg = json?.let { it.optString("msg").ifBlank { it.optString("error_description") } }.orEmpty()
        return when {
            e.code == 429 || code.contains("rate_limit") -> "傳送太頻繁，請稍後再試"
            code == "otp_expired" -> "驗證碼錯誤或已過期"
            code == "invalid_credentials" || msg.contains("Invalid login credentials", true) -> "郵箱或密碼不正確"
            code == "email_not_confirmed" -> "這個郵箱還沒完成驗證，請重新註冊取得驗證碼"
            code == "user_already_exists" || code == "email_exists" -> "這個郵箱已經註冊，請直接登入"
            code == "weak_password" -> "密碼太簡單"
            code == "same_password" -> "新密碼不能和舊密碼一樣"
            code == "validation_failed" || code == "email_address_invalid" -> "郵箱格式不正確"
            else -> msg.ifBlank { "伺服器錯誤（HTTP ${e.code}）" }
        }
    }

    class HttpError(val code: Int, val body: String) : Exception("HTTP $code")

    private suspend fun post(path: String, body: JSONObject, method: String = "POST", bearer: String? = null): JSONObject = withContext(Dispatchers.IO) {
        val conn = (URL(BuildConfig.SUPABASE_URL.trimEnd('/') + "/auth/v1/" + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            bearer?.let { setRequestProperty("Authorization", "Bearer $it") }
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
