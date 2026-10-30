package com.liquid.browser.ui.glass

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.luminance
import com.liquid.browser.core.Palette
import kotlin.math.roundToInt
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
    /** 打開選單外的下一層（設定、網站設定…）：選單往左推出、那一層從右推入，返回時選單從左推回；不重播變形動畫 */
    val push: Boolean = false,
    /** 選單內的下一層：面板內容推入式切換到這些項目（不重播變形動畫） */
    val sub: List<MenuItem>? = null,
    val onClick: () -> Unit = {},
)

@Immutable
class MenuSpec(
    val anchor: Rect,
    val items: List<MenuItem>,
    val width: Int = 260,
    val header: (@Composable ColumnScope.(menu: MenuState) -> Unit)? = null,
    /** 觸發按鈕的圖示（Container Transform 開始時畫在按鈕位置、前 30% 淡出）；沒有就不畫 */
    val anchorIcon: String? = null,
)

/** 浮動選單的狀態；anchor 為觸發元件在 root 座標的範圍，選單從那裡「長出」。 */
@Stable
class MenuState {
    var spec by mutableStateOf<MenuSpec?>(null)
        private set
    var open by mutableStateOf(false)
        private set
    /** 選單內推入的層（二、三級）；空的就是第一層 */
    val pages = mutableStateListOf<MenuItem>()
    /** 選單正被推到選單外的下一層（那一層讀它，決定用推入式進場） */
    var pushed by mutableStateOf(false)
        private set
    internal var returning = false
    private var last: MenuSpec? = null

    fun show(spec: MenuSpec) {
        this.spec = spec; last = spec
        pages.clear(); pushed = false; returning = false
        open = true
    }

    /** 已在關閉中再呼叫一次時直接清掉，避免殘留看不見的遮罩擋住點擊 */
    fun dismiss() { if (open) open = false else spec = null }

    /** 推入選單內的下一層 */
    fun enter(item: MenuItem) { pages.add(item) }

    /** 返回鍵：選單內有下一層就退一層，否則關閉 */
    fun back() { if (pages.isNotEmpty()) pages.removeAt(pages.lastIndex) else dismiss() }

    internal fun push() { pushed = true; open = false }

    /** 從選單外的下一層返回：選單從左邊推回來，停在離開時那一層（不重播變形） */
    fun restore() {
        val l = last ?: return
        spec = l; pushed = false; returning = true; open = true
    }
    internal fun clear() { spec = null }

    internal fun click(item: MenuItem) {
        when {
            item.sub != null -> enter(item)
            item.push -> { push(); item.onClick() }
            else -> { dismiss(); item.onClick() }
        }
    }
}

/** 選單面板的最終外形：20dp 圓角的卡片 */
private val PanelRadius = 20.dp
// Material 3 emphasized easing：開啟沿曲線正向播放；關閉沿同一條曲線反向播放（起步慢、離開快）
private val Emphasized = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private const val OpenMs = 380
private const val CloseMs = 280
// 推入／推出（選單內換層、推到選單外的下一層）：iOS 導覽 push 的曲線
private val PushEasing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
private const val PushMs = 350

/** 版面計算的暫存（排版階段寫、子元件讀）；內容高度是狀態：換層時高度變了，外層容器要跟著重新排版 */
private class MenuGeometry {
    var screenW = 0; var screenH = 0
    var panelW = 0
    var contentH by mutableIntStateOf(0)
    var current = Rect.Zero; var final = Rect.Zero
    var up = false
}

/**
 * 選單（Container Transform）：觸發按鈕的容器本身平滑變形成選單面板。
 *  - 位置、寬高、圓角（按鈕原本的形狀 → 20dp）、背景色（按鈕的底色 → 白色卡片，深色模式為深灰卡片）同時過渡
 *  - 以容器尺寸變化實作：內容一次排成最終寬度、留在最終位置，容器的裁切範圍長大把它露出來，不縮放內容、文字不變形
 *  - 時序：按鈕圖示在前 30% 淡出；選單項目在 40% 後依序淡入，每項間隔 40ms、帶 8dp 位移；背景遮罩同步淡入到 40%
 *  - 開啟 380ms（emphasized easing）；關閉 280ms 反向播放，面板縮回按鈕原位置與原形狀
 *  - 展開方向朝螢幕空間較大處（按鈕在右下就往左上展開）
 *  - 二、三級採推入式切換（面板內容左右推、面板高度跟著變），不重播變形
 *  - 系統「移除動畫」開啟時改為單純淡入淡出
 * 位置與大小在排版階段讀、圓角／顏色／透明度在繪製階段讀，動畫期間不重組。
 * 面板是不透明卡片，直接畫在主畫面（不用系統模糊視窗：視窗不能每幀改大小）。
 * 按鈕多半在系統模糊視窗裡、跟選單不在同一個視窗，不能用 SharedTransitionLayout（共用元素必須在同一個組合）；
 * 改以按鈕在螢幕上的範圍（anchor）當起點，逐幀內插到面板的範圍，效果同 matchedGeometryEffect。
 */
@Composable
fun GlassMenuHost(state: MenuState, haze: HazeState, tone: Tone) {
    val spec = state.spec ?: return
    val reduce = Motion.reduced
    val t = remember(spec) { Animatable(0f) }
    // 每一項自己的進場進度（0 是頂部快捷列）
    val itemAnim = remember(spec) { List(spec.items.size + 1) { Animatable(0f) } }
    // 推到選單外下一層的位移：0 = 在原位，-1 = 推到左邊（下一層蓋上來）
    val slide = remember(spec) { Animatable(0f) }
    LaunchedEffect(spec, state.open) {
        if (state.open && state.returning) {
            // 從下一層返回：已經是面板外形，直接從左邊推回
            t.snapTo(1f); itemAnim.forEach { it.snapTo(1f) }
            slide.snapTo(-1f)
            slide.animateTo(0f, tween(if (reduce) 160 else PushMs, easing = PushEasing))
        } else if (!state.open && state.pushed) {
            slide.animateTo(-1f, tween(if (reduce) 160 else PushMs, easing = PushEasing))
            state.clear()
        } else if (state.open) coroutineScope {
            launch { t.animateTo(1f, tween(if (reduce) 200 else OpenMs, easing = LinearEasing)) }
            itemAnim.forEachIndexed { i, a ->
                launch {
                    if (reduce) { a.snapTo(1f); return@launch }
                    delay((OpenMs * 0.4f).toLong() + i * 40L)
                    a.animateTo(1f, tween(220, easing = Emphasized))
                }
            }
        } else {
            coroutineScope {
                // 反向：項目先一起淡出（前 40%），容器同時開始縮回按鈕
                itemAnim.forEach { a -> launch { a.animateTo(0f, tween(if (reduce) 0 else (CloseMs * 0.4f).toInt())) } }
                launch { t.animateTo(0f, tween(if (reduce) 160 else CloseMs, easing = LinearEasing)) }
            }
            state.clear()
        }
    }
    // 變形進度（套用 emphasized easing）；移除動畫時固定在面板外形，只淡入淡出
    val e = { if (reduce) 1f else Emphasized.transform(t.value.coerceIn(0f, 1f)) }
    val density = LocalDensity.current
    val margin = with(density) { 12.dp.toPx() }
    val rise = with(density) { 8.dp.toPx() }
    val panelR = with(density) { PanelRadius.toPx() }
    val shadow = with(density) { 12.dp.toPx() }
    val dark = tone.content.luminance() > 0.5f
    val panelColor = if (dark) Palette.CardDark else Palette.CardLight
    val startColor = tone.tint
    val geo = remember(spec) { MenuGeometry() }

    Box(Modifier.fillMaxSize()) {
        // 背景遮罩：同步淡入到 40%；點空白處關閉
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = 0.4f * (if (reduce) t.value else e()) * (1f + slide.value) }
                .background(Color.Black)
                .then(if (state.open) Modifier.pointerInput(spec) { detectTapGestures { state.dismiss() } } else Modifier),
        )
        // 容器：排版階段算出目前的範圍（按鈕 → 面板），以這個大小放置
        Box(
            Modifier
                .layout { m, c ->
                    geo.screenW = c.maxWidth; geo.screenH = c.maxHeight
                    geo.panelW = min(with(density) { spec.width.dp.roundToPx() }, (c.maxWidth - margin * 2).toInt())
                    val a = spec.anchor
                    val h = geo.contentH.toFloat()
                    val left = a.center.x > c.maxWidth / 2f
                    geo.up = a.center.y > c.maxHeight / 2f
                    // 面板與按鈕對齊在「遠離空間較大處」的那個角，往空間大的方向長
                    val fx = (if (left) a.right - geo.panelW else a.left).coerceIn(margin, c.maxWidth - margin - geo.panelW)
                    val fy = (if (geo.up) a.bottom - h else a.top).coerceIn(margin, c.maxHeight - margin - h)
                    geo.final = Rect(fx, fy, fx + geo.panelW, fy + h)
                    val k = e()
                    geo.current = Rect(
                        lerp(a.left, geo.final.left, k), lerp(a.top, geo.final.top, k),
                        lerp(a.right, geo.final.right, k), lerp(a.bottom, geo.final.bottom, k),
                    )
                    val r = geo.current
                    val p = m.measure(Constraints.fixed(r.width.roundToInt().coerceAtLeast(1), r.height.roundToInt().coerceAtLeast(1)))
                    layout(c.maxWidth, c.maxHeight) { p.place(r.left.roundToInt(), r.top.roundToInt()) }
                }
                .graphicsLayer {
                    val k = e()
                    val r = lerp(geo.current.height / 2f, panelR, k)
                    shape = RoundedCornerShape(r.coerceAtLeast(0f))
                    clip = true
                    shadowElevation = shadow * k
                    alpha = (if (reduce) t.value else 1f) * (1f + slide.value * 0.7f)
                    translationX = if (reduce) 0f else slide.value * size.width * 0.3f
                }
                .drawBehind { drawRect(androidx.compose.ui.graphics.lerp(startColor, panelColor, e())) },
        ) {
            // 內容：排成最終寬度、留在最終位置（相對於目前的容器位移），由容器裁切露出；不縮放
            Column(
                Modifier
                    .layout { m, c ->
                        val p = m.measure(Constraints(minWidth = geo.panelW, maxWidth = geo.panelW, maxHeight = (geo.screenH * 0.7f).toInt()))
                        if (p.height != geo.contentH) geo.contentH = p.height
                        layout(c.maxWidth, c.maxHeight) {
                            p.place((geo.final.left - geo.current.left).roundToInt(), (geo.final.top - geo.current.top).roundToInt())
                        }
                    }
                    .verticalScroll(rememberScrollState()),
            ) {
                val dir = { if (geo.up) 1f else -1f }
                // 二、三級：內容左右推入（高度跟著動畫），容器不重播變形
                AnimatedContent(
                    state.pages.toList(),
                    transitionSpec = {
                        val fwd = targetState.size > initialState.size
                        val spec2 = tween<IntOffset>(PushMs, easing = PushEasing)
                        if (reduce) fadeIn(tween(160)) togetherWith fadeOut(tween(160))
                        else (slideInHorizontally(spec2) { if (fwd) it else -it / 3 } + fadeIn(tween(PushMs / 2)) togetherWith
                            slideOutHorizontally(spec2) { if (fwd) -it / 3 else it } + fadeOut(tween(PushMs / 2)))
                            .using(SizeTransform(clip = false) { _, _ -> tween(PushMs, easing = PushEasing) })
                    },
                    contentKey = { it.size },
                    label = "menuPage",
                ) { pages ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        val page = pages.lastOrNull()
                        if (page == null) {
                            spec.header?.let { header ->
                                Column(Modifier.graphicsLayer { val a = itemAnim[0].value; alpha = a; translationY = (1f - a) * rise * dir() }) { header(state) }
                            }
                            spec.items.forEachIndexed { i, item ->
                                MenuRow(item, tone, Modifier.graphicsLayer { val a = itemAnim[i + 1].value; alpha = a; translationY = (1f - a) * rise * dir() }) { state.click(item) }
                            }
                        } else {
                            // 下一層的標題列：‹ 標題（點了退回上一層）
                            Row(
                                Modifier.fillMaxWidth().clickable { state.back() }.padding(horizontal = 12.dp).height(46.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Sym("arrow_back_ios_new", tone.content, 18.dp)
                                Text(page.label, Modifier.padding(start = 8.dp), color = tone.content, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            }
                            MenuDivider(tone)
                            page.sub.orEmpty().forEach { item -> MenuRow(item, tone, Modifier) { state.click(item) } }
                        }
                    }
                }
            }
            // 按鈕原本的圖示：留在按鈕的位置，前 30% 淡出（關閉時最後 30% 淡回）
            spec.anchorIcon?.let { icon ->
                Box(
                    Modifier
                        .layout { m, c ->
                            val p = m.measure(Constraints())
                            layout(c.maxWidth, c.maxHeight) {
                                val a = spec.anchor
                                p.place((a.center.x - geo.current.left - p.width / 2f).roundToInt(), (a.center.y - geo.current.top - p.height / 2f).roundToInt())
                            }
                        }
                        .graphicsLayer { alpha = if (reduce) 0f else (1f - e() / 0.3f).coerceIn(0f, 1f) },
                ) { Sym(icon, tone.content, 20.dp) }
            }
        }
    }
}
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
