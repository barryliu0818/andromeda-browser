package com.liquid.browser.ui.start

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

/** 分段名稱：每次讀取時翻譯（介面語言切換後立即生效） */
private val Sections get() = listOf(t("常用網站"), t("書籤"), t("閱讀列表"), t("歷史紀錄"))

/**
 * 起始頁（新分頁）：頂部液態玻璃分段標籤＋可左右滑動的四個頁面。
 * 底部只有浮動網址列（由 BrowserScreen 提供），所以內容底部留白。
 */
@Composable
fun StartPage(vm: BrowserViewModel, menu: MenuState, modifier: Modifier, initialPage: Int = 0, onOpen: (String) -> Unit) {
    val tab = vm.current ?: return
    val dark = tab.incognito || isSystemInDarkTheme()
    val content = if (dark) Color.White else Palette.Ink
    val tone = if (tab.incognito) Tone.Incognito else if (dark) Tone.Dark else Tone.Light
    val pager = rememberPagerState(initialPage) { Sections.size }
    val background = vm.settings.collectAsState().value.startBackground
    val haze = rememberHazeState()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomSpace = PaddingValues(start = 20.dp, end = 20.dp, top = top + 68.dp, bottom = 120.dp)

    Box(modifier) {
        // 自訂背景圖（帶模糊）：Android 12+ 用 RenderEffect 模糊，舊版改為加深遮罩
        if (background != null) Box(Modifier.fillMaxSize().hazeSource(haze)) {
            AsyncImage(background, null, Modifier.fillMaxSize().blur(24.dp), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background((if (dark) Color.Black else Color.White).copy(alpha = if (Build.VERSION.SDK_INT >= 31) 0.25f else 0.55f)))
        }
        HorizontalPager(pager, Modifier.fillMaxSize().hazeSource(haze), beyondViewportPageCount = 1) { page ->
            when (page) {
                0 -> if (tab.incognito) IncognitoNote(content, bottomSpace) else TopSitesPage(vm, content, tone, bottomSpace, onOpen)
                1 -> BookmarksPage(vm, content, tone, bottomSpace, onOpen)
                2 -> ReadingListPage(vm, menu, content, tone, bottomSpace, onOpen)
                else -> HistoryPage(vm, menu, content, tone, bottomSpace, onOpen)
            }
        }
        GlassSegments(
            pager, haze, tone,
            Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(40.dp),
        )
    }
}

/** 玻璃分段標籤：選取指示器是一顆跟著頁面位置滑動的玻璃膠囊，滑動途中依速度輕微拉伸。 */
@Composable
private fun GlassSegments(pager: PagerState, haze: dev.chrisbanes.haze.HazeState, tone: Tone, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    // 小米樣式分段：選中項是白色膠囊（深色為深灰）＋黑字
    val darkSeg = tone.content.luminance() > 0.5f
    LiquidGlass(haze, tone, CircleShape, modifier) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val seg = maxWidth / Sections.size
            Box(
                Modifier.width(seg).fillMaxHeight().padding(3.dp)
                    .graphicsLayer {
                        val pos = pager.currentPage + pager.currentPageOffsetFraction
                        translationX = pos * seg.toPx()
                        val frac = pos - floor(pos)
                        val stretch = 1f + 0.22f * sin(PI * frac).toFloat()
                        scaleX = stretch
                        scaleY = 1f - (stretch - 1f) * 0.25f
                    }
                    .clip(CircleShape).background(if (darkSeg) Color(0xFF48484A) else Color.White),
            )
            Row(Modifier.fillMaxSize()) {
                Sections.forEachIndexed { i, label ->
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clip(CircleShape)
                            .clickable { scope.launch { pager.animateScrollToPage(i, animationSpec = Motion.glass()) } },
                        Alignment.Center,
                    ) {
                        Text(
                            label, color = tone.content,
                            fontSize = 14.sp,
                            fontWeight = if (pager.targetPage == i) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IncognitoNote(content: Color, padding: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(80.dp))
        Sym("visibility_off", content, 48.dp)
        Text(t("無痕瀏覽"), Modifier.padding(top = 16.dp), color = content, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            t("無痕瀏覽不會記錄你的瀏覽紀錄"),
            Modifier.padding(top = 8.dp),
            color = content.copy(alpha = 0.7f), fontSize = 15.sp,
        )
    }
}

/** 常用網站：4 欄圖示格（依序淡入）、經常造訪、最近關閉的分頁。 */
@Composable
private fun TopSitesPage(vm: BrowserViewModel, content: Color, tone: Tone, padding: PaddingValues, onOpen: (String) -> Unit) {
    val icons by vm.icons.collectAsState()
    val frequent by vm.frequent.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item { SectionTitle(t("常用網站"), content) }
        item { TopSites(vm.topSites(), icons, content, onPick = onOpen) }
        if (frequent.isNotEmpty()) {
            item { SectionTitle(t("經常造訪"), content, top = 28.dp) }
            itemsIndexed(frequent, key = { _, f -> "f${f.host}" }) { i, f ->
                SiteRow("https://${f.host}", f.title.ifBlank { f.host }, f.host, icons, content, Modifier.animateItem(), Triple(i, frequent.size, tone)) { onOpen("https://${f.host}") }
            }
        }
        if (vm.recentlyClosed.isNotEmpty()) {
            item { SectionTitle(t("最近關閉的分頁"), content, top = 28.dp) }
            val closed = vm.recentlyClosed
            itemsIndexed(closed, key = { _, v -> "c${v.url}" }) { i, v ->
                SiteRow(v.url, v.title.ifBlank { v.url }, Uri.parse(v.url).host.orEmpty(), icons, content, Modifier.animateItem(), Triple(i, closed.size, tone)) { onOpen(v.url) }
            }
        }
    }
}

/**
 * 新分頁首頁（參照 Chrome）：只有置中的搜尋引擎標誌、搜尋框、圖片搜尋／無痕模式。
 * 書籤、閱讀列表、歷史紀錄改從頁面選單（aA）的「書籤與歷史紀錄」（StartPage 分段頁）進入。
 */
@Composable
fun HomePage(vm: BrowserViewModel, modifier: Modifier, onSearch: () -> Unit, onOpen: (String) -> Unit) {
    val tab = vm.current ?: return
    val dark = tab.incognito || isSystemInDarkTheme()
    val content = if (dark) Color.White else Palette.Ink
    val tone = if (tab.incognito) Tone.Incognito else if (dark) Tone.Dark else Tone.Light
    val background = vm.settings.collectAsState().value.startBackground
    val haze = rememberHazeState()
    Box(modifier) {
        // 玻璃的取樣來源：自訂背景圖，或搜尋引擎品牌色的柔和色場（讓毛玻璃透出淡淡顏色）；玻璃本身不能放在來源裡
        Box(Modifier.fillMaxSize().hazeSource(haze)) {
            if (background != null) {
                AsyncImage(background, null, Modifier.fillMaxSize().blur(24.dp), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background((if (dark) Color.Black else Color.White).copy(alpha = if (Build.VERSION.SDK_INT >= 31) 0.25f else 0.55f)))
            } else EngineGlow(vm.settings.collectAsState().value.engine, dark)
        }
        HomeHeader(vm, haze, tone, tab.incognito, Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(bottom = 40.dp), onSearch, onOpen)
    }
}

/**
 * 搜尋引擎色場：四團低透明度的放射漸層圍在搜尋框周圍，切換引擎時顏色交叉淡化。
 * 位置以畫面比例表示（x, y, 半徑佔寬度比例）。
 */
private val GlowSpots = listOf(Triple(0.08f, 0.34f, 0.52f), Triple(0.96f, 0.38f, 0.5f), Triple(0.1f, 0.70f, 0.5f), Triple(0.92f, 0.72f, 0.52f))
private val YandexGlow = listOf(0xFFFC3F1D, 0xFFFFCC00, 0xFFFF7A45, 0xFFFFB340).map { Color(it) }
private val BingGlow = listOf(0xFF0C8EC6, 0xFF37C2D9, 0xFF2F6FE0, 0xFF1BC5B4).map { Color(it) }

@Composable
private fun EngineGlow(engine: SearchEngine, dark: Boolean) {
    val palette = when (engine) {
        SearchEngine.Google -> listOf(GoogleColors[0], GoogleColors[1], GoogleColors[2], GoogleColors[4])
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

/**
 * 進場位移＋淡入：位移在排版階段（offset），透明度只在動畫進行中加 layer，
 * 動畫結束後不留常駐 graphicsLayer（玻璃放在常駐 layer 裡會取樣不到背景）。
 */
private fun Modifier.rise(a: Animatable<Float, *>): Modifier =
    offset { IntOffset(0, ((1f - a.value) * 28.dp.toPx()).roundToInt()) }
        .then(if (a.value < 1f) Modifier.graphicsLayer { alpha = a.value.coerceIn(0f, 1f) } else Modifier)

/** Google 標誌以文字配品牌色繪製（不內嵌官方圖檔）；其他引擎顯示名稱。 */
/**
 * 官方標誌圖檔（Wikimedia Commons 的 1280px 版本）。
 * Yandex 的「andex」是黑字：深色模式時右側改畫成白色，紅色 Y 保持原色。
 */
@Composable
private fun EngineLogo(engine: SearchEngine, content: Color) {
    val dark = content.luminance() > 0.5f
    when (engine) {
        SearchEngine.Google -> Image(painterResource(R.drawable.logo_google), "Google", Modifier.height(68.dp))
        SearchEngine.Bing -> Image(painterResource(R.drawable.logo_bing), "Microsoft Bing", Modifier.height(42.dp))
        SearchEngine.Yandex -> {
            val painter = painterResource(R.drawable.logo_yandex)
            Canvas(Modifier.height(52.dp).aspectRatio(painter.intrinsicSize.width / painter.intrinsicSize.height)) {
                // Y 約佔寬度 19%
                val split = size.width * 0.19f
                clipRect(right = split) { with(painter) { draw(size) } }
                clipRect(left = split) { with(painter) { draw(size, colorFilter = if (dark) ColorFilter.tint(Color.White) else null) } }
            }
        }
        else -> Text(engine.label, color = Color(0xFFDE5833), fontSize = 48.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
    }
}

private val GoogleColors = listOf(0xFF4285F4, 0xFFEA4335, 0xFFFBBC05, 0xFF4285F4, 0xFF34A853, 0xFFEA4335).map { Color(it) }

@Composable
private fun HomeHeader(vm: BrowserViewModel, haze: dev.chrisbanes.haze.HazeState, tone: Tone, incognito: Boolean, modifier: Modifier, onSearch: () -> Unit, onOpen: (String) -> Unit) {
    val engine = vm.settings.collectAsState().value.engine
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val tab = vm.current
        if (uri != null && tab != null) vm.searchByImage(tab, uri)
    }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(onOpen)
    }
    val content = tone.content
    // 進場：標誌、搜尋框、按鈕依序以彈簧往上浮現（間隔 70ms）
    val enter = remember { List(3) { Animatable(if (Motion.reduced) 1f else 0f) } }
    LaunchedEffect(Unit) { enter.forEachIndexed { i, a -> launch { delay(i * 70L); a.animateTo(1f, Motion.standard()) } } }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        // 點標誌在設定裡選定的兩個引擎之間切換（四選二）
        Row(
            Modifier.rise(enter[0]).clip(RoundedCornerShape(20.dp))
                .clickable(onClickLabel = t("切換搜尋引擎")) {
                    vm.updateSettings { s -> s.copy(engine = s.quickEngines.firstOrNull { it != s.engine } ?: s.quickEngines[0]) }
                }
                .semantics(mergeDescendants = true) { contentDescription = t("搜尋引擎：%1\$s，點兩下切換", engine.label) }
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 切換引擎：舊標誌縮小往上淡出、新標誌從下方放大浮上來
            AnimatedContent(
                engine,
                transitionSpec = {
                    (slideInVertically(Motion.standard()) { it / 3 } + fadeIn(Motion.timed())) togetherWith
                        (slideOutVertically(Motion.standard()) { -it / 3 } + fadeOut(Motion.timed(Motion.ShortMs))) using
                        SizeTransform(clip = false)
                },
                label = "logo",
            ) { e ->
                EngineLogo(e, content)
            }
            Sym("swap_horiz", content.copy(alpha = 0.6f), 22.dp, Modifier.padding(start = 6.dp))
        }
        // 搜尋框與下方按鈕都是毛玻璃（與底部網址列同一套 LiquidGlass）
        LiquidGlass(haze, tone, CircleShape, Modifier.padding(top = 28.dp).rise(enter[1]).fillMaxWidth().height(64.dp), onClick = onSearch) {
          Row(Modifier.fillMaxSize().padding(start = 20.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Sym("search", content.copy(alpha = 0.7f), 24.dp)
            Text(t("搜尋或輸入網址"), Modifier.padding(start = 14.dp).weight(1f), color = content.copy(alpha = 0.66f), fontSize = 17.sp, maxLines = 1)
            Box(
                Modifier.size(52.dp).clip(CircleShape).semantics { contentDescription = t("語音搜尋") }.clickable(role = Role.Button) {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                    if (intent.resolveActivity(context.packageManager) != null) voice.launch(intent) else vm.showToast(t("此裝置不支援語音輸入"))
                },
                Alignment.Center,
            ) { Sym("mic", content.copy(alpha = 0.75f), 24.dp) }
          }
        }
        Row(Modifier.padding(top = 10.dp).rise(enter[2]).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeChip("image_search", t("圖片搜尋"), haze, tone, Modifier.weight(1f)) {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            if (incognito) HomeChip("tab", t("一般分頁"), haze, tone, Modifier.weight(1f)) { vm.newTab(incognito = false) }
            else HomeChip("visibility_off", t("無痕模式"), haze, tone, Modifier.weight(1f)) { vm.newTab(incognito = true) }
        }
    }
}

@Composable
private fun HomeChip(icon: String, label: String, haze: dev.chrisbanes.haze.HazeState, tone: Tone, modifier: Modifier, onClick: () -> Unit) {
    LiquidGlass(haze, tone, CircleShape, modifier.height(48.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Sym(icon, tone.content.copy(alpha = 0.8f), 22.dp)
            Text(label, Modifier.padding(start = 8.dp), color = tone.content, fontSize = 15.sp, fontWeight = FontWeight.Medium)
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
