package com.alphainternational.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PicturesTest {
    @Test fun aPictureOnItsOwnLineShowsInTheText() {
        assertEquals("Michelin Defender2" to "photo-3fa29c1e.jpg",Pictures.alone("![Michelin Defender2](photo-3fa29c1e.jpg)"))
        assertEquals("" to "chart-sales-12ab.png",Pictures.alone("  ![](chart-sales-12ab.png)  "))
        // Words around it, web addresses and odd file types are just text.
        assertNull(Pictures.alone("Here: ![x](photo-1.jpg) enjoy"))
        assertNull(Pictures.alone("![x](https://example.com/photo.jpg)"))
        assertNull(Pictures.alone("![x](notes.txt)"))
        assertNull(Pictures.alone("![x](../secret.png)"))
    }

    @Test fun chatTextSplitsOutThePictureLines() {
        val blocks=ChatMarkdown.blocks("**Defender2** – \$189\n![Michelin Defender2](photo-aa.jpg)\nMore at [the store](https://example.com/t)")
        assertEquals(listOf("photo-aa.jpg"),blocks.filterIsInstance<ChatMarkdown.Block.Image>().map {it.name})
        assertEquals(2,blocks.filterIsInstance<ChatMarkdown.Block.Para>().size)
    }

    @Test fun picturesAlreadyInTheTextAreNotShownAgainUnderIt() {
        val text="Two options:\n![A](photo-aa.jpg)\nand\n![B](photo-bb.png)"
        assertEquals(setOf("photo-aa.jpg","photo-bb.png"),Pictures.shown(text))
        assertTrue(Pictures.shown("no pictures here").isEmpty())
    }

    @Test fun aWebPageListsItsMainPictureFirst() {
        val html="""
            <html><head>
            <meta property="og:image" content="https://cdn.example.com/p/tire-main.jpg?w=800&amp;h=800">
            <link rel="image_src" href="/img/alt.jpg">
            </head><body>
            <img src="/img/pixel.gif" width="1" height="1">
            <img src="/img/logo.svg">
            <img src="/img/icons/cart.png">
            <img src="/img/tire-side.jpg" width="640">
            <img data-src="//cdn.example.com/lazy.webp">
            <img src="data:image/png;base64,AAAA">
            <img src="/img/tiny.jpg" width="40">
            </body></html>
        """.trimIndent()
        val pictures=Pictures.imagesIn(html,"https://shop.example.com/tires/defender")
        assertEquals("https://cdn.example.com/p/tire-main.jpg?w=800&h=800",pictures.first())
        assertTrue("https://shop.example.com/img/alt.jpg" in pictures)
        assertTrue("https://shop.example.com/img/tire-side.jpg" in pictures)
        assertTrue("https://cdn.example.com/lazy.webp" in pictures)
        // Icons, tracking pixels, vector logos, inline data and tiny pictures aren't product photos.
        assertTrue(pictures.none {it.contains("pixel") || it.endsWith(".svg") || it.contains("/icons/") || it.startsWith("data:") || it.contains("tiny")})
    }

    @Test fun aPageListsAtMostEightPictures() {
        val html=(1..30).joinToString("") {"<img src=\"/p$it.jpg\" width=\"500\">"}
        assertEquals(8,Pictures.imagesIn(html,"https://x.example.com/").size)
    }

    @Test fun commonsSearchGivesPhotosWithCredit() {
        val reply=JSONObject("""
            {"query":{"pages":{
              "11":{"title":"File:Camry 2018.jpg","index":2,"imageinfo":[{"mime":"image/jpeg","thumburl":"https://upload.wikimedia.org/thumb/camry.jpg","descriptionurl":"https://commons.wikimedia.org/wiki/File:Camry_2018.jpg",
                    "extmetadata":{"Artist":{"value":"<a href='x'>Jane Doe</a>"},"LicenseShortName":{"value":"CC BY-SA 4.0"}}}]},
              "12":{"title":"File:Camry front.png","index":1,"imageinfo":[{"mime":"image/png","url":"https://upload.wikimedia.org/camry-front.png","descriptionurl":"https://commons.wikimedia.org/wiki/File:Camry_front.png"}]},
              "13":{"title":"File:Brochure.pdf","index":3,"imageinfo":[{"mime":"application/pdf","url":"https://upload.wikimedia.org/b.pdf"}]}
            }}}
        """.trimIndent())
        val photos=Pictures.results(reply).getJSONArray("photos")
        assertEquals(2,photos.length())
        // Best match first, PDFs left out, markup stripped from the credit.
        assertEquals("Camry front.png",photos.getJSONObject(0).getString("title"))
        assertEquals("https://upload.wikimedia.org/thumb/camry.jpg",photos.getJSONObject(1).getString("picture"))
        assertEquals("Jane Doe",photos.getJSONObject(1).getString("credit"))
        assertEquals("CC BY-SA 4.0",photos.getJSONObject(1).getString("license"))
    }

    @Test fun anEmptySearchSaysWhatToTryNext() {
        val result=Pictures.results(JSONObject("""{"batchcomplete":""}"""))
        assertEquals(0,result.getJSONArray("photos").length())
        assertTrue(result.getString("note").contains("web_fetch"))
    }
}

class DrawingsTest {
    private val ok="<svg viewBox=\"0 0 200 100\" xmlns=\"http://www.w3.org/2000/svg\"><rect width=\"200\" height=\"100\" fill=\"#e8412d\"/><text x=\"10\" y=\"50\">Alpha</text></svg>"

    @Test fun plainShapesAreAccepted() {
        assertNull(Drawings.problem(ok))
    }

    @Test fun scriptsOutsidePicturesAndEntityTricksAreNeverDrawn() {
        listOf("<svg><script>alert(1)</script></svg>","<svg><foreignObject><div/></foreignObject></svg>","<svg><image href=\"https://evil.example/x.png\"/></svg>",
            "<!DOCTYPE svg [<!ENTITY x \"y\">]><svg/>","<svg onload=\"x()\"/>").forEach {assertNotNull(it,Drawings.problem(it))}
        assertNotNull(Drawings.problem("just words"))
        assertNotNull(Drawings.problem(""))
        assertNotNull(Drawings.problem("<svg>"+"x".repeat(Drawings.MAX_SVG)+"</svg>"))
    }

    @Test fun drawingFilesGetSafeDistinctNames() {
        assertEquals("shop-logo-v2",Drawings.slug("Shop Logo (v2).png"))
        assertEquals("drawing",Drawings.slug("!!!"))
        val a=Drawings.fileName("Shop Logo",ok);val b=Drawings.fileName("Shop Logo",ok.replace("e8412d","000000"))
        assertTrue(a.matches(Regex("drawing-shop-logo-[0-9a-f]{4}\\.png")))
        assertNotEquals(a,b)
        assertEquals(a,Drawings.fileName("Shop Logo",ok))
        Rules.fileName(a)
    }
}
