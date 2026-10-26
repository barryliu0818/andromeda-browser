package com.liquid.browser.player

import com.liquid.browser.core.t

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquid.browser.core.Motion
import com.liquid.browser.ui.Sym
import com.liquid.browser.ui.glass.LiquidGlass
import com.liquid.browser.ui.glass.Tone
import dev.chrisbanes.haze.HazeState
import kotlin.math.abs
import kotlin.math.hypot

/* 影片手勢的視覺回饋。動畫只動 graphicsLayer／Canvas，數字使用等寬數字避免跳動閃爍。 */

private val Tabular = TextStyle(fontFeatureSettings = "tnum")

/** 全部回饋元件，疊在影片上。 */
@Composable
fun BoxScope.GestureFeedback(state: VideoGestureState, haze: HazeState) {
    SpeedPill(state.boosting, haze, Modifier.align(Alignment.TopCenter).padding(top = 24.dp))
    state.ripple?.let { SeekRippleView(it) }
    ScrubLabel(state.scrub, haze, Modifier.align(Alignment.Center))
    state.level?.let { LevelIndicator(it, haze, Modifier.align(if (it.kind == LevelKind.Brightness) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 32.dp)) }
}

/** 「2x ▶▶」提示膠囊：從上方滑入並淡入，放開手指時淡出；箭頭依序閃動。 */
@Composable
private fun SpeedPill(visible: Boolean, haze: HazeState, modifier: Modifier) {
    AnimatedVisibility(
        visible, modifier,
        enter = slideInVertically(Motion.standard()) { -it } + fadeIn(Motion.timed(Motion.ShortMs)),
        exit = fadeOut(Motion.timed(Motion.ShortMs)),
    ) {
        LiquidGlass(haze, Tone.Dark, CircleShape, systemBlur = false) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("2x", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                FlashingArrows(forward = true, Modifier.padding(start = 6.dp), size = 14)
            }
        }
    }
}

/** 三個箭頭依序閃動（快轉向右、倒退向左）。 */
@Composable
private fun FlashingArrows(forward: Boolean, modifier: Modifier = Modifier, size: Int = 18) {
    val t = rememberInfiniteTransition(label = "arrows")
    val phase by t.animateFloat(0f, 3f, infiniteRepeatable(tween(750), RepeatMode.Restart), label = "phase")
    Row(modifier.graphicsLayer { scaleX = if (forward) 1f else -1f }, horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
        repeat(3) { i ->
            val a = 1f - (abs(phase - i - 0.5f) / 1.5f).coerceIn(0f, 0.75f)
            Sym("play_arrow", Color.White, size.dp, Modifier.graphicsLayer { alpha = a })
        }
    }
}

/**
 * 雙擊快轉／倒退：對應側出現半圓形區塊，從點擊位置擴散漣漪，箭頭依序閃動，
 * 秒數累加時數字輕微彈跳。
 */
@Composable
private fun BoxScope.SeekRippleView(r: SeekRipple) {
    val spread = remember(r.id) { Animatable(0f) }
    LaunchedEffect(r.id) { spread.animateTo(1f, tween(450)) }
    val right = r.side > 0
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Box(
        Modifier.align(if (right) Alignment.CenterEnd else Alignment.CenterStart)
            .fillMaxHeight(if (landscape) 1f else 0.45f).fillMaxWidth(0.38f)
            // 內側為橢圓弧：取一個比區塊寬一倍的橢圓，只露出靠內側的一半
            .clip(GenericShape { size, _ ->
                val w = size.width * 2
                val left = if (right) 0f else -size.width
                addOval(androidx.compose.ui.geometry.Rect(left, -size.height * 0.15f, left + w, size.height * 1.15f))
            })
            .background(Color.White.copy(alpha = 0.10f)),
        Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // 漣漪從點擊位置擴散：點擊位置換算到這個區塊的座標
            val local = Offset(if (right) r.origin.x - (size.width / 0.38f - size.width) else r.origin.x, size.height / 2)
            val maxR = hypot(size.width, size.height)
            drawCircle(Color.White.copy(alpha = 0.18f * (1f - spread.value)), radius = maxR * spread.value, center = local)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FlashingArrows(forward = right)
            Text(
                t("%1\$s 秒", r.seconds),
                Modifier.padding(top = 6.dp),
                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, style = Tabular,
            )
        }
    }
}

/** 拖動進度時的時間提示：縮放淡入，數字即時更新。 */
@Composable
private fun ScrubLabel(p: ScrubPreview?, haze: HazeState, modifier: Modifier) {
    AnimatedVisibility(p != null, modifier, enter = fadeIn(Motion.timed(Motion.ShortMs)), exit = fadeOut()) {
        val last = remember { arrayOf(ScrubPreview(0, 0, 0)) }
        val v = p ?: last[0]
        last[0] = v
        LiquidGlass(haze, Tone.Dark, RoundedCornerShape(20.dp), systemBlur = false) {
            Column(Modifier.padding(horizontal = 22.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${formatTime(v.targetMs)} / ${formatTime(v.durationMs)}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, style = Tabular)
                val sec = v.deltaMs / 1000
                Text((if (sec >= 0) "+" else "−") + t("%1\$s 秒", abs(sec)), color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp, style = Tabular)
            }
        }
    }
}

/** 亮度／音量的直條指示。 */
@Composable
private fun LevelIndicator(l: LevelPreview, haze: HazeState, modifier: Modifier) {
    LiquidGlass(haze, Tone.Dark, CircleShape, modifier.width(44.dp).height(170.dp), systemBlur = false) {
        Column(Modifier.fillMaxSize().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.weight(1f).width(6.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f)), Alignment.BottomCenter) {
                Box(Modifier.fillMaxWidth().fillMaxHeight(l.value.coerceIn(0f, 1f)).background(Color.White))
            }
            Sym(if (l.kind == LevelKind.Brightness) "light_mode" else "volume_up", Color.White, 20.dp, Modifier.padding(top = 8.dp).size(22.dp))
        }
    }
}

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    return if (h > 0) "%d:%02d:%02d".format(h, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
