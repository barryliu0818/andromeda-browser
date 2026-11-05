package com.liquid.browser.browser

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 一組儲存的帳密：origin 為「https://網域」（只在同一個網站填入） */
data class Credential(val origin: String, val user: String, val pass: String)

/**
 * 密碼保險箱：整份清單用 Android Keystore 裡的硬體金鑰（AES-256-GCM）加密，存在 App 私有資料夾。
 * 金鑰拿不出手機，檔案被複製走也解不開；其他 App 讀不到私有資料夾。
 * 填入密碼前要先過指紋／螢幕鎖驗證（[Auth]），這裡只負責存取。
 * ponytail: 金鑰沒有綁定「驗證後才能用」（setUserAuthenticationRequired），儲存時才不必每次都驗證；
 * 要更嚴格時改成綁定驗證、每次加解密前先過 BiometricPrompt（CryptoObject）。
 */
object PasswordVault {
    private const val ALIAS = "andromeda_passwords"
    private lateinit var file: File
    private var cache: MutableList<Credential>? = null

    fun init(context: Context) { file = File(context.filesDir, "passwords.enc") }

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

    /** 檔案格式：12 位元組 IV＋密文（JSON 陣列） */
    @Synchronized
    private fun load(): MutableList<Credential> {
        cache?.let { return it }
        val list = runCatching {
            val bytes = file.readBytes()
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12)) }
            val arr = JSONArray(String(c.doFinal(bytes, 12, bytes.size - 12)))
            MutableList(arr.length()) { i -> arr.getJSONObject(i).let { Credential(it.getString("o"), it.getString("u"), it.getString("p")) } }
        }.getOrElse { mutableListOf() }
        cache = list
        return list
    }

    @Synchronized
    private fun persist(list: List<Credential>) {
        val json = JSONArray(list.map { JSONObject().put("o", it.origin).put("u", it.user).put("p", it.pass) }).toString()
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(c.iv + c.doFinal(json.toByteArray()))
        tmp.renameTo(file)
    }

    @Synchronized fun all(): List<Credential> = load().toList()

    @Synchronized fun forOrigin(origin: String): List<Credential> = load().filter { it.origin == origin }

    /** 同一網站同一帳號只留一筆（更新密碼）；回傳 true＝新增或有改變 */
    @Synchronized
    fun save(c: Credential): Boolean {
        val list = load()
        val old = list.indexOfFirst { it.origin == c.origin && it.user == c.user }
        if (old >= 0 && list[old].pass == c.pass) return false
        if (old >= 0) list[old] = c else list.add(c)
        persist(list)
        return true
    }

    @Synchronized
    fun delete(c: Credential) {
        val list = load()
        if (list.removeAll { it.origin == c.origin && it.user == c.user }) persist(list)
    }

    /** 高強度密碼（Safari 樣式）：xxxxxx-xxxxxx-xxxxxx，大小寫字母＋數字，約 71 bits */
    fun strongPassword(): String {
        val r = java.security.SecureRandom()
        val chars = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val groups = List(3) { String(CharArray(6) { chars[r.nextInt(chars.length)] }) }.toMutableList()
        // 保證有大寫與數字（網站常見的密碼規則）
        groups[0] = groups[0].replaceRange(0, 1, chars[r.nextInt(24) + 25].toString())
        groups[2] = groups[2].replaceRange(5, 6, (r.nextInt(8) + 2).toString())
        return groups.joinToString("-")
    }
}
