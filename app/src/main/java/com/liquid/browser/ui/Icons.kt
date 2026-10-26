package com.liquid.browser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.liquid.browser.R
import com.liquid.browser.core.Palette

/** 強調色：只用在進度條、選取狀態、連結 */
val accent: Color
    @Composable get() = if (isSystemInDarkTheme()) Palette.AccentDark else Palette.AccentLight

// Material Symbols Rounded 可變字型，細線條（wght 300）。圖示用連字輸入名稱：Sym("arrow_back")。
// ponytail: 目前打包完整 15MB 字型；圖示集確定後用 fonttools 子集化到實際用到的字形。
val Symbols = FontFamily(
    Font(
        R.font.material_symbols_rounded,
        FontWeight.Light,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(300),
            FontVariation.Setting("FILL", 0f),
            FontVariation.Setting("GRAD", 0f),
            FontVariation.Setting("opsz", 24f),
        ),
    )
)

@Composable
fun Sym(name: String, color: Color, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    val sp = with(LocalDensity.current) { size.toSp() }
    Text(name, modifier, color = color, fontSize = sp, lineHeight = sp, fontFamily = Symbols, maxLines = 1)
}

/** 圓形點擊區的圖示按鈕。alpha 以 lambda 讀取，動畫時只重繪不重組。 */
@Composable
fun SymButton(
    name: String,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    enabled: Boolean = true,
    alpha: () -> Float = { 1f },
    onClick: () -> Unit,
) {
    Box(
        modifier.size(40.dp).graphicsLayer { this.alpha = alpha() }.clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        Alignment.Center,
    ) { Sym(name, color, size) }
}
