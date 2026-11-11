package com.liquid.browser.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 不需要 Android 的純邏輯：主網域判斷（跳轉阻擋、網站例外） */
class PureLogicTest {
    @Test
    fun baseDomainKeepsSiteLevel() {
        assertEquals("example.com", baseDomain("www.example.com"))
        assertEquals("example.com", baseDomain("a.b.example.com"))
        assertEquals("example.com", baseDomain("Example.COM"))
        // 兩層後綴
        assertEquals("example.co.uk", baseDomain("shop.example.co.uk"))
        assertEquals("example.com.tw", baseDomain("www.example.com.tw"))
        assertEquals("yahoo.co.jp", baseDomain("news.yahoo.co.jp"))
        // 只有一兩段的照原樣
        assertEquals("localhost", baseDomain("localhost"))
        assertEquals("example.com", baseDomain("example.com"))
    }

    @Test
    fun userScriptVersions() {
        // 使用者腳本更新：遠端版本較新才重裝
        assertTrue(Extensions.newerVersion("1.10", "1.9"))
        assertTrue(Extensions.newerVersion("2.0", "1.99.9"))
        assertTrue(Extensions.newerVersion("1.0.1", "1.0"))
        assertTrue(Extensions.newerVersion("2024.11.10", "2024.9.30"))
        assertTrue(!Extensions.newerVersion("1.0", "1.0"))
        assertTrue(!Extensions.newerVersion("1.0", "1.0.0"))
        assertTrue(!Extensions.newerVersion("1.2", "1.10"))
        assertTrue(Extensions.newerVersion("1.0b", "1.0a"))
    }

    @Test
    fun baseDomainDistinguishesSites() {
        // 跳轉阻擋：同一個網站的子網域不算跳出去，不同網站要算
        assertEquals(baseDomain("m.youtube.com"), baseDomain("www.youtube.com"))
        assertTrue(baseDomain("ads.doubleclick.net") != baseDomain("www.youtube.com"))
    }

}
