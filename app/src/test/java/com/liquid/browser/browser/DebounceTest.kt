package com.liquid.browser.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 跳過追蹤跳轉頁：認得的轉址頁取出目的地，一般網址不動 */
class DebounceTest {
    @Test
    fun unwraps() {
        assertEquals("https://example.com/a?b=1", Debounce.target("https://www.google.com/url?q=https%3A%2F%2Fexample.com%2Fa%3Fb%3D1&sa=D"))
        assertEquals("https://kotlinlang.org/", Debounce.target("https://link.zhihu.com/?target=https%3A%2F%2Fkotlinlang.org%2F"))
        assertEquals("https://x.org/", Debounce.target("https://l.facebook.com/l.php?u=https%3A%2F%2Fx.org%2F&h=AT0"))
    }

    @Test
    fun leavesOthers() {
        assertNull(Debounce.target("https://www.zhihu.com/question/1?target=https%3A%2F%2Fx.org"))
        assertNull(Debounce.target("https://www.google.com/search?q=https://x.org"))
        assertNull(Debounce.target("https://www.google.com/url?q=javascript:alert(1)"))
        assertNull(Debounce.target("https://example.com/"))
    }
}
