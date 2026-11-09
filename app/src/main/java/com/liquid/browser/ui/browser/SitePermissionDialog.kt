package com.liquid.browser.ui.browser

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
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
import com.liquid.browser.browser.FileSource
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
 *  位置另有「精確位置／大概位置」（大概位置見 geoApproxScript）
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
    var approx by remember(ask) { mutableStateOf(false) }
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
            // 位置：精確／大概（同 Android 12 起的系統定位對話框）
            if (SiteKind.Location in ask.kinds) {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(false to ("my_location" to "精確位置"), true to ("near_me" to "大概位置")).forEach { (ap, v) ->
                        val on = approx == ap
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                                .background(if (on) accent.copy(alpha = 0.14f) else content.copy(alpha = 0.06f))
                                .clickable { approx = ap }.padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Sym(v.first, if (on) accent else Palette.Gray, 26.dp)
                            Text(t(v.second), Modifier.padding(top = 6.dp, start = 6.dp, end = 6.dp), color = if (on) accent else content, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
            }
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
                        .clickable { vm.answerSitePermission(true, lifetime, approx) },
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

/**
 * 網頁上傳圖片／影片的來源選單（同 iOS Safari）：照片圖庫／拍照／錄影／選擇檔案，下面另一塊「取消」。
 * 網頁加了 capture 也一樣先問，使用者可以拍新的或用相簿裡的。
 */
@Composable
fun FileSourceSheet(vm: BrowserViewModel) {
    val ask = vm.fileChooser?.takeIf { it.source == null } ?: return
    val dark = isSystemInDarkTheme()
    val content = if (dark) Color.White else Palette.Ink
    val card = if (dark) Palette.CardDark else Palette.CardLight
    val accent = accent
    val appear = remember(ask) { Animatable(if (Motion.reduced) 1f else 0f) }
    LaunchedEffect(ask) { appear.animateTo(1f, Motion.glass()) }
    val options = buildList {
        add(Triple(FileSource.Library, "photo_library", "照片圖庫"))
        if (ask.wantsImage) add(Triple(FileSource.Photo, "photo_camera", "拍照"))
        if (ask.wantsVideo) add(Triple(FileSource.Video, "videocam", "錄影"))
        add(Triple(FileSource.Files, "folder", "選擇檔案"))
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { vm.pickFileSource(null) },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) { vm.pickFileSource(null) },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 10.dp, vertical = 10.dp)
                    .graphicsLayer { alpha = appear.value; translationY = (1f - appear.value) * 80.dp.toPx() },
            ) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Dimens.CardRadius)).background(card)) {
                    options.forEachIndexed { i, (src, icon, label) ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(0.5.dp).background(content.copy(alpha = 0.12f)))
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.pickFileSource(src) }.padding(horizontal = 20.dp, vertical = 15.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(t(label), Modifier.weight(1f), color = content, fontSize = 17.sp)
                            Sym(icon, accent, 24.dp)
                        }
                    }
                }
                Box(
                    Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(Dimens.CardRadius)).background(card)
                        .clickable { vm.pickFileSource(null) }.padding(vertical = 15.dp),
                    Alignment.Center,
                ) { Text(t("取消"), color = accent, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}
