package com.liquid.browser.player

import com.liquid.browser.core.t

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import com.liquid.browser.core.Motion
import com.liquid.browser.ui.Sym
import com.liquid.browser.ui.glass.GlassMenuHost
import com.liquid.browser.ui.glass.LiquidGlass
import com.liquid.browser.ui.glass.MenuItem
import com.liquid.browser.ui.glass.MenuSpec
import com.liquid.browser.ui.glass.MenuState
import com.liquid.browser.ui.glass.Tone
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.chrisbanes.haze.HazeState
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 右上角速度選單：最高 5 倍 */
private val Speeds = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f, 5f)
private val Tabular = TextStyle(fontFeatureSettings = "tnum")

/**
 * 原生播放器畫面：影片（TextureView，讓液態玻璃控制項能取樣影片畫面）＋手勢層＋手勢回饋＋玻璃控制列。
 * 控制列 3 秒無操作自動隱藏；全螢幕切換時轉為橫向並隱藏系統列；子母畫面時只顯示影片。
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(c: PlayerController, inPip: Boolean, onVideoBounds: (android.graphics.Rect) -> Unit, onDownload: () -> Unit, onClose: () -> Unit) {
    val activity = LocalActivity.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val haze = rememberHazeState()
    val gesture = remember { VideoGestureState() }
    val menu = remember { MenuState() }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var controls by remember { mutableStateOf(true) }
    var touchedAt by remember { mutableLongStateOf(0L) }

    // 播放位置每幀更新，但只在繪製階段與「秒」改變時讀取，避免每幀重組
    val position = remember { mutableLongStateOf(0L) }
    val buffered = remember { mutableLongStateOf(0L) }
    LaunchedEffect(c) {
        while (true) {
            withFrameMillis { }
            position.longValue = c.player.currentPosition
            buffered.longValue = c.player.bufferedPosition
        }
    }
    val seconds by remember { derivedStateOf { position.longValue / 1000 } }

    // 從網頁影片原位置放大到全螢幕；關閉時縮回
    val appear = remember { Animatable(if (c.request.from != null) 0f else 1f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, Motion.standard()) }
    val close: () -> Unit = {
        scope.launch {
            if (c.request.from != null && !landscape) appear.animateTo(0f, Motion.timed(Motion.MediumMs))
            onClose()
        }
    }
    BackHandler(!inPip) { if (menu.open) menu.dismiss() else close() }

    // 隱藏系統列；離開時恢復並解除方向鎖定
    DisposableEffect(Unit) {
        releasePortraitHold(activity)
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activity?.let(::backToPortrait)
            activity?.window?.attributes = activity?.window?.attributes?.apply { screenBrightness = -1f }
        }
    }

    // 3 秒無操作自動隱藏控制列
    LaunchedEffect(controls, touchedAt, c.isPlaying) {
        if (controls && c.isPlaying) {
            delay(3_000)
            controls = false
        }
    }
    val touch = { touchedAt = System.currentTimeMillis() }

    val audio = remember { activity?.getSystemService(AudioManager::class.java) }
    val volume = remember {
        mutableFloatStateOf(audio?.let { it.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / it.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } ?: 0.5f)
    }
    val actions = remember(c) {
        object : VideoGestureActions {
            override fun singleTap() { controls = !controls; touch() }
            override fun togglePlay() { c.togglePlay(); touch() }
            override fun seekBy(deltaMs: Long) = c.seekBy(deltaMs)
            override fun seekTo(ms: Long) = c.seekTo(ms)
            override fun position() = c.player.currentPosition
            override fun duration() = c.durationMs
            override fun setBoost(on: Boolean) {
                if (on) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                c.setBoost(on)
            }
            override fun adjustBrightness(delta: Float): Float {
                val w = activity?.window ?: return 0f
                val cur = w.attributes.screenBrightness.takeIf { it >= 0 } ?: 0.5f
                val v = (cur + delta).coerceIn(0.01f, 1f)
                w.attributes = w.attributes.apply { screenBrightness = v }
                return v
            }
            override fun adjustVolume(delta: Float): Float {
                val a = audio ?: return 0f
                val max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                volume.floatValue = (volume.floatValue + delta).coerceIn(0f, 1f)
                a.setStreamVolume(AudioManager.STREAM_MUSIC, (volume.floatValue * max).toInt(), 0)
                return volume.floatValue
            }
        }
    }

    var speedBounds by remember { mutableStateOf(Rect.Zero) }
    val showSpeed = {
        menu.show(MenuSpec(speedBounds, Speeds.map { sp -> MenuItem(speedLabel(sp), if (sp == c.speed) "check" else null) { c.setPlaybackSpeed(sp) } }, width = 160))
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // 影片
        // 沒有原生 backdrop 的手機玻璃不做即時模糊：不必每幀把影片錄進模糊來源
        Box(Modifier.fillMaxSize().then(if (com.liquid.browser.ui.glass.PlatformBackdrop) Modifier.hazeSource(haze) else Modifier), Alignment.Center) {
            var full by remember { mutableStateOf(Rect.Zero) }
            Box(
                Modifier.fillMaxSize().onGloballyPositioned { full = it.boundsInRoot() },
                Alignment.Center,
            ) {
                Box(
                    Modifier.aspectRatio(c.videoAspect)
                        .onGloballyPositioned { coords ->
                            val b = coords.boundsInWindow()
                            onVideoBounds(android.graphics.Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt()))
                        }
                        .graphicsLayer {
                            val from = c.request.from ?: return@graphicsLayer
                            val p = appear.value
                            if (p >= 1f || full.width == 0f) return@graphicsLayer
                            // 從網頁中的影片位置與大小放大
                            scaleX = lerp(from.width / size.width, 1f, p)
                            scaleY = lerp(from.height / size.height, 1f, p)
                            translationX = lerp(from.center.x - full.center.x, 0f, p)
                            translationY = lerp(from.center.y - full.center.y, 0f, p)
                        },
                ) {
                    AndroidView(
                        factory = { ctx -> TextureView(ctx).also { c.player.setVideoTextureView(it) } },
                        modifier = Modifier.fillMaxSize(),
                        onRelease = { c.player.clearVideoTextureView(it) },
                    )
                }
            }
        }

        if (!inPip) {
            // 手勢層
            Box(Modifier.fillMaxSize().videoGestures(gesture, actions, scope))
            GestureFeedback(gesture, haze)
            c.error?.let {
                // 放在中央按鈕上方，不與按鈕重疊
                Text(
                    t("無法播放・%1\$s", it), Modifier.align(Alignment.Center).offset(y = (-96).dp).padding(horizontal = 24.dp),
                    color = Color.White, fontSize = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }

            AnimatedVisibility(
                controls,
                enter = fadeIn(Motion.timed(Motion.ShortMs)) + slideInVertically(Motion.standard()) { it / 40 },
                exit = fadeOut(Motion.timed(Motion.ShortMs)) + slideOutVertically(Motion.timed(Motion.ShortMs)) { it / 40 },
            ) {
                Box(Modifier.fillMaxSize()) {
                    // 上下淡漸層：底部有毛玻璃面板，漸層只需讓頂部白字在亮畫面上清楚
                    Box(Modifier.fillMaxWidth().height(120.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent))))
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(if (landscape) 160.dp else 140.dp)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = if (landscape) 0.6f else 0.35f)))),
                    )

                    // 頂部：返回｜標題＋網域｜（直向）速度；橫向兩側多留一點邊
                    Row(
                        Modifier.fillMaxWidth().statusBarsPadding().displayCutoutPadding().padding(horizontal = if (landscape) 20.dp else 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        PlayerGlass(haze, Modifier.size(44.dp).semantics { contentDescription = t("關閉播放器") }, onClick = { close() }) {
                            Sym(if (landscape) "chevron_left" else "keyboard_arrow_down", Color.White, 26.dp)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(c.request.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val host = c.request.referer?.let { android.net.Uri.parse(it).host?.removePrefix("www.") }
                            if (!host.isNullOrEmpty()) Text(host, color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp, maxLines = 1)
                        }
                        // 直向：速度在右上角；橫向放到底部那一列
                        if (!landscape) PlayerGlass(haze, Modifier.height(44.dp).onGloballyPositioned { speedBounds = it.boundsInRoot() }) {
                            SpeedButton(c.speed) { touch(); showSpeed() }
                        }
                    }

                    // 中央不放按鈕：播放／暫停、快轉倒退全部用手勢（雙擊中間／左右、長按 2 倍速、水平拖動）

                    val shown = gesture.scrub?.targetMs ?: (seconds * 1000)
                    val seek: @Composable (Modifier) -> Unit = { m ->
                        SeekBar(
                            position = { gesture.scrub?.targetMs ?: position.longValue },
                            buffered = { buffered.longValue },
                            duration = { c.durationMs },
                            onSeek = { c.seekTo(it); touch() },
                            onDragging = touch,
                            modifier = m,
                        )
                    }
                    if (landscape) {
                        // 橫向：兩列，直接放在底部漸層上（不加面板，影片露出最多）。
                        // 上列：整條寬的進度條；下列：左邊播放／暫停＋時間，右邊速度｜下載｜子母畫面｜結束全螢幕（下載在右下角）
                        Column(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().displayCutoutPadding()
                                .padding(start = 20.dp, end = 20.dp, bottom = 6.dp),
                        ) {
                            seek(Modifier.fillMaxWidth())
                            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                                PlayerIcon(if (c.isPlaying) "pause" else "play_arrow", if (c.isPlaying) t("暫停") else t("播放"), size = 30.dp) { c.togglePlay(); touch() }
                                Text(
                                    "${formatTime(shown)} / ${formatTime(c.durationMs)}", Modifier.padding(start = 8.dp),
                                    color = Color.White, fontSize = 14.sp, style = Tabular,
                                )
                                Spacer(Modifier.weight(1f))
                                Box(Modifier.onGloballyPositioned { speedBounds = it.boundsInRoot() }) { SpeedButton(c.speed) { touch(); showSpeed() } }
                                PlayerIcon("download", t("下載影片")) { touch(); onDownload() }
                                if (android.os.Build.VERSION.SDK_INT >= 26) PlayerIcon("picture_in_picture_alt", t("子母畫面"), size = 22.dp) {
                                    runCatching { activity?.enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().build()) }
                                }
                                PlayerIcon("fullscreen_exit", t("結束全螢幕"), size = 26.dp) {
                                    touch()
                                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                                }
                            }
                        }
                    } else {
                        // 直向：單行半透明窄條（時間｜進度條｜剩餘｜子母畫面｜全螢幕｜下載），不模糊，不遮住影片
                        PlayerGlass(
                            haze,
                            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().displayCutoutPadding()
                                .padding(horizontal = 12.dp, vertical = 10.dp).widthIn(max = 720.dp).fillMaxWidth().height(44.dp),
                        ) { Row(Modifier.fillMaxSize().padding(start = 14.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(formatTime(shown), color = Color.White, fontSize = 12.sp, style = Tabular)
                            seek(Modifier.weight(1f).padding(horizontal = 10.dp))
                            Text("-${formatTime((c.durationMs - shown).coerceAtLeast(0))}", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, style = Tabular)
                            if (android.os.Build.VERSION.SDK_INT >= 26) PlayerIcon("picture_in_picture_alt", t("子母畫面"), box = 38.dp, size = 20.dp) {
                                runCatching { activity?.enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().build()) }
                            }
                            PlayerIcon("fullscreen", t("全螢幕"), box = 38.dp) {
                                touch()
                                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            }
                            PlayerIcon("download", t("下載影片"), box = 38.dp) { touch(); onDownload() }
                        } }
                    }
                }
            }
            GlassMenuHost(menu, haze, Tone.InputDark)
        }
    }
}

/** 播放器的圖示按鈕（圓形點擊範圍） */
@Composable
private fun PlayerIcon(icon: String, label: String, box: androidx.compose.ui.unit.Dp = 44.dp, size: androidx.compose.ui.unit.Dp = 24.dp, onClick: () -> Unit) =
    Box(Modifier.size(box).clip(CircleShape).clickable(onClickLabel = label, onClick = onClick), Alignment.Center) { Sym(icon, Color.White, size) }

/** 播放速度（點了打開速度選單） */
@Composable
private fun SpeedButton(speed: Float, onClick: () -> Unit) =
    Text(
        speedLabel(speed),
        Modifier.clip(CircleShape).clickable(onClickLabel = t("播放速度"), onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, style = Tabular,
    )

/** 控制元件的半透明底：不模糊，影片畫面透得過來，維持觀影沉浸感 */

private fun speedLabel(s: Float) = if (s == s.toInt().toFloat()) "${s.toInt()}x" else "${s}x"

/**
 * 進度條：已播放、已緩衝、可拖動的圓點；拖動中只預覽，放開才跳轉。
 * 拖動時軌道變粗、圓點以彈簧放大，上方浮出目標時間泡泡。軌道與圓點在 Canvas 繪製，不重組。
 */
@Composable
private fun SeekBar(position: () -> Long, buffered: () -> Long, duration: () -> Long, onSeek: (Long) -> Unit, onDragging: () -> Unit, modifier: Modifier = Modifier) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val active by animateFloatAsState(if (drag != null) 1f else 0f, Motion.glass(), label = "seek")
    val measurer = rememberTextMeasurer()
    Canvas(
        modifier.fillMaxWidth().height(36.dp)
            .pointerInput(Unit) {
                detectTapGestures { o -> onSeek((o.x / size.width * duration()).toLong()) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f); onDragging() },
                    onDragEnd = { drag?.let { onSeek((it * duration()).toLong()) }; drag = null },
                    onDragCancel = { drag = null },
                ) { change, dx ->
                    change.consume()
                    drag = ((drag ?: 0f) + dx / size.width).coerceIn(0f, 1f)
                    onDragging()
                }
            },
    ) {
        val d = duration().coerceAtLeast(1).toFloat()
        val played = drag ?: (position() / d).coerceIn(0f, 1f)
        val buf = (buffered() / d).coerceIn(0f, 1f)
        val h = (3 + 3 * active).dp.toPx()
        val cy = size.height / 2
        val y = cy - h / 2
        val r = CornerRadius(h / 2)
        drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(0f, y), Size(size.width, h), r)
        drawRoundRect(Color.White.copy(alpha = 0.4f), Offset(0f, y), Size(size.width * buf, h), r)
        drawRoundRect(Color.White, Offset(0f, y), Size(size.width * played, h), r)
        val x = size.width * played
        drawCircle(Color.White, radius = (5 + 4 * active).dp.toPx(), center = Offset(x, cy))
        // 拖動中的時間泡泡
        if (active > 0.01f) {
            val t = measurer.measure(formatTime((played * d).toLong()), TextStyle(color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"))
            val pw = t.size.width + 16.dp.toPx()
            val ph = t.size.height + 6.dp.toPx()
            val left = (x - pw / 2).coerceIn(0f, size.width - pw)
            val top = cy - 14.dp.toPx() - ph - 6.dp.toPx() * (1 - active)
            drawRoundRect(Color.White.copy(alpha = active), Offset(left, top), Size(pw, ph), CornerRadius(ph / 2))
            drawText(t, topLeft = Offset(left + 8.dp.toPx(), top + 3.dp.toPx()), alpha = active)
        }
    }
}

/**
 * 播放器的按鈕外觀：跟 App 其他按鈕同一套玻璃（深色色調＋高光邊框、膠囊形）。
 * 不開系統模糊：播放中每幀都要重新模糊影片會讓播放卡頓。
 */
@Composable
private fun PlayerGlass(haze: HazeState, modifier: Modifier, onClick: (() -> Unit)? = null, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) =
    androidx.compose.runtime.CompositionLocalProvider(com.liquid.browser.ui.glass.LocalInBlurWindow provides true) {
        com.liquid.browser.ui.glass.LiquidGlass(haze, Tone.Dark, CircleShape, modifier, systemBlur = false, onClick = onClick, content = content)
    }

/**
 * 離開播放器：網頁回到直向（即使手機還橫拿著）。等手機真的轉回直向才解除鎖定，之後照常可以轉橫向瀏覽；
 * 不立刻解除是因為手還橫拿著時，系統會馬上又把網頁轉回橫向
 */
internal fun backToPortrait(activity: android.app.Activity) {
    val main = activity as? com.liquid.browser.MainActivity
    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
    // 轉回直向前擋下網頁核心晚到的轉向（screen.orientation.unlock）：見 MainActivity.holdPortrait
    main?.holdPortrait = true
    val release = {
        main?.holdPortrait = false
        if (activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT)
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    val listener = object : android.view.OrientationEventListener(activity) {
        override fun onOrientationChanged(deg: Int) {
            if (deg == ORIENTATION_UNKNOWN || (deg in 30..330)) return
            disable()
            release()
        }
    }
    if (listener.canDetectOrientation()) listener.enable() else release()
}

/** 播放器或網頁全螢幕開始：不再只限直向（允許轉橫向） */
internal fun releasePortraitHold(activity: android.app.Activity?) {
    (activity as? com.liquid.browser.MainActivity)?.holdPortrait = false
}