package com.liquid.browser.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import coil3.size.Size
import coil3.transform.Transformation

/**
 * 網站圖示統一大小（同 Safari 常用網站的作法）：網站給的圖示留白差很多——
 * Google、維基百科的圖案只佔一半、GitHub 的黑圓幾乎佔滿、BBC 是一長條——排在一起大小不一。
 *
 *  - 滿版設計（四個角都是同一個不透明、不是白色的底色，例如 Hacker News 的橘色方塊）：照原樣鋪滿
 *  - 其他：裁掉透明與白色的留白，圖案等比縮放到格子的 [CONTENT] 比例，置中放在白底上
 */
object IconNormalize : Transformation() {
    private const val OUT = 192
    /** 圖案佔格子的比例（最長邊） */
    private const val CONTENT = 0.62f

    override val cacheKey: String = "IconNormalize-1"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val src = if (input.config == Bitmap.Config.HARDWARE) input.copy(Bitmap.Config.ARGB_8888, false) else input
        val w = src.width
        val h = src.height
        if (w < 8 || h < 8) return src
        if (fullBleed(src)) return src

        // 圖案範圍：不透明、而且不是接近白色的像素（白色當作背景，圖示本身的白底圓也一起裁掉）
        var left = w; var top = h; var right = -1; var bottom = -1
        val row = IntArray(w)
        for (y in 0 until h) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                if (isContent(row[x])) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        if (right < left || bottom < top) return src
        val cw = (right - left + 1).toFloat()
        val ch = (bottom - top + 1).toFloat()
        val scale = OUT * CONTENT / maxOf(cw, ch)
        val dw = cw * scale
        val dh = ch * scale
        val out = Bitmap.createBitmap(OUT, OUT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val dst = RectF((OUT - dw) / 2f, (OUT - dh) / 2f, (OUT + dw) / 2f, (OUT + dh) / 2f)
        canvas.drawBitmap(src, Rect(left, top, right + 1, bottom + 1), dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    private fun isContent(p: Int): Boolean {
        if (Color.alpha(p) < 48) return false
        return !(Color.red(p) > 235 && Color.green(p) > 235 && Color.blue(p) > 235)
    }

    /** 四個角都是同一個不透明、非白色的顏色：圖示本身就是滿版設計 */
    private fun fullBleed(b: Bitmap): Boolean {
        val i = maxOf(1, minOf(b.width, b.height) / 24)
        val corners = listOf(b.getPixel(i, i), b.getPixel(b.width - 1 - i, i), b.getPixel(i, b.height - 1 - i), b.getPixel(b.width - 1 - i, b.height - 1 - i))
        if (corners.any { !isContent(it) || Color.alpha(it) < 250 }) return false
        val c = corners[0]
        return corners.all { d ->
            kotlin.math.abs(Color.red(d) - Color.red(c)) < 24 && kotlin.math.abs(Color.green(d) - Color.green(c)) < 24 && kotlin.math.abs(Color.blue(d) - Color.blue(c)) < 24
        }
    }
}
