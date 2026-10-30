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
 *  - auto_編號_類別_時間/：自動回報，內容同上；類別：freeze（一幀超過 150ms）、anr（主執行緒卡住 2.5 秒，附卡住時的堆疊）、
 *    overlap（總覽／面板／選單／全螢幕開著時網址列模糊視窗還浮在上面）、renderer（網頁渲染程序崩潰）、crash（上次閃退）、
 *    stuck_*（畫面狀態卡住：選單／面板關不掉、總覽轉場沒結束、凍結畫面沒拿掉、輸入時鍵盤沒出來）
 *  - events.log 另外記：jank（一幀超過 25ms）、js（網頁 JS 錯誤）、http（主頁面 HTTP 錯誤）、slow（網頁載入超過 8 秒）、memory（系統要求釋放記憶體）
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

    private var limitWindow = 0L
    private var limitCount = 0

    /** 量可能很大的事件（網頁 JS 錯誤、HTTP 錯誤）：每分鐘最多 30 筆，超過的丟掉 */
    fun limited(kind: String, msg: String) {
        val now = System.currentTimeMillis()
        synchronized(stamp) {
            if (now - limitWindow > 60_000) { limitWindow = now; limitCount = 0 }
            if (++limitCount > 30) return
        }
        event(kind, msg)
    }

    /** 同步寫入：閃退時程式馬上就要結束，不能交給背景執行緒 */
    fun eventNow(kind: String, msg: String) = runCatching { append(line(kind, msg)) }

    /** 目前在前景的畫面（自動回報要截圖）；MainActivity 在 onResume／onPause 設定 */
    @Volatile var activity: java.lang.ref.WeakReference<Activity>? = null

    /** 當下的畫面狀態（網址、分頁、版面…）；由 BrowserScreen 提供，自動與手動回報共用 */
    @Volatile var state: (() -> String)? = null

    private val lastAuto = HashMap<String, Long>()

    /**
     * 自動回報：嚴重的問題（卡住、圖層重疊、網頁渲染程序崩潰、閃退）發生時，自動存一份跟一鍵回報一樣的資料夾
     * （auto_編號_類別_時間：截圖、狀態、最近事件、App 日誌）。同一類 2 分鐘內只記一次，自動回報最多留 30 份（刪最舊的）。
     * 任何執行緒都能呼叫；截圖在主執行緒做。
     */
    fun auto(kind: String, detail: String) {
        event(kind, detail)
        val now = System.currentTimeMillis()
        synchronized(lastAuto) {
            if (now - (lastAuto[kind] ?: 0L) < 120_000) return
            lastAuto[kind] = now
        }
        val a = activity?.get() ?: return
        Handler(Looper.getMainLooper()).post {
            if (a.isFinishing || a.isDestroyed) return@post
            val st = runCatching { state?.invoke() }.getOrNull().orEmpty()
            report(a, "【自動回報】$kind\n$detail\n\n$st", prefix = "auto", tag = kind) {}
            io.execute {
                val old = dir?.listFiles { f -> f.name.startsWith("auto_") }?.sortedBy { it.name.substringAfterLast('_') }.orEmpty()
                old.dropLast(30).forEach { it.deleteRecursively() }
            }
        }
    }

    /**
     * 一鍵回報：截下 App 主畫面（PixelCopy；系統模糊小視窗是另外的視窗，截不到）、記下當下狀態，
     * 連同最近的事件與 App 自己的日誌存成一個資料夾。完成後在主執行緒回呼回報編號。
     */
    fun report(activity: Activity, state: String, prefix: String = "report", tag: String = "", onDone: (Int) -> Unit) {
        val root = dir ?: return
        val n = (root.listFiles { f -> f.name.startsWith("${prefix}_") }?.size ?: 0) + 1
        val folder = File(root, "%s_%02d_%s%s".format(prefix, n, if (tag.isEmpty()) "" else "${tag}_", SimpleDateFormat("MMdd-HHmmss", Locale.US).format(Date())))
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
                event(prefix, "#$n ${folder.name}")
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

    /**
     * 主執行緒看門狗：背景執行緒每秒丟一個小工作給主執行緒，超過 2.5 秒還沒執行就是畫面卡住（快要 ANR）。
     * 卡住當下就抓主執行緒正在跑的程式（堆疊），恢復後自動回報（截到的是恢復後的畫面）。
     */
    fun startWatchdog() {
        val main = Handler(Looper.getMainLooper())
        Thread({
            while (true) {
                val done = java.util.concurrent.atomic.AtomicBoolean(false)
                val start = System.currentTimeMillis()
                main.post { done.set(true) }
                Thread.sleep(2_500)
                if (!done.get()) {
                    val stack = Looper.getMainLooper().thread.stackTrace.take(25).joinToString("\n") { "  at $it" }
                    while (!done.get()) Thread.sleep(100)
                    auto("anr", "主執行緒卡住 ${System.currentTimeMillis() - start}ms，卡住時正在執行：\n$stack")
                }
                Thread.sleep(1_000)
            }
        }, "bug-watchdog").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /** 閃退時留一個記號；下次開啟時（[pendingCrash]）補一份自動回報 */
    fun markCrash(trace: String) {
        val d = dir ?: return
        runCatching { File(d, "pending_crash.txt").writeText(trace) }
    }

    fun pendingCrash(): String? = runCatching {
        val f = File(dir ?: return null, "pending_crash.txt")
        if (!f.exists()) null else f.readText().also { f.delete() }
    }.getOrNull()
}
