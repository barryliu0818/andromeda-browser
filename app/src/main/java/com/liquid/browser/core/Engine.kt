package com.liquid.browser.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import com.liquid.browser.BugLog
import com.norman.webviewup.lib.WebViewReplace

/**
 * 網頁核心：Orea 自己編譯的 Chromium（另外安裝的 [PKG]）或系統 WebView。
 *
 * 用 WebViewUpgrade（app/src/main/java/com/norman/webviewup，Apache-2.0）把這個 App 行程裡 WebView 的底層換成 [PKG]，
 * 其他程式碼照樣用 android.webkit.WebView，功能不用改。必須在建立任何 WebView 之前呼叫 [install]；切換要重開 App 才生效。
 *
 * 防護：換核心前先記「嘗試中」，第一個網頁載入完成（[markHealthy]）或撐過 [SAFE_MS] 才清掉。上次開到一半就掛（旗標還在）
 * → 這次退回系統 WebView 並記錄，不會因為核心壞掉而一直閃退。（只等計時器的話，剛打開就滑掉 App 也會被誤判成閃退）
 */
object Engine {
    const val PKG = "com.liquid.orea.webview"
    private const val SAFE_MS = 15_000L

    /** 目前使用的核心（給設定畫面顯示） */
    var active: String = ""
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("engine", Context.MODE_PRIVATE)

    /** 使用者是否選用 Orea 核心（預設：有裝就用） */
    fun wanted(ctx: Context) = prefs(ctx).getBoolean("chromium", true)

    fun setWanted(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean("chromium", v).putBoolean("trying", false).commit()
    }

    /** Orea 核心的版本；沒安裝回傳 null */
    fun installedVersion(ctx: Context): String? =
        runCatching { ctx.packageManager.getPackageInfo(PKG, 0).versionName }.getOrNull()

    @Volatile private var healthy = false

    /** 核心已經正常載入過網頁：清掉「嘗試中」（每次啟動只寫一次） */
    fun markHealthy(ctx: Context) {
        if (healthy) return
        healthy = true
        prefs(ctx).edit().putBoolean("trying", false).apply()
    }

    fun install(ctx: Context) {
        val p = prefs(ctx)
        val info = runCatching { ctx.packageManager.getPackageInfo(PKG, 0) }.getOrNull()
        when {
            !wanted(ctx) || info == null -> Unit
            p.getBoolean("trying", false) -> {
                // 上次換了核心後沒撐過 SAFE_MS 就結束（多半是閃退）：這次先用系統 WebView
                p.edit().putBoolean("trying", false).commit()
                BugLog.auto("engine_crash", "上次使用 Orea 核心 ${info.versionName} 後很快就結束，這次退回系統 WebView")
            }
            else -> {
                p.edit().putBoolean("trying", true).commit()
                runCatching { WebViewReplace.replace(ctx.applicationContext, info) }
                    .onSuccess {
                        active = "Orea Chromium ${info.versionName}"
                        Handler(Looper.getMainLooper()).postDelayed({ p.edit().putBoolean("trying", false).apply() }, SAFE_MS)
                    }
                    .onFailure {
                        p.edit().putBoolean("trying", false).commit()
                        BugLog.auto("engine_fail", "換 Orea 核心失敗：$it")
                    }
            }
        }
        if (active.isEmpty()) active = "系統 WebView ${WebView.getCurrentWebViewPackage()?.versionName.orEmpty()}"
        BugLog.event("engine", active)
    }
}
