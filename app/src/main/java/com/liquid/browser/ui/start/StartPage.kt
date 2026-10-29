package com.liquid.browser.ui.start

import com.liquid.browser.ui.glass.MenuSpec
import com.liquid.browser.ui.glass.MenuItem
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextAlign
import com.liquid.browser.data.ReadingItem
import com.liquid.browser.core.t

import android.content.Intent
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.SizeTransform
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import com.liquid.browser.R
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.liquid.browser.data.SearchEngine
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import android.os.Build
import androidx.compose.ui.draw.blur
import coil3.compose.rememberAsyncImagePainter
import com.liquid.browser.browser.BrowserViewModel
import com.liquid.browser.browser.Visit
import com.liquid.browser.core.Dimens
import com.liquid.browser.core.Motion
import com.liquid.browser.core.Palette
import com.liquid.browser.ui.Sym
import androidx.compose.foundation.lazy.itemsIndexed
import com.liquid.browser.ui.groupItem
import com.liquid.browser.ui.glass.LiquidGlass
import com.liquid.browser.ui.glass.MenuState
import com.liquid.browser.ui.glass.Tone
import com.liquid.browser.ui.glass.liquidGlass
import com.liquid.browser.ui.glass.rememberGlassStyle
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/** 分段（Safari 起始頁頂部那條）：圖示＋給螢幕閱讀器的名稱；名稱每次讀取時翻譯（介面語言切換後立即生效） */
private val Sections get() = listOf("grid_view" to t("常用網站"), "book" to t("書籤"), "eyeglasses" to t("閱讀列表"), "history" to t("歷史紀錄"))

/**
 * 起始頁（Safari）：頂部液態玻璃分段（常用網站／書籤／閱讀列表／歷史紀錄，圖示）＋可左右滑動的四個頁面。
 * 新分頁（[HomePage]）與「書籤與歷史紀錄」面板共用；搜尋與輸入網址由網址列負責。
 * glow：沒有自訂背景圖時，鋪一層搜尋引擎品牌色的柔和色場（新分頁用；面板裡不用）。
 */
@Composable
fun StartPage(
    vm: BrowserViewModel, menu: MenuState, modifier: Modifier, initialPage: Int = 0, glow: Boolean = false,
    /** 網址列佔掉的上方／下方空間（依網址列版面）：分段列放在上方網址列下面，清單底部讓出下方的網址列或工具列 */
    barTop: Dp = 0.dp, barBottom: Dp = 120.dp,
    onOpen: (String) -> Unit,
) {
    val tab = vm.current ?: return
    val dark = tab.incognito || isSystemInDarkTheme()
    val content = if (dark) Color.White else Palette.Ink
    val tone = if (tab.incognito) Tone.Incognito else if (dark) Tone.Dark else Tone.Light
    val pager = rememberPagerState(initialPage) { Sections.size }
    val settings by vm.settings.collectAsState()
    val background = settings.startBackground
    val haze = rememberHazeState()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // 上方讓出網址列（頂部版面）與分段列（8＋44＋16），下方讓出網址列／工具列（再多 20dp 呼吸空間）
    val bottomSpace = PaddingValues(start = 20.dp, end = 20.dp, top = top + barTop + 68.dp, bottom = barBottom + 20.dp)

    Box(modifier) {
        // 玻璃的取樣來源：自訂背景圖（帶模糊），或搜尋引擎色場；Android 12 以下改加深遮罩
        Box(Modifier.fillMaxSize().hazeSource(haze)) {
            if (background != null) {
                AsyncImage(background, null, Modifier.fillMaxSize().blur(24.dp), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background((if (dark) Color.Black else Color.White).copy(alpha = if (Build.VERSION.SDK_INT >= 31) 0.25f else 0.55f)))
            } else if (glow) EngineGlow(settings.engine, dark)
        }
        HorizontalPager(pager, Modifier.fillMaxSize().hazeSource(haze), beyondViewportPageCount = 1) { page ->
            when (page) {
                0 -> if (tab.incognito) IncognitoNote(content, bottomSpace) else FavoritesPage(vm, menu, content, tone, haze, bottomSpace, pager, onOpen)
                1 -> BookmarksPage(vm, content, tone, bottomSpace, onOpen)
                2 -> ReadingListPage(vm, menu, content, tone, bottomSpace, onOpen)
                else -> HistoryPage(vm, menu, content, tone, bottomSpace, onOpen)
            }
        }
        GlassSegments(
            pager, haze, tone,
            Modifier.statusBarsPadding().padding(top = barTop).padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(44.dp),
        )
    }
}

/**
 * 玻璃分段：選中的那段墊一顆白色膠囊（深色為深灰），跟著手指滑動頁面的位置即時移動，
 * 滑到兩段之間時輕微拉長（只拉伸、不回彈）。位移與拉伸都在繪製階段讀，滑動時不重組。
 */
@Composable
private fun GlassSegments(pager: PagerState, haze: dev.chrisbanes.haze.HazeState, tone: Tone, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val darkSeg = tone.content.luminance() > 0.5f
    LiquidGlass(haze, tone, CircleShape, modifier) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val seg = maxWidth / Sections.size
            Box(
                Modifier.width(seg).fillMaxHeight().padding(4.dp)
                    .graphicsLayer {
                        val pos = pager.currentPage + pager.currentPageOffsetFraction
                        translationX = pos * seg.toPx()
                        val frac = pos - floor(pos)
                        scaleX = 1f + 0.18f * sin(PI * frac).toFloat()
                    }
                    .clip(CircleShape).background(if (darkSeg) Color(0xFF48484A) else Color.White),
            )
            Row(Modifier.fillMaxSize()) {
                Sections.forEachIndexed { i, (icon, label) ->
                    val on = pager.targetPage == i
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clip(CircleShape)
                            .semantics { contentDescription = label; selected = on; role = Role.Tab }
                            .clickable { scope.launch { pager.animateScrollToPage(i, animationSpec = Motion.glass()) } },
                        Alignment.Center,
                    ) {
                        // 選中的圖示實心深色、其他淡一點（不只靠底塊表示選中）
                        val a by animateFloatAsState(if (on) 1f else 0.6f, Motion.timed(), label = "seg$i")
                        Sym(icon, tone.content, 21.dp, Modifier.graphicsLayer { alpha = a })
                    }
                }
            }
        }
    }
}

/** 無痕起始頁（Safari 私密瀏覽）：舉手圖示＋說明 */
@Composable
private fun IncognitoNote(content: Color, padding: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Sym("back_hand", content.copy(alpha = 0.55f), 52.dp)
        Text(t("無痕瀏覽"), Modifier.padding(top = 14.dp), color = content.copy(alpha = 0.75f), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(
            t("關閉無痕分頁後，不會留下瀏覽紀錄、搜尋紀錄與 Cookie"),
            Modifier.padding(top = 8.dp),
            color = content.copy(alpha = 0.55f), fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 21.sp,
        )
    }
}

/**
 * 常用網站頁（Safari 起始頁）：「常用網站」4×2 圖示格、「閱讀列表」最多三張玻璃卡片、最近關閉的分頁。
 * 每段標題右側「全部顯示 ›」切到對應的分段頁。
 */
@Composable
private fun FavoritesPage(
    vm: BrowserViewModel, menu: MenuState, content: Color, tone: Tone, haze: dev.chrisbanes.haze.HazeState,
    padding: PaddingValues, pager: PagerState, onOpen: (String) -> Unit,
) {
    val icons by vm.icons.collectAsState()
    val scope = rememberCoroutineScope()
    val reading by remember { vm.db.reading().all() }.collectAsState(emptyList())
    val settings by vm.settings.collectAsState()
    // 7 個網站＋最後一格「＋」（加入常用網站）
    val sites = vm.topSites(7, settings)
    // 編輯中的常用網站：first＝原本的網址（新增時為 null）、second＝名稱、third＝網址
    var editing by remember { mutableStateOf<Triple<String?, String, String>?>(null) }
    editing?.let { (old, title, url) ->
        FavoriteDialog(if (old == null) t("加入常用網站") else t("編輯常用網站"), title, url, onDismiss = { editing = null }) { newTitle, newUrl ->
            vm.saveFavorite(old, newTitle, newUrl)
            editing = null
        }
    }
    val showAll = { page: Int -> scope.launch { pager.animateScrollToPage(page, animationSpec = Motion.glass()) }; Unit }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item(key = "fav") {
            SectionHeader(t("常用網站"), content, null)
            FavoritesGrid(
                sites, icons, content, onPick = onOpen,
                // 長按：在新分頁開啟／編輯／刪除
                onLongPress = { site, bounds ->
                    menu.show(
                        MenuSpec(
                            bounds,
                            listOf(
                                MenuItem(t("在新分頁開啟"), "add_box") { vm.newTab(site.url) },
                                MenuItem(t("編輯"), "edit") { editing = Triple(site.url, site.title, site.url) },
                                MenuItem(t("刪除"), "delete", destructive = true) { vm.removeFavorite(site.url) },
                            ),
                        ),
                    )
                },
                onAdd = { editing = Triple(null, "", "") },
            )
        }
        if (reading.isNotEmpty()) {
            item(key = "rl") { SectionHeader(t("閱讀列表"), content, { showAll(2) }, top = 28.dp) }
            itemsIndexed(reading.take(3), key = { _, r -> "r${r.url}" }) { i, r ->
                ReadingCard(r, content, tone, haze, i, Modifier.animateItem()) {
                    scope.launch { vm.db.reading().setRead(r.url, true) }
                    onOpen(r.url)
                }
            }
        }
        if (vm.recentlyClosed.isNotEmpty()) {
            item(key = "rc") { SectionHeader(t("最近關閉的分頁"), content, null, top = 28.dp) }
            val closed = vm.recentlyClosed.take(5)
            itemsIndexed(closed, key = { _, v -> "c${v.url}" }) { i, v ->
                SiteRow(v.url, v.title.ifBlank { v.url }, Uri.parse(v.url).host.orEmpty(), icons, content, Modifier.animateItem(), Triple(i, closed.size, tone)) { onOpen(v.url) }
            }
        }
    }
}

/**
 * 常用網站格子（4 欄）：每格依序淡入；長按跳出選單（在新分頁開啟／編輯／刪除）；最後一格「＋」加入常用網站。
 * 刪除時那格縮小淡出、後面的格子平順補位（animateItem 式的位移），不是瞬間跳掉。
 */
@Composable
private fun FavoritesGrid(
    sites: List<Visit>, icons: Map<String, String>, content: Color, onPick: (String) -> Unit,
    onLongPress: (Visit, Rect) -> Unit, onAdd: () -> Unit,
) {
    val cells: List<Visit?> = sites + null
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        cells.chunked(4).forEachIndexed { r, row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEachIndexed { c, site ->
                    androidx.compose.runtime.key(site?.url ?: "add") {
                        val appear = remember { Animatable(if (Motion.reduced) 1f else 0f) }
                        LaunchedEffect(Unit) { delay((r * 4 + c) * 30L); appear.animateTo(1f, Motion.glass()) }
                        val m = Modifier.weight(1f).graphicsLayer {
                            alpha = appear.value
                            translationY = (1f - appear.value) * 12.dp.toPx()
                            val s = 0.9f + 0.1f * appear.value
                            scaleX = s; scaleY = s
                        }
                        if (site == null) AddTile(content, m, onAdd)
                        else FavoriteTile(site, icons, content, m, { onLongPress(site, it) }) { onPick(site.url) }
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FavoriteTile(site: Visit, icons: Map<String, String>, content: Color, modifier: Modifier, onLongPress: (Rect) -> Unit, onClick: () -> Unit) {
    val host = Uri.parse(site.url).host?.removePrefix("www.").orEmpty()
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val haptic = LocalHapticFeedback.current
    Column(
        modifier.clip(RoundedCornerShape(Dimens.TileRadius))
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .combinedClickable(onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLongPress(bounds) }, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SiteIcon(host, icons, Dimens.Tile, Dimens.TileRadius)
        Text(
            site.title.ifBlank { host }, Modifier.padding(top = 6.dp).widthIn(max = 76.dp),
            color = content, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, lineHeight = 15.sp,
        )
    }
}

/** 「＋」格：跟網站圖示同大小同圓角的淡色方塊 */
@Composable
private fun AddTile(content: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(Dimens.TileRadius)).clickable(onClick = onClick).padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = t("加入常用網站"); role = Role.Button },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(Dimens.Tile).clip(RoundedCornerShape(Dimens.TileRadius)).background(content.copy(alpha = 0.08f)), Alignment.Center) {
            Sym("add", content.copy(alpha = 0.6f), 28.dp)
        }
        Text(t("加入"), Modifier.padding(top = 6.dp), color = content.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1)
    }
}

/**
 * 加入／編輯常用網站：名稱與網址兩個欄位；網址要像網址（www、.com、IP…）才能儲存，名稱空白時用網域。
 * 對話框是 20dp 圓角的卡片，從中間淡入並微微放大（減速曲線）。
 */
@Composable
private fun FavoriteDialog(title: String, initialName: String, initialUrl: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    var url by remember { mutableStateOf(initialUrl) }
    val valid = com.liquid.browser.browser.looksLikeUrl(url)
    val dark = isSystemInDarkTheme()
    val content = if (dark) Color.White else Palette.Ink
    val accent = com.liquid.browser.ui.accent
    val appear = remember { Animatable(if (Motion.reduced) 1f else 0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, Motion.glass()) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().graphicsLayer { alpha = appear.value; val s = 0.94f + 0.06f * appear.value; scaleX = s; scaleY = s }
                .clip(RoundedCornerShape(Dimens.CardRadius)).background(if (dark) Palette.CardDark else Palette.CardLight).padding(20.dp),
        ) {
            Text(title, color = content, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            FieldBox(t("名稱"), name, content, accent) { name = it }
            FieldBox(t("網址"), url, content, accent, uri = true) { url = it }
            if (url.isNotBlank() && !valid) Text(t("請輸入網址，例如 example.com"), Modifier.padding(top = 6.dp), color = Color(0xFFFF3B30), fontSize = 13.sp)
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(14.dp)).background(content.copy(alpha = 0.08f)).clickable(onClick = onDismiss), Alignment.Center) {
                    Text(t("取消"), color = content, fontSize = 16.sp)
                }
                Box(
                    Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(14.dp)).background(if (valid) accent else accent.copy(alpha = 0.35f))
                        .clickable(enabled = valid) {
                            val full = com.liquid.browser.browser.toUrl(url, "")
                            onSave(name.trim().ifBlank { Uri.parse(full).host?.removePrefix("www.").orEmpty() }, full)
                        },
                    Alignment.Center,
                ) { Text(t("儲存"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun FieldBox(label: String, value: String, content: Color, accent: Color, uri: Boolean = false, onChange: (String) -> Unit) {
    Text(label, Modifier.padding(top = 14.dp, bottom = 6.dp), color = content.copy(alpha = 0.6f), fontSize = 13.sp)
    androidx.compose.foundation.text.BasicTextField(
        value, onChange, singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(color = content, fontSize = 16.sp),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(accent),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = if (uri) androidx.compose.ui.text.input.KeyboardType.Uri else androidx.compose.ui.text.input.KeyboardType.Text, autoCorrectEnabled = false,
        ),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(content.copy(alpha = 0.06f)).padding(horizontal = 12.dp, vertical = 12.dp),
    )
}

/** 段落標題（Safari）：粗體標題，右側「全部顯示 ›」 */
@Composable
private fun SectionHeader(text: String, content: Color, onShowAll: (() -> Unit)?, top: Dp = 0.dp) {
    Row(Modifier.fillMaxWidth().padding(top = top, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), color = content, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (onShowAll != null) Row(
            Modifier.clip(CircleShape).clickable(onClick = onShowAll).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(t("全部顯示"), color = content.copy(alpha = 0.7f), fontSize = 15.sp)
            Sym("chevron_right", content.copy(alpha = 0.7f), 18.dp)
        }
    }
}

/**
 * 閱讀列表卡片（Safari）：玻璃底、20dp 圓角，左側縮圖、右側標題（兩行）與網域。
 * 玻璃畫在主畫面（不開系統模糊視窗）：捲動清單裡的卡片不會變成浮在畫面上的獨立視窗、也不會疊到選單上。
 * 進場：依序淡入並微微上浮（每張間隔 40ms，減速曲線、不回彈）。
 */
@Composable
private fun ReadingCard(r: ReadingItem, content: Color, tone: Tone, haze: dev.chrisbanes.haze.HazeState, index: Int, modifier: Modifier, onClick: () -> Unit) {
    val appear = remember { Animatable(if (Motion.reduced) 1f else 0f) }
    LaunchedEffect(Unit) { delay(index * 40L); appear.animateTo(1f, Motion.glass()) }
    LiquidGlass(
        haze, tone, RoundedCornerShape(Dimens.CardRadius),
        modifier.padding(bottom = 10.dp).fillMaxWidth().height(104.dp)
            .graphicsLayer { alpha = appear.value; translationY = (1f - appear.value) * 14.dp.toPx() },
        systemBlur = false, contentAlignment = Alignment.CenterStart, onClick = onClick,
    ) {
        Row(Modifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)).background(content.copy(alpha = 0.08f)), Alignment.Center) {
                if (r.thumbnail != null) AsyncImage(r.thumbnail, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Sym("article", content.copy(alpha = 0.4f), 28.dp)
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(r.title.ifBlank { r.url }, color = content, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 21.sp)
                Text(Uri.parse(r.url).host?.removePrefix("www.").orEmpty(), Modifier.padding(top = 6.dp), color = content.copy(alpha = 0.55f), fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}

/** 新分頁（Safari 起始頁）：起始頁鋪上搜尋引擎色場或自訂背景圖；搜尋、輸入網址由網址列負責 */
@Composable
fun HomePage(vm: BrowserViewModel, menu: MenuState, modifier: Modifier, barTop: Dp, barBottom: Dp, onOpen: (String) -> Unit) =
    StartPage(vm, menu, modifier, glow = true, barTop = barTop, barBottom = barBottom, onOpen = onOpen)

/**
 * 搜尋引擎色場：四團低透明度的放射漸層，切換引擎時顏色交叉淡化。
 * 位置以畫面比例表示（x, y, 半徑佔寬度比例）。
 */
private val GlowSpots = listOf(Triple(0.08f, 0.34f, 0.52f), Triple(0.96f, 0.38f, 0.5f), Triple(0.1f, 0.70f, 0.5f), Triple(0.92f, 0.72f, 0.52f))
private val YandexGlow = listOf(0xFFFC3F1D, 0xFFFFCC00, 0xFFFF7A45, 0xFFFFB340).map { Color(it) }
private val BingGlow = listOf(0xFF0C8EC6, 0xFF37C2D9, 0xFF2F6FE0, 0xFF1BC5B4).map { Color(it) }
private val GoogleGlow = listOf(0xFF4285F4, 0xFFEA4335, 0xFFFBBC05, 0xFF34A853).map { Color(it) }

@Composable
private fun EngineGlow(engine: SearchEngine, dark: Boolean) {
    val palette = when (engine) {
        SearchEngine.Google -> GoogleGlow
        SearchEngine.Bing -> BingGlow
        SearchEngine.Yandex -> YandexGlow
        else -> List(4) { Color(0xFFDE5833) }
    }
    val alpha = if (dark) 0.22f else 0.30f
    val colors = palette.mapIndexed { i, c -> animateColorAsState(c.copy(alpha = alpha), Motion.timed(), label = "glow$i") }
    Canvas(Modifier.fillMaxSize()) {
        GlowSpots.forEachIndexed { i, (x, y, r) ->
            val center = androidx.compose.ui.geometry.Offset(size.width * x, size.height * y)
            val radius = size.width * r
            drawCircle(Brush.radialGradient(listOf(colors[i].value, Color.Transparent), center, radius), radius, center)
        }
    }
}

@Composable
fun SectionTitle(text: String, content: Color, top: Dp = 0.dp) =
    Text(text, Modifier.padding(top = top, bottom = 14.dp), color = content, fontSize = 22.sp, fontWeight = FontWeight.Bold)

/** 4 欄常用網站格子；每個圖示以小延遲依序淡入。 */
@Composable
fun TopSites(sites: List<Visit>, icons: Map<String, String>, content: Color, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        sites.chunked(4).forEachIndexed { r, row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEachIndexed { c, site ->
                    val appear = remember { Animatable(0f) }
                    LaunchedEffect(Unit) {
                        delay((r * 4 + c) * 30L)
                        appear.animateTo(1f, Motion.standard())
                    }
                    SiteTile(
                        site, icons, content,
                        Modifier.weight(1f).graphicsLayer {
                            alpha = appear.value
                            translationY = (1f - appear.value) * 12.dp.toPx()
                        },
                    ) { onPick(site.url) }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SiteTile(site: Visit, icons: Map<String, String>, content: Color, modifier: Modifier, onClick: () -> Unit) {
    val host = Uri.parse(site.url).host?.removePrefix("www.").orEmpty()
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        SiteIcon(host, icons, Dimens.Tile, Dimens.TileRadius)
        Text(
            site.title.ifBlank { host }, Modifier.padding(top = 6.dp).widthIn(max = 72.dp),
            color = content, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 網站圖示：有高解析度圖示就用（Coil 背景解碼），否則首字母＋依網域生成的背景色。 */
@Composable
fun SiteIcon(host: String, icons: Map<String, String>, size: Dp, radius: Dp) {
    val shape = RoundedCornerShape(radius)
    val url = icons[host]
    val painter = rememberAsyncImagePainter(url)
    val state by painter.state.collectAsState()
    // Coil 需要先繪製一次才知道尺寸並開始載入，所以圖片永遠繪製，成功前以首字母方塊蓋住
    Box(Modifier.size(size).clip(shape).background(Color.White), Alignment.Center) {
        Image(painter, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (url == null || state !is AsyncImagePainter.State.Success) {
            val hue = (abs(host.hashCode()) % 360).toFloat()
            Box(Modifier.fillMaxSize().background(Color.hsv(hue, 0.55f, 0.85f)), Alignment.Center) {
                Text(host.take(1).uppercase(), color = Color.White, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun SiteRow(
    url: String,
    title: String,
    sub: String,
    icons: Map<String, String>,
    content: Color,
    modifier: Modifier = Modifier,
    /** 分組卡片中的位置（第幾列、共幾列、色調）；null 為不分組 */
    group: Triple<Int, Int, Tone>? = null,
    onLongClick: ((Rect) -> Unit)? = null,
    onClick: () -> Unit,
) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val haptic = LocalHapticFeedback.current
    Row(
        modifier.fillMaxWidth()
            .then(group?.let { (i, n, t) -> Modifier.groupItem(i, n, t, 64.dp) } ?: Modifier)
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .combinedClickable(
                onLongClick = onLongClick?.let { cb -> { haptic.performHapticFeedback(HapticFeedbackType.LongPress); cb(bounds) } },
                onClick = onClick,
            )
            .padding(horizontal = if (group != null) 14.dp else 0.dp, vertical = if (group != null) 10.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SiteIcon(Uri.parse(url).host?.removePrefix("www.").orEmpty(), icons, 36.dp, 9.dp)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(title, color = content, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = Palette.Gray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
