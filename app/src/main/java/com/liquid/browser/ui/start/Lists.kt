package com.liquid.browser.ui.start

import com.liquid.browser.core.t

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.liquid.browser.browser.BrowserViewModel
import com.liquid.browser.data.Bookmark
import com.liquid.browser.data.HistoryEntry
import com.liquid.browser.core.Palette
import com.liquid.browser.ui.Reorder
import com.liquid.browser.ui.Sym
import com.liquid.browser.ui.SymButton
import com.liquid.browser.ui.accent
import androidx.compose.foundation.lazy.itemsIndexed
import com.liquid.browser.ui.groupFill
import com.liquid.browser.ui.groupItem
import com.liquid.browser.ui.IconTile
import com.liquid.browser.ui.GroupRow
import com.liquid.browser.ui.GroupFooter
import com.liquid.browser.ui.GroupHeader
import com.liquid.browser.ui.PageTitle
import com.liquid.browser.ui.reorderItem
import com.liquid.browser.ui.glass.MenuItem
import com.liquid.browser.ui.glass.MenuSpec
import com.liquid.browser.ui.glass.MenuState
import com.liquid.browser.ui.glass.Tone
import kotlinx.coroutines.launch
import java.util.Calendar

/** 書籤頁：資料夾導覽、編輯模式（刪除、重新命名、長按拖曳排序）、建立資料夾。 */
@Composable
fun BookmarksPage(vm: BrowserViewModel, content: Color, tone: Tone, padding: PaddingValues, onOpen: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val dao = vm.db.bookmarks()
    val icons by vm.icons.collectAsState()
    var path by remember { mutableStateOf(listOf<Bookmark>()) }
    val parent = path.lastOrNull()
    val items by remember(parent?.id) { dao.children(parent?.id) }.collectAsState(emptyList())
    val local = remember(items) { items.toMutableStateList() }
    var editing by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf<Bookmark?>(null) } // 新資料夾（id=0）或要重新命名的項目
    val list = rememberLazyListState()
    val reorder = remember(list) {
        Reorder<Long>(
            positionOf = { k -> list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == k }?.let { Offset(0f, it.offset.toFloat()) } },
            keyAt = { p -> list.layoutInfo.visibleItemsInfo.firstOrNull { it.key is Long && p.y.toInt() in it.offset until it.offset + it.size }?.key as Long? },
            move = { a, b ->
                val i = local.indexOfFirst { it.id == a }
                val j = local.indexOfFirst { it.id == b }
                if (i >= 0 && j >= 0) local.add(j, local.removeAt(i))
            },
        )
    }
    val persistOrder = { scope.launch { dao.update(local.mapIndexed { i, b -> b.copy(position = i) }) } }

    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = padding) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (parent != null) SymButton("chevron_left", content, size = 28.dp) { path = path.dropLast(1); editing = false }
                PageTitle(parent?.title ?: t("書籤"), tone, Modifier.weight(1f).padding(start = if (parent != null) 0.dp else 4.dp)) {
                    if (editing) TextAction(t("新增資料夾"), accent) { naming = Bookmark(title = "", isFolder = true, parentId = parent?.id) }
                    TextAction(if (editing) t("完成") else t("編輯"), accent) { editing = !editing; naming = null }
                }
            }
        }
        naming?.let { target ->
            item(key = "naming") {
                NameField(target.title, content) { name ->
                    scope.launch {
                        if (name.isNotBlank()) {
                            if (target.id == 0L) dao.insert(target.copy(title = name, position = dao.nextPosition(target.parentId)))
                            else dao.update(listOf(target.copy(title = name)))
                        }
                        naming = null
                    }
                }
            }
        }
        if (local.isEmpty()) item(key = "empty") { EmptyState("bookmark", t("還沒有書籤"), t("開啟網頁後，從 ⋯ 選單點「加入書籤」"), tone) }
        else item(key = "gap") { Spacer(Modifier.size(8.dp)) }
        itemsIndexed(local, key = { _, b -> b.id }) { i, b ->
            Row(
                Modifier.animateItem().then(if (editing) Modifier.reorderItem(reorder, b.id) { persistOrder() } else Modifier)
                    .fillMaxWidth()
                    .groupItem(i, local.size, tone, 64.dp)
                    .clickable {
                        when {
                            editing -> naming = b
                            b.isFolder -> path = path + b
                            else -> b.url?.let(onOpen)
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (editing) SymButton("do_not_disturb_on", Color(0xFFFF3B30), size = 22.dp) { scope.launch { dao.delete(b.id) } }
                if (b.isFolder) IconTile("folder", Color(0xFFFFB300), 36.dp)
                else SiteIcon(Uri.parse(b.url).host?.removePrefix("www.").orEmpty(), icons, 36.dp, 9.dp)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(b.title.ifBlank { b.url.orEmpty() }, color = content, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!b.isFolder) Text(Uri.parse(b.url).host.orEmpty(), color = Palette.Gray, fontSize = 13.sp, maxLines = 1)
                }
                if (editing) Sym("drag_handle", Palette.Gray, 22.dp, Modifier.padding(horizontal = 8.dp))
                else if (b.isFolder) Sym("chevron_right", Palette.Gray, 20.dp)
            }
        }
    }
}

/** 閱讀列表頁：縮圖、標題、網域；未讀有藍點；長按可標示已讀或刪除。 */
@Composable
fun ReadingListPage(vm: BrowserViewModel, menu: MenuState, content: Color, tone: Tone, padding: PaddingValues, onOpen: (String) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val dao = vm.db.reading()
    val items by remember { dao.all() }.collectAsState(emptyList())
    val accent = accent
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item(key = "header") {
            PageTitle(t("閱讀列表"), tone, Modifier.padding(start = 4.dp))
            if (items.isNotEmpty()) GroupFooter(t("%1\$s 篇未讀・長按可標示已讀或刪除", items.count { !it.read }), Modifier.padding(bottom = 10.dp))
        }
        if (items.isEmpty()) item(key = "empty") { EmptyState("eyeglasses", t("閱讀列表是空的"), t("從 ⋯ 選單或長按連結「加入閱讀列表」，之後慢慢看"), tone) }
        itemsIndexed(items, key = { _, r -> r.url }) { i, r ->
            var bounds by remember { mutableStateOf(Rect.Zero) }
            Row(
                Modifier.animateItem()
                    .fillMaxWidth()
                    .groupItem(i, items.size, tone, 112.dp)
                    .onGloballyPositioned { bounds = it.boundsInRoot() }
                    .combinedClickable(
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            menu.show(
                                MenuSpec(
                                    bounds,
                                    listOf(
                                        MenuItem(if (r.read) t("標示為未讀") else t("標示為已讀"), "mark_email_read") { scope.launch { dao.setRead(r.url, !r.read) } },
                                        MenuItem(t("在新分頁開啟"), "add_box") { vm.newTab(r.url) },
                                        MenuItem(t("刪除"), "delete", destructive = true) { scope.launch { dao.delete(r.url) } },
                                    ),
                                ),
                            )
                        },
                    ) {
                        scope.launch { dao.setRead(r.url, true) }
                        onOpen(r.url)
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (r.read) Color.Transparent else accent))
                Box(Modifier.padding(start = 8.dp).size(72.dp, 54.dp).clip(RoundedCornerShape(10.dp)).background(content.copy(alpha = 0.08f))) {
                    if (r.thumbnail != null) AsyncImage(r.thumbnail, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(r.title.ifBlank { r.url }, color = if (r.read) content.copy(alpha = 0.6f) else content, fontSize = 15.sp, fontWeight = if (r.read) FontWeight.Normal else FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(Uri.parse(r.url).host?.removePrefix("www.").orEmpty(), color = Palette.Gray, fontSize = 13.sp, maxLines = 1)
                }
            }
        }
    }
}

/** 歷史紀錄頁：頂部搜尋、依日期分組（今天／昨天／本週／更早）、長按刪除、底部清除（可選時間範圍）。 */
@Composable
fun HistoryPage(vm: BrowserViewModel, menu: MenuState, content: Color, tone: Tone, padding: PaddingValues, onOpen: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val dao = vm.db.history()
    val icons by vm.icons.collectAsState()
    var q by remember { mutableStateOf("") }
    val items by remember(q) { dao.search(q) }.collectAsState(emptyList())
    val groups = remember(items) { groupByDay(items) }
    var clearBounds by remember { mutableStateOf(Rect.Zero) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item(key = "header") { PageTitle(t("歷史紀錄"), tone, Modifier.padding(start = 4.dp)) }
        item(key = "search") {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp).height(42.dp).clip(RoundedCornerShape(12.dp)).background(groupFill(tone)).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Sym("search", Palette.Gray, 20.dp)
                Box(Modifier.weight(1f).padding(start = 8.dp)) {
                    if (q.isEmpty()) Text(t("搜尋歷史紀錄"), color = Palette.Gray, fontSize = 15.sp)
                    BasicTextField(q, { q = it }, singleLine = true, textStyle = TextStyle(color = content, fontSize = 15.sp), cursorBrush = SolidColor(accent))
                }
            }
        }
        if (items.isEmpty()) item(key = "empty") {
            if (q.isEmpty()) EmptyState("history", t("沒有歷史紀錄"), t("瀏覽過的網頁會依日期列在這裡（無痕分頁不會記錄）"), tone)
            else EmptyState("search_off", t("找不到「%1\$s」", q), t("試試網址的一部分或其他關鍵字"), tone)
        }
        groups.forEach { (label, entries) ->
            item(key = "g$label") { GroupHeader(label, tone, Modifier.animateItem()) }
            itemsIndexed(entries, key = { _, e -> e.id }) { i, e ->
                // 起始頁的左右滑動用來切換分段，所以刪除改用長按選單
                SiteRow(
                    e.url, e.title.ifBlank { e.url }, "${e.host}・${timeLabel(e.visitedAt)}", icons, content, Modifier.animateItem(), Triple(i, entries.size, tone),
                    onLongClick = { anchor ->
                        menu.show(
                            MenuSpec(
                                anchor,
                                listOf(
                                    MenuItem(t("在新分頁開啟"), "add_box") { vm.newTab(e.url) },
                                    MenuItem(t("在背景分頁開啟"), "tab") { vm.newTab(e.url, background = true) },
                                    MenuItem(t("刪除"), "delete", destructive = true) { scope.launch { dao.delete(e.id) } },
                                ),
                            ),
                        )
                    },
                ) { onOpen(e.url) }
            }
        }
        if (items.isNotEmpty() && q.isEmpty()) item(key = "clear") {
            Box(Modifier.fillMaxWidth().padding(top = 24.dp)) {
                GroupRow(
                    0, 1, tone, t("清除歷史紀錄…"), destructive = true, leading = { IconTile("delete", Color(0xFFFF3B30)) },
                    modifier = Modifier.onGloballyPositioned { clearBounds = it.boundsInRoot() },
                    onClick = {
                            val now = System.currentTimeMillis()
                            fun clear(since: Long) = MenuItem(rangeLabel(since, now), destructive = true) { scope.launch { dao.deleteSince(since) } }
                            menu.show(
                                MenuSpec(
                                    clearBounds,
                                    listOf(clear(now - 3_600_000), clear(startOfDay(0)), clear(startOfDay(-1)), clear(0)),
                                ),
                            )
                    },
                )
            }
        }
    }
}

private fun rangeLabel(since: Long, now: Long) = when {
    since == 0L -> t("清除所有紀錄")
    now - since <= 3_600_000 -> t("清除最近一小時")
    since == startOfDay(0) -> t("清除今天")
    else -> t("清除今天和昨天")
}

/** 往前 daysAgo 天（0=今天、-1=昨天）的 00:00 時間戳記。 */
fun startOfDay(daysAgo: Int): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    add(Calendar.DAY_OF_YEAR, daysAgo)
}.timeInMillis

private fun groupByDay(items: List<HistoryEntry>): List<Pair<String, List<HistoryEntry>>> {
    val today = startOfDay(0)
    val yesterday = startOfDay(-1)
    val week = startOfDay(-6)
    return items.groupBy {
        when {
            it.visitedAt >= today -> t("今天")
            it.visitedAt >= yesterday -> t("昨天")
            it.visitedAt >= week -> t("本週")
            else -> t("更早")
        }
    }.toList()
}

@Composable
private fun TextAction(text: String, color: Color, onClick: () -> Unit) =
    Text(text, Modifier.clip(CircleShape).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp), color = color, fontSize = 16.sp)

/** 歷史紀錄的時間：今天顯示「14:05」，其他日子顯示「10/19 14:05」 */
private fun timeLabel(at: Long): String =
    java.text.SimpleDateFormat(if (at >= startOfDay(0)) "HH:mm" else "M/d HH:mm", java.util.Locale.getDefault()).format(java.util.Date(at))

/** 空清單：大圖示＋標題＋一句說明（告訴使用者怎麼開始） */
@Composable
private fun EmptyState(icon: String, title: String, hint: String, tone: Tone) {
    Column(Modifier.fillMaxWidth().padding(top = 56.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(groupFill(tone)), Alignment.Center) { Sym(icon, tone.content.copy(alpha = 0.55f), 30.dp) }
        Text(title, Modifier.padding(top = 16.dp), color = tone.content, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(hint, Modifier.padding(top = 6.dp), color = Palette.Gray, fontSize = 14.sp, lineHeight = 20.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun EmptyNote(text: String) =
    Text(text, Modifier.fillMaxWidth().padding(vertical = 32.dp), color = Palette.Gray, fontSize = 15.sp)

/** 書籤資料夾命名／重新命名的行內輸入框。 */
@Composable
private fun NameField(initial: String, content: Color, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).height(44.dp).clip(RoundedCornerShape(12.dp))
            .background(content.copy(alpha = 0.07f)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) Text(t("名稱"), color = Palette.Gray, fontSize = 16.sp)
            BasicTextField(
                text, { text = it }, singleLine = true,
                textStyle = TextStyle(color = content, fontSize = 16.sp), cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone(text) }),
            )
        }
        Spacer(Modifier.size(8.dp))
        TextAction(t("儲存"), accent) { onDone(text) }
    }
}
