package com.alphainternational.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Every bundled blueprint is complete, flat, self-contained and honest, so studio_start can never hand a bot a page that is broken before it starts. */
class BlueprintAssetsTest {
    private val root=File("src/main/assets/studio/blueprints")
    private val dirs get()=root.listFiles()?.filter {it.isDirectory && File(it,"blueprint.json").isFile}.orEmpty().sortedBy {it.name}
    private val allowedTokens=setOf("business","tagline","phone","phone_href","email","email_href","address","city","hours","year","initial","slug")
    private val allowedUrls=listOf("http://www.w3.org/","https://schema.org","https://www.google.com/maps","https://maps.google.com","https://www.openstreetmap.org","https://wa.me/","https://creativecommons.org/")

    @Test fun theLibraryHasSitesAndApps() {
        val kinds=dirs.map {JSONObject(File(it,"blueprint.json").readText()).optString("kind")}
        assertTrue("at least 6 site blueprints expected, found ${kinds.count {it=="site" || it=="local"}}",kinds.count {it=="site" || it=="local"}>=6)
        assertTrue("at least 4 app blueprints expected, found ${kinds.count {it=="app"}}",kinds.count {it=="app"}>=4)
    }

    @Test fun everyBlueprintIsCompleteFlatAndSelfContained() {
        dirs.forEach {dir->
            val id=dir.name
            val j=JSONObject(File(dir,"blueprint.json").readText())
            assertEquals("$id: blueprint.json id",id,j.optString("id"))
            val kind=j.optString("kind");assertTrue("$id: kind $kind",kind in setOf("site","local","app"))
            listOf("name","summary").forEach {k->assertTrue("$id: $k",j.optString(k).length>=8)}
            val files=j.getJSONArray("files").let {a->(0 until a.length()).map {a.getString(it)}}.filter {it!="blueprint.json"}
            assertTrue("$id: needs index.html and style.css",files.containsAll(listOf("index.html","style.css")))
            files.forEach {f->
                assertFalse("$id: $f must be a flat name",f.contains('/') || f.contains('\\'))
                val file=File(dir,f);assertTrue("$id: $f is listed but missing",file.isFile)
                assertTrue("$id: $f is ${file.length()} bytes (limit 90,000 so a bot can read and edit it)",file.length()<90_000)
            }
            val onDisk=dir.listFiles()!!.map {it.name}.filter {it!="blueprint.json"}
            assertEquals("$id: files on disk and files listed must match",onDisk.sorted(),files.sorted())
            assertTrue("$id: best_for",j.optJSONArray("best_for")?.length()?:0>=3)
            assertTrue("$id: sections",j.optJSONArray("sections")?.length()?:0>=3)

            val texts=files.filter {it.endsWith(".html") || it.endsWith(".css") || it.endsWith(".js") || it.endsWith(".svg")}.associateWith {File(dir,it).readText()}
            // No outside requests, ever: every address in the files is on the short list of harmless ones (namespaces, JSON-LD context, map links).
            texts.forEach {(name,text)->
                Regex("https?://[^\\s\"'<>)]+").findAll(text).forEach {m->assertTrue("$id/$name has an outside address: ${m.value}",allowedUrls.any {p->m.value.startsWith(p)})}
                assertFalse("$id/$name has a placeholder link",Regex("href=\"#\"").containsMatchIn(text))
                assertFalse("$id/$name has lorem ipsum",text.contains("lorem ipsum",true))
            }
            val index=texts.getValue("index.html")
            assertTrue("$id: <html lang=\"en\" data-kind=\"$kind\">",Regex("<html[^>]*lang=\"en\"[^>]*data-kind=\"$kind\"").containsMatchIn(index) || Regex("<html[^>]*data-kind=\"$kind\"[^>]*lang=\"en\"").containsMatchIn(index))
            assertTrue("$id: viewport",index.contains("name=\"viewport\""))
            assertTrue("$id: links style.css",index.contains("style.css"))
            assertTrue("$id: links theme.css and motion",index.contains("theme.css") && (kind=="app" || index.contains("motion.js")))
            assertTrue("$id: section markers",Regex("<!-- section:[a-z0-9-]+ -->").findAll(index).count()>=3)
            if(kind=="local") assertTrue("$id: JSON-LD",index.contains("application/ld+json"))
            if(kind=="app") assertTrue("$id: links appkit",index.contains("appkit.css") && index.contains("appkit.js"))
            // Tokens: only the ones the app fills, and every photo slot used is declared (and every declared slot is used).
            val used=Blueprints.tokensIn(texts.values)
            assertTrue("$id: unknown tokens ${used-allowedTokens}",used.all {it in allowedTokens})
            val declared=j.optJSONArray("photo_slots")?.let {a->(0 until a.length()).map {a.getJSONObject(it).getString("slot")}}.orEmpty().toSet()
            val slots=Blueprints.slotsIn(texts.values).toSet()
            assertEquals("$id: photo slots used vs declared",declared,slots)
            assertTrue("$id: title uses the business",Regex("<title>[^<]*\\{\\{business}}").containsMatchIn(index) || kind=="app")
        }
    }

    @Test fun everyBlueprintFillsWithoutLeavingTokensBehind() {
        dirs.forEach {dir->
            val j=JSONObject(File(dir,"blueprint.json").readText())
            val files=j.getJSONArray("files").let {a->(0 until a.length()).map {a.getString(it)}}.filter {it!="blueprint.json"}
            val texts=files.filter {it.endsWith(".html") || it.endsWith(".css") || it.endsWith(".js") || it.endsWith(".svg")}.associateWith {File(dir,it).readText()}
            val slots=Blueprints.slotsIn(texts.values).associateWith {Blueprints.Placed("photo-$it.jpg","Alt for $it")}
            val values=Blueprints.tokens(Blueprints.Facts("Rosa's Bakery","Fresh every morning","(512) 555-0142","rosa@example.com","12 Elm St, Austin","Austin","Mon-Sat 7-3"),2026)
            texts.forEach {(name,text)->
                val out=Blueprints.fill(name,text,values,slots)
                assertFalse("${dir.name}/$name still has {{ }}: ${Regex("\\{\\{[^}]*}}").find(out)?.value}",out.contains("{{"))
            }
        }
    }
}
