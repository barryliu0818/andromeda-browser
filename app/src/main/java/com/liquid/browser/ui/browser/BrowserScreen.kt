package com.liquid.browser.ui.browser

import androidx.compose.animation.core.animate
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import com.liquid.browser.core.t

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.liquid.browser.ui.glass.GlassSheetHost
import com.liquid.browser.ui.glass.SheetState
import com.liquid.browser.ui.settings.DownloadsContent
import com.liquid.browser.ui.settings.SettingsContent
import kotlin.coroutines.cancellation.CancellationException
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.metrics.performance.PerformanceMetricsState
import com.liquid.browser.browser.BrowserViewModel
import com.liquid.browser.browser.Tab
import com.liquid.browser.core.Dimens
import com.liquid.browser.core.GlassTokens
import com.liquid.browser.core.Motion
import com.liquid.browser.core.Palette
import com.liquid.browser.ui.glass.GlassMenuHost
import com.liquid.browser.ui.glass.LiquidGlass
import com.liquid.browser.ui.glass.MenuItem
import com.liquid.browser.browser.TargetLang
import com.liquid.browser.ui.accent
import com.liquid.browser.ui.glass.MenuSpec
import com.liquid.browser.ui.glass.MenuState
import com.liquid.browser.ui.glass.Tone
import com.liquid.browser.ui.glass.sampleDominantColor
import com.liquid.browser.ui.glass.PlatformBackdrop
import com.liquid.browser.ui.glass.SystemBlurWindow
import com.liquid.browser.ui.glass.crossWindowBlurEnabled
import com.liquid.browser.ui.glass.LocalOpaqueGlass
import com.liquid.browser.ui.glass.LocalCheapGlass
import com.liquid.browser.ui.start.HomePage
import com.liquid.browser.ui.start.StartPage
import com.liquid.browser.ui.tabs.TabsOverview
import com.liquid.browser.ui.tabs.FlyingCard
import com.liquid.browser.ui.tabs.OverviewTransition
import androidx.compose.ui.geometry.Rect
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedContent
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import com.liquid.browser.ui.Sym
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.layout.boundsInRoot
import android.net.Uri
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import com.liquid.browser.ui.glass.LocalHideBlurWindows
import androidx.lifecycle.repeatOnLifecycle

/** 除錯用：沒有原生 backdrop 的機種也強制走系統模糊視窗，方便在其他裝置上測試 */
private const val DEBUG_FORCE_SYSBLUR = false

/** 瀏覽主畫面：全螢幕網頁（或起始頁）＋浮動玻璃控制列；直向在底部、橫向在頂部置中。 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun BrowserScreen(vm: BrowserViewModel) {
    val tab = vm.current ?: return
    val dark = isSystemInDarkTheme()
    val haze = rememberHazeState()
    val menu = remember { MenuState() }
    val ov = remember { OverviewTransition() }
    var size by remember { mutableStateOf(Offset(1f, 1f)) }
    val sheet = remember { SheetState() }
    val settings by vm.settings.collectAsState()
    val context = LocalContext.current
    var finding by remember(tab) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var focused by remember { mutableStateOf(false) }
    var overview by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf(TextFieldValue()) }
    // 使用者是否真的打過字：剛點進網址列（預填網址或空白）時不顯示建議面板
    var typed by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    val activity = LocalActivity.current
    val crossBlur = crossWindowBlurEnabled()
    // 狀態列正下方網頁的實際顏色；換分頁或開新網頁時先清掉，改用 theme-color 直到下一次取樣
    var pageTop by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(tab, tab.url) { pageTop = null }
    val statusPx = WindowInsets.statusBars.getTop(LocalDensity.current).toFloat()
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    // 只在 App 顯示於前景時取樣：背景時視窗沒有畫面，取樣沒有意義又耗電
    LaunchedEffect(landscape) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
        while (true) {
            delay(GlassTokens.SampleIntervalMs)
            val w = activity?.window?.decorView?.width ?: break
            // 狀態列與網頁融為一體：取樣狀態列正下方那一條網頁的實際顏色（面板、選單、輸入時不取樣，免得取到遮罩）
            if (!tab.isBlank && !overview && !focused && !sheet.open && !menu.open) {
                val top = statusPx.toInt()
                sampleDominantColor(activity, android.graphics.Rect(0, top + 2, w, top + 6))?.let { pageTop = it }
            }
        }
        }
    }
    // 玻璃固定為磨砂（淺色模式白霧、深色模式深霧），不隨網頁亮度或輸入狀態變色；無痕保留深色方便辨識
    val target = when {
        tab.incognito -> Tone.Incognito
        dark -> Tone.Dark
        else -> Tone.Light
    }
    val tint by animateColorAsState(target.tint, tween(Motion.ToneMs), label = "tint")
    val content by animateColorAsState(target.content, tween(Motion.ToneMs), label = "content")
    val tone = Tone(tint, content)
    val menuTone = if (tab.incognito || dark) Tone.InputDark else Tone.InputLight

    // 淺色／深色、無痕切換時背景色平滑過渡
    val bg by animateColorAsState(if (tab.incognito) Palette.Surface2Dark else if (dark) Palette.BgDark else Palette.BgLight, Motion.timed(), label = "bg")

    // 狀態列圖示深淺跟著 theme-color（沒有則跟系統深淺色）
    val view = LocalView.current
    val statusTint = if (tab.isBlank) bg else pageTop ?: tab.themeColor
    // 狀態列底色：顏色變化時平滑過渡（捲到不同顏色的區塊時不會跳色）
    val statusFill by animateColorAsState(statusTint ?: bg, Motion.timed(Motion.ShortMs), label = "status")
    SideEffect {
        val light = if (overview) !(dark || vm.incognitoMode) else statusTint?.let { it.luminance() > 0.5f } ?: !dark
        activity?.window?.let { WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = light }
    }

    val cancel = { focused = false; focusManager.clearFocus() }
    // 預備系統模糊視窗：開好、閒置一下後先建 2 個，之後打開選單、面板不必當場建視窗；離開畫面時釋放
    if (!PlatformBackdrop) {
        val poolParent = androidx.compose.runtime.rememberCompositionContext()
        DisposableEffect(Unit) { onDispose { com.liquid.browser.ui.glass.BlurPool.clear() } }
        val poolLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
        val poolSaved = androidx.savedstate.compose.LocalSavedStateRegistryOwner.current
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(1500); com.liquid.browser.ui.glass.BlurPool.prewarm(context, poolParent, poolLifecycle, poolSaved) }
    }
    // 輸入與選單／面板互斥：開始輸入就收起選單與面板；打開選單或面板就結束輸入
    LaunchedEffect(focused) { if (focused) { if (menu.open) menu.dismiss(); if (sheet.open) sheet.dismiss() } }
    val overlayOpen = menu.open || sheet.open
    // 進分頁總覽時收起選單與面板（否則回來後還開著）
    LaunchedEffect(overview) { if (overview) { if (menu.open) menu.dismiss(); if (sheet.open) sheet.dismiss() } }
    LaunchedEffect(overlayOpen) { if (overlayOpen && focused) cancel() }

    // 面板（設定、網站設定、書籤…）出現期間，網頁換成一張凍結畫面：面板每滑動一幀整個畫面都要重新合成，
    // 底下的網頁跟著整頁重畫（Google 這類圖多的頁面 GPU 每幀約 16ms，只剩 60fps）；靜態圖片幾乎不花 GPU
    var frozen by remember { mutableStateOf<ImageBitmap?>(null) }
    val sheetShown = sheet.content != null
    LaunchedEffect(sheetShown, tab) {
        if (sheetShown && !tab.isBlank) tab.freezeFrame { if (sheet.content != null) frozen = it } else frozen = null
    }

    // 網址列收合／展開：目標改變時跑一段彈簧動畫（約 0.35 秒、無回彈），可隨時被反方向捲動打斷並從目前位置轉向
    LaunchedEffect(Unit) {
        snapshotFlow { vm.collapsed }.collectLatest { target ->
            animate(vm.collapse, if (target) 1f else 0f, animationSpec = Motion.collapse()) { v, _ -> vm.collapse = v }
        }
    }

    // JankStats 的畫面狀態：掉幀紀錄會標出發生在哪個畫面
    val metrics = remember(view) { PerformanceMetricsState.getHolderForHierarchy(view) }
    SideEffect { metrics.state?.putState("screen", if (overview) "overview" else if (tab.isBlank) "start" else "browse") }

    // 預測式返回（Predictive Back）＝邊緣滑動導覽：左緣往右滑返回上一頁，右緣往左滑前往下一頁；
    // 前一頁的截圖跟手從側邊滑入，目前頁面帶陰影移動並有輕微視差
    val back = remember(tab) { Animatable(0f) }
    var backForward by remember(tab) { mutableStateOf(false) }
    var backShot by remember(tab) { mutableStateOf<ImageBitmap?>(null) }
    // 網頁沒有上一頁時，返回手勢回到首頁（不退出 App）；原本的網頁留在分頁管理裡，之後還找得回來
    BackHandler(!tab.canGoBack && !tab.isBlank && !focused && !overview) { vm.newTab(incognito = tab.incognito) }
    PredictiveBackHandler(tab.canGoBack && !focused && !overview) { events ->
        try {
            events.collect { e ->
                if (back.value == 0f) {
                    backForward = e.swipeEdge == BackEventCompat.EDGE_RIGHT && tab.canGoForward
                    backShot = if (backForward) tab.nextShot() else tab.previousShot()
                }
                back.snapTo(e.progress)
            }
            back.animateTo(1f, Motion.timed(Motion.ShortMs))
            if (backForward) tab.webView.goForward() else tab.webView.goBack()
            back.snapTo(0f)
        } catch (e: CancellationException) {
            scope.launch { back.animateTo(0f, Motion.glass()) }
            throw e
        }
    }
    BackHandler(focused) { cancel() }
    BackHandler(overview) { overview = false }
    BackHandler(finding) { tab.clearFind(); finding = false }
    BackHandler(vm.controlsHidden) { vm.controlsHidden = false }
    BackHandler(menu.open) { menu.dismiss() }
    BackHandler(sheet.open) { sheet.dismiss() }
    BackHandler(vm.longPress != null) { vm.longPress = null }
    BackHandler(vm.customView != null) { vm.exitFullscreen() }

    // 分頁總覽的 shared element 轉場：網頁縮成卡片飛到網格中的位置；點卡片反向放大；新分頁從＋長出
    // 先非同步擷取縮圖（PixelCopy，約 1 幀），拿到後才開始轉場，飛行卡片一開始就有畫面、不會卡頓
    val enterOverview: (Rect?) -> Unit = { from ->
        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
        tab.snapshot {
            ov.tab = tab
            ov.from = from
            ov.active = true
            overview = true
            scope.launch {
                ov.progress.snapTo(0f)
                ov.progress.animateTo(1f, Motion.overview())
                ov.active = false
                ov.from = null
            }
        }
    }
    // 分頁總覽疊在瀏覽畫面上面（不再把瀏覽畫面整個拿掉）：網頁一直留在畫面裡，回到分頁時不必重新接上、重新排版
    val overviewLayer: (@Composable () -> Unit)? = if (!overview) null else { {
        // 網頁從狀態列下方開始，縮圖也是；飛行卡片的全螢幕位置對齊網頁區域
        val statusPx = WindowInsets.statusBars.getTop(LocalDensity.current).toFloat()
        val fullRect = Rect(0f, if (ov.tab?.isBlank == false) statusPx else 0f, size.x, size.y)
        val leave: (Tab?) -> Unit = { target ->
            ov.tab = target
            ov.from = null
            ov.active = true
            scope.launch {
                ov.progress.snapTo(1f)
                ov.progress.animateTo(0f, Motion.overview())
                if (target != null) vm.select(target) else vm.newTab()
                overview = false
                ov.active = false
            }
        }
        // 擋住底下瀏覽畫面的觸控
        Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) {
            TabsOverview(
                vm, ov,
                onOpen = { leave(it) },
                onNew = { leave(null) },
                onDone = { leave(vm.current) },
            )
            FlyingCard(ov, fullRect, bg)
        }
    } }

    // 網址列手勢：左右滑動切換分頁（網頁與膠囊一起跟手），往上滑進入分頁總覽（網頁跟手縮小）
    val swipe = remember(tab) { Animatable(0f) }
    val lift = remember(tab) { Animatable(0f) }
    val slop = with(LocalDensity.current) { 8.dp.toPx() }
    val gestures = remember(tab, size) {
        object : PillGestures {
            var acc = Offset.Zero
            var axis = 0 // 0 未定、1 水平、2 往上、3 忽略

            override fun onStart() {
                acc = Offset.Zero
                axis = 0
            }

            override fun onDrag(delta: Offset) {
                acc += delta
                if (axis == 0 && acc.getDistance() > slop) {
                    axis = when {
                        abs(acc.x) > abs(acc.y) -> 1
                        acc.y < 0 -> 2
                        else -> 3
                    }
                }
                scope.launch {
                    when (axis) {
                        1 -> {
                            // 第一個分頁往右滑時沒有可切換的分頁，加上阻尼
                            val next = swipe.value + delta.x
                            val damp = if (next > 0 && vm.neighbor(-1) == null) 0.3f else 1f
                            swipe.snapTo(swipe.value + delta.x * damp)
                        }
                        2 -> lift.snapTo((-acc.y / (size.y * 0.45f)).coerceIn(0f, 1f))
                    }
                }
            }

            override fun onEnd(velocity: Velocity) {
                scope.launch {
                    when (axis) {
                        1 -> {
                            val w = size.x
                            val left = vm.neighbor(-1)
                            // 依位置或甩動速度決定；速度方向必須與拖曳方向一致
                            val goRight = swipe.value < 0 && (swipe.value < -w * 0.3f || velocity.x < -900f)
                            val goLeft = left != null && swipe.value > 0 && (swipe.value > w * 0.3f || velocity.x > 900f)
                            when {
                                goRight -> {
                                    swipe.animateTo(-w, Motion.standard())
                                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    // 最後一個分頁繼續往左滑：建立新分頁
                                    vm.neighbor(1)?.let(vm::select) ?: vm.newTab()
                                }
                                goLeft -> {
                                    swipe.animateTo(w, Motion.standard())
                                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    vm.select(left)
                                }
                                else -> swipe.animateTo(0f, Motion.glass())
                            }
                        }
                        2 -> if (lift.value > 0.3f || velocity.y < -900f) {
                            // 從手勢縮小後的位置接著飛向卡片
                            val l = lift.value
                            val w = size.x * (1f - 0.3f * l)
                            val h = size.y * (1f - 0.3f * l)
                            val left = (size.x - w) / 2
                            val top = (size.y - h) / 2 - 0.06f * size.y * l
                            enterOverview(Rect(left, top, left + w, top + h))
                            lift.snapTo(0f)
                        } else lift.animateTo(0f, Motion.glass())
                    }
                }
            }
        }
    }
    val swiping by remember(tab) { derivedStateOf { swipe.value != 0f } }
    val lifting by remember(tab) { derivedStateOf { lift.value != 0f } }
    val backing by remember(tab) { derivedStateOf { back.value != 0f } }
    val pageActions = PageMenuActions(
        onFind = { finding = true },
        onLibrary = {
            sheet.show { dismiss ->
                StartPage(vm, menu, Modifier.fillMaxSize(), initialPage = 1) { url -> tab.load(url); dismiss() }
            }
        },
        onDownloads = { sheet.show { DownloadsContent(menuTone) } },
        onSiteSettings = { sheet.show { SiteSettings(tab, vm, menuTone) } },
        onSettings = { sheet.show { SettingsContent(vm, menuTone) } },
    )

    // 沒有原生 backdrop 時，網頁上與輸入中的玻璃改用輕量磨砂底（網頁上的控制列另有系統模糊視窗）
    androidx.compose.runtime.CompositionLocalProvider(LocalOpaqueGlass provides (!PlatformBackdrop && (!tab.isBlank || focused)), LocalCheapGlass provides (!PlatformBackdrop && tab.isBlank)) {
        Box(
            Modifier.fillMaxSize().background(bg).onGloballyPositioned { size = Offset(it.size.width.toFloat(), it.size.height.toFloat()) },
        ) {
            // 網頁延伸到狀態列與手勢導覽列下方；它也是玻璃取樣的來源。
            // 沒有原生 backdrop 時網頁不能當來源（WebView 被錄進離屏圖層會讓 App 閃退），只有首頁當來源
            Box(Modifier.fillMaxSize().then(if (PlatformBackdrop || tab.isBlank) Modifier.hazeSource(haze) else Modifier)) {
                // 邊緣返回時，前一頁（或下一頁）從側邊以視差滑入
                if (backing) {
                    NeighborPreview(
                        null, bg,
                        Modifier.graphicsLayer {
                            val dir = if (backForward) 1f else -1f
                            translationX = dir * size.x * 0.3f * (1f - back.value)
                        },
                        backShot,
                    )
                }
                if (swiping) {
                    NeighborPreview(vm.neighbor(-1), bg, Modifier.graphicsLayer { translationX = swipe.value - size.x - 16.dp.toPx() })
                    NeighborPreview(vm.neighbor(1), bg, Modifier.graphicsLayer { translationX = swipe.value + size.x + 16.dp.toPx() })
                }
                // 縮小、圓角只在往上滑時套用 layer；平常不加，讓起始頁上的玻璃能取樣到底下內容
                Box(
                    // 跟手移動用 GPU 平移（只在滑動中套用）：用 offset 的話每幀都要重新排版 WebView、
                    // Chromium 跟著更新可視區域，返回手勢時主執行緒每幀約 35ms；純平移不觸發 WebView 離屏圖層的問題
                    Modifier.fillMaxSize()
                        .then(
                            if (backing || swiping) Modifier.graphicsLayer {
                                val dir = if (backForward) -1f else 1f
                                translationX = swipe.value + dir * back.value * size.x
                            } else Modifier,
                        )
                        .then(if (backing) Modifier.shadow(24.dp) else Modifier)
                        // 面板滑動期間把底下畫面畫成一張快取圖層（約一個螢幕大小，停下就釋放），每幀不必整頁重畫。
                        // 只在網頁已換成凍結圖片（WebView 隱藏）或起始頁時才用：WebView 不能放進離屏圖層（Android 17 會閃退）
                        .then(
                            if (sheet.content != null && (frozen != null || tab.isBlank) && !backing && !swiping && !lifting) Modifier.graphicsLayer {
                                compositingStrategy = if (sheet.moving()) androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                                else androidx.compose.ui.graphics.CompositingStrategy.Auto
                            } else Modifier,
                        )
                        .then(
                            if (lifting) Modifier.graphicsLayer {
                                val l = lift.value
                                scaleX = 1f - 0.3f * l
                                scaleY = 1f - 0.3f * l
                                translationY = -0.06f * size.y * l
                                shape = RoundedCornerShape((l.coerceIn(0f, 1f) * 48).dp)
                                clip = true
                            } else Modifier,
                        ),
                ) {
                    // 輸入、選單、面板開著（含關閉動畫）時首頁的玻璃視窗先收起，不會壓在選單、面板、輸入框上面
                    if (tab.isBlank) androidx.compose.runtime.CompositionLocalProvider(LocalHideBlurWindows provides (focused || menu.spec != null || sheet.content != null || LocalHideBlurWindows.current)) {
                        HomePage(vm, Modifier.fillMaxSize().background(bg), onSearch = { query = TextFieldValue(); typed = false; focused = true }) { tab.load(it) }
                    }
                    else Column(Modifier.fillMaxSize()) {
                        // 網頁內容從狀態列下方開始（否則頁首會被時間與狀態圖示蓋住）；
                        // 狀態列區域以 theme-color／頁面背景色染色，底部則延伸到控制列與手勢導覽列下方
                        Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBarsIgnoringVisibility).drawBehind { drawRect(statusFill) })
                        // 展開狀態的控制列所佔高度（只依直／橫向決定，不隨捲動變化，避免網頁每幀重排）
                        val density = LocalDensity.current
                        // 用「不論是否隱藏」的高度：影片全螢幕隱藏系統列時，背後網頁不會因高度改變而整頁重排
                        val navBottom = WindowInsets.navigationBarsIgnoringVisibility.getBottom(density)
                        val controlsPx = with(density) { (Dimens.Control + Dimens.NavGap * 2).roundToPx() }
                        LaunchedEffect(tab, landscape, navBottom) {
                            if (landscape) tab.setControlsInset(0, controlsPx) else tab.setControlsInset(navBottom + controlsPx, 0)
                        }
                        key(tab.id) {
                            Box(Modifier.fillMaxWidth().weight(1f)) {
                                AndroidView(
                                    factory = { tab.webView.also { (it.parent as? ViewGroup)?.removeView(it) } },
                                    modifier = Modifier.fillMaxSize(),
                                    // 分頁總覽蓋住、或面板滑動中（改顯示凍結畫面）時不畫網頁；INVISIBLE 保留版面，回來不必重新排版
                                    update = { it.visibility = if (overview || frozen != null) android.view.View.INVISIBLE else android.view.View.VISIBLE },
                                )
                                frozen?.let { Image(it, null, Modifier.fillMaxWidth(), alignment = Alignment.TopStart, contentScale = ContentScale.FillWidth) }
                                PullIndicator(tab, Modifier.align(Alignment.TopCenter))
                            }
                        }
                    }
                }
            }

            // 聚焦輸入時背景網頁輕微變暗；點遮罩等同取消
            val dim = animateFloatAsState(if (focused) 0.28f else 0f, Motion.timed(), label = "dim")
            if (focused || dim.value > 0f) {
                Box(
                    Modifier.fillMaxSize()
                        .graphicsLayer { alpha = dim.value }
                        .background(Color.Black)
                        .pointerInput(Unit) { detectTapGestures { cancel() } },
                )
            }

            // 隱藏控制項：整列滑出畫面，點畫面底部（直向）／頂部（橫向）叫回
            val hide = animateFloatAsState(if (vm.controlsHidden) 1f else 0f, Motion.glass(), label = "hide")
            val hideShift = with(LocalDensity.current) { 120.dp.toPx() }
            // 鍵盤：不用 imePadding（鍵盤動畫每一幀都會重新量測、排版整列玻璃），改在擺放階段位移，每幀只移動位置
            val imeInsets = WindowInsets.ime
            val navInsets = WindowInsets.navigationBars
            val place = (if (landscape) Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = Dimens.NavGap)
            else Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = Dimens.NavGap))
                .offset {
                    val lift = if (landscape) 0 else (imeInsets.getBottom(this) - navInsets.getBottom(this)).coerceAtLeast(0)
                    IntOffset(0, ((if (landscape) -1f else 1f) * hide.value * hideShift).roundToInt() - lift)
                }
            Column(
                place.widthIn(max = if (landscape) Dimens.LandscapeMaxWidth else 10_000.dp)
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.EdgeMargin),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val panel = @Composable {
                    AnimatedVisibility(focused, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                        Suggestions(vm, if (typed) query.text else "", haze, tone) {
                            tab.load(it)
                            cancel()
                        }
                    }
                }
                // 網址列上方的浮動膠囊：翻譯狀態（點一下切回原文）＋ IDM 式「下載影片」；以彈簧從網址列「長」出來
                val showTr = tab.translated && !focused && !finding
                val showDl = tab.media.isNotEmpty() && !focused && !finding && settings.videoDownloadButton
                AnimatedVisibility(showTr || showDl, enter = expandVertically(Motion.glass()) + fadeIn(), exit = shrinkVertically(Motion.glass()) + fadeOut()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        AnimatedVisibility(
                            showTr,
                            enter = fadeIn(Motion.timed()) + slideInVertically(Motion.standard()) { it / 3 },
                            exit = fadeOut(Motion.timed(Motion.ShortMs)),
                        ) {
                            val working = tab.trDone < tab.trSent
                            // 點一下切回原文（再按「翻譯網頁」又翻回設定的語言）
                            LiquidGlass(haze, tone, CircleShape, Modifier.height(40.dp), onClick = { vm.toggleTranslate(tab) }) {
                                Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Sym("translate", accent, 20.dp)
                                    AnimatedContent(working, transitionSpec = { fadeIn(Motion.timed()) togetherWith fadeOut(Motion.timed()) }, label = "tr") { w ->
                                        Text(
                                            if (w) t("翻譯中 %1\$s/%2\$s", tab.trDone, tab.trSent) else "${vm.settings.value.translateTarget.label}・${vm.activeTranslateEngine().label.substringBefore('（')}",
                                            Modifier.padding(start = 6.dp), color = tone.content, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                            }
                        }
                        AnimatedVisibility(
                            showDl,
                            enter = fadeIn(Motion.timed()) + slideInVertically(Motion.standard()) { it / 3 },
                            exit = fadeOut(Motion.timed(Motion.ShortMs)),
                        ) {
                            var pillBounds by remember { mutableStateOf(Rect.Zero) }
                            var probing by remember { mutableStateOf(false) }
                            LiquidGlass(
                                haze, tone, CircleShape,
                                Modifier.height(40.dp).onGloballyPositioned { pillBounds = it.boundsInRoot() },
                                onClick = {
                                    if (probing) return@LiquidGlass
                                    // 先分析畫質與大小，再列出讓使用者選（畫質高的在上）
                                    probing = true
                                    scope.launch {
                                        val options = vm.mediaOptions(tab)
                                        probing = false
                                        // 第一項：用 App 播放器全螢幕播放最高畫質；其餘為各畫質下載
                                        val play = options.firstOrNull()?.let { best -> listOf(MenuItem(t("用播放器全螢幕播放"), "play_circle") { vm.playSniffed(tab, best.url) }) }.orEmpty()
                                        menu.show(MenuSpec(pillBounds, play + options.map { o -> MenuItem(mediaOptionLabel(o), "download") { vm.downloadSniffed(tab, o.url) } }, width = 300))
                                    }
                                },
                            ) {
                                Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Sym("download", accent, 20.dp)
                                    Text(
                                        if (probing) t("分析影片中…") else if (tab.media.size == 1) t("下載影片") else t("下載影片（%1\$s）", tab.media.size),
                                        Modifier.padding(start = 6.dp), color = tone.content, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                }
                if (!landscape) panel()
                val controlsRow = @Composable { toMain: (Rect) -> Rect, onGlass: ((List<Rect>) -> Unit)? ->
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val rowW = constraints.maxWidth.toFloat()
                        // 相鄰分頁的膠囊從旁滑入
                        if (swiping) {
                            vm.neighbor(-1)?.let { GhostPill(it, haze, tone, Modifier.offset { IntOffset((swipe.value - rowW - 8.dp.toPx()).roundToInt(), 0) }) }
                            GhostPill(vm.neighbor(1), haze, tone, Modifier.offset { IntOffset((swipe.value + rowW + 8.dp.toPx()).roundToInt(), 0) })
                        }
                        // 用 offset（排版階段位移）而非 graphicsLayer：原生 backdrop 玻璃放在祖先 layer 裡會取樣不到網頁
                        Box(Modifier.offset { IntOffset(swipe.value.roundToInt(), 0) }) {
                            if (finding) FindBar(tab, haze, menuTone) { finding = false }
                            else Controls(
                                vm, tab, haze, tone, focused, query,
                                onQuery = { if (it.text != query.text) typed = true; query = it },
                                onFocus = {
                                    query = TextFieldValue(tab.url, TextRange(0, tab.url.length))
                                    typed = false
                                    focused = true
                                },
                                onSubmit = { if (it.isNotBlank()) tab.load(it); cancel() },
                                onCancel = cancel,
                                onTabs = { enterOverview(null) },
                                onDownloads = { pageActions.onDownloads() },
                                onPageMenu = { anchor -> menu.show(pageMenu(toMain(anchor), tab, vm, menuTone, context, pageActions)) },
                                // 長按返回：列出上一頁歷史（最近的在最上面），點一下直接跳回那一頁
                                onBackLongPress = { anchor ->
                                    val list = tab.webView.copyBackForwardList()
                                    val items = (1..minOf(list.currentIndex, 8)).mapNotNull { n ->
                                        list.getItemAtIndex(list.currentIndex - n)?.let { h ->
                                            MenuItem(h.title?.takeIf { it.isNotBlank() } ?: h.url, "arrow_back") { tab.webView.goBackOrForward(-n) }
                                        }
                                    }
                                    if (items.isNotEmpty()) menu.show(MenuSpec(toMain(anchor), items))
                                },
                                onTabsLongPress = { anchor ->
                                    val n = vm.visibleTabs().size
                                    menu.show(
                                        MenuSpec(
                                            toMain(anchor),
                                            listOf(
                                                MenuItem(t("新增分頁"), "add") { vm.newTab(incognito = false) },
                                                MenuItem(t("新增無痕分頁"), "visibility_off") { vm.newTab(incognito = true) },
                                                MenuItem(t("關閉此分頁"), "close") { vm.close(tab) },
                                                MenuItem(t("關閉所有分頁（%1\$s 個）", n), "close", destructive = true) { vm.closeAll() },
                                            ),
                                        ),
                                    )
                                },
                                pillGestures = gestures,
                                alignTop = landscape,
                                onGlassBounds = onGlass,
                            )
                        }
                    }
                }
                // 沒有原生 backdrop（Android 17.0）時，網頁上的控制列放進系統模糊視窗，由系統合成器做真正的毛玻璃；
                // 輸入網址、尋找、橫向時維持在主畫面（不透明磨砂底）
                val sysBlur = (DEBUG_FORCE_SYSBLUR || (!PlatformBackdrop && crossBlur)) && !tab.isBlank && !focused && !finding && !landscape
                // 面板（設定、網站設定…）打開時不畫控制列：面板蓋住整個底部，而系統模糊視窗／原生取樣的玻璃會疊在面板之上
                // 系統模糊視窗一直保留：輸入中、面板或選單開著等情況改畫在主畫面（inline），切回來不必重建、不會換色；
                // 選單開著時控制列在遮罩下面，點哪裡都只會關選單，不會同時打開別的東西
                if (!PlatformBackdrop && crossBlur) {
                    var glass by remember { mutableStateOf(emptyList<Rect>()) }
                    SystemBlurWindow(Dimens.Control, regions = { glass.map { it.translate(swipe.value, 0f) } }, inline = !sysBlur || sheet.open || menu.spec != null) { toMain ->
                        // 只有視窗裡那份回報玻璃範圍：主畫面那份（輸入中）收起前最後一幀是動畫中途的尺寸，會蓋掉正確值
                        val inWindow = com.liquid.browser.ui.glass.LocalInBlurWindow.current
                        controlsRow(toMain) { if (inWindow) glass = it }
                    }
                } else controlsRow({ it }, null)
                if (landscape) panel()
            }

            if (vm.controlsHidden) {
                Box(
                    Modifier.align(if (landscape) Alignment.TopCenter else Alignment.BottomCenter).fillMaxWidth().height(56.dp)
                        .pointerInput(Unit) { detectTapGestures { vm.controlsHidden = false } },
                )
            }

            // 跳轉 App 提示列（在網址列上方）；提示訊息同時出現時往上讓開
            AppPromptBar(vm, haze, menuTone, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 72.dp))

            // 玻璃提示訊息
            AnimatedVisibility(
                vm.toast != null,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (vm.appPrompt?.tab == vm.current) 132.dp else 72.dp),
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut(),
            ) {
                LiquidGlass(haze, menuTone, CircleShape) {
                    Text(vm.toast.orEmpty(), Modifier.padding(horizontal = 18.dp, vertical = 10.dp), color = menuTone.content, fontSize = 14.sp)
                }
            }

            val statusTop = WindowInsets.statusBars.getTop(LocalDensity.current).toFloat()
            LinkPreviewHost(vm, haze, menuTone, statusTop)
            GlassMenuHost(menu, haze, menuTone)
            GlassSheetHost(sheet, haze, menuTone)

            // 分頁總覽：跟首頁同一套（沒有原生 backdrop 時用 Material 3 表面）
            overviewLayer?.let { layer -> androidx.compose.runtime.CompositionLocalProvider(LocalOpaqueGlass provides false, LocalCheapGlass provides !PlatformBackdrop) { layer() } }

            // 網頁影片全螢幕：隱藏系統列、允許旋轉
            vm.customView?.let { fullscreen ->
                DisposableEffect(fullscreen) {
                    val window = activity?.window
                    val controller = window?.let { WindowCompat.getInsetsController(it, view) }
                    controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    controller?.hide(WindowInsetsCompat.Type.systemBars())
                    // 不強制轉向：跟著手機方向（使用者自己轉手機）
                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
                    onDispose {
                        controller?.show(WindowInsetsCompat.Type.systemBars())
                        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView({ fullscreen.also { (it.parent as? ViewGroup)?.removeView(it) } }, Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** 切換分頁時從旁滑入的相鄰分頁畫面（快取縮圖；沒有則顯示起始頁底色）。 */
@Composable
private fun NeighborPreview(tab: Tab?, bg: Color, modifier: Modifier, shot: ImageBitmap? = tab?.thumbnail) {
    Box(modifier.fillMaxSize().background(bg)) {
        shot?.let { Image(it, null, Modifier.fillMaxSize(), alignment = Alignment.TopCenter, contentScale = ContentScale.Crop) }
    }
}

/** 相鄰分頁的網址膠囊（切換分頁時從旁滑入）。 */
@Composable
private fun GhostPill(tab: Tab?, haze: HazeState, tone: Tone, modifier: Modifier) {
    LiquidGlass(haze, tone, CircleShape, modifier.fillMaxWidth().height(Dimens.Control)) {
        Text(
            tab?.let { if (it.isBlank) t("起始頁") else it.domain } ?: t("新分頁"),
            color = tone.content, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 嗅探清單的顯示名稱：類型＋檔名 */
/** 下載選項：「1080p・約 245 MB」；畫質不明時顯示格式（MP4、HLS），大小不明時顯示「大小未知」 */
private fun mediaOptionLabel(o: com.liquid.browser.browser.MediaOption): String {
    val q = if (o.height > 0) "${o.height}p" else o.kind
    val size = when {
        o.bytes <= 0 -> t("大小未知")
        o.bytes >= 1L shl 30 -> t("約 %1\$s", "%.2f GB".format(o.bytes / 1073741824.0))
        else -> t("約 %1\$s", "%.0f MB".format(maxOf(1.0, o.bytes / 1048576.0)))
    }
    return "$q・$size"
}

/** 下拉重新整理指示器：跟手往下（越拉越緊），圖示隨距離轉動，過門檻變藍；放手後轉圈直到載入完成，再以彈簧收回 */
@Composable
private fun PullIndicator(tab: com.liquid.browser.browser.Tab, modifier: Modifier) {
    val density = LocalDensity.current
    val threshold = with(density) { 96.dp.toPx() }
    val rest = with(density) { 64.dp.toPx() }
    val raw = tab.pull
    val dragging = raw > 0f
    val damped = (raw * 0.6f).let { d -> val cap = rest * 1.15f; if (d < cap) d else cap + (d - cap) * 0.15f }
    val target = when { dragging -> damped; tab.refreshing -> rest; else -> 0f }
    // 收回用無回彈的彈簧（有回彈會在 0 附近晃很久、殘留在頂端）
    val y by animateFloatAsState(
        target,
        if (dragging) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(1f, 500f, 0.5f),
        label = "pull",
    )
    if (y < with(density) { 4.dp.toPx() } && !tab.refreshing) return
    val armed = raw >= threshold
    val spin = if (tab.refreshing) {
        val inf = androidx.compose.animation.core.rememberInfiniteTransition(label = "spin")
        inf.animateFloat(0f, 360f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(700, easing = androidx.compose.animation.core.LinearEasing)), label = "spin")
    } else null
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    // 裁在網頁區域內：收回時不會畫到狀態列上
    Box(modifier.fillMaxWidth().height(160.dp).clipToBounds(), Alignment.TopCenter) { Box(
        Modifier.size(40.dp)
            .graphicsLayer {
                translationY = y - size.height
                val k = (y / rest).coerceIn(0f, 1f)
                scaleX = 0.4f + 0.6f * k
                scaleY = 0.4f + 0.6f * k
                alpha = k
                rotationZ = spin?.value ?: ((raw / threshold).coerceIn(0f, 1.2f) * 300f)
            }
            .shadow(6.dp, androidx.compose.foundation.shape.CircleShape)
            .background(if (dark) Color(0xFF2C2C2E) else Color.White, androidx.compose.foundation.shape.CircleShape),
        Alignment.Center,
    ) {
        com.liquid.browser.ui.Sym("refresh", if (armed || tab.refreshing) com.liquid.browser.ui.accent else Palette.Gray, 22.dp)
    } }
}
