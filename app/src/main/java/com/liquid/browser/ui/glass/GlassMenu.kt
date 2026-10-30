package com.liquid.browser.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.liquid.browser.core.Dimens
import com.liquid.browser.core.Motion
import com.liquid.browser.ui.Sym
import dev.chrisbanes.haze.HazeState
import kotlin.math.min

@Immutable
data class MenuItem(
    val label: String,
    val icon: String? = null,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

@Immutable
class MenuSpec(
    val anchor: Rect,
    val items: List<MenuItem>,
    val width: Int = 260,
    val header: (@Composable ColumnScope.(dismiss: () -> Unit) -> Unit)? = null,
)

/** 浮動玻璃選單的狀態；anchor 為觸發元件在 root 座標的範圍，選單從那裡「長出」。 */
@Stable
class MenuState {
    var spec by mutableStateOf<MenuSpec?>(null)
        private set
    var open by mutableStateOf(false)
        private set

    fun show(spec: MenuSpec) {
        this.spec = spec
        open = true
    }

    /** 已在關閉中再呼叫一次時直接清掉，避免殘留看不見的遮罩擋住點擊 */
    fun dismiss() { if (open) open = false else spec = null }
    internal fun clear() { spec = null }
}

private val MenuShape = RoundedCornerShape(Dimens.MenuRadius)

/**
 * 選單容器：與網頁在同一視窗內（玻璃才能取樣網頁），背景輕微變暗。
 * 開啟：玻璃先以觸發按鈕的大小與位置出現，再以彈簧擴張成選單外形，內容依序淡入（stagger）；
 * 關閉：內容先淡出，再縮回按鈕。動畫只動 graphicsLayer。
 */
@Composable
fun GlassMenuHost(state: MenuState, haze: HazeState, tone: Tone) {
    val spec = state.spec ?: return
    val progress = remember(spec) { Animatable(0f) }
    LaunchedEffect(spec, state.open) {
        if (state.open) progress.animateTo(1f, Motion.glass())
        else {
            progress.animateTo(0f, Motion.exit())
            state.clear()
        }
    }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) }
                .background(Color.Black.copy(alpha = 0.18f))
                .then(if (state.open) Modifier.pointerInput(spec) { detectTapGestures { state.dismiss() } } else Modifier),
        )
        var cardPos by remember(spec) { mutableStateOf(Offset.Zero) }
        var cardSize by remember(spec) { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
        val density = androidx.compose.ui.platform.LocalDensity.current
        val slide = with(density) { 16.dp.toPx() }
        val rise = with(density) { 10.dp.toPx() }
        // 選單在按鈕上方（從下往上長）還是下方
        val above = { spec.anchor.center.y > cardPos.y }
        Layout(
            content = {
                // 選單本體：LiquidGlass 在沒有原生 backdrop 的手機上會放進系統模糊視窗（HyperOS 控制中心同一套）。
                // 系統模糊視窗只能整塊位移、不能縮放（每幀改視窗大小很貴），所以玻璃朝按鈕方向輕輕滑入，
                // 動感放在玻璃裡的內容：
                //  - 從按鈕長出來：內容以「最靠近按鈕的角落」為原點，從 90% 放大到 100%
                //  - 逐行浮現：每行間隔約 30ms 淡入並往上（或往下）滑 10dp 到定位，像一道波
                //  - 收起較快、較單純：整體縮到 96% 同時淡出，不逐行
                // 全部是減速曲線、不回彈；只改 graphicsLayer（繪製階段），不重新排版
                LiquidGlass(
                    haze, tone, MenuShape,
                    Modifier.onPlaced { cardPos = it.positionInRoot(); cardSize = it.size },
                    contentAlignment = Alignment.TopStart,
                    shiftY = { (1f - progress.value) * slide * (if (above()) 1f else -1f) },
                    // 關閉時玻璃連同背後的模糊一起淡出（之前只有內容淡出，玻璃停在畫面上 280ms 再一下子消失，看起來像延遲）
                    windowAlpha = { if (state.open) 1f else progress.value.coerceIn(0f, 1f) },
                ) {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()).padding(vertical = 6.dp).graphicsLayer {
                            val p = progress.value.coerceIn(0f, 1f)
                            val s = if (state.open) androidx.compose.ui.util.lerp(0.9f, 1f, p) else androidx.compose.ui.util.lerp(0.96f, 1f, p)
                            scaleX = s; scaleY = s
                            val px = if (cardSize.width > 0) ((spec.anchor.center.x - cardPos.x) / cardSize.width).coerceIn(0f, 1f) else 0.5f
                            transformOrigin = TransformOrigin(px, if (above()) 1f else 0f)
                            if (!state.open) alpha = ((p - 0.15f) / 0.6f).coerceIn(0f, 1f)
                        },
                    ) {
                        val dir = { if (above()) 1f else -1f }
                        spec.header?.let { header ->
                            Column(Modifier.graphicsLayer { val a = stagger(progress.value, 0, state.open); alpha = a; translationY = (1f - a) * rise * dir() }) { header(state::dismiss) }
                        }
                        spec.items.forEachIndexed { i, item ->
                            MenuRow(item, tone, Modifier.graphicsLayer { val a = stagger(progress.value, i + 1, state.open); alpha = a; translationY = (1f - a) * rise * dir() }) {
                                state.dismiss()
                                item.onClick()
                            }
                        }
                    }
                }
            },
        ) { m, c ->
            val margin = 12.dp.roundToPx()
            val maxW = min(spec.width.dp.roundToPx(), c.maxWidth - margin * 2)
            val maxH = (c.maxHeight * 0.7f).toInt()
            val p = m[0].measure(Constraints(maxWidth = maxW, minWidth = maxW, maxHeight = maxH))
            val a = spec.anchor
            val x = (if (a.center.x < c.maxWidth / 2) a.left.toInt() else a.right.toInt() - p.width)
                .coerceIn(margin, c.maxWidth - margin - p.width)
            val gap = 8.dp.roundToPx()
            val y = (if (a.center.y > c.maxHeight / 2) a.top.toInt() - p.height - gap else a.bottom.toInt() + gap)
                .coerceIn(margin, c.maxHeight - margin - p.height)
            layout(c.maxWidth, c.maxHeight) { p.place(x, y) }
        }
    }
}

/** 第 i 個項目的進場進度：每項晚一點點（打開時逐行；收起時不逐行，由整體淡出處理） */
private fun stagger(p: Float, i: Int, opening: Boolean) =
    if (!opening) 1f else ((p - 0.08f - i * 0.035f) / 0.4f).coerceIn(0f, 1f)

@Composable
private fun MenuRow(item: MenuItem, tone: Tone, modifier: Modifier, onClick: () -> Unit) {
    val color = if (item.destructive) Color(0xFFFF3B30) else tone.content
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp).height(46.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(item.label, Modifier.weight(1f), color = color, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (item.icon != null) Sym(item.icon, color, 20.dp, Modifier.padding(start = 12.dp))
    }
}

/** 選單內的分隔線。 */
@Composable
fun MenuDivider(tone: Tone) {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(0.5.dp).background(tone.content.copy(alpha = 0.15f)))
}
