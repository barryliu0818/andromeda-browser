package com.liquid.browser

import com.liquid.browser.core.t

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import androidx.metrics.performance.JankStats
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.content.ContextCompat
import com.liquid.browser.browser.BrowserViewModel
import com.liquid.browser.core.Motion
import com.liquid.browser.core.installImageLoader
import com.liquid.browser.player.PlayerScreen
import com.liquid.browser.ui.browser.BrowserScreen
import com.liquid.browser.ui.glass.LocalReduceTransparency
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeFeatureFlags

private const val ACTION_PIP_TOGGLE = "com.liquid.browser.PIP_TOGGLE"

/** 單一 Activity：edge-to-edge、最高刷新率、Compose 畫面、原生播放器的子母畫面。 */
class MainActivity : ComponentActivity() {
    private val vm: BrowserViewModel by viewModels()
    private val refresh by lazy { com.liquid.browser.core.AdaptiveRefresh(this) }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        refresh.poke()
        return super.dispatchTouchEvent(ev)
    }

    /** JankStats：逐幀監測，掉幀時寫入 logcat（tag: LiquidJank），附帶目前畫面狀態（瀏覽／總覽／播放器） */
    private var jankStats: JankStats? = null
    private var created = 0L

    /** 子母畫面中的播放／暫停按鈕 */
    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            vm.player?.togglePlay()
        }
    }

    @OptIn(ExperimentalHazeApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        BugLog.init(this)
        BugLog.startWatchdog()
        created = android.os.SystemClock.uptimeMillis()
        CrashLog.install(this)
        // debug 版才開 WebView 遠端除錯（chrome://inspect、adb forward），release 版不開
        if (BuildConfig.DEBUG) android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        CrashLog.showIfAny(this)
        // 預先載入（第一個畫面畫完、主執行緒閒下來才做，不拖慢啟動）：
        //  - 先把網頁渲染程序開好（約 30～50MB）：第一次開網頁不必等程序啟動
        //  - 先連上搜尋引擎、翻譯伺服器（DNS＋TLS 握手）：搜尋、翻譯第一個請求更快
        android.os.Looper.myQueue().addIdleHandler {
            runCatching {
                if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.MULTI_PROFILE)) {
                    val profile = androidx.webkit.ProfileStore.getInstance().getProfile(androidx.webkit.Profile.DEFAULT_PROFILE_NAME)
                    if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.WARM_UP_RENDERER_PROCESS)) profile?.warmUpRendererProcess()
                    if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.PRECONNECT)) {
                        val engine = android.net.Uri.parse(vm.settings.value.engine.prefix)
                        listOf("${engine.scheme}://${engine.host}", "https://transmart.qq.com", "https://cn-beijing.maas.qianwenaiapi.com").forEach { profile?.preconnect(it) }
                    }
                }
            }
            false
        }
        // 智適應刷新率：操作時 120Hz、靜止後交還系統降頻；網頁捲動（含慣性滑動）也算在操作
        vm.onUserActivity = refresh::poke
        installImageLoader(applicationContext)
        // Android 17 QPR2+：玻璃直接取樣已合成的視窗畫面（含 WebView），不需另外錄製來源
        HazeFeatureFlags.isPlatformBackdropEnabled = com.liquid.browser.ui.glass.PlatformBackdrop
        vm.attach(this)
        if (vm.tabs.isEmpty()) {
            // 系統結束 App 後重開：還原上次的分頁；從外部開連結則另開新分頁
            val restored = vm.restoreSession()
            if (!restored || intent?.dataString != null) vm.newTab(intent?.dataString)
        }
        jankStats = JankStats.createAndTrack(window) { frame ->
            if (frame.isJank) {
                val ms = frame.frameDurationUiNanos / 1_000_000.0
                Log.w("LiquidJank", "${ms}ms ${frame.states}")
                // 超過 3 幀（120Hz 約 25ms）才記進問題紀錄：一兩幀的小抖動太多，會把真正的卡頓淹沒
                if (ms > 25) BugLog.event("jank", "%.1fms %s %s".format(ms, frame.states.joinToString { "${it.key}=${it.value}" }, vm.current?.domain.orEmpty()))
                // 一幀超過 150ms（明顯卡一下）：自動回報；剛啟動的前 3 秒（建立畫面）不算
                if (ms > 150 && android.os.SystemClock.uptimeMillis() - created > 3_000) BugLog.auto("freeze", "一幀 %.0fms %s %s".format(ms, frame.states.joinToString { "${it.key}=${it.value}" }, vm.current?.domain.orEmpty()))
            }
        }
        ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(ACTION_PIP_TOGGLE), ContextCompat.RECEIVER_NOT_EXPORTED)
        com.liquid.browser.core.Palette.apply(this)
        setContent {
            // Android 官方 Material 3 主題：Material You 動態配色（下拉選單、開關等官方元件自動套用）
            val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
            val scheme = androidx.compose.runtime.remember(darkTheme) {
                if (darkTheme) com.liquid.browser.core.Palette.dark(this) else com.liquid.browser.core.Palette.light(this)
            }
            androidx.compose.material3.MaterialTheme(colorScheme = scheme) {
            val settings by vm.settings.collectAsState()
            // 介面語言：設定變更時立即套用（讀過 t() 的畫面會重組）
            LaunchedEffect(settings.appLang) { com.liquid.browser.core.I18n.apply(settings.appLang) }
            val player = vm.player
            // Android 17：區域網路權限（要求一次，同意後重新載入被擋下的頁面）
            val localNet = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                vm.localNetworkRequest?.let { tab -> if (granted) tab.webView.reload() else vm.showToast(t("未允許存取區域網路")) }
                vm.localNetworkRequest = null
            }
            // 下載引擎的通知列進度：第一次開始下載時要求通知權限（拒絕也能下載，只是不顯示通知）
            val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            val hasDownloads = com.liquid.browser.browser.DownloadEngine.tasks.isNotEmpty()
            LaunchedEffect(hasDownloads) {
                if (hasDownloads && Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            LaunchedEffect(vm.localNetworkRequest) {
                if (vm.localNetworkRequest != null && Build.VERSION.SDK_INT >= 37) localNet.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
            }
            // 播放狀態改變時更新子母畫面參數（Android 12+ 播放中離開 App 自動進入子母畫面）
            LaunchedEffect(player, player?.isPlaying, player?.videoAspect) { setPictureInPictureParams(pipParams()) }
            CompositionLocalProvider(LocalReduceTransparency provides settings.reduceTransparency) {
                // testTagsAsResourceId：讓 Macrobenchmark／UiAutomator 能以 testTag 找到元件
                Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    if (!vm.inPip) BrowserScreen(vm)
                    player?.let { PlayerScreen(it, vm.inPip, onVideoBounds = { r -> vm.videoBounds = r }, onDownload = { vm.downloadVideo(it.request) }, onClose = vm::closePlayer) }
                }
            }
            }
        }
    }

    // 深淺色或桌布配色改變（uiMode 在 configChanges 裡，不會重建 Activity）：更新 Material You 色票
    // 記憶體吃緊或退到背景很久：釋放預備的模糊視窗（需要時再建）
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_BACKGROUND) {
            com.liquid.browser.ui.glass.BlurPool.clear()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        com.liquid.browser.core.Palette.apply(this)
    }

    private fun pipParams(): PictureInPictureParams {
        val p = vm.player
        val aspect = (p?.videoAspect ?: (16f / 9f)).coerceIn(1 / 2.39f, 2.39f)
        val playing = p?.isPlaying == true
        val toggle = RemoteAction(
            Icon.createWithResource(this, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
            if (playing) t("暫停") else t("播放"),
            if (playing) t("暫停") else t("播放"),
            PendingIntent.getBroadcast(this, 0, Intent(ACTION_PIP_TOGGLE).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE),
        )
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational((aspect * 1000).toInt(), 1000))
            .setSourceRectHint(vm.videoBounds.takeIf { !it.isEmpty })
            .setActions(listOf(toggle))
            .apply {
                if (Build.VERSION.SDK_INT >= 31) {
                    setAutoEnterEnabled(playing)
                    setSeamlessResizeEnabled(true)
                }
            }
            .build()
    }

    /** Android 11 以下沒有自動進入：按 Home 離開時手動進入子母畫面。 */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && vm.player?.isPlaying == true) enterPictureInPictureMode(pipParams())
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        vm.inPip = isInPictureInPictureMode
    }

    override fun onResume() {
        super.onResume()
        BugLog.activity = java.lang.ref.WeakReference(this)
        // 上次閃退：補一份自動回報（附上閃退的堆疊）
        BugLog.pendingCrash()?.let { BugLog.auto("crash", "上次閃退：\n$it") }
        refresh.poke()
        // ADPF：RenderThread 在第一幀後才存在，等畫面畫出來再建立效能提示
        window.decorView.post { com.liquid.browser.core.AdpfHint.start(this, display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 60f) }
        jankStats?.isTrackingEnabled = true
        // 尊重系統「移除動畫」無障礙設定
        Motion.reduced = android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }

    override fun onPause() {
        super.onPause()
        // 視窗還有畫面時先更新目前分頁的縮圖，onStop 存工作階段時一起存下
        if (!isInPictureInPictureMode) vm.current?.snapshot()
        jankStats?.isTrackingEnabled = false
    }

    override fun onStart() {
        super.onStart()
        vm.current?.webView?.onResume()
    }

    override fun onStop() {
        super.onStop()
        vm.saveSession()
        refresh.release()
        // 背景時暫停目前網頁的動畫與額外處理（不暫停 JavaScript，背景播放的音樂不受影響）
        if (!isInPictureInPictureMode) vm.current?.webView?.onPause()
        // 關掉子母畫面視窗或一般離開 App：暫停播放
        if (!isInPictureInPictureMode) vm.player?.player?.pause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 測試用（只有 debug 版）：adb shell am start -n <pkg>/com.liquid.browser.MainActivity --es debug_layout Top
        // 直接切換網址列版面，自動化測試三種版面時不必每次點進設定
        if (BuildConfig.DEBUG) intent.getStringExtra("debug_layout")?.let { name ->
            runCatching { com.liquid.browser.data.BarLayout.valueOf(name) }.getOrNull()?.let { l -> vm.updateSettings { it.copy(barLayout = l) } }
            return
        }
        intent.dataString?.let { vm.newTab(it) }
    }

    override fun onDestroy() {
        com.liquid.browser.core.AdpfHint.stop(this)
        unregisterReceiver(pipReceiver)
        vm.detach()
        super.onDestroy()
    }
}
