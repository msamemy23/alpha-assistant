package com.alphainternational.assistant

import android.graphics.Bitmap
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The bot's eyes: page_screenshot must bring back a real picture of a page (it once came back blank on the phone, so no bot could check its own work). */
@RunWith(AndroidJUnit4::class)
class PageShotTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val page="""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Shot test page for the bots</title>
        <style>body{margin:0;font:20px sans-serif;background:#14171a;color:#f2f0ea}section{padding:60px 24px}h1{font-size:44px;margin:0 0 16px}.b{height:220px;border-radius:16px;background:linear-gradient(135deg,#ff6a13,#7a2cff);margin:16px 0}
        .c{background:#2a3138;padding:20px;border-radius:12px;margin:12px 0}</style></head><body>
        <section><h1>Hello from the page</h1><p>Real text, real colours, tall enough to scroll.</p><div class="b"></div><div class="c">Card one</div><div class="c">Card two</div></section>
        <section style="background:#1d2630"><h1>Second part</h1><div class="b" style="background:#13a06f"></div><p>More text here.</p></section>
        <section><h1>Third part</h1><div class="b" style="background:#e63946"></div><div class="c">Card three</div></section></body></html>"""

    private fun run(attach:Boolean):Pair<PageShot.Result,File> {
        val dir=File(context.cacheDir,"shot-"+System.nanoTime()).also {it.mkdirs()}
        File(dir,"index.html").writeText(page)
        var host:ViewGroup?=null
        val scenario=if(attach) ActivityScenario.launch(MainActivity::class.java) else null
        scenario?.onActivity {host=it.window.decorView as ViewGroup}
        val result=runBlocking {PageShot.take(context.applicationContext,dir,"index.html","phone",3,host)}
        scenario?.close()
        val out=File(context.getExternalFilesDir(null),if(attach) "shot-attached.png" else "shot-hidden.png")
        result.picture?.let {b->out.outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)}}
        return result to out
    }

    @Test fun aPageShownInTheAppComesBackAsAPicture() {
        val (r,out)=run(true)
        assertNotNull("no picture: "+r.problem,r.picture)
        assertFalse(PageShot.isBlank(r.picture!!))
        assertTrue("picture saved to $out",out.length()>1000)
        assertTrue(r.pageHeight>1000)
    }

    @Test fun aPageWithNoAppWindowStillComesBackAsAPicture() {
        val (r,_)=run(false)
        assertNotNull("no picture: "+r.problem,r.picture)
        assertFalse(PageShot.isBlank(r.picture!!))
    }
}
