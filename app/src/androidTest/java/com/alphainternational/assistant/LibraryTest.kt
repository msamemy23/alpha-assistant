package com.alphainternational.assistant

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** The Library shows a made website as a picture of the page, and knows what counts as something a bot made. */
class LibraryTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun kindsAreRecognisedAndTheOwnersPhotosAreLeftOut() {
        assertEquals("page",Library.kindOf("index.html"))
        assertEquals("document",Library.kindOf("invoice-1042.pdf"))
        assertEquals("picture",Library.kindOf("chart-sales-ab12.png"))
        assertEquals("video",Library.kindOf("promo.mp4"))
        assertEquals("sound",Library.kindOf("jingle.wav"))
        assertNull(Library.kindOf("photo-1760000000.jpg"))
        assertNull(Library.kindOf("notes.txt"))
        assertNull(Library.kindOf("_scratch.html"))
    }

    @Test fun aMadePageGetsAThumbnailThatIsRememberedOnDisk() {
        val dir=File(context.cacheDir,"library-"+System.nanoTime()).also {it.mkdirs()}
        try {
            val page=File(dir,"index.html")
            page.writeText("""<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Lib</title>
                <style>body{margin:0;font:22px sans-serif;background:#0f1a2b;color:#fff}.h{padding:48px 24px;background:linear-gradient(135deg,#ff6a13,#7a2cff)}.c{margin:18px;padding:24px;background:#1f2d44;border-radius:14px}</style></head>
                <body><div class="h"><h1>Rosa's Bakery</h1><p>Fresh sourdough daily</p></div><div class="c">Order online</div><div class="c">Visit us</div></body></html>""")
            val first=runBlocking {Library.thumb(context,page,"TestBot")}
            assertNotNull("no thumbnail for a made page",first)
            assertTrue(first!!.width>=300 && first.height>=300)
            // The second ask is served from memory/disk without drawing the page again.
            val again=runBlocking {Library.thumb(context,page,"TestBot")}
            assertNotNull(again)
            assertTrue(File(context.filesDir,"thumbs").listFiles().orEmpty().isNotEmpty())
        } finally {dir.deleteRecursively()}
    }
}
