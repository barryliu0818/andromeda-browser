package com.liquid.browser.core

import android.app.Activity
import android.os.Build
import android.view.Display
import android.view.Surface

/**
 * 選出與目前解析度相同、刷新率最高的顯示模式（通常為 120Hz）並套用到視窗。
 * 不再強制整個視窗一直以高幀率繪製：驍龍 8 Elite 等機種的 LTPO 螢幕靜止時可降到 1～10Hz 省電，
 * 觸控、捲動與動畫時由系統（Android 15+ 觸控加速＋各動畫的幀率投票）自動拉回 120Hz。
 */
fun Activity.requestHighestRefreshRate() {
    @Suppress("DEPRECATION")
    val display: Display = if (Build.VERSION.SDK_INT >= 30) display ?: return else windowManager.defaultDisplay
    val current = display.mode
    val best = display.supportedModes
        .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
        .maxByOrNull { it.refreshRate } ?: return
    window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
    if (Build.VERSION.SDK_INT >= 35) window.frameRateBoostOnTouchEnabled = true
}

/** 影片等獨立 Surface（SurfaceView）用：Android 11+ 告知系統此 Surface 的目標幀率。 */
fun Surface.preferFrameRate(fps: Float) {
    if (Build.VERSION.SDK_INT >= 30 && isValid) {
        setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
    }
}

/** 目前顯示器支援的最高刷新率，給播放器 Surface 使用。 */
fun Activity.maxRefreshRate(): Float {
    @Suppress("DEPRECATION")
    val d = if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay
    return d?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 60f
}
