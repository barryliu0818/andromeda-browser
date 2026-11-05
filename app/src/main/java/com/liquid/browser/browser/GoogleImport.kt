package com.liquid.browser.browser

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.liquid.browser.data.AppDatabase
import com.liquid.browser.data.Bookmark
import com.liquid.browser.data.HistoryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.zip.ZipInputStream

/**
 * 從 Google 匯入（Google 不開放第三方瀏覽器使用 Chrome 同步，只能匯入匯出檔）：
 *  - 書籤：Bookmarks.html（Google 匯出資料 Chrome 資料夾，或 Chrome 書籤管理員匯出），放進「Google 書籤」資料夾，保留子資料夾；
 *  - 瀏覽記錄：BrowserHistory.json（Google 匯出資料）；
 *  - 密碼：Google 密碼管理工具匯出的 CSV（name,url,username,password,note）。
 * 可以選整個 Google 匯出資料的 zip，或單一檔案。重複匯入不會重複（書籤看網址、記錄看網址＋時間、密碼看網站＋帳號）。
 */
object GoogleImport {
    sealed class Node {
        data class Folder(val title: String, val children: List<Node>) : Node()
        data class Link(val title: String, val url: String) : Node()
    }

    data class History(val url: String, val title: String, val at: Long)

    data class Result(var bookmarks: Int = 0, var history: Int = 0, var passwords: Int = 0, var files: Int = 0)

    // ------------------------------------------------------------ 解析（純函式，有單元測試）

    /** Netscape 書籤 HTML：<DT><H3>資料夾</H3><DL> … </DL>、<DT><A HREF="網址">標題</A> */
    fun parseBookmarks(html: String): List<Node> {
        val token = Regex("""<H3[^>]*>(.*?)</H3>|<A\s[^>]*HREF="([^"]*)"[^>]*>(.*?)</A>|<DL>|</DL>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val stack = ArrayDeque<Pair<String, MutableList<Node>>>()
        stack.addLast("" to mutableListOf())
        var pendingFolder: String? = null
        var depth = 0
        for (m in token.findAll(html)) {
            val t = m.value
            when {
                m.groups[1] != null -> pendingFolder = unescape(m.groupValues[1])
                m.groups[2] != null -> {
                    val url = unescape(m.groupValues[2])
                    if (url.startsWith("http")) stack.last().second += Node.Link(unescape(m.groupValues[3]).ifBlank { url }, url)
                }
                t.equals("<DL>", true) -> {
                    depth++
                    // 第一層 <DL> 是根目錄本身，不是資料夾
                    if (depth > 1) stack.addLast((pendingFolder ?: "") to mutableListOf())
                    pendingFolder = null
                }
                else -> { // </DL>
                    if (depth > 1 && stack.size > 1) {
                        val (title, kids) = stack.removeLast()
                        stack.last().second += Node.Folder(title, kids)
                    }
                    depth--
                }
            }
        }
        while (stack.size > 1) { val (title, kids) = stack.removeLast(); stack.last().second += Node.Folder(title, kids) }
        return stack.first().second
    }

    /** Google 匯出資料的 BrowserHistory.json：{"Browser History":[{"title","url","time_usec"}]} */
    fun parseHistory(json: String): List<History> {
        val arr = JSONObject(json).optJSONArray("Browser History") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url")
            if (!url.startsWith("http")) return@mapNotNull null
            History(url, o.optString("title"), o.optLong("time_usec") / 1000)
        }
    }

    /** 密碼 CSV（依標題列找欄位，支援引號、逗號、換行）；只收網站（http），App 的密碼（android://）略過 */
    fun parsePasswords(csv: String): List<Credential> {
        val rows = parseCsv(csv)
        if (rows.isEmpty()) return emptyList()
        val head = rows[0].map { it.trim().lowercase() }
        val iu = head.indexOf("url"); val iUser = head.indexOf("username"); val ip = head.indexOf("password")
        if (iu < 0 || ip < 0) return emptyList()
        return rows.drop(1).mapNotNull { r ->
            val u = runCatching { java.net.URI(r.getOrNull(iu)?.trim()) }.getOrNull() ?: return@mapNotNull null
            if (u.scheme?.startsWith("http") != true || u.host.isNullOrEmpty()) return@mapNotNull null
            val pass = r.getOrNull(ip).orEmpty()
            if (pass.isEmpty()) return@mapNotNull null
            Credential("${u.scheme}://${u.host}${if (u.port > 0) ":${u.port}" else ""}", r.getOrNull(iUser).orEmpty(), pass)
        }
    }

    fun parseCsv(s: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                quoted && c == '"' && s.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> { row += cell.toString(); cell.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && s.getOrNull(i + 1) == '\n') i++
                    row += cell.toString(); cell.clear()
                    if (row.any { it.isNotEmpty() }) rows += row
                    row = mutableListOf()
                }
                else -> cell.append(c)
            }
            i++
        }
        row += cell.toString()
        if (row.any { it.isNotEmpty() }) rows += row
        return rows
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").trim()

    // ------------------------------------------------------------ 匯入

    /** 匯入選到的檔案（zip 或單一檔案）；回傳各類新增筆數 */
    suspend fun import(context: Context, uri: Uri): Result = withContext(Dispatchers.IO) {
        val res = Result()
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext res
        if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = e.name.substringAfterLast('/').lowercase()
                    if (!e.isDirectory && (name.endsWith(".html") || name.endsWith(".json") || name.endsWith(".csv")))
                        importText(context, zip.readBytes().toString(Charsets.UTF_8), res)
                }
            }
        } else importText(context, bytes.toString(Charsets.UTF_8), res)
        res
    }

    private suspend fun importText(context: Context, text: String, res: Result) {
        val head = text.trimStart('﻿', ' ', '\n', '\r').take(200)
        when {
            head.contains("NETSCAPE-Bookmark", true) || head.contains("<DL", true) -> { res.bookmarks += saveBookmarks(context, parseBookmarks(text)); res.files++ }
            head.startsWith("{") && text.contains("\"Browser History\"") -> { res.history += saveHistory(context, parseHistory(text)); res.files++ }
            head.lowercase().let { it.contains("url") && it.contains("password") } -> {
                PasswordVault.init(context)
                res.passwords += PasswordVault.saveAll(parsePasswords(text)); res.files++
            }
        }
    }

    private suspend fun saveBookmarks(context: Context, nodes: List<Node>): Int {
        val db = AppDatabase.get(context)
        val dao = db.bookmarks()
        val have = dao.allUrls().toHashSet()
        var added = 0
        suspend fun put(parent: Long?, list: List<Node>) {
            var pos = dao.nextPosition(parent)
            for (n in list) when (n) {
                is Node.Link -> if (have.add(n.url)) { dao.insert(Bookmark(parentId = parent, title = n.title, url = n.url, position = pos++)); added++ }
                is Node.Folder -> {
                    val id = dao.findFolder(parent, n.title) ?: dao.insert(Bookmark(parentId = parent, title = n.title, isFolder = true, position = pos++))
                    put(id, n.children)
                }
            }
        }
        db.withTransaction {
            // Chrome 匯出的最上層通常只有「書籤列」「其他書籤」等資料夾：全部收進「Google 書籤」
            val name = com.liquid.browser.core.t("Google 書籤")
            val root = dao.findFolder(null, name) ?: dao.insert(Bookmark(title = name, isFolder = true, position = dao.nextPosition(null)))
            put(root, nodes)
        }
        return added
    }

    private suspend fun saveHistory(context: Context, items: List<History>): Int {
        if (items.isEmpty()) return 0
        val db = AppDatabase.get(context)
        val dao = db.history()
        val have = dao.keysBetween(items.minOf { it.at }, items.maxOf { it.at }).toHashSet()
        val fresh = items.filter { have.add("${it.url} ${it.at}") }.map {
            HistoryEntry(url = it.url, host = Uri.parse(it.url).host.orEmpty().removePrefix("www."), title = it.title, visitedAt = it.at)
        }
        fresh.chunked(1000).forEach { dao.insertAll(it) }
        return fresh.size
    }
}
