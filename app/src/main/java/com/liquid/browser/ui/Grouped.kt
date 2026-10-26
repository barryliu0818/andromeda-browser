package com.liquid.browser.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.luminance
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import com.liquid.browser.core.Motion
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquid.browser.core.Palette
import com.liquid.browser.ui.glass.Tone

/*
 * 二級頁面共用的「分組清單」（iOS 設定／Safari 的 inset grouped 樣式）：
 * 同一組的列合成一張圓角卡片、列與列之間是內縮的細分隔線、左側彩色圓角圖示代表類別。
 * 每一列各自是 LazyColumn 的一個 item，靠在組內的位置決定圓角，所以長清單仍然是延遲載入。
 */

/** 小米設定頁樣式：一組是一整張白色圓角卡片，列與列直接相接 */
private val GroupRadius = 20.dp
private val InnerRadius = 0.dp

/** 卡片底色：Material 3 表面色階（面板底是 surfaceContainer，卡片是 surfaceContainerLowest；深色是 Low／High） */
fun groupFill(tone: Tone) = if (tone.content.luminance() > 0.5f) Palette.CardDark else Palette.CardLight

/** 第 index 列（共 count 列）的形狀：組的上下兩端大圓角，組內相接處小圓角（Expressive 分段） */
fun groupShape(index: Int, count: Int): Shape {
    val top = if (index == 0) GroupRadius else InnerRadius
    val bottom = if (index == count - 1) GroupRadius else InnerRadius
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** 組內一列的外觀：同一張卡片內相接，不留空隙、不畫分隔線 */
@Suppress("UNUSED_PARAMETER")
fun Modifier.groupItem(index: Int, count: Int, tone: Tone, dividerInset: Dp = 16.dp): Modifier =
    clip(groupShape(index, count)).background(groupFill(tone))

/** 頁面大標題（二級頁面頂端），右側可放動作 */
@Composable
fun PageTitle(text: String, tone: Tone, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), color = tone.content, fontSize = 28.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        actions()
    }
}

/** 組標題：卡片上方的小灰字 */
@Composable
fun GroupHeader(text: String, tone: Tone, modifier: Modifier = Modifier) =
    // 小米設定頁的分組標題：灰色小字
    Text(text, modifier.padding(start = 16.dp, top = 22.dp, bottom = 8.dp), color = Palette.Gray, fontSize = 13.sp)

/** 組下方的說明文字 */
@Composable
fun GroupFooter(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp), color = Palette.Gray, fontSize = 12.sp, lineHeight = 17.sp)

/** 彩色圓角圖示（顏色代表類別：藍＝一般、綠＝網路／翻譯、紅＝危險、橘＝下載…） */
@Composable
fun IconTile(icon: String, tint: Color, size: Dp = 30.dp) =
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.27f)).background(tint), Alignment.Center) {
        Sym(icon, Color.White, size * 0.62f)
    }

/** 有左側圖示時分隔線從文字起點開始（圖示 30dp＋間距 14dp＋左邊距 16dp） */
val IconDividerInset = 60.dp

/**
 * 分組清單的一列：左側 leading（圖示或網站圖示）、標題與副標、右側 trailing。
 * destructive 時標題為紅色（刪除、清除這類動作）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GroupRow(
    index: Int,
    count: Int,
    tone: Tone,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    destructive: Boolean = false,
    titleColor: Color? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth()
            .groupItem(index, count, tone, if (leading != null) IconDividerInset else 16.dp)
            .then(if (onClick != null || onLongClick != null) Modifier.combinedClickable(onLongClick = onLongClick) { onClick?.invoke() } else Modifier)
            .heightIn(min = 58.dp)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Box(Modifier.size(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title, color = titleColor ?: if (destructive) Color(0xFFFF3B30) else tone.content,
                fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrEmpty()) Text(subtitle, color = Palette.Gray, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke(this)
    }
}

/** 開關列（iOS 綠色開關） */
@Composable
fun GroupToggle(
    index: Int,
    count: Int,
    tone: Tone,
    title: String,
    checked: Boolean,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    onChange: (Boolean) -> Unit,
) = GroupRow(index, count, tone, title, subtitle = subtitle, leading = leading, onClick = { onChange(!checked) }, trailing = {
    IosSwitch(checked, tone, Modifier.padding(start = 8.dp), onChange)
})

/** 小米樣式開關：開＝藍底白點，關＝淺灰底白點、無外框。整列可點，開關本身也可點。 */
@Composable
fun IosSwitch(checked: Boolean, tone: Tone, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    val dark = tone.content.luminance() > 0.5f
    androidx.compose.material3.Switch(
        checked, onChange, modifier,
        thumbContent = null,
        colors = androidx.compose.material3.SwitchDefaults.colors(
            checkedThumbColor = Color.White, checkedTrackColor = accent, checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Color.White, uncheckedTrackColor = if (dark) Color(0xFF3A3A3C) else Color(0xFFE5E5E9), uncheckedBorderColor = Color.Transparent,
        ),
    )
}

/** 多選列（例如「清除瀏覽資料」要清哪些）：右側勾選圈，選中時彈一下 */
@Composable
fun GroupPick(
    index: Int,
    count: Int,
    tone: Tone,
    title: String,
    checked: Boolean,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    onChange: (Boolean) -> Unit,
) = GroupRow(index, count, tone, title, subtitle = subtitle, leading = leading, onClick = { onChange(!checked) }, trailing = {
    val pop by animateFloatAsState(if (checked) 1f else 0.85f, Motion.glass(), label = "pick")
    Sym(
        if (checked) "check_circle" else "radio_button_unchecked", if (checked) accent else Palette.Gray, 24.dp,
        Modifier.graphicsLayer { scaleX = pop; scaleY = pop },
    )
})

/**
 * 下拉選單列（iOS 的 pull-down）：右側顯示目前的選擇＋上下箭頭，點一下從右側彈出選單，選中項打勾。
 * 用來取代「整組平鋪的單選清單」，設定頁更短、更好掃讀。
 */
@Composable
fun GroupMenuRow(
    index: Int,
    count: Int,
    tone: Tone,
    title: String,
    options: List<String>,
    selected: Int,
    subtitle: String? = null,
    optionSubtitles: List<String?>? = null,
    leading: (@Composable () -> Unit)? = null,
    onSelect: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val dark = tone.content.luminance() > 0.5f
    Box {
        GroupRow(index, count, tone, title, subtitle = subtitle, leading = leading, onClick = { open = true }, trailing = {
            Text(
                options.getOrElse(selected) { "" }, Modifier.widthIn(max = 170.dp).padding(start = 8.dp),
                color = Palette.Gray, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Sym("chevron_right", Palette.Gray.copy(alpha = 0.7f), 20.dp, Modifier.padding(start = 2.dp))
        })
        // 錨點放在列的右上角：選單從右側彈出、靠右對齊
        Box(Modifier.align(Alignment.TopEnd).padding(end = 8.dp)) {
            DropdownMenu(
                open, { open = false },
                Modifier.widthIn(min = 220.dp),
                shape = RoundedCornerShape(20.dp),
                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer,
                shadowElevation = 8.dp,
            ) {
                options.forEachIndexed { i, label ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(label, color = tone.content, fontSize = 16.sp, fontWeight = FontWeight.Normal)
                                optionSubtitles?.getOrNull(i)?.let { Text(it, color = Palette.Gray, fontSize = 12.sp, lineHeight = 15.sp) }
                            }
                        },
                        onClick = { open = false; if (i != selected) onSelect(i) },
                        trailingIcon = { if (i == selected) Sym("check", accent, 20.dp) },
                    )
                }
            }
        }
    }
}

/** 單選列：選中時右側藍色勾 */
@Composable
fun GroupCheck(index: Int, count: Int, tone: Tone, title: String, checked: Boolean, accent: Color, subtitle: String? = null, subtitleColor: Color? = null, onClick: () -> Unit) =
    GroupRow(index, count, tone, title, subtitle = subtitle, onClick = onClick, trailing = {
        if (checked) Sym("check", accent, 22.dp)
    })

/** 右側箭頭（可點進下一層） */
@Composable
fun Chevron() = Sym("chevron_right", Palette.Gray, 20.dp)
