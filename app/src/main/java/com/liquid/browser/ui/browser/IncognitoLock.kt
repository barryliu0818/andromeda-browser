package com.liquid.browser.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquid.browser.core.t
import com.liquid.browser.ui.Sym

/**
 * 無痕分頁鎖定畫面（Chrome「離開時鎖定無痕分頁」）：黑底、鎖頭、「解鎖無痕分頁」與「查看一般分頁」。
 * 一出現就跳出驗證；取消後留在這個畫面，可以再按解鎖。
 */
@Composable
fun IncognitoLock(onUnlock: () -> Unit, onNormal: () -> Unit) {
    LaunchedEffect(Unit) { onUnlock() }
    Box(
        // 擋住底下所有觸控
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
        Alignment.Center,
    ) {
        Column(Modifier.padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.1f)), Alignment.Center) {
                Sym("lock", Color.White, 34.dp)
            }
            Text(t("無痕分頁已鎖定"), Modifier.padding(top = 20.dp), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(
                t("用指紋或螢幕鎖解鎖後繼續瀏覽"), Modifier.padding(top = 8.dp),
                color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 21.sp,
            )
            Box(
                Modifier.padding(top = 28.dp).widthIn(min = 220.dp).height(48.dp).clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF0A84FF)).clickable(onClick = onUnlock).padding(horizontal = 24.dp),
                Alignment.Center,
            ) { Text(t("解鎖無痕分頁"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            Text(
                t("查看一般分頁"),
                Modifier.padding(top = 12.dp).clip(CircleShape).clickable(onClick = onNormal).padding(horizontal = 16.dp, vertical = 10.dp),
                color = Color(0xFF0A84FF), fontSize = 16.sp,
            )
        }
    }
}
