package com.liquid.browser.ui.browser

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquid.browser.browser.BrowserViewModel
import com.liquid.browser.browser.PermLifetime
import com.liquid.browser.browser.SiteKind
import com.liquid.browser.core.Dimens
import com.liquid.browser.core.Motion
import com.liquid.browser.core.Palette
import com.liquid.browser.core.t
import com.liquid.browser.ui.Sym
import com.liquid.browser.ui.accent

/**
 * 網站權限對話框，照 Brave（BravePermissionDialogModel）：
 *  「example.com 想要使用你的裝置位置」
 *  記住我的決定：○ 直到我關閉這個網站（預設）○ 24 小時 ○ 1 週 ○ 永遠
 *  [封鎖]  [允許]
 * 返回鍵或點外面＝沒有回答（累計 3 次自動封鎖 7 天）。一次只顯示一個，其他排隊。
 */
@Composable
fun SitePermissionDialog(vm: BrowserViewModel) {
    val ask = vm.sitePermission ?: return
    val dark = isSystemInDarkTheme()
    val content = if (dark) Color.White else Palette.Ink
    val accent = accent
    var lifetime by remember(ask) { mutableStateOf(PermLifetime.UntilClose) }
    val appear = remember(ask) { Animatable(if (Motion.reduced) 1f else 0f) }
    LaunchedEffect(ask) { appear.animateTo(1f, Motion.glass()) }
    // 無痕分頁的決定只放記憶體，不給時限選項（同 Brave：無痕不寫入設定）
    val private = ask.tab.incognito
    androidx.compose.ui.window.Dialog(onDismissRequest = { vm.answerSitePermission(null, lifetime) }) {
        Column(
            Modifier.fillMaxWidth().graphicsLayer { alpha = appear.value; val s = 0.94f + 0.06f * appear.value; scaleX = s; scaleY = s }
                .clip(RoundedCornerShape(Dimens.CardRadius)).background(if (dark) Palette.CardDark else Palette.CardLight).padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ask.kinds.forEach { Sym(it.icon, accent, 26.dp, Modifier.padding(end = 6.dp)) }
            }
            Text(
                t(message(ask.kinds), ask.host),
                Modifier.padding(top = 12.dp), color = content, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp,
            )
            if (!private) {
                Text(t("記住我的決定"), Modifier.padding(top = 16.dp, bottom = 4.dp), color = content.copy(alpha = 0.6f), fontSize = 13.sp)
                PermLifetime.entries.forEach { opt ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { lifetime = opt }.padding(vertical = 8.dp, horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Sym(if (opt == lifetime) "radio_button_checked" else "radio_button_unchecked", if (opt == lifetime) accent else Palette.Gray, 22.dp)
                        Text(t(opt.label), Modifier.padding(start = 10.dp), color = content, fontSize = 15.sp)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(14.dp)).background(content.copy(alpha = 0.08f))
                        .clickable { vm.answerSitePermission(false, lifetime) },
                    Alignment.Center,
                ) { Text(t("封鎖"), color = content, fontSize = 16.sp) }
                Box(
                    Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(14.dp)).background(accent)
                        .clickable { vm.answerSitePermission(true, lifetime) },
                    Alignment.Center,
                ) { Text(t("允許"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

/** Chromium 的提示文字（permissions_strings.grdp）：位置／相機／麥克風／相機和麥克風 */
private fun message(kinds: Set<SiteKind>): String = when {
    SiteKind.Camera in kinds && SiteKind.Mic in kinds -> "%1\$s 想要使用你的相機和麥克風"
    SiteKind.Camera in kinds -> "%1\$s 想要使用你的相機"
    SiteKind.Mic in kinds -> "%1\$s 想要使用你的麥克風"
    else -> "%1\$s 想要使用你的裝置位置"
}
