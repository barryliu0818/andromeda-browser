package com.liquid.browser.browser

import android.webkit.CookieManager
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature

/** 下載相關的共用工具（實際下載由 [DownloadEngine] 處理）。 */
object Downloads {
    /** 分頁所屬設定檔的 Cookie（無痕分頁用獨立的設定檔） */
    fun cookieManager(incognito: Boolean): CookieManager =
        if (incognito && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
            ProfileStore.getInstance().getProfile(INCOGNITO_PROFILE)?.cookieManager ?: CookieManager.getInstance()
        else CookieManager.getInstance()
}
