package com.liquid.browser.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.min

/*
 * 影片手勢模組（原生端）。行為與 assets/gesture_layer.js（JS 手勢層）一致：
 *  1. 長按 400ms → 2.0 倍速，放開恢復；觸發時震動。
 *  2. 雙擊：左 35% 倒退 10 秒、右 35% 快轉 10 秒、中間播放／暫停；連續快速點擊累加（第三下 20 秒、第四下 30 秒）。
 *     單擊（沒有第二下）顯示／隱藏控制列。
 *  3. 水平滑動超過 16dp 進入拖動進度：放開才跳轉；整個畫面寬度約對應 90 秒（短片則對應整部長度）。
 *  4. 左半邊上下滑動調亮度、右半邊調音量。
 * 數值集中在 GestureSpec，方便與 JS 端同步調整。
 */
object GestureSpec {
    const val LongPressMs = 400L
    const val DoubleTapMs = 280L
    /** 累加模式中，兩次點擊間隔超過此值就結束累加 */
    const val AccumulateMs = 700L
    const val SeekStepMs = 10_000L
    const val SideZone = 0.35f
    val ScrubThreshold = 16.dp
    val VerticalThreshold = 16.dp
    /**
     * 左右拖動進度：拖滿整個螢幕寬度跳多少。以前固定 90 秒，長片（兩三小時）要拖好多次；
     * 改成依片長：片長的 1/12，至少 5 分鐘、最多 10 分鐘（短於這個的片子拖滿就是整部）
     */
    fun screenWidthMs(durationMs: Long): Long = (durationMs / 12).coerceIn(300_000L, 600_000L).coerceAtMost(durationMs.coerceAtLeast(1))
    const val BoostSpeed = 2.0f
}

/** 側邊雙擊快轉／倒退的視覺狀態；id 每次變動讓動畫重新播放。 */
@Immutable
data class SeekRipple(val side: Int, val seconds: Int, val origin: Offset, val id: Long)

@Immutable
data class ScrubPreview(val targetMs: Long, val deltaMs: Long, val durationMs: Long)

enum class LevelKind { Brightness, Volume }

@Immutable
data class LevelPreview(val kind: LevelKind, val value: Float)

/** 手勢的視覺回饋狀態，由 GestureFeedback 的各元件讀取。 */
@Stable
class VideoGestureState {
    var boosting by mutableStateOf(false)
        internal set
    var ripple by mutableStateOf<SeekRipple?>(null)
        internal set
    var scrub by mutableStateOf<ScrubPreview?>(null)
        internal set
    var level by mutableStateOf<LevelPreview?>(null)
        internal set
}

/** 手勢要驅動的播放器操作。 */
interface VideoGestureActions {
    fun singleTap()
    fun togglePlay()
    fun seekBy(deltaMs: Long)
    fun seekTo(ms: Long)
    fun position(): Long
    fun duration(): Long
    fun setBoost(on: Boolean)
    /** 調整亮度，回傳新值 0～1 */
    fun adjustBrightness(delta: Float): Float
    /** 調整音量，回傳新值 0～1 */
    fun adjustVolume(delta: Float): Float
}

/**
 * 把所有影片手勢掛到一個元件上。scope 用來排程「單擊」的延遲判定與提示自動消失。
 */
fun Modifier.videoGestures(state: VideoGestureState, actions: VideoGestureActions, scope: CoroutineScope): Modifier {
    var pendingTap: Job? = null
    var lastTapAt = 0L
    var lastTapZone = 0
    var accumulating = false
    var accumulated = 0
    var rippleHide: Job? = null
    var rippleId = 0L

    return pointerInput(Unit) {
        val scrubSlop = GestureSpec.ScrubThreshold.toPx()
        val vertSlop = GestureSpec.VerticalThreshold.toPx()

        fun zoneOf(x: Float): Int = when {
            x < size.width * GestureSpec.SideZone -> -1
            x > size.width * (1 - GestureSpec.SideZone) -> 1
            else -> 0
        }

        fun showSeek(zone: Int, origin: Offset) {
            accumulated += 1
            actions.seekBy(zone * GestureSpec.SeekStepMs)
            state.ripple = SeekRipple(zone, accumulated * (GestureSpec.SeekStepMs / 1000).toInt(), origin, ++rippleId)
            rippleHide?.cancel()
            rippleHide = scope.launch {
                delay(GestureSpec.AccumulateMs + 200)
                state.ripple = null
                accumulating = false
                accumulated = 0
            }
        }

        fun onTap(pos: Offset, at: Long) {
            val zone = zoneOf(pos.x)
            when {
                // 累加中：同側快速點擊立刻再加 10 秒
                accumulating && zone != 0 && zone == lastTapZone && at - lastTapAt < GestureSpec.AccumulateMs -> showSeek(zone, pos)
                // 第二下：雙擊
                pendingTap != null && at - lastTapAt < GestureSpec.DoubleTapMs && zone == lastTapZone -> {
                    pendingTap?.cancel()
                    pendingTap = null
                    if (zone == 0) actions.togglePlay()
                    else {
                        accumulating = true
                        accumulated = 0
                        showSeek(zone, pos)
                    }
                }
                // 第一下：等一下看有沒有第二下，沒有就是單擊
                else -> {
                    accumulating = false
                    pendingTap?.cancel()
                    pendingTap = scope.launch {
                        delay(GestureSpec.DoubleTapMs)
                        pendingTap = null
                        actions.singleTap()
                    }
                }
            }
            lastTapAt = at
            lastTapZone = zone
        }

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var moved: PointerInputChange? = null
            var released: PointerInputChange? = null
            // 等待放開、移動或長按逾時（逾時 = 長按）
            withTimeoutOrNull(GestureSpec.LongPressMs) {
                while (true) {
                    val c = awaitPointerEvent().changes.first()
                    if (!c.pressed) {
                        released = c
                        break
                    }
                    val d = c.position - down.position
                    if (abs(d.x) > scrubSlop || abs(d.y) > vertSlop) {
                        moved = c
                        break
                    }
                }
            }

            when {
                released != null -> onTap(released!!.position, released!!.uptimeMillis)

                moved != null -> {
                    val first = moved!!
                    val d0 = first.position - down.position
                    if (abs(d0.x) >= abs(d0.y)) {
                        // 水平拖動進度：放開才跳轉
                        val start = actions.position()
                        val dur = actions.duration().coerceAtLeast(1)
                        val msPerPx = GestureSpec.screenWidthMs(dur).toFloat() / size.width
                        var target = start
                        while (true) {
                            val c = awaitPointerEvent().changes.first()
                            if (!c.pressed) break
                            c.consume()
                            target = (start + ((c.position.x - down.position.x) * msPerPx).toLong()).coerceIn(0, dur)
                            state.scrub = ScrubPreview(target, target - start, dur)
                        }
                        actions.seekTo(target)
                        state.scrub = null
                    } else {
                        // 垂直拖動不做事（不調亮度、音量：使用者不要左右兩側的上下手勢）；等手指放開
                        while (awaitPointerEvent().changes.first().pressed) { }
                    }
                }

                else -> {
                    // 長按：2 倍速，放開恢復
                    pendingTap?.cancel()
                    pendingTap = null
                    actions.setBoost(true)
                    state.boosting = true
                    while (true) {
                        val c = awaitPointerEvent().changes.first()
                        c.consume()
                        if (!c.pressed) break
                    }
                    actions.setBoost(false)
                    state.boosting = false
                }
            }
        }
    }
}
