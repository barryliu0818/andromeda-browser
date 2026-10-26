package com.liquid.browser

import com.liquid.browser.core.t

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import java.io.File

/**
 * 閃退紀錄：未處理的例外寫進 App 私有資料夾，下次開啟時顯示，可一鍵複製回報。
 * 只存在手機上，不會自動上傳。
 */
object CrashLog {
    private fun file(context: Context) = File(context.filesDir, "last_crash.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                file(app).writeText(
                    buildString {
                        appendLine("Andromeda ${runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()}")
                        appendLine("${Build.MANUFACTURER} ${Build.MODEL}・Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
                        appendLine(t("執行緒：%1\$s", thread.name))
                        appendLine(e.stackTraceToString())
                    },
                )
            }
            previous?.uncaughtException(thread, e)
        }
    }

    fun showIfAny(activity: Activity) {
        val f = file(activity)
        val text = if (f.exists()) f.readText().also { f.delete() } else systemExit(activity) ?: return
        AlertDialog.Builder(activity)
            .setTitle(t("上次發生閃退"))
            .setMessage(t("可以複製錯誤紀錄回報，方便找出原因。\n\n") + text.take(1500))
            .setPositiveButton(t("複製紀錄")) { _, _ ->
                activity.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Andromeda crash", text))
                Toast.makeText(activity, t("已複製"), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(t("關閉"), null)
            .show()
    }

    /**
     * 上面的例外處理抓不到的結束原因（Android 11+ 由系統保存）：原生程式崩潰（WebView、圖形驅動）、
     * 無回應（ANR）、在前景時被系統結束。每筆只回報一次；使用者自己滑掉、更新 App、在背景被回收都不算。
     */
    private fun systemExit(context: Context): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        val am = context.getSystemService(android.app.ActivityManager::class.java) ?: return null
        val info = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() }.getOrNull() ?: return null
        val prefs = context.getSharedPreferences("crashlog", Context.MODE_PRIVATE)
        if (info.timestamp <= prefs.getLong("seen", 0L)) return null
        prefs.edit().putLong("seen", info.timestamp).apply()
        val foreground = info.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        val reason = when (info.reason) {
            android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            android.app.ApplicationExitInfo.REASON_ANR -> "ANR"
            android.app.ApplicationExitInfo.REASON_CRASH -> "CRASH"
            android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
            android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> if (foreground) "LOW_MEMORY" else null
            android.app.ApplicationExitInfo.REASON_SIGNALED -> if (foreground) "SIGNALED" else null
            android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
            android.app.ApplicationExitInfo.REASON_OTHER -> if (foreground) "OTHER" else null
            else -> null
        } ?: return null
        return buildString {
            appendLine("Andromeda ${runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()}")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL}・Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
            appendLine("$reason・status ${info.status}・importance ${info.importance}")
            appendLine(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(info.timestamp))
            appendLine("pss ${info.pss / 1024}MB・rss ${info.rss / 1024}MB")
            info.description?.let { appendLine(it) }
            // ANR 的追蹤是文字；原生崩潰是二進位 tombstone，只取得到上面的描述
            if (info.reason == android.app.ApplicationExitInfo.REASON_ANR) {
                runCatching { info.traceInputStream?.bufferedReader()?.use { r -> append(r.readText().take(6000)) } }
            }
        }
    }
}
