package com.liquid.browser.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.view.HapticFeedbackConstants
import org.json.JSONObject
import com.liquid.browser.player.isMediaUrl
import android.webkit.WebSettings
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.webkit.ProfileStore
import androidx.webkit.ScriptHandler
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

const val INCOGNITO_PROFILE = "incognito"

/** 長按網頁元素的結果：連結、圖片或圖片連結；x/y 為 WebView 內的觸控位置。 */
@Immutable
data class HitResult(val link: String?, val image: String?, val x: Float, val y: Float)

/** 單一分頁：擁有自己的 WebView 與可觀察的頁面狀態。 */
@Stable
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
class Tab(
    val id: Long,
    val incognito: Boolean,
    context: Context,
    private val listener: Listener,
    desktopByDefault: Boolean = false,
    identifyAsIPhone: Boolean = true,
) {
    interface Listener {
        fun onScroll(tab: Tab, dy: Int, atBottom: Boolean)
        fun onVisited(tab: Tab, url: String, title: String)
        fun onIcon(host: String, iconUrl: String)
        fun onDownload(tab: Tab, url: String, userAgent: String, contentDisposition: String?, mime: String?)
        fun onLongPress(tab: Tab, hit: HitResult)
        /** 網頁影片全螢幕：view 為 null 表示離開全螢幕 */
        fun onCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?)
        fun searchPrefix(): String
        /** 影片直連網址（mp4／m3u8／mpd）：回傳 true 表示已交給原生播放器，不在網頁中開啟 */
        fun onMediaUrl(tab: Tab, url: String, typed: Boolean = false): Boolean
        /** 網頁中 <video> 的「用原生播放器播放」按鈕；from 為影片在視窗中的位置（px） */
        fun onNativeVideo(tab: Tab, src: String, title: String, from: androidx.compose.ui.geometry.Rect?, startMs: Long)
        /** 網頁影片按全螢幕時改用內建播放器 */
        fun nativeFullscreen(): Boolean = false
        /** 網頁渲染程序被系統結束（記憶體不足）或崩潰：由 ViewModel 重建分頁，App 不跟著閃退 */
        fun onRendererGone(tab: Tab)

        /** 這個網站是否要阻擋廣告（總開關開著且不在例外清單） */
        fun adBlockFor(pageHost: String): Boolean

        /** 登入表單的帳號／密碼欄被點到（origin 為 null＝離開欄位）；isNew＝註冊表單（可建議高強度密碼） */
        fun onPasswordField(tab: Tab, origin: String?, isNew: Boolean)

        /** 登入表單送出：詢問要不要儲存這組帳密 */
        fun onPasswordSubmit(tab: Tab, origin: String, user: String, pass: String)

        /** 之前翻譯過的網頁重新載入完成：再翻一次（走快取） */
        fun onReapplyTranslation(tab: Tab)

        /** 這個分頁要不要拿掉網址上的追蹤參數（無痕分頁一律要；設定可改成所有瀏覽） */
        fun cleanLinks(tab: Tab): Boolean

        /** 是否允許第三方 Cookie（設定；預設不允許） */
        fun allowThirdPartyCookies(): Boolean
        /** Brave 式防追蹤（防指紋、跳過追蹤跳轉頁、HTTPS 升級）是否開啟 */
        fun shields(): Boolean

        /** 是否阻止未經點擊的跨站跳轉 */
        fun blockRedirects(): Boolean

        fun onRedirectBlocked(tab: Tab, url: String)

        /** 點擊後被擋下的廣告跳轉（目標在廣告清單裡） */
        fun onAdClickBlocked(tab: Tab, url: String)

        /** 全螢幕控制列按下載（串流影片，網頁拿不到網址）：下載偵測到的最高畫質 */
        fun onDownloadBest(tab: Tab)

        /** 點擊時趁機開的彈出視窗被擋下 */
        fun onPopupBlocked(tab: Tab)
        /** 點擊開出來的彈窗第一頁是廣告網站：關掉這個分頁、回到原本的網頁 */
        fun onPopupAd(tab: Tab, url: String)
        /** 彈窗的第一個網址確定不是廣告：切到前景 */
        fun onPopupReady(tab: Tab) {}

        /** 網頁點擊開的新視窗（window.open／target=_blank）：開成新分頁並記住來源頁，回傳新分頁 */
        fun onPopup(opener: Tab): Tab

        /** 原地翻譯：一批文字要翻譯 */
        fun onTranslateBatch(tab: Tab, id: Int, texts: List<String>)

        /** 網頁影片旁的「下載」鈕 */
        fun onVideoDownload(tab: Tab, src: String, title: String)
        /** 注入每個頁面的 JS（assets/gesture_layer.js＋video_detect.js） */
        fun injectedScript(): String
        /** Android 17 區域網路權限不足而無法載入（ERR_LOCAL_NETWORK_PERMISSION_MISSING） */
        fun onLocalNetworkNeeded(tab: Tab)
        /** YouTube 字幕翻譯：一批字幕要翻譯（回填 __lqYt.apply） */
        fun onSubtitleBatch(tab: Tab, id: Int, texts: List<String>)
        /** 開了字幕翻譯，但這部影片沒有字幕可用 */
        fun onNoCaptions(tab: Tab)
        /** App 專用連結（taobao://、intent://…）；gesture＝使用者點擊觸發 */
        fun onAppLink(tab: Tab, url: String, gesture: Boolean)
        /** 擴充功能：開到商店頁面或 .user.js／.xpi／.crx，詢問要不要安裝 */
        fun onExtensionOffer(tab: Tab, url: String)
    }

    val webView = WebView(context)
    var url by mutableStateOf("")
    var title by mutableStateOf("")
    var progress by mutableIntStateOf(100)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var favicon by mutableStateOf<ImageBitmap?>(null)
    var thumbnail by mutableStateOf<ImageBitmap?>(null)

    /** 狀態列顏色：網頁 theme-color，沒有則為頁面背景色；載入完成前為 null */
    var themeColor by mutableStateOf<Color?>(null)

    /** 要求桌面版網站 */
    var desktop by mutableStateOf(desktopByDefault)
        private set

    /** 讓網站識別為 iPhone（行動版）／Mac（桌面版）Safari */
    var asIPhone = identifyAsIPhone
        private set
    private var iosShim: ScriptHandler? = null
    private var extHandler: ScriptHandler? = null
    private var shieldsHandler: ScriptHandler? = null
    /** HTTPS 升級後的網址（連不上時退回 http 用） */
    private var upgraded: String? = null
    /** 網頁點擊開出來的彈窗分頁：第一個網址是廣告網站就關掉（onPageStarted） */
    var popupCheck = false

    /** 最近一次點在網頁影片畫面上的時間（video_detect.js 的 vtap） */
    private var videoTapAt = 0L

    /** 防指紋腳本（Brave 式防追蹤開關改變時重新註冊；無痕分頁也套用） */
    fun applyShields(on: Boolean) {
        shieldsHandler?.remove()
        shieldsHandler = null
        if (on && !legacyInject) shieldsHandler = WebViewCompat.addDocumentStartJavaScript(webView, Shields.fingerprintJs, setOf("*"))
    }

    /** 擴充功能的開頁腳本（安裝、移除、開關後重新註冊）。無痕分頁不跑擴充，跟 Firefox 的預設一樣 */
    fun applyExtensions() {
        extHandler?.remove()
        extHandler = null
        if (incognito || legacyInject) return
        val js = Extensions.documentStartScript() ?: return
        extHandler = WebViewCompat.addDocumentStartJavaScript(webView, js, setOf("*"))
    }
    /** 目前實際套用的是否為 Apple 身分（個別網站例外時與 asIPhone 不同） */
    private var appliedIos: Boolean? = null

    /** 這個網址要不要用 Apple 身分：asIPhone 開著、且不在例外清單（[NO_IOS_HOSTS]） */
    private fun iosFor(url: String?): Boolean {
        if (!asIPhone) return false
        val host = url?.let { Uri.parse(it).host }.orEmpty()
        return NO_IOS_HOSTS.none { host == it || host.endsWith(".$it") }
    }

    /** 換到身分不同的網站前先切換（UA 在請求送出前就要對）；回傳是否有切換 */
    private fun switchIdentityFor(url: String): Boolean {
        if (iosFor(url) == appliedIos) return false
        applyIdentity(reload = false, forUrl = url)
        return true
    }

    /** 文字大小百分比（WebSettings.textZoom） */
    var textZoom by mutableIntStateOf(100)
        private set
    var javaScript by mutableStateOf(true)
        private set
    var readerOn by mutableStateOf(false)
        private set

    // 在頁面上尋找
    var findMatches by mutableIntStateOf(0)
        private set
    var findIndex by mutableIntStateOf(0)
        private set

    /** 離開頁面前的畫面（網址 → 縮圖），給邊緣滑動返回時顯示前一頁；最多保留 6 張 */
    private val pageShots = object : LinkedHashMap<String, ImageBitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 6
    }

    private var lastRecorded: String? = null

    /** 主頁面載入失敗（錯誤頁）：不記入歷史紀錄 */
    private var loadFailed = false

    /** 目前畫面上實際顯示的頁面網址（load() 會先改 url，所以另外記錄） */
    private var shownUrl: String? = null
    private var touchX = 0f
    /** 手指還按在網頁上（網址列等放開才彈到定位） */
    var touching = false
        private set
    private var touchY = 0f

    val isBlank get() = url.isEmpty()

    /** 原地翻譯：是否顯示譯文、已送出／已完成的段數（顯示進度用） */
    var translated by androidx.compose.runtime.mutableStateOf(false)
    /** 這個頁面上次翻成的語言（換語言時要先丟掉舊譯文） */
    var trTarget: String? = null
    var trSent by androidx.compose.runtime.mutableIntStateOf(0)
    var trDone by androidx.compose.runtime.mutableIntStateOf(0)

    /** 開始原地翻譯（腳本只注入一次；切回原文後再翻會直接套用已有譯文） */
    fun startTranslate(script: String) {
        translated = true
        url.takeIf { it.startsWith("http") }?.let { translatedUrls += it }
        webView.evaluateJavascript("$script\n;window.__lqTr.start();", null)
    }

    /**
     * 翻譯過的網頁：重新整理、或返回時頁面得重新載入，載完自動再翻一次。
     * 譯文從手機上的翻譯快取直接套用（翻過的文字不會再送出去，不花 API 額度）。按「顯示原文」就移除。
     */
    private val translatedUrls = mutableSetOf<String>()

    /** 換語言前：還原原文並丟掉舊譯文 */
    fun resetTranslate() {
        translated = false
        webView.evaluateJavascript("window.__lqTr&&window.__lqTr.reset();", null)
    }

    /** YouTube 字幕翻譯是否開著（只在 YouTube 頁面有意義；換頁不關，換影片時腳本自己抓新字幕） */
    var ytSubs by androidx.compose.runtime.mutableStateOf(false)
        private set
    val isYouTube get() = pageHost == "youtube.com" || pageHost.endsWith(".youtube.com")

    /** 網頁宣告的語言（小寫，例如 en、zh-tw）；沒宣告或還沒載完為空字串 */
    var pageLang by androidx.compose.runtime.mutableStateOf("")
        private set

    /** 這頁已經是 target 語言（依網頁宣告判斷；沒宣告時視為需要翻譯，翻譯鈕照常出現） */
    fun alreadyIn(target: TargetLang): Boolean {
        val l = pageLang
        if (l.isEmpty()) return false
        return when (target) {
            TargetLang.ZhTW -> l == "zh-tw" || l == "zh-hk" || l == "zh-mo" || l.startsWith("zh-hant")
            TargetLang.ZhCN -> l == "zh" || l == "zh-cn" || l == "zh-sg" || l.startsWith("zh-hans")
            TargetLang.En -> l == "en" || l.startsWith("en-")
            TargetLang.Ja -> l == "ja" || l.startsWith("ja-")
        }
    }

    /** 把帳密填進目前聚焦的登入表單（JSON 字串化，帳密裡的引號、反斜線不會破壞 JS） */
    fun fillPassword(user: String, pass: String) =
        webView.evaluateJavascript("window.__lqPw&&window.__lqPw.fill(${JSONObject.quote(user)},${JSONObject.quote(pass)});", null)

    fun fillNewPassword(pass: String) =
        webView.evaluateJavascript("window.__lqPw&&window.__lqPw.fillNew(${JSONObject.quote(pass)});", null)

    fun setYouTubeSubs(on: Boolean) {
        ytSubs = on
        webView.evaluateJavascript(if (on) "window.__lqYt&&window.__lqYt.start();" else "window.__lqYt&&window.__lqYt.stop();", null)
    }

    fun showOriginal() {
        translated = false
        translatedUrls -= url
        webView.evaluateJavascript("window.__lqTr&&window.__lqTr.restore();", null)
    }

    /** 這個頁面已阻擋的廣告／追蹤請求數 */
    var blockedCount by androidx.compose.runtime.mutableIntStateOf(0)

    /** 目前頁面的網域（網路執行緒也會讀，所以用 volatile 欄位而非 Compose 狀態） */
    @Volatile var pageHost: String = ""
        private set

    /** 這個頁面嗅探到的影片網址（IDM 式「下載此影片」用）；換頁時清空 */
    val media = androidx.compose.runtime.mutableStateListOf<String>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    // about:blank（彈出視窗新分頁還在等第一個網址）不顯示
    val domain get() = if (url == "about:blank") "" else Uri.parse(url).host?.removePrefix("www.") ?: url
    val insecure get() = url.startsWith("http://")

    /** 阻擋已知追蹤器：無痕分頁一律阻擋（Safari 私密瀏覽），其他依設定與網站例外 */
    private fun blocksTrackers(pageHost: String) = incognito || listener.adBlockFor(pageHost)

    /** 第三方 Cookie（嵌在別的網站裡的追蹤 Cookie）：預設封鎖；無痕分頁一律封鎖 */
    fun setThirdPartyCookies(allow: Boolean) =
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, allow && !incognito)

    init {
        setThirdPartyCookies(listener.allowThirdPartyCookies())
        // Compose AndroidView 預設給 WRAP_CONTENT：WebView 會把版面高度當成 0（高度隨內容延展），
        // 導致網頁的 100vh／dvh 變成 0（例如 Google 首頁整個擠在上方）。明確指定 MATCH_PARENT。
        webView.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        // 無痕分頁使用獨立的 WebView Profile：Cookie、快取、儲存空間與一般分頁隔離
        if (incognito && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            ProfileStore.getInstance().getOrCreateProfile(INCOGNITO_PROFILE)
            WebViewCompat.setProfile(webView, INCOGNITO_PROFILE)
        }
        // 返回不重新載入（往返快取）：離開的網頁整頁留在記憶體，返回時直接還原（含捲動位置、已翻好的譯文）；
        // 最多 3 頁、10 分鐘（每頁約數 MB～數十 MB，太多會吃記憶體）
        if (WebViewFeature.isFeatureSupported(WebViewFeature.BACK_FORWARD_CACHE)) {
            @Suppress("RestrictedApi", "UnsafeOptInUsageError")
            runCatching {
                WebSettingsCompat.setBackForwardCacheEnabled(webView.settings, true)
                if (WebViewFeature.isFeatureSupported(WebViewFeature.BACK_FORWARD_CACHE_SETTINGS)) {
                    WebSettingsCompat.getBackForwardCacheSettings(webView.settings).apply {
                        maxPagesInCache = 3
                        timeoutSeconds = 600
                    }
                }
            }
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            // 網頁開新視窗時交給 onCreateWindow 開成新分頁（原頁面不會被換掉，回來不必重新載入）
            setSupportMultipleWindows(true)
        }
        // 不送 X-Requested-With（WebView 預設會附上 App 套件名稱）
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(webView.settings, emptySet())
        }
        applyIdentity(reload = false)
        // 往返快取：返回上一頁時從記憶體直接還原（不重新載入，被廣告帶走再回來時廣告也不會重新出現）
        if (WebViewFeature.isFeatureSupported(WebViewFeature.BACK_FORWARD_CACHE)) {
            runCatching { WebSettingsCompat.setBackForwardCacheEnabled(webView.settings, true) }
        }
        // Google 安全瀏覽：詐騙、惡意軟體網站會先顯示警告頁
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) WebSettingsCompat.setSafeBrowsingEnabled(webView.settings, true)
        // 頁尾留白：在每個頁面內容最後加一段與浮動控制列等高的空白，頁尾才能捲到網址列上方
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, END_SPACER_JS, setOf("*"))
            WebViewCompat.addDocumentStartJavaScript(webView, LIFT_FIXED_JS, setOf("*"))
        }
        webView.webViewClient = object : WebViewClient() {
            // 點連結離開頁面前先留下舊頁面的畫面（onPageStarted 時新頁面已提交、舊畫面已不在），邊緣滑動返回時使用
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // App 專用連結（含 iframe 裡的）：WebView 打不開，交給 ViewModel 開 App 或先詢問
                if (AppLinks.isAppScheme(request.url)) {
                    listener.onAppLink(this@Tab, request.url.toString(), request.hasGesture() && request.isForMainFrame)
                    return true
                }
                if (!request.isForMainFrame) return false
                // 使用者腳本的安裝連結（Greasy Fork 的「安裝」）：不開成一頁原始碼，改問要不要安裝
                if (!incognito && request.url.path.orEmpty().endsWith(".user.js")) {
                    listener.onExtensionOffer(this@Tab, request.url.toString())
                    return true
                }
                // 跳轉阻擋（照 Brave：只看目的地是不是廣告／追蹤網站，不看「是不是點連結」）：
                // 以前點按鈕跳到別的網站（登入、付款）、載入後網頁自己轉址都一律擋，正常功能也被擋掉
                // 彈窗裡的轉址是廣告：關掉整個彈窗回原頁（以前只擋轉址，使用者被留在空白彈窗、進不去要的網頁）
                if (popupCheck && popupIsAd(request.url.toString())) { listener.onPopupAd(this@Tab, request.url.toString()); return true }
                val target = request.url.host.orEmpty()
                if (target.isNotEmpty() && !isBlank && pageHost.isNotEmpty() && baseDomain(target) != baseDomain(pageHost) &&
                    listener.blockRedirects() && AdBlocker.isAdNavigation(request.url.toString(), pageHost)
                ) {
                    if (request.hasGesture()) listener.onAdClickBlocked(this@Tab, request.url.toString())
                    else listener.onRedirectBlocked(this@Tab, request.url.toString())
                    return true
                }
                if (isMediaUrl(request.url.toString()) && listener.onMediaUrl(this@Tab, request.url.toString())) return true
                rememberShownPage()
                val raw = request.url.toString()
                // 連結追蹤保護：拿掉網址上的追蹤參數再開（伺服器轉址不處理：網站轉回帶參數的網址時才不會來回打轉）
                val cleaned = if (request.method == "GET" && !request.isRedirect && listener.cleanLinks(this@Tab)) LinkCleaner.clean(raw) else raw
                // Brave 式防追蹤：追蹤跳轉頁直接開目的地；http 先試 https（伺服器轉址不升級，免得網站轉回 http 時來回打轉）
                val clean = if (request.method == "GET" && !request.isRedirect && listener.shields())
                    Debounce.target(cleaned) ?: Shields.upgrade(cleaned)?.also { upgraded = it } ?: cleaned
                else cleaned
                // 要去的網站身分不同（例如從一般網站點進 YouTube）：切換後自己重新發出這個請求
                val switched = request.method == "GET" && switchIdentityFor(clean)
                if (switched || clean !== raw) {
                    view.loadUrl(clean)
                    return true
                }
                return false
            }

            // IDM 式影片嗅探：只觀察、不攔截。網頁播放器（含 blob:／hls.js、iframe 內）實際請求的影片網址都會被記下
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                Extensions.resource(request.url)?.let { return it }
                val u = request.url.toString()
                // 廣告／追蹤阻擋：子資源符合清單就回空內容（Brave 引擎完整比對；沒有引擎時只比對網域）
                if (!request.isForMainFrame) {
                    val page = pageHost
                    if (blocksTrackers(page) && AdBlocker.blocksRequest(u, request.url.host.orEmpty(), page, AdBlocker.resourceType(request))) {
                        main.post { blockedCount++ }
                        return AdBlocker.emptyResponse()
                    }
                }
                if (!request.isForMainFrame && isSniffableMedia(u)) main.post {
                    if (u !in media && media.size < 20) media.add(u)
                    // 告訴網頁這頁有可下載的影片（全螢幕控制列的下載鈕據此顯示）
                    webView.evaluateJavascript("window.__lqHasMedia=true", null)
                }
                return null
            }

            // 預設回傳 false 會讓整個 App 被結束（看起來就是閃退）：接手處理、重建這個分頁
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                android.util.Log.w("LiquidTab", "renderer gone crashed=${detail.didCrash()} priority=${detail.rendererPriorityAtExit()}")
                com.liquid.browser.BugLog.event("renderer", "crashed=${detail.didCrash()} priority=${detail.rendererPriorityAtExit()} $url")
                listener.onRendererGone(this@Tab)
                return true
            }

            override fun onPageStarted(view: WebView, u: String, icon: Bitmap?) {
                // 點擊開出來的彈窗：第一個真正的網址（about:blank 之後）是廣告網站就關掉
                // 點擊開出來的彈窗：使用者碰它之前，任何一步（含網站自己的 pop?url=廣告 中轉頁）是廣告就關掉、回原頁
                if (popupCheck && popupIsAd(u)) { view.stopLoading(); listener.onPopupAd(this@Tab, u); return }
                if (popupCheck && u.startsWith("http")) listener.onPopupReady(this@Tab)
                pageStartedAt = android.os.SystemClock.uptimeMillis()
                media.clear()
                blockedCount = 0
                pageHost = Uri.parse(u).host.orEmpty()
                // 沒經過 shouldOverrideUrlLoading 的導覽（上一頁／下一頁、伺服器轉址）身分不對：切換後重新載入
                if (switchIdentityFor(u)) {
                    view.stopLoading()
                    view.loadUrl(u)
                    return
                }
                if (listener.adBlockFor(pageHost)) view.evaluateJavascript(AdBlocker.cosmeticJs(u), null)
                // 舊版 WebView 沒有開頁腳本：擴充改在這裡注入（只有最上層頁面）
                if (legacyInject && !incognito) Extensions.documentStartScript()?.let { view.evaluateJavascript(it, null) }
                if (legacyInject && listener.shields()) view.evaluateJavascript(Shields.fingerprintJs, null)
                translated = false
                trTarget = null
                trSent = 0
                trDone = 0
                url = u
                loadFailed = false
                // 整頁重新載入：頁面上的字幕翻譯腳本狀態已不在
                ytSubs = false
                pageLang = ""
                themeColor = null
                readerOn = false
                findMatches = 0
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: android.webkit.WebResourceResponse) {
                if (request.isForMainFrame) com.liquid.browser.BugLog.limited("http", "${response.statusCode} ${request.url}")
            }

            // HTTPS 升級後憑證有問題：這個網站其實只有 http，退回 http（記住，這次開 App 期間不再升級）
            override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel()
                val u = error.url.orEmpty()
                if (upgraded != null && Uri.parse(u).host == Uri.parse(upgraded).host) { upgraded = null; view.loadUrl(Shields.fallback(u)) }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                // HTTPS 升級後連不上（網站沒開 443、拒絕連線）：退回 http
                val u = request.url.toString()
                if (request.isForMainFrame && upgraded != null && u.startsWith("https://") && Uri.parse(u).host == Uri.parse(upgraded).host &&
                    error.errorCode in setOf(ERROR_CONNECT, ERROR_TIMEOUT, ERROR_FAILED_SSL_HANDSHAKE)
                ) { upgraded = null; view.loadUrl(Shields.fallback(u)); return }
                if (request.isForMainFrame) {
                    loadFailed = true
                    com.liquid.browser.BugLog.event("load", "${error.errorCode} ${error.description} ${request.url}")
                }
                if (request.isForMainFrame && error.description?.contains("LOCAL_NETWORK_PERMISSION") == true) listener.onLocalNetworkNeeded(this@Tab)
            }

            override fun doUpdateVisitedHistory(view: WebView, u: String, isReload: Boolean) {
                // Chrome 線上應用程式商店、Firefox 附加元件商店是單頁應用程式：在商店裡點進擴充功能只改網址、不重新載入頁面
                if (!incognito && u != url && Extensions.packageUrl(u) != null) listener.onExtensionOffer(this@Tab, u)
                url = u
                shownUrl = u
                canGoBack = view.canGoBack()
                canGoForward = view.canGoForward()
            }

            override fun onPageFinished(view: WebView, u: String) {
                com.liquid.browser.core.Engine.markHealthy(view.context)
                title = view.title.orEmpty()
                if (!incognito && Extensions.packageUrl(u) != null) listener.onExtensionOffer(this@Tab, u)
                if (cacheFirst) { cacheFirst = false; view.settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT }
                if (pageStartedAt > 0) {
                    val ms = android.os.SystemClock.uptimeMillis() - pageStartedAt
                    if (ms > 8_000) com.liquid.browser.BugLog.event("slow", "載入 ${ms}ms $u")
                    pageStartedAt = 0
                }
                // 這頁之前翻譯過（重新整理、返回時重新載入）：自動再翻一次（譯文走快取，不花 API 額度）
                if (!translated && u in translatedUrls) listener.onReapplyTranslation(this@Tab)
                if (legacyInject) view.evaluateJavascript(listener.injectedScript(), null)
                // 同一頁可能觸發多次 onPageFinished（重新導向、SPA），只在網址改變時記錄一次
                if (u != lastRecorded && !loadFailed) {
                    lastRecorded = u
                    listener.onVisited(this@Tab, u, title)
                }
                view.evaluateJavascript(THEME_COLOR_JS) { themeColor = parseCssColor(it.trim('"')) }
                // 網頁宣告的語言（<html lang>）：網址列的翻譯鈕只在跟翻譯目標不同時出現
                view.evaluateJavascript("document.documentElement.lang") { pageLang = it.trim('"').lowercase() }
                val host = Uri.parse(u).host
                if (host != null) view.evaluateJavascript(ICON_JS) { r ->
                    val icon = r.trim('"')
                    if (icon.startsWith("http")) listener.onIcon(host.removePrefix("www."), icon)
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) { progress = p; if (p == 100) refreshing = false }
            // 網頁的 JS 錯誤（含我們注入的腳本出錯）：記下來方便查網頁功能失靈
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR)
                    com.liquid.browser.BugLog.limited("js", "${pageHost} ${m.message().take(200)} (${m.sourceId().substringAfterLast('/').take(60)}:${m.lineNumber()})")
                return false
            }
            override fun onReceivedTitle(view: WebView, t: String?) { title = t.orEmpty() }
            override fun onReceivedIcon(view: WebView, icon: Bitmap) { favicon = icon.asImageBitmap() }
            // 網頁播放器按全螢幕：改用內建播放器（手勢、倍速、下載、子母畫面）。影片網址取自全螢幕的那個 <video>；
            // 串流網站的 <video> 是 blob:（MSE），改用攔截到的串流網址。找不到能播的網址才用網頁自己的全螢幕
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (!listener.nativeFullscreen()) return listener.onCustomView(view, callback)
                webView.evaluateJavascript(FULLSCREEN_VIDEO_JS) { r ->
                    val json = runCatching { org.json.JSONObject(org.json.JSONTokener(r).nextValue() as String) }.getOrNull()
                    // 網頁播放器是 blob:（hls.js／MSE）：串流網址多半綁了網頁的權杖，原生播放器去抓會 403；直接用網頁自己的全螢幕
                    // （以前改用偵測到的最後一個影片網址，常是旁邊的廣告影片，或被 CDN 拒絕）
                    val src = json?.optString("src")?.takeIf { it.startsWith("http") }
                        ?: media.lastOrNull()?.takeIf { json?.optString("src").isNullOrEmpty() }
                    if (src == null) return@evaluateJavascript listener.onCustomView(view, callback)
                    callback.onCustomViewHidden()
                    listener.onNativeVideo(this@Tab, src, json?.optString("title").orEmpty().ifBlank { title }, null, ((json?.optDouble("time", 0.0) ?: 0.0) * 1000).toLong())
                }
            }
            override fun onHideCustomView() = listener.onCustomView(null, null)

            // 只接受使用者點擊開的新視窗；新分頁的第一個網址若在廣告清單裡，onAdClickBlocked 會把它關掉
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                // 沒有點擊就開的彈窗一律不開（同 Chrome／Brave）；有點擊的照開（登入、付款視窗常是按鈕開的），
                // 開出來的第一頁是廣告網站才關掉（見 onPageStarted 的 popupCheck）
                // 使用者關掉「阻止跳轉」：沒點擊的彈窗也照開
                if (!isUserGesture && listener.blockRedirects()) return false
                // 點影片畫面開出的彈窗＝蓋在影片上的廣告層：不開，並讓那層點擊穿透，下一下就點到播放器
                if (listener.blockRedirects() && android.os.SystemClock.uptimeMillis() - videoTapAt < 1500) {
                    videoTapAt = 0
                    listener.onPopupBlocked(this@Tab)
                    return false
                }
                val popup = listener.onPopup(this@Tab)
                popup.popupCheck = true
                (resultMsg.obj as? WebView.WebViewTransport)?.webView = popup.webView
                resultMsg.sendToTarget()
                return true
            }
        }
        installVideoBridge()
        applyExtensions()
        applyShields(listener.shields())
        webView.setOnScrollChangeListener { v, _, y, _, oldY ->
            listener.onScroll(this, y - oldY, !v.canScrollVertically(1))
        }
        webView.setDownloadListener { u, ua, cd, mime, _ -> listener.onDownload(this, u, ua, cd, mime) }
        webView.setFindListener { active, count, done -> if (done) { findIndex = if (count == 0) 0 else active + 1; findMatches = count } }
        // 記住觸控位置，長按時用來決定預覽卡片從哪裡浮現
        webView.setOnTouchListener { _, e ->
            touchX = e.x
            touchY = e.y
            if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) popupCheck = false
            touching = e.actionMasked != android.view.MotionEvent.ACTION_UP && e.actionMasked != android.view.MotionEvent.ACTION_CANCEL
            // 手指按下時把 Android 的焦點交給 WebView：放在 Compose 裡的 WebView，在網址列、面板等 Compose 元件拿過焦點後，
            // 點網頁輸入框不會自己拿回焦點（網頁裡已經 focus，但系統不知道要給哪個 View 鍵盤），Google 搜尋框點了不出鍵盤
            if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN && !webView.hasFocus()) webView.requestFocus()
            trackPull(e)
            false
        }
        webView.setOnLongClickListener {
            val r = webView.hitTestResult
            when (r.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                    listener.onLongPress(this, HitResult(r.extra, null, touchX, touchY)); true
                }
                WebView.HitTestResult.IMAGE_TYPE -> {
                    listener.onLongPress(this, HitResult(null, r.extra, touchX, touchY)); true
                }
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                    // 圖片連結：hitTestResult 只給圖片網址，連結網址需另外向 WebView 要
                    val image = r.extra
                    val x = touchX
                    val y = touchY
                    val handler = Handler(Looper.getMainLooper()) { msg ->
                        listener.onLongPress(this, HitResult(msg.data.getString("url"), image, x, y)); true
                    }
                    webView.requestFocusNodeHref(handler.obtainMessage())
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 影片偵測與 JS 手勢層：在每個頁面（含 iframe）文件開始時注入腳本，並透過 WebMessageListener 接收訊息。
     * 舊版 WebView 不支援時，改在頁面開始／完成時以 evaluateJavascript 注入（腳本本身會避免重複執行）。
     */
    private fun installVideoBridge() {
        val script = listener.injectedScript()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
        } else {
            legacyInject = true
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, "LiquidBridge", setOf("*")) { _, message, source, mainFrame, _ ->
                val json = runCatching { JSONObject(message.data ?: return@addWebMessageListener) }.getOrNull() ?: return@addWebMessageListener
                val type = json.optString("type")
                // 密碼訊息：只收最上層頁面、https、而且跟目前顯示的網站同一個來源（iframe 或別的網站送來的一律不理）
                if (type.startsWith("pw_")) {
                    val origin = "${source.scheme}://${source.host}"
                    if (!mainFrame || source.scheme != "https" || source.host.isNullOrEmpty() || Uri.parse(url).host != source.host) return@addWebMessageListener
                    when (type) {
                        "pw_focus" -> listener.onPasswordField(this, origin, json.optString("mode") == "new")
                        "pw_blur" -> listener.onPasswordField(this, null, false)
                        "pw_submit" -> json.optString("pass").takeIf { it.isNotEmpty() }?.let { listener.onPasswordSubmit(this, origin, json.optString("user"), it) }
                    }
                    return@addWebMessageListener
                }
                when (type) {
                    "haptic" -> webView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    "nopull" -> { pullArmed = false; pull = 0f }
                    "vtap" -> videoTapAt = android.os.SystemClock.uptimeMillis()
                    "download_best" -> listener.onDownloadBest(this)
                    "tr" -> {
                        val arr = json.optJSONArray("texts") ?: return@addWebMessageListener
                        listener.onTranslateBatch(this, json.optInt("id"), List(arr.length()) { arr.optString(it) })
                    }
                    "tr_state" -> { trSent = json.optInt("sent"); trDone = json.optInt("done") }
                    "yt_tr" -> {
                        val arr = json.optJSONArray("texts") ?: return@addWebMessageListener
                        listener.onSubtitleBatch(this, json.optInt("id"), List(arr.length()) { arr.optString(it) })
                    }
                    "yt_state" -> if (json.optBoolean("noCaptions")) listener.onNoCaptions(this)
                    "download" -> json.optString("src").takeIf { it.startsWith("http") }?.let { listener.onVideoDownload(this, it, json.optString("title").ifBlank { title }) }
                    "native" -> {
                        val src = json.optString("src").takeIf { it.startsWith("http") } ?: return@addWebMessageListener
                        val dpr = json.optDouble("dpr", 1.0).toFloat()
                        val from = json.optJSONArray("rect")?.let { r ->
                            val loc = IntArray(2).also { webView.getLocationInWindow(it) }
                            val l = loc[0] + r.getDouble(0).toFloat() * dpr
                            val t = loc[1] + r.getDouble(1).toFloat() * dpr
                            androidx.compose.ui.geometry.Rect(l, t, l + r.getDouble(2).toFloat() * dpr, t + r.getDouble(3).toFloat() * dpr)
                        }
                        listener.onNativeVideo(this, src, json.optString("title").ifBlank { title }, from, (json.optDouble("time", 0.0) * 1000).toLong())
                    }
                }
            }
        }
    }

    private var legacyInject = false

    fun load(input: String) {
        val target = toUrl(input, listener.searchPrefix()).let { if (listener.cleanLinks(this)) LinkCleaner.clean(it) else it }
            .let { if (listener.shields()) Debounce.target(it) ?: Shields.upgrade(it)?.also { u -> upgraded = u } ?: it else it }
        if (isMediaUrl(target) && listener.onMediaUrl(this, target, typed = true)) return
        rememberShownPage()
        url = target
        // WebView 還沒放上畫面（寬度 0）就載入的話，網頁會以 0 寬度排版，之後可能留下錯誤的縮放與水平捲動：
        // 等量到實際寬度再載入
        whenSized {
            switchIdentityFor(target)
            // 不支援 allow-list 的 WebView：至少把主頁面請求的 X-Requested-With 覆寫成空值
            if (asIPhone) webView.loadUrl(target, mapOf("X-Requested-With" to "")) else webView.loadUrl(target)
        }
    }

    private fun whenSized(block: () -> Unit) {
        if (webView.width > 0) return block()
        webView.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                if (v.width <= 0) return
                v.removeOnLayoutChangeListener(this)
                block()
            }
        })
    }

    fun setDesktopMode(on: Boolean) {
        desktop = on
        applyIdentity(reload = true)
    }

    fun setIdentifyAsIPhone(on: Boolean) {
        if (asIPhone == on) return
        asIPhone = on
        applyIdentity(reload = !isBlank)
    }

    /**
     * 依「iPhone 識別」與「桌面版」組合設定 User-Agent、Client Hints 與頁面中的 navigator 屬性：
     *  iPhone＋行動版 → iPhone Safari；iPhone＋桌面版 → macOS Safari；
     *  Android＋桌面版 → 桌面 Chrome；Android＋行動版 → WebView 預設。
     */
    private fun applyIdentity(reload: Boolean, forUrl: String? = url) {
        val ctx = webView.context
        val asIPhone = iosFor(forUrl)
        appliedIos = asIPhone
        webView.settings.userAgentString = when {
            asIPhone && desktop -> MAC_SAFARI_UA
            asIPhone -> IPHONE_SAFARI_UA
            desktop -> desktopUserAgent(ctx)
            else -> null
        }
        // Client Hints（Sec-CH-UA-Platform 等標頭）：與 User-Agent 一致
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            if (asIPhone) WebSettingsCompat.setUserAgentMetadata(
                webView.settings,
                UserAgentMetadata.Builder()
                    // Safari 不送 Sec-CH-UA：品牌只留中性的佔位品牌，不出現 Android WebView／Chromium
                    .setBrandVersionList(
                        listOf(UserAgentMetadata.BrandVersion.Builder().setBrand("Not A(Brand").setMajorVersion("99").setFullVersion("99.0.0.0").build()),
                    )
                    .setFullVersion("26.0")
                    .setPlatform(if (desktop) "macOS" else "iOS")
                    .setPlatformVersion(if (desktop) "10.15.7" else "18.6")
                    .setMobile(!desktop)
                    .setModel("")
                    .build(),
            )
            else WebSettingsCompat.setUserAgentMetadata(webView.settings, UserAgentMetadata.Builder().build())
        }
        // 頁面 JS 讀到的 navigator.platform／vendor 也改成 Apple；Safari 沒有 userAgentData
        iosShim?.remove()
        iosShim = null
        if (asIPhone && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            iosShim = WebViewCompat.addDocumentStartJavaScript(webView, iosNavigatorShim(desktop), setOf("*"))
        }
        if (reload) webView.reload()
    }

    fun setTextSize(percent: Int) {
        textZoom = percent.coerceIn(50, 300)
        webView.settings.textZoom = textZoom
    }

    fun setJavaScriptEnabled(on: Boolean) {
        javaScript = on
        webView.settings.javaScriptEnabled = on
        webView.reload()
    }

    fun find(query: String) {
        if (query.isEmpty()) clearFind() else webView.findAllAsync(query)
    }

    fun findNext(forward: Boolean) = webView.findNext(forward)

    fun clearFind() {
        webView.clearMatches()
        findMatches = 0
        findIndex = 0
    }

    /** 閱讀器模式：注入 assets/reader.js 把文章轉成乾淨排版；再按一次重新載入原頁。 */
    fun toggleReader(script: String) {
        if (readerOn) {
            readerOn = false
            webView.reload()
        } else {
            webView.evaluateJavascript(script) { ok -> readerOn = ok == "true" }
        }
    }

    /**
     * 告訴網頁底部（直向）或頂部（橫向）被浮動控制列蓋住多少 px：
     * 併入系統列的 WindowInsets，WebView 會換算成 CSS env(safe-area-inset-*)，
     * 支援安全區域的網站（為 iPhone 最佳化的網站多半支援）就會把底部內容推到網址列上方，和 iOS Safari 一致。
     */
    fun setControlsInset(bottomPx: Int, topPx: Int) {
        controlsBottomPx = bottomPx
        ViewCompat.setOnApplyWindowInsetsListener(webView) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val status = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val merged = WindowInsetsCompat.Builder(insets)
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(bars.left, bars.top, bars.right, maxOf(bars.bottom, bottomPx)))
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(status.left, maxOf(status.top, topPx), status.right, status.bottom))
                .build()
            ViewCompat.onApplyWindowInsets(v, merged)
        }
        ViewCompat.requestApplyInsets(webView)
    }

    /** 文章縮圖（og:image）與標題，加入閱讀列表時使用。 */
    fun pageMeta(onResult: (title: String, image: String?) -> Unit) {
        webView.evaluateJavascript(META_JS) { r ->
            val img = r.trim('"').takeIf { it.startsWith("http") }
            onResult(title, img)
        }
    }

    private fun rememberShownPage() {
        val shown = shownUrl ?: return
        capture(onDone = { pageShots[shown] = it })
    }

    /** 上一頁離開前留下的畫面（沒有則 null）。 */
    fun previousShot(): ImageBitmap? {
        val list = webView.copyBackForwardList()
        val prev = list.getItemAtIndex(list.currentIndex - 1) ?: return null
        return pageShots[prev.url]
    }

    fun nextShot(): ImageBitmap? {
        val list = webView.copyBackForwardList()
        val next = list.getItemAtIndex(list.currentIndex + 1) ?: return null
        return pageShots[next.url]
    }

    /**
     * 擷取目前畫面縮圖（0.4 倍）給分頁總覽。
     * ponytail: 在主執行緒同步繪製（約數毫秒），只在進入總覽／離開頁面時呼叫；第 8 階段若量到掉幀再改成 PixelCopy 非同步。
     */
    /**
     * 目前分頁：渲染程序設為重要（不被系統降速，捲動更順）；
     * 背景分頁恢復預設，記憶體與耗電讓給前景。
     */
    /** 網頁上 <video> 由上到下的網址（下載選單排序用；串流影片是 blob: 網址，對不到偵測清單） */
    suspend fun videoSourcesInOrder(): List<String> = kotlinx.coroutines.suspendCancellableCoroutine { c ->
        webView.evaluateJavascript(
            "JSON.stringify(Array.prototype.map.call(document.querySelectorAll('video'),function(v){var s=v.querySelector('source');return v.currentSrc||v.src||(s&&s.src)||''}))",
        ) { r ->
            val list = runCatching { org.json.JSONArray(org.json.JSONTokener(r).nextValue() as String) }.getOrNull()
            c.resumeWith(Result.success(if (list == null) emptyList() else List(list.length()) { list.optString(it) }.filter { it.startsWith("http") }))
        }
    }

    /** 擋下廣告點擊後，讓蓋在影片上的透明廣告層點擊穿透（video_detect.js 的 __lqUnblock） */
    /** 彈窗網址（或它參數裡夾帶的網址）是廣告 */
    private fun popupIsAd(u: String): Boolean {
        if (!u.startsWith("http")) return false
        val from = opener?.pageHost.orEmpty()
        val uri = android.net.Uri.parse(u)
        val inner = runCatching { uri.queryParameterNames.mapNotNull { uri.getQueryParameter(it) }.filter { it.startsWith("http") } }.getOrDefault(emptyList())
        return (listOf(u) + inner).any { AdBlocker.isAdNavigation(it, from) }
    }

    fun unblockLastTap() = webView.evaluateJavascript("window.__lqUnblock&&window.__lqUnblock()", null)

    /** 下拉重新整理：目前下拉距離（px，未阻尼）與是否正在重新整理（轉圈直到載入完成） */
    var pull by mutableFloatStateOf(0f)
        private set
    var refreshing by mutableStateOf(false)
        private set
    private var pullStartY = 0f
    private var pullArmed = false
    private val pullThreshold get() = 96f * webView.resources.displayMetrics.density

    // 只在網頁已經捲到最頂、手指往下拉時啟動；網頁一開始往上捲或多指就取消。只觀察觸控，不攔截（網頁照常收到事件）
    // ponytail: 用 canScrollVertically 判斷頂端，內層自己捲動的網頁（body 不捲）可能誤判；要更準再用 JS 回報 scrollTop
    private fun trackPull(e: android.view.MotionEvent) {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                pullStartY = e.y
                pullArmed = !refreshing && !isBlank && webView.scrollY == 0 && !webView.canScrollVertically(-1)
                pull = 0f
            }
            android.view.MotionEvent.ACTION_MOVE -> if (pullArmed) {
                val dy = e.y - pullStartY
                if (dy < 0f || webView.canScrollVertically(-1)) { pullArmed = false; pull = 0f }
                else {
                    if ((pull < pullThreshold) != (dy < pullThreshold)) webView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    pull = dy
                }
            }
            android.view.MotionEvent.ACTION_UP -> {
                if (pullArmed && pull >= pullThreshold) { refreshing = true; refresh() }
                pullArmed = false
                pull = 0f
            }
            android.view.MotionEvent.ACTION_CANCEL, android.view.MotionEvent.ACTION_POINTER_DOWN -> { pullArmed = false; pull = 0f }
        }
    }

    /** 重新整理：網頁還沒真的載入過（還原中、等寬度）就重新載入網址；載入中也直接重來 */
    fun refresh() {
        val cur = webView.url
        if (cur.isNullOrEmpty() || cur == "about:blank") {
            val target = pendingUrl ?: url
            pendingUrl = null
            pendingState = null
            if (target.isNotBlank() && target != "about:blank") load(target) else refreshing = false
        } else {
            webView.stopLoading()
            webView.reload()
        }
    }

    /** 停止載入；進度設回完成，按鈕才會變回「重新整理」（否則卡住的頁面會一直顯示停止鈕） */
    fun stop() {
        webView.stopLoading()
        progress = 100
        refreshing = false
    }

    /** 由哪個分頁的彈出視窗開出來的；在這個分頁沒有上一頁時右滑返回，會關掉它回到來源頁 */
    var opener: Tab? = null

    /** 從上次工作階段還原、還沒載入的網址：第一次切到這個分頁時才載入（重開 App 不會一次載入所有分頁） */
    private var pendingUrl: String? = null
    /** 還原的上一頁／下一頁紀錄（WebView.saveState 的內容）；載入時連紀錄一起還原 */
    private var pendingState: android.os.Bundle? = null
    private var pageStartedAt = 0L
    /** 還原載入時先用快取（不重新下載）；這一頁載完就改回一般模式 */
    private var cacheFirst = false
    /** 最後一次切到這個分頁的時間：重開 App 後預先載入最近用過的分頁 */
    var lastUsed = 0L
    val pending: Boolean get() = pendingUrl != null

    fun restore(savedUrl: String, savedTitle: String, state: android.os.Bundle? = null) {
        url = savedUrl
        title = savedTitle
        pendingUrl = savedUrl
        pendingState = state
    }

    /** 上一頁紀錄（存工作階段用）：還沒載入的分頁回傳還原時拿到的那份 */
    fun historyState(): android.os.Bundle? =
        pendingState ?: if (pending) null else android.os.Bundle().takeIf { runCatching { webView.saveState(it) }.getOrNull() != null }

    /**
     * 載入還原的網頁：先用快取（看過的網頁幾乎立刻出現、不重新下載），有上一頁紀錄就連紀錄一起還原。
     * sizeLike：分頁不在畫面上時（背景預先載入）借用這個大小排版，否則要等放上畫面才會載入
     */
    fun loadPending(sizeLike: View? = null): Boolean {
        val u = pendingUrl ?: return false
        pendingUrl = null
        val st = pendingState
        pendingState = null
        if (webView.width == 0 && sizeLike != null && sizeLike.width > 0) {
            webView.measure(View.MeasureSpec.makeMeasureSpec(sizeLike.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(sizeLike.height, View.MeasureSpec.EXACTLY))
            webView.layout(0, 0, sizeLike.width, sizeLike.height)
        }
        cacheFirst = true
        webView.settings.cacheMode = android.webkit.WebSettings.LOAD_CACHE_ELSE_NETWORK
        whenSized {
            if (st == null || runCatching { webView.restoreState(st) }.getOrNull() == null) load(u)
        }
        return true
    }

    fun setForeground(on: Boolean) {
        if (on) { lastUsed = System.currentTimeMillis(); loadPending() }
        // 背景分頁暫停動畫與額外處理，省 CPU 與電量；切回來時恢復。WebView 已銷毀時（分頁剛被關閉或重建）不做事
        runCatching { if (on) webView.onResume() else webView.onPause() }
        // 不開 offscreenPreRaster：會讓渲染程序多佔大量記憶體，小米 HyperOS 等積極回收的系統會因此結束渲染程序
        runCatching { webView.setRendererPriorityPolicy(if (on) WebView.RENDERER_PRIORITY_IMPORTANT else WebView.RENDERER_PRIORITY_BOUND, true) }
    }

    /** 分頁總覽用的縮圖；onDone 在縮圖更新後（或無法擷取時）呼叫 */
    fun snapshot(onDone: () -> Unit = {}) {
        if (isBlank) { onDone(); return }
        capture({ thumbnail = it; onDone() }, onFail = onDone)
    }

    /** 底部控制列蓋住的高度：擷取畫面時排除，縮圖裡才不會有網址列 */
    private var controlsBottomPx = 0

    /**
     * 擷取目前網頁畫面（0.4 倍）：用 PixelCopy 從已合成的視窗複製，在 GPU／RenderThread 完成、非同步回呼，
     * 不像 webView.draw() 會在主執行緒用 CPU 重畫整個網頁（點連結、開分頁總覽時會卡幾十毫秒）。
     */
    /** 全解析度的目前畫面（面板滑動期間暫時代替網頁，網頁不必每幀重畫） */
    fun freezeFrame(onDone: (ImageBitmap?) -> Unit) = capture({ onDone(it) }, onFail = { onDone(null) }, scale = 1f)

    private fun capture(onDone: (ImageBitmap) -> Unit, onFail: () -> Unit = {}, scale: Float = 0.4f) {
        val activity = (webView.context as? android.content.MutableContextWrapper)?.baseContext as? android.app.Activity
        val w = webView.width
        val h = webView.height - controlsBottomPx
        // 網頁要真的顯示在螢幕上才擷取（擷取的是螢幕畫面）：被分頁總覽蓋住、INVISIBLE 或不是目前分頁時跳過，
        // 否則會拍到總覽畫面或別的分頁，存成錯的縮圖
        if (activity == null || w <= 0 || h <= 0 || !webView.isAttachedToWindow || !webView.isShown) { onFail(); return }
        val loc = IntArray(2).also { webView.getLocationInWindow(it) }
        val bmp = Bitmap.createBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        runCatching {
            android.view.PixelCopy.request(
                activity.window, android.graphics.Rect(loc[0], loc[1], loc[0] + w, loc[1] + h), bmp,
                { r -> if (r == android.view.PixelCopy.SUCCESS) onDone(bmp.asImageBitmap()) else onFail() },
                android.os.Handler(android.os.Looper.getMainLooper()),
            )
        }.onFailure { onFail() }
    }
}

/**
 * 在 <html> 結尾（body 之後）加一段高度為 safe-area-inset-bottom 的空白：不影響網頁本身版面，
 * 只讓整頁可以多捲一點，被浮動控制列蓋住的頁尾就能捲上來。只作用於主頁面（iframe 不加）。
 */
/**
 * 把貼在畫面底部的固定元素（cookie 橫幅、底部提示列、聊天按鈕）往上推到浮動控制列上方，否則會被網址列擋住。
 * 控制列高度＝safe-area-inset-bottom（App 透過 setControlsInset 提供）。只處理寬度過半、位在畫面下半、高度不到 6 成的
 * fixed／sticky 元素（全螢幕彈窗不動）；先用 offsetParent===null 篩出固定定位的元素，不必對整頁算樣式。
 * 頁面載入、DOM 變動（節流）與前 30 秒每 2 秒各掃一次（橫幅常晚幾秒才出現）。
 */
private const val LIFT_FIXED_JS = """(function(){
  if (window !== window.top || window.__lqLift) return;
  window.__lqLift = true;
  function inset() {
    var d = document.createElement('div');
    d.style.cssText = 'position:fixed;visibility:hidden;height:env(safe-area-inset-bottom,0px)';
    document.documentElement.appendChild(d);
    var h = d.offsetHeight; d.remove(); return h;
  }
  // 元素底部留白：自己的 padding-bottom，或貼在最底下的子元素（常見寫法是放一個高度＝安全區域的空 div）
  function padBottom(el) {
    var p = parseFloat(getComputedStyle(el).paddingBottom) || 0;
    var last = el.lastElementChild;
    if (last) {
      var lr = last.getBoundingClientRect(), er = el.getBoundingClientRect();
      if (Math.abs(lr.bottom - er.bottom) < 2 && !last.textContent.trim() && !last.querySelector('img,svg,canvas,video')) p = Math.max(p, lr.height);
      p = Math.max(p, parseFloat(getComputedStyle(last).paddingBottom) || 0);
    }
    return p;
  }
  function scan() {
    if (!document.body) return;
    var gap = inset(); if (!gap) return;
    var vh = window.innerHeight, vw = window.innerWidth;
    var all = document.body.getElementsByTagName('*');
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      if (el.offsetParent !== null || el.__lqLifted) continue;
      var cs = getComputedStyle(el);
      if (cs.position !== 'fixed' && cs.position !== 'sticky') continue;
      var r = el.getBoundingClientRect();
      if (r.width < vw * 0.5 || r.height === 0 || r.height > vh * 0.6 || r.top < vh * 0.4 || r.bottom < vh - gap) continue;
      // 網站自己已經用 safe-area-inset-bottom 留了底部空間（例如京東的底部導覽列）：再往上推會多出一段空白
      if (padBottom(el) >= gap - 1) { el.__lqLifted = true; continue; }
      var b = parseFloat(cs.bottom) || 0;
      // 用 env()：網址列收合／展開時留白會變，元素跟著上下移動
      el.style.setProperty('bottom', 'calc(' + b + 'px + env(safe-area-inset-bottom, 0px))', 'important');
      el.__lqLifted = true;
    }
  }
  var t = 0;
  function later() { clearTimeout(t); t = setTimeout(scan, 400); }
  document.addEventListener('DOMContentLoaded', function () {
    scan();
    new MutationObserver(later).observe(document.body, { childList: true, subtree: true });
    var n = 0, iv = setInterval(function () { scan(); if (++n >= 15) clearInterval(iv); }, 2000);
  });
  window.addEventListener('load', scan);
})();"""

private const val END_SPACER_JS = """(function(){
  if (window !== window.top) return;
  var s = document.createElement('style');
  s.textContent = 'html::after{content:"";display:block;height:calc(env(safe-area-inset-bottom,0px) + 16px);pointer-events:none}';
  (document.head || document.documentElement).appendChild(s);
})();"""

/** iOS 26 Safari：OS 版本在 UA 中固定為 18_6，Safari 版本為 26.0 */
/**
 * 「識別為 iPhone」的例外網站（像 Firefox 的個別網站相容性處理）：這些網站用真實的 Android 身分。
 * YouTube：防機器人權杖是在 Chromium 環境產生的，跟 iPhone 身分對不上 → 字幕檔回傳空白、全螢幕走 iPhone 路線只放大 <video>
 */
private val NO_IOS_HOSTS = listOf("youtube.com", "youtube-nocookie.com", "youtu.be")

const val IPHONE_SAFARI_UA =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Mobile/15E148 Safari/604.1"
const val MAC_SAFARI_UA =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"

private fun iosNavigatorShim(desktop: Boolean) = """(function(){
  try {
    var p = Navigator.prototype, d = function (k, v) { Object.defineProperty(p, k, { get: function () { return v; }, configurable: true }); };
    d('platform', '${if (desktop) "MacIntel" else "iPhone"}');
    d('vendor', 'Apple Computer, Inc.');
    d('maxTouchPoints', ${if (desktop) 0 else 5});
    d('userAgentData', undefined);
  } catch (e) {}
})();"""

/** 桌面版 User-Agent：沿用 WebView 的 Chrome 版本號，換成桌面 Linux 平台並移除 Mobile。 */
fun desktopUserAgent(context: Context): String {
    val chrome = Regex("Chrome/[\\d.]+").find(WebSettings.getDefaultUserAgent(context))?.value ?: "Chrome/140.0.0.0"
    return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
}

private const val META_JS = """(function(){var m=document.querySelector('meta[property="og:image"],meta[name="twitter:image"]');return m?m.content:''})()"""

/** 使用者輸入轉成網址：有協定直接用、像網域就補 https、其他交給搜尋引擎。 */
fun toUrl(input: String, searchPrefix: String): String {
    val s = input.trim()
    val l = s.lowercase()
    return when {
        l.startsWith("http://") || l.startsWith("https://") || l.startsWith("about:") || l.startsWith("file:") -> s
        // 本機與區網（localhost、IP）多半沒有 HTTPS
        looksLikeUrl(s) -> if (isLocalAddress(l)) "http://$s" else "https://$s"
        else -> searchPrefix + Uri.encode(s)
    }
}

private val IpAddress = Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?([/?#].*)?$")
private val DomainName = Regex("^[\\w-]+(\\.[\\w-]+)*\\.[a-z]{2,}(:\\d+)?([/?#].*)?$")

private fun isLocalAddress(l: String) = l == "localhost" || l.startsWith("localhost:") || l.startsWith("localhost/") || IpAddress.matches(l)

/**
 * 輸入的是網址（要直接開）還是要搜尋的字：有協定、www. 開頭、localhost、IP、或「名稱.頂級網域」形式
 * （例如 example.com、news.example.co.uk/path、192.168.1.1:8080）就是網址；有空白、或像 v1.2 這種沒有頂級網域的就是搜尋。
 * 網址列輸入時前面的圖示依此切換：網址＝地球、搜尋＝搜尋引擎的圖示。
 */
fun looksLikeUrl(input: String): Boolean {
    val l = input.trim().lowercase()
    if (l.isEmpty() || l.any { it.isWhitespace() }) return false
    return l.startsWith("http://") || l.startsWith("https://") || l.startsWith("about:") || l.startsWith("file:") ||
        l.startsWith("www.") || isLocalAddress(l) || DomainName.matches(l)
}

/** 狀態列顏色：優先 theme-color，沒有就用頁面背景色（body → html），都透明則回傳空字串。 */
private const val THEME_COLOR_JS = """(function(){
  var m=document.querySelector('meta[name="theme-color"]');
  if(m&&m.content)return m.content;
  var t='rgba(0, 0, 0, 0)',b=getComputedStyle(document.body).backgroundColor;
  if(b&&b!==t)return b;
  var h=getComputedStyle(document.documentElement).backgroundColor;
  return h&&h!==t?h:'#ffffff';
})()"""

/** 找出頁面上最大的圖示（apple-touch-icon 預設視為 180px），小於 48px 不採用。 */
private const val ICON_JS = """(function(){var best='',size=0;
document.querySelectorAll('link[rel~="icon"],link[rel^="apple-touch-icon"]').forEach(function(l){
var v=(l.sizes&&l.sizes.value)||'';var s=parseInt(v.split('x')[0])||(l.rel.indexOf('apple')>=0?180:16);
if(s>size){size=s;best=l.href}});return size>=48?best:''})()"""

/** 解析 CSS 顏色字串（#rgb、#rrggbb、rgb()、rgba()）。 */
fun parseCssColor(s: String): Color? {
    if (s.isBlank()) return null
    if (s.startsWith("#")) {
        val hex = if (s.length == 4) "#" + s.drop(1).map { "$it$it" }.joinToString("") else s
        return runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()
    }
    val n = Regex("[\\d.]+").findAll(s).map { it.value.toFloat() }.toList()
    if (!s.startsWith("rgb") || n.size < 3) return null
    if (n.size >= 4 && n[3] == 0f) return null
    return Color(n[0] / 255f, n[1] / 255f, n[2] / 255f)
}

/**
 * 可下載的影片請求：m3u8 播放清單與 mp4／webm 等整檔影片。
 * 排除 HLS 片段（.ts／.m4s）與 DASH（.mpd，引擎尚不支援）；同一播放清單的子清單也會被記下，由使用者選擇。
 */
fun isSniffableMedia(url: String): Boolean {
    if (!url.startsWith("http")) return false
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return path.endsWith(".m3u8") || path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".m4v") ||
        path.endsWith(".mov") || path.endsWith(".mkv") || path.endsWith(".flv")
}

/** 正在全螢幕（或正在播放）的 <video>：網址、播放位置、標題；同時暫停它（交給內建播放器接著播） */
private const val FULLSCREEN_VIDEO_JS = """(function(){
  var f = document.fullscreenElement || document.webkitFullscreenElement;
  var v = f && (f.tagName === 'VIDEO' ? f : f.querySelector && f.querySelector('video'));
  if (!v) { var all = Array.prototype.slice.call(document.querySelectorAll('video')); v = all.filter(function(x){ return !x.paused; })[0] || all[0]; }
  if (!v) return null;
  var s = v.currentSrc || v.src || '';
  if (/^https?:/.test(s)) try { v.pause(); } catch (e) {}
  return JSON.stringify({ src: s, time: v.currentTime || 0, title: document.title || '' });
})()"""