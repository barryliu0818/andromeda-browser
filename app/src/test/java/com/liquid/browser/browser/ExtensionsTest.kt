package com.liquid.browser.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 擴充功能：網址比對樣式、使用者腳本檔頭、轉成 JS 正規表示式 */
class ExtensionsTest {
    private fun m(p: String) = Extensions.matchPattern(p)!!

    @Test
    fun matchPatterns() {
        assertTrue(m("*://*.youtube.com/*").containsMatchIn("https://www.youtube.com/watch?v=1"))
        assertTrue(m("*://*.youtube.com/*").containsMatchIn("https://youtube.com/"))
        assertFalse(m("*://*.youtube.com/*").containsMatchIn("https://notyoutube.com/"))
        assertTrue(m("https://example.com/a/*").containsMatchIn("https://example.com/a/b.html"))
        assertFalse(m("https://example.com/a/*").containsMatchIn("http://example.com/a/b.html"))
        assertTrue(m("<all_urls>").containsMatchIn("http://x.org/"))
        assertTrue(m("*://*/*").containsMatchIn("https://localhost:8080/p"))
        assertNull(Extensions.matchPattern("not a pattern"))
    }

    @Test
    fun globs() {
        assertTrue(Extensions.globOrRegex("https://*.bilibili.com/*")!!.containsMatchIn("https://www.bilibili.com/video/1"))
        assertTrue(Extensions.globOrRegex("/^https?://(www\\.)?github\\.com/")!!.containsMatchIn("https://github.com/x"))
        assertTrue(Extensions.globOrRegex("*")!!.containsMatchIn("anything"))
    }

    @Test
    fun userScriptHeader() {
        val meta = Extensions.userScriptMeta(
            """
            // ==UserScript==
            // @name         Hello
            // @name:zh-TW   你好
            // @match        *://*.example.com/*
            // @match        https://a.org/*
            // @run-at       document-start
            // ==/UserScript==
            console.log(1)
            """.trimIndent()
        )
        assertEquals(listOf("Hello"), meta["name"])
        assertEquals(2, meta["match"]?.size)
        assertEquals("document-start", meta["run-at"]?.single())
    }

    @Test
    fun jsRegexEscapes() {
        // \Q…\E 換成逐字跳脫，「/」也要跳脫才能放進 /…/
        assertEquals("^https?:\\/\\/([^\\/]*\\.)?a\\.b(:\\d+)?\\/.*$", Extensions.jsRegex(m("*://*.a.b/*")))
    }
}
