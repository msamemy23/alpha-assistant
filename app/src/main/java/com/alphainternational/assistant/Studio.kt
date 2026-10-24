package com.alphainternational.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The design studio the bots build websites and apps with. The app, not the model, makes the first design decisions: it picks a distinctive
 * visual direction (fonts, colours, spacing, motion) so no two sites look alike, copies the matching font, theme and motion files next to the
 * page, and hands the bot a short art direction to follow. The bot supplies the words, the pictures and the structure.
 */
object Studio {
    data class Pack(val id:String,val name:String,val json:JSONObject)

    fun asset(context:Context,path:String):String? = runCatching {context.assets.open(path).bufferedReader().use {it.readText()}}.getOrNull()

    /** The compact design brief every site-building bot is given. */
    fun brief(context:Context):String = asset(context,"studio/design-brief.md")?.trim()?.takeIf {it.isNotBlank()}?:FALLBACK_BRIEF

    private val siteWords=Regex("\\b(page|site|website|header|footer|hero|section|layout|animation|css|html|navbar|nav bar)\\b")
    private val partWords=Regex("\\b(buttons?|colou?rs?|fonts?|pictures?|photos?|mobile|menu|forms?|logo|game|app|dashboard|tool|calculator)\\b")
    private val changeWords=Regex("\\b(change|update|edit|fix|make|add|remove|delete|move|resize|redo|redesign|improve|tweak|adjust|swap|replace|rename|rewrite|bigger|smaller|larger|center|centre|align|restyle|darker|lighter|brighter|rounder|build|rebuild)\\b" +
        "|\\btoo (small|big|large|dark|light|bright|plain|boring|ugly|slow|wide|narrow)\\b|\\blooks? (bad|ugly|off|wrong|broken|plain|boring|cheap)\\b|\\b(doesn'?t|isn'?t) (look|work|show|showing|fit|centered|aligned)\\b")
    private val notAboutTheSite=Regex("\\b(text|email|e-mail|call|message|remind|forward|reply|invoice|estimate|quote|send|attach)\\b")

    /**
     * Whether a request is about making something for the web (a site, a page, an app, a game). A bot that already has a page in its files only counts
     * when the request itself asks to change it: a chat that merely mentions a photo or a menu is not a building job (it would get the strongest model and a
     * half-hour budget for nothing).
     */
    fun wants(prompt:String,hasSite:Boolean):Boolean {
        val p=prompt.lowercase()
        if(hasSite && changeWords.containsMatchIn(p) && !notAboutTheSite.containsMatchIn(p) && (siteWords.containsMatchIn(p) || partWords.containsMatchIn(p))) return true
        // Anything about a website or software, or a verb of making next to an app, tool, game or similar.
        if(Regex("\\b(web ?site|web ?page|landing page|home ?page|html|pwa|web app|software|online store|online shop|portfolio|booking page|quote form|menu page|(app|tool|game|calculator|dashboard|tracker) for)\\b").containsMatchIn(p)) return true
        val make=Regex("\\b(build|make|create|design|code|develop|program|write|generate|i need|i want|need an?|want an?)\\b")
        val thing=Regex("\\b(app|application|tool|game|calculator|dashboard|tracker|planner|scheduler|generator|converter|quiz|system|platform|crm|invoice builder|estimate builder|checklist)\\b")
        return make.containsMatchIn(p) && thing.containsMatchIn(p) && !Regex("\\b(estimate|invoice|quote|email|text|call|message) (for|to) [a-z]+").containsMatchIn(p)
    }

    fun packs(context:Context):List<Pack> =
        (context.assets.list("studio/packs")?.filter {it.endsWith(".json") && it!="index.json"}.orEmpty()).mapNotNull {file->
            runCatching {val j=JSONObject(asset(context,"studio/packs/$file")!!);Pack(j.optString("id").ifBlank {file.removeSuffix(".json")},j.optString("name").ifBlank {file.removeSuffix(".json")},j)}.getOrNull()
        }

    private fun words(text:String)=text.lowercase().split(Regex("[^a-z0-9]+")).filter {it.length>2}.toSet()
    private fun strs(j:JSONObject,key:String):List<String> {
        val v=j.opt(key)
        return when(v) {is JSONArray->(0 until v.length()).map {v.optString(it)};is String->v.split(',',';').map {it.trim()};else->emptyList()}
    }

    /** The best-fitting pack for [hint] (the business and the feel asked for), skipping the ones used lately; ties are broken at random. */
    fun pick(packs:List<Pack>,hint:String,recent:List<String>,random:java.util.Random=java.util.Random(),preferred:Set<String> = emptySet()):Pack? {
        if(packs.isEmpty()) return null
        val want=words(hint)
        val scored=packs.map {p->
            val have=words((strs(p.json,"mood")+strs(p.json,"best_for")+strs(p.json,"bestFor")+strs(p.json,"trades")+p.name+p.id.replace('-',' ')).joinToString(" "))
            val score=want.count {it in have}*3-(if(p.id in recent) 6 else 0)+(if(p.id in preferred) 2 else 0)+random.nextDouble()*2.5
            p to score
        }
        return scored.maxByOrNull {it.second}?.first
    }

    private fun kebab(name:String)=name.replace(Regex("([a-z0-9])([A-Z])"),"$1-$2").replace('_','-').lowercase()
    private fun px(v:Any?)=when(v) {is Number->(if(v.toDouble()==v.toInt().toDouble()) v.toInt().toString() else v.toString())+"px";else->v?.toString().orEmpty()}

    /**
     * theme.css for a pack: its colours, radius, shadows, spacing, type scale and motion as CSS variables, plus a small base layer that uses them, so
     * a page starts on-style and a later "make it rounder" or "change the accent" is one line. [fontRule] is the pairing's own variable rule from fonts.css.
     */
    fun themeCss(pack:Pack,fontRule:String):String {
        val j=pack.json
        val out=StringBuilder("/* theme.css | "+pack.name+" | change these variables to restyle the whole site */\n:root{\n")
        // The pairing's rule has no closing semicolon; without one the next variable is swallowed into --font-body and the body text falls back to Times.
        if(fontRule.isNotBlank()) out.append("  ").append(fontRule.trim().trimEnd(';')).append(";\n")
        (j.optJSONObject("colors")?:j.optJSONObject("palette"))?.let {colors->
            colors.keys().forEach {k->
                val v=colors.opt(k);val hex=if(v is JSONObject) v.optString("hex") else v?.toString().orEmpty()
                if(hex.startsWith("#")) out.append("  --c-").append(kebab(k)).append(":").append(hex).append(";\n")
            }
        }
        fun group(prefix:String,o:JSONObject?,unit:Boolean) {o?.keys()?.forEach {k->val v=o.opt(k);if(v is String || v is Number) out.append("  --").append(prefix).append("-").append(kebab(k)).append(":").append(if(unit) px(v) else v).append(";\n")}}
        when(val r=j.opt("radius")) {is JSONObject->group("radius",r,true);is String->if(r.isNotBlank()) out.append("  --radius:").append(r).append(";\n")}
        when(val s=j.opt("shadow")) {is JSONObject->group("shadow",s,false);is String->if(s.isNotBlank()) out.append("  --shadow:").append(s).append(";\n")}
        val space=j.optJSONObject("space")?:j.optJSONObject("spacing")
        space?.optJSONArray("scale")?.let {sc->(0 until sc.length()).forEach {i->out.append("  --space-").append(i+1).append(":").append(px(sc.opt(i))).append(";\n")}}
        space?.let {s->listOf("sectionY","gutter","maxWidth").forEach {k->s.opt(k)?.let {v->out.append("  --").append(kebab(k)).append(":").append(px(v)).append(";\n")}}}
        j.optJSONObject("type")?.let {t->
            t.opt("basePx")?.let {out.append("  --text-base:").append(px(it)).append(";\n")}
            t.opt("bodyLeading")?.let {out.append("  --body-leading:").append(it).append(";\n")}
            listOf("h1","h2","h3").forEach {k->t.optString(k).takeIf {v->v.isNotBlank()}?.let {v->out.append("  --").append(k).append(":").append(v).append(";\n")}}
            listOf("headingCase","headingWeight","headingLeading","headingTracking").forEach {k->t.opt(k)?.let {v->out.append("  --").append(kebab(k)).append(":").append(v).append(";\n")}}
        }
        j.optJSONObject("motion")?.let {m->
            m.opt("durationMs")?.let {out.append("  --m-dur:").append(it).append("ms;\n")}
            m.optString("ease").takeIf {it.isNotBlank()}?.let {out.append("  --m-ease:").append(it).append(";\n")}
            m.opt("staggerMs")?.let {out.append("  --m-stagger:").append(it).append("ms;\n")}
        }
        out.append("}\n")
        out.append("""
            /* base layer (low specificity, so your own styles win) */
            :where(html){background:var(--c-bg,#fff);color:var(--c-ink,#111)}
            :where(body){margin:0;font-family:var(--font-body,system-ui,sans-serif);font-size:var(--text-base,17px);line-height:var(--body-leading,1.6)}
            :where(h1,h2,h3,h4,.display){font-family:var(--font-display,inherit);text-transform:var(--heading-case,none);font-weight:var(--heading-weight,700);line-height:var(--heading-leading,1.1);letter-spacing:var(--heading-tracking,0);margin:0 0 .5em}
            :where(h1){font-size:var(--h1,3rem)}:where(h2){font-size:var(--h2,2.2rem)}:where(h3){font-size:var(--h3,1.4rem)}
            :where(a){color:var(--c-accent,#06c)}
            :where(.container){max-width:var(--max-width,1120px);margin-inline:auto;padding-inline:var(--gutter,16px)}
            :where(section){padding-block:clamp(48px,9vw,var(--section-y,96px))}
            :where(.btn){display:inline-flex;align-items:center;justify-content:center;min-height:48px;padding:0 1.4em;border:2px solid var(--c-accent,#06c);border-radius:var(--radius-button,6px);background:var(--c-accent,#06c);color:var(--c-on-accent,#fff);font-weight:700;text-decoration:none;cursor:pointer}
            :where(.card){background:var(--c-surface,transparent);border-radius:var(--radius-md,8px);padding:var(--space-5,24px);box-shadow:var(--shadow-card,none)}
            :where(img){max-width:100%;height:auto}
        """.trimIndent()+"\n")
        return out.toString()
    }

    /** The pairing's own `[data-fonts="id"]{...}` variable declarations from fonts.css, as plain declarations. */
    private fun pairingRule(css:String,id:String):String {
        val m=Regex("\\[data-fonts=\""+Regex.escape(id)+"\"\\]\\{([^}]*)\\}").find(css)
        return m?.groupValues?.get(1).orEmpty()
    }

    /** Copies the files a pack needs next to the page and returns their names. Fonts are copied flat (no folders in a bot's files). */
    fun kit(context:Context,dir:File,pack:Pack,prefix:String=""):List<String> {
        dir.mkdirs()
        val written=mutableListOf<String>()
        fun named(name:String)=if(prefix.isBlank()) name else prefix+"-"+name
        fun text(name:String,body:String) {File(dir,named(name)).writeText(body);written+=named(name)}
        val pairings=asset(context,"studio/pairings.json")?.let {runCatching {JSONObject(it)}.getOrNull()}
        val pairId=pack.json.optString("fonts").ifBlank {pack.json.optString("pairing")}
        val pairing=pairings?.optJSONObject(pairId)
        val files=pairing?.optJSONArray("files")?.strings().orEmpty()
        val css=asset(context,"studio/fonts.css").orEmpty()
        // Only this pack's font files, and a fonts.css that lists only them.
        if(css.isNotBlank() && files.isNotEmpty()) {
            val blocks=Regex("@font-face\\{[^}]*\\}").findAll(css).map {it.value}.filter {b->files.any {f->b.contains("fonts/$f")}}.toList()
            text("fonts.css",blocks.joinToString("\n").replace("url(fonts/","url("))
            files.forEach {f->runCatching {context.assets.open("studio/fonts/$f").use {input->File(dir,f).outputStream().use {out->input.copyTo(out)}};written+=f}}
        }
        text("theme.css",themeCss(pack,pairingRule(css,pairId)))
        asset(context,"studio/motion.css")?.let {text("motion.css",it)}
        asset(context,"studio/motion.js")?.let {text("motion.js",it)}
        return written
    }

    /** Copies the app toolkit (components and logic for tools and apps) next to the page, and returns the file names. */
    fun appkit(context:Context,dir:File,prefix:String=""):List<String> {
        dir.mkdirs()
        return listOf("appkit.css","appkit.js").mapNotNull {name->asset(context,"studio/appkit/$name")?.let {val out=if(prefix.isBlank()) name else prefix+"-"+name;File(dir,out).writeText(it);out}}
    }
    /** The app toolkit's short guide for the bot, from APPKIT.md. */
    fun appkitGuide(context:Context):String = asset(context,"studio/appkit/APPKIT.md")?.trim().orEmpty().take(9000)

    /** A style's colours by role (bg, surface, ink, muted, accent, onAccent), for drawing art that matches it. */
    fun colors(pack:Pack):Map<String,String> = (pack.json.optJSONObject("colors")?:pack.json.optJSONObject("palette"))?.let {c->
        c.keys().asSequence().associateWith {k->val v=c.opt(k);(if(v is JSONObject) v.optString("hex") else v?.toString().orEmpty())}.filterValues {it.startsWith("#")}
    }.orEmpty()

    /** The few parts of a style's description a bot needs to follow it while it rewrites a blueprint. */
    fun rules(pack:Pack):JSONObject = JSONObject().apply {
        put("name",pack.name)
        listOf("signature","hero","background","button","banned","mood").forEach {k->pack.json.opt(k)?.let {v->put(k,v)}}
        pack.json.optJSONObject("motion")?.optString("rule")?.takeIf {it.isNotBlank()}?.let {put("motion",it)}
    }

    /** The motion kit's quick-reference table (which attributes do what), from MOTION.md. */
    fun motionGuide(context:Context):String {
        val text=asset(context,"studio/MOTION.md")?:return ""
        val start=text.indexOf("## Quick reference");if(start<0) return text.take(2500)
        val end=text.indexOf("\n## ",start+10).let {if(it<0) text.length else it}
        return text.substring(start,end).trim().take(3500)
    }

    /** Inline SVG for icon [name] (see icons.json), or null. */
    fun icon(context:Context,name:String,size:Int=24):String? {
        val icons=asset(context,"studio/icons.json")?.let {runCatching {JSONObject(it)}.getOrNull()}?:return null
        val i=icons.optJSONObject(name.lowercase().trim())?:return null
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$size\" height=\"$size\" viewBox=\""+i.optString("viewBox","0 0 24 24")+"\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\">"+i.optString("body")+"</svg>"
    }
    fun iconNames(context:Context):List<String> = asset(context,"studio/icons.json")?.let {runCatching {JSONObject(it).keys().asSequence().toList().filterNot {k->k.startsWith("_")}}.getOrNull()}.orEmpty()

    /** Photos from the curated catalog that match [query] (tags, titles), best first. */
    fun photos(context:Context,query:String,count:Int):List<JSONObject> {
        val list=asset(context,"studio/auto-photos.json")?.let {runCatching {val v=JSONObject(it).optJSONArray("photos")?:JSONArray(it);(0 until v.length()).map {i->v.getJSONObject(i)}}.getOrNull()}.orEmpty()
        val want=words(query)
        // Pixabay's own summary says only its full license text binds, so its photos stay in the catalog file but are never handed out for a business site.
        return list.filter {it.optInt("verified_http_status",200)==200 && !it.optString("source").equals("pixabay",true)}.map {p->
            val have=words(strs(p,"tags").joinToString(" ")+" "+p.optString("title")+" "+p.optString("alt_text")+" "+p.optString("best_use"))
            p to want.count {w->w in have}
        }.filter {it.second>0 || want.isEmpty()}.sortedByDescending {it.second}.take(count.coerceIn(1,12)).map {it.first}
    }

    const val FALLBACK_BRIEF="""DESIGN BRIEF for any website, web app or game you build:
1. Before any code, write 4 lines: who it is for, the one idea for the top of the page, three adjectives for the feel, and the style pack studio_start gave you.
2. Follow the pack: use its fonts, its colours (theme.css variables) and its motion. Link fonts.css, theme.css, motion.css and motion.js, and mark sections with data-reveal.
3. At most 6 sections, one H1, two typefaces, one accent colour, one main button above the fold (a tap-to-call link on phones).
4. Use real pictures (the owner's own photos, or studio_photos), real words from the owner, and real icons (studio_icons). Never invent testimonials, numbers or team names. No emoji as icons. No default blue-purple gradient, no centered hero over a card grid.
5. Make it work on a phone first: text at least 16 px, tap targets at least 44 px, contrast at least 4.5:1, alt text on every picture.
6. Mark each section with <!-- section:name --> ... <!-- /section:name --> so changes are small, use file_edit or section_replace for later changes, and ALWAYS call page_screenshot, fix what you see, and look again before you say it's done."""
}
