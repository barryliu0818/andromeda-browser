package com.liquid.browser.browser

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/**
 * 跳轉 App 的提示：ask＝網頁自己觸發（沒有點擊）的跳轉，先問；suggest＝目前網址有對應的 App，可改用 App 開啟。
 */
@Immutable
data class AppPrompt(val tab: Tab, val intent: Intent, val label: String, val icon: ImageBitmap?, val ask: Boolean)

/** App 專用連結（taobao://、weixin://、intent://…）與一般網址對應的 App。 */
object AppLinks {
    /** 留在瀏覽器裡處理的協定，其他都視為 App 連結 */
    private val WEB = setOf("http", "https", "about", "data", "javascript", "blob", "file", "content")

    fun isAppScheme(uri: Uri) = uri.scheme?.lowercase()?.let { it !in WEB } ?: false

    /**
     * 連結轉成可安全開啟的 Intent：intent:// 照 Chrome 的規則拿掉指定元件、選擇器與檔案授權，
     * 只能開 BROWSABLE 的畫面，網頁不能藉此打開 App 內部未公開的頁面。
     */
    fun toIntent(url: String): Intent? = runCatching {
        val intent = if (url.startsWith("intent:", ignoreCase = true)) Intent.parseUri(url, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, Uri.parse(url))
        intent.apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            component = null
            selector = null
            flags = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION).inv()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }.getOrNull()

    /** intent:// 附的備用網址（沒裝 App 時改開網頁） */
    fun fallbackUrl(intent: Intent) = intent.getStringExtra("browser_fallback_url")?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

    /** 能開這個 Intent 的 App（不含自己）；沒有則 null */
    fun target(context: Context, intent: Intent): AppTarget? {
        val pm = context.packageManager
        val ri = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).firstOrNull { it.activityInfo.packageName != context.packageName } ?: return null
        return AppTarget(ri.loadLabel(pm).toString(), runCatching { ri.loadIcon(pm).toBitmap(96, 96).asImageBitmap() }.getOrNull())
    }

    /**
     * 一般網址的專屬 App（例如淘寶、知乎、YouTube）：篩選條件指名這個網域的 App。
     * 瀏覽器只比對到協定（任何網址都接），不算；本機位址（localhost、IP）不提示——
     * 不少 App 用 http://localhost 接 OAuth 登入回呼（例如釘釘郵箱），不是真的能開這個網站。
     */
    fun nativeAppFor(context: Context, url: String): Intent? {
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase() ?: return null
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || ':' in host || host.all { it.isDigit() || it == '.' }) return null
        val view = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        val ri = context.packageManager.queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY).firstOrNull {
            it.activityInfo.packageName != context.packageName && (it.match and IntentFilter.MATCH_CATEGORY_MASK) >= IntentFilter.MATCH_CATEGORY_HOST
        } ?: return null
        return view.setPackage(ri.activityInfo.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

@Immutable
data class AppTarget(val label: String, val icon: ImageBitmap?)
