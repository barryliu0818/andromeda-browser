package com.liquid.browser.browser

import com.liquid.browser.core.t

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.liquid.browser.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class DlStatus { Running, Paused, Done, Failed }

/** 檔案中的一段（end 含）；end 會在動態分段時被其他連線縮短，所以是 volatile。 */
internal class Seg(val start: Long, end: Long) {
    @Volatile var pos = start
    @Volatile var end = end
    @Volatile var active = false
    val left get() = end - pos + 1
}

/** 一個下載任務；Compose 讀取的欄位都是 State，由取樣協程每 0.5 秒更新一次（避免每個封包都觸發重組）。 */
@Stable
class DlTask internal constructor(
    val id: Long,
    val url: String,
    name: String,
    internal val headers: Map<String, String>,
    val isHls: Boolean,
    internal val temp: File,
) {
    var name by mutableStateOf(name); internal set
    var status by mutableStateOf(DlStatus.Running); internal set
    /** 總位元組（HLS 為總片段數）；未知時為 -1 */
    var total by mutableLongStateOf(-1); internal set
    /** 已完成位元組（HLS 為已完成片段數） */
    var done by mutableLongStateOf(0); internal set
    /** 每秒位元組 */
    var speed by mutableLongStateOf(0); internal set
    var connections by mutableIntStateOf(0); internal set
    var error by mutableStateOf<String?>(null); internal set
    /** 各連線負責的區段（佔整個檔案的比例）：start、已下載到、end；供下載頁畫 IDM 式分段進度條 */
    var parts by mutableStateOf<List<Triple<Float, Float, Float>>>(emptyList()); internal set
    var saved: Uri? = null; internal set

    val progress get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f

    internal val segs = mutableListOf<Seg>()
    internal var ranged = false
    internal val bytes = AtomicLong()
    internal val units = AtomicLong()
    internal val conns = AtomicInteger()
    internal var job: Job? = null
    internal var canceled = false
}

/**
 * 內建下載引擎（IDM 式多連線加速）：
 *  - 伺服器支援 Range 時最多 [MAX_CONN] 條連線同時下載同一個檔案；
 *  - 動態分段：任一連線做完自己那段，就把剩餘最多的那段從中間切開接手後半，直到剩餘小於 [MIN_SPLIT]；
 *  - 暫停／繼續：保留各段進度，繼續時從斷點接著下；每段網路錯誤自動重試；
 *  - HLS（m3u8）影片：選最高畫質、並行下載片段（支援 AES-128 解密），完成後依序合併成 .ts（fMP4 則為 .mp4）；
 *  - 完成後存到系統「下載」資料夾（Android 10+ 經 MediaStore，不需儲存權限）。
 * 下載期間由 [DownloadService] 前景服務保活並在通知列顯示進度。
 * ponytail: 任務只存在記憶體，App 被系統殺掉後清單不保留（暫存檔仍在 cache），需要時再把任務寫進 Room。
 */
object DownloadEngine {
    /** 8 核以上（例如驍龍 8 Elite：2 顆 Oryon 超大核＋6 顆效能核）開 16 條連線，其餘 8 條 */
    val MAX_CONN = if (Runtime.getRuntime().availableProcessors() >= 8) 16 else 8
    private const val MIN_SPLIT = 512 * 1024L
    private const val BUF = 256 * 1024

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var nextId = 1L
    val tasks = mutableStateListOf<DlTask>()

    fun init(context: Context) { if (!::app.isInitialized) app = context.applicationContext }

    val running get() = tasks.count { it.status == DlStatus.Running }

    fun enqueue(url: String, fileName: String, headers: Map<String, String>): DlTask? {
        if (!url.startsWith("http")) return null
        val hls = url.substringBefore('?').lowercase().endsWith(".m3u8")
        val name = sanitize(if (hls) fileName.substringBeforeLast('.') + ".ts" else fileName)
        val id = nextId++
        val temp = File(app.cacheDir, "dl/$id").apply { parentFile?.mkdirs() }
        val task = DlTask(id, url, name, headers.filterValues { it.isNotBlank() }, hls, temp)
        tasks.add(0, task)
        start(task)
        return task
    }

    fun pause(t: DlTask) { if (t.status == DlStatus.Running) t.job?.cancel() }

    fun resume(t: DlTask) {
        if (t.status == DlStatus.Paused || t.status == DlStatus.Failed) start(t)
    }

    fun cancel(t: DlTask) {
        t.canceled = true
        t.job?.cancel()
        tasks.remove(t)
        scope.launch { t.temp.deleteRecursively() }
    }

    fun clearFinished() = tasks.removeAll { it.status == DlStatus.Done }

    /** 刪除已下載的檔案（從手機的「下載」資料夾移除）並移出清單 */
    fun deleteFile(t: DlTask) {
        tasks.remove(t)
        val uri = t.saved ?: return
        scope.launch {
            runCatching {
                if (Build.VERSION.SDK_INT >= 29) app.contentResolver.delete(uri, null, null)
                else File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), t.name).delete()
            }.onSuccess { android.util.Log.i("LiquidDl", "delete $uri -> $it") }
                .onFailure { android.util.Log.w("LiquidDl", "delete $uri failed", it) }
        }
    }

    /** 影片交給內建播放器（由 BrowserViewModel 設定）；回傳 false 時改用系統的開啟方式 */
    var videoOpener: ((DlTask) -> Boolean)? = null

    fun open(context: Context, t: DlTask) {
        val uri = t.saved ?: return
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(t.name.substringAfterLast('.', "").lowercase()) ?: "*/*"
        // 下載好的影片（含 HLS 合併後的 .mp4／.ts）用內建播放器開
        val video = mime.startsWith("video/") || t.isHls || t.name.substringAfterLast('.', "").lowercase() in setOf("mp4", "m4v", "mkv", "webm", "ts", "mov")
        if (video && videoOpener?.invoke(t) == true) return
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private fun start(t: DlTask) {
        t.status = DlStatus.Running
        t.error = null
        DownloadService.ensureRunning(app)
        t.job = scope.launch {
            val sampler = launch { sample(t) }
            try {
                if (t.isHls) runHls(t) else runHttp(t)
                t.connections = 0
                export(t)
                t.status = DlStatus.Done
            } catch (e: CancellationException) {
                if (!t.canceled) t.status = DlStatus.Paused
            } catch (e: Exception) {
                t.status = DlStatus.Failed
                t.error = e.message ?: e.javaClass.simpleName
            } finally {
                sampler.cancel()
                t.connections = 0
                t.speed = 0
                synchronized(t.segs) { t.segs.forEach { it.active = false } }
                publish(t)
            }
        }
    }

    /** 每 0.5 秒把原子計數同步到 Compose State，並以 2 秒移動平均算速度 */
    private suspend fun sample(t: DlTask) {
        val window = ArrayDeque<Pair<Long, Long>>()
        while (true) {
            publish(t)
            val now = System.nanoTime()
            window.addLast(now to t.bytes.get())
            while (window.size > 4) window.removeFirst()
            val (t0, b0) = window.first()
            t.speed = if (now > t0) ((t.bytes.get() - b0) * 1_000_000_000L / (now - t0)) else 0
            delay(500)
        }
    }

    private fun publish(t: DlTask) {
        t.connections = t.conns.get()
        if (t.ranged && t.total > 0) {
            val total = t.total.toFloat()
            t.parts = synchronized(t.segs) { t.segs.map { Triple(it.start / total, it.pos / total, (it.end + 1) / total) } }.sortedBy { it.first }
        }
        t.done = if (t.isHls) t.units.get() else t.bytes.get().let { if (t.total > 0) it.coerceAtMost(t.total) else it }
    }

    // ---------------------------------------------------------------- 一般檔案（多連線）

    private suspend fun runHttp(t: DlTask) = coroutineScope {
        if (t.segs.isEmpty()) {
            val probe = connect(t.url, t.headers, "bytes=0-")
            val code = probe.responseCode
            if (code !in 200..299) { probe.disconnect(); throw IOException("HTTP $code") }
            val total = probe.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
                ?: probe.contentLengthLong.takeIf { it > 0 } ?: -1
            // 伺服器給的檔名（Content-Disposition）優先
            probe.getHeaderField("Content-Disposition")?.let { cd ->
                android.webkit.URLUtil.guessFileName(t.url, cd, probe.contentType).takeIf { it.isNotBlank() }?.let { t.name = sanitize(it) }
            }
            probe.disconnect()
            t.total = total
            t.ranged = code == 206 && total > 0
            if (t.ranged) {
                val n = (total / MIN_SPLIT).coerceIn(1, MAX_CONN.toLong()).toInt()
                val size = total / n
                for (i in 0 until n) t.segs += Seg(i * size, if (i == n - 1) total - 1 else (i + 1) * size - 1)
                RandomAccessFile(t.temp, "rw").use { it.setLength(total) }
            } else {
                t.segs += Seg(0, Long.MAX_VALUE)
            }
        } else if (!t.ranged) {
            // 不支援續傳的伺服器只能從頭再來
            t.segs.clear(); t.segs += Seg(0, Long.MAX_VALUE); t.bytes.set(0)
        }
        val workers = if (t.ranged) MAX_CONN else 1
        repeat(workers) {
            launch {
                RandomAccessFile(t.temp, "rw").use { raf ->
                    while (isActive) {
                        val seg = take(t) ?: break
                        t.conns.incrementAndGet()
                        try { fetch(t, seg, raf) } finally { t.conns.decrementAndGet(); seg.active = false }
                    }
                }
            }
        }
    }

    /** 取下一段：先拿沒人做的段；沒有就把剩餘最多的那段切一半（IDM 的動態分段） */
    private fun take(t: DlTask): Seg? = synchronized(t.segs) {
        t.segs.firstOrNull { !it.active && it.left > 0 }?.let { it.active = true; return it }
        if (!t.ranged) return null
        val big = t.segs.filter { it.active }.maxByOrNull { it.left } ?: return null
        if (big.left < MIN_SPLIT * 2) return null
        val mid = big.pos + big.left / 2
        val s = Seg(mid, big.end).also { it.active = true }
        big.end = mid - 1
        t.segs += s
        s
    }

    private suspend fun fetch(t: DlTask, seg: Seg, raf: RandomAccessFile) {
        var attempt = 0
        val buf = ByteArray(BUF)
        while (seg.left > 0) {
            currentCoroutineContext().ensureActive()
            try {
                val conn = connect(t.url, t.headers, if (t.ranged) "bytes=${seg.pos}-${seg.end}" else null)
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) throw IOException("HTTP $code")
                    if (t.ranged && code != 206) throw IOException(t("伺服器不再支援分段下載"))
                    conn.inputStream.use { input ->
                        while (seg.left > 0) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) {
                                if (!t.ranged) seg.end = seg.pos - 1 // 長度未知：讀到結尾即完成
                                break
                            }
                            // end 可能剛被其他連線縮短：只寫到新的 end 為止
                            val w = if (t.ranged) minOf(n.toLong(), seg.left).toInt() else n
                            raf.channel.write(ByteBuffer.wrap(buf, 0, w), seg.pos)
                            seg.pos += w
                            t.bytes.addAndGet(w.toLong())
                        }
                    }
                } finally { conn.disconnect() }
                attempt = 0
            } catch (e: IOException) {
                if (++attempt > 5) throw e
                delay(600L * attempt)
            }
        }
    }

    private fun connect(url: String, headers: Map<String, String>, range: String?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            setRequestProperty("Accept-Encoding", "identity") // 壓縮會讓 Range 與長度對不上
            if (range != null) setRequestProperty("Range", range)
        }

    // ---------------------------------------------------------------- HLS 影片

    private class HlsSeg(val url: String, val keyUrl: String?, val iv: ByteArray?)

    private suspend fun runHls(t: DlTask) = coroutineScope {
        var playlistUrl = t.url
        var text = readText(playlistUrl, t.headers)
        if ("#EXT-X-STREAM-INF" in text) {
            // 主清單：選頻寬最高的畫質
            val lines = text.lines()
            val best = lines.indices.filter { lines[it].startsWith("#EXT-X-STREAM-INF") }
                .maxByOrNull { Regex("BANDWIDTH=(\\d+)").find(lines[it])?.groupValues?.get(1)?.toLongOrNull() ?: 0 }
                ?: throw IOException(t("找不到影片串流"))
            val uri = lines.drop(best + 1).first { it.isNotBlank() && !it.startsWith("#") }.trim()
            playlistUrl = resolve(playlistUrl, uri)
            text = readText(playlistUrl, t.headers)
        }
        if ("#EXT-X-ENDLIST" !in text) throw IOException(t("直播串流無法下載"))
        var keyUrl: String? = null
        var iv: ByteArray? = null
        var seq = Regex("#EXT-X-MEDIA-SEQUENCE:(\\d+)").find(text)?.groupValues?.get(1)?.toLong() ?: 0
        var init: String? = null
        val segs = mutableListOf<HlsSeg>()
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-KEY") -> {
                    val method = Regex("METHOD=([A-Z0-9-]+)").find(line)?.groupValues?.get(1)
                    keyUrl = when (method) {
                        "NONE", null -> null
                        "AES-128" -> resolve(playlistUrl, Regex("URI=\"([^\"]+)\"").find(line)!!.groupValues[1])
                        else -> throw IOException(t("不支援的加密方式：%1\$s", method))
                    }
                    iv = Regex("IV=0[xX]([0-9a-fA-F]+)").find(line)?.groupValues?.get(1)?.padStart(32, '0')?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()
                }
                line.startsWith("#EXT-X-MAP") -> init = resolve(playlistUrl, Regex("URI=\"([^\"]+)\"").find(line)!!.groupValues[1])
                line.isNotEmpty() && !line.startsWith("#") -> {
                    val segIv = iv ?: ByteArray(16).also { b -> for (i in 0 until 8) b[15 - i] = (seq shr (8 * i)).toByte() }
                    segs += HlsSeg(resolve(playlistUrl, line), keyUrl, if (keyUrl != null) segIv else null)
                    seq++
                }
            }
        }
        if (init != null) {
            t.name = t.name.substringBeforeLast('.') + ".mp4"
            segs.add(0, HlsSeg(init, null, null))
        }
        t.total = segs.size.toLong()
        t.temp.mkdirs()
        val keys = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
        val next = AtomicInteger(0)
        t.units.set(segs.indices.count { File(t.temp, "$it.ok").exists() }.toLong())
        repeat(MAX_CONN) {
            launch {
                while (isActive) {
                    val i = next.getAndIncrement()
                    if (i >= segs.size) break
                    val ok = File(t.temp, "$i.ok")
                    if (ok.exists()) continue
                    t.conns.incrementAndGet()
                    try {
                        var attempt = 0
                        while (true) {
                            try {
                                var data = readBytes(segs[i].url, t.headers) { n -> t.bytes.addAndGet(n.toLong()) }
                                segs[i].keyUrl?.let { k ->
                                    val key = keys.getOrPut(k) { readBytes(k, t.headers) {} }
                                    data = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                                        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(segs[i].iv))
                                        doFinal(data)
                                    }
                                }
                                File(t.temp, "$i.part").writeBytes(data)
                                File(t.temp, "$i.part").renameTo(ok)
                                t.units.incrementAndGet()
                                break
                            } catch (e: IOException) {
                                if (++attempt > 5) throw e
                                delay(600L * attempt)
                            }
                        }
                    } finally { t.conns.decrementAndGet() }
                }
            }
        }
    }

    private fun readText(url: String, headers: Map<String, String>) = String(readBytes(url, headers) {})

    private fun readBytes(url: String, headers: Map<String, String>, onRead: (Int) -> Unit): ByteArray {
        val conn = connect(url, headers, null)
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            val out = java.io.ByteArrayOutputStream(conn.contentLength.coerceAtLeast(64 * 1024))
            val buf = ByteArray(BUF)
            conn.inputStream.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    onRead(n)
                }
            }
            return out.toByteArray()
        } finally { conn.disconnect() }
    }

    private fun resolve(base: String, ref: String) = URL(URL(base), ref).toString()

    // ---------------------------------------------------------------- 存檔

    private suspend fun export(t: DlTask) = withContext(Dispatchers.IO) {
        // HLS：依序合併片段
        val source = if (t.isHls) File(t.temp.parentFile, "${t.id}.merged").also { merged ->
            merged.outputStream().use { out -> for (i in 0 until t.total.toInt()) File(t.temp, "$i.ok").inputStream().use { it.copyTo(out) } }
        } else t.temp
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(t.name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        t.saved = if (Build.VERSION.SDK_INT >= 29) {
            val resolver = app.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, t.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException(t("無法寫入下載資料夾"))
            resolver.openOutputStream(uri)!!.use { out -> source.inputStream().use { it.copyTo(out, BUF) } }
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            // Android 9 以下：存到 App 專屬的下載資料夾（不需儲存權限），以 FileProvider 開啟
            val dir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!
            val dest = File(dir, t.name)
            source.copyTo(dest, overwrite = true)
            FileProvider.getUriForFile(app, "${app.packageName}.files", dest)
        }
        source.delete()
        t.temp.deleteRecursively()
    }

    private fun sanitize(name: String) = name.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").take(120).ifBlank { "download" }
}

/**
 * 下載期間的前景服務：保活並在通知列顯示總進度；沒有進行中的任務就自行結束。
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, t("下載"), NotificationManager.IMPORTANCE_LOW))
        val notification = build(0, 0, 0)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, notification)
        scope.coroutineContext[Job]?.children?.forEach { it.cancel() }
        scope.launch {
            while (true) {
                delay(1000)
                val active = DownloadEngine.tasks.filter { it.status == DlStatus.Running }
                if (active.isEmpty()) break
                val total = active.sumOf { if (it.total > 0) 1000L else 0L }
                val done = active.sumOf { (it.progress * 1000).toLong() }
                nm.notify(ID, build(active.size, if (total > 0) (done * 100 / total).toInt() else -1, active.sumOf { it.speed }))
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun build(count: Int, percent: Int, speed: Long): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (count > 0) t("正在下載 %1\$s 個檔案", count) else t("準備下載"))
            .setContentText(if (speed > 0) "${android.text.format.Formatter.formatShortFileSize(this, speed)}/s" else null)
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val ID = 42

        fun ensureRunning(context: Context) {
            runCatching { context.startForegroundService(Intent(context, DownloadService::class.java)) }
        }
    }
}
