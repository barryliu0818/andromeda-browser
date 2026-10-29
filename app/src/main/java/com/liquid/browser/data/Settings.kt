package com.liquid.browser.data

import com.liquid.browser.BuildConfig
import com.liquid.browser.browser.TranslateEngine
import com.liquid.browser.browser.TargetLang

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 搜尋引擎：名稱、搜尋網址前綴。 */
enum class SearchEngine(val label: String, val prefix: String) {
    Google("Google", "https://www.google.com/search?q="),
    Bing("Bing", "https://www.bing.com/search?q="),
    DuckDuckGo("DuckDuckGo", "https://duckduckgo.com/?q="),
    Yandex("Yandex", "https://yandex.com/search/?text="),
}

@Immutable
data class Settings(
    val engine: SearchEngine = SearchEngine.Google,
    val desktopByDefault: Boolean = false,
    val nativePlayer: Boolean = true,
    val reduceTransparency: Boolean = false,
    /** 起始頁自訂背景圖（content:// URI），null 為預設淺灰／近黑 */
    val startBackground: String? = null,
    /** 讓網站把本 App 識別為 iPhone Safari（User-Agent、Client Hints、navigator.platform） */
    val identifyAsIPhone: Boolean = true,
    /** 首頁點標誌切換的兩個引擎（四選二） */
    val quickEngines: List<SearchEngine> = listOf(SearchEngine.Google, SearchEngine.Bing),
    /** 網頁翻譯引擎與各引擎的 API 金鑰、模型名稱（空白＝用預設模型） */
    val translateEngine: TranslateEngine = DefaultKeys.keys.firstOrNull() ?: TranslateEngine.Tencent,
    /** 翻譯目標語言 */
    val translateTarget: TargetLang = TargetLang.ZhTW,
    val translateKeys: Map<TranslateEngine, String> = emptyMap(),
    val translateModels: Map<TranslateEngine, String> = emptyMap(),
    /** 每晚 22:00～次日 08:00 優先用 DeepSeek（夜間半價） */
    val nightDeepSeek: Boolean = true,
    /** 阻擋廣告與追蹤（類似 AdGuard）；adAllow 為不阻擋的網站 */
    val adBlock: Boolean = true,
    val adAllow: Set<String> = emptySet(),
    /** 阻止網頁未經點擊就自動跳轉到其他網站（含彈出視窗） */
    val blockRedirects: Boolean = true,
    /** 網址列上方顯示「下載影片」按鈕（偵測到影片時） */
    val videoDownloadButton: Boolean = true,
    /** 網址列版面（直向時；橫向一律在頂端）：精簡／底部／頂部，同 Safari */
    val barLayout: BarLayout = BarLayout.Compact,
    /** 封鎖第三方 Cookie（嵌在別的網站裡、用來跨網站追蹤的 Cookie） */
    val blockThirdPartyCookies: Boolean = true,
    /** 所有瀏覽都拿掉網址上的追蹤參數（關閉時只有無痕分頁拿掉，同 Safari） */
    val cleanLinksEverywhere: Boolean = false,
    /** 介面語言 */
    val appLang: com.liquid.browser.core.AppLang = com.liquid.browser.core.AppLang.System,
)

/** 編譯時從 local.properties 帶入的預設金鑰（沒有就不放）；順序即預設引擎的優先順序 */
private val DefaultKeys: Map<TranslateEngine, String> = linkedMapOf(
    TranslateEngine.Qwen to BuildConfig.QWEN_API_KEY,
).filterValues { it.isNotEmpty() }

private val Context.store by preferencesDataStore("settings")

/** 設定（DataStore）：讀取為 Flow，寫入為 suspend，皆不在主執行緒做 I/O。 */
class SettingsStore(private val context: Context) {
    private object K {
        val engine = stringPreferencesKey("engine")
        val desktop = booleanPreferencesKey("desktop")
        val native = booleanPreferencesKey("native_player")
        val reduce = booleanPreferencesKey("reduce_transparency")
        val bg = stringPreferencesKey("start_background")
        val iphone = booleanPreferencesKey("identify_as_iphone")
        val quick = stringPreferencesKey("quick_engines")
        val trEngine = stringPreferencesKey("translate_engine")
        val night = booleanPreferencesKey("night_deepseek")
        val target = stringPreferencesKey("translate_target")
        val adBlock = booleanPreferencesKey("ad_block")
        val adAllow = stringSetPreferencesKey("ad_allow")
        val redirects = booleanPreferencesKey("block_redirects")
        val videoDl = booleanPreferencesKey("video_download_button")
        val appLang = stringPreferencesKey("app_lang")
        val barTop = booleanPreferencesKey("address_bar_top")
        val layout = stringPreferencesKey("bar_layout")
        val tpc = booleanPreferencesKey("block_third_party_cookies")
        val cleanAll = booleanPreferencesKey("clean_links_everywhere")
        fun trKey(e: TranslateEngine) = stringPreferencesKey("translate_key_${e.name}")
        fun trModel(e: TranslateEngine) = stringPreferencesKey("translate_model_${e.name}")
    }

    private fun Preferences.toSettings() = Settings(
        engine = this[K.engine]?.let { runCatching { SearchEngine.valueOf(it) }.getOrNull() } ?: SearchEngine.Google,
        desktopByDefault = this[K.desktop] ?: false,
        nativePlayer = this[K.native] ?: true,
        reduceTransparency = this[K.reduce] ?: false,
        startBackground = this[K.bg],
        identifyAsIPhone = this[K.iphone] ?: true,
        quickEngines = this[K.quick]?.split(',')?.mapNotNull { runCatching { SearchEngine.valueOf(it) }.getOrNull() }
            ?.distinct()?.takeIf { it.size == 2 } ?: listOf(SearchEngine.Google, SearchEngine.Bing),
        translateEngine = this[K.trEngine]?.let { runCatching { TranslateEngine.valueOf(it) }.getOrNull() } ?: Settings().translateEngine,
        // ponytail: 金鑰以明文存在 App 私有的 DataStore（其他 App 讀不到）；需要更高保護時改用 Android Keystore 加密
        translateKeys = TranslateEngine.entries.mapNotNull { e -> this[K.trKey(e)]?.let { e to it } }.toMap()
            // 內建預設金鑰只在使用者從沒設定過時套用；使用者刪除後存的是空字串，不會再冒出預設金鑰
            .let { m -> DefaultKeys.filterKeys { m[it] == null } + m },
        translateModels = TranslateEngine.entries.mapNotNull { e -> this[K.trModel(e)]?.let { e to it } }.toMap(),
        nightDeepSeek = this[K.night] ?: true,
        translateTarget = this[K.target]?.let { runCatching { TargetLang.valueOf(it) }.getOrNull() } ?: TargetLang.ZhTW,
        adBlock = this[K.adBlock] ?: true,
        adAllow = this[K.adAllow] ?: emptySet(),
        blockRedirects = this[K.redirects] ?: true,
        videoDownloadButton = this[K.videoDl] ?: true,
        // 舊版只有「網址列在頂部」開關：開著的當成頂部版面
        barLayout = this[K.layout]?.let { runCatching { BarLayout.valueOf(it) }.getOrNull() } ?: if (this[K.barTop] == true) BarLayout.Top else BarLayout.Compact,
        blockThirdPartyCookies = this[K.tpc] ?: true,
        cleanLinksEverywhere = this[K.cleanAll] ?: false,
        appLang = this[K.appLang]?.let { runCatching { com.liquid.browser.core.AppLang.valueOf(it) }.getOrNull() } ?: com.liquid.browser.core.AppLang.System,
    )

    val flow: Flow<Settings> = context.store.data.map { it.toSettings() }

    suspend fun update(transform: (Settings) -> Settings) {
        context.store.edit { p ->
            val n = transform(p.toSettings())
            p[K.engine] = n.engine.name
            p[K.desktop] = n.desktopByDefault
            p[K.native] = n.nativePlayer
            p[K.reduce] = n.reduceTransparency
            p[K.iphone] = n.identifyAsIPhone
            p[K.quick] = n.quickEngines.joinToString(",") { it.name }
            p[K.trEngine] = n.translateEngine.name
            p[K.night] = n.nightDeepSeek
            p[K.target] = n.translateTarget.name
            p[K.adBlock] = n.adBlock
            p[K.adAllow] = n.adAllow
            p[K.redirects] = n.blockRedirects
            p[K.videoDl] = n.videoDownloadButton
            p[K.layout] = n.barLayout.name
            p.remove(K.barTop)
            p[K.tpc] = n.blockThirdPartyCookies
            p[K.cleanAll] = n.cleanLinksEverywhere
            p[K.appLang] = n.appLang.name
            TranslateEngine.entries.forEach { e ->
                n.translateKeys[e]?.let { p[K.trKey(e)] = it } ?: p.remove(K.trKey(e))
                n.translateModels[e]?.takeIf { it.isNotBlank() }?.let { p[K.trModel(e)] = it } ?: p.remove(K.trModel(e))
            }
            if (n.startBackground == null) p.remove(K.bg) else p[K.bg] = n.startBackground
        }
    }
}

/** 網址列版面（Safari 的三種「標籤頁」佈局） */
enum class BarLayout {
    /** 精簡：浮在底部的一列——返回鈕、網址列、分頁鈕 */
    Compact,
    /** 底部：底部一整條工具區——網址列，下面一排返回／前進／分享／書籤／分頁 */
    Bottom,
    /** 頂部：網址列在頂端一整條，底部一排返回／前進／分享／書籤／分頁 */
    Top,
}
