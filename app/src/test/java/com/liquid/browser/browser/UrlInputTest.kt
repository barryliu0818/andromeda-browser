package com.liquid.browser.browser

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 網址列：輸入的是網址（直接開、前面顯示地球）還是要搜尋的字（顯示搜尋引擎圖示） */
class UrlInputTest {
    @Test
    fun urls() {
        listOf(
            "example.com", "www.example.com", "https://a.b/c?d=1", "http://x", "news.example.co.uk/path",
            "localhost", "localhost:3000", "192.168.1.1", "192.168.1.1:8080/admin", "about:blank", "Example.COM",
        ).forEach { assertTrue(looksLikeUrl(it), it) }
    }

    @Test
    fun searches() {
        listOf("hello world", "v1.2", "kotlin", "什麼是 kotlin", "a.b", "", "  ", "foo.c").forEach { assertFalse(looksLikeUrl(it), it) }
    }
}
