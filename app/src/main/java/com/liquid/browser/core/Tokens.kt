package com.liquid.browser.core

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 全 App 可調參數集中於此：動畫（彈簧、時長）、液態玻璃（模糊、折射、高光）、版面尺寸、色彩。
 * 調手感只改這個檔案。
 */
/**
 * 動畫規格：Material 3 Expressive 動態系統（Compose MotionScheme.expressive() 的彈簧參數）。
 *  - 空間（位移、大小、面板）：預設 0.8／380（微微回彈）、快速 0.6／800、慢速 0.8／200；
 *  - 效果（透明度、顏色）：預設 1.0／1600、快速 1.0／3800（不回彈）。
 * 系統「移除動畫」開啟時一律立即完成。調手感只改這裡。
 */
object Motion {
    const val ShortMs = 200
    const val MediumMs = 280
    const val LongMs = 350

    /** 自適應色調深淺切換時間 */
    const val ToneMs = 250

    /** 按下時的縮放（Material 3 Expressive：按下微縮、放開以快速空間彈簧彈回） */
    const val PressScale = 0.96f

    /** 系統「移除動畫」開啟時為 true：所有動畫改為立即完成（由 MainActivity 依系統設定更新） */
    @Volatile var reduced = false

    // iOS 原生動畫：位移、縮放、面板一律用「臨界阻尼彈簧」（SwiftUI .smooth＝bounce 0），不用固定時長的曲線：
    //  - 自然減速停住、不回彈（沒有「蹦一下」）
    //  - 動畫中途被打斷（再點一次、反方向捲動）時，從「目前的速度」接著轉向，不會急停再重來——iOS 順的主因
    // 以 iOS 的 response（約等於完成時間）換算：stiffness = (2π / response)²，dampingRatio = 1
    private fun <T> iosSpring(response: Float): FiniteAnimationSpec<T> =
        androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = (2f * Math.PI.toFloat() / response).let { it * it })
    // 透明度、顏色：iOS 的 easeInOut（0.42, 0, 0.58, 1），不用線性
    private val IosEaseInOut = androidx.compose.animation.core.CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    /** 一般 UI（response 0.4s） */
    fun <T> standard(): FiniteAnimationSpec<T> = if (reduced) snap() else iosSpring(0.4f)

    /** 面板、選單進場、放開後歸位（response 0.5s，SwiftUI .smooth） */
    fun <T> glass(): FiniteAnimationSpec<T> = if (reduced) snap() else iosSpring(0.5f)

    /** 面板、選單退場（response 0.35s，比進場快，iOS 關閉的節奏） */
    fun <T> exit(): FiniteAnimationSpec<T> = if (reduced) snap() else iosSpring(0.35f)

    /** 分頁總覽轉場（response 0.35s） */
    fun <T> overview(): FiniteAnimationSpec<T> = if (reduced) snap() else iosSpring(0.35f)

    /** 網址列收合／展開（response 0.4s） */
    fun <T> collapse(): FiniteAnimationSpec<T> = if (reduced) snap() else iosSpring(0.4f)

    /** 按下：要立即回饋（response 0.15s） */
    fun <T> pressIn(): FiniteAnimationSpec<T> = if (reduced) snap() else iosSpring(0.15f)

    /** 淡入淡出、顏色（iOS easeInOut） */
    fun <T> timed(ms: Int = MediumMs): FiniteAnimationSpec<T> = if (reduced) snap() else androidx.compose.animation.core.tween((ms * 1.25f).toInt(), easing = IosEaseInOut)
}

object GlassTokens {
    /** 毛玻璃模糊半徑：越大越霧，底下網頁越看不清 */
    val BlurRadius = 44.dp

    /** 玻璃色調不透明度（淺色／深色），越高越像磨砂、越不透明 */
    const val TintLight = 0.58f
    const val TintDark = 0.62f

    /** 毛玻璃對比（1 = 原樣，越低底下內容越霧） */
    const val FrostContrast = 0.75f

    /** 邊緣折射強度（0～1）；毛玻璃不需要強折射 */
    const val Refraction = 0.35f

    /** 折射作用範圍佔玻璃高度的比例 */
    const val RefractionEdge = 0.32f

    /** 上緣高光強度 */
    const val Specular = 0.6f

    /** 飽和度倍率，讓底下顏色透出 */
    const val Saturation = 1.3f

    val ShadowRadius = 24.dp
    val ShadowOffsetY = 6.dp
    const val ShadowAlpha = 0.12f

    /** 兩個玻璃元件間距小於 MergeSpacing/2 時以 metaball 方式相連 */
    val MergeSpacing = 14.dp

    /** 自適應色調：亮度高於 LightAbove 用淺色玻璃（深色圖示），低於 DarkBelow 用深色玻璃 */
    const val LightAbove = 0.62f
    const val DarkBelow = 0.45f
    const val SampleIntervalMs = 350L
}

object Dimens {
    val Control = 48.dp
    val ControlCollapsed = 32.dp
    val ControlGap = 8.dp
    val EdgeMargin = 12.dp

    /** 與系統手勢導覽列的最小間隙 */
    val NavGap = 8.dp

    val CollapsedPillMaxWidth = 200.dp
    val LandscapeMaxWidth = 600.dp

    val CardRadius = 20.dp
    /** 選單、面板圓角：Material 3 Expressive extra-large（28dp） */
    val MenuRadius = 28.dp
    val TileRadius = 16.dp
    val Tile = 60.dp
}

/**
 * 全 App 色票：跟著 Material You 動態配色（Android 12+ 取自桌布，之前的版本用 Material 3 預設色）。
 * 用 Compose 狀態保存，桌布或深淺色改變時（MainActivity 呼叫 apply），讀到它的畫面自動重組換色。
 */
/**
 * 全 App 色票：跟著 Material You 動態配色（Android 12+ 取自桌布，之前的版本用 Material 3 預設色）。
 * 用 Compose 狀態保存，桌布或深淺色改變時（MainActivity 呼叫 apply），讀到它的畫面自動重組換色。
 */
object Palette {
    private class Colors(
        val accentLight: Color, val accentDark: Color,
        val bgLight: Color, val bgDark: Color, val surface2Dark: Color,
        val cardLight: Color, val cardDark: Color,
        val grayLight: Color, val grayDark: Color, val ink: Color,
    )
    private var colors by mutableStateOf(from(lightColorScheme(), darkColorScheme()))
    private var dark by mutableStateOf(false)

    private fun from(l: ColorScheme, d: ColorScheme) = Colors(
        accentLight = l.primary, accentDark = d.primary,
        bgLight = l.background, bgDark = d.background, surface2Dark = d.surfaceContainerLow,
        cardLight = l.surfaceContainerLowest, cardDark = d.surfaceContainerHigh,
        grayLight = l.onSurfaceVariant, grayDark = d.onSurfaceVariant, ink = l.onSurface,
    )

    // 固定配色（小米 HyperOS 設定頁取色），不跟桌布變：灰底 #F3F3F8、白卡、藍 #3478F6、灰字 #999999
    private val HyperLight = lightColorScheme(
        primary = Color(0xFF3478F6), onPrimary = Color.White,
        primaryContainer = Color(0xFFE3EDFE), onPrimaryContainer = Color(0xFF0D4FD0),
        secondaryContainer = Color(0xFFE3EDFE), onSecondaryContainer = Color(0xFF0D4FD0),
        background = Color(0xFFF3F3F8), surface = Color(0xFFF3F3F8), onSurface = Color.Black, onBackground = Color.Black,
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White, surfaceContainer = Color.White,
        surfaceContainerHigh = Color.White, surfaceContainerHighest = Color(0xFFE5E5E9),
        onSurfaceVariant = Color(0xFF999999), outline = Color(0xFFD9D9DE), outlineVariant = Color(0xFFE5E5E9),
    )
    private val HyperDark = darkColorScheme(
        primary = Color(0xFF3D86F7), onPrimary = Color.White,
        primaryContainer = Color(0xFF1B3A6E), onPrimaryContainer = Color(0xFFD6E4FF),
        secondaryContainer = Color(0xFF1B3A6E), onSecondaryContainer = Color(0xFFD6E4FF),
        background = Color.Black, surface = Color.Black, onSurface = Color.White, onBackground = Color.White,
        surfaceContainerLowest = Color(0xFF1C1C1E), surfaceContainerLow = Color(0xFF1C1C1E), surfaceContainer = Color(0xFF242426),
        surfaceContainerHigh = Color(0xFF1C1C1E), surfaceContainerHighest = Color(0xFF2C2C2E),
        onSurfaceVariant = Color(0xFF8C8C8C), outline = Color(0xFF3A3A3C), outlineVariant = Color(0xFF2C2C2E),
    )

    @Suppress("UNUSED_PARAMETER") fun light(context: Context): ColorScheme = HyperLight
    @Suppress("UNUSED_PARAMETER") fun dark(context: Context): ColorScheme = HyperDark

    /** 依目前深淺色更新（Activity 建立與設定改變時呼叫） */
    fun apply(context: Context) {
        colors = from(light(context), dark(context))
        dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    val AccentLight: Color get() = colors.accentLight
    val AccentDark: Color get() = colors.accentDark
    val BgLight: Color get() = colors.bgLight
    val BgDark: Color get() = colors.bgDark
    val Surface2Dark: Color get() = colors.surface2Dark
    /** 分組清單卡片（淺色／深色） */
    val CardLight: Color get() = colors.cardLight
    val CardDark: Color get() = colors.cardDark
    /** 次要文字、圖示 */
    val Gray: Color get() = if (dark) colors.grayDark else colors.grayLight
    val Ink: Color get() = colors.ink
}
