package com.liquid.browser.ui.extensions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquid.browser.browser.BrowserViewModel
import com.liquid.browser.browser.Extensions
import com.liquid.browser.core.Palette
import com.liquid.browser.core.t
import com.liquid.browser.ui.GroupFooter
import com.liquid.browser.ui.GroupHeader
import com.liquid.browser.ui.GroupRow
import com.liquid.browser.ui.IconTile
import com.liquid.browser.ui.IosSwitch
import com.liquid.browser.ui.PageTitle
import com.liquid.browser.ui.SymButton
import com.liquid.browser.ui.accent
import com.liquid.browser.ui.glass.Tone
import com.liquid.browser.ui.groupItem

/** 擴展工具管理（頁面選單 ≡ 裡）：已安裝的擴充功能（開關、移除）＋從網址安裝 */
@Composable
fun ExtensionsPanel(vm: BrowserViewModel, tone: Tone) {
    val accent = accent
    val exts = Extensions.list.toList()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 48.dp)) {
        item { PageTitle(t("擴展工具管理"), tone, Modifier.padding(start = 4.dp)) }
        item { GroupHeader(if (exts.isEmpty()) t("還沒有安裝擴充功能") else t("已安裝"), tone) }
        itemsIndexed(exts, key = { _, e -> "ext:" + e.id }) { i, e ->
            val kind = if (e.userScript) t("使用者腳本") else if (e.manifest.has("browser_specific_settings") || e.manifest.has("applications")) "Firefox" else "Chrome"
            GroupRow(
                i, exts.size, tone, e.name.ifBlank { e.id },
                subtitle = listOfNotNull(kind, e.version.takeIf { it.isNotBlank() }?.let { "v$it" }, e.description.takeIf { it.isNotBlank() }).joinToString("・"),
                leading = { IconTile(if (e.userScript) "code" else "extension", if (e.userScript) Color(0xFF34C759) else Color(0xFFFF9500)) },
                trailing = {
                    SymButton("delete", Palette.Gray, size = 20.dp, label = t("移除")) {
                        Extensions.remove(e)
                        vm.showToast(t("已移除「%1\$s」", e.name))
                    }
                    IosSwitch(e.enabled, tone, Modifier.padding(start = 4.dp)) { on -> Extensions.setEnabled(e, on) }
                },
                onClick = { Extensions.setEnabled(e, !e.enabled) },
            )
        }
        item(key = "ext-install") {
            GroupHeader(t("安裝"), tone)
            Column(Modifier.fillMaxWidth().groupItem(0, 1, tone).padding(horizontal = 16.dp, vertical = 6.dp)) {
                InstallField(tone, accent) { vm.installExtension(it) }
            }
            GroupFooter(t("支援 Firefox 附加元件（.xpi）、Chrome 線上應用程式商店（.crx）與使用者腳本（.user.js，Greasy Fork）。也可以直接開啟商店頁面安裝。只支援會修改網頁的擴充功能，不支援工具列按鈕與背景程式；無痕分頁不執行擴充功能"))
        }
    }
}

/** 擴充功能網址欄：貼上或輸入商店頁面／套件網址，按「安裝」或鍵盤的完成 */
@Composable
private fun InstallField(tone: Tone, accent: Color, onInstall: (String) -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    val go = { text.trim().takeIf { it.startsWith("http") }?.let { onInstall(it); text = "" } }
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(t("從網址安裝"), color = tone.content, fontSize = 15.sp)
        Row(
            Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(tone.content.copy(alpha = 0.06f)),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.text.BasicTextField(
                text, { text = it },
                Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 12.dp),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = tone.content, fontSize = 15.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(accent),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { go() }),
                decorationBox = { inner ->
                    if (text.isEmpty()) Text(t("商店頁面或 .xpi／.crx／.user.js 網址"), color = Palette.Gray, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                },
            )
            Text(
                if (text.isBlank()) t("貼上") else t("安裝"),
                Modifier.clip(CircleShape).clickable {
                    if (text.isBlank()) {
                        val clip = context.getSystemService(android.content.ClipboardManager::class.java)
                        text = clip?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
                    } else go()
                }.padding(horizontal = 14.dp, vertical = 10.dp),
                color = accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
