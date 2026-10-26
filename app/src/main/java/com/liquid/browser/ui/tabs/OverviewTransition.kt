package com.liquid.browser.ui.tabs

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.liquid.browser.browser.Tab
import com.liquid.browser.core.Dimens
import kotlin.math.roundToInt

/**
 * 分頁總覽的 shared element 轉場狀態。
 * progress：0 = 全螢幕網頁、1 = 縮成總覽中的卡片。進入總覽 0→1，點卡片開啟 1→0。
 * 「飛行卡片」用分頁的快取縮圖繪製（WebView 本身無法參與 Compose 轉場），轉場結束後換回即時網頁。
 */
@Stable
class OverviewTransition {
    val progress = Animatable(0f)
    var active by mutableStateOf(false)

    /** 正在飛的分頁；null 表示「新增分頁」（從＋按鈕長出空白頁） */
    var tab by mutableStateOf<Tab?>(null)

    /** 自訂起點（往上滑手勢時，網頁已經縮小的位置） */
    var from by mutableStateOf<Rect?>(null)

    /** 總覽中每張卡片截圖區的位置（root 座標） */
    val cardBounds = mutableStateMapOf<Long, Rect>()
    var plusBounds by mutableStateOf(Rect.Zero)
}

private fun lerp(a: Rect, b: Rect, t: Float) = Rect(
    lerp(a.left, b.left, t), lerp(a.top, b.top, t), lerp(a.right, b.right, t), lerp(a.bottom, b.bottom, t),
)

/** 飛行卡片：位置、大小在排版階段、圓角在繪製階段依 progress 內插，動畫中不重組。 */
@Composable
fun FlyingCard(t: OverviewTransition, full: Rect, bg: Color) {
    if (!t.active) return
    val tab = t.tab
    Box(
        Modifier
            .layout { m, c ->
                val p = t.progress.value
                val start = t.from ?: full
                val end = if (tab == null) t.plusBounds else t.cardBounds[tab.id] ?: full
                val r = lerp(start, end, p)
                val pl = m.measure(Constraints.fixed(r.width.roundToInt().coerceAtLeast(1), r.height.roundToInt().coerceAtLeast(1)))
                layout(c.maxWidth, c.maxHeight) { pl.place(r.left.roundToInt(), r.top.roundToInt()) }
            }
            .graphicsLayer {
                val target = if (tab == null) 24.dp else Dimens.CardRadius
                // 彈簧會略微超出 0～1，圓角不可為負
                shape = RoundedCornerShape(lerp(0.dp, target, t.progress.value.coerceIn(0f, 1f)))
                clip = true
            }
            .background(bg),
    ) {
        tab?.thumbnail?.let {
            Image(it, null, Modifier.fillMaxSize(), alignment = Alignment.TopCenter, contentScale = ContentScale.Crop)
        }
    }
}
