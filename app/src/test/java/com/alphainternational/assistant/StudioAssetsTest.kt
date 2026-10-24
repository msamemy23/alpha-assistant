package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** The bundled design studio files are complete and agree with each other, so a bot never gets a style that points at a missing font. */
class StudioAssetsTest {
    private val root=File("src/main/assets/studio")
    private fun json(path:String)=JSONObject(File(root,path).readText())

    private fun luminance(hex:String):Double {
        val c=hex.removePrefix("#").chunked(2).map {it.toInt(16)/255.0}.map {if(it<=0.03928) it/12.92 else Math.pow((it+0.055)/1.055,2.4)}
        return 0.2126*c[0]+0.7152*c[1]+0.0722*c[2]
    }
    private fun ratio(a:String,b:String):Double {val x=luminance(a);val y=luminance(b);return (maxOf(x,y)+0.05)/(minOf(x,y)+0.05)}

    @Test fun theStudioFilesAreThere() {
        listOf("design-brief.md","fonts.css","pairings.json","motion.css","motion.js","icons.json").forEach {assertTrue(it,File(root,it).isFile && File(root,it).length()>200)}
        assertTrue(File(root,"design-brief.md").readText().length<3200)
    }

    @Test fun everyStyleIsCompleteAndUsesRealFonts() {
        val pairings=json("pairings.json");val css=File(root,"fonts.css").readText()
        val packs=File(root,"packs").listFiles()!!.filter {it.name.endsWith(".json") && it.name!="index.json"}
        assertTrue("16 styles expected, found ${packs.size}",packs.size>=16)
        packs.forEach {file->
            val p=JSONObject(file.readText());val id=p.getString("id")
            assertEquals(file.nameWithoutExtension,id)
            listOf("name","signature","mood","bestFor","fonts","colors","radius","shadow","space","type","motion","hero","background","button","banned").forEach {k->assertTrue("$id missing $k",p.has(k))}
            val pair=pairings.optJSONObject(p.getString("fonts"));assertNotNull("$id uses an unknown font pairing "+p.getString("fonts"),pair)
            val files=pair!!.getJSONArray("files").let {a->(0 until a.length()).map {i->a.getString(i)}}
            files.forEach {f->assertTrue("$id needs font $f",File(root,"fonts/$f").isFile)}
            assertTrue("$id: fonts.css has no rule for its pairing",css.contains("[data-fonts=\""+p.getString("fonts")+"\"]"))
            val colors=p.getJSONObject("colors")
            listOf("bg","surface","ink","muted","accent","onAccent").forEach {k->assertTrue("$id colour $k",colors.getString(k).matches(Regex("#[0-9A-Fa-f]{6}")))}
            // Text on the page's backgrounds is readable (AA), and the button label on the accent too.
            assertTrue("$id ink on bg",ratio(colors.getString("ink"),colors.getString("bg"))>=4.5)
            assertTrue("$id ink on surface",ratio(colors.getString("ink"),colors.getString("surface"))>=4.5)
            assertTrue("$id muted on bg",ratio(colors.getString("muted"),colors.getString("bg"))>=4.5)
            assertTrue("$id label on accent",ratio(colors.getString("onAccent"),colors.getString("accent"))>=4.5)
            assertTrue("$id has banned elements",p.getJSONArray("banned").length()>=3)
        }
    }

    @Test fun theStylesReallyDiffer() {
        val packs=File(root,"packs").listFiles()!!.filter {it.name.endsWith(".json") && it.name!="index.json"}.map {JSONObject(it.readText())}
        assertTrue("fonts should vary",packs.map {it.getString("fonts")}.toSet().size>=7)
        assertTrue("accent colours should vary",packs.map {it.getJSONObject("colors").getString("accent").lowercase()}.toSet().size>=14)
        assertTrue("hero treatments should vary",packs.map {it.getJSONObject("hero").getString("treatment")}.toSet().size>=6)
        // A shop-friendly style exists for auto repair.
        assertTrue(packs.any {p->(0 until p.getJSONArray("bestFor").length()).any {i->p.getJSONArray("bestFor").getString(i).contains("auto repair")}})
    }

    @Test fun theIconSetCoversWhatAShopSiteNeeds() {
        val icons=json("icons.json")
        listOf("wrench","car","phone","map-pin","clock","star","shield-check","calendar","mail","check","arrow-right","menu","x","camera").forEach {assertTrue("icon $it",icons.has(it))}
        icons.keys().forEach {k->if(!k.startsWith("_")) {val i=icons.getJSONObject(k);assertTrue(k,i.optString("body").isNotBlank() && i.optString("viewBox").isNotBlank())}}
    }

    @Test fun theMotionKitDoesWhatTheBriefPromises() {
        val css=File(root,"motion.css").readText();val js=File(root,"motion.js").readText()
        listOf("data-reveal","data-stagger","data-count-to","prefers-reduced-motion").forEach {assertTrue(it,css.contains(it) || js.contains(it))}
        // A screenshot URL parameter makes every effect finish at once, so a picture of the page shows the finished design.
        assertTrue(js.contains("shot"))
        assertTrue((css.length+js.length)<40_000)
    }

    /** theme.css as the app writes it for each style: every variable is declared on its own, so a missing semicolon can't swallow the next one. */
    @Test fun everyThemeFileHasWellFormedVariables() {
        val css=File(root,"fonts.css").readText()
        File(root,"packs").listFiles()!!.filter {it.name.endsWith(".json") && it.name!="index.json"}.forEach {file->
            val j=JSONObject(file.readText());val id=j.getString("id")
            val rule=Regex("\\[data-fonts=\""+Regex.escape(j.getString("fonts"))+"\"\\]\\{([^}]*)\\}").find(css)?.groupValues?.get(1).orEmpty()
            val theme=Studio.themeCss(Studio.Pack(id,j.optString("name"),j),rule)
            val vars=theme.substringAfter(":root{").substringBefore("\n}\n").lines().map {it.trim()}.filter {it.isNotEmpty()}
            vars.forEach {line->assertTrue("$id: not a single declaration (missing semicolon?): $line",line.endsWith(";") && line.count {c->c==';'}>=1)}
            listOf("--c-bg:","--c-surface:","--c-ink:","--c-accent:","--c-on-accent:","--font-display:","--font-body:").forEach {v->assertTrue("$id lacks $v",theme.contains("\n  $v") || theme.contains(";$v"))}
            val body=Regex("--font-body:([^;]*);").find(theme)?.groupValues?.get(1).orEmpty()
            assertFalse("$id: --font-body swallowed another variable: $body",body.contains("--"))
        }
    }
}
