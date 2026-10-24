package com.alphainternational.assistant

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * The page reader must list what a person could tap even when the page never marked it as a control: a menu button that is a plain div with a click
 * handler, an icon-only button, a pointer-cursor row. (A signed-in web portal's hamburger menu was invisible to the bots, so they asked the owner to tap it.)
 */
class ReaderIconsTest {
    private val html="""<html><head><title>Fixture</title></head><body style="margin:0">
        <div style="display:flex;justify-content:space-between;padding:8px">
          <div id="burger" class="nav-hamburger" style="cursor:pointer;width:40px;height:40px;background:#ccc"><span></span><span></span></div>
          <button class="icon-btn" style="width:40px;height:40px"></button>
          <div id="reports" style="cursor:pointer;padding:10px">Reports</div>
          <a href="#x">Plain link</a>
        </div>
        <p>Welcome to the portal</p>
        <script>document.getElementById('burger').addEventListener('click',function(){document.title='menu opened'})</script>
        </body></html>"""

    @Test fun aMenuDivAnIconButtonAndAPointerRowAreListedAndTappable() {
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        instrument.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val c=(context.applicationContext as AlphaApplication).controller
        runBlocking {
            withContext(Dispatchers.Main) {
                var tries=0;while(c.browser==null && tries++<50) delay(100)
                val web=c.web()
                web.view.loadDataWithBaseURL("https://fixture.example/page",html,"text/html","UTF-8",null)
                tries=0;while(web.currentUrl!="https://fixture.example/page" && tries++<100) delay(100)
                val page=JSONObject(web.read())
                val found=page.getJSONArray("elements").objects()
                val labels=found.map {it.optString("label")}
                assertTrue(labels.toString(),labels.any {it.contains("hamburger icon")})
                assertTrue(labels.toString(),labels.contains("Reports"))
                assertTrue(labels.toString(),labels.any {it.startsWith("Icon button")})
                assertTrue(labels.toString(),labels.contains("Plain link"))
                // and tapping the menu div really reaches its click handler
                val burger=found.first {it.optString("label").contains("hamburger icon")}
                web.click(burger.getString("id"))
                delay(500)
                assertEquals("menu opened",web.probe("document.title"))
            }
        }
    }
}
