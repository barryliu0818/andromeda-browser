package com.liquid.browser.browser

/**
 * Brave 的 adblock-rust（native/adblock → liboreaadblock.so）。完整的 Adblock Plus／uBlock 語法：
 * 路徑規則、$third-party、$domain=、資源類型、例外規則，以及各網站專屬的元素隱藏規則。
 * 載入失敗（例如 ABI 不支援）時 [available] 為 false，AdBlocker 退回只比對網域。
 */
object AdEngine {
    val available: Boolean = runCatching { System.loadLibrary("oreaadblock") }
        .onFailure { com.liquid.browser.BugLog.event("adblock", "原生引擎載入失敗：$it") }.isSuccess

    @JvmStatic external fun nativeBuild(text: String): Boolean
    @JvmStatic external fun nativeSerialize(): ByteArray?
    @JvmStatic external fun nativeLoad(data: ByteArray): Boolean
    /** 0 放行、1 阻擋 */
    @JvmStatic external fun nativeCheck(url: String, source: String, type: String): Int
    /** JSON：hide_selectors、injected_script、generichide… */
    @JvmStatic external fun nativeCosmetic(url: String): String
}
