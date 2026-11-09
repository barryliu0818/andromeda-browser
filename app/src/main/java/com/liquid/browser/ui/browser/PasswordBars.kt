package com.liquid.browser.ui.browser

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquid.browser.browser.BrowserViewModel
import com.liquid.browser.core.Motion
import com.liquid.browser.core.Palette
import com.liquid.browser.core.t
import com.liquid.browser.ui.Sym
import com.liquid.browser.ui.SymButton
import com.liquid.browser.ui.accent
import com.liquid.browser.ui.glass.LiquidGlass
import com.liquid.browser.ui.glass.MenuItem
import com.liquid.browser.ui.glass.MenuSpec
import com.liquid.browser.ui.glass.MenuState
import com.liquid.browser.ui.glass.Tone
import dev.chrisbanes.haze.HazeState
import android.net.Uri

/**
 * 鍵盤上方的密碼列：點到登入表單的帳號／密碼欄時出現。
 *  - 有一組已存帳號：「🔑 使用 帳號 的密碼」，點一下過指紋驗證後填入
 *  - 有多組：「🔑 選擇已存的密碼（N）」，點一下跳出帳號選單
 *  - 註冊表單：「🔑 使用高強度密碼」
 * 只在鍵盤開著時顯示（帳號欄聚焦但鍵盤收起時不擋畫面）；從下方滑入、減速停住，退場較快。
 */
@Composable
fun PasswordBar(vm: BrowserViewModel, haze: HazeState, tone: Tone, menu: MenuState, modifier: Modifier = Modifier) {
    val activity = LocalActivity.current
    val keyboard = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val offer = vm.pwOffer?.takeIf { it.tab == vm.current && keyboard }
    var shown by remember { mutableStateOf(offer) }
    if (offer != null) shown = offer
    var bounds by remember { mutableStateOf(Rect.Zero) }
    AnimatedVisibility(
        offer != null, modifier,
        enter = fadeIn(Motion.glass()) + slideInVertically(Motion.glass()) { it / 2 },
        exit = fadeOut(Motion.exit()) + slideOutVertically(Motion.exit()) { it / 2 },
    ) {
        val o = shown ?: return@AnimatedVisibility
        val accent = accent
        LiquidGlass(haze, tone, CircleShape, Modifier.onGloballyPositioned { bounds = it.boundsInRoot() }, systemBlur = false) {
            Row(Modifier.widthIn(max = 420.dp).padding(start = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val label = when {
                    o.accounts.size == 1 -> t("使用 %1\$s 的密碼", o.accounts[0].user.ifBlank { Uri.parse(o.origin).host.orEmpty() })
                    o.accounts.size > 1 -> t("選擇已存的密碼（%1\$s）", o.accounts.size)
                    else -> t("使用高強度密碼")
                }
                Row(
                    Modifier.weight(1f, fill = false).clip(CircleShape).clickable {
                        when {
                            o.accounts.isEmpty() -> vm.useStrongPassword(o)
                            o.accounts.size == 1 -> activity?.let { vm.usePassword(it, o, o.accounts[0]) }
                            else -> menu.show(MenuSpec(bounds, o.accounts.map { c -> MenuItem(c.user.ifBlank { t("（沒有帳號）") }, "key") { activity?.let { vm.usePassword(it, o, c) } } }))
                        }
                    }.padding(horizontal = 10.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Sym("key", accent, 20.dp)
                    Text(label, Modifier.padding(start = 8.dp), color = tone.content, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                SymButton("close", Palette.Gray, size = 18.dp, label = t("關閉")) { vm.pwOffer = null }
            }
        }
    }
}

/** 登入後的「要儲存密碼嗎？」：儲存／不要；12 秒沒動作自動收起（無痕分頁不會出現） */
@Composable
fun SavePasswordBar(vm: BrowserViewModel, haze: HazeState, tone: Tone, modifier: Modifier = Modifier) {
    val save = vm.pwSave?.takeIf { it.tab == vm.current }
    var shown by remember { mutableStateOf(save) }
    if (save != null) shown = save
    AnimatedVisibility(
        save != null, modifier,
        enter = fadeIn(Motion.glass()) + slideInVertically(Motion.glass()) { it / 2 },
        exit = fadeOut(Motion.exit()) + slideOutVertically(Motion.exit()) { it / 2 },
    ) {
        val s = shown ?: return@AnimatedVisibility
        val accent = accent
        val host = Uri.parse(s.cred.origin).host?.removePrefix("www.").orEmpty()
        LiquidGlass(haze, tone, CircleShape, systemBlur = false) {
            Row(Modifier.widthIn(max = 440.dp).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Sym("key", accent, 20.dp)
                Text(
                    if (s.update) t("要更新 %1\$s 的密碼嗎？", host) else t("要儲存 %1\$s 的密碼嗎？", host),
                    Modifier.weight(1f, fill = false).padding(start = 8.dp, top = 12.dp, bottom = 12.dp),
                    color = tone.content, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    t("不要"),
                    Modifier.padding(start = 6.dp).clip(CircleShape).clickable { vm.pwSave = null }.padding(horizontal = 10.dp, vertical = 10.dp),
                    color = tone.content.copy(alpha = 0.7f), fontSize = 14.sp,
                )
                Text(
                    if (s.update) t("更新") else t("儲存"),
                    Modifier.clip(CircleShape).clickable { vm.savePassword(s) }.padding(horizontal = 12.dp, vertical = 10.dp),
                    color = accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
