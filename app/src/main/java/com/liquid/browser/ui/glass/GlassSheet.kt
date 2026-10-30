package com.liquid.browser.ui.glass

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.draw.drawBehind
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
import kotlin.math.min

/** 底部面板的狀態：show 傳入內容，dismiss 關閉。 */
@Stable
class SheetState {
    var content by mutableStateOf<(@Composable (dismiss: () -> Unit) -> Unit)?>(null)
        private set
    var open by mutableStateOf(false)
        private set
    /** 從選單打開時，選單面板在螢幕上的範圍：面板從這裡變形長成（Container Transform）；null 是從下面升起 */
    var from: Rect? = null
        private set
    /** 關閉時縮回的範圍（打開選單的那顆按鈕）；選單已經關了，面板直接縮回按鈕 */
    internal var to: Rect? = null

    fun show(from: Rect? = null, to: Rect? = null, content: @Composable (dismiss: () -> Unit) -> Unit) {
        this.content = content
        this.from = from; this.to = to
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
// Container Transform：跟選單同一條 emphasized 曲線；開 380ms、縮回 280ms
private val Emphasized = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/** 圓角矩形外框（範圍、上緣圓角、下緣圓角）：變形時每幀換一個，只影響繪製 */
private class MorphShape(val r: Rect, val top: Float, val bottom: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
        Outline.Rounded(RoundRect(r, CornerRadius(top), CornerRadius(top), CornerRadius(bottom), CornerRadius(bottom)))
}

/**
 * 液態玻璃底部面板：以彈簧從下方滑入；可用手指往下拖曳關閉，拖曳時背景遮罩透明度跟著變化；
 * 內部清單捲到頂端後繼續往下拉，會改為拖曳面板（nested scroll）。
 * 從選單打開（from 不是 null）時改用 Container Transform：選單面板的容器變形成這個面板——
 * 範圍、圓角（20dp → 上 28／下 0）、底色（白卡 → 面板灰底）同時過渡；內容一次排在最終位置、由裁切範圍露出，不縮放；
 * 內容在 30% 後淡入。返回鍵反向縮回選單面板，選單接著出現在原位。
 */
@Composable
fun GlassSheetHost(state: SheetState, haze: HazeState, tone: Tone) {
    val content = state.content ?: return
    val scope = rememberCoroutineScope()
    val hidden = remember(content) { Animatable(if (state.from != null) 0f else 1f) } // 0 = 完全展開，1 = 收到畫面外
    val morph = remember(content) { Animatable(if (state.from != null) 0f else 1f) } // 0 = 選單面板的外形，1 = 面板
    val reduce = Motion.reduced
    // 變形的起點（開啟：選單面板；關閉：打開選單的按鈕）；在繪製階段讀
    var start by remember(content) { mutableStateOf(state.from) }
    state.anim = hidden
    LaunchedEffect(content, state.open) {
        if (state.open) {
            // 先讓內容在畫面外完成第一次組合與排版，下一幀才開始滑入：首次開啟時排版不會吃掉動畫的幀
            androidx.compose.runtime.withFrameNanos { }
            if (state.from != null) morph.animateTo(1f, tween(if (reduce) 200 else 380, easing = LinearEasing))
            else hidden.animateTo(0f, Motion.glass())
        }
        else if (state.from != null && hidden.value < 0.01f) {
            // 從選單變形出來的面板（沒被往下拖開）：反向縮回打開選單的那顆按鈕。
            // 在 k=1 時外形與起點無關，關閉前換起點不會跳
            state.to?.let { start = it }
            try {
                morph.animateTo(0f, tween(if (reduce) 160 else 280, easing = LinearEasing))
            } catch (e: CancellationException) {
                if (!isActive) throw e
            }
            state.clear()
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
        val panelTop = constraints.maxHeight - h
        val density = LocalDensity.current
        val topR = with(density) { Dimens.MenuRadius.toPx() }
        val menuR = with(density) { 20.dp.toPx() }
        val dark = tone.content.luminance() > 0.5f
        val menuColor = if (dark) com.liquid.browser.core.Palette.CardDark else com.liquid.browser.core.Palette.CardLight
        // 變形進度（emphasized）；移除動畫時不變形、只淡入淡出
        val e = { if (reduce) 1f else Emphasized.transform(morph.value.coerceIn(0f, 1f)) }
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
        // 遮罩（不用視窗 dim：每幀改 dim 會讓系統重新排版整個面板視窗，反而更卡）。
        // 從選單變形時由選單遮罩的 40% 接到面板遮罩的 30%
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    alpha = (1f - hidden.value).coerceIn(0f, 1f) * (if (state.from != null) androidx.compose.ui.util.lerp(1f, 0.75f, e()) else 0.75f)
                }
                .background(Color.Black.copy(alpha = 0.4f))
                // 只有展開時遮罩才接收點擊；點空白處關閉面板回到網頁
                .then(if (state.open) Modifier.pointerInput(content) { detectTapGestures { state.dismiss() } } else Modifier),
        )
        // 三級面板（設定、網站設定、書籤與歷史、下載項目…）用不透明底色，內容長、要好讀
        val sheetBg = if (dark) com.liquid.browser.core.Palette.Surface2Dark else com.liquid.browser.core.Palette.BgLight
        val from = state.from
        val pill = { r: Rect -> min(menuR, r.height / 2f) }
        // 面板本體：不透明底色畫在主畫面（不用玻璃）。底色本來就把玻璃整個蓋住，
        // 用系統模糊視窗只會多開一個視窗、滑動時每幀重算一整片看不見的模糊（實測開面板掉 10 幀、最長一幀 90ms）。
        // 位移與變形都在 graphicsLayer（繪製階段），動畫時不重新排版
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(with(density) { h.toDp() })
                .graphicsLayer {
                    translationY = hidden.value * h
                    if (from != null && !reduce) {
                        // 容器範圍：選單面板（換成面板自己的座標）→ 整個面板
                        val k = e()
                        val s0 = (start ?: from)
                        val st = s0.translate(0f, -panelTop)
                        val r = Rect(
                            androidx.compose.ui.util.lerp(st.left, 0f, k), androidx.compose.ui.util.lerp(st.top, 0f, k),
                            androidx.compose.ui.util.lerp(st.right, size.width, k), androidx.compose.ui.util.lerp(st.bottom, size.height, k),
                        )
                        shape = MorphShape(r, androidx.compose.ui.util.lerp(pill(s0), topR, k), androidx.compose.ui.util.lerp(pill(s0), 0f, k))
                    } else shape = SheetShape
                    clip = true
                    alpha = if (from != null && reduce) morph.value else 1f
                }
                .drawBehind { drawRect(if (from != null) androidx.compose.ui.graphics.lerp(menuColor, sheetBg, e()) else sheetBg) }
                .nestedScroll(nested)
                .draggable(
                    rememberDraggableState { d -> scope.launch { hidden.snapTo((hidden.value + d / h).coerceIn(0f, 1f)) } },
                    Orientation.Vertical,
                    enabled = morph.value >= 1f || from == null,
                    onDragStopped = { v -> settle(v) },
                ),
        ) {
            // 滑動期間把面板內容畫成一張快取圖層（約一個面板大小的 GPU 記憶體，停下就釋放）：
            // 每幀只要平移這張圖，不必重畫每張卡片與文字；面板視窗裡沒有 WebView，可以安全離屏
            Column(Modifier.fillMaxSize().graphicsLayer {
                compositingStrategy = if (state.moving() || morph.isRunning) androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                else androidx.compose.ui.graphics.CompositingStrategy.Auto
                // 變形時內容在 30% 之後淡入（縮回時反向，先淡出）
                if (from != null && !reduce) alpha = ((e() - 0.3f) / 0.4f).coerceIn(0f, 1f)
            }) {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), Alignment.Center) {
                    Box(Modifier.size(36.dp, 5.dp).background(tone.content.copy(alpha = 0.25f), CircleShape))
                }
                content(state::dismiss)
            }
        }
    }
}