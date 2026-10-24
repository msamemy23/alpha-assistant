package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StudioTest {
    private fun pack(id:String,mood:String,best:String)=Studio.Pack(id,id.replace('-',' '),JSONObject().put("id",id).put("mood",JSONArray(mood.split(',')))
        .put("best_for",JSONArray(best.split(','))).put("fonts","oswald-inter")
        .put("colors",JSONObject().put("ink","#111111").put("paper","#fafafa").put("accent_hot","#e8412d")).put("radius","14px").put("shadow","0 8px 30px rgba(0,0,0,.12)")
        .put("spacing",JSONObject().put("1","4px").put("2","8px")))
    private val packs=listOf(pack("workshop-industrial","sturdy,gritty,bold","auto repair,garage,trades"),pack("soft-pastel","gentle,friendly,light","bakery,florist,kids"),
        pack("dark-luxury","premium,elegant,dark","jeweler,detailing,lounge"),pack("swiss-grid","clean,neutral,precise","consulting,architects"))

    @Test fun requestsForSitesAppsAndGamesGetTheDesignBrief() {
        listOf("build me a website for my shop","make a landing page for brake specials","I need an html page with my hours","make me a calculator for tire sizes","build me an app for tracking oil changes",
            "make me a game for the lobby","a dashboard for my invoices","a booking page for appointments").forEach {assertTrue(it,Studio.wants(it,false))}
        listOf("what's the weather","text John his car is ready","summarize my emails","make an estimate for Maria").forEach {assertFalse(it,Studio.wants(it,false))}
    }

    @Test fun followUpsOnAnExistingSiteAlsoGetIt() {
        assertTrue(Studio.wants("make the header bigger",true))
        assertTrue(Studio.wants("change the button color to red",true))
        assertFalse(Studio.wants("make the header bigger",false))
        assertFalse(Studio.wants("thanks!",true))
    }

    @Test fun theStyleThatFitsTheBusinessWinsAndRecentOnesAreSkipped() {
        val rnd=java.util.Random(1)
        assertEquals("workshop-industrial",Studio.pick(packs,"auto repair shop bold gritty",emptyList(),rnd)?.id)
        assertEquals("soft-pastel",Studio.pick(packs,"florist gentle",emptyList(),rnd)?.id)
        // The style used lately isn't picked again when another one fits nearly as well.
        val twin=packs+pack("retro-americana","nostalgic,warm,bold","auto repair,diner,garage")
        assertEquals("retro-americana",Studio.pick(twin,"auto repair garage",listOf("workshop-industrial"),java.util.Random(2))?.id)
        assertNull(Studio.pick(emptyList(),"anything",emptyList()))
        // With no hint, any style may come up, never the same one three times in a row.
        val seen=(0 until 40).map {Studio.pick(packs,"",listOf("swiss-grid"),java.util.Random(it.toLong()))!!.id}
        assertFalse("swiss-grid" in seen)
        assertTrue(seen.toSet().size>1)
    }

    @Test fun aStyleBecomesCssVariablesSoLaterChangesAreOneLine() {
        val real=Studio.Pack("workshop-industrial","Workshop Industrial",JSONObject("""{
            "colors":{"bg":"#14171A","surface":"#1F2428","ink":"#F2F0EA","muted":"#B4BAC0","accent":"#FF6A13","onAccent":"#14171A"},
            "radius":{"sm":2,"md":4,"lg":6,"button":4},"shadow":{"card":"none","pop":"0 16px 32px -16px rgba(0,0,0,.7)"},
            "space":{"scale":[4,8,12],"sectionY":96,"gutter":16,"maxWidth":1120},
            "type":{"basePx":17,"bodyLeading":1.6,"h1":"clamp(2.75rem,10vw,6.25rem)","h2":"2rem","h3":"1.5rem","headingCase":"uppercase","headingWeight":700,"headingLeading":1.02,"headingTracking":"0.01em"},
            "motion":{"durationMs":500,"ease":"cubic-bezier(.2,.8,.2,1)","staggerMs":70}}"""))
        val css=Studio.themeCss(real,"--font-display:\"Oswald\",sans-serif;--font-body:\"Inter\",sans-serif")
        assertTrue(css.contains("--font-display:\"Oswald\""))
        // Every variable ends with a semicolon: a missing one after the font rule once swallowed --c-surface and broke the body font (Times New Roman).
        assertTrue(css.contains("--font-body:\"Inter\",sans-serif;\n"))
        css.substringAfter(":root{").substringBefore("}\n").lines().map {it.trim()}.filter {it.isNotEmpty()}.forEach {assertTrue("no closing semicolon: $it",it.endsWith(";"))}
        // The real pairing rule from fonts.css (no trailing semicolon of its own) is handled the same way.
        val withRealRule=Studio.themeCss(real,"--font-display:\"Oswald\",sans-serif;--font-body:\"Inter\",sans-serif;")
        assertFalse(withRealRule.contains(";;"))
        assertTrue(css.contains("--c-bg:#14171A;") && css.contains("--c-on-accent:#14171A;") && css.contains("--c-accent:#FF6A13;"))
        assertTrue(css.contains("--radius-button:4px;") && css.contains("--shadow-pop:0 16px 32px -16px rgba(0,0,0,.7);"))
        assertTrue(css.contains("--space-1:4px;") && css.contains("--space-3:12px;") && css.contains("--section-y:96px;") && css.contains("--max-width:1120px;"))
        assertTrue(css.contains("--text-base:17px;") && css.contains("--h1:clamp(2.75rem,10vw,6.25rem);") && css.contains("--heading-case:uppercase;"))
        assertTrue(css.contains("--m-dur:500ms;") && css.contains("--m-stagger:70ms;"))
        // A starting layer that uses the variables, with low specificity so the bot's own styles win.
        assertTrue(css.contains(":where(body)") && css.contains("var(--c-ink") && css.contains(":where(.btn)"))
        // Older, simpler style files still work.
        val simple=Studio.themeCss(packs[0],"")
        assertTrue(simple.contains("--c-accent-hot:#e8412d;") && simple.contains("--radius:14px;") && simple.contains("--shadow:0 8px 30px"))
    }

    @Test fun theDesignBriefIsShortAndSaysWhatMatters() {
        val brief=Studio.FALLBACK_BRIEF
        assertTrue(brief.length<2600)
        listOf("page_screenshot","studio_photos","studio_icons","section","alt text","Never invent").forEach {assertTrue(it,brief.contains(it))}
    }

    @Test fun thePageChecksReadAsShortPlainLinesMustFixFirst() {
        val checks=JSONArray("""[
            {"id":"overflow","sev":"error","problems":["The page scrolls sideways (width 520 in a 390 window)."]},
            {"id":"headings","sev":"error","problems":[]},
            {"id":"seo","sev":"warn","problems":["no meta description"]},
            {"id":"claims","sev":"error","problems":["WARN: unverifiable claim: \"best in town\"","unverifiable claim: \"500 happy customers\""]}]""")
        val lines=PageShot.summary(checks).let {(0 until it.length()).map {i->it.getString(i)}}
        assertEquals(listOf("fix: overflow: The page scrolls sideways (width 520 in a 390 window).","fix: claims: unverifiable claim: \"500 happy customers\"",
            "better: seo: no meta description","better: claims: unverifiable claim: \"best in town\""),lines)
        assertEquals(0,PageShot.summary(JSONArray()).length())
        assertEquals(0,PageShot.summary(JSONArray("""[{"id":"x","sev":"error","problems":[]}]""")).length())
        assertEquals(390 to 844,PageShot.size("phone"))
        assertEquals(1280 to 800,PageShot.size("Desktop"))
    }

    @Test fun theStudioToolsAreOffered() {
        listOf("studio_start","studio_photos","studio_icons","page_screenshot","file_edit","section_replace","file_append").forEach {assertTrue(it,it in allTools.map {t->t.name})}
        assertTrue("page_screenshot" in viewTools)
    }

    @Test fun moreFileTypesWorkForSitesAndMedia() {
        listOf("style.css","app.js","logo.svg","manifest.webmanifest","fonts-inter.woff2","hero.mp4","jingle.wav","music.mp3").forEach {assertEquals(it,Rules.fileName(it))}
        assertEquals("style.css",Rules.textFileName("style.css"))
        assertThrows(IllegalArgumentException::class.java) {Rules.textFileName("clip.mp4")}
        assertThrows(IllegalArgumentException::class.java) {Rules.fileName("run.sh")}
        assertThrows(IllegalArgumentException::class.java) {Rules.fileName("img/a.png")}
        assertEquals("audio",Attachments.kind("a.wav"));assertEquals("video",Attachments.kind("a.mp4"));assertEquals("text",Attachments.kind("a.css"))
    }
}
