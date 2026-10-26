package com.liquid.browser.ui.browser

import com.liquid.browser.core.t

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import com.liquid.browser.browser.BrowserViewModel
import com.liquid.browser.browser.Tab
import com.liquid.browser.core.Dimens
import com.liquid.browser.core.Motion
import com.liquid.browser.core.Palette
import com.liquid.browser.ui.Sym
import com.liquid.browser.ui.glass.LiquidGlass
import com.liquid.browser.ui.glass.MenuDivider
import com.liquid.browser.ui.glass.Tone
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import kotlinx.coroutines.launch

/**
 * 長按連結／圖片：背景模糊變暗，預覽以大圓角卡片從觸控位置放大浮現，下方接液態玻璃選單。
 * 點預覽卡片直接開啟連結。
 */
@Composable
fun LinkPreviewHost(vm: BrowserViewModel, haze: HazeState, tone: Tone, webTop: Float) {
    val (tab, hit) = vm.longPress ?: return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val appear = remember(hit) { Animatable(0f) }
    LaunchedEffect(hit) { appear.animateTo(1f, Motion.glass()) }
    val dismiss: () -> Unit = {
        scope.launch {
            appear.animateTo(0f, Motion.timed(Motion.ShortMs))
            vm.longPress = null
        }
    }
    val copy = { text: String ->
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("url", text))
        vm.showToast(t("已拷貝"))
    }
    val link = hit.link
    val items = buildList {
        if (link != null) {
            add(Triple(t("在新分頁開啟"), "add_box") { vm.newTab(link) })
            add(Triple(t("在背景分頁開啟"), "tab") { vm.newTab(link, background = true); vm.showToast(t("已在背景開啟")) })
            add(Triple(t("拷貝連結"), "content_copy") { copy(link) })
            add(Triple(t("分享"), "ios_share") { share(context, link) })
            add(Triple(t("加入閱讀列表"), "eyeglasses") { vm.addToReadingList(link, Uri.parse(link).host.orEmpty(), hit.image) })
        }
        hit.image?.let { img ->
            add(Triple(t("儲存圖片"), "download") { vm.saveImage(tab, img) })
            add(Triple(t("拷貝圖片網址"), "content_copy") { copy(img) })
            if (link == null) add(Triple(t("分享"), "ios_share") { share(context, img) })
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 背景：模糊（Android 12+）＋變暗
        Box(
            Modifier.fillMaxSize()
                .hazeBlur(HazeInput.Sources(haze), remember { HazeBlurStyle { blurRadius(28.dp) } })
                .graphicsLayer { alpha = appear.value.coerceIn(0f, 1f) }
                .background(Color.Black.copy(alpha = 0.25f))
                .pointerInput(hit) { detectTapGestures { dismiss() } },
        )
        var cardPos by remember { mutableStateOf(Offset.Zero) }
        val screenH = maxHeight
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            val cardH = if (link != null && hit.image == null) screenH * 0.5f else screenH * 0.4f
            Box(
                Modifier.fillMaxWidth().height(cardH)
                    .onPlaced { cardPos = it.positionInRoot() }
                    .graphicsLayer {
                        // 從長按位置放大浮現
                        val p = appear.value
                        val s = 0.5f + 0.5f * p
                        scaleX = s
                        scaleY = s
                        alpha = p.coerceIn(0f, 1f)
                        transformOrigin = TransformOrigin(
                            ((hit.x - cardPos.x) / size.width.coerceAtLeast(1f)).coerceIn(0f, 1f),
                            ((hit.y + webTop - cardPos.y) / size.height.coerceAtLeast(1f)).coerceIn(0f, 1f),
                        )
                        shape = RoundedCornerShape(Dimens.CardRadius)
                        clip = true
                        // 裡面有預覽 WebView：不可用離屏圖層做淡入（Vulkan 下 WebView 畫進離屏圖層會閃退），改為逐一調整透明度
                        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                    }
                    .background(Color.White)
                    .clickable {
                        link?.let(tab::load)
                        vm.longPress = null
                    },
            ) {
                if (link != null && hit.image == null) PreviewWebView(vm, tab, link)
                else AsyncImage(hit.image, null, Modifier.fillMaxSize().background(Palette.Ink), contentScale = ContentScale.Fit)
                if (link != null) Text(
                    Uri.parse(link).host?.removePrefix("www.").orEmpty(),
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 14.dp, vertical = 8.dp),
                    color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            LiquidGlass(
                haze, tone, RoundedCornerShape(Dimens.MenuRadius),
                Modifier.width(260.dp).graphicsLayer {
                    alpha = ((appear.value - 0.3f) / 0.5f).coerceIn(0f, 1f)
                    translationY = (1f - appear.value) * 24.dp.toPx()
                },
            ) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    items.forEachIndexed { i, (label, icon, action) ->
                        // 圖片連結：連結項目與圖片項目之間加分隔線
                        if (link != null && i == 5) MenuDivider(tone)
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.longPress = null; action() }.padding(horizontal = 18.dp).height(46.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(label, Modifier.weight(1f), color = tone.content, fontSize = 16.sp)
                            Sym(icon, tone.content, 20.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewWebView(vm: BrowserViewModel, tab: Tab, url: String) {
    val web = remember(url) { vm.previewWebView(url, tab.incognito) }
    DisposableEffect(web) { onDispose { web.destroy() } }
    AndroidView({ web }, Modifier.fillMaxSize())
}
