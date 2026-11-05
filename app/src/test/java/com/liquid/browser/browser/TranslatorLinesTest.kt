package com.liquid.browser.browser

import kotlin.test.Test
import kotlin.test.assertEquals

/** AI 翻譯的逐行格式：編號對回原文，漏掉、亂序、多餘的行都不會錯位 */
class TranslatorLinesTest {
    @Test
    fun roundTrip() {
        assertEquals("0|Hello world\n1|a | b", Translator.encodeLines(listOf("Hello\n  world", " a | b ")))
        // 亂序、全形分隔、前後雜訊、漏掉 1、超出範圍的 5、譯文本身含 |
        val out = Translator.parseLines("好的：\n2｜第三\n0| 你好 世界\n5|多餘\n3|a | b", 4)
        assertEquals(listOf("你好 世界", null, "第三", "a | b"), out)
    }
}
