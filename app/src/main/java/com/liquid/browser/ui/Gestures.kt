package com.liquid.browser.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import com.liquid.browser.core.Motion
import kotlinx.coroutines.launch

/**
 * 長按拖曳排序（LazyColumn／LazyVerticalGrid 通用）。
 * 座標一律使用 lazy layoutInfo 的空間：positionOf 回傳項目左上角，keyAt 回傳某點下的項目。
 * 被拖曳項目的位移在繪製階段由「手指位置 − 抓取點 − 項目目前位置」計算，所以清單重排後不需另外補償。
 */
@Stable
class Reorder<K : Any>(
    private val positionOf: (K) -> Offset?,
    private val keyAt: (Offset) -> K?,
    private val move: (from: K, to: K) -> Unit,
) {
    var dragging by mutableStateOf<K?>(null)
        private set
    private var grab = Offset.Zero
    private var pointer by mutableStateOf(Offset.Zero)

    fun start(key: K, local: Offset) {
        val base = positionOf(key) ?: return
        dragging = key
        grab = local
        pointer = base + local
    }

    fun drag(delta: Offset) {
        val key = dragging ?: return
        pointer += delta
        val target = keyAt(pointer)
        if (target != null && target != key) move(key, target)
    }

    fun end() { dragging = null }

    fun translation(key: K): Offset {
        if (key != dragging) return Offset.Zero
        val base = positionOf(key) ?: return Offset.Zero
        return pointer - grab - base
    }
}

/** 套在清單項目上：長按後跟手拖曳、輕微放大浮起、震動回饋。ponytail: 拖到清單邊緣不會自動捲動。 */
@Composable
fun <K : Any> Modifier.reorderItem(reorder: Reorder<K>, key: K, onDrop: () -> Unit = {}): Modifier {
    val haptic = LocalHapticFeedback.current
    val active = reorder.dragging == key
    // 只有拖曳中才加 graphicsLayer（放大浮起）：常駐的 layer 會讓內部的原生 backdrop 玻璃取樣不到底下內容
    return this
        .zIndex(if (active) 1f else 0f)
        .offset {
            val t = reorder.translation(key)
            IntOffset(t.x.roundToInt(), t.y.roundToInt())
        }
        .then(if (active) Modifier.graphicsLayer { scaleX = 1.04f; scaleY = 1.04f } else Modifier)
        .pointerInput(key) {
            detectDragGesturesAfterLongPress(
                onDragStart = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    reorder.start(key, it)
                },
                onDrag = { change, d -> change.consume(); reorder.drag(d) },
                onDragEnd = { reorder.end(); onDrop() },
                onDragCancel = { reorder.end() },
            )
        }
}

/** 往左滑刪除：跟手移動，放開時超過門檻（或甩動夠快）就滑出並觸發 onDismiss，否則彈回。 */
@Composable
fun Modifier.swipeToDismiss(onDismiss: () -> Unit): Modifier {
    val scope = rememberCoroutineScope()
    val dx = remember { Animatable(0f) }
    val threshold = with(LocalDensity.current) { 120.dp.toPx() }
    var width by remember { mutableIntStateOf(0) }
    val moving by remember { derivedStateOf { dx.value != 0f } }
    return this
        .onSizeChanged { width = it.width }
        .offset { IntOffset(dx.value.roundToInt(), 0) }
        // 淡出只在滑動中套用 layer（理由同上）
        .then(if (moving) Modifier.graphicsLayer { alpha = 1f - (-dx.value / (threshold * 2.5f)).coerceIn(0f, 0.9f) } else Modifier)
        .draggable(
            rememberDraggableState { d -> scope.launch { dx.snapTo((dx.value + d).coerceAtMost(0f)) } },
            Orientation.Horizontal,
            onDragStopped = { v ->
                if (dx.value < -threshold || v < -1500f) {
                    dx.animateTo(-width * 1.2f, Motion.timed(Motion.ShortMs))
                    onDismiss()
                    dx.snapTo(0f)
                } else dx.animateTo(0f, Motion.standard())
            },
        )
}
