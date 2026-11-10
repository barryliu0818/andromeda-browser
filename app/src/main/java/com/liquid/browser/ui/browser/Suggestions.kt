package com.liquid.browser.ui.browser

import com.liquid.browser.core.t

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquid.browser.browser.BrowserViewModel
import com.liquid.browser.browser.Visit
import com.liquid.browser.core.Dimens
import com.liquid.browser.ui.Sym
import com.liquid.browser.ui.glass.LiquidGlass
import com.liquid.browser.ui.glass.Tone
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URL
import kotlin.math.min

private val PanelShape = RoundedCornerShape(Dimens.MenuRadius)

/**
 * 網址列上方的建議面板。輸入為空：常用網站格子；有輸入：熱門結果 → 搜尋引擎建議 → 書籤與歷史紀錄。
 * ponytail: 「在此頁面上尋找」項目隨第 4 階段的尋找列一起加入。
 */
@Composable
fun Suggestions(vm: BrowserViewModel, q: String, haze: HazeState, tone: Tone, onPick: (String) -> Unit) {
    // 搜尋引擎建議：背景執行緒取得，150ms 防抖
    // 建議來自使用者選的搜尋引擎；打下一個字時上一個請求連線直接取消（OkHttp）
    val engine = vm.settings.collectAsState().value.engine
    val remote by produceState(emptyList<String>(), q, engine) {
        value = emptyList()
        if (q.isBlank() || q.startsWith("http")) return@produceState
        delay(150)
        value = try {
            val req = okhttp3.Request.Builder().url(engine.suggest + Uri.encode(q)).header("User-Agent", com.liquid.browser.browser.IPHONE_SAFARI_UA).build()
            com.liquid.browser.core.Http.exec(req) { r ->
                JSONArray(r.body.string()).getJSONArray(1).let { a -> List(min(a.length(), 4)) { a.getString(it) } }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }
    }
    // 書籤與歷史紀錄：Room 查詢（suspend，自動在背景執行緒）
    val local by produceState(emptyList<Visit>(), q) {
        value = if (q.isBlank()) emptyList()
        else (vm.matchBookmarks(q).map { Visit(it.url.orEmpty(), it.title) } + vm.matchHistory(q).map { Visit(it.url, it.title) })
            .distinctBy { it.url }.take(5)
    }
    // 熱門結果：最符合的一筆
    val top = local.firstOrNull()

    // 剛點進網址列（尚未輸入）不顯示任何面板，開始打字才出現建議
    if (q.isBlank()) return
    LiquidGlass(haze, tone, PanelShape, Modifier.fillMaxWidth()) {
        LazyColumn(Modifier.heightIn(max = 340.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            if (top != null) {
                item { Header(t("熱門結果"), tone) }
                item { SuggestionRow("public", top.title.ifBlank { top.url }, Uri.parse(top.url).host, tone) { onPick(top.url) } }
            }
            item { Header(t("搜尋建議"), tone) }
            item { SuggestionRow("search", q, null, tone) { onPick(q) } }
            items(remote.filter { it != q }, key = { "r$it" }) { s -> SuggestionRow("search", s, null, tone) { onPick(s) } }
            val rest = local.drop(1)
            if (rest.isNotEmpty()) item { Header(t("書籤與歷史紀錄"), tone) }
            items(rest, key = { "h${it.url}" }) { v -> SuggestionRow("history", v.title.ifBlank { v.url }, Uri.parse(v.url).host, tone) { onPick(v.url) } }
        }
    }
}

@Composable
private fun Header(text: String, tone: Tone) = Text(
    text, Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
    color = tone.content.copy(alpha = 0.55f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
)

@Composable
private fun SuggestionRow(icon: String, title: String, sub: String?, tone: Tone, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Sym(icon, tone.content.copy(alpha = 0.6f), 20.dp)
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, color = tone.content, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, color = tone.content.copy(alpha = 0.55f), fontSize = 13.sp, maxLines = 1)
        }
    }
}
