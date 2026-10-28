package com.liquid.browser

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 問題紀錄：只存在手機上，不會上傳。放在 App 的外部私有資料夾（Android/data/<套件>/files/bugs），
 * 接 USB 用 adb pull 就讀得到，不必 root 或 debug 版。
 *  - events.log：自動記錄的異常（閃退、網頁渲染程序被結束、掉幀、網頁載入失敗、翻譯／下載失敗），超過 512KB 只留後半
 *  - report_編號_時間/：一鍵回報（螢幕截圖、當下狀態、最近的事件與 App 日誌）
 */
object BugLog {
    private var dir: File? = null
    private val io = Executors.newSingleThreadExecutor()
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "bugs").apply { mkdirs() }
    }

    private fun line(kind: String, msg: String) = synchronized(stamp) { "${stamp.format(Date())} [$kind] $msg\n" }

    private fun append(text: String) {
        val f = File(dir ?: return, "events.log")
        if (f.length() > 512 * 1024) f.writeText(f.readText().takeLast(256 * 1024).substringAfter('\n'))
        f.appendText(text)
    }

    /** 記一筆異常（背景執行緒寫檔，任何執行緒都能呼叫） */
    fun event(kind: String, msg: String) {
        val text = line(kind, msg)
        io.execute { runCatching { append(text) } }
    }

    /** 同步寫入：閃退時程式馬上就要結束，不能交給背景執行緒 */
    fun eventNow(kind: String, msg: String) = runCatching { append(line(kind, msg)) }

    /**
     * 一鍵回報：截下 App 主畫面（PixelCopy；系統模糊小視窗是另外的視窗，截不到）、記下當下狀態，
     * 連同最近的事件與 App 自己的日誌存成一個資料夾。完成後在主執行緒回呼回報編號。
     */
    fun report(activity: Activity, state: String, onDone: (Int) -> Unit) {
        val root = dir ?: return
        val n = (root.listFiles { f -> f.name.startsWith("report_") }?.size ?: 0) + 1
        val folder = File(root, "report_%02d_%s".format(n, SimpleDateFormat("MMdd-HHmmss", Locale.US).format(Date())))
        val main = Handler(Looper.getMainLooper())
        val save = { shot: Bitmap? ->
            io.execute {
                runCatching {
                    folder.mkdirs()
                    shot?.let { b -> File(folder, "screen.jpg").outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }; b.recycle() }
                    File(folder, "info.txt").writeText(
                        buildString {
                            appendLine(line("report", "#$n").trim())
                            appendLine("Andromeda ${runCatching { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName }.getOrNull()}")
                            appendLine("${Build.MANUFACTURER} ${Build.MODEL}・Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
                            appendLine()
                            appendLine(state)
                        },
                    )
                    File(root, "events.log").takeIf { it.exists() }?.let { File(folder, "events.txt").writeText(it.readLines().takeLast(200).joinToString("\n")) }
                    // App 只讀得到自己的日誌
                    val logcat = ProcessBuilder("logcat", "-d", "-t", "600", "--pid", android.os.Process.myPid().toString()).redirectErrorStream(true).start()
                    File(folder, "logcat.txt").writeText(logcat.inputStream.bufferedReader().use { it.readText() })
                }
                event("report", "#$n ${folder.name}")
                main.post { onDone(n) }
            }
        }
        val decor = activity.window.decorView
        if (decor.width <= 0 || decor.height <= 0) return save(null)
        val bmp = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        runCatching {
            PixelCopy.request(activity.window, bmp, { r -> save(if (r == PixelCopy.SUCCESS) bmp else null) }, main)
        }.onFailure { save(null) }
    }
}
