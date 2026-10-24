package com.alphainternational.assistant

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** The Library names a made page by its own title, and falls back to the file name when the page has none worth showing. */
class LibraryTitleTest {
    private fun page(html:String)=File.createTempFile("lib",".html").also {it.writeText(html);it.deleteOnExit()}

    @Test fun aPageIsNamedByItsTitle() {
        assertEquals("Rosa's Bakery & Cafe",Library.pageTitle(page("<!doctype html><html><head><meta charset=utf-8><title>\n  Rosa&#39;s Bakery &amp; Cafe </title></head><body></body></html>")))
    }

    @Test fun placeholderTitlesAreIgnored() {
        assertEquals("",Library.pageTitle(page("<html><head><title>Index</title></head></html>")))
        assertEquals("",Library.pageTitle(page("<html><head><title></title></head></html>")))
        assertEquals("",Library.pageTitle(page("<html><body>no head at all</body></html>")))
    }

    @Test fun whatCountsAsMadeIsStable() {
        assertEquals("page",Library.kindOf("shop.html"));assertEquals("document",Library.kindOf("quote.PDF"))
        assertNull(Library.kindOf("photo-1.jpg"));assertNull(Library.kindOf("notes.txt"))
    }
}
