package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BlueprintsTest {
    private fun bp(id:String,kind:String,best:String,mood:String="",notes:String="")=Blueprints.Blueprint(id,kind,id.replace('-',' '),
        JSONObject().put("id",id).put("kind",kind).put("best_for",JSONArray(best.split(','))).put("mood",JSONArray(if(mood.isBlank()) emptyList<String>() else mood.split(','))).put("style_notes",notes).put("summary","x"))
    private val all=listOf(bp("service-pro","local","auto repair,plumber,salon","trustworthy"),bp("restaurant-cafe","local","restaurant,bakery,cafe","warm"),
        bp("portfolio-studio","site","photographer,designer,agency"),bp("estimate-builder","app","estimate,invoice,quote"),bp("crm-jobs","app","customers,jobs,tracker"))

    @Test fun theBlueprintThatFitsTheBusinessAndKindWins() {
        val rnd=java.util.Random(1)
        assertEquals("restaurant-cafe",Blueprints.pick(all,"site","bakery in houston warm",emptyList(),rnd)?.id)
        assertEquals("service-pro",Blueprints.pick(all,"site","auto repair shop",emptyList(),rnd)?.id)
        assertEquals("estimate-builder",Blueprints.pick(all,"app","estimate and invoice tool",emptyList(),rnd)?.id)
        // A site request never gets an app, and an app request never gets a site.
        assertTrue(Blueprints.pick(all,"site","estimate invoice quote",emptyList(),rnd)!!.kind in setOf("site","local"))
        assertEquals("app",Blueprints.pick(all,"app","anything",emptyList(),rnd)!!.kind)
        // No app blueprints at all: an app request gets none (it builds custom with the toolkit) instead of a website.
        assertNull(Blueprints.pick(all.filter {it.kind!="app"},"app","calculator",emptyList(),rnd))
        assertNull(Blueprints.pick(emptyList(),"site","x",emptyList()))
        // The one used lately is passed over when another fits nearly as well.
        val twin=all+bp("service-pro-2","local","auto repair,plumber,salon")
        assertEquals("service-pro-2",Blueprints.pick(twin,"site","auto repair plumber",listOf("service-pro"),java.util.Random(3))?.id)
    }

    @Test fun anAppOrAGameIsGuessedFromTheRequest() {
        assertEquals("app",StudioFlow.guessKind("an estimate builder for my tire shop"))
        assertEquals("app",StudioFlow.guessKind("customer tracker for a barber"))
        assertEquals("site",StudioFlow.guessKind("a website for my bakery"))
        assertEquals("site",StudioFlow.guessKind("landing page for my invoice app"))
        assertEquals("game",StudioFlow.guessKind("a little arcade game for the lobby"))
        assertEquals("site",StudioFlow.guessKind("dave's garage, brakes and tires in Houston"))
    }

    @Test fun phoneNumbersBecomeCallLinksAndMissingOnesAreHonest() {
        assertEquals("tel:+17135550100",Blueprints.phoneHref("(713) 555-0100"))
        assertEquals("tel:+17135550100",Blueprints.phoneHref("1-713-555-0100"))
        assertEquals("tel:+442071838750",Blueprints.phoneHref("+44 20 7183 8750"))
        assertEquals("#contact",Blueprints.phoneHref(""))
        assertEquals("#contact",Blueprints.phoneHref("call us"))
        val t=Blueprints.tokens(Blueprints.Facts(name="Dave's Garage"),2026)
        assertEquals("Dave's Garage",t["business"]);assertEquals("D",t["initial"]);assertEquals("daves-garage",t["slug"]);assertEquals("2026",t["year"])
        assertEquals("Call us",t["phone"]);assertEquals("#contact",t["phone_href"]);assertEquals("#contact",t["email_href"])
        // A fact that wasn't given shows as a [bracketed marker] the page checker flags until it is filled in.
        assertEquals("[Add your address]",t["address"]);assertEquals("[Add your hours]",t["hours"]);assertEquals("[Your tagline here]",t["tagline"])
        assertEquals("[Business name]",Blueprints.tokens(Blueprints.Facts(),2026)["business"])
        assertEquals(listOf("tagline","phone","email","address","hours"),Blueprints.missing(Blueprints.Facts(name="Dave's"),setOf("business","tagline","phone","email","address","hours")))
        // Only facts the page actually uses are reported.
        assertEquals(listOf("phone"),Blueprints.missing(Blueprints.Facts(name="x"),setOf("business","phone")))
        assertEquals("mailto:a@b.com",Blueprints.tokens(Blueprints.Facts(email="a@b.com"),2026)["email_href"])
    }

    @Test fun factsComeFromLinesOrJson() {
        val lines=Blueprints.parseFacts("name: Dave's Garage\nphone: 713 555 0100\nopening hours: Mon-Fri 8-6\nAddress: 10 Main St: Suite 2")
        assertEquals("Dave's Garage",lines.name);assertEquals("713 555 0100",lines.phone);assertEquals("10 Main St: Suite 2",lines.address)
        val json=Blueprints.parseFacts("""{"business":"Rosa's Bakery","Phone":"555-1234","email":"r@x.com","city":"Austin"}""")
        assertEquals("Rosa's Bakery",json.name);assertEquals("r@x.com",json.email);assertEquals("Austin",json.city)
        assertEquals(Blueprints.Facts(),Blueprints.parseFacts(""))
    }

    @Test fun tokensAreFilledAndEscapedForTheFileTheyLandIn() {
        val values=Blueprints.tokens(Blueprints.Facts(name="Tom & \"Jerry\" <Co>",phone="(713) 555-0100",tagline="Fast\nand fair"),2026)
        val photos=mapOf("hero" to Blueprints.Placed("photo-ab12.jpg","A \"bright\" shop"))
        val page="""<title>{{business}}</title><img src="{{photo:hero}}" alt="{{alt:hero}}"><a href="{{phone_href}}">{{phone}}</a>
            <script type="application/ld+json">{"name":"{{business}}","telephone":"{{phone}}"}</script><p>{{tagline}}</p><i>{{nope}}</i>"""
        val out=Blueprints.fill("index.html",page,values,photos)
        assertTrue(out.contains("<title>Tom &amp; &quot;Jerry&quot; &lt;Co&gt;</title>"))
        assertTrue(out.contains("src=\"photo-ab12.jpg\" alt=\"A &quot;bright&quot; shop\""))
        assertTrue(out.contains("href=\"tel:+17135550100\""))
        // JSON-LD is JSON, not HTML: quotes are backslash-escaped, nothing is turned into entities.
        assertTrue(out.contains("\"name\":\"Tom & \\\"Jerry\\\" \\u003cCo>\""))
        assertTrue(out.contains("{{nope}}"))
        // In a script the value is a safe string; in a stylesheet a safe url.
        assertEquals("var n=\"Tom & \\\"Jerry\\\" \\u003cCo>\";",Blueprints.fill("app.js","var n=\"{{business}}\";",values,photos))
        assertEquals("a{background:url(photo-ab12.jpg)}",Blueprints.fill("style.css","a{background:url({{photo:hero}})}",values,photos))
        // A slot with no photo falls back to its art file.
        assertEquals("art-gallery-1.svg",Blueprints.fill("index.html","{{photo:gallery-1}}",values,photos))
    }

    @Test fun slotsAndTokensAreFoundInFirstSeenOrder() {
        val a="<img src=\"{{photo:hero}}\"><p>{{business}} {{city}}</p>";val b="x{{photo:gallery-2}}{{photo:hero}}{{photo:about}}{{alt:hero}}{{phone}}"
        assertEquals(listOf("hero","gallery-2","about"),Blueprints.slotsIn(listOf(a,b)))
        assertEquals(setOf("business","city","phone"),Blueprints.tokensIn(listOf(a,b)))
    }

    @Test fun eachSlotGetsAPhotoOfTheRightShapeAndNoRepeats() {
        fun s(u:String,w:Int,h:Int)=Pictures.Stock("https://x/$u.jpg","alt $u","",w,h,"stocksnap")
        val pool=listOf(s("tall",800,1200),s("wide1",1600,900),s("wide2",1500,1000),s("square",1000,1000))
        val slots=listOf(Blueprints.Slot("hero","landscape",""),Blueprints.Slot("portrait","portrait",""),Blueprints.Slot("gallery-1","",""),Blueprints.Slot("gallery-2","",""),Blueprints.Slot("gallery-3","",""))
        val got=Blueprints.assign(slots,pool)
        assertEquals("https://x/wide1.jpg",got["hero"]?.url);assertEquals("https://x/tall.jpg",got["portrait"]?.url)
        assertEquals(4,got.values.map {it.url}.toSet().size)
        // Five slots, four photos: the last slot has none, so it gets art.
        assertNull(got["gallery-3"])
        assertTrue(Blueprints.assign(slots,emptyList()).isEmpty())
    }

    @Test fun artIsValidAndFollowsTheStylesColours() {
        val svg=Blueprints.art("hero",mapOf("bg" to "#101010","surface" to "#202020","accent" to "#ff6a13"))
        assertTrue(svg.startsWith("<svg") && svg.endsWith("</svg>"))
        assertTrue(svg.contains("#ff6a13") && svg.contains("#101010"))
        assertEquals(svg,Blueprints.art("hero",mapOf("bg" to "#101010","surface" to "#202020","accent" to "#ff6a13")))
        assertNotEquals(svg,Blueprints.art("gallery-1",mapOf("bg" to "#101010","surface" to "#202020","accent" to "#ff6a13")))
        // Parses as XML.
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(svg.byteInputStream())
    }

    @Test fun aBlueprintsNotesNameTheStylesItLooksBestIn() {
        val ids=listOf("dark-luxury","editorial-serif","coastal","soft-pastel","newspaper","poster-bold")
        // Whole style ids only, including one-word ones; "soft pastel" (no hyphen) and "poster-bolder" are not the style ids.
        assertEquals(setOf("dark-luxury","editorial-serif","coastal"),Blueprints.preferred(bp("x","site","a",notes="Works best with dark-luxury, editorial-serif and coastal; tested in soft pastel and poster-bolder."),ids))
        assertTrue(Blueprints.preferred(null,ids).isEmpty())
    }

    @Test fun stockPhotosAreRealLargeAndCreditedWhenTheLicenceNeedsIt() {
        val reply=JSONObject("""{"results":[
            {"url":"https://cdn.stocksnap.io/img-thumbs/960w/AAA.jpg","title":"Bakery  #bread","license":"cc0","width":3200,"height":2000,"source":"stocksnap","mature":false,"creator":"Someone"},
            {"url":"https://live.staticflickr.com/1/2.jpg","title":"Loaf","license":"by","creator":"Jane","source":"flickr","width":1024,"height":768,"mature":false},
            {"url":"https://x.com/small.jpg","title":"Tiny","license":"cc0","width":300,"height":200},
            {"url":"https://x.com/adult.jpg","title":"No","license":"cc0","width":2000,"height":1000,"mature":true},
            {"url":"http://x.com/plain-http.jpg","title":"No","license":"cc0","width":2000,"height":1000},
            {"url":"https://x.com/vector.svg","title":"No","license":"cc0","width":2000,"height":1000}]}""")
        val got=Pictures.parseStock(reply)
        assertEquals(2,got.size)
        assertEquals("","${got[0].credit}");assertEquals("Bakery bread",got[0].alt);assertEquals("landscape",got[0].orientation)
        assertEquals("Jane (flickr, CC BY)",got[1].credit)
    }

    @Test fun publishingFindsTheFilesAPageUses() {
        val dir=kotlin.io.path.createTempDirectory("site").toFile()
        try {
            File(dir,"index.html").writeText("<link rel=stylesheet href=\"theme.css\"><link href=\"style.css\" rel=stylesheet><script src=\"app.js\" defer></script><img src=\"photo-ab12.jpg\"><a href=\"menu.html\">menu</a><img src=\"missing.png\">")
            File(dir,"theme.css").writeText("@font-face{src:url(oswald-var.woff2)} body{background:url(\"photo-bg.webp\")}")
            File(dir,"style.css").writeText("a{color:red}");File(dir,"app.js").writeText("const hero='art-hero.svg';fetch('data.json')")
            File(dir,"menu.html").writeText("<img src=\"photo-menu.jpg\">")
            listOf("photo-ab12.jpg","oswald-var.woff2","photo-bg.webp","art-hero.svg","data.json","photo-menu.jpg","unused.png","shot-index-phone.png").forEach {File(dir,it).writeText("x")}
            val needed=Sites.needed(dir,"index.html")
            assertEquals(listOf("index.html","theme.css","style.css","app.js","photo-ab12.jpg","menu.html","oswald-var.woff2","photo-bg.webp","art-hero.svg","data.json","photo-menu.jpg").sorted(),needed.sorted())
            assertFalse("unused.png" in needed || "missing.png" in needed || "shot-index-phone.png" in needed)
            // The bundle takes them all even when the bot only names the page.
            assertEquals(needed.size,Sites.bundle(dir,"index.html",emptyList()).size)
        } finally {dir.deleteRecursively()}
    }
}
