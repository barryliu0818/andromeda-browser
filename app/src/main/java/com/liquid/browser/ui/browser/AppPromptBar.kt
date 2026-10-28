package com.liquid.browser.ui.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquid.browser.browser.AppPrompt
import com.liquid.browser.browser.BrowserViewModel
import com.liquid.browser.core.Motion
import com.liquid.browser.core.Palette
import com.liquid.browser.core.t
import com.liquid.browser.ui.SymButton
import com.liquid.browser.ui.glass.LiquidGlass
import com.liquid.browser.ui.glass.Tone
import dev.chrisbanes.haze.HazeState

/** 跳轉 App 的玻璃提示列：App 圖示＋說明＋「開啟」＋關閉；退場時保留最後一則內容，文字不會先消失 */
@Composable
fun AppPromptBar(vm: BrowserViewModel, haze: HazeState, tone: Tone, modifier: Modifier = Modifier) {
    val prompt = vm.appPrompt?.takeIf { it.tab == vm.current }
    var shown by remember { mutableStateOf<AppPrompt?>(null) }
    if (prompt != null) shown = prompt
    AnimatedVisibility(
        prompt != null,
        modifier,
        enter = fadeIn(Motion.glass()) + slideInVertically(Motion.glass()) { it / 2 },
        exit = fadeOut(Motion.exit()) + slideOutVertically(Motion.exit()) { it / 2 },
    ) {
        val p = shown ?: return@AnimatedVisibility
        val accent = if (tone.content.luminance() > 0.5f) Palette.AccentDark else Palette.AccentLight
        LiquidGlass(haze, tone, CircleShape) {
            Row(Modifier.widthIn(max = 420.dp).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                p.icon?.let { Image(it, null, Modifier.padding(end = 10.dp).size(24.dp).clip(CircleShape)) }
                Text(
                    if (p.ask) t("此網頁想開啟「%1\$s」", p.label) else t("在「%1\$s」中開啟", p.label),
                    Modifier.weight(1f, fill = false).padding(vertical = 12.dp),
                    color = tone.content, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    t("開啟"),
                    Modifier.padding(start = 8.dp).clip(CircleShape).clickable { vm.launchApp(p.intent) }.padding(horizontal = 12.dp, vertical = 10.dp),
                    color = accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                )
                SymButton("close", Palette.Gray, size = 18.dp) { vm.dismissAppPrompt() }
            }
        }
    }
}
