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
        val slide = with(androidx.compose.ui.platform.LocalDensity.current) { 16.dp.toPx() }
        Layout(
            content = {
                // 選單本體：LiquidGlass 在沒有原生 backdrop 的手機上會放進系統模糊視窗（HyperOS 控制中心同一套）
                LiquidGlass(
                    haze, tone, MenuShape,
                    Modifier
                        .onPlaced { cardPos = it.positionInRoot() },
                    contentAlignment = Alignment.TopStart,
                    // 不用縮放彈出：朝按鈕方向輕輕滑入（選單在按鈕上方就從下往上），內容逐行淡入
                    shiftY = { (1f - progress.value) * slide * (if (spec.anchor.center.y > cardPos.y) 1f else -1f) },
                ) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                        spec.header?.let { header ->
                            Column(Modifier.graphicsLayer { alpha = stagger(progress.value, 0) }) { header(state::dismiss) }
                        }
                        spec.items.forEachIndexed { i, item ->
                            MenuRow(item, tone, Modifier.graphicsLayer { alpha = stagger(progress.value, i + 1) }) {
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

/** 第 i 個項目的淡入進度：每項延遲一點點。 */
private fun stagger(p: Float, i: Int) = ((p - 0.15f - i * 0.03f) / 0.3f).coerceIn(0f, 1f)

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
