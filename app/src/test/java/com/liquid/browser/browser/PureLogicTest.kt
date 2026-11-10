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
    fun baseDomainDistinguishesSites() {
        // 跳轉阻擋：同一個網站的子網域不算跳出去，不同網站要算
        assertEquals(baseDomain("m.youtube.com"), baseDomain("www.youtube.com"))
        assertTrue(baseDomain("ads.doubleclick.net") != baseDomain("www.youtube.com"))
    }

}
