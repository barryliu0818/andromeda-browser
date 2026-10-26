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
import androidx.media3.datasource.DefaultHttpDataSource
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

    init {
        val headers = buildMap {
            request.referer?.let { put("Referer", it) }
            request.cookies?.let { put("Cookie", it) }
        }
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(request.userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(headers)
        player = ExoPlayer.Builder(context, androidx.media3.exoplayer.DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(DefaultDataSource.Factory(context, http)))
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
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
            }
            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) videoAspect = size.width * size.pixelWidthHeightRatio / size.height
            }
            override fun onPlayerError(e: androidx.media3.common.PlaybackException) { error = describe(e) }
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
