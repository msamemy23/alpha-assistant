package com.alphainternational.assistant

import android.content.Intent
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The bot's browser hands on real sites, in the app's own on-screen browser: type, press Enter, scroll, tap, and full-screen web apps. */
class BrowserActionsTest {
    private fun elements(page:JSONObject)=page.getJSONArray("elements").objects()

    @Test fun botCanTypeSearchScrollAndTapOnRealSites() {
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        instrument.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val c=(context.applicationContext as AlphaApplication).controller
        runBlocking {
            withContext(Dispatchers.Main) {
                var tries=0;while(c.browser==null && tries++<50) delay(100)
                val web=c.web()
                // Type into Google's search box and press Enter.
                val home=JSONObject(web.open("https://www.google.com/"))
                val box=elements(home).first {it.optString("kind") in setOf("textarea","combobox","input","textbox") && it.optString("label").contains("Search",true)}
                // A search whose top result is a stable https page (a dead or http-only site would stop the tap test).
                val typed=web.fill(box.getString("id"),"Houston Texas Wikipedia")
                assertTrue(typed,typed=="Field filled." || typed.startsWith("Typed"))
                val entered=web.pressEnter(box.getString("id"))
                Log.i("AlphaProbe","enter=$entered url="+web.currentUrl)
                var results=JSONObject(web.read());tries=0
                while(!results.getString("url").contains("/search") && tries++<20) {delay(500);results=JSONObject(web.read())}
                Log.i("AlphaProbe","results="+results.getString("url").take(120))
                // Google sometimes answers a test device with its "unusual traffic" check; that is not something to get around.
                org.junit.Assume.assumeFalse("Google asked this test device to prove it is human",results.getString("url").contains("/sorry/"))
                assertTrue(results.getString("url"),results.getString("url").contains("/search"))
                // Scroll the results, then tap a plain result link and land on another page.
                // Right after a cold start Google may still be drawing the results; give it one more moment.
                var scrolled=JSONObject(web.scroll("down"))
                if(scrolled.getString("scroll")!="Scrolled down.") {delay(2000);scrolled=JSONObject(web.scroll("down"))}
                assertEquals("Scrolled down.",scrolled.getString("scroll"))
                val listed=elements(JSONObject(web.read()))
                Log.i("AlphaProbe","elements="+listed.size+" "+listed.take(60).joinToString(" | ") {it.optString("kind")+":"+it.optString("label").take(30)+":"+it.optString("href").take(60)})
                // Google wraps result links in its own redirect (google.com/goto?url=…, or /url?q=…).
                val link=listed.first {val h=it.optString("href");it.optString("kind")=="a" && h.startsWith("https://") && (h.contains("/goto?") || h.contains("/url?") || !h.contains("google."))}
                assertTrue(BrowserScripts.plainLink(web.describe(link.getString("id"))))
                assertEquals("Clicked.",web.click(link.getString("id")))
                tries=0;while(web.currentUrl.contains("google.") && tries++<30) delay(500)
                Log.i("AlphaProbe","tapped to "+web.currentUrl)
                assertFalse(web.currentUrl,web.currentUrl.contains("google."))
                // Full-screen web apps lay out on screen (they used to get a zero-height page).
                val messages=JSONObject(web.open("https://messages.google.com/web/"))
                assertTrue(messages.getString("text").contains("Pair with QR code"))
                val signIn=elements(messages).first {it.optString("label")=="Sign In" && it.optBoolean("on_screen")}
                // Google's sign-in page opens inside Alpha's browser (nothing is typed here).
                assertEquals("Clicked.",web.click(signIn.getString("id")))
                tries=0;while(!web.currentUrl.contains("accounts.google.com") && tries++<30) delay(500)
                val account=JSONObject(web.read())
                Log.i("AlphaProbe","signin="+account.getString("url").take(80)+" text="+account.getString("text").take(300).replace('\n',' '))
                assertTrue(account.getString("url"),account.getString("url").contains("accounts.google.com"))
                assertFalse(account.getString("text").contains("may not be secure",true))
            }
        }
    }

    /** HTTPS-First: http:// addresses open over https://. A site that only works unencrypted is refused in plain words and never loaded. */
    @Test fun httpOnlySitesAreTriedOverHttpsThenRefusedPlainly() {
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        instrument.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val c=(context.applicationContext as AlphaApplication).controller
        runBlocking {
            withContext(Dispatchers.Main) {
                var tries=0;while(c.browser==null && tries++<50) delay(100)
                val web=c.web()
                // A site that also has https: its http:// address opens securely.
                val secure=JSONObject(web.open("http://example.com/"))
                assertTrue(secure.getString("url"),secure.getString("url").startsWith("https://example.com"))
                // httpforever.com's secure version fails its handshake: the bot gets the reason as the error, the owner sees a notice,
                // and the browser stays on the page it was on.
                val refused=runCatching {web.open("http://httpforever.com/")}.exceptionOrNull()?.message.orEmpty()
                Log.i("AlphaProbe","httpforever: $refused")
                assertTrue(refused,refused.startsWith("httpforever.com only works without encryption (http://), so Alpha's browser didn't open it. It tried the secure https:// version first"))
                assertTrue(c.notice,c.notice.startsWith("httpforever.com only works without encryption"))
                assertEquals("https://example.com/",JSONObject(web.read()).getString("url"))
                // NeverSSL exists to be unencrypted, but how it answers over https varies: its front page often loads securely and then
                // sends itself to an address like xyz.neverssl.com, which sometimes has no secure version. Either it ends on a secure
                // page or the bot is told why (open()'s error or the next read's notice); it never loads over http and never loops.
                val attempt=runCatching {web.open("http://neverssl.com/")}
                var told=attempt.exceptionOrNull()?.message.orEmpty()
                Log.i("AlphaProbe","neverssl: error=$told | page="+attempt.getOrNull()?.take(120)+" | now "+web.currentUrl)
                tries=0;while(told.isBlank() && tries++<12) {delay(500);told=runCatching {JSONObject(web.read()).optString("notice")}.getOrDefault("")}
                assertTrue(told,told.isBlank() || (told.contains("neverssl.com only works without encryption") && told.contains("tried the secure https:// version first")))
                delay(3000)
                val settled=JSONObject(web.read())
                Log.i("AlphaProbe","neverssl settled on "+settled.getString("url")+" notice="+settled.optString("notice").take(60))
                assertTrue(settled.getString("url"),settled.getString("url").startsWith("https://") && web.currentUrl.startsWith("https://"))
                assertEquals("",settled.optString("notice"))
                // A page that sends itself to an http-only site as it loads (like a search result's redirect page): refused once,
                // reported with the next read, and Alpha goes back instead of looping. (Chromium may count the jump as a redirect that
                // replaced the page, so "back" can be the page before it, as with a search result's redirect page.)
                web.view.loadDataWithBaseURL("https://redirect.example/","<html><body><p>Taking you there</p>"+
                    "<script>setTimeout(function(){location.href='http://httpforever.com/';},300)</script></body></html>","text/html","UTF-8","https://redirect.example/")
                var notice="";tries=0
                while(notice.isBlank() && tries++<40) {delay(500);notice=runCatching {JSONObject(web.read()).optString("notice")}.getOrDefault("")}
                Log.i("AlphaProbe","redirect page: $notice")
                assertTrue(notice,notice.startsWith("httpforever.com only works without encryption"))
                delay(3000)
                val back=JSONObject(web.read())
                Log.i("AlphaProbe","after redirect page: "+back.getString("url"))
                assertTrue(back.getString("url"),back.getString("url") in setOf("https://redirect.example/","https://example.com/"))
                assertEquals("",back.optString("notice"))
                delay(3000)
                assertEquals(back.getString("url"),JSONObject(web.read()).getString("url"))
                // Tapping http:// links: one whose secure version sends visitors back to http:// is refused, and the tap result says why;
                // the other opens over https.
                web.view.loadDataWithBaseURL("https://links.example/","<html><body><p><a href='http://http.badssl.com/'>HTTP only</a></p>"+
                    "<p><a href='http://example.com/'>Example</a></p></body></html>","text/html","UTF-8","https://links.example/")
                tries=0;while(web.currentUrl!="https://links.example/" && tries++<40) delay(250)
                val plain=elements(JSONObject(web.read())).first {it.optString("label")=="HTTP only"}
                assertTrue(BrowserScripts.plainLink(web.describe(plain.getString("id"))))
                val tapped=web.click(plain.getString("id"))
                Log.i("AlphaProbe","tap: $tapped")
                assertTrue(tapped,tapped.startsWith("http.badssl.com only works without encryption") && tapped.contains("sends visitors back to http://"))
                // The bot is still on the page it tapped from, not on an error screen.
                val after=JSONObject(web.read())
                assertEquals("https://links.example/",after.getString("url"))
                val example=elements(after).first {it.optString("label")=="Example"}
                assertEquals("Clicked.",web.click(example.getString("id")))
                tries=0;while(!web.currentUrl.startsWith("https://example.com") && tries++<40) delay(250)
                assertTrue(web.currentUrl,web.currentUrl.startsWith("https://example.com"))
                // web_fetch follows the same rule.
                val fetched=withContext(Dispatchers.IO) {WebReader.read("http://example.com/")}
                assertTrue(fetched.getString("url"),fetched.getString("url").startsWith("https://example.com"))
                val fetchRefused=withContext(Dispatchers.IO) {runCatching {WebReader.read("http://http.badssl.com/")}.exceptionOrNull()?.message.orEmpty()}
                Log.i("AlphaProbe","fetch: $fetchRefused")
                assertTrue(fetchRefused,fetchRefused.startsWith("http.badssl.com only works without encryption"))
            }
        }
    }
}
