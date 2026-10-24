package com.alphainternational.assistant

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** studio_start on a real device with the real bundled blueprints: a finished page lands in the bot's files with the owner's facts and pictures in place. */
class StudioFlowTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun temp():File=File(context.cacheDir,"studio-flow-"+System.nanoTime()).also {it.mkdirs()}
    private val fakeStock:suspend (String,Int)->List<Pictures.Stock> = {q,n->(1..n).map {i->Pictures.Stock("https://example.test/$q-$i.jpg","Photo of $q $i",if(i==2) "Jane (flickr, CC BY)" else "",if(i%3==0) 900 else 1600,if(i%3==0) 1400 else 1000,"stocksnap")}}
    private val fakeFetch:suspend (String,File)->String = {url,dir->File(dir,"photo-"+url.hashCode().toString(16)+".jpg").also {it.writeBytes(ByteArray(300) {b->b.toByte()})}.name}
    private fun args(business:String,kind:String="site",facts:String="",extra:JSONObject.()->Unit={})=JSONObject().put("business",business).put("feel","warm").put("kind",kind).put("blueprint","").put("facts",facts).put("photo_topics","").put("fresh",false).apply(extra)

    @Test fun aSiteBlueprintArrivesFilledInWithPicturesAndAWorkingStyle() {
        val dir=temp()
        try {
            val started=runBlocking {StudioFlow.start(context,dir,args("a bakery in Austin selling sourdough","site","name: Rosa's Bakery\nphone: (512) 555-0142\ncity: Austin"),emptyList(),emptyList(),fakeFetch,fakeStock)}
            val files=dir.listFiles()!!.map {it.name}
            assertTrue(files.toString(),files.containsAll(listOf("index.html","style.css","theme.css","fonts.css","motion.css","motion.js")))
            val index=File(dir,"index.html").readText()
            assertTrue(index.contains("Rosa&#39;s Bakery") || index.contains("Rosa's Bakery") || index.contains("Rosa&amp;#39;s Bakery") || index.contains("Rosa"))
            assertTrue("phone link",index.contains("tel:+15125550142"))
            assertFalse("no tokens left",index.contains("{{"))
            assertTrue("photos placed",started.result.getJSONArray("photos").length()>0)
            // The theme file is well formed (a missing semicolon once broke the body font).
            val theme=File(dir,"theme.css").readText()
            assertTrue(theme.contains("--c-surface:") && !Regex("--font-body:[^;]*--").containsMatchIn(theme))
            assertTrue(started.result.getString("result").contains("blueprint",true))
            assertTrue(started.result.getJSONArray("missing_facts").length()>0)
        } finally {dir.deleteRecursively()}
    }

    @Test fun anAppRequestGetsTheToolkitAndAHouseSiteIsNotOverwritten() {
        val dir=temp()
        try {
            val first=runBlocking {StudioFlow.start(context,dir,args("an estimate builder for a tire shop","app"),emptyList(),emptyList(),fakeFetch,fakeStock)}
            assertTrue(File(dir,"appkit.js").isFile && File(dir,"appkit.css").isFile)
            assertTrue(first.result.has("appkit_guide"))
            File(dir,"index.html").writeText("<h1>my page</h1>")
            val themeBefore=File(dir,"theme.css").takeIf {it.isFile}?.readText()
            // A second project never touches the first: it gets its own file names, and its page links to them.
            val again=runBlocking {StudioFlow.start(context,dir,args("a bakery","site"),emptyList(),emptyList(),fakeFetch,fakeStock)}
            assertEquals("<h1>my page</h1>",File(dir,"index.html").readText())
            val second=again.result.getString("main_page")
            assertTrue(second,second!="index.html" && second.endsWith(".html") && File(dir,second).isFile)
            val prefix=second.removeSuffix(".html")
            assertTrue(File(dir,"$prefix-style.css").isFile && File(dir,"$prefix-theme.css").isFile)
            val html=File(dir,second).readText()
            assertTrue(html.contains("$prefix-style.css") && html.contains("$prefix-theme.css") && !html.contains("href=\"theme.css\""))
            if(themeBefore!=null) assertEquals(themeBefore,File(dir,"theme.css").readText())
            // Starting over keeps the old page.
            val fresh=runBlocking {StudioFlow.start(context,dir,args("a bakery","site",extra={put("fresh",true)}),emptyList(),emptyList(),fakeFetch,fakeStock)}
            assertEquals("<h1>my page</h1>",File(dir,"old-index.html").readText())
            assertTrue(File(dir,"index.html").readText().length>2000 && fresh.blueprint.isNotBlank())
        } finally {dir.deleteRecursively()}
    }

    @Test fun withNoInternetTheSlotsGetMatchingArtInstead() {
        val dir=temp()
        try {
            val started=runBlocking {StudioFlow.start(context,dir,args("a florist"),emptyList(),emptyList(),{_,_->error("offline")},{_,_->emptyList()})}
            val photos=started.result.getJSONArray("photos")
            assertTrue(photos.length()>0 && (0 until photos.length()).all {photos.getJSONObject(it).getString("file").startsWith("art-")})
            assertTrue(dir.listFiles()!!.any {it.name.startsWith("art-") && it.name.endsWith(".svg")})
            assertFalse(File(dir,"index.html").readText().contains("{{"))
        } finally {dir.deleteRecursively()}
    }
}
