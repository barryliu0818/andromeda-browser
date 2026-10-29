package com.liquid.browser.ui.glass

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onLayoutRectChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.compose.LocalSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.liquid.browser.R
import kotlin.math.roundToInt

/**
 * 系統視窗模糊（cross-window blur，Android 12+；HyperOS 控制中心用的同一套）：
 * 由系統合成器模糊視窗底下的畫面，完全不碰 WebView 的繪製，所以沒有原生 backdrop 的系統（Android 17.0）
 * 也能在網頁上做出真正的毛玻璃。
 *
 * 做法：內容改放在一個獨立的半透明小視窗裡（系統只對半透明視窗套用背景模糊，所以不用 Compose 內建 Dialog），
 * 視窗大小＝[visible]（內容裡實際有玻璃的範圍，例如收合中的膠囊），圓角＝高度一半。
 * 內容照原本的排版（佔位大小）畫，只是被視窗裁成 visible 那一塊；內容是主畫面的子組合，狀態與 CompositionLocal 共用。
 * 主畫面留一個同樣大小的佔位，視窗位置即時跟著佔位走（鍵盤升降、隱藏控制列都會跟上）。
 */
@Composable
fun SystemBlurWindow(
    height: Dp,
    /** 每塊玻璃的範圍（相對於佔位）；每塊一個模糊視窗，最多 [MaxRegions] 塊 */
    regions: () -> List<Rect>,
    blurRadius: Dp = 36.dp,
    modifier: Modifier = Modifier,
    /** true＝暫時畫在主畫面（輸入中、面板開著…）：視窗保留但隱藏，切回來立即顯示，不必重建（重建要等 0.4 秒、中間會換色） */
    inline: Boolean = false,
    /** toMain：把視窗內元件的座標（boundsInRoot）換成主畫面座標，讓選單等錨點對得上 */
    content: @Composable (toMain: (Rect) -> Rect) -> Unit,
) {
    // 位置、大小、玻璃範圍都不在組合階段讀（避免排版階段寫入、組合階段讀取的反向寫入：
    // 收合動畫每幀都會倒回去重組，兩個視窗裡的控制列也跟著整個重組）：排版回呼與 snapshotFlow 直接更新視窗
    val place = remember { Placement() }
    // 開 App／切到網頁的那一刻最忙（網頁開始載入、主畫面第一次組合）：模糊小視窗晚 0.4 秒再建立，
    // 這段時間內容先畫在主畫面（輕量磨砂底），網址列不會消失
    var ready by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(400); ready = true }
    // 從主畫面切回視窗時，等視窗畫完兩幀才拿掉主畫面那份，中間不會空一下
    var winShown by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(ready, inline) {
        if (ready && !inline) { androidx.compose.runtime.withFrameNanos {}; androidx.compose.runtime.withFrameNanos {}; winShown = true } else winShown = false
    }
    Box(modifier.fillMaxWidth().height(height).onLayoutRectChanged(0, 0) { b -> place.origin = Offset(b.positionInWindow.x.toFloat(), b.positionInWindow.y.toFloat()); place.size = IntSize(b.width, b.height); place.push() }) {
        if (!ready || inline || !winShown) content { it }
    }
    if (!ready) return
    val latestInline by rememberUpdatedState(inline)

    val context = LocalContext.current
    val parent = rememberCompositionContext()
    val lifecycle = LocalLifecycleOwner.current
    val saved = LocalSavedStateRegistryOwner.current
    val latest by rememberUpdatedState(content)
    val latestRegions by rememberUpdatedState(regions)
    val blurPx = with(LocalDensity.current) { blurRadius.roundToPx() }
    val wins = remember { List(MaxRegions) { BlurDialog(context, parent).apply { bar = true } } }
    DisposableEffect(wins) {
        wins.forEach { win ->
            win.decor.setViewTreeLifecycleOwner(lifecycle)
            win.decor.setViewTreeSavedStateRegistryOwner(saved)
            // 每個視窗都畫完整的內容，只露出自己那一塊（觸控也只收自己那一塊）
            // 畫在主畫面時視窗內不組合內容（避免兩份輸入框同時要求焦點）
            win.view.setContent { win.Content { toMain -> if (!latestInline) latest(toMain) } }
            win.show()
        }
        place.push = { if (place.size != IntSize.Zero) { val rs = latestRegions(); wins.forEachIndexed { i, w -> w.update(place.origin, rs.getOrNull(i) ?: Rect.Zero, place.size, blurPx, hidden = latestInline) } } }
        place.push()
        onDispose { place.push = {}; wins.forEach { it.park(); it.dismiss() } }
    }
    // 玻璃範圍（含左右滑切換分頁的位移）改變時更新視窗：在協程裡觀察，不觸發重組
    androidx.compose.runtime.LaunchedEffect(wins) { androidx.compose.runtime.snapshotFlow { latestRegions() to latestInline }.collect { place.push() } }
}

/** 佔位的位置與大小（排版回呼寫入，不是 Compose 狀態），push 把目前資料套到視窗 */
private class Placement {
    var origin = Offset.Zero
    var size = IntSize.Zero
    var push: () -> Unit = {}
}

// 返回鈕、網址列、分頁鈕（Safari 精簡版面）各一塊
private const val MaxRegions = 3

/**
 * 預備好的系統模糊視窗：打開選單、面板時不必當場建立視窗（建 Dialog、加進 WindowManager、第一次排版），直接拿來用。
 * 閒置的視窗內容是 GONE（系統不配置畫面緩衝區），每個只佔幾百 KB；最多留 [Max] 個，記憶體吃緊或 App 關閉時全部釋放。
 */
internal object BlurPool {
    private const val Max = 2
    private val idle = ArrayList<BlurDialog>(Max)

    fun take(context: Context, parent: CompositionContext): BlurDialog {
        val i = idle.indexOfFirst { it.context === context || (it.context as? android.content.ContextWrapper)?.baseContext === context }
        val d = if (i >= 0) idle.removeAt(i) else BlurDialog(context, parent)
        d.view.setParentCompositionContext(parent)
        return d
    }

    fun give(d: BlurDialog) {
        // 換成空內容：閒置時不會重新組合上一次的內容
        d.view.setContent {}
        d.park()
        if (idle.size < Max && d.isShowing) idle += d else d.dismiss()
    }

    /** 閒置時先建好，顯示但內容 GONE */
    fun prewarm(context: Context, parent: CompositionContext, lifecycle: androidx.lifecycle.LifecycleOwner, saved: androidx.savedstate.SavedStateRegistryOwner) {
        while (idle.size < Max) idle += BlurDialog(context, parent).also {
            // ComposeView 量測時需要 ViewTree 的 Lifecycle／SavedState（沒有會閃退）
            it.decor.setViewTreeLifecycleOwner(lifecycle)
            it.decor.setViewTreeSavedStateRegistryOwner(saved)
            it.view.setContent {}
            it.park()
            it.show()
        }
    }

    fun clear() { idle.forEach { it.dismiss() }; idle.clear() }
}

/**
 * 自動問題紀錄用：網址列的系統模糊視窗目前有幾個真的顯示在畫面上（可見、而且不是收起來的 1×1）。
 * 分頁總覽、面板、選單、影片全螢幕開著時這個數應該是 0；不是 0 就是視窗浮在它們上面（圖層重疊）。
 */
object BlurWatch {
    @Volatile var barShown = 0
        internal set
}

internal class BlurDialog(context: Context, parent: CompositionContext) : Dialog(context, R.style.BlurWindow) {
    /** 網址列（SystemBlurWindow）的視窗；BlurPool 的單塊玻璃（選單、面板）不算 */
    var bar = false
    private var counted = false
    private fun count(shown: Boolean) {
        if (!bar || shown == counted) return
        counted = shown
        BlurWatch.barShown += if (shown) 1 else -1
    }
    val view = ComposeView(context).apply { setParentCompositionContext(parent) }
    val decor get() = window!!.decorView
    private val bg = GradientDrawable().apply { setColor(0) }
    private var rect by mutableStateOf(Rect.Zero)
    private var full by mutableStateOf(IntSize.Zero)
    private var origin = Offset.Zero
    private var blurSet = false

    /** 視窗外的點擊（需要 FLAG_WATCH_OUTSIDE_TOUCH）：系統 dim 開著時，視窗外的觸控不會傳到主畫面，改在這裡收 */
    var onOutside: (() -> Unit)? = null

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_OUTSIDE) { onOutside?.invoke(); return true }
        return super.onTouchEvent(event)
    }

    init {
        setContentView(view)
        setCancelable(false)
        window!!.apply {
            setWindowAnimations(0)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // 不搶焦點（鍵盤、返回鍵仍給主畫面）、視窗外的觸控交給主畫面、座標用螢幕絕對位置
            addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            )
            setBackgroundDrawable(bg)
            attributes = attributes.apply { gravity = Gravity.TOP or Gravity.START; width = 1; height = 1 }
        }
    }

    @Composable
    fun Content(content: @Composable (toMain: (Rect) -> Rect) -> Unit) {
        val density = LocalDensity.current
        val f = full
        // 視窗內有真模糊，玻璃用一般透明度；內容照完整大小排版，往左上位移 visible 的起點（位移在排版階段讀，不重組）
        val toMain = remember { { b: Rect -> b.translate(origin.x + rect.left, origin.y + rect.top) } }
        CompositionLocalProvider(LocalInBlurWindow provides true, LocalOpaqueGlass provides false, LocalCheapGlass provides false, LocalGlassShadow provides false) {
            Box(
                Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
                    .offset { IntOffset(-rect.left.roundToInt(), -rect.top.roundToInt()) }
                    .requiredSize(with(density) { f.width.toDp() }, with(density) { f.height.toDp() }),
            ) { content(toMain) }
        }
    }

    /** 收起待用：內容 GONE、縮成 1×1、清掉位移 */
    fun park() {
        count(false)
        val w = window ?: return
        w.decorView.visibility = android.view.View.GONE
        w.decorView.translationY = 0f
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        onOutside = null
        rect = Rect.Zero
        val lp = w.attributes
        if (lp.width != 1 || lp.height != 1) { lp.width = 1; lp.height = 1; w.attributes = lp }
    }

    fun update(o: Offset, r: Rect, s: IntSize, blurPx: Int, cornerPx: Float? = null, hidden: Boolean = false) {
        rect = r; origin = o
        if (full != s) full = s
        val w = window ?: return
        // 被總覽、播放器蓋住：隱藏視窗，連模糊一起消失
        w.decorView.visibility = if (hidden) android.view.View.GONE else android.view.View.VISIBLE
        count(!hidden && r.width >= 1f && r.height >= 1f)
        if (hidden) return
        // 這塊玻璃目前不存在（例如收合時的分頁鈕）或還不知道範圍：縮成畫面外 1×1，但內容照常排版——
        // 玻璃範圍是視窗裡的內容排版時回報的，視窗 GONE 就永遠收不到範圍、一直不出現（開網頁後網址列消失）
        if (r.width < 1f || r.height < 1f) {
            val lp = w.attributes
            if (lp.x != -1 || lp.y != -1 || lp.width != 1 || lp.height != 1) { lp.x = -1; lp.y = -1; lp.width = 1; lp.height = 1; w.attributes = lp }
            return
        }
        bg.cornerRadius = cornerPx ?: (r.height / 2f)
        if (!blurSet && w.decorView.isAttachedToWindow) {
            w.setBackgroundBlurRadius(blurPx)
            blurSet = true
        }
        val lp = w.attributes
        val x = (o.x + r.left).roundToInt()
        val y = (o.y + r.top).roundToInt()
        val ww = r.width.roundToInt().coerceAtLeast(1)
        val hh = r.height.roundToInt().coerceAtLeast(1)
        if (lp.x != x || lp.y != y || lp.width != ww || lp.height != hh) {
            lp.x = x; lp.y = y; lp.width = ww; lp.height = hh
            w.attributes = lp
        }
    }
}

/** 系統是否開啟跨視窗模糊（省電模式、開發者選項關閉時為 false，此時不用 [SystemBlurWindow]） */
@Composable
fun crossWindowBlurEnabled(): Boolean {
    val context = LocalContext.current
    return remember { context.getSystemService(WindowManager::class.java)?.isCrossWindowBlurEnabled == true }
}

/** 分頁總覽、播放器、網頁影片全螢幕蓋住畫面時為 true：系統模糊小視窗全部隱藏（否則會浮在它們上面） */
val LocalHideBlurWindows = androidx.compose.runtime.staticCompositionLocalOf { false }

/** 單一玻璃元件的系統模糊視窗：主畫面留佔位（決定大小與位置），內容畫在視窗裡、由視窗接收點擊 */
@Composable
internal fun BlurBoxWindow(
    origin: () -> Offset,
    size: () -> IntSize,
    cornerPx: Float?,
    onLayout: ((() -> Unit) -> Unit),
    onReady: () -> Unit,
    shiftY: (() -> Float)? = null,
    dimBehind: (() -> Float)? = null,
    onOutside: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val latestOutside by rememberUpdatedState(onOutside)
    val parent = rememberCompositionContext()
    val lifecycle = LocalLifecycleOwner.current
    val saved = LocalSavedStateRegistryOwner.current
    val latest by rememberUpdatedState(content)
    val hidden = LocalHideBlurWindows.current
    val latestHidden by rememberUpdatedState(hidden)
    val blurPx = with(LocalDensity.current) { 36.dp.roundToPx() }
    val win = remember { BlurPool.take(context, parent) }
    DisposableEffect(win) {
        win.decor.setViewTreeLifecycleOwner(lifecycle)
        win.decor.setViewTreeSavedStateRegistryOwner(saved)
        win.view.setContent { win.Content { latest() } }
        // 位移在第一次顯示前就套上，不會先在終點閃一幀
        shiftY?.let { win.decor.translationY = it() }
        // 背後變暗交給系統合成器（視窗的 dim）：主畫面在動畫期間完全不必重畫
        dimBehind?.let { win.window?.apply { addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); setDimAmount(it()) } }
        if (onOutside != null) {
            win.window?.addFlags(WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
            win.onOutside = { latestOutside?.invoke() }
        }
        if (!win.isShowing) win.show()
        val push = { val s = size(); if (s != IntSize.Zero) win.update(origin(), Rect(0f, 0f, s.width.toFloat(), s.height.toFloat()), s, blurPx, cornerPx, latestHidden) }
        onLayout(push)
        push()
        // 視窗第一次畫出來後才讓主畫面的佔位隱形，避免中間有一兩幀什麼都沒有
        // 模糊半徑要等視窗真的掛上才設得進去；靜止不動的玻璃（首頁）之後不會再有位置更新，所以掛上後補推一次
        win.decor.post { push(); win.decor.post { onReady() } }
        onDispose { onLayout {}; BlurPool.give(win) }
    }
    // 平移整個視窗內容（RenderThread 層級，不經 WindowManager）；模糊區域由系統跟著畫面位置更新
    if (shiftY != null) androidx.compose.runtime.LaunchedEffect(win) {
        androidx.compose.runtime.snapshotFlow { shiftY() }.collect { win.decor.translationY = it }
    }
    if (dimBehind != null) androidx.compose.runtime.LaunchedEffect(win) {
        androidx.compose.runtime.snapshotFlow { dimBehind() }.collect { win.window?.setDimAmount(it.coerceIn(0f, 1f)) }
    }
    androidx.compose.runtime.SideEffect { val s = size(); if (s != IntSize.Zero) win.update(origin(), Rect(0f, 0f, s.width.toFloat(), s.height.toFloat()), s, blurPx, cornerPx, hidden) }
}
