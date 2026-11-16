package com.liquid.browser.player

import com.liquid.browser.core.t
import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.liquid.browser.data.PlaybackDao
import com.liquid.browser.data.PlaybackPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 交給原生播放器的影片：網址與網頁的 Referer、User-Agent、Cookie；from 為影片在網頁中的位置（轉場用）。 */
@Immutable
data class PlayerRequest(
    val url: String,
    val title: String,
    val referer: String?,
    val userAgent: String,
    val cookies: String?,
    val from: Rect? = null,
    /** 從網頁影片目前的播放位置接著播（毫秒）；0 表示依記住的進度 */
    val startMs: Long = 0,
)

/** 判斷網址是否為原生播放器可直接播放的影片（MP4、HLS、DASH）。 */
fun isMediaUrl(url: String): Boolean {
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return url.startsWith("http") && (path.endsWith(".mp4") || path.endsWith(".m3u8") || path.endsWith(".mpd") ||
        path.endsWith(".webm") || path.endsWith(".mkv") || path.endsWith(".m4v"))
}

/**
 * 原生播放器（Media3 ExoPlayer）：支援 MP4、HLS、DASH；帶入網頁的標頭；每 5 秒與離開時記住播放進度。
 * ExoPlayer 會依影片幀率自動對 Surface 呼叫 setFrameRate（Android 11+）。
 */
@Stable
@OptIn(UnstableApi::class)
class PlayerController(
    context: Context,
    val request: PlayerRequest,
    private val playback: PlaybackDao,
    private val scope: CoroutineScope,
) {
    val player: ExoPlayer
    var isPlaying by mutableStateOf(false)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var videoAspect by mutableFloatStateOf(16f / 9f)
        private set
    var ended by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** 使用者選擇的速度（長按 2 倍速放開後回到這個值） */
    var speed by mutableFloatStateOf(1f)
        private set

    private val saver: Job
    /** 開始緩衝的時間（0＝沒在緩衝）：卡住多久記進錯誤紀錄 */
    private var bufferingSince = 0L

    init {
        val headers = buildMap {
            request.referer?.let {
                put("Referer", it)
                // hls.js 等網頁播放器用 XHR 抓串流，會帶 Origin；不少影片 CDN 沒有 Origin 就回 403
                android.net.Uri.parse(it).let { u -> if (u.scheme != null && u.host != null) put("Origin", "${u.scheme}://${u.host}") }
            }
            request.cookies?.let { put("Cookie", it) }
        }
        // 網路：用 App 共用的 OkHttp（連線重用、HTTP/2、讀取逾時較長），以前的 HttpURLConnection 在代理或慢的 CDN 上
        // 一段載入失敗就卡在緩衝
        val http = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(
            com.liquid.browser.core.Http.client.newBuilder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build(),
        )
            .setUserAgent(request.userAgent)
            .setDefaultRequestProperties(headers)
        // 緩衝：預設以大小為上限（約 13MB），高畫質串流只夠十幾秒，網路一頓就卡住；
        // 改成以時間為準，至少預先載入 30 秒、最多 3 分鐘，開播 1.5 秒、卡住後等 4 秒再繼續（不會一頓一頓）
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 180_000, 1_500, 4_000)
            .setTargetBufferBytes(128 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        // 載入失敗多重試幾次（預設 3 次）：一個片段暫時抓不到就不會整個卡住
        val retries = androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(8)
        player = ExoPlayer.Builder(context, androidx.media3.exoplayer.DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context).setDataSourceFactory(DefaultDataSource.Factory(context, http)).setLoadErrorHandlingPolicy(retries),
            )
            .setLoadControl(loadControl)
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
            // 音訊焦點：來電、其他 App 開始播放聲音時自動暫停；拔掉耳機、藍牙斷線時暫停，不會突然從喇叭放出來
            .setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        val path = request.url.substringBefore('?').lowercase()
        val mime = when {
            path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            else -> null
        }
        player.setMediaItem(MediaItem.Builder().setUri(request.url).setMimeType(mime).build())
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlaybackStateChanged(state: Int) {
                if (player.duration > 0) durationMs = player.duration
                ended = state == Player.STATE_ENDED
                // 卡住診斷：緩衝超過 3 秒的記進錯誤紀錄（網域、位置、已緩衝多少）
                if (state == Player.STATE_BUFFERING) bufferingSince = android.os.SystemClock.uptimeMillis()
                else if (bufferingSince > 0) {
                    val waited = android.os.SystemClock.uptimeMillis() - bufferingSince
                    bufferingSince = 0
                    if (waited > 3_000) com.liquid.browser.BugLog.event(
                        "player", "緩衝 ${waited}ms ${android.net.Uri.parse(request.url).host} 位置 ${player.currentPosition / 1000}s 已緩衝 ${(player.bufferedPosition - player.currentPosition) / 1000}s",
                    )
                }
            }
            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) videoAspect = size.width * size.pixelWidthHeightRatio / size.height
            }
            override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                error = describe(e)
                com.liquid.browser.BugLog.event("player", "錯誤 ${e.errorCodeName} ${e.cause?.message?.take(120)} ${android.net.Uri.parse(request.url).host}")
            }
        })
        player.prepare()
        player.playWhenReady = true
        // 從網頁影片的位置接著播；否則從上次記住的位置繼續（太接近開頭或結尾則從頭）
        if (request.startMs > 1_000) player.seekTo(request.startMs)
        else scope.launch {
            val saved = playback.get(request.url) ?: return@launch
            if (saved.positionMs > 10_000 && saved.positionMs < saved.durationMs - 10_000) player.seekTo(saved.positionMs)
        }
        saver = scope.launch {
            while (true) {
                delay(5_000)
                save()
            }
        }
    }

    fun togglePlay() {
        if (ended) player.seekTo(0)
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekBy(deltaMs: Long) = seekTo(player.currentPosition + deltaMs)
    fun seekTo(ms: Long) = player.seekTo(ms.coerceIn(0, if (durationMs > 0) durationMs else Long.MAX_VALUE))

    fun setPlaybackSpeed(s: Float) {
        speed = s
        player.playbackParameters = PlaybackParameters(s)
    }

    /** 長按 2 倍速：按住時 2.0x，放開回到使用者選擇的速度。 */
    fun setBoost(on: Boolean) {
        player.playbackParameters = PlaybackParameters(if (on) GestureSpec.BoostSpeed else speed)
    }

    private fun save() {
        val pos = player.currentPosition
        val dur = player.duration
        if (dur <= 0) return
        scope.launch { playback.save(PlaybackPosition(request.url, pos, dur, System.currentTimeMillis())) }
    }

    fun release() {
        saver.cancel()
        save()
        player.release()
    }
}

/** 播放錯誤轉成看得懂的原因（伺服器拒絕、網路、格式），其他的才顯示原始代碼 */
private fun describe(e: androidx.media3.common.PlaybackException): String {
    val http = (e.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
    return when {
        http != null -> t("伺服器拒絕（HTTP %1\$s）", http)
        e.errorCode in 2000..2999 -> t("網路連線失敗")
        e.errorCode in 3000..4999 -> t("不支援的影片格式")
        else -> e.errorCodeName
    }
}
