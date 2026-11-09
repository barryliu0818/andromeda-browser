package com.liquid.browser.ui.browser

import com.liquid.browser.core.t

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquid.browser.browser.BrowserViewModel
import com.liquid.browser.browser.Tab
import com.liquid.browser.core.Dimens
import com.liquid.browser.core.Palette
import com.liquid.browser.ui.Sym
import com.liquid.browser.browser.baseDomain
import com.liquid.browser.ui.IconTile
import com.liquid.browser.ui.GroupToggle
import com.liquid.browser.ui.GroupRow
import com.liquid.browser.ui.GroupHeader
import com.liquid.browser.ui.GroupMenuRow
import com.liquid.browser.ui.PageTitle
import com.liquid.browser.ui.SymButton
import com.liquid.browser.ui.accent
import com.liquid.browser.ui.glass.LiquidGlass
import com.liquid.browser.ui.glass.MenuDivider
import com.liquid.browser.ui.glass.MenuItem
import com.liquid.browser.ui.glass.MenuSpec
import com.liquid.browser.ui.glass.MenuState
import com.liquid.browser.ui.glass.Tone
import dev.chrisbanes.haze.HazeState

/** 文字大小的級距（%）。 */
private val TextSteps = listOf(50, 75, 85, 100, 115, 125, 150, 175, 200, 250, 300)

/** 頁面選單需要由 BrowserScreen 處理的動作。 */
class PageMenuActions(
    val onFind: () -> Unit,
    val onLibrary: () -> Unit,
    val onDownloads: () -> Unit,
    val onSiteSettings: () -> Unit,
    val onSettings: () -> Unit,
    /** 擴展工具管理：已安裝的擴充功能、從網址安裝 */
    val onExtensions: () -> Unit,
)

/**
 * 頁面選單（點網址列左側 …）：只放「這一頁」的動作——頂部四個圓形快捷鈕（分享、加入書籤、閱讀列表、閱讀器）、
 * 文字大小「小 A ─ 百分比 ─ 大 A」、尋找／桌面版／網站設定／隱藏控制項。
 * 翻譯在網址列的翻譯鈕；書籤與歷史、下載、設定這些 App 層級的功能在分頁總覽的「資料庫」鈕與起始頁的 … 裡。
 * 起始頁沒有網頁：… 直接是資料庫選單。
 */
fun pageMenu(anchor: Rect, tab: Tab, vm: BrowserViewModel, tone: Tone, context: Context, actions: PageMenuActions) =
    if (tab.isBlank) libraryMenu(anchor, actions, anchorIcon = "menu")
    else MenuSpec(
        anchor = anchor,
        anchorIcon = "menu",
        width = 290,
        header = { m -> PageMenuHeader(tab, vm, tone, m, actions) },
        items = listOf(
            MenuItem(t("在此頁面上尋找"), "search") { actions.onFind() },
            if (tab.desktop) MenuItem(t("要求行動版網站"), "smartphone") { tab.setDesktopMode(false) }
            else MenuItem(t("要求桌面版網站"), "computer") { tab.setDesktopMode(true) },
            MenuItem(t("網站設定"), "tune", push = true) { actions.onSiteSettings() },
            MenuItem(t("隱藏控制項"), "unfold_less") {
                vm.controlsHidden = true
                val top = vm.settings.value.barLayout == com.liquid.browser.data.BarLayout.Top ||
                    context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                vm.showToast(if (top) t("點擊畫面頂部可叫回控制項") else t("點擊畫面底部可叫回控制項"))
            },
            MenuItem(t("擴展工具管理"), "extension", push = true) { actions.onExtensions() },
            // 設定要隨時找得到：不只在分頁總覽的資料庫鈕裡
            MenuItem(t("設定"), "settings", push = true) { actions.onSettings() },
        ),
    )

/** 資料庫選單（App 層級）：書籤與歷史紀錄、下載項目、擴展工具管理、設定（問題由 BugLog 自動記錄，不另設回報鈕） */
fun libraryMenu(anchor: Rect, actions: PageMenuActions, anchorIcon: String? = null) = MenuSpec(
    anchor = anchor,
    anchorIcon = anchorIcon,
    items = listOf(
        MenuItem(t("書籤與歷史紀錄"), "book", push = true) { actions.onLibrary() },
        MenuItem(t("下載項目"), "download", push = true) { actions.onDownloads() },
        MenuItem(t("擴展工具管理"), "extension", push = true) { actions.onExtensions() },
        MenuItem(t("設定"), "settings", push = true) { actions.onSettings() },
    ),
)

/** 網址列的翻譯鈕（YouTube）：翻譯網頁與翻譯字幕兩項 */
fun translateMenu(anchor: Rect, tab: Tab, vm: BrowserViewModel) = MenuSpec(
    anchor = anchor,
    anchorIcon = "translate",
    items = listOf(
        MenuItem(if (tab.translated) t("顯示原文") else t("翻譯網頁"), "translate") { vm.toggleTranslate(tab) },
        MenuItem(if (tab.ytSubs) t("關閉字幕翻譯") else t("翻譯字幕"), "subtitles") { vm.toggleYouTubeSubs(tab) },
    ),
)

@Composable
private fun PageMenuHeader(tab: Tab, vm: BrowserViewModel, tone: Tone, menu: MenuState, actions: PageMenuActions) {
    val context = LocalContext.current
    val dismiss = menu::dismiss
    val bookmarked by remember(tab.url) { vm.isBookmarked(tab.url) }.collectAsState(false)
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), Arrangement.SpaceBetween) {
        // 分享、書籤：在選單內推入下一層
        QuickButton("ios_share", t("分享"), tone) {
            menu.enter(MenuItem(t("分享"), sub = listOf(
                MenuItem(t("拷貝連結"), "content_copy") {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText(tab.title, tab.url))
                },
                MenuItem(t("拷貝標題與連結"), "link") {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText(tab.title, "${tab.title}\n${tab.url}"))
                },
                MenuItem(t("分享到其他 App…"), "ios_share") { share(context, tab.url, tab.title) },
            )))
        }
        QuickButton(if (bookmarked) "bookmark_added" else "bookmark_add", if (bookmarked) t("已加入") else t("書籤"), tone, active = bookmarked) {
            menu.enter(MenuItem(t("書籤"), sub = listOf(
                if (bookmarked) MenuItem(t("移除書籤"), "bookmark_remove", destructive = true) { vm.toggleBookmark(tab) }
                else MenuItem(t("加入書籤"), "bookmark_add") { vm.toggleBookmark(tab) },
                MenuItem(t("加入閱讀列表"), "eyeglasses") { vm.addToReadingList(tab) },
                MenuItem(t("書籤與歷史紀錄"), "book", push = true) { actions.onLibrary() },
            )))
        }
        QuickButton("eyeglasses", t("閱讀列表"), tone) {
            dismiss()
            vm.addToReadingList(tab)
        }
        QuickButton("chrome_reader_mode", t("閱讀器"), tone, active = tab.readerOn) {
            dismiss()
            tab.toggleReader(vm.readerScript)
        }
    }
    // 文字大小：小 A ─ 百分比 ─ 大 A
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).height(44.dp).clip(CircleShape)
            .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHighest),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val i = TextSteps.indexOfFirst { it >= tab.textZoom }.coerceAtLeast(0)
        Box(Modifier.weight(1f).fillMaxSize().clickable(enabled = i > 0) { tab.setTextSize(TextSteps[i - 1]) }, Alignment.Center) {
            Text("A", color = tone.content, fontSize = 14.sp)
        }
        Text("${tab.textZoom}%", color = tone.content, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Box(Modifier.weight(1f).fillMaxSize().clickable(enabled = i < TextSteps.lastIndex) { tab.setTextSize(TextSteps[i + 1]) }, Alignment.Center) {
            Text("A", color = tone.content, fontSize = 22.sp)
        }
    }
    MenuDivider(tone)
}

/** 選單頂部的圓形快捷鈕（非玻璃：選單本身已是玻璃，避免玻璃疊在動畫 layer 裡）。 */
@Composable
private fun QuickButton(icon: String, label: String, tone: Tone, active: Boolean = false, onClick: () -> Unit) {
    val accent = accent
    Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(if (active) androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer else androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHighest),
            Alignment.Center,
        ) { Sym(icon, if (active) accent else tone.content, 22.dp) }
        Text(label, Modifier.padding(top = 4.dp), color = tone.content, fontSize = 11.sp, maxLines = 1)
    }
}

/** 系統分享。 */
fun share(context: Context, url: String, title: String = "") {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url).putExtra(Intent.EXTRA_SUBJECT, title)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** 在頁面上尋找：取代底部控制列的玻璃尋找列（輸入、第 n/共 m 筆、上一個／下一個、完成）。 */
@Composable
fun FindBar(tab: Tab, haze: HazeState, tone: Tone, onDone: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val fr = remember { FocusRequester() }
    FocusAndShowKeyboard(fr)
    val accent = accent
    Row(Modifier.fillMaxWidth().height(Dimens.Control), horizontalArrangement = Arrangement.spacedBy(Dimens.ControlGap), verticalAlignment = Alignment.CenterVertically) {
        LiquidGlass(haze, tone, CircleShape, Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Sym("search", tone.content.copy(alpha = 0.6f), 20.dp)
                Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    if (q.isEmpty()) Text(t("在頁面上尋找"), color = tone.content.copy(alpha = 0.5f), fontSize = 16.sp)
                    BasicTextField(
                        q, { q = it; tab.find(it) },
                        Modifier.focusRequester(fr),
                        singleLine = true,
                        textStyle = TextStyle(color = tone.content, fontSize = 16.sp),
                        cursorBrush = SolidColor(accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { tab.findNext(true) }),
                    )
                }
                if (q.isNotEmpty()) Text(
                    if (tab.findMatches == 0) t("無結果") else "${tab.findIndex}/${tab.findMatches}",
                    color = tone.content.copy(alpha = 0.6f), fontSize = 14.sp,
                )
                SymButton("keyboard_arrow_up", tone.content, enabled = tab.findMatches > 0) { tab.findNext(false) }
                SymButton("keyboard_arrow_down", tone.content, enabled = tab.findMatches > 0) { tab.findNext(true) }
            }
        }
        LiquidGlass(haze, tone, CircleShape, Modifier.height(Dimens.Control), onClick = {
            tab.clearFind()
            onDone()
        }) {
            Text(t("完成"), Modifier.padding(horizontal = 18.dp), color = tone.content, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** 網站設定面板（分組清單）：連線安全、JavaScript、桌面版、文字大小、清除此網站 Cookie。 */
@Composable
fun SiteSettings(tab: Tab, vm: BrowserViewModel, tone: Tone) {
    val red = Color(0xFFFF3B30)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 40.dp)) {
        PageTitle(tab.domain, tone, Modifier.padding(start = 4.dp))
        SecurityCheck(tab, vm, tone)
        GroupHeader(t("廣告與追蹤"), tone)
        val settings by vm.settings.collectAsState()
        val siteOn = settings.adBlock && baseDomain(tab.pageHost) !in settings.adAllow
        GroupToggle(
            0, 1, tone, t("在此網站阻擋廣告"), siteOn,
            subtitle = if (!settings.adBlock) t("總開關已關閉（設定 → 隱私與安全）") else t("此頁已阻擋 %1\$s 個廣告與追蹤請求", tab.blockedCount),
            leading = { IconTile("block", Color(0xFFFF3B30)) },
        ) { on -> if (settings.adBlock) { vm.setAdBlockForSite(tab.pageHost, on); tab.webView.reload() } }
        GroupHeader(t("此網站"), tone)
        GroupToggle(0, 3, tone, "JavaScript", tab.javaScript, leading = { IconTile("code", Color(0xFF5856D6)) }) { tab.setJavaScriptEnabled(it) }
        GroupToggle(1, 3, tone, t("要求桌面版網站"), tab.desktop, leading = { IconTile("desktop_windows", Color(0xFF30B0C7)) }) { tab.setDesktopMode(it) }
        GroupRow(2, 3, tone, t("文字大小"), leading = { IconTile("format_size", Color(0xFF007AFF)) }, trailing = {
            SymButton("remove", tone.content) { tab.setTextSize(TextSteps.lastOrNull { it < tab.textZoom } ?: tab.textZoom) }
            Text("${tab.textZoom}%", color = tone.content, fontSize = 15.sp, fontWeight = FontWeight.Medium, style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"))
            SymButton("add", tone.content) { tab.setTextSize(TextSteps.firstOrNull { it > tab.textZoom } ?: tab.textZoom) }
        })
        SitePermissionRows(tab, tone)
        GroupHeader(t("隱私"), tone)
        GroupRow(0, 1, tone, t("清除此網站的 Cookie"), leading = { IconTile("cookie", red) }, destructive = true, onClick = { vm.clearSiteCookies(tab) })
    }
}

/**
 * 網站安全性檢查：連線加密、憑證（簽發者、到期日）、Google 安全瀏覽、網址可疑特徵、已阻擋的追蹤器；
 * 綜合成「安全／注意／有風險」三級，以盾牌顏色呈現。
 */
@Composable
private fun SecurityCheck(tab: Tab, vm: BrowserViewModel, tone: Tone) {
    val host = tab.pageHost
    val cert = tab.webView.certificate
    val until = cert?.validNotAfterDate
    val daysLeft = until?.let { ((it.time - System.currentTimeMillis()) / 86_400_000L).toInt() }
    val safeBrowsing = androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.SAFE_BROWSING_ENABLE) &&
        androidx.webkit.WebSettingsCompat.getSafeBrowsingEnabled(tab.webView.settings)
    val isIp = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host) || host.startsWith("[")
    val punycode = host.split('.').any { it.startsWith("xn--") }
    val riskyTld = host.substringAfterLast('.') in setOf("zip", "mov", "xyz", "top", "click", "loan", "work", "gq", "tk", "ml", "cf", "ga", "country", "kim", "cam")

    // (圖示, 顏色, 標題, 說明, 嚴重度 0 正常／1 注意／2 風險)
    val green = Color(0xFF34C759); val orange = Color(0xFFFF9500); val red = Color(0xFFFF3B30)
    val checks = buildList {
        add(if (tab.insecure) Triple("lock_open", red, t("連線未加密")) to (t("此網站使用 HTTP，請勿輸入密碼或個人資料") to 2)
            else Triple("lock", green, t("連線已加密")) to (t("使用 HTTPS 安全連線") to 0))
        if (cert != null) {
            val issuer = cert.issuedBy?.oName?.ifBlank { null } ?: cert.issuedBy?.cName.orEmpty()
            val date = until?.let { java.text.SimpleDateFormat("yyyy/M/d", java.util.Locale.getDefault()).format(it) }.orEmpty()
            add(
                if (daysLeft != null && daysLeft < 30) Triple("verified", orange, t("憑證即將到期")) to (t("%1\$s・剩 %2\$s 天（%3\$s）", issuer, daysLeft, date) to 1)
                else Triple("verified", green, t("憑證有效")) to (t("%1\$s・有效至 %2\$s", issuer, date) to 0),
            )
        }
        add(if (safeBrowsing) Triple("shield", green, t("Google 安全瀏覽已啟用")) to (t("詐騙、惡意軟體網站會先顯示警告") to 0)
            else Triple("shield", orange, t("安全瀏覽無法使用")) to (t("此裝置的 WebView 不支援") to 1))
        if (isIp) add(Triple("dns", orange, t("以 IP 位址開啟")) to (t("正規網站很少直接用 IP，請確認來源") to 1))
        if (punycode) add(Triple("translate", red, t("國際化網域（可能仿冒）")) to (t("網址含 xn-- 編碼，外觀可能模仿知名網站") to 2))
        if (riskyTld) add(Triple("flag", orange, t("常被濫用的網域後綴")) to (t(".%1\$s 常見於垃圾與詐騙網站", host.substringAfterLast('.')) to 1))
        add(Triple("block", if (tab.blockedCount > 0) green else Palette.Gray, t("已阻擋追蹤器")) to (t("此頁擋下 %1\$s 個廣告與追蹤請求", tab.blockedCount) to 0))
    }
    val level = checks.maxOf { it.second.second }
    val (shieldColor, verdict, hint) = when (level) {
        0 -> Triple(green, t("安全"), t("沒有發現問題"))
        1 -> Triple(orange, t("注意"), t("有 %1\$s 項需要留意", checks.count { it.second.second == 1 }))
        else -> Triple(red, t("有風險"), t("請勿輸入密碼或付款資料"))
    }
    GroupRow(
        0, checks.size + 1, tone, t("網站安全性：%1\$s", verdict), subtitle = hint,
        leading = { IconTile(if (level == 0) "verified_user" else "gpp_maybe", shieldColor, 36.dp) },
        titleColor = shieldColor, modifier = Modifier.padding(top = 8.dp),
    )
    checks.forEachIndexed { i, (head, body) ->
        GroupRow(i + 1, checks.size + 1, tone, head.third, subtitle = body.first, leading = { Sym(head.first, head.second, 22.dp) })
    }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, tone: Tone, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = tone.content, fontSize = 16.sp)
        com.liquid.browser.ui.IosSwitch(checked, tone, onChange = onChange)
    }
}

/** 網站設定裡的「權限」（Brave 的 Page Info → 權限）：位置／相機／麥克風各自 詢問／允許／封鎖，改了就永久記住 */
@Composable
private fun SitePermissionRows(tab: Tab, tone: Tone) {
    val origin = com.liquid.browser.browser.SitePermissionStore.originOf(tab.url) ?: return
    @Suppress("UNUSED_VARIABLE") val v = com.liquid.browser.browser.SitePermissionStore.version
    val list = com.liquid.browser.browser.SitePermissionStore.exceptions()
    val kinds = com.liquid.browser.browser.SiteKind.entries
    GroupHeader(t("權限"), tone)
    kinds.forEachIndexed { i, kind ->
        val ex = list.firstOrNull { it.origin == origin && it.kind == kind }
        val ask = com.liquid.browser.browser.SitePermissionStore.askByDefault(kind)
        // 位置的「允許」分成精確位置／大概位置：選項 詢問／精確位置／大概位置／封鎖
        val geo = kind == com.liquid.browser.browser.SiteKind.Location
        val options = buildList {
            add(if (ask) t("詢問（預設）") else t("封鎖（預設）"))
            add(if (geo) t("精確位置") else t("允許"))
            if (geo) add(t("大概位置"))
            add(t("封鎖"))
        }
        val selected = when { ex == null -> 0; ex.allow && ex.approx -> 2; ex.allow -> 1; else -> options.lastIndex }
        GroupMenuRow(
            i, kinds.size, tone, t(kind.label).replaceFirstChar { it.uppercase() }, options, selected,
            subtitle = ex?.let { permissionLifetimeText(it) },
            leading = { IconTile(kind.icon, Color(0xFF007AFF)) },
        ) { pick ->
            val store = com.liquid.browser.browser.SitePermissionStore
            when (pick) {
                0 -> store.reset(origin, kind)
                else -> store.set(
                    origin, kind, pick != options.lastIndex, com.liquid.browser.browser.PermLifetime.Forever, tab.incognito,
                    approx = geo && pick == 2,
                )
            }
        }
    }
}

/** 例外的有效期：關閉網站前／到某日／永遠；自動封鎖另外標示 */
fun permissionLifetimeText(ex: com.liquid.browser.browser.SiteException): String {
    val until = ex.until
    return when {
        until == 0L -> t("直到關閉這個網站")
        until == null -> t("永遠")
        else -> t("到 %1\$s", java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.getDefault()).format(java.util.Date(until)))
    }
}
