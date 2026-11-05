package com.liquid.browser.browser

import com.liquid.browser.browser.GoogleImport.Node
import kotlin.test.Test
import kotlin.test.assertEquals

class GoogleImportTest {
    @Test
    fun bookmarks() {
        val html = """<!DOCTYPE NETSCAPE-Bookmark-file-1>
<TITLE>Bookmarks</TITLE><H1>Bookmarks</H1>
<DL><p>
    <DT><H3 ADD_DATE="1" PERSONAL_TOOLBAR_FOLDER="true">書籤列</H3>
    <DL><p>
        <DT><A HREF="https://a.com/?x=1&amp;y=2" ADD_DATE="1">A &amp; B</A>
        <DT><H3>子資料夾</H3>
        <DL><p>
            <DT><A HREF="https://b.com/">B</A>
        </DL><p>
    </DL><p>
    <DT><A HREF="javascript:alert(1)">bad</A>
    <DT><A HREF="https://c.com/"></A>
</DL><p>"""
        assertEquals(
            listOf(
                Node.Folder("書籤列", listOf(Node.Link("A & B", "https://a.com/?x=1&y=2"), Node.Folder("子資料夾", listOf(Node.Link("B", "https://b.com/"))))),
                Node.Link("https://c.com/", "https://c.com/"),
            ),
            GoogleImport.parseBookmarks(html),
        )
    }

    @Test
    fun passwords() {
        val csv = "name,url,username,password,note\r\n" +
            "a.com,https://a.com/login,me@x.com,\"p,a\"\"ss\",\"line1\nline2\"\n" +
            "app,android://hash@com.app/,u,p,\n" +
            "b,https://b.com:8443/,,secret,\n" +
            "c,https://c.com/,u,,\n"
        assertEquals(
            listOf(Credential("https://a.com", "me@x.com", "p,a\"ss"), Credential("https://b.com:8443", "", "secret")),
            GoogleImport.parsePasswords(csv),
        )
    }
}
