package com.liquid.browser.browser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.liquid.browser.core.t

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.MutableContextWrapper
import android.net.Uri
import android.view.ViewGroup
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import com.liquid.browser.core.Dimens
import com.liquid.browser.data.AppDatabase
import com.liquid.browser.data.Bookmark
import com.liquid.browser.data.HistoryEntry
import com.liquid.browser.data.SiteIcon
import com.liquid.browser.player.PlayerController
import com.liquid.browser.player.PlayerRequest
import com.liquid.browser.data.ReadingItem
import com.liquid.browser.data.Settings
import com.liquid.browser.data.SettingsStore
import android.annotation.SuppressLint
import android.view.View
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebStorage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit

@Immutable
data class Visit(val url: String, val title: String)

/**
 * 瀏覽器狀態（MVVM 的 ViewModel）：分頁、目前分頁、收合進度、無痕模式，以及 Room 資料。
 * WebView 以 MutableContextWrapper 建立：Activity 重建時換掉底層 Context，WebView 本身保留、不洩漏舊 Activity。
 */
class BrowserViewModel(app: Application) : AndroidViewModel(app), Tab.Listener {
    private val webContext = MutableContextWrapper(app)
    val db = AppDatabase.get(app)

    val tabs = mutableStateListOf<Tab>()
    var current by mutableStateOf<Tab?>(null)
        private set
    var incognitoMode by mutableStateOf(false)

    val settingsStore = SettingsStore(app)
    val settings: StateFlow<Settings> = settingsStore.flow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    /** 網頁影片全螢幕（WebChromeClient.onShowCustomView）的 View 與回呼 */
    var customView by mutableStateOf<View?>(null)
        private set
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    /** 長按連結／圖片的結果，BrowserScreen 據此顯示預覽卡片與選單 */
    var longPress by mutableStateOf<Pair<Tab, HitResult>?>(null)

    /** 頁面選單的「隱藏控制項」 */
    var controlsHidden by mutableStateOf(false)

    /** 原生播放器（開啟中才有值）與子母畫面狀態 */
    var player by mutableStateOf<PlayerController?>(null)
        private set
    var inPip by mutableStateOf(false)
    var videoBounds = android.graphics.Rect()

    fun openPlayer(request: PlayerRequest) {
        player?.release()
        player = PlayerController(getApplication(), request, db.playback(), viewModelScope)
    }

    fun closePlayer() {
        player?.release()
        player = null
    }

    override fun onMediaUrl(tab: Tab, url: String, typed: Boolean): Boolean {
        if (!settings.value.nativePlayer) return false
        openPlayer(
            PlayerRequest(
                url = url,
                title = Uri.parse(url).lastPathSegment ?: tab.title,
                // 直接輸入的網址不是從目前網頁來的：不帶 Referer（也不會顯示成上一頁的網域）
                referer = tab.url.takeIf { !typed && it.startsWith("http") },
                userAgent = tab.webView.settings.userAgentString,
                cookies = Downloads.cookieManager(tab.incognito).getCookie(url),
            ),
        )
        return true
    }

    /** 注入網頁的影片偵測＋手勢層腳本（assets），第一次使用時讀取並快取 */
    private val videoScript: String by lazy {
        val assets = getApplication<Application>().assets
        // yt_subs.js 自己判斷網域，只在 YouTube 上啟用
        listOf("gesture_layer.js", "video_detect.js", "yt_subs.js", "pw_autofill.js").joinToString("\n") { name -> assets.open(name).bufferedReader().use { it.readText() } }
    }

    override fun injectedScript() = videoScript

    /** 等待區域網路權限的分頁；MainActivity 看到後向使用者要求權限，同意後重新載入 */
    var localNetworkRequest by mutableStateOf<Tab?>(null)

    override fun onLocalNetworkNeeded(tab: Tab) { localNetworkRequest = tab }

    /** 跳轉 App 的提示列（BrowserScreen 顯示）；一段時間沒按就自動收起 */
    var appPrompt by mutableStateOf<AppPrompt?>(null)
        private set

    /** 已提示過「在 App 中開啟」的網站：同一個網站只提示一次（沒找到 App 不記，內容頁才對得到 App 的網站之後還會再查） */
    private val suggestedSites = HashSet<String>()

    // 點擊開的直接開 App；網頁自己觸發的先詢問；沒裝 App 就改開備用網址（intent:// 的 browser_fallback_url）
    override fun onAppLink(tab: Tab, url: String, gesture: Boolean) {
        val intent = AppLinks.toIntent(url) ?: return
        val target = AppLinks.target(webContext, intent)
        when {
            target == null -> {
                val fallback = AppLinks.fallbackUrl(intent)
                when {
                    fallback != null -> tab.load(fallback)
                    gesture && intent.`package` != null -> launchApp(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${intent.`package`}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    gesture && tab == current -> showToast(t("沒有安裝可開啟此連結的 App"))
                }
            }
            gesture -> {
                launchApp(intent)
                // 只為了跳轉而開的彈出分頁：關掉回到來源頁
                val opener = tab.opener?.takeIf { it in tabs }
                if (opener != null && (tab.isBlank || tab.url == POPUP_PENDING)) { close(tab); select(opener) }
            }
            tab == current -> showAppPrompt(AppPrompt(tab, intent, target.label, target.icon, ask = true))
        }
    }

    /** 網頁載入完成：這個網站有專屬 App（淘寶、知乎…）就提示可以改用 App 開啟 */
    private fun suggestApp(tab: Tab, url: String) {
        val site = Uri.parse(url).host?.let(::baseDomain) ?: return
        if (site in suggestedSites) return
        viewModelScope.launch {
            val found = withContext(Dispatchers.Default) {
                AppLinks.nativeAppFor(webContext, url)?.let { intent -> AppLinks.target(webContext, intent)?.let { intent to it } }
            } ?: return@launch
            if (tab == current && tab.url == url && appPrompt == null && suggestedSites.add(site)) {
                showAppPrompt(AppPrompt(tab, found.first, found.second.label, found.second.icon, ask = false))
            }
        }
    }

    private fun showAppPrompt(p: AppPrompt) {
        appPrompt = p
        viewModelScope.launch {
            delay(if (p.ask) 8000 else 6000)
            if (appPrompt === p) appPrompt = null
        }
    }

    fun dismissAppPrompt() { appPrompt = null }

    /** 「要安裝這個擴充功能嗎？」提示列：分頁＋來源網址；同一個網址只問一次 */
    var extOffer by mutableStateOf<Pair<Tab, String>?>(null)
        private set
    private val offeredExt = mutableSetOf<String>()

    override fun onExtensionOffer(tab: Tab, url: String) {
        if (tab != current || !offeredExt.add(url)) return
        extOffer = tab to url
        viewModelScope.launch {
            delay(10_000)
            if (extOffer?.second == url) extOffer = null
        }
    }

    fun dismissExtOffer() { extOffer = null }

    /** 安裝擴充功能（商店網頁、.xpi、.crx、.user.js 網址）；結果用提示訊息告訴使用者 */
    fun installExtension(url: String) {
        extOffer = null
        if (!requireLogin("安裝擴充功能")) return
        showToast(t("正在安裝擴充功能…"))
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { Extensions.install(url) } }
                .onSuccess { showToast(t("已安裝「%1\$s」，重新整理網頁後生效", it.name)) }
                .onFailure {
                    com.liquid.browser.BugLog.event("extension", "安裝失敗 $url：$it")
                    showToast(t("無法安裝：%1\$s", it.message?.let { m -> t(m) } ?: it.javaClass.simpleName))
                }
        }
    }

    fun launchApp(intent: Intent) {
        appPrompt = null
        runCatching { webContext.startActivity(intent) }.onFailure { showToast(t("無法開啟 App")) }
    }

    override fun nativeFullscreen() = settings.value.nativePlayer

    override fun onNativeVideo(tab: Tab, src: String, title: String, from: androidx.compose.ui.geometry.Rect?, startMs: Long) {
        openPlayer(
            PlayerRequest(
                url = src,
                title = title,
                referer = tab.url.takeIf { it.startsWith("http") },
                userAgent = tab.webView.settings.userAgentString,
                cookies = Downloads.cookieManager(tab.incognito).getCookie(src),
                from = from,
                startMs = startMs,
            ),
        )
    }

    /** 底部浮現的玻璃提示訊息 */
    var toast by mutableStateOf<String?>(null)

    /** 閱讀器模式的 JS（assets/reader.js），第一次使用時讀取 */
    val readerScript: String by lazy { app.assets.open("reader.js").bufferedReader().use { it.readText() } }

    /** 0 = 控制項展開，1 = 收合成小膠囊；由 UI 以彈簧動畫往 [collapsed] 過渡 */
    var collapse by mutableFloatStateOf(0f)

    /** 最近關閉的分頁（只記一般分頁，供起始頁顯示） */
    val recentlyClosed = mutableStateListOf<Visit>()

    /** 網域 → 高解析度圖示網址 */
    val icons: StateFlow<Map<String, String>> = db.icons().all()
        .map { list -> list.associate { it.host to it.iconUrl } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** 近 30 天最常造訪的網站 */
    val frequent = db.history().frequent(System.currentTimeMillis() - 30L * 24 * 3600 * 1000)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        // 下載項目裡的影片用內建播放器開（本機檔案，不帶 Referer／Cookie）
        DownloadEngine.videoOpener = { t ->
            t.saved?.let { uri ->
                openPlayer(PlayerRequest(uri.toString(), t.name, null, android.webkit.WebSettings.getDefaultUserAgent(getApplication()), null))
                true
            } ?: false
        }
        PasswordVault.init(app)
        DownloadEngine.init(app)
        AdBlocker.init(app)
        Extensions.init(app)
        Account.init(app)
        viewModelScope.launch { Account.refresh() }
        // 安裝在背景執行緒完成：重新註冊開頁腳本要回到主執行緒（WebView 只能在主執行緒操作）
        Extensions.onChanged = { viewModelScope.launch(Dispatchers.Main) { tabs.forEach { it.applyExtensions() } } }
        // 「識別為 iPhone」設定改變時套用到所有已開啟的分頁
        viewModelScope.launch {
            settings.map { it.identifyAsIPhone }.distinctUntilChanged().collect { on -> tabs.forEach { it.setIdentifyAsIPhone(on) } }
        }
        // 「封鎖第三方 Cookie」改變時套用到所有已開啟的分頁
        viewModelScope.launch {
            settings.map { it.blockThirdPartyCookies }.distinctUntilChanged().collect { block -> tabs.forEach { it.setThirdPartyCookies(!block) } }
        }
        // 「Brave 防追蹤」開關改變時重新註冊防指紋腳本
        viewModelScope.launch {
            settings.map { it.shields }.distinctUntilChanged().drop(1).collect { on -> tabs.forEach { it.applyShields(on) } }
        }
    }

    private var nextId = 0L
    /** 捲動多少網址列完全收合（約一個網址列高） */
    private val collapseDistance = 64 * app.resources.displayMetrics.density

    fun attach(activity: Activity) { webContext.baseContext = activity }
    fun detach() { webContext.baseContext = getApplication() }

    /** 同模式、已開啟網頁的分頁；起始頁（空白分頁）不算，不出現在總覽卡片與分頁數裡 */
    fun visibleTabs() = tabs.filter { it.incognito == incognitoMode && !it.isBlank }

    fun newTab(url: String? = null, incognito: Boolean = incognitoMode, background: Boolean = false): Tab {
        val tab = Tab(nextId++, incognito, webContext, this, settings.value.desktopByDefault, settings.value.identifyAsIPhone)
        // 新分頁插在目前分頁之後
        val at = current?.let { tabs.indexOf(it) + 1 }?.takeIf { it > 0 } ?: tabs.size
        tabs.add(at, tab)
        if (!background || current == null) select(tab)
        if (!url.isNullOrBlank()) tab.load(url)
        return tab
    }

    fun select(tab: Tab) {
        // 分頁可能已經關閉或被重建（渲染程序被系統回收時換成新的 Tab）：舊的參照不能再用（WebView 已銷毀，呼叫會閃退）
        if (current == tab || tab !in tabs) return
        val prev = current
        prev?.snapshot()
        prev?.setForeground(false)
        tab.setForeground(true)
        current = tab
        incognitoMode = tab.incognito
        collapse = 0f
        collapsed = false
        // 離開沒開過網頁的起始頁時直接丟掉，總覽裡不會一直留著一張起始頁
        if (prev != null && prev.isBlank) close(prev)
    }

    /** 同模式下相鄰的分頁（dir = -1 左、+1 右），沒有則為 null。 */
    fun neighbor(dir: Int): Tab? {
        val list = visibleTabs()
        val i = list.indexOf(current ?: return null)
        return list.getOrNull(i + dir)
    }

    fun close(tab: Tab) {
        val siblings = tabs.filter { it.incognito == tab.incognito }
        val i = siblings.indexOf(tab)
        if (!tab.incognito && !tab.isBlank) {
            recentlyClosed.removeAll { it.url == tab.url }
            recentlyClosed.add(0, Visit(tab.url, tab.title))
            if (recentlyClosed.size > 10) recentlyClosed.removeAt(recentlyClosed.lastIndex)
        }
        tabs.remove(tab)
        tabs.forEach { if (it.opener == tab) it.opener = null }
        (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
        tab.webView.destroy()
        val left = siblings - tab
        // 最後一個無痕分頁關閉時刪除無痕 Profile，Cookie 與儲存資料一併清除
        // WebView.destroy() 是非同步的，立刻刪會丟「in-use profile」而閃退：等它釋放後再刪，期間又開了無痕分頁就不刪
        if (tab.incognito && left.isEmpty() && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            viewModelScope.launch {
                for (wait in listOf(500L, 2000L)) {
                    delay(wait)
                    if (tabs.any { it.incognito }) return@launch
                    if (runCatching { ProfileStore.getInstance().deleteProfile(INCOGNITO_PROFILE) }.isSuccess) return@launch
                }
            }
        }
        if (current == tab) {
            current = null
            if (left.isEmpty()) newTab(incognito = tab.incognito) else select(left[(i - 1).coerceAtLeast(0)])
        }
    }

    fun closeAll() = visibleTabs().forEach(::close)

    private val sessionFile = java.io.File(app.filesDir, "session.json")

    /** 記下一般分頁（不含無痕與起始頁）與目前分頁，App 被系統結束後重開可以還原 */
    fun saveSession() {
        val list = tabs.filter { !it.incognito && !it.isBlank }
        val json = org.json.JSONObject()
            .put("current", list.indexOf(current))
            .put("tabs", org.json.JSONArray(list.map { t ->
                org.json.JSONObject().put("url", t.url).put("title", t.title).put("used", t.lastUsed)
                    .apply { t.historyState()?.let(::bundleToB64)?.let { put("history", it) } }
            }))
        runCatching { sessionFile.writeText(json.toString()) }
        // 總覽卡片的縮圖也存起來（JPEG，背景執行緒壓縮），還原後卡片不會一片空白
        val thumbs = list.map { it.thumbnail?.asAndroidBitmap() }
        viewModelScope.launch(Dispatchers.IO) {
            thumbDir.deleteRecursively()
            thumbDir.mkdirs()
            thumbs.forEachIndexed { i, b ->
                b?.let { bmp -> runCatching { java.io.File(thumbDir, "$i.jpg").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) } } }
            }
        }
    }

    private val thumbDir = java.io.File(app.cacheDir, "session_thumbs")

    /** 還原上次的分頁；沒有可還原的回傳 false。只有目前分頁會立即載入 */
    fun restoreSession(): Boolean {
        val json = runCatching { org.json.JSONObject(sessionFile.readText()) }.getOrNull() ?: return false
        val arr = json.optJSONArray("tabs") ?: return false
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val u = o.optString("url").takeIf { it.startsWith("http") } ?: continue
            val tab = Tab(nextId++, false, webContext, this, settings.value.desktopByDefault, settings.value.identifyAsIPhone).apply {
                restore(u, o.optString("title"), o.optString("history").takeIf { it.isNotEmpty() }?.let(::b64ToBundle))
                lastUsed = o.optLong("used")
            }
            tabs.add(tab)
            // 縮圖在背景執行緒解碼（開 App 時不卡主執行緒）
            val file = java.io.File(thumbDir, "$i.jpg")
            viewModelScope.launch {
                val bmp = withContext(Dispatchers.IO) { android.graphics.BitmapFactory.decodeFile(file.path)?.asImageBitmap() }
                if (tab.thumbnail == null) tab.thumbnail = bmp
            }
        }
        if (tabs.isEmpty()) return false
        select(tabs[json.optInt("current", 0).coerceIn(0, tabs.lastIndex)])
        preloadRecent()
        return true
    }

    /**
     * 重開 App 後，等目前分頁載完、閒下來，在背景先載入最近用過的另一個分頁（約 20～50MB）：
     * 從分頁總覽切過去時已經載好，不必重新載入。只預載一個，控制在 50MB 左右的預算內
     */
    private fun preloadRecent() = viewModelScope.launch {
        kotlinx.coroutines.delay(4000)
        val cur = current ?: return@launch
        while (cur.progress < 100) kotlinx.coroutines.delay(500)
        val am = getApplication<Application>().getSystemService(android.app.ActivityManager::class.java)
        val mem = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        if (mem.lowMemory) return@launch
        tabs.filter { it.pending && it != current }.maxByOrNull { it.lastUsed }?.loadPending(sizeLike = cur.webView)
    }

    // WebView.saveState 的內容存成文字（放進 session.json）；太大的（超過 256KB）不存，還原時只載入網址
    private fun bundleToB64(b: android.os.Bundle): String? {
        val p = android.os.Parcel.obtain()
        return try {
            p.writeBundle(b)
            p.marshall().takeIf { it.size <= 256 * 1024 }?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
        } catch (e: Exception) { null } finally { p.recycle() }
    }

    private fun b64ToBundle(s: String): android.os.Bundle? {
        val p = android.os.Parcel.obtain()
        return try {
            val bytes = android.util.Base64.decode(s, android.util.Base64.NO_WRAP)
            p.unmarshall(bytes, 0, bytes.size)
            p.setDataPosition(0)
            p.readBundle(javaClass.classLoader)
        } catch (e: Exception) { null } finally { p.recycle() }
    }

    /** 分頁總覽拖曳排序：在同模式清單中把 from 移到 to 的位置。 */
    fun move(from: Tab, to: Tab) {
        val a = tabs.indexOf(from)
        val b = tabs.indexOf(to)
        if (a < 0 || b < 0 || a == b) return
        tabs.removeAt(a)
        tabs.add(b, from)
    }

    /** 使用者在操作（智適應刷新率用，由 MainActivity 設定） */
    var onUserActivity: (() -> Unit)? = null

    override fun onScroll(tab: Tab, dy: Int, atBottom: Boolean) {
        if (tab != current) return
        onUserActivity?.invoke()
        // 捲到頂端一定展開
        if (tab.webView.scrollY <= 0) { collapsed = false; return }
        // 跟著捲動走（Chromium BrowserControlsOffsetManager 的做法）：網址列收合程度＝累積捲動量，慣性捲動也跟著；
        // 手指放開、捲動停下時停在半路，就以彈簧動畫彈到較近的一端
        collapseTarget = null
        collapse = (collapse + dy / collapseDistance).coerceIn(0f, 1f)
        if (collapse == 1f) settled = true else if (collapse == 0f) settled = false
        settleJob?.cancel()
        settleJob = viewModelScope.launch {
            delay(120)
            while (tab.touching) delay(50)
            if (collapse > 0f && collapse < 1f) collapsed = collapse > 0.5f
        }
    }

    /** 網址列狀態：true＝收合成小膠囊。設定時由 UI 以彈簧動畫把 [collapse] 帶到 0／1 */
    var collapsed: Boolean
        get() = settled
        set(v) { settled = v; collapseTarget = if (v) 1f else 0f }
    private var settled by mutableStateOf(false)
    /** UI 動畫的目標；null＝正跟著捲動走，不跑動畫 */
    var collapseTarget by mutableStateOf<Float?>(0f)
        private set
    private var settleJob: kotlinx.coroutines.Job? = null

    override fun onVisited(tab: Tab, url: String, title: String) {
        if (tab == current && url.startsWith("http")) suggestApp(tab, url)
        if (tab.incognito || !url.startsWith("http")) return
        val host = Uri.parse(url).host?.removePrefix("www.") ?: return
        viewModelScope.launch { db.history().insert(HistoryEntry(url = url, host = host, title = title, visitedAt = System.currentTimeMillis())) }
    }

    override fun onIcon(host: String, iconUrl: String) {
        if (icons.value[host] == iconUrl) return
        viewModelScope.launch { db.icons().upsert(SiteIcon(host, iconUrl)) }
    }

    /** 常用網站：最常造訪在前，不足時以預設網站補齊。 */
    /**
     * 常用網站（Safari 的「喜好項目」）：只有使用者自己加入／編輯過的，加上預設網站；不會因為常去就自動加進來。
     * 刪掉的網域不出現，同網域只留一個。傳入 settings（呼叫端用 collectAsState 讀到的值），設定改變時畫面會跟著更新。
     */
    fun topSites(n: Int = 8, s: Settings = settings.value): List<Visit> {
        return (s.favorites.map { (t, u) -> Visit(u, t) } + DEFAULT_SITES)
            .filter { favHost(it.url) !in s.hiddenFavorites }
            .distinctBy { favHost(it.url) }.take(n)
    }

    private fun favHost(url: String) = Uri.parse(url).host?.removePrefix("www.").orEmpty()

    /**
     * 加入或編輯常用網站：old＝原本那筆的網址（加入時為 null）。編輯時留在原本的位置，加入的排在自己加的最後面；
     * 換了網域時原網域從清單隱藏（不然它會以「常去的網站」身分又冒出來），新網域如果之前刪過就恢復。
     */
    fun saveFavorite(old: String?, title: String, url: String) = updateSettings { s ->
        val at = s.favorites.indexOfFirst { it.second == old }
        val others = s.favorites.filter { (_, u) -> u != old && favHost(u) != favHost(url) }.toMutableList()
        others.add(if (at >= 0) at.coerceAtMost(others.size) else others.size, title to url)
        var hidden = s.hiddenFavorites - favHost(url)
        if (old != null && favHost(old) != favHost(url)) hidden = hidden + favHost(old)
        s.copy(favorites = others, hiddenFavorites = hidden)
    }

    /** 刪除常用網站：拿掉自己加的那筆，並記住這個網域不再自動出現 */
    fun removeFavorite(url: String) = updateSettings { s ->
        s.copy(favorites = s.favorites.filter { favHost(it.second) != favHost(url) }, hiddenFavorites = s.hiddenFavorites + favHost(url))
    }

    override fun onDownload(tab: Tab, url: String, userAgent: String, contentDisposition: String?, mime: String?) {
        // 擴充功能套件（Firefox .xpi、Chrome .crx）：直接問要不要安裝，不存成檔案
        val name = android.webkit.URLUtil.guessFileName(url, contentDisposition, mime)
        if (!tab.incognito && (mime == "application/x-xpinstall" || mime == "application/x-chrome-extension" || name.endsWith(".xpi") || name.endsWith(".crx"))) {
            offeredExt.remove(url)
            onExtensionOffer(tab, url)
            if (extOffer?.second == url) return
        }
        val task = download(tab, url, android.webkit.URLUtil.guessFileName(url, contentDisposition, mime), userAgent)
        showToast(if (task != null) t("開始下載「%1\$s」", task.name) else t("無法下載此類型的檔案"))
        // 只為了下載而開的分頁（沒有任何頁面內容）直接關掉，不留空白分頁
        if (!tab.canGoBack && tab.title.isBlank() && tabs.size > 1) viewModelScope.launch { delay(300); close(tab) }
    }

    override fun onLongPress(tab: Tab, hit: HitResult) { longPress = tab to hit }

    override fun onCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
        if (view == null) customCallback?.onCustomViewHidden()
        customView = view
        customCallback = callback
    }

    fun exitFullscreen() = onCustomView(null, null)

    /** 長按連結的預覽用 WebView：不可互動、與該分頁同一個 Profile（無痕不留紀錄）。用完由呼叫端 destroy。 */
    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    fun previewWebView(url: String, incognito: Boolean): WebView = WebView(webContext).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        if (incognito && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) WebViewCompat.setProfile(this, INCOGNITO_PROFILE)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        setOnTouchListener { _, _ -> true }
        loadUrl(url)
    }

    override fun searchPrefix() = settings.value.engine.prefix

    fun showToast(text: String) {
        toast = text
        viewModelScope.launch {
            delay(2200)
            if (toast == text) toast = null
        }
    }

    /**
     * 以圖搜尋（Google 智慧鏡頭）：圖片縮到長邊 1000px 後以 multipart 上傳，
     * 取得轉址後的結果網址再交給分頁開啟（不經過中繼頁，返回鍵不會重複上傳）。
     * Cookie 走該分頁自己的設定檔（無痕分頁與一般分頁分開）：上傳產生的工作階段 Cookie 要寫回 WebView，結果頁才讀得到圖片。
     */
    fun searchByImage(tab: Tab, uri: Uri) {
        viewModelScope.launch {
            val ua = tab.webView.settings.userAgentString
            val cm = Downloads.cookieManager(tab.incognito)
            val endpoint = "https://lens.google.com/v3/upload?ep=ccm&s=4&st=${System.currentTimeMillis()}"
            val cookies = cm.getCookie(endpoint)
            val result = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val jpeg = downscaleJpeg(uri)
                    val boundary = "----liquid${System.nanoTime()}"
                    val conn = java.net.URL(endpoint)
                        .openConnection() as java.net.HttpURLConnection
                    conn.instanceFollowRedirects = false
                    conn.doOutput = true
                    conn.setRequestProperty("User-Agent", ua)
                    if (cookies != null) conn.setRequestProperty("Cookie", cookies)
                    conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    conn.outputStream.use {
                        it.write("--$boundary\r\nContent-Disposition: form-data; name=\"encoded_image\"; filename=\"image.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
                        it.write(jpeg)
                        it.write("\r\n--$boundary--\r\n".toByteArray())
                    }
                    val location = conn.getHeaderField("Location")
                    conn.headerFields["Set-Cookie"]?.forEach { cm.setCookie(endpoint, it) }
                    conn.disconnect()
                    location ?: error("HTTP ${conn.responseCode}")
                }
            }
            result.onSuccess { tab.load(it) }.onFailure { showToast(t("圖片搜尋失敗")) }
        }
    }

    private fun downscaleJpeg(uri: Uri): ByteArray {
        val resolver = getApplication<Application>().contentResolver
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1000) sample *= 2
        val raw = resolver.openInputStream(uri).use {
            android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error(t("無法解碼圖片"))
        val scale = minOf(1f, 1000f / maxOf(raw.width, raw.height))
        val bmp = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt(), (raw.height * scale).toInt(), true) else raw
        return java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    fun saveImage(tab: Tab, url: String) {
        val task = download(tab, url, android.webkit.URLUtil.guessFileName(url, null, null))
        showToast(if (task != null) t("已開始儲存圖片") else t("無法儲存這張圖片"))
    }

    /** 交給內建下載引擎；帶上分頁的 User-Agent、Cookie 與 Referer（不少網站沒有這些會拒絕下載） */
    private fun download(tab: Tab, url: String, name: String, ua: String = tab.webView.settings.userAgentString) =
        DownloadEngine.enqueue(
            url, name,
            mapOf(
                "User-Agent" to ua,
                "Cookie" to Downloads.cookieManager(tab.incognito).getCookie(url).orEmpty(),
                "Referer" to tab.url.takeIf { it.startsWith("http") }.orEmpty(),
            ),
        )

    private val translateScript: String by lazy { getApplication<Application>().assets.open("translate.js").bufferedReader().use { it.readText() } }

    /** 同時送出的翻譯批次：AI 引擎 4 批、免費引擎 2 批（避免被限流） */
    private val translateGate = kotlinx.coroutines.sync.Semaphore(4)
    private val freeGate = kotlinx.coroutines.sync.Semaphore(2)
    /** AI 引擎失敗（金鑰錯誤、額度用完）後這個頁面改用免費引擎，並只提示一次 */
    private var aiFailedFor: Tab? = null

    /** 渲染程序沒了：WebView 已不能再用，在原位置換成新分頁並重新載入同一個網址 */
    override fun onRendererGone(tab: Tab) {
        com.liquid.browser.BugLog.auto("renderer", "網頁渲染程序被結束或崩潰：${tab.url}")
        viewModelScope.launch {
            val url = tab.url
            val wasCurrent = tab == current
            val at = tabs.indexOf(tab)
            if (at < 0) return@launch
            val fresh = Tab(nextId++, tab.incognito, webContext, this@BrowserViewModel, settings.value.desktopByDefault, settings.value.identifyAsIPhone)
            tabs[at] = fresh
            (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
            runCatching { tab.webView.destroy() }
            // 保留標題、縮圖與來源頁；其他分頁指向舊分頁的來源頁改指向新的
            fresh.thumbnail = tab.thumbnail
            fresh.opener = tab.opener
            tabs.forEach { if (it.opener == tab) it.opener = fresh }
            if (wasCurrent) {
                current = fresh
                fresh.setForeground(true)
                if (url.isNotEmpty()) fresh.load(url)
                showToast(t("網頁已重新載入"))
            } else if (url.isNotEmpty()) fresh.restore(url, tab.title) // 背景分頁：切過去時才重新載入
        }
    }

    override fun adBlockFor(pageHost: String): Boolean = settings.value.let { it.adBlock && baseDomain(pageHost) !in it.adAllow }

    /** 密碼列（鍵盤上方）：這個網站已存的帳號，或註冊表單時建議高強度密碼 */
    data class PwOffer(val tab: Tab, val origin: String, val accounts: List<Credential>, val isNew: Boolean)
    var pwOffer by mutableStateOf<PwOffer?>(null)

    /** 登入送出後詢問要不要儲存的帳密 */
    data class PwSave(val tab: Tab, val cred: Credential, val update: Boolean)
    var pwSave by mutableStateOf<PwSave?>(null)

    override fun onPasswordField(tab: Tab, origin: String?, isNew: Boolean) {
        if (origin == null) { pwOffer = null; return }
        viewModelScope.launch {
            val accounts = withContext(Dispatchers.IO) { PasswordVault.forOrigin(origin) }
            pwOffer = if (accounts.isNotEmpty() || isNew) PwOffer(tab, origin, accounts, isNew) else null
        }
    }

    override fun onPasswordSubmit(tab: Tab, origin: String, user: String, pass: String) {
        // 無痕分頁不留密碼；已經存過一模一樣的就不再問
        if (tab.incognito) return
        viewModelScope.launch {
            val existing = withContext(Dispatchers.IO) { PasswordVault.forOrigin(origin) }.firstOrNull { it.user == user }
            if (existing?.pass == pass) return@launch
            pwSave = PwSave(tab, Credential(origin, user, pass), update = existing != null)
            delay(12_000)
            if (pwSave?.cred?.pass == pass) pwSave = null
        }
    }

    /** 填入已存的密碼：先過指紋／螢幕鎖驗證 */
    fun usePassword(activity: android.app.Activity, offer: PwOffer, c: Credential) =
        Auth.request(activity, t("填入 %1\$s 的密碼", c.user.ifBlank { Uri.parse(c.origin).host.orEmpty() }), onFail = { showToast(it) }) {
            offer.tab.fillPassword(c.user, c.pass)
            pwOffer = null
        }

    /** 註冊表單：填入高強度密碼（新密碼與確認密碼一起），登入送出時會再問要不要儲存 */
    fun useStrongPassword(offer: PwOffer) {
        offer.tab.fillNewPassword(PasswordVault.strongPassword())
        pwOffer = null
        showToast(t("已填入高強度密碼"))
    }

    fun savePassword(save: PwSave) {
        pwSave = null
        viewModelScope.launch {
            withContext(Dispatchers.IO) { PasswordVault.save(save.cred) }
            showToast(if (save.update) t("已更新密碼") else t("已儲存密碼"))
        }
    }

    override fun cleanLinks(tab: Tab): Boolean = tab.incognito || settings.value.cleanLinksEverywhere

    override fun allowThirdPartyCookies(): Boolean = !settings.value.blockThirdPartyCookies
    override fun shields(): Boolean = settings.value.shields

    override fun blockRedirects() = settings.value.blockRedirects

    override fun onRedirectBlocked(tab: Tab, url: String) {
        if (tab == current) showToast(t("已阻止自動跳轉到 %1\$s", Uri.parse(url).host))
    }

    override fun onAdClickBlocked(tab: Tab, url: String) {
        showToast(t("已阻擋廣告跳轉"))
        tab.unblockLastTap()
        // 彈出視窗一開就是廣告：直接關掉這個新分頁，回到原頁面
        val opener = tab.opener?.takeIf { it in tabs }
        if (opener != null && (tab.isBlank || tab.url == POPUP_PENDING)) { close(tab); select(opener) }
    }

    /** 全螢幕控制列的下載鈕（串流影片）：下載偵測到的第一部影片的最高畫質 */
    override fun onDownloadBest(tab: Tab) {
        viewModelScope.launch {
            val best = mediaOptions(tab).firstOrNull()
            if (best != null) downloadSniffed(tab, best.url) else showToast(t("這部影片無法下載"))
        }
    }

    override fun onPopupAd(tab: Tab, url: String) {
        showToast(t("已擋下彈出廣告"))
        val opener = tab.opener?.takeIf { it in tabs }
        if (tab in tabs) close(tab)
        opener?.let { select(it); it.unblockLastTap() }
    }

    override fun onPopupBlocked(tab: Tab) {
        if (tab == current) showToast(t("已阻擋彈出視窗"))
        tab.unblockLastTap()
    }

    /** 用原生播放器播放偵測到的影片（繞過網頁上擋住控制列的廣告層，可全螢幕、手勢、倍速） */
    fun playSniffed(tab: Tab, src: String) = openPlayer(
        PlayerRequest(
            url = src, title = tab.title.ifBlank { tab.domain },
            referer = tab.url.takeIf { it.startsWith("http") },
            userAgent = tab.webView.settings.userAgentString,
            cookies = Downloads.cookieManager(tab.incognito).getCookie(src),
        ),
    )

    // 彈出視窗開的新分頁：網址要等 WebView 開始載入才知道，先標成 about:blank，等待期間顯示空白網頁而不是首頁（否則會先閃一下首頁）
    // 彈窗先在背景開：第一個網址確定不是廣告才切過去（onPopupReady），是廣告就直接關掉，畫面不會閃一下白頁或廣告
    override fun onPopupReady(tab: Tab) { if (tab in tabs && tab.opener == current) select(tab) }

    override fun onPopup(opener: Tab): Tab = newTab(incognito = opener.incognito, background = true).also { it.opener = opener; it.url = POPUP_PENDING }

    /** 網頁沒有上一頁時的返回：彈出視窗開的分頁 → 關掉回到來源頁（原頁面還在，不必重新載入）；其他 → 回到首頁 */
    fun backFromFirstPage(tab: Tab) {
        val opener = tab.opener?.takeIf { it in tabs }
        if (opener != null) { close(tab); select(opener) } else newTab(incognito = tab.incognito)
    }

    /** 單一網站是否阻擋廣告（網站設定的開關） */
    fun setAdBlockForSite(host: String, on: Boolean) = updateSettings { s ->
        val d = baseDomain(host)
        s.copy(adAllow = if (on) s.adAllow - d else s.adAllow + d)
    }

    /** 夜間（22:00～次日 08:00，依手機時間） */
    private fun isNight() = java.time.LocalTime.now().hour.let { it >= 22 || it < 8 }

    /** 實際使用的引擎：開了「夜間優先 DeepSeek」且有百煉金鑰時，夜間改用 DeepSeek；其餘依手動選擇 */
    fun activeTranslateEngine(): TranslateEngine {
        val s = settings.value
        val nightSwitch = s.nightDeepSeek && isNight() && !s.translateEngine.isFree &&
            !s.translateKeys[TranslateEngine.DeepSeek.keyOwner].isNullOrBlank()
        return if (nightSwitch) TranslateEngine.DeepSeek else s.translateEngine
    }

    override fun onReapplyTranslation(tab: Tab) { if (!tab.translated && Account.signedIn) toggleTranslate(tab) }

    /** 打開用戶中心（BrowserScreen 看這個計數）：要登入才能用的功能（AI 翻譯、安裝擴充）未登入時，或設定頁的「用戶中心」 */
    var userCenterAsk by mutableStateOf(0)
        private set

    private fun requireLogin(what: String): Boolean {
        if (Account.signedIn) return true
        showToast(t("登入後才能%1\$s", t(what)))
        userCenterAsk++
        return false
    }

    fun openUserCenter() { userCenterAsk++ }

    fun toggleTranslate(tab: Tab) {
        if (tab.translated) tab.showOriginal()
        else if (requireLogin("使用 AI 翻譯")) {
            aiFailedFor = null
            // 設定改過目標語言：先丟掉頁面上舊語言的譯文
            val target = settings.value.translateTarget.name
            if (tab.trTarget != null && tab.trTarget != target) tab.resetTranslate()
            tab.trTarget = target
            if (activeTranslateEngine() != settings.value.translateEngine) showToast(t("夜間 DeepSeek 半價，本次改用 DeepSeek 翻譯"))
            tab.startTranslate(translateScript)
        }
    }

    /** 翻譯快取：翻過的原文不再花 API 額度 */
    private val translationCache by lazy { TranslationCache(getApplication()) }

    fun clearTranslationCache() = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { translationCache.clear() }

    override fun onTranslateBatch(tab: Tab, id: Int, texts: List<String>) {
        viewModelScope.launch {
            translateTexts(tab, texts).onSuccess { tr ->
                tab.webView.evaluateJavascript("window.__lqTr&&window.__lqTr.apply($id, ${org.json.JSONArray(tr)});", null)
            }.onFailure { e ->
                tab.webView.evaluateJavascript("window.__lqTr&&window.__lqTr.fail($id);", null)
                showToast(t("翻譯失敗：%1\$s", e.message))
            }
        }
    }

    override fun onSubtitleBatch(tab: Tab, id: Int, texts: List<String>) {
        viewModelScope.launch {
            translateTexts(tab, texts).onSuccess { tr ->
                tab.webView.evaluateJavascript("window.__lqYt&&window.__lqYt.apply($id, ${org.json.JSONArray(tr)});", null)
            }.onFailure { e ->
                tab.webView.evaluateJavascript("window.__lqYt&&window.__lqYt.fail($id);", null)
                com.liquid.browser.BugLog.event("translate", "字幕翻譯失敗：${e.message}")
                if (tab == current) showToast(t("翻譯失敗：%1\$s", e.message))
            }
        }
    }

    override fun onNoCaptions(tab: Tab) {
        if (tab == current) showToast(t("這部影片沒有字幕，或請先按播放器的 CC 打開字幕"))
    }

    fun toggleYouTubeSubs(tab: Tab) {
        val on = !tab.ytSubs
        if (on && !requireLogin("使用 AI 翻譯")) return
        tab.setYouTubeSubs(on)
        if (on) showToast(t("字幕翻譯已開啟"))
    }

    /** 翻譯一批文字：先查快取，AI 引擎失敗時這個分頁改用免費引擎（只提示一次） */
    private suspend fun translateTexts(tab: Tab, texts: List<String>): Result<List<String>> {
            val s = settings.value
            val engine = if (aiFailedFor == tab) TranslateEngine.Tencent else activeTranslateEngine()
            val target = s.translateTarget
            return runCatching {
                // 先查快取；只把沒翻過、且不重複的文字送出去
                val cached = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { translationCache.get(target.google, texts) }
                val todo = texts.filter { it !in cached }.distinct()
                val fresh = if (todo.isEmpty()) emptyMap() else {
                    val tr = (if (engine.isFree) freeGate else translateGate).withPermit {
                        runCatching { Translator.translate(todo, engine, s.translateKeys[engine.keyOwner].orEmpty(), s.translateModels[engine].orEmpty(), target) }
                            .recoverCatching { e ->
                                if (engine == TranslateEngine.Tencent) throw e
                                com.liquid.browser.BugLog.event("translate", "$engine 失敗，改用免費翻譯：${e.message}")
                                if (aiFailedFor != tab) { aiFailedFor = tab; showToast(t("%1\$s，改用免費翻譯", e.message)) }
                                Translator.translate(todo, TranslateEngine.Tencent, "", "", target)
                            }.getOrThrow()
                    }
                    todo.zip(tr).toMap().also { m ->
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { translationCache.put(target.google, m) }
                    }
                }
                texts.map { cached[it] ?: fresh[it] ?: it }
            }
    }

    /** 嗅探到的影片：用分頁標題當檔名 */
    fun downloadSniffed(tab: Tab, src: String) = onVideoDownload(tab, src, tab.title.ifBlank { tab.domain })

    /** 分析偵測到的影片：每個畫質與大小一個選項（帶網頁的 Cookie、Referer，跟網頁播放時一樣） */
    suspend fun mediaOptions(tab: Tab): List<MediaOption> {
        // 排序：網頁上 <video> 由上到下對得到網址的先列，其餘依偵測到的先後
        val dom = tab.videoSourcesInOrder()
        val sniffed = tab.media.toList()
        val list = dom.filter { it in sniffed || it.startsWith("http") }.distinct() + sniffed.filter { it !in dom }
        val headers = mapOf(
            "User-Agent" to tab.webView.settings.userAgentString,
            "Referer" to tab.url.takeIf { it.startsWith("http") }.orEmpty(),
            "Cookie" to (list.firstOrNull()?.let { Downloads.cookieManager(tab.incognito).getCookie(it) }.orEmpty()),
        )
        return MediaProbe.options(list, headers)
    }

    override fun onVideoDownload(tab: Tab, src: String, title: String) = downloadVideo(
        PlayerRequest(
            url = src, title = title,
            referer = tab.url.takeIf { it.startsWith("http") },
            userAgent = tab.webView.settings.userAgentString,
            cookies = Downloads.cookieManager(tab.incognito).getCookie(src),
        ),
    )

    /** 檔名：標題是網址時只取最後一段；只去掉已知的影片副檔名（「hls.js demo」這種標題要原樣保留） */
    private fun videoBaseName(title: String): String {
        val t = if ("://" in title || title.startsWith("/")) title.substringAfterLast('/') else title
        val ext = t.substringAfterLast('.', "").lowercase()
        return (if (ext in setOf("mp4", "webm", "mkv", "m4v", "mov", "m3u8", "ts", "flv")) t.substringBeforeLast('.') else t).trim().ifBlank { "video" }
    }

    /** 原生播放器的「下載影片」：MP4 等直接多連線下載，m3u8 走 HLS 片段下載 */
    fun downloadVideo(req: PlayerRequest) {
        val path = Uri.parse(req.url).lastPathSegment.orEmpty()
        val ext = path.substringAfterLast('.', "").lowercase().takeIf { it in setOf("mp4", "webm", "mkv", "m4v", "m3u8") } ?: "mp4"
        val task = DownloadEngine.enqueue(
            req.url, "${videoBaseName(req.title)}.$ext",
            mapOf("User-Agent" to req.userAgent, "Cookie" to req.cookies.orEmpty(), "Referer" to req.referer.orEmpty()),
        )
        showToast(if (task != null) t("開始下載影片「%1\$s」", task.name) else t("這段影片無法下載"))
    }

    fun isBookmarked(url: String): Flow<Boolean> = db.bookmarks().isBookmarked(url)

    fun toggleBookmark(tab: Tab) = viewModelScope.launch {
        val dao = db.bookmarks()
        if (dao.isBookmarked(tab.url).first()) {
            dao.deleteUrl(tab.url)
            showToast(t("已移除書籤"))
        } else {
            dao.insert(Bookmark(title = tab.title.ifBlank { tab.domain }, url = tab.url, position = dao.nextPosition(null)))
            showToast(t("已加入書籤"))
        }
    }

    fun addToReadingList(url: String, title: String, thumbnail: String?) = viewModelScope.launch {
        db.reading().upsert(ReadingItem(url, title, thumbnail, System.currentTimeMillis()))
        showToast(t("已加入閱讀列表"))
    }

    fun addToReadingList(tab: Tab) = tab.pageMeta { title, image -> addToReadingList(tab.url, title, image) }

    fun updateSettings(transform: (Settings) -> Settings) = viewModelScope.launch { settingsStore.update(transform) }

    /** 清除瀏覽資料：歷史紀錄、Cookie 與網站資料、快取。 */
    fun clearData(history: Boolean, cookies: Boolean, cache: Boolean) = viewModelScope.launch {
        if (history) db.history().deleteSince(0)
        if (cookies) {
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
        }
        if (cache) tabs.firstOrNull()?.webView?.clearCache(true)
        showToast(t("已清除瀏覽資料"))
    }

    /** 清除單一網站的 Cookie（把該網域的每個 Cookie 設為過期）。 */
    fun clearSiteCookies(tab: Tab) {
        val cm = Downloads.cookieManager(tab.incognito)
        val host = Uri.parse(tab.url).host ?: return
        cm.getCookie(tab.url)?.split(';')?.map { it.substringBefore('=').trim() }?.forEach { name ->
            cm.setCookie(tab.url, "$name=; Max-Age=0; Path=/")
            cm.setCookie(tab.url, "$name=; Max-Age=0; Path=/; Domain=$host")
        }
        cm.flush()
        showToast(t("已清除 %1\$s 的 Cookie", host))
    }

    suspend fun matchHistory(q: String) = db.history().match(q)
    suspend fun matchBookmarks(q: String): List<Bookmark> = db.bookmarks().search(q)

    override fun onCleared() {
        DownloadEngine.videoOpener = null
        player?.release()
        tabs.forEach { it.webView.destroy() }
    }
}

private val DEFAULT_SITES = listOf(
    Visit("https://www.google.com", "Google"),
    Visit("https://www.youtube.com", "YouTube"),
    Visit("https://www.wikipedia.org", "Wikipedia"),
    Visit("https://github.com", "GitHub"),
    Visit("https://www.reddit.com", "Reddit"),
    Visit("https://news.ycombinator.com", "Hacker News"),
    Visit("https://www.bbc.com", "BBC"),
    Visit("https://maps.google.com", "Maps"),
)

/** 彈出視窗新分頁在第一個網址出現前的佔位網址 */
const val POPUP_PENDING = "about:blank"
