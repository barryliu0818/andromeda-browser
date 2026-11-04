package com.liquid.browser.baselineprofile

import android.content.Intent
import android.net.Uri
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Baseline Profile：把開 App、捲動網頁、網址列收合、分頁總覽、選單、設定面板會跑到的程式碼列出來，
 * 安裝時就預先編譯（不必等系統背景編譯，更新後頭幾天也不會因為還在 JIT 而卡）。
 * 實測：同一支手機剛安裝（JIT）掉幀 71%，完整預先編譯後 24%。
 *
 * 產生（在模擬器上跑就好，設定檔跟 CPU 架構無關）：
 *   ./gradlew :app:generatePersonalReleaseBaselineProfile
 * 座標用螢幕比例：精簡版面（預設）的底部膠囊，≡ 在左、分頁鈕在右
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = "com.liquid.browser.next", includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait(Intent(Intent.ACTION_VIEW, Uri.parse("https://en.wikipedia.org/wiki/Web_browser")).setPackage(packageName))
        Thread.sleep(4000)
        val w = device.displayWidth
        val h = device.displayHeight
        fun tap(x: Double, y: Double, wait: Long = 1500) { device.click((w * x).toInt(), (h * y).toInt()); Thread.sleep(wait) }
        // 捲動（網址列跟著收合／展開）
        repeat(3) { device.swipe(w / 2, h * 3 / 4, w / 2, h / 4, 12); Thread.sleep(600) }
        device.swipe(w / 2, h / 4, w / 2, h * 3 / 4, 12); Thread.sleep(800)
        // 分頁總覽：開、關
        tap(0.9, 0.938, 2000); device.pressBack(); Thread.sleep(1200)
        // 頁面選單：開、關；再開一次進設定面板，捲一下再關
        tap(0.24, 0.938); device.pressBack(); Thread.sleep(800)
        tap(0.24, 0.938); tap(0.2, 0.927, 2000)
        repeat(2) { device.swipe(w / 2, h * 3 / 4, w / 2, h / 4, 12); Thread.sleep(500) }
        device.pressBack(); Thread.sleep(1200)
        // 網址列左右滑：切換分頁（最後一個分頁往左滑會開新分頁）
        device.swipe((w * 0.62).toInt(), (h * 0.938).toInt(), (w * 0.2).toInt(), (h * 0.938).toInt(), 15); Thread.sleep(1500)
        device.swipe((w * 0.2).toInt(), (h * 0.938).toInt(), (w * 0.62).toInt(), (h * 0.938).toInt(), 15); Thread.sleep(1500)
    }
}
