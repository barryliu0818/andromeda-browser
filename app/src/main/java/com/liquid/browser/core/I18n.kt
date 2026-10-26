package com.liquid.browser.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** 介面語言（設定 → 介面語言）；System 依手機語言決定 */
enum class AppLang(val nativeName: String) {
    System("跟隨系統"),
    ZhTW("繁體中文"),
    ZhCN("简体中文"),
    En("English"),
    Ja("日本語"),
}

/**
 * 介面文字翻譯：程式中的文字以繁體中文撰寫並當作鍵，t() 依目前語言查表（I18nTables.kt），
 * 查不到就顯示原文。lang 是 Compose 狀態，切換語言時讀過 t() 的畫面會立即重組更新。
 */
object I18n {
    var lang by mutableStateOf(AppLang.ZhTW)
        private set

    fun apply(pref: AppLang, locale: Locale = Locale.getDefault()) {
        lang = if (pref != AppLang.System) pref else when {
            locale.language == "zh" && (locale.script == "Hans" || locale.country in setOf("CN", "SG")) -> AppLang.ZhCN
            locale.language == "zh" -> AppLang.ZhTW
            locale.language == "ja" -> AppLang.Ja
            else -> AppLang.En
        }
    }
}

/** 翻譯介面文字；帶參數時以 String.format 代入（%1$s…） */
fun t(key: String, vararg args: Any?): String {
    val text = when (I18n.lang) {
        AppLang.ZhCN -> ZH_CN[key]
        AppLang.En -> EN[key]
        AppLang.Ja -> JA[key]
        else -> null
    } ?: key
    return if (args.isEmpty()) text else runCatching { String.format(text, *args) }.getOrDefault(text)
}
