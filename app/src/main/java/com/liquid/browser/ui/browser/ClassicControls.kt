package com.liquid.browser.ui.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.liquid.browser.browser.BrowserViewModel
import com.liquid.browser.browser.Tab
import com.liquid.browser.core.Motion
import com.liquid.browser.core.t
import com.liquid.browser.ui.Sym
import com.liquid.browser.ui.SymButton
import com.liquid.browser.ui.accent
import com.liquid.browser.ui.glass.LiquidGlass
import com.liquid.browser.ui.glass.Tone
import dev.chrisbanes.haze.HazeState

/**
 * Safari「底部」「頂部」版面用的元件（精簡版面是 [Controls]）：
 *  - [ClassicUrlField]：一整條玻璃上的圓角網址欄（≡ 網域 文A ↻；點一下輸入，輸入時右側出現 ✕）
 *  - [ClassicToolbar]：返回、前進、分享、書籤、分頁五顆按鈕平均排開
 * 底部版面＝網址欄＋工具列疊在同一塊玻璃；頂部版面＝網址欄在頂端、工具列在底部，各一塊玻璃。
 */

/** 底部版面整塊的高度（不含手勢導覽列）：上 8＋網址欄 48＋2＋工具列 48＋下 2 */
internal val ClassicBottomH = 108.dp
/** 頂部版面底部工具列的高度（不含手勢導覽列） */
internal val ClassicToolbarH = 52.dp
/** 頂部版面網址欄的高度（不含狀態列）：上 4＋48＋下 8 */
internal val ClassicTopH = 60.dp

/** 網址欄與工具列共用的動作 */
class ClassicActions(
    val onQuery: (TextFieldValue) -> Unit,
    val onFocus: () -> Unit,
    val onSubmit: (String) -> Unit,
    val onCancel: () -> Unit,
    val onPageMenu: (Rect) -> Unit,
    val onTranslate: (Rect) -> Unit,
    val onDownloads: () -> Unit,
    val onBack: () -> Unit,
    val backEnabled: Boolean,
    val onBackLongPress: (Rect) -> Unit,
    val onShare: () -> Unit,
    val onBookmarks: () -> Unit,
    val onTabs: () -> Unit,
    val onTabsLongPress: (Rect) -> Unit,
)

/** 網址欄：圓角欄位（比玻璃底色深一點），未輸入時顯示網域，點一下展開成完整網址輸入 */
@Composable
fun ClassicUrlField(vm: BrowserViewModel, tab: Tab, tone: Tone, focused: Boolean, query: TextFieldValue, a: ClassicActions, modifier: Modifier = Modifier) {
    val accent = accent
    Row(modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(14.dp))
                .background(tone.content.copy(alpha = 0.08f))
                .clickable(enabled = !focused) { a.onFocus() },
        ) {
            Crossfade(focused, animationSpec = Motion.timed(Motion.ShortMs), label = "url") { f ->
                if (f) UrlInput(query, tone, accent, a.onQuery, a.onSubmit)
                else {
                    val target = vm.settings.collectAsState().value.translateTarget
                    val canTranslate = !tab.isBlank && (tab.isYouTube || tab.translated || !tab.alreadyIn(target))
                    // 底部／頂部版面不收合成小膠囊：p 固定 0，按鈕一律可按
                    UrlLabel(tab, tone, { 0f }, { 1f }, a.onPageMenu, a.onDownloads, a.onTranslate, canTranslate) {}
                }
            }
            Progress(tab.progress, accent, Modifier.align(Alignment.BottomStart))
        }
        // 輸入時右側的 ✕：收起輸入（送出用鍵盤的「前往」）
        AnimatedVisibility(
            focused,
            enter = fadeIn(Motion.timed()) + expandHorizontally(Motion.glass()),
            exit = fadeOut(Motion.exit()) + shrinkHorizontally(Motion.exit()),
        ) {
            SymButton("close", tone.content, Modifier.padding(start = 4.dp), size = 22.dp, label = t("取消")) { a.onCancel() }
        }
    }
}

/** 工具列：返回、前進、分享、書籤、分頁 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClassicToolbar(tab: Tab, tone: Tone, tabCount: Int, a: ClassicActions, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    var backBounds by remember { mutableStateOf(Rect.Zero) }
    var tabsBounds by remember { mutableStateOf(Rect.Zero) }
    Row(modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
        // 返回：長按列出上一頁歷史
        ToolButton(
            "arrow_back_ios_new", tone, t("上一頁"), a.backEnabled,
            Modifier.onGloballyPositioned { backBounds = it.boundsInRoot() },
            onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); a.onBackLongPress(backBounds) },
        ) { a.onBack() }
        ToolButton("arrow_forward_ios", tone, t("下一頁"), tab.canGoForward) { tab.webView.goForward() }
        ToolButton("ios_share", tone, t("分享"), !tab.isBlank) { a.onShare() }
        ToolButton("book", tone, t("書籤與歷史紀錄"), true) { a.onBookmarks() }
        // 分頁：長按＝新增／關閉分頁的選單
        Box(
            Modifier.size(48.dp).clip(CircleShape)
                .onGloballyPositioned { tabsBounds = it.boundsInRoot() }
                .semantics { contentDescription = t("%1\$s 個分頁", tabCount); role = Role.Button }
                .combinedClickable(onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); a.onTabsLongPress(tabsBounds) }) { a.onTabs() },
            Alignment.Center,
        ) { TabsIcon(tabCount, tone.content) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolButton(
    icon: String, tone: Tone, label: String, enabled: Boolean, modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null, onClick: () -> Unit,
) {
    Box(
        modifier.size(48.dp).clip(CircleShape)
            .semantics { contentDescription = label; role = Role.Button }
            .combinedClickable(enabled = enabled, onLongClick = onLongClick, onClick = onClick),
        Alignment.Center,
    ) { Sym(icon, tone.content, 22.dp, Modifier.graphicsLayer { alpha = if (enabled) 1f else 0.3f }) }
}

/**
 * 底部版面：一塊從畫面底緣長上來的玻璃（上緣圓角），裡面是網址欄＋工具列；延伸到手勢導覽列下方。
 * 不放進系統模糊視窗（systemBlur = false）：按鈕的位置就是主畫面座標，選單能正確從按鈕長出來；\n * 往下捲時由呼叫端在排版階段整塊位移滑出。
 */
@Composable
fun ClassicBottomBar(
    vm: BrowserViewModel, tab: Tab, haze: HazeState, tone: Tone, focused: Boolean, query: TextFieldValue, a: ClassicActions,
    modifier: Modifier = Modifier,
) {
    LiquidGlass(
        haze, tone, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter, systemBlur = false,
    ) {
        Column(Modifier.navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp)) {
            ClassicUrlField(vm, tab, tone, focused, query, a)
            // 輸入網址時工具列讓位給鍵盤上方的空間
            AnimatedVisibility(!focused) { ClassicToolbar(tab, tone, vm.visibleTabs().size, a, Modifier.padding(top = 2.dp)) }
        }
    }
}

/** 頂部版面的網址欄：從畫面頂端（含狀態列）一整條玻璃（下緣圓角）；往下捲時往上滑出 */
@Composable
fun ClassicTopBar(
    vm: BrowserViewModel, tab: Tab, haze: HazeState, tone: Tone, focused: Boolean, query: TextFieldValue, a: ClassicActions,
    modifier: Modifier = Modifier,
) {
    LiquidGlass(
        haze, tone, RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp), modifier.fillMaxWidth(),
        contentAlignment = Alignment.BottomCenter, systemBlur = false,
    ) {
        ClassicUrlField(vm, tab, tone, focused, query, a, Modifier.statusBarsPadding().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp))
    }
}

/** 頂部版面的工具列：底部一整條玻璃（上緣圓角）；往下捲時往下滑出 */
@Composable
fun ClassicBottomToolbar(vm: BrowserViewModel, tab: Tab, haze: HazeState, tone: Tone, a: ClassicActions, modifier: Modifier = Modifier) {
    LiquidGlass(
        haze, tone, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter, systemBlur = false,
    ) {
        ClassicToolbar(tab, tone, vm.visibleTabs().size, a, Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 2.dp))
    }
}
