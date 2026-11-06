package com.liquid.browser.ui.glass

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.layout.onLayoutRectChanged
import androidx.compose.ui.graphics.asImageBitmap
import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Box
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.liquid.browser.core.GlassTokens
import com.liquid.browser.core.Motion
import com.liquid.browser.core.Palette
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.GlassOptics
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.OpticalSizeValue
import dev.chrisbanes.haze.glass.hazeGlass
import dev.chrisbanes.haze.glass.hazeGlassContainer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/*
 * 液態玻璃（LiquidGlass）可重用模組。
 *
 * 實作：Haze 2.x 的 glass 效果。
 *  - Android 13+：AGSL RuntimeShader 做背景模糊＋飽和度＋邊緣透鏡折射＋上亮下暗的高光邊框。
 *  - Android 17 QPR2（API 37.2）+：開啟 HazeFeatureFlags.isPlatformBackdropEnabled 後直接取樣已合成的視窗畫面，
 *    WebView 像素零額外擷取成本。
 *  - Android 12：Haze 自動降級為 RenderEffect 模糊＋色調（無折射 shader），再加上本模組的陰影。
 *  - Android 11 以下：Haze 自動改畫半透明純色底（scrim），本模組再加陰影。
 *
 * WebView 擷取驗證（Android 17 / API 37.0 模擬器，非原生 backdrop 路徑）：
 *  Haze 以 GraphicsLayer 錄製 hazeSource 內容時，WebView 的畫面能被正確錄入並模糊、折射，
 *  所以不需要「定時截圖＋縮小模糊」的備案。
 *
 * 使用限制（實測）：原生 backdrop 路徑下，玻璃不能放在常駐的祖先 graphicsLayer 裡，否則取樣不到底下的網頁、
 * 變成完全透明。位移請用 Modifier.offset；淡出／縮放只在動畫進行中才加 layer；
 * 同一節點上（玻璃自己的 modifier 鏈）加 graphicsLayer 則沒有問題（例如按壓膨脹）。
 *
 * 效能：模糊與 shader 只作用在每個玻璃元件自身的小面積；色調動畫只改 tint（uniform），
 * 不重建 RenderEffect。
 */

/** 玻璃色調與在其上保持可讀的圖示／文字顏色。 */
@Immutable
data class Tone(val tint: Color, val content: Color) {
    companion object {
        /** 背景亮：淺色玻璃＋深色圖示 */
        val Light = Tone(Color.White.copy(alpha = GlassTokens.TintLight), Palette.Ink)

        /** 背景暗：深色玻璃＋淺色圖示 */
        val Dark = Tone(Color(0xFF3A3A3C).copy(alpha = GlassTokens.TintDark), Color.White)

        /** 輸入中：較不透明，確保輸入文字在任何網頁上都清楚 */
        val InputLight = Tone(Color.White.copy(alpha = 0.72f), Palette.Ink)
        val InputDark = Tone(Color(0xFF2C2C2E).copy(alpha = 0.78f), Color.White)

        /** 無痕模式：更深的玻璃 */
        val Incognito = Tone(Color.Black.copy(alpha = 0.45f), Color.White)
    }
}

private val Optics = GlassOptics(
    refractionStrength = GlassTokens.Refraction,
    refractionHeightFraction = GlassTokens.RefractionEdge,
    blurRadius = OpticalSizeValue.Fixed(GlassTokens.BlurRadius),
)

/**
 * 系統能否直接取樣已合成的視窗畫面（Android 17 QPR2 = API 37.2 起）。
 * 不能時 Haze 會把來源內容錄進離屏圖層再模糊；WebView 用 Vulkan 繪圖時不支援被畫進離屏圖層，
 * Chromium 內部檢查失敗會直接中止整個 App（小米 15 Ultra／Android 17.0 實測：開網頁即閃退）。
 * 所以沒有原生 backdrop 時，網頁不當玻璃的取樣來源（見 BrowserScreen），網頁上的玻璃改用較不透明的磨砂底。
 */
val PlatformBackdrop: Boolean = Build.VERSION.SDK_INT >= 36 && Build.VERSION.SDK_INT_FULL >= 3_700_002

/**
 * 首頁（沒有原生 backdrop 的手機）為 true：每幀把整頁錄成圖層再模糊太耗 GPU（開 App 時淡入動畫每幀約 90ms），
 * 改用 Material 3 表面（見 materialSurface）。
 */
val LocalCheapGlass = staticCompositionLocalOf { false }

/**
 * Material 3 表面色（Android 12+ 依桌布動態配色，之前的版本用 M3 預設色）：淺色／深色依玻璃色調判斷，
 * 無痕模式（深色調）一律用深色表面
 */
@Composable
private fun materialSurface(tint: Color): Color {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dark = tint.red + tint.green + tint.blue < 1.5f
    return remember(dark) {
        if (Build.VERSION.SDK_INT >= 31) {
            (if (dark) androidx.compose.material3.dynamicDarkColorScheme(context) else androidx.compose.material3.dynamicLightColorScheme(context)).surfaceContainerHigh
        } else if (dark) Color(0xFF2B2930) else Color(0xFFECE6F0)
    }
}

/** 在系統模糊視窗裡（或等待視窗建立時的主畫面佔位）：模糊由系統合成器做，玻璃只畫半透明色調＋高光邊 */
val LocalInBlurWindow = staticCompositionLocalOf { false }

/** 系統模糊小視窗內為 false：陰影會被視窗邊界切成方形硬邊，視窗內不畫陰影 */
val LocalGlassShadow = staticCompositionLocalOf { true }

/** 玻璃底下是網頁、但沒有原生 backdrop 可模糊它：提高色調不透明度，看起來仍是磨砂而不是透明 */
val LocalOpaqueGlass = staticCompositionLocalOf { false }

/** 玻璃樣式：Haze glass 樣式（Android 13+）＋降級路徑需要的色調。 */
@Immutable
class LiquidGlassStyle(val glass: GlassStyle, val blur: HazeBlurStyle, val frost: HazeBlurStyle, val tint: Color)

/** 依形狀與色調建立玻璃樣式。樣式以 identity 比較，所以必須 remember。 */
@Composable
fun rememberGlassStyle(shape: RoundedCornerShape, tint: Color): LiquidGlassStyle = remember(shape, tint) {
    LiquidGlassStyle(glassStyle(shape, tint), blurStyle(tint), frostStyle, tint)
}

/** Android 12 以下：純模糊＋色調；Android 11 以下 Haze 改畫 85% 不透明底（fallbackColorEffect）。 */
private fun blurStyle(tint: Color) = HazeBlurStyle {
    blurRadius(GlassTokens.BlurRadius)
    colorEffects(listOf(HazeColorEffect.tint(tint.copy(alpha = (tint.alpha + 0.25f).coerceAtMost(1f)))))
    fallbackColorEffect(HazeColorEffect.tint(tint.compositeOverOpaque().copy(alpha = 0.85f)))
}

/** Android 13+ 毛玻璃底層：只做強模糊，色調交給上層玻璃（兩層都加色調會變成不透明白板） */
private val frostStyle = HazeBlurStyle {
    blurRadius(GlassTokens.BlurRadius)
}

/** 降級用：把半透明色調壓到不透明（白或黑底），確保 85% 純色底可讀。 */
private fun Color.compositeOverOpaque(): Color = if (luminance() > 0.5f) Color(0xFFF7F7F9) else Color(0xFF2C2C2E)

private fun glassStyle(shape: RoundedCornerShape, tint: Color): GlassStyle =
    GlassStyle.regular.then {
        shape(shape)
        // 色調不交給 Haze：玻璃容器（metaball）內的成員會忽略各自的 tint，改由 liquidGlass 自己疊霧面色
        contrast(GlassTokens.FrostContrast)
        optics(Optics)
        specularIntensity(GlassTokens.Specular)
        chromaMultiplier(GlassTokens.Saturation)
        // 按下：增強高光（膨脹由 pressInflate 處理，Haze 的互動縮放上限為 1）
        pressed { lightingIntensity(1f) }
    }

/** 按下時玻璃輕微膨脹（PressScale），放開以彈簧回彈；只動 graphicsLayer，不重新排版。 */
@Composable
fun Modifier.pressInflate(interaction: MutableInteractionSource?): Modifier {
    if (interaction == null) return this
    val pressed by interaction.collectIsPressedAsState()
    // 按下立即微縮＋輕觸覺回饋；放開以快速空間彈簧彈回（Material 3 Expressive）
    val scale = animateFloatAsState(if (pressed) Motion.PressScale else 1f, if (pressed) Motion.pressIn() else Motion.glass(), label = "press")
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.LaunchedEffect(pressed) { if (pressed) view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY) }
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

/** 給任意元件套上液態玻璃底：按壓膨脹＋玻璃本體＋大範圍低透明度的漂浮陰影。 */
@Composable
fun Modifier.liquidGlass(
    haze: HazeState,
    style: LiquidGlassStyle,
    shape: RoundedCornerShape,
    interaction: MutableInteractionSource? = null,
): Modifier {
    val base = if (!LocalGlassShadow.current) pressInflate(interaction) else pressInflate(interaction).dropShadow(
        shape,
        Shadow(
            radius = GlassTokens.ShadowRadius,
            color = Color.Black.copy(alpha = GlassTokens.ShadowAlpha),
            offset = DpOffset(0.dp, GlassTokens.ShadowOffsetY),
        ),
    )
    // 沒有原生 backdrop 時的輕量磨砂：92% 色調＋高光邊框，不跑 Haze 模糊（網頁上不能模糊 WebView；
    // 輸入時玻璃隨鍵盤移動，每幀重新模糊整個首頁會讓 GPU 掉幀）
    if (LocalInBlurWindow.current) {
        return base.clip(shape).background(style.tint).border(0.6.dp, RimBrush, shape)
    }
    // 不能（或不值得）即時模糊時：改用 Android 官方的 Material 3 表面，桌布配色（Material You）的 surfaceContainerHigh 實心色塊＋柔和陰影
    if (LocalCheapGlass.current || LocalOpaqueGlass.current) {
        return base.clip(shape).background(materialSurface(style.tint))
    }
    // Android 13+：強模糊底層 → 液態玻璃 → 霧面色調（Haze 玻璃本身的模糊上限約 38px，只夠「透明玻璃」），
    // 再疊液態玻璃（折射＋高光，AGSL），合起來才是毛玻璃
    if (Build.VERSION.SDK_INT >= 33) {
        return base
            // Backdrop：取樣已合成的畫面。不可用 Sources，那會把 WebView 再畫一次到另一個 layer，WebView 不支援重複繪製，整頁會一幀空白一幀正常地閃爍
            // 強模糊底層用效能模式（較低解析度取樣）：模糊這麼重時看不出差別，捲動時 GPU 負擔小很多
            .clip(shape).hazeBlur(HazeInput.Backdrop(haze), style.frost, dev.chrisbanes.haze.HazePerformanceMode.Performance)
            .hazeGlass(input = HazeInput.Backdrop(haze), style = style.glass, interactionSource = interaction)
            .background(style.tint, shape)
    }
    // Android 12：模糊＋色調＋高光邊框；Android 11 以下：Haze 自動改為 85% 不透明底＋高光邊框
    return base.clip(shape)
        .hazeBlur(input = HazeInput.Sources(haze), style = style.blur)
        .border(0.8.dp, RimBrush, shape)
}

/** 上亮下暗的高光邊框（降級路徑用；Android 13+ 由 shader 繪製）。 */
private val RimBrush = Brush.verticalGradient(
    0f to Color.White.copy(alpha = 0.6f),
    0.45f to Color.White.copy(alpha = 0.08f),
    1f to Color.Black.copy(alpha = 0.12f),
)

/** 容器內相鄰的玻璃元件距離夠近時會像水滴一樣以 metaball 相連（smooth-min 形變）。 */
fun Modifier.liquidGlassGroup(): Modifier = hazeGlassContainer(GlassTokens.MergeSpacing)

/** 這裡的玻璃會不會放進系統模糊視窗（沒有原生 backdrop、系統開著跨視窗模糊、不在另一個模糊視窗裡） */
@Composable
fun usesBlurWindow(): Boolean =
    !PlatformBackdrop && !LocalInBlurWindow.current &&crossWindowBlurEnabled()

/** 最常用的形式：一塊玻璃，內含內容。 */
@Composable
fun LiquidGlass(
    haze: HazeState,
    tone: Tone,
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
    interaction: MutableInteractionSource? = null,
    contentAlignment: Alignment = Alignment.Center,
    /** false＝不開系統模糊視窗（例如數量很多的小按鈕） */
    systemBlur: Boolean = true,
    /** 掛在實際接收觸控的那份內容上（拖曳、巢狀捲動等）；用系統模糊視窗時觸控由視窗接收，掛在 modifier 上會收不到 */
    contentModifier: Modifier = Modifier,
    /** 垂直位移（面板滑入、拖曳）：系統模糊視窗不搬動，只在視窗內平移，每幀不必重新擺放視窗 */
    shiftY: (() -> Float)? = null,
    /** false＝大小只由 modifier 決定（例如面板）：主畫面佔位不再組合一份內容 */
    placeholderContent: Boolean = true,
    /** 系統模糊視窗背後變暗的程度（0～1，由系統合成）；呼叫端用 [usesBlurWindow] 判斷要不要自己畫遮罩 */
    dimBehind: (() -> Float)? = null,
    /** 點到系統模糊視窗外面（用 dimBehind 時主畫面收不到這次點擊） */
    onOutside: (() -> Unit)? = null,
    /** 玻璃整體的不透明度（系統模糊視窗時連背後的模糊一起淡；主畫面的玻璃用 graphicsLayer） */
    windowAlpha: (() -> Float)? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val style = rememberGlassStyle(shape, tone.tint)
    // 可點擊的玻璃不用 Material 漣漪：按壓回饋由玻璃本身的縮放呈現
    val source = interaction ?: if (onClick != null) remember { MutableInteractionSource() } else null
    val glass = @Composable { m: Modifier ->
        Box(
            m.liquidGlass(haze, style, shape, source)
                .then(contentModifier)
                .then(if (onClick != null) Modifier.clickable(source, indication = null, onClick = onClick) else Modifier),
            contentAlignment,
            content = content,
        )
    }
    // 沒有原生 backdrop 的手機：每塊玻璃一個系統模糊小視窗（HyperOS 控制中心同一套，系統合成器模糊底下畫面）
    val useWindow = systemBlur && usesBlurWindow()
    if (!useWindow) { glass(if (shiftY != null) modifier.offset { IntOffset(0, shiftY().roundToInt()) } else modifier); return }
    val density = LocalDensity.current
    val cornerPx = with(density) { shape.topStart.toPx(androidx.compose.ui.geometry.Size(100f, 100f), density) }.takeIf { shape != CircleShape }
    val place = remember { object { var origin = androidx.compose.ui.geometry.Offset.Zero; var size = androidx.compose.ui.unit.IntSize.Zero; var push: () -> Unit = {} } }
    var ready by remember { mutableStateOf(false) }
    // 主畫面佔位：同樣的內容排版決定大小；視窗畫出來後隱形（點擊由上面的視窗接收）。等待期間先畫色調版
    val track = modifier.onLayoutRectChanged(0, 0) { b ->
        place.origin = androidx.compose.ui.geometry.Offset(b.positionInWindow.x.toFloat(), b.positionInWindow.y.toFloat())
        place.size = androidx.compose.ui.unit.IntSize(b.width, b.height)
        place.push()
    }
    if (!placeholderContent) Box(track)
    else CompositionLocalProvider(LocalInBlurWindow provides true) {
        glass(track.then(if (ready) Modifier.graphicsLayer { alpha = 0f } else Modifier))
    }
    BlurBoxWindow({ place.origin }, { place.size }, cornerPx, { place.push = it }, { ready = true }, shiftY, dimBehind, onOutside, windowAlpha) {
        // 視窗外框已依佔位大小排版（狀態），內容填滿即可；不能讀 place.size（非狀態，內容變短時會停在舊尺寸）
        glass(Modifier.fillMaxSize())
    }
}

/**
 * 取樣視窗某區域的平均亮度（0～1），供自適應色調使用。
 * PixelCopy 在 RenderThread 完成，主執行緒只讀 24×4 像素，成本可忽略。
 */
suspend fun sampleLuminance(activity: Activity, rect: android.graphics.Rect): Float? {
    if (rect.width() <= 0 || rect.height() <= 0) return null
    val bmp = Bitmap.createBitmap(24, 4, Bitmap.Config.ARGB_8888)
    // 視窗沒有畫面時（App 進背景、螢幕關閉、多工切換）PixelCopy 會直接丟例外：當作取樣失敗，不能讓 App 閃退
    val ok = suspendCancellableCoroutine { c ->
        runCatching {
            PixelCopy.request(activity.window, rect, bmp, { c.resume(it == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
        }.onFailure { c.resume(false) }
    }
    if (!ok) return null
    var sum = 0f
    for (x in 0 until 24) for (y in 0 until 4) {
        val p = bmp.getPixel(x, y)
        sum += 0.2126f * (p shr 16 and 0xFF) + 0.7152f * (p shr 8 and 0xFF) + 0.0722f * (p and 0xFF)
    }
    bmp.recycle()
    return sum / (96 * 255f)
}

/**
 * 取樣視窗某一條的主要顏色（狀態列與網頁融為一體用）：取 48×2 像素，量化後找出現最多的顏色，
 * 標題列上的 logo、文字不會把顏色弄髒（平均色會）。失敗回傳 null。
 */
suspend fun sampleDominantColor(activity: Activity, rect: android.graphics.Rect): Color? {
    if (rect.width() <= 0 || rect.height() <= 0) return null
    val bmp = Bitmap.createBitmap(48, 2, Bitmap.Config.ARGB_8888)
    val ok = suspendCancellableCoroutine { c ->
        runCatching {
            PixelCopy.request(activity.window, rect, bmp, { c.resume(it == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
        }.onFailure { c.resume(false) }
    }
    if (!ok) return null
    val counts = HashMap<Int, Int>()
    for (x in 0 until 48) for (y in 0 until 2) {
        val key = bmp.getPixel(x, y) and 0xF8F8F8 // 每色 5 bit，相近的顏色算同一種
        counts[key] = (counts[key] ?: 0) + 1
    }
    val best = counts.maxByOrNull { it.value }?.key ?: return null
    // 用屬於最多那一種的像素平均回原色（量化會讓顏色偏暗）
    var r = 0; var g = 0; var b = 0; var n = 0
    for (x in 0 until 48) for (y in 0 until 2) {
        val p = bmp.getPixel(x, y)
        if (p and 0xF8F8F8 == best) { r += p shr 16 and 0xFF; g += p shr 8 and 0xFF; b += p and 0xFF; n++ }
    }
    bmp.recycle()
    return Color(r / n, g / n, b / n)
}
