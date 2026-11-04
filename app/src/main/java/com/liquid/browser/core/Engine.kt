package com.liquid.browser.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import com.liquid.browser.BugLog
import com.norman.webviewup.lib.WebViewReplace
import com.norman.webviewup.lib.util.ApksUtils
import java.io.File

/**
 * 網頁核心：Orea 自己編譯的 Chromium，或系統 WebView。
 *
 * 用 WebViewUpgrade（app/src/main/java/com/norman/webviewup，Apache-2.0）把這個 App 行程裡 WebView 的底層換成 Orea 核心，
 * 其他程式碼照樣用 android.webkit.WebView，功能不用改。必須在建立任何 WebView 之前呼叫 [install]；切換要重開 App 才生效。
 *
 * 核心從哪來（依序）：
 *  1. 另外安裝的 [PKG]（開發測試用）
 *  2. 打包在 App 裡的核心（assets/orea_core/core.bin，見 build.gradle 的 OREA_CORE_DIR）：第一次開 App 在背景解壓到
 *     App 私有資料夾（APK＋native 程式庫），這次先用系統 WebView，之後每次開 App 直接用 Orea 核心。使用者只要裝一個 App
 *
 * 防護：換核心前先記「嘗試中」，第一個網頁載入完成（[markHealthy]）或撐過 [SAFE_MS] 才清掉。上次開到一半就掛（旗標還在）
 * → 這次退回系統 WebView 並記錄，不會因為核心壞掉而一直閃退。（只等計時器的話，剛打開就滑掉 App 也會被誤判成閃退）
 */
object Engine {
    const val PKG = "com.liquid.orea.webview"
    private const val SAFE_MS = 15_000L
    private const val ASSET_DIR = "orea_core"

    /** 目前使用的核心（給設定畫面顯示） */
    var active: String = ""
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("engine", Context.MODE_PRIVATE)

    /** 使用者是否選用 Orea 核心（預設：有就用） */
    fun wanted(ctx: Context) = prefs(ctx).getBoolean("chromium", true)

    fun setWanted(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean("chromium", v).putBoolean("trying", false).commit()
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, ASSET_DIR)
    private fun apk(ctx: Context) = File(dir(ctx), "core.apk")
    private fun libs(ctx: Context) = File(dir(ctx), "libs")
    private fun readyFile(ctx: Context) = File(dir(ctx), "ready")

    /** App 裡打包的核心版本（assets/orea_core/version.txt）；沒打包回傳 null */
    private fun bundledVersion(ctx: Context): String? =
        runCatching { ctx.assets.open("$ASSET_DIR/version.txt").bufferedReader().use { it.readText().trim() } }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** 已解壓好、可以直接用的內建核心版本 */
    private fun extractedVersion(ctx: Context): String? =
        runCatching { readyFile(ctx).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() && apk(ctx).isFile }

    /** Orea 核心的版本（另外安裝的優先，其次內建的）；都沒有回傳 null */
    fun installedVersion(ctx: Context): String? =
        runCatching { ctx.packageManager.getPackageInfo(PKG, 0).versionName }.getOrNull()
            ?: extractedVersion(ctx)?.substringBefore('@')
            ?: bundledVersion(ctx)?.substringBefore('@')

    @Volatile private var healthy = false

    /** 核心已經正常載入過網頁：清掉「嘗試中」（每次啟動只寫一次） */
    fun markHealthy(ctx: Context) {
        if (healthy) return
        healthy = true
        prefs(ctx).edit().putBoolean("trying", false).apply()
    }

    fun install(ctx: Context) {
        val p = prefs(ctx)
        val pkg = runCatching { ctx.packageManager.getPackageInfo(PKG, 0) }.getOrNull()
        val bundled = bundledVersion(ctx)
        val extracted = extractedVersion(ctx)
        when {
            !wanted(ctx) -> Unit
            pkg == null && bundled == null -> Unit
            p.getBoolean("trying", false) -> {
                // 上次換了核心後沒撐過 SAFE_MS 就結束（多半是閃退）：這次先用系統 WebView
                p.edit().putBoolean("trying", false).commit()
                BugLog.auto("engine_crash", "上次使用 Orea 核心 ${pkg?.versionName ?: extracted} 後很快就結束，這次退回系統 WebView")
            }
            // 內建核心還沒解壓（第一次開 App、或 App 更新換了核心版本）：背景解壓，這次先用系統 WebView
            pkg == null && extracted != bundled -> extract(ctx.applicationContext, bundled!!)
            else -> {
                p.edit().putBoolean("trying", true).commit()
                runCatching {
                    if (pkg != null) WebViewReplace.replace(ctx.applicationContext, pkg)
                    else WebViewReplace.replace(ctx.applicationContext, apk(ctx).path, libs(ctx).path)
                }
                    .onSuccess {
                        active = "Orea Chromium ${pkg?.versionName ?: extracted!!.substringBefore('@')}"
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

    @Volatile private var extracting = false

    /** 背景解壓打包在 App 裡的核心：先寫暫存檔再改名，中途被結束也不會留下半個核心；完成後寫 ready（版本） */
    private fun extract(ctx: Context, version: String) {
        if (extracting) return
        extracting = true
        Thread({
            val started = android.os.SystemClock.uptimeMillis()
            runCatching {
                val d = dir(ctx)
                d.deleteRecursively()
                d.mkdirs()
                // 解壓一次要寫入約 400MB（APK＋程式庫）：空間不夠就不解，維持系統 WebView
                check(d.usableSpace > 800L * 1024 * 1024) { "空間不足" }
                val tmp = File(d, "core.tmp")
                ctx.assets.open("$ASSET_DIR/core.bin").use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
                ApksUtils.extractNativeLibrary(tmp.path, libs(ctx).path)
                check(tmp.renameTo(apk(ctx)))
                // Android 14+：動態載入的程式碼檔案必須是唯讀
                apk(ctx).setReadOnly()
                readyFile(ctx).writeText(version)
            }.onSuccess {
                BugLog.event("engine", "內建核心 $version 解壓完成 ${android.os.SystemClock.uptimeMillis() - started}ms，下次開啟生效")
            }.onFailure {
                dir(ctx).deleteRecursively()
                BugLog.auto("engine_fail", "內建核心解壓失敗：$it")
            }
            extracting = false
        }, "orea-core-extract").apply { priority = Thread.MIN_PRIORITY }.start()
    }
}
