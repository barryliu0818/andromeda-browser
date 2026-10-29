package com.liquid.browser.ui.tabs

import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.liquid.browser.core.t

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquid.browser.browser.BrowserViewModel
import com.liquid.browser.browser.Tab
import com.liquid.browser.core.Dimens
import com.liquid.browser.core.Motion
import com.liquid.browser.core.Palette
import com.liquid.browser.ui.Reorder
import com.liquid.browser.ui.Sym
import com.liquid.browser.ui.accent
import com.liquid.browser.ui.reorderItem
import com.liquid.browser.ui.swipeToDismiss
import com.liquid.browser.ui.glass.LiquidGlass
import com.liquid.browser.ui.glass.Tone
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch

/**
 * 分頁總覽：兩欄網格卡片（大圓角截圖＋favicon＋標題）、右上玻璃 X、往左滑關閉、目前分頁藍框、長按拖曳排序；
 * 頂端往下拉出現搜尋欄；底部「＋ / N 個分頁（點擊切換無痕）/ 完成」。
 * ponytail: 進出總覽的 shared element 轉場在第 7 階段加入。
 */
@Composable
fun TabsOverview(vm: BrowserViewModel, transition: OverviewTransition, onOpen: (Tab) -> Unit, onNew: () -> Unit, onDone: () -> Unit, onLibrary: (Rect) -> Unit) {
    val haze = rememberHazeState()
    val scope = rememberCoroutineScope()
    val dark = isSystemInDarkTheme() || vm.incognitoMode
    val tone = if (vm.incognitoMode) Tone.Incognito else if (dark) Tone.Dark else Tone.Light
    // 一般／無痕切換時背景色平滑過渡
    val bg by animateColorAsState(if (vm.incognitoMode) Palette.Surface2Dark else if (dark) Palette.BgDark else Palette.BgLight, Motion.timed(), label = "bg")
    var query by remember { mutableStateOf("") }

    // 下拉搜尋：在頂端繼續往下拉時，搜尋欄跟手露出；放開超過 60% 就固定顯示
    val searchH = with(LocalDensity.current) { 56.dp.toPx() }
    var reveal by remember { mutableFloatStateOf(0f) }
    var searching by remember { mutableStateOf(false) }
    val pull = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0 && reveal > 0 && !searching) {
                    val take = maxOf(available.y, -reveal)
                    reveal += take
                    return Offset(0f, take)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0 && source == NestedScrollSource.UserInput) {
                    reveal = (reveal + available.y * 0.5f).coerceAtMost(searchH)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (reveal > 0f && !searching) {
                    val open = reveal > searchH * 0.6f
                    searching = open
                    animate(reveal, if (open) searchH else 0f, animationSpec = Motion.glass()) { v, _ -> reveal = v }
                }
                return Velocity.Zero
            }
        }
    }

    Box(Modifier.fillMaxSize().background(bg)) {
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        Box(Modifier.fillMaxSize().hazeSource(haze)) {
            // 一般／無痕切換：整個網格水平滑動
            AnimatedContent(
                vm.incognitoMode,
                transitionSpec = {
                    val dir = if (targetState) 1 else -1
                    slideInHorizontally(Motion.standard()) { it * dir } togetherWith slideOutHorizontally(Motion.standard()) { -it * dir }
                },
                label = "mode",
            ) { incognito ->
                val list = vm.tabs.filter {
                    it.incognito == incognito && !it.isBlank && (query.isBlank() || it.title.contains(query, true) || it.url.contains(query, true))
                }
            val grid = rememberLazyGridState()
            val reorder = remember(grid) {
                Reorder<Long>(
                    positionOf = { k ->
                        grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == k }?.let { Offset(it.offset.x.toFloat(), it.offset.y.toFloat()) }
                    },
                    keyAt = { p ->
                        grid.layoutInfo.visibleItemsInfo.firstOrNull {
                            p.x.toInt() in it.offset.x until it.offset.x + it.size.width &&
                                p.y.toInt() in it.offset.y until it.offset.y + it.size.height
                        }?.key as Long?
                    },
                    move = { a, b ->
                        val from = vm.tabs.firstOrNull { it.id == a }
                        val to = vm.tabs.firstOrNull { it.id == b }
                        if (from != null && to != null) vm.move(from, to)
                    },
                )
            }
            LazyVerticalGrid(
                GridCells.Fixed(2),
                Modifier.fillMaxSize().nestedScroll(pull).offset { IntOffset(0, reveal.roundToInt()) },
                state = grid,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top + 16.dp, bottom = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                items(list, key = { it.id }) { t ->
                    TabCard(
                        t, t == vm.current, haze, tone, dark,
                        Modifier.animateItem().swipeToDismiss { vm.close(t) }.reorderItem(reorder, t.id)
                            // 轉場中：飛行中的那張先隱藏，其他卡片跟著進度淡入／淡出
                            .then(
                                if (transition.active) Modifier.graphicsLayer {
                                    // 其他卡片跟著進度淡入、從 0.9 倍放大並微微上浮，與飛行卡片一起「展開」
                                    val p = transition.progress.value.coerceIn(0f, 1f)
                                    alpha = if (t == transition.tab) 0f else p
                                    val sc = 0.9f + 0.1f * p
                                    scaleX = sc
                                    scaleY = sc
                                    translationY = (1f - p) * 24.dp.toPx()
                                } else Modifier,
                            ),
                        onBounds = { transition.cardBounds[t.id] = it },
                        onOpen = { onOpen(t) },
                    ) { vm.close(t) }
                }
            }
            if (list.isEmpty() && query.isBlank()) Text(
                if (incognito) t("沒有無痕分頁\n點 ＋ 開新無痕分頁") else t("沒有分頁\n點 ＋ 開新分頁，點「完成」回到起始頁"),
                Modifier.fillMaxSize().wrapContentSize(),
                color = tone.content.copy(alpha = 0.6f), fontSize = 15.sp, lineHeight = 22.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            }
        }

        // 搜尋欄
        if (reveal > 0f) {
            LiquidGlass(
                haze, tone, CircleShape,
                Modifier.padding(top = top + 8.dp, start = 16.dp, end = 16.dp).fillMaxWidth().height(40.dp)
                    .offset { IntOffset(0, (reveal - searchH).roundToInt()) },
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Sym("search", tone.content.copy(alpha = 0.6f), 18.dp)
                    Box(Modifier.weight(1f).padding(start = 8.dp)) {
                        if (query.isEmpty()) Text(t("搜尋分頁"), color = tone.content.copy(alpha = 0.5f), fontSize = 15.sp)
                        BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = tone.content, fontSize = 15.sp), cursorBrush = SolidColor(accent))
                    }
                    if (searching) Text(
                        t("取消"),
                        Modifier.clip(CircleShape).clickable {
                            query = ""
                            searching = false
                            scope.launch { animate(reveal, 0f, animationSpec = Motion.glass()) { v, _ -> reveal = v } }
                        }.padding(horizontal = 6.dp),
                        color = tone.content, fontSize = 15.sp,
                    )
                }
            }
        }

        // 底部：＋ / N 個分頁 / 完成
        Row(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(start = Dimens.EdgeMargin, end = Dimens.EdgeMargin, bottom = Dimens.NavGap)
                .fillMaxWidth().height(Dimens.Control),
            horizontalArrangement = Arrangement.spacedBy(Dimens.ControlGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiquidGlass(haze, tone, CircleShape, Modifier.size(Dimens.Control).onGloballyPositioned { transition.plusBounds = it.boundsInRoot() }, onClick = onNew) {
                Sym("add", tone.content, 26.dp)
            }
            // 資料庫：書籤與歷史紀錄、下載項目、設定（App 層級的功能，不放在網頁的 … 裡）
            var libBounds by remember { mutableStateOf(Rect.Zero) }
            LiquidGlass(haze, tone, CircleShape, Modifier.size(Dimens.Control).onGloballyPositioned { libBounds = it.boundsInRoot() }, onClick = { onLibrary(libBounds) }) {
                Sym("book", tone.content, 24.dp, Modifier.clearAndSetSemantics { contentDescription = t("書籤、下載與設定") })
            }
            LiquidGlass(haze, tone, CircleShape, Modifier.weight(1f).height(Dimens.Control), onClick = { vm.incognitoMode = !vm.incognitoMode }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (vm.incognitoMode) Sym("visibility_off", tone.content, 18.dp, Modifier.padding(end = 6.dp))
                    val n = vm.visibleTabs().size
                    Text(
                        if (n == 1) (if (vm.incognitoMode) t("1 個無痕分頁") else t("1 個分頁")) else if (vm.incognitoMode) t("%1\$s 個無痕分頁", n) else t("%1\$s 個分頁", n),
                        color = tone.content, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    )
                    Sym("expand_more", tone.content, 18.dp)
                }
            }
            LiquidGlass(haze, tone, CircleShape, Modifier.height(Dimens.Control).testTag("overview_done"), onClick = onDone) {
                Text(t("完成"), Modifier.padding(horizontal = 20.dp), color = tone.content, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun TabCard(
    t: Tab,
    selected: Boolean,
    haze: HazeState,
    tone: Tone,
    dark: Boolean,
    modifier: Modifier,
    onBounds: (Rect) -> Unit,
    onOpen: () -> Unit,
    onClose: () -> Unit,
) {
    // 小米樣式：一張白色圓角卡片，上方標題列（網站圖示、標題、灰色關閉鈕），下方是頁面預覽；選中時小米藍外框
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.clip(shape).background(if (dark) Color(0xFF1C1C1E) else Color.White)
            .then(if (selected) Modifier.border(2.5.dp, accent, shape) else Modifier)
            .clickable(onClick = onOpen),
    ) {
        Row(Modifier.fillMaxWidth().height(40.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            t.favicon?.let { Image(it, null, Modifier.padding(end = 6.dp).size(16.dp).clip(RoundedCornerShape(4.dp))) }
            Text(
                t.title.ifBlank { if (t.isBlank) t("起始頁") else t.domain },
                Modifier.weight(1f),
                color = if (dark) Color.White else Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClose), Alignment.Center) {
                Sym("close", Palette.Gray, 18.dp)
            }
        }
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.8f).onGloballyPositioned { onBounds(it.boundsInRoot()) }
                .padding(start = 6.dp, end = 6.dp, bottom = 6.dp).clip(RoundedCornerShape(14.dp))
                .background(if (dark) Color(0xFF2C2C2E) else Color(0xFFF3F3F8)),
        ) {
            t.thumbnail?.let {
                Image(it, null, Modifier.fillMaxSize(), alignment = Alignment.TopCenter, contentScale = ContentScale.Crop)
            }
            if (t.isBlank) Text(t("起始頁"), Modifier.align(Alignment.Center), color = Palette.Gray, fontSize = 14.sp)
        }
    }
}
