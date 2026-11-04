package com.liquid.browser.ui.settings

import com.liquid.browser.ui.SymButton
import com.liquid.browser.browser.Translator
import com.liquid.browser.data.BarLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import com.liquid.browser.core.t

import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import com.liquid.browser.core.Motion
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquid.browser.browser.BrowserViewModel
import com.liquid.browser.browser.DlStatus
import com.liquid.browser.browser.AdBlocker
import com.liquid.browser.browser.TranslateEngine
import com.liquid.browser.browser.TargetLang
import androidx.compose.ui.focus.onFocusChanged
import com.liquid.browser.browser.DlTask
import com.liquid.browser.browser.DownloadEngine
import androidx.compose.foundation.lazy.LazyListScope
import com.liquid.browser.core.Palette
import com.liquid.browser.data.SearchEngine
import com.liquid.browser.ui.Sym
import com.liquid.browser.ui.PageTitle
import com.liquid.browser.ui.GroupHeader
import com.liquid.browser.ui.GroupFooter
import com.liquid.browser.ui.GroupRow
import com.liquid.browser.ui.GroupToggle
import com.liquid.browser.ui.GroupCheck
import com.liquid.browser.ui.GroupMenuRow
import com.liquid.browser.ui.GroupPick
import com.liquid.browser.ui.IconTile
import com.liquid.browser.ui.Chevron
import com.liquid.browser.ui.groupItem
import androidx.compose.foundation.lazy.itemsIndexed
import com.liquid.browser.ui.accent
import com.liquid.browser.ui.browser.ToggleRow
import com.liquid.browser.ui.glass.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 設定頁：搜尋引擎、網站預設、外觀（降低透明度、起始頁背景）、清除瀏覽資料（下載項目在頁面選單與網址列的下載圖示）。 */
@Composable
fun SettingsContent(vm: BrowserViewModel, tone: Tone) {
    val s by vm.settings.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accent = accent
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val path = copyBackground(context, uri)
            vm.updateSettings { it.copy(startBackground = path) }
        }
    }
    var clearHistory by remember { mutableStateOf(true) }
    var clearCookies by remember { mutableStateOf(false) }
    var clearCache by remember { mutableStateOf(true) }

    val red = Color(0xFFFF3B30)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 48.dp)) {
        item { PageTitle(t("設定"), tone, Modifier.padding(start = 4.dp)) }

        // 一般：介面語言、搜尋引擎（下拉選單，不平鋪）
        item { GroupHeader(t("一般"), tone) }
        item(key = "lang") {
            val langs = com.liquid.browser.core.AppLang.entries
            // 語言名稱一律用該語言本身顯示
            GroupMenuRow(
                0, 2, tone, t("介面語言"),
                langs.map { if (it == com.liquid.browser.core.AppLang.System) t("跟隨系統") else it.nativeName }, langs.indexOf(s.appLang),
                leading = { IconTile("language", Color(0xFF007AFF)) },
            ) { i -> vm.updateSettings { it.copy(appLang = langs[i]) } }
        }
        val engines = SearchEngine.entries
        item(key = "engine") {
            GroupMenuRow(1, 2, tone, t("搜尋引擎"), engines.map { it.label }, engines.indexOf(s.engine), leading = { IconTile("search", Color(0xFF8E8E93)) }) { i ->
                vm.updateSettings { it.copy(engine = engines[i]) }
            }
        }

        // 預設瀏覽器：用系統的角色請求對話框（HyperOS 預設應用清單不一定列出第三方瀏覽器）
        item(key = "default") {
            val roles = if (android.os.Build.VERSION.SDK_INT >= 29) context.getSystemService(android.app.role.RoleManager::class.java) else null
            var check by remember { mutableStateOf(0) }
            val isDefault = remember(check) { android.os.Build.VERSION.SDK_INT >= 29 && roles?.isRoleHeld(android.app.role.RoleManager.ROLE_BROWSER) == true }
            val request = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { check++ }
            GroupRow(
                0, 1, tone, if (isDefault) t("已是預設瀏覽器") else t("設為預設瀏覽器"),
                Modifier.padding(top = 8.dp),
                leading = { IconTile("public", Color(0xFF34C759)) },
                onClick = if (isDefault) null else ({
                    if (android.os.Build.VERSION.SDK_INT >= 29 && roles != null && roles.isRoleAvailable(android.app.role.RoleManager.ROLE_BROWSER)) {
                        request.launch(roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_BROWSER))
                    } else {
                        request.launch(android.content.Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                    }
                }),
            )
        }

        // 網址列輸入時，前面的引擎圖示點一下在這兩個之間切換；兩個選成同一個時自動對調
        item { GroupHeader(t("搜尋引擎快速切換"), tone) }
        items(2, key = { "quick$it" }) { slot ->
            GroupMenuRow(slot, 2, tone, if (slot == 0) t("第一個") else t("第二個"), engines.map { it.label }, engines.indexOf(s.quickEngines[slot])) { i ->
                vm.updateSettings { st ->
                    val q = st.quickEngines.toMutableList()
                    val other = 1 - slot
                    if (q[other] == engines[i]) q[other] = q[slot]
                    q[slot] = engines[i]
                    st.copy(quickEngines = q)
                }
            }
        }

        // 翻譯：引擎、目標語言、夜間 DeepSeek 同一組
        item { GroupHeader(t("網頁翻譯"), tone) }
        val tEngines = TranslateEngine.entries
        fun engineNote(e: TranslateEngine) = when {
            !e.isFree && s.translateKeys[e.keyOwner].isNullOrBlank() -> t("尚未填金鑰（會改用免費翻譯）")
            e == TranslateEngine.DeepSeek -> t("晚上 22:00–08:00 半價")
            e == TranslateEngine.Tencent -> t("免金鑰、免費，中國與海外都能用")
            e == TranslateEngine.Free -> t("免金鑰、免費；中國無法連線時自動改用騰訊")
            else -> null
        }
        item(key = "trEngine") {
            GroupMenuRow(
                0, 3, tone, t("翻譯引擎"), tEngines.map { when (it) { TranslateEngine.Tencent -> t("騰訊翻譯"); TranslateEngine.Free -> "Google"; else -> it.label } }, tEngines.indexOf(s.translateEngine),
                subtitle = engineNote(s.translateEngine), optionSubtitles = tEngines.map { engineNote(it) },
                leading = { IconTile("translate", Color(0xFF34C759)) },
            ) { i -> vm.updateSettings { it.copy(translateEngine = tEngines[i]) } }
        }
        item(key = "trTarget") {
            val targets = TargetLang.entries
            GroupMenuRow(1, 3, tone, t("翻譯成"), targets.map { it.label }, targets.indexOf(s.translateTarget), leading = { IconTile("g_translate", Color(0xFF30B0C7)) }) { i ->
                vm.updateSettings { it.copy(translateTarget = targets[i]) }
            }
        }
        item(key = "night") {
            GroupToggle(2, 3, tone, t("夜間優先使用 DeepSeek"), s.nightDeepSeek, subtitle = t("22:00–08:00 自動改用，半價"), leading = { IconTile("dark_mode", Color(0xFF5856D6)) }) { v ->
                vm.updateSettings { it.copy(nightDeepSeek = v) }
            }
        }
        // API 金鑰：產生金鑰後按「貼上」即可；在免費模式下貼上會自動切到 AI 引擎
        item { GroupHeader(t("API 金鑰"), tone) }
        item(key = "keys") {
            val owner = TranslateEngine.Qwen
            Column(Modifier.fillMaxWidth().groupItem(0, 3, tone).padding(horizontal = 16.dp, vertical = 6.dp)) {
                KeyField(t("阿里雲百煉（千問、DeepSeek、GLM 共用）"), s.translateKeys[owner].orEmpty(), t("阿里雲百煉控制台 → API-KEY"), tone, accent) { v ->
                    vm.updateSettings { st ->
                        st.copy(
                            translateKeys = st.translateKeys + (owner to v.trim()),
                            translateEngine = when {
                                // 貼上金鑰：免費模式自動切到千問；刪除金鑰：AI 引擎切回免費
                                st.translateEngine.isFree && v.isNotBlank() -> owner
                                v.isBlank() && !st.translateEngine.isFree -> TranslateEngine.Tencent
                                else -> st.translateEngine
                            },
                        )
                    }
                    if (v.isBlank()) vm.showToast(t("已刪除金鑰，改用免費翻譯"))
                }
            }
        }
        item(key = "trcheck") {
            // 測試連線：用目前選的 AI 引擎（選免費引擎時測千問）以金鑰送一句短句；結果直接顯示在這一列
            val engine = if (s.translateEngine.isFree) TranslateEngine.Qwen else s.translateEngine
            val key = s.translateKeys[engine.keyOwner].orEmpty()
            var checking by remember { mutableStateOf(false) }
            // 換了金鑰或引擎，舊的結果就不算數
            var result by remember(key, engine) { mutableStateOf<Pair<Boolean, String>?>(null) }
            val scope = rememberCoroutineScope()
            GroupRow(
                1, 3, tone, t("測試連線"),
                subtitle = when {
                    key.isBlank() -> t("先貼上金鑰")
                    checking -> t("連線中…")
                    result != null -> result!!.second
                    else -> t("用 %1\$s 送一句短句，確認金鑰可以用", engine.label)
                },
                leading = { IconTile("network_check", Color(0xFF007AFF)) },
                trailing = {
                    when {
                        checking -> androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = accent)
                        result?.first == true -> Sym("check_circle", Color(0xFF34C759), 22.dp)
                        result?.first == false -> Sym("error", red, 22.dp)
                    }
                },
                onClick = if (key.isBlank() || checking) null else ({
                    checking = true
                    scope.launch {
                        result = runCatching { Translator.check(engine, key, s.translateModels[engine].orEmpty()) }.fold(
                            { ms -> true to t("連線成功・%1\$s 秒", "%.1f".format(ms / 1000f)) },
                            { e -> false to (e.message?.takeIf { it.isNotBlank() } ?: t("連不上（%1\$s）", e.javaClass.simpleName)) },
                        )
                        checking = false
                    }
                }),
            )
        }
        item(key = "trcache") {
            GroupRow(2, 3, tone, t("清除翻譯快取"), leading = { IconTile("delete_sweep", Palette.Gray) }, onClick = {
                vm.clearTranslationCache(); vm.showToast(t("已清除翻譯快取"))
            })
            GroupFooter(t("翻過的文字存在手機上，重新整理或再次造訪時直接套用，不再花 API 額度"))
        }

        // 網站
        item { GroupHeader(t("網站"), tone) }
        item {
            // 網站看到的裝置：Android（Chrome）或 iOS（Safari）；影響網站版面與播放器行為（例如全螢幕方式）
            GroupMenuRow(
                0, 5, tone, t("網站識別為"), listOf("Android", "iOS"), if (s.identifyAsIPhone) 1 else 0,
                subtitle = if (s.identifyAsIPhone) t("以 iOS Safari 身分瀏覽") else t("以 Android Chrome 身分瀏覽"),
                leading = { IconTile("smartphone", accent) },
            ) { i -> vm.updateSettings { it.copy(identifyAsIPhone = i == 1) } }
        }
        item {
            GroupToggle(1, 5, tone, t("預設要求桌面版網站"), s.desktopByDefault, leading = { IconTile("desktop_windows", Color(0xFF30B0C7)) }) { v -> vm.updateSettings { it.copy(desktopByDefault = v) } }
        }
        item {
            GroupToggle(2, 5, tone, t("原生播放器播放影片"), s.nativePlayer, leading = { IconTile("play_circle", red) }) { v -> vm.updateSettings { it.copy(nativePlayer = v) } }
        }
        item {
            GroupToggle(3, 5, tone, t("顯示「下載影片」按鈕"), s.videoDownloadButton, subtitle = t("網頁有影片時出現在網址列上方"), leading = { IconTile("download", Color(0xFFFF9500)) }) { v ->
                vm.updateSettings { it.copy(videoDownloadButton = v) }
            }
        }
        item {
            // 網頁核心：Orea 自己編的 Chromium（另外安裝）或系統 WebView；切換後重開 App 生效
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val ver = androidx.compose.runtime.remember { com.liquid.browser.core.Engine.installedVersion(ctx) }
            var on by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(com.liquid.browser.core.Engine.wanted(ctx)) }
            GroupToggle(
                4, 5, tone, t("使用 Orea 核心"), on && ver != null,
                subtitle = if (ver == null) t("尚未安裝 Orea 核心") else t("使用中：") + com.liquid.browser.core.Engine.active,
                leading = { IconTile("memory", Color(0xFF5856D6)) },
            ) { v ->
                if (ver == null) return@GroupToggle
                on = v; com.liquid.browser.core.Engine.setWanted(ctx, v); vm.showToast(t("重新開啟 App 後生效"))
            }
        }

        // 外觀
        item { GroupHeader(t("外觀"), tone) }
        item {
            // 直向時網址列在底部（單手好按）或頂部（傳統瀏覽器的位置）；橫向一律在頂部。
            // 跟 Safari 一樣用手機示意圖選：看圖就知道網址列會在哪
            Row(
                Modifier.fillMaxWidth().groupItem(0, 3, tone).padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                listOf(BarLayout.Compact to t("精簡"), BarLayout.Bottom to t("底部"), BarLayout.Top to t("頂部")).forEach { (layout, label) ->
                    val on = s.barLayout == layout
                    Column(
                        Modifier.clip(RoundedCornerShape(16.dp)).clickable { vm.updateSettings { it.copy(barLayout = layout) } }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .semantics(mergeDescendants = true) { selected = on },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        LayoutPhone(layout, if (on) accent else tone.content.copy(alpha = 0.35f))
                        Text(label, Modifier.padding(top = 8.dp), color = tone.content, fontSize = 15.sp)
                        Box(
                            Modifier.padding(top = 6.dp).size(22.dp).clip(CircleShape)
                                .then(if (on) Modifier.background(accent) else Modifier.border(1.5.dp, tone.content.copy(alpha = 0.3f), CircleShape)),
                            Alignment.Center,
                        ) { if (on) Sym("check", Color.White, 16.dp) }
                    }
                }
            }
        }
        item {
            GroupToggle(1, 3, tone, t("降低透明度"), s.reduceTransparency, leading = { IconTile("contrast", Color(0xFF8E8E93)) }) { v -> vm.updateSettings { it.copy(reduceTransparency = v) } }
        }
        item {
            GroupRow(
                2, 3, tone, t("起始頁背景圖"), subtitle = if (s.startBackground != null) t("已設定") else t("預設（搜尋引擎色場）"),
                leading = { IconTile("wallpaper", Color(0xFFAF52DE)) },
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                trailing = {
                    if (s.startBackground != null) TextButton(t("移除"), red) { vm.updateSettings { it.copy(startBackground = null) } }
                    Chevron()
                },
            )
        }

        // 隱私與安全
        item { GroupHeader(t("隱私與安全"), tone) }
        item {
            GroupToggle(0, 4, tone, t("阻擋廣告與追蹤"), s.adBlock, subtitle = t("類似 AdGuard 的過濾清單・%1\$s 條規則", "%,d".format(AdBlocker.ruleCount)), leading = { IconTile("block", red) }) { v ->
                vm.updateSettings { it.copy(adBlock = v) }
            }
        }
        item {
            GroupToggle(1, 4, tone, t("阻止自動跳轉"), s.blockRedirects, subtitle = t("網頁未經點擊就跳到其他網站時阻擋"), leading = { IconTile("alt_route", Color(0xFFFF9500)) }) { v ->
                vm.updateSettings { it.copy(blockRedirects = v) }
            }
        }
        item {
            // 第三方 Cookie：嵌在別的網站裡（廣告、分享鈕、追蹤像素）用來跨網站認出你；少數網站的嵌入登入會需要它
            GroupToggle(2, 4, tone, t("封鎖第三方 Cookie"), s.blockThirdPartyCookies, subtitle = t("防止跨網站追蹤"), leading = { IconTile("cookie", Color(0xFF8E8E93)) }) { v ->
                vm.updateSettings { it.copy(blockThirdPartyCookies = v) }
            }
        }
        item {
            // 連結追蹤保護（Safari）：拿掉 utm_、fbclid、gclid 等追蹤參數；無痕分頁一律套用
            GroupMenuRow(
                3, 4, tone, t("移除網址追蹤參數"), listOf(t("僅無痕瀏覽"), t("所有瀏覽")), if (s.cleanLinksEverywhere) 1 else 0,
                subtitle = t("例如 utm_、fbclid、gclid"),
                leading = { IconTile("link_off", Color(0xFF34C759)) },
            ) { i -> vm.updateSettings { it.copy(cleanLinksEverywhere = i == 1) } }
        }
        item { GroupFooter(t("無痕分頁一律阻擋已知追蹤器、封鎖第三方 Cookie、移除網址追蹤參數。個別網站可在「網站設定」關閉廣告阻擋；網站設定也會顯示安全性檢查結果")) }

        // 密碼：要先過指紋／螢幕鎖驗證才展開清單；點一列顯示／隱藏密碼，右側刪除
        item { GroupHeader(t("密碼"), tone) }
        item(key = "passwords") {
            val activity = androidx.activity.compose.LocalActivity.current
            var creds by remember { mutableStateOf<List<com.liquid.browser.browser.Credential>?>(null) }
            var reveal by remember { mutableStateOf<com.liquid.browser.browser.Credential?>(null) }
            val scope = rememberCoroutineScope()
            val list = creds
            GroupRow(
                0, 1 + (list?.size ?: 0), tone, t("已儲存的密碼"),
                subtitle = if (list == null) t("需要指紋或螢幕鎖驗證") else t("%1\$s 組", list.size),
                leading = { IconTile("key", Color(0xFF34C759)) },
                trailing = { Sym(if (list == null) "lock" else "lock_open", tone.content.copy(alpha = 0.4f), 20.dp) },
                onClick = {
                    if (list != null) creds = null
                    else activity?.let { a ->
                        com.liquid.browser.browser.Auth.request(a, t("查看已儲存的密碼"), onFail = { vm.showToast(it) }) {
                            scope.launch { creds = withContext(Dispatchers.IO) { com.liquid.browser.browser.PasswordVault.all() } }
                        }
                    }
                },
            )
            list?.forEachIndexed { i, c ->
                GroupRow(
                    i + 1, list.size + 1, tone, Uri.parse(c.origin).host?.removePrefix("www.").orEmpty(),
                    subtitle = if (reveal == c) "${c.user}・${c.pass}" else c.user.ifBlank { t("（沒有帳號）") },
                    trailing = {
                        SymButton("delete", red, size = 20.dp, label = t("刪除")) {
                            scope.launch {
                                withContext(Dispatchers.IO) { com.liquid.browser.browser.PasswordVault.delete(c) }
                                creds = creds?.minus(c)
                            }
                        }
                    },
                    onClick = { reveal = if (reveal == c) null else c },
                )
            }
            GroupFooter(t("密碼用這支手機的硬體金鑰加密，只存在這支手機上；填入前一定要驗證身分。無痕分頁不會詢問儲存"))
        }

        item { GroupHeader(t("清除瀏覽資料"), tone) }
        item { GroupPick(0, 4, tone, t("歷史紀錄"), clearHistory, leading = { IconTile("history", Color(0xFF007AFF)) }) { clearHistory = it } }
        item { GroupPick(1, 4, tone, t("Cookie 與網站資料"), clearCookies, leading = { IconTile("cookie", Color(0xFFFF9500)) }) { clearCookies = it } }
        item { GroupPick(2, 4, tone, t("快取的圖片與檔案"), clearCache, leading = { IconTile("photo_library", Color(0xFF34C759)) }) { clearCache = it } }
        item {
            GroupRow(
                3, 4, tone, t("清除瀏覽資料"), leading = { IconTile("delete", red) }, destructive = true,
                onClick = { if (clearHistory || clearCookies || clearCache) { vm.clearData(clearHistory, clearCookies, clearCache); vm.showToast(t("已清除瀏覽資料")) } },
            )
        }

        // 關於：App 與 Chromium（Android System WebView）版本；WebView 由系統自動更新，點一下可到商店手動檢查
        item { GroupHeader(t("關於"), tone) }
        item(key = "about") {
            val context = androidx.compose.ui.platform.LocalContext.current
            val app = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
            val web = remember { androidx.webkit.WebViewCompat.getCurrentWebViewPackage(context) }
            GroupRow(0, 2, tone, "Orea", leading = { IconTile("info", Color(0xFF5856D6)) }, trailing = { Text(app, color = Palette.Gray, fontSize = 16.sp) })
            GroupRow(
                1, 2, tone, t("瀏覽器核心"), subtitle = t("Chromium（系統自動更新）"),
                leading = { IconTile("public", Color(0xFF34C759)) },
                trailing = { Text(web?.versionName?.substringBefore(' ').orEmpty(), color = Palette.Gray, fontSize = 16.sp); Chevron() },
                onClick = {
                    val pkg = web?.packageName ?: "com.google.android.webview"
                    runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$pkg")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { vm.newTab("https://play.google.com/store/apps/details?id=$pkg") }
                },
            )
        }
    }
}

/** 下載頁（頁面選單「下載項目」）：標題與總覽、進行中／已完成兩組、空白時的引導。 */
@Composable
fun DownloadsContent(tone: Tone) {
    val accent = accent
    val context = LocalContext.current
    val active = DownloadEngine.tasks.filter { it.status == DlStatus.Running }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 48.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t("下載項目"), Modifier.weight(1f), color = tone.content, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                if (DownloadEngine.tasks.any { it.status == DlStatus.Done }) TextButton(t("清除已完成"), accent) { DownloadEngine.clearFinished() }
            }
            // 總覽：進行中的數量與總速度
            Text(
                if (active.isEmpty()) t("多線程加速・最多 %1\$s 條連線", DownloadEngine.MAX_CONN)
                else t("%1\$s 個進行中・總速度 %2\$s/s", active.size, Formatter.formatShortFileSize(context, active.sumOf { it.speed })),
                Modifier.padding(top = 2.dp, bottom = 8.dp), color = Palette.Gray, fontSize = 13.sp,
            )
        }
        downloadItems(tone, accent)
    }
}

fun LazyListScope.downloadItems(tone: Tone, accent: Color) {
    val tasks = DownloadEngine.tasks
    if (tasks.isEmpty()) {
        item { DownloadsEmpty(tone) }
        return
    }
    val (done, ongoing) = tasks.partition { it.status == DlStatus.Done }
    if (ongoing.isNotEmpty()) {
        item(key = "h_on") { GroupLabel(t("進行中"), tone) }
        items(ongoing, key = { it.id }) { t -> DownloadCard(t, tone, accent, Modifier.animateItem()) }
    }
    if (done.isNotEmpty()) {
        item(key = "h_done") { GroupLabel(t("已完成"), tone) }
        items(done, key = { it.id }) { t -> DownloadCard(t, tone, accent, Modifier.animateItem()) }
    }
}

@Composable
private fun GroupLabel(text: String, tone: Tone) =
    Text(text, Modifier.padding(top = 16.dp, bottom = 8.dp), color = tone.content.copy(alpha = 0.55f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

@Composable
private fun DownloadsEmpty(tone: Tone) {
    Column(Modifier.fillMaxWidth().padding(top = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(tone.content.copy(alpha = 0.06f)), Alignment.Center) {
            Sym("download", tone.content.copy(alpha = 0.6f), 30.dp)
        }
        Text(t("還沒有下載"), Modifier.padding(top = 16.dp), color = tone.content, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(
            t("網頁上的檔案、長按圖片儲存，或播放器右上角的下載鈕，都會出現在這裡"),
            Modifier.padding(top = 6.dp, start = 24.dp, end = 24.dp), color = Palette.Gray, fontSize = 14.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 20.sp,
        )
    }
}

/** 檔案類型 → 圖示與顏色（顏色代表類型） */
private fun fileKind(t: DlTask): Pair<String, Color> {
    val ext = t.name.substringAfterLast('.', "").lowercase()
    return when {
        t.isHls || ext in setOf("mp4", "webm", "mkv", "m4v", "ts", "mov", "avi") -> "movie" to Color(0xFFAF52DE)
        ext in setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "avif") -> "image" to Color(0xFF34C759)
        ext in setOf("zip", "rar", "7z", "tar", "gz", "apk", "xapk") -> "folder_zip" to Color(0xFFFF9500)
        ext in setOf("mp3", "m4a", "flac", "wav", "aac", "ogg") -> "music_note" to Color(0xFFFF2D55)
        else -> "description" to Color(0xFF007AFF)
    }
}

/**
 * 一筆下載：類型圖示、檔名、右側大字百分比；IDM 式分段進度條（每條連線負責的區段各自填滿）；
 * 下方左側大小、右側速度與剩餘時間。取消需要再點一次確認（會刪掉已下載的部分）。
 */
@Composable
private fun DownloadCard(t: DlTask, tone: Tone, accent: Color, modifier: Modifier) {
    val context = LocalContext.current
    val red = Color(0xFFFF3B30)
    val (icon, kindColor) = fileKind(t)
    val finished = t.status == DlStatus.Done
    Column(
        modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(18.dp))
            .background(tone.content.copy(alpha = 0.05f))
            .clickable(enabled = finished, onClickLabel = t("開啟檔案")) { DownloadEngine.open(context, t) }
            .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 下載完成的瞬間：圖示以彈簧「彈」成綠色勾，0.9 秒後換回檔案類型圖示
            var justDone by remember { mutableStateOf(false) }
            val wasRunning = remember { mutableStateOf(t.status == DlStatus.Running) }
            LaunchedEffect(t.status) {
                if (t.status == DlStatus.Done && wasRunning.value) { justDone = true; delay(900); justDone = false }
                wasRunning.value = t.status == DlStatus.Running
            }
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(12.dp))
                    .background((if (justDone) Color(0xFF34C759) else kindColor).copy(alpha = 0.14f)),
                Alignment.Center,
            ) {
                androidx.compose.animation.Crossfade(justDone, animationSpec = Motion.timed(), label = "done") { d ->
                    if (d) Sym("check_circle", Color(0xFF34C759), 24.dp)
                    else Sym(if (t.status == DlStatus.Failed) "error" else icon, if (t.status == DlStatus.Failed) red else kindColor, 22.dp)
                }
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(t.name, color = tone.content, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                Text(
                    statusLine(context, t),
                    color = if (t.status == DlStatus.Failed) red else Palette.Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (!finished && t.total > 0) Text(
                "${(t.progress * 100).toInt()}%",
                Modifier.padding(horizontal = 6.dp),
                color = if (t.status == DlStatus.Running) tone.content else Palette.Gray,
                style = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
            )
            when (t.status) {
                DlStatus.Running -> RowButton("pause", t("暫停"), tone) { DownloadEngine.pause(t) }
                DlStatus.Paused -> RowButton("play_arrow", t("繼續"), tone) { DownloadEngine.resume(t) }
                DlStatus.Failed -> RowButton("refresh", t("重試"), tone) { DownloadEngine.resume(t) }
                DlStatus.Done -> RowButton("open_in_new", t("開啟"), tone) { DownloadEngine.open(context, t) }
            }
            // 完成的項目：刪除檔案；未完成：取消下載。兩者都會刪掉資料，要再點一次確認
            if (finished) ConfirmButton("delete", t("刪除？"), t("刪除檔案"), tone, red) { DownloadEngine.deleteFile(t) }
            else ConfirmButton("close", t("取消？"), t("取消下載"), tone, red) { DownloadEngine.cancel(t) }
        }
        if (!finished && t.status != DlStatus.Failed) {
            SegmentBar(t, tone, if (t.status == DlStatus.Running) accent else Palette.Gray, Modifier.padding(top = 10.dp, end = 8.dp))
            Row(Modifier.padding(top = 6.dp, end = 8.dp)) {
                Text(amountLine(context, t), Modifier.weight(1f), color = Palette.Gray, fontSize = 12.sp, style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"))
                Text(speedLine(context, t), color = Palette.Gray, fontSize = 12.sp, style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"))
            }
        }
    }
}

/** IDM 式分段進度條：底軌上依各連線的區段畫出已填滿的部分，區段之間留 1dp 縫隙看得出是多條連線；HLS 或單線時為一般進度條 */
@Composable
private fun SegmentBar(t: DlTask, tone: Tone, color: Color, modifier: Modifier) {
    val parts = t.parts
    val smooth by androidx.compose.animation.core.animateFloatAsState(t.progress, label = "progress")
    androidx.compose.foundation.Canvas(modifier.fillMaxWidth().height(6.dp).clip(CircleShape)) {
        drawRect(tone.content.copy(alpha = 0.08f))
        if (parts.size > 1) {
            val gap = 1.dp.toPx()
            parts.forEach { (start, pos, end) ->
                val x0 = start * size.width
                val x1 = (pos * size.width).coerceAtMost(end * size.width - gap)
                if (x1 > x0) drawRect(color, androidx.compose.ui.geometry.Offset(x0, 0f), androidx.compose.ui.geometry.Size(x1 - x0, size.height))
            }
        } else {
            drawRect(color, size = androidx.compose.ui.geometry.Size(size.width * smooth, size.height))
        }
    }
}

/** 會刪資料的按鈕：第一次點變成紅色確認字（例如「刪除？」），3 秒內再點才執行 */
@Composable
private fun ConfirmButton(icon: String, confirmText: String, label: String, tone: Tone, red: Color, onConfirm: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) { if (armed) { delay(3000); armed = false } }
    if (armed) Text(
        confirmText,
        Modifier.height(44.dp).clip(CircleShape).clickable(onClickLabel = t("確認%1\$s", label)) { onConfirm() }
            .background(red.copy(alpha = 0.12f)).padding(horizontal = 12.dp).wrapContentHeight(),
        color = red, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
    )
    else RowButton(icon, label, tone) { armed = true }
}

@Composable
private fun RowButton(icon: String, label: String, tone: Tone, onClick: () -> Unit) =
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = label, onClick = onClick), Alignment.Center) {
        Sym(icon, tone.content.copy(alpha = 0.8f), 22.dp)
    }

private fun statusLine(context: Context, t: DlTask): String = when (t.status) {
    DlStatus.Done -> buildString {
        append(if (t.isHls) t("影片") else Formatter.formatShortFileSize(context, t.total.coerceAtLeast(t.done)))
        append(t("・點一下開啟"))
    }
    DlStatus.Failed -> t("下載失敗：%1\$s", t.error.orEmpty())
    DlStatus.Paused -> t("已暫停")
    DlStatus.Running -> when {
        t.isHls && t.total <= 0 -> t("分析影片中…")
        t.connections > 1 -> t("%1\$s 條連線加速中", t.connections)
        else -> t("下載中")
    }
}

private fun amountLine(context: Context, t: DlTask): String {
    fun size(b: Long) = Formatter.formatShortFileSize(context, b)
    return when {
        t.isHls -> if (t.total > 0) t("片段 %1\$s / %2\$s", t.done, t.total) else ""
        t.total > 0 -> "${size(t.done)} / ${size(t.total)}"
        else -> size(t.done)
    }
}

private fun speedLine(context: Context, t: DlTask): String {
    if (t.status != DlStatus.Running || t.speed <= 0) return ""
    val speed = "${Formatter.formatShortFileSize(context, t.speed)}/s"
    if (t.isHls || t.total <= 0) return speed
    val sec = (t.total - t.done) / t.speed
    val left = if (sec >= 60) t("%1\$s 分 %2\$s 秒", sec / 60, sec % 60) else t("%1\$s 秒", sec)
    return t("%1\$s・剩 %2\$s", speed, left)
}

/** API 金鑰欄：遮蔽顯示；「貼上」直接讀剪貼簿並存檔，手動輸入則在離開欄位或按完成時存檔 */
@Composable
private fun KeyField(label: String, value: String, hint: String, tone: Tone, accent: Color, onSave: (String) -> Unit) {
    val context = LocalContext.current
    var text by remember(value) { mutableStateOf(value) }
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label, color = tone.content, fontSize = 15.sp)
        Row(
            Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(tone.content.copy(alpha = 0.06f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.text.BasicTextField(
                text, { text = it },
                Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 12.dp)
                    .onFocusChanged { if (!it.isFocused && text != value) onSave(text) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = tone.content, fontSize = 15.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(accent),
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onSave(text) }),
                decorationBox = { inner ->
                    if (text.isEmpty()) Text(t("貼上金鑰"), color = Palette.Gray, fontSize = 15.sp)
                    inner()
                },
            )
            // 有金鑰時可刪除（清空並存檔）
            if (text.isNotEmpty()) Text(
                t("刪除"),
                Modifier.clip(CircleShape).clickable(onClickLabel = t("刪除金鑰")) { text = ""; onSave("") }.padding(horizontal = 10.dp, vertical = 10.dp),
                color = Color(0xFFFF3B30), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (text.isEmpty()) t("貼上") else t("更換"),
                Modifier.clip(CircleShape).clickable(onClickLabel = t("從剪貼簿貼上金鑰")) {
                    val clip = context.getSystemService(android.content.ClipboardManager::class.java)
                    val pasted = clip?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
                    if (pasted.isNotEmpty()) {
                        text = pasted
                        onSave(pasted)
                        // 金鑰貼上後清掉剪貼簿：不留在剪貼簿（其他 App、輸入法的剪貼簿紀錄、小米跨裝置同步都讀得到）
                        if (android.os.Build.VERSION.SDK_INT >= 28) clip?.clearPrimaryClip()
                        else clip?.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
                    }
                }.padding(horizontal = 14.dp, vertical = 10.dp),
                color = accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        if (hint.isNotEmpty()) Text(hint, Modifier.padding(top = 4.dp), color = Palette.Gray, fontSize = 12.sp)
    }
}

@Composable
private fun Section(text: String, tone: Tone) =
    Text(text, Modifier.padding(top = 20.dp, bottom = 4.dp), color = tone.content.copy(alpha = 0.55f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

@Composable
private fun TextButton(text: String, color: Color, onClick: () -> Unit) =
    Text(text, Modifier.clip(CircleShape).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp), color = color, fontSize = 15.sp)

/** 把選取的圖片複製到 App 私有資料夾（相簿授權只是暫時的），回傳 file:// 路徑。 */
/**
 * 網址列版面的示意圖（Safari 設定頁那三支手機）：
 * 精簡＝底部一條短膠囊；底部＝底部一整塊粗條；頂部＝頂端一條＋底部一條細工具列。選中時用強調色。
 */
@Composable
private fun LayoutPhone(layout: BarLayout, color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(44.dp, 84.dp)) {
        val st = 2.dp.toPx()
        drawRoundRect(color, topLeft = Offset(st / 2, st / 2), size = Size(size.width - st, size.height - st), cornerRadius = CornerRadius(10.dp.toPx()), style = Stroke(st))
        val inset = 6.dp.toPx()
        fun bar(y: Float, w: Float, h: Float) = drawRoundRect(color, topLeft = Offset((size.width - w) / 2, y), size = Size(w, h), cornerRadius = CornerRadius(minOf(h / 2, 3.dp.toPx())))
        when (layout) {
            BarLayout.Compact -> bar(size.height - 9.dp.toPx() - 5.dp.toPx(), size.width * 0.62f, 5.dp.toPx())
            BarLayout.Bottom -> bar(size.height - inset - 13.dp.toPx(), size.width - inset * 2, 13.dp.toPx())
            BarLayout.Top -> {
                bar(inset + 2.dp.toPx(), size.width - inset * 2, 6.dp.toPx())
                bar(size.height - inset - 5.dp.toPx(), size.width - inset * 2, 5.dp.toPx())
            }
        }
    }
}

private suspend fun copyBackground(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    val file = File(context.filesDir, "start_background_${System.currentTimeMillis()}.img")
    context.filesDir.listFiles { f -> f.name.startsWith("start_background_") }?.forEach { it.delete() }
    context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
    Uri.fromFile(file).toString()
}
