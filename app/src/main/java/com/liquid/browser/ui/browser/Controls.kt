package com.liquid.browser.ui.browser

import com.liquid.browser.core.t

import androidx.compose.foundation.Canvas
import com.liquid.browser.browser.DlStatus
import com.liquid.browser.browser.DownloadEngine
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Velocity
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.liquid.browser.browser.BrowserViewModel
import com.liquid.browser.browser.Tab
import com.liquid.browser.core.Dimens
import com.liquid.browser.core.Motion
import com.liquid.browser.core.Palette
import com.liquid.browser.ui.Sym
import com.liquid.browser.ui.SymButton
import com.liquid.browser.ui.accent
import com.liquid.browser.ui.glass.Tone
import com.liquid.browser.ui.glass.LocalReduceTransparency
import com.liquid.browser.ui.glass.liquidGlass
import com.liquid.browser.ui.glass.liquidGlassGroup
import com.liquid.browser.ui.glass.rememberGlassStyle
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * 底部浮動控制列：上一頁圓鈕 · 網址膠囊 · 分頁圓鈕（＋聚焦時的「取消」）。
 *
 * 用自訂 Layout 手動擺放，讓同一個進度值驅動形變：
 *  - 收合 p（0→1）：圓鈕往網址列滑入並縮到與小膠囊同高，間距由 +8dp 變成 -直徑，最後完全藏進膠囊；
 *    間距一旦小於 MergeSpacing/2，玻璃容器就以 metaball 方式把它們融成一滴。
 *  - 聚焦 f（0→1）：圓鈕讓出空間給網址列，右側出現「取消」。
 *  - 上一頁 b（0→1）：沒有上一頁時左鈕縮小淡出，網址列以彈簧延展補位。
 * 所有進度值都在 measure／draw 階段讀取，捲動時只重新排版與重繪，不重組。
 */
@Composable
fun Controls(
    vm: BrowserViewModel,
    tab: Tab,
    haze: HazeState,
    tone: Tone,
    focused: Boolean,
    query: TextFieldValue,
    onQuery: (TextFieldValue) -> Unit,
    onFocus: () -> Unit,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
    onTabs: () -> Unit,
    onDownloads: () -> Unit,
    onPageMenu: (Rect) -> Unit,
    /** 網址列的翻譯鈕；anchor 給 YouTube 的兩項小選單 */
    onTranslate: (Rect) -> Unit,
    /** 左側返回鈕（Safari 精簡版面）：點一下上一頁、長按列出上一頁歷史 */
    onBack: () -> Unit,
    backEnabled: Boolean,
    onBackLongPress: (Rect) -> Unit,
    onTabsLongPress: (Rect) -> Unit,
    pillGestures: PillGestures,
    alignTop: Boolean = false,
    /** 每次排版回報每個玻璃元件的範圍（相對於本元件），系統視窗模糊用來決定各視窗的位置與大小 */
    onGlassBounds: ((List<Rect>) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var tabsBounds by remember { mutableStateOf(Rect.Zero) }
    val f = animateFloatAsState(if (focused) 1f else 0f, Motion.glass(), label = "focus")
    // 往下捲時網址列整個滑出畫面（BrowserScreen 位移整列），不再縮成小膠囊：外形固定在展開狀態
    val p = { 0f }
    val iconAlpha = { (1 - p() * 2.5f).coerceIn(0f, 1f) }
    val circleStyle = rememberGlassStyle(CircleShape, tone.tint)
    val cancelI = remember { MutableInteractionSource() }
    val noMerge = Build.VERSION.SDK_INT < 33 || LocalReduceTransparency.current
    // 完全隱藏的玻璃元件必須移出組合，否則仍會留在玻璃容器裡與膠囊融合出殘影；
    // derivedStateOf 讓重組只發生在跨越門檻的那一幀
    // 收合過半時圓鈕已大半藏在膠囊下，直接移除（毛玻璃底層無法隨 metaball 融合，留著會看到一顆亮圓）
    val showTabs by remember { derivedStateOf { f.value < 0.999f && p() < 0.5f } }
    val showCancel by remember { derivedStateOf { f.value > 0.001f } }
    val expand = { vm.collapsed = false }
    val accent = accent

    Layout(
        modifier = Modifier.fillMaxWidth().height(Dimens.Control).liquidGlassGroup(),
        content = {
            // 分頁（兩個重疊方框＋分頁數）
            val tabsI = remember { MutableInteractionSource() }
            if (showTabs) Box(
                Modifier.layoutId("tabs").testTag("tabs_button").legacyFade(p, noMerge).liquidGlass(haze, circleStyle, CircleShape, tabsI)
                    .onGloballyPositioned { tabsBounds = it.boundsInRoot() }
                    .combinedClickable(
                        interactionSource = tabsI, indication = null,
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onTabsLongPress(tabsBounds)
                        },
                    ) { if (p() > 0.5f) expand() else onTabs() },
                Alignment.Center,
            ) {
                TabsIcon(
                    vm.visibleTabs().size, tone.content,
                    Modifier.graphicsLayer { alpha = iconAlpha() * (1 - f.value) },
                )
            }

            // 返回（Safari 精簡版面左側的圓鈕）：沒有上一頁時變淡、不能按；長按列出上一頁歷史
            var backBounds by remember { mutableStateOf(Rect.Zero) }
            val backI = remember { MutableInteractionSource() }
            if (showTabs) Box(
                Modifier.layoutId("back").liquidGlass(haze, circleStyle, CircleShape, backI)
                    .onGloballyPositioned { backBounds = it.boundsInRoot() }
                    .semantics { contentDescription = t("上一頁") }
                    .combinedClickable(
                        interactionSource = backI, indication = null, enabled = backEnabled,
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onBackLongPress(backBounds)
                        },
                    ) { onBack() },
                Alignment.Center,
            ) {
                Sym("arrow_back_ios_new", tone.content, 20.dp, Modifier.graphicsLayer { alpha = (if (backEnabled) 1f else 0.3f) * (1 - f.value) })
            }

            // 輸入時右側的 ✕（Safari）：收起輸入、回到網頁；送出用鍵盤的「前往」
            // 與圓鈕同一套毛玻璃；左側 8dp 為與網址列的間隙
            if (showCancel) Box(
                Modifier.layoutId("cancel").padding(start = 8.dp).liquidGlass(haze, circleStyle, CircleShape, cancelI)
                    .semantics { contentDescription = t("取消") }
                    .clickable(cancelI, null) { onCancel() },
                Alignment.Center,
            ) {
                Sym("close", tone.content, 22.dp, Modifier.graphicsLayer { alpha = f.value })
            }

            // 網址膠囊
            val pillI = remember { MutableInteractionSource() }
            Box(
                Modifier.layoutId("pill").testTag("url_bar").liquidGlass(haze, circleStyle, CircleShape, pillI)
                    .pillDrag(!focused, pillGestures)
                    .clickable(pillI, null, enabled = !focused) { if (p() > 0.5f) expand() else onFocus() },
            ) {
                // 網域文字與完整網址輸入框之間淡入淡出
                Crossfade(focused, animationSpec = Motion.timed(Motion.ShortMs), label = "url") { f ->
                    if (f) UrlInput(query, tone, accent, onQuery, onSubmit)
                    else {
                        // 翻譯鈕：網頁已經是翻譯目標語言時不出現（YouTube 有字幕翻譯，一律出現；翻譯中／已翻譯也留著，用來切回原文）
                        val target = vm.settings.collectAsState().value.translateTarget
                        val canTranslate = !tab.isBlank && (tab.isYouTube || tab.translated || !tab.alreadyIn(target))
                        UrlLabel(tab, tone, p, iconAlpha, onPageMenu, onDownloads, onTranslate, canTranslate) { expand() }
                    }
                }
                Progress(tab.progress, accent, Modifier.align(Alignment.BottomStart))
            }

        },
    ) { m, c ->
        val pv = p()
        val fv = f.value
        val full = c.maxWidth.toFloat()
        val d0 = Dimens.Control.toPx()
        val h = lerp(d0, Dimens.ControlCollapsed.toPx(), pv)
        val gap0 = Dimens.ControlGap.toPx()
        val gap = lerp(gap0, -h, pv)
        // Safari 精簡版面：(返回) [網址列] (分頁)；輸入時兩側圓鈕收進去、右側換成 ✕
        val tw = h * (1 - fv)
        val tgap = gap * (1 - fv)
        val cw = (d0 + gap0) * fv
        val pillFull = full - (d0 + gap0) * 2 * (1 - fv) - cw
        val pillW = lerp(pillFull, min(Dimens.CollapsedPillMaxWidth.toPx(), full * 0.6f), pv)
        val total = tw + tgap + pillW + tgap + tw + cw
        val backX = (full - total) / 2
        val pillX = backX + tw + tgap
        // 直向底緣對齊：高度縮小時中心自然下移，與手勢導覽列的間隙不變；橫向則貼齊頂端
        val y = if (alignTop) 0 else (d0 - h).toInt()
        fun fixed(w: Float) = Constraints.fixed(w.toInt().coerceAtLeast(0), h.toInt())
        fun part(id: String, w: Float) = m.firstOrNull { it.layoutId == id }?.measure(fixed(w))
        val pillP = part("pill", pillW)!!
        val tabsP = part("tabs", tw)
        val cancelP = part("cancel", cw)
        val backP = part("back", tw)
        layout(c.maxWidth, d0.toInt()) {
            // 返回鈕、網址列、分頁鈕各一塊（系統模糊時各自一個視窗，之間的空隙不模糊）
            onGlassBounds?.invoke(
                listOf(
                    Rect(backX, y.toFloat(), backX + tw, y + h),
                    Rect(pillX, y.toFloat(), pillX + pillW, y + h),
                    // 收合時分頁鈕會滑進網址列：只算網址列右邊露出來的那段，兩塊不重疊（重疊處會被模糊、上色兩次而有色差）
                    Rect(maxOf(pillX + pillW + tgap, pillX + pillW), y.toFloat(), pillX + pillW + tgap + tw, y + h),
                ),
            )
            backP?.place(backX.toInt(), y)
            pillP.place(pillX.toInt(), y)
            tabsP?.place((pillX + pillW + tgap).toInt(), y)
            cancelP?.place((pillX + pillW + tgap + tw).toInt(), y)
        }
    }
}

/** 網址列上的下載圖示：下載中以外圈弧線顯示所有進行中項目的平均進度 */
@Composable
private fun DownloadIndicator(tone: Tone, alpha: () -> Float, onClick: () -> Unit) {
    val running = DownloadEngine.tasks.filter { it.status == DlStatus.Running }
    val progress = if (running.isEmpty()) 0f else running.map { it.progress }.average().toFloat()
    val accent = accent
    Box(
        Modifier.size(36.dp).graphicsLayer { this.alpha = alpha() }.clip(CircleShape)
            .clickable(onClickLabel = t("下載項目"), onClick = onClick)
            .semantics { contentDescription = if (running.isEmpty()) t("下載項目") else t("下載中 %1\$s%", (progress * 100).toInt()) },
        Alignment.Center,
    ) {
        if (running.isNotEmpty()) Canvas(Modifier.size(26.dp)) {
            val st = 2.dp.toPx()
            drawCircle(tone.content.copy(alpha = 0.15f), style = Stroke(st))
            drawArc(accent, -90f, 360f * progress, false, style = Stroke(st, cap = StrokeCap.Round))
        }
        Sym("download", if (running.isEmpty()) tone.content else accent, if (running.isEmpty()) 20.dp else 16.dp)
    }
}

/**
 * 分頁圖示（仿 Safari）：前方圓角方框內置中顯示分頁數，後方方框只露出右上角的 L 形邊。
 * 數字用 TextMeasurer 量測後畫在方框正中央（Text 元件的字型上下留白會讓數字偏移）。
 */
@Composable
internal fun TabsIcon(count: Int, color: Color, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    fun label(n: Int) = if (n > 99) "∞" else "$n"
    fun style(l: String) = TextStyle(color = color, fontSize = if (l.length > 1) 10.sp else 11.sp, fontWeight = FontWeight.SemiBold)
    // 數字改變時滾動：增加時新數字從下方滾上來、減少時從上方滾下來
    var shown by remember { mutableIntStateOf(count) }
    var prev by remember { mutableIntStateOf(count) }
    val roll = remember { Animatable(1f) }
    LaunchedEffect(count) {
        if (count != shown) {
            prev = shown
            shown = count
            roll.snapTo(0f)
            roll.animateTo(1f, Motion.glass())
        }
    }
    Canvas(modifier.size(23.dp).semantics { contentDescription = t("%1\$s 個分頁", count) }) {
        val stroke = 1.7.dp.toPx()
        val box = 17.dp.toPx()
        val d = 3.5.dp.toPx()
        val r = CornerRadius(4.5.dp.toPx())
        val left = stroke / 2
        val top = size.height - box - stroke / 2
        // 後方方框＝前方方框往右上偏移 d，只畫露出的上、右兩邊
        val bl = left + d
        val bt = top - d
        val br = bl + box
        val back = androidx.compose.ui.graphics.Path().apply {
            moveTo(bl, bt + r.y)
            arcTo(androidx.compose.ui.geometry.Rect(bl, bt, bl + r.x * 2, bt + r.y * 2), 180f, 90f, false)
            lineTo(br - r.x, bt)
            arcTo(androidx.compose.ui.geometry.Rect(br - r.x * 2, bt, br, bt + r.y * 2), 270f, 90f, false)
            lineTo(br, bt + box - r.y)
        }
        drawPath(back, color.copy(alpha = 0.55f), style = Stroke(stroke, cap = StrokeCap.Round))
        drawRoundRect(color, topLeft = Offset(left, top), size = Size(box, box), cornerRadius = r, style = Stroke(stroke))
        val p = roll.value
        val dir = if (shown >= prev) 1f else -1f
        val shift = box * 0.7f
        clipRect(left, top, left + box, top + box) {
            fun draw(l: String, dy: Float, alpha: Float) {
                if (alpha <= 0f) return
                val t = measurer.measure(l, style(l))
                drawText(t, topLeft = Offset(left + (box - t.size.width) / 2, top + (box - t.size.height) / 2 + dy), alpha = alpha.coerceIn(0f, 1f))
            }
            if (p < 1f) draw(label(prev), -dir * shift * p, 1f - p)
            draw(label(shown), dir * shift * (1f - p), p)
        }
    }
}

/** Android 12 以下或「降低透明度」時沒有 metaball 融合：圓鈕在收合時直接淡出，避免疊在膠囊上變成獨立圓盤。 */
private fun Modifier.legacyFade(p: () -> Float, noMerge: Boolean) =
    if (!noMerge) this else graphicsLayer { alpha = (1f - p() * 1.6f).coerceIn(0f, 1f) }

/** 網址列上的拖曳手勢：由 BrowserScreen 判斷方向（左右切換分頁、往上進入總覽）。 */
interface PillGestures {
    fun onStart()
    fun onDrag(delta: Offset)
    fun onEnd(velocity: Velocity)
}

private fun Modifier.pillDrag(enabled: Boolean, g: PillGestures) = pointerInput(enabled) {
    if (!enabled) return@pointerInput
    val tracker = VelocityTracker()
    var total = Offset.Zero
    detectDragGestures(
        onDragStart = {
            tracker.resetTracking()
            total = Offset.Zero
            g.onStart()
        },
        onDragEnd = { g.onEnd(tracker.calculateVelocity()) },
        onDragCancel = { g.onEnd(Velocity.Zero) },
    ) { change, d ->
        change.consume()
        // 膠囊本身會跟手移動，所以用累計位移而非區域座標計算速度
        total += d
        tracker.addPosition(change.uptimeMillis, total)
        g.onDrag(d)
    }
}

/** 未聚焦的網址列：左 aA 頁面選單、中網域、右重新整理／停止。收合時兩側淡出、網域縮小。 */
@Composable
internal fun UrlLabel(
    tab: Tab, tone: Tone, p: () -> Float, iconAlpha: () -> Float, onPageMenu: (Rect) -> Unit, onDownloads: () -> Unit,
    onTranslate: (Rect) -> Unit, showTranslate: Boolean, onCollapsedTap: () -> Unit,
) {
    var menuBounds by remember { mutableStateOf(Rect.Zero) }
    var trBounds by remember { mutableStateOf(Rect.Zero) }
    // 收合時兩側按鈕不可見，點到它們等同點小膠囊（展開）；不在組合階段讀進度，避免捲動時重組
    Row(Modifier.fillMaxSize().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        SymButton(
            // 頁面選單：Safari 的 ≡（網頁的動作）
            "menu", tone.content, Modifier.onGloballyPositioned { menuBounds = it.boundsInRoot() }, alpha = iconAlpha, label = t("頁面選單"),
        ) { if (p() > 0.5f) onCollapsedTap() else onPageMenu(menuBounds) }
        Row(
            Modifier.weight(1f).graphicsLayer {
                val s = lerp(1f, 0.78f, p())
                scaleX = s
                scaleY = s
            },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (tab.incognito) Sym("visibility_off", tone.content.copy(alpha = 0.7f), 16.dp, Modifier.padding(end = 6.dp))
            if (tab.insecure) Text(t("不安全 "), color = Palette.Gray, fontSize = 16.sp, maxLines = 1)
            Text(
                if (tab.isBlank) t("搜尋或輸入網址") else tab.domain,
                color = if (tab.isBlank) tone.content.copy(alpha = 0.6f) else tone.content,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 網址列右側的按鈕出現／消失：從原位放大淡入、網域文字平順讓位（不是突然跳出、把文字擠開）；
        // 進場 450ms 減速、退場 300ms 加速
        val enter = fadeIn(Motion.timed()) + scaleIn(Motion.glass(), initialScale = 0.6f) + expandHorizontally(Motion.glass())
        val exit = fadeOut(Motion.exit()) + scaleOut(Motion.exit(), targetScale = 0.6f) + shrinkHorizontally(Motion.exit())
        // 有下載項目時顯示下載圖示（下載中外圈顯示總進度），點擊進入「下載項目」
        AnimatedVisibility(DownloadEngine.tasks.isNotEmpty(), enter = enter, exit = exit) {
            DownloadIndicator(tone, iconAlpha) { if (p() > 0.5f) onCollapsedTap() else onDownloads() }
        }
        // 翻譯鈕：已翻譯（或 YouTube 字幕翻譯開著）時用強調色，一眼看出這頁正在翻譯；顏色漸變而不是瞬間換色
        AnimatedVisibility(showTranslate, enter = enter, exit = exit) {
            val trColor by animateColorAsState(if (tab.translated || tab.ytSubs) accent else tone.content, Motion.timed(), label = "tr")
            SymButton(
                "translate", trColor,
                Modifier.onGloballyPositioned { trBounds = it.boundsInRoot() }, size = 20.dp, alpha = iconAlpha,
                label = when {
                    tab.isYouTube -> t("翻譯選項")
                    tab.translated -> t("顯示原文")
                    else -> t("翻譯網頁")
                },
            ) { if (p() > 0.5f) onCollapsedTap() else onTranslate(trBounds) }
        }
        val loading = tab.progress < 100
        SymButton(
            if (loading) "close" else "refresh", tone.content, size = 20.dp,
            enabled = !tab.isBlank, alpha = { if (tab.isBlank) 0f else iconAlpha() },
        ) {
            when {
                p() > 0.5f -> onCollapsedTap()
                loading -> tab.stop()
                else -> tab.refresh()
            }
        }
    }
}

/** 聚焦的輸入框：完整網址全選、清除鈕、按「前往」送出。 */
@Composable
internal fun UrlInput(query: TextFieldValue, tone: Tone, accent: Color, onQuery: (TextFieldValue) -> Unit, onSubmit: (String) -> Unit) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { fr.requestFocus() }
    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Sym("search", tone.content.copy(alpha = 0.6f), 20.dp)
        BasicTextField(
            query, onQuery,
            Modifier.weight(1f).padding(horizontal = 8.dp).focusRequester(fr),
            singleLine = true,
            textStyle = TextStyle(color = tone.content, fontSize = 16.sp),
            cursorBrush = SolidColor(accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = { onSubmit(query.text) }),
        )
        if (query.text.isNotEmpty()) SymButton("cancel", tone.content.copy(alpha = 0.5f), size = 18.dp) { onQuery(TextFieldValue()) }
    }
}

/** 沿膠囊底緣的藍色進度線：平滑增長，完成時淡出。只改 graphicsLayer，不重新排版。 */
@Composable
internal fun Progress(progress: Int, accent: Color, modifier: Modifier) {
    val loading = progress < 100
    val alpha by animateFloatAsState(if (loading) 1f else 0f, tween(if (loading) 0 else 400), label = "pa")
    val frac by animateFloatAsState(progress / 100f, Motion.standard(), label = "pf")
    Box(
        modifier.padding(horizontal = 18.dp).fillMaxWidth().height(2.dp)
            .graphicsLayer {
                this.alpha = alpha
                scaleX = frac
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .background(accent, CircleShape),
    )
}
