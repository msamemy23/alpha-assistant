package com.alphainternational.assistant

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SitesTest {
    private val template=File("src/main/assets/site-shop.html").readText()
    private val shop=Sites.Shop("Acme Auto Repair","(713) 555-0100","100 Main St, Springfield, TX 77001",
        "Mon–Fri: 8:00 AM–6:00 PM; Sat: 9:00 AM–3:00 PM; Sun: Closed","Brakes, Oil change, Tires & alignment, A/C repair, Check engine diagnostics")

    @Test fun theShopPageIsFilledWithEverythingTheOwnerGave() {
        val html=Sites.shop(template,shop,year=2026)
        assertTrue(html.contains("Acme Auto Repair"))
        assertTrue(html.contains("tel:+17135550100"))
        assertTrue(html.contains("(713) 555-0100"))
        assertTrue(html.contains("100 Main St, Springfield, TX 77001"))
        assertTrue(html.contains("<td>Mon–Fri</td><td>8:00 AM–6:00 PM</td>"))
        assertTrue(html.contains("<td>Sun</td><td>Closed</td>"))
        assertTrue(html.contains("<h3>Brakes</h3>") && html.contains("<h3>A/C repair</h3>"))
        assertTrue(html.contains("Auto repair and service in Springfield"))
        assertTrue(html.contains("&copy; 2026"))
        assertTrue(html.contains("100%20Main%20St"))
        // Every blank was filled, and the business is described to search engines.
        assertFalse(Regex("\\{\\{[A-Z_]+}}").containsMatchIn(html))
        assertTrue(html.contains("\"@type\":\"AutoRepair\"") && html.contains("\"postalCode\":\"77001\""))
    }

    @Test fun withoutADatabaseTheFormSendsTheRequestByText() {
        val html=Sites.shop(template,shop)
        assertTrue(html.contains("var QUOTE_URL=\"\", QUOTE_KEY=\"\""))
        assertTrue(html.contains("SHOP_TEL=\"+17135550100\""))
    }

    @Test fun withADatabaseTheFormSavesToTheQuoteTable() {
        val html=Sites.shop(template,shop.copy(quoteUrl="https://abcdefghijklmnopqrst.supabase.co/",quoteKey="sb_publishable_abcdefghijklmnop12345"))
        assertTrue(html.contains("QUOTE_URL=\"https://abcdefghijklmnopqrst.supabase.co\""))
        assertTrue(html.contains("QUOTE_KEY=\"sb_publishable_abcdefghijklmnop12345\""))
        assertTrue(html.contains("/rest/v1/${Sites.QUOTE_TABLE}"))
    }

    @Test fun theDatabaseNeedsBothHalvesAndAPublicKey() {
        assertThrows(IllegalArgumentException::class.java) {Sites.shop(template,shop.copy(quoteUrl="https://abcdefghijklmnopqrst.supabase.co"))}
        assertThrows(IllegalArgumentException::class.java) {Sites.shop(template,shop.copy(quoteUrl="https://example.com",quoteKey="sb_publishable_abcdefghijklmnop12345"))}
        assertThrows(IllegalArgumentException::class.java) {Sites.shop(template,shop.copy(quoteUrl="https://abcdefghijklmnopqrst.supabase.co",quoteKey="sb_secret_abcdefghijklmnop12345"))}
        // A service-role JWT is refused; an anon one is fine.
        fun jwt(role:String)="eyJhbGciOiJIUzI1NiJ9."+java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"role":"$role"}""".toByteArray())+".sig"
        assertThrows(IllegalArgumentException::class.java) {Sites.publicKey(jwt("service_role"))}
        assertEquals(jwt("anon"),Sites.publicKey(jwt("anon")))
    }

    @Test fun hoursAreNeverGuessed() {
        assertThrows(IllegalArgumentException::class.java) {Sites.shop(template,shop.copy(hours=""))}
        assertThrows(IllegalArgumentException::class.java) {Sites.shop(template,shop.copy(services="Brakes"))}
        assertThrows(IllegalArgumentException::class.java) {Sites.shop(template,shop.copy(phone="555-0100"))}
    }

    @Test fun nothingTheBotTypesCanBreakOutOfThePage() {
        val html=Sites.shop(template,shop.copy(name="Al's <b>Auto</b> & \"Tires\"",services="Brakes, </script><script>alert(1)</script>",
            tagline="Best <i>ever</i>",blurb="x\"y"))
        assertFalse(html.contains("<script>alert(1)"))
        assertFalse(html.contains("<b>Auto</b>"))
        assertTrue(html.contains("Al&#39;s &lt;b&gt;Auto&lt;/b&gt; &amp; &quot;Tires&quot;"))
    }

    @Test fun hoursBecomeTableRows() {
        assertEquals(listOf("Mon–Fri" to "8 AM–6 PM","Sat" to "Closed"),Sites.hoursRows("Mon–Fri: 8 AM–6 PM\nSat: Closed"))
        assertEquals(listOf("Open daily 8-6" to ""),Sites.hoursRows("Open daily 8-6"))
        assertEquals(listOf("Mon" to "8:00 AM–5:00 PM"),Sites.hoursRows("Mon: 8:00 AM–5:00 PM"))
    }

    @Test fun phoneNumbersBecomeDialLinks() {
        assertEquals("+17135550100",Sites.telephone("713-555-0100"))
        assertEquals("+17135550100",Sites.telephone("1 (713) 555 0100"))
        assertEquals("(713) 555-0100",Sites.phoneDisplay("+1 713 555 0100"))
    }

    @Test fun websiteNamesBecomeSafeProjectNames() {
        assertEquals("acme-auto",Sites.slug("Acme Auto!"))
        assertEquals("shop-site-v2",Sites.slug("  Shop Site (v2) "))
        assertThrows(IllegalArgumentException::class.java) {Sites.slug("a")}
        assertTrue(Sites.slug("x".repeat(90)).length<=52)
    }

    @Test fun aPublicPageNeverCarriesASecret() {
        assertNotNull(Sites.problem("<script>var k='service_role'</script>"))
        assertNotNull(Sites.problem("fetch(u,{headers:{Authorization:'Bearer abcdefghijklmnopqrstuvwxyz'}})"))
        assertNotNull(Sites.problem("var key='sb_secret_abcdefghijklmnop'"))
        assertNull(Sites.problem(Sites.shop(template,shop)))
    }

    @Test fun publishingBundlesThePageAsTheHomePage() {
        val dir=kotlin.io.path.createTempDirectory("site").toFile()
        try {
            File(dir,"shop-website.html").writeText("<html>hi</html>");File(dir,"photo-aa.jpg").writeBytes(ByteArray(300))
            val bundle=Sites.bundle(dir,"shop-website.html",listOf("photo-aa.jpg"," "))
            assertEquals(listOf("index.html","photo-aa.jpg"),bundle.keys.toList())
            assertThrows(IllegalArgumentException::class.java) {Sites.bundle(dir,"missing.html",emptyList())}
            assertThrows(IllegalArgumentException::class.java) {Sites.bundle(dir,"photo-aa.jpg",emptyList())}
            File(dir,"leak.html").writeText("<p>service_role</p>")
            assertThrows(IllegalStateException::class.java) {Sites.bundle(dir,"leak.html",emptyList())}
        } finally {dir.deleteRecursively()}
    }

    @Test fun theLiveAddressIsTheProjectsOwnFreeName() {
        assertEquals("https://alpha-auto.vercel.app",Sites.publicAddress("alpha-auto",listOf("alpha-auto-abc123-team.vercel.app","alpha-auto.vercel.app")))
        assertEquals("https://alpha-auto-x1.vercel.app",Sites.publicAddress("alpha-auto",listOf("alpha-auto-git-main-team.vercel.app","alpha-auto-x1.vercel.app")))
        assertEquals("https://alpha-auto.vercel.app",Sites.publicAddress("alpha-auto",emptyList()))
    }

    @Test fun theQuoteTableOnlyLetsVisitorsAddRows() {
        val sql=Sites.quoteTableSql
        assertTrue(sql.contains("enable row level security"))
        assertTrue(sql.contains("revoke all on public.${Sites.QUOTE_TABLE} from anon"))
        assertTrue(sql.contains("grant insert on public.${Sites.QUOTE_TABLE} to anon"))
        assertFalse(sql.contains("for select") || sql.contains("grant select") || sql.contains("for update") || sql.contains("for delete"))
    }
}

class PictureToolsTest {
    @Test fun theNewToolsAreOfferedAndCheckedCorrectly() {
        val names=allTools.map {it.name}
        listOf("image_show","image_search","picture_draw","chart_make","site_build_shop","site_publish").forEach {assertTrue(it,it in names)}
        // Putting a site on the internet is the one that asks the owner.
        assertTrue(allTools.first {it.name=="site_publish"}.review)
        listOf("image_show","image_search","picture_draw","chart_make","site_build_shop").forEach {n->assertFalse(n,allTools.first {it.name==n}.review)}
        assertEquals("vercel",AgentRules.connectionFor("site_publish"))
        assertNull(AgentRules.connectionFor("site_build_shop"))
        assertNull(AgentRules.connectionFor("image_show"))
        assertTrue("image_show" in AgentRules.parallelSafe && "image_search" in AgentRules.parallelSafe)
        assertTrue("site_publish" in AgentRules.unsureIfFailed)
    }

    @Test fun thePublishCardSaysWhatGoesPublic() {
        val summary=Reviews.summary("site_publish","""{"project":"Acme Auto","page":"shop-website.html","files":"photo-aa.jpg"}""")
        assertEquals("Put a website online",summary.title)
        val rows=summary.rows.toMap()
        assertEquals("acme-auto.vercel.app",rows["Address"])
        assertEquals("Anyone on the internet",rows["Who can see it"])
    }

    @Test fun siteToolsAreHiddenUntilVercelIsConnected() {
        val offline=AgentRules.visibleTools(allTools,{it!="vercel"},true).map {it.name}
        assertFalse("site_publish" in offline)
        assertTrue("site_build_shop" in offline && "image_show" in offline)
        assertTrue("site_publish" in AgentRules.visibleTools(allTools,{true},true).map {it.name})
    }
}
