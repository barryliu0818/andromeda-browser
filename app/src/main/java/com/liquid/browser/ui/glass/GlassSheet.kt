package com.liquid.browser.ui.glass

import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.liquid.browser.core.Dimens
import com.liquid.browser.core.Motion
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 底部面板的狀態：show 傳入內容，dismiss 關閉。 */
@Stable
class SheetState {
    var content by mutableStateOf<(@Composable (dismiss: () -> Unit) -> Unit)?>(null)
        private set
    var open by mutableStateOf(false)
        private set

    fun show(content: @Composable (dismiss: () -> Unit) -> Unit) {
        this.content = content
        open = true
    }

    /** 已在關閉中（例如收起動畫被打斷）再呼叫一次時直接清掉，避免殘留一層看不見的遮罩 */
    fun dismiss() { if (open) open = false else content = null }
    internal fun clear() { content = null }

    internal var anim: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>? = null

    /** 面板正在滑動（動畫或拖曳中）；在繪製階段讀，只讓圖層重畫、不觸發重組 */
    fun moving(): Boolean = anim?.let { it.isRunning || it.value in 0.001f..0.999f } == true
}

private val SheetShape = RoundedCornerShape(topStart = Dimens.MenuRadius, topEnd = Dimens.MenuRadius)

/**
 * 液態玻璃底部面板：以彈簧從下方滑入；可用手指往下拖曳關閉，拖曳時背景遮罩透明度跟著變化；
 * 內部清單捲到頂端後繼續往下拉，會改為拖曳面板（nested scroll）。
 */
@Composable
fun GlassSheetHost(state: SheetState, haze: HazeState, tone: Tone) {
    val content = state.content ?: return
    val scope = rememberCoroutineScope()
    val hidden = remember(content) { Animatable(1f) } // 0 = 完全展開，1 = 收到畫面外
    state.anim = hidden
    LaunchedEffect(content, state.open) {
        if (state.open) {
            // 先讓內容在畫面外完成第一次組合與排版，下一幀才開始滑入：首次開啟時排版不會吃掉動畫的幀
            androidx.compose.runtime.withFrameNanos { }
            hidden.animateTo(0f, Motion.glass())
        }
        else {
            // 收起動畫可能被拖曳的 snapTo 打斷（Animatable 會取消前一個動畫）：被打斷也要清掉，
            // 否則面板停在畫面外、透明遮罩仍蓋住整個畫面吃掉所有點擊
            try {
                hidden.animateTo(1f, Motion.exit())
            } catch (e: CancellationException) {
                if (!isActive) throw e
            }
            state.clear()
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val h = constraints.maxHeight * 0.9f
        fun settle(velocity: Float) {
            if (hidden.value > 0.3f || velocity > 1200f) state.dismiss()
            else scope.launch { hidden.animateTo(0f, Motion.glass()) }
        }
        val nested = remember(content, h) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    // 面板被往下拖過時，往上滑先把面板推回去
                    if (available.y < 0 && hidden.value > 0f) {
                        val before = hidden.value
                        scope.launch { hidden.snapTo((before + available.y / h).coerceAtLeast(0f)) }
                        return Offset(0f, available.y)
                    }
                    return Offset.Zero
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (available.y > 0 && source == NestedScrollSource.UserInput) {
                        scope.launch { hidden.snapTo((hidden.value + available.y / h).coerceAtMost(1f)) }
                        return Offset(0f, available.y)
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (hidden.value > 0f) {
                        settle(available.y)
                        return available
                    }
                    return Velocity.Zero
                }
            }
        }
        // 遮罩（不用視窗 dim：每幀改 dim 會讓系統重新排版整個面板視窗，反而更卡）
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = (1f - hidden.value).coerceIn(0f, 1f) }
                .background(Color.Black.copy(alpha = 0.3f))
                // 只有展開時遮罩才接收點擊；點空白處關閉面板回到網頁
                .then(if (state.open) Modifier.pointerInput(content) { detectTapGestures { state.dismiss() } } else Modifier),
        )
        // 三級面板（設定、網站設定、書籤與歷史、下載項目…）用不透明底色，內容長、要好讀
        val sheetBg = if (tone.content.luminance() > 0.5f) com.liquid.browser.core.Palette.Surface2Dark else com.liquid.browser.core.Palette.BgLight
        // 面板本體：LiquidGlass 在沒有原生 backdrop 的手機上放進系統模糊視窗；其他手機用原本的玻璃
        LiquidGlass(
            haze, tone, SheetShape,
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(with(LocalDensity.current) { h.toDp() }),
            shiftY = { hidden.value * h },
            placeholderContent = false,
            contentModifier = Modifier
                .nestedScroll(nested)
                .draggable(
                    rememberDraggableState { d -> scope.launch { hidden.snapTo((hidden.value + d / h).coerceIn(0f, 1f)) } },
                    Orientation.Vertical,
                    onDragStopped = { v -> settle(v) },
                ),
        ) {
            // 滑動期間把面板內容畫成一張快取圖層（約一個面板大小的 GPU 記憶體，停下就釋放）：
            // 每幀只要平移這張圖，不必重畫每張卡片與文字；面板視窗裡沒有 WebView，可以安全離屏
            Column(Modifier.fillMaxSize().graphicsLayer {
                compositingStrategy = if (state.moving()) androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                else androidx.compose.ui.graphics.CompositingStrategy.Auto
            }) {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), Alignment.Center) {
                    Box(Modifier.size(36.dp, 5.dp).background(tone.content.copy(alpha = 0.25f), CircleShape))
                }
                content(state::dismiss)
            }
        }
    }
}
