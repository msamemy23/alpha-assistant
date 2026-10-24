package com.alphainternational.assistant

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * studio_start: the app, not the model, makes the first design decisions. It picks a visual style and a finished blueprint, fills in the owner's facts,
 * downloads real photos for the picture slots (or draws matching art when offline), and hands the bot a complete, beautiful page to rewrite and refine.
 */
object StudioFlow {
    data class Started(val result:JSONObject,val pack:String,val blueprint:String)

    /** Stock photos for the slots: the app's own auto-shop catalog when it matches, then Openverse for any topic, interleaved so one topic doesn't fill every slot. */
    private suspend fun pool(context:Context,topics:List<String>,want:Int,stock:suspend (String,Int)->List<Pictures.Stock>):List<Pictures.Stock> {
        val catalog=Studio.photos(context,topics.joinToString(" "),6).mapNotNull {p->
            val url=p.optString("image_url_1200").ifBlank {p.optString("image_url")}.takeIf {it.startsWith("https://")}?:return@mapNotNull null
            val portrait=p.optString("orientation").equals("portrait",true)
            Pictures.Stock(url,p.optString("alt_text").ifBlank {p.optString("title")},if(p.optBoolean("credit_required")) p.optString("photographer")+" ("+p.optString("source")+")" else "",
                if(portrait) 1000 else 1600,if(portrait) 1500 else 1000,"catalog")
        }
        val found=coroutineScope {topics.map {t->async {runCatching {stock(t,(want+2).coerceAtMost(10))}.getOrDefault(emptyList())}}.awaitAll()}
        val mixed=ArrayList<Pictures.Stock>()
        var i=0
        while(mixed.size<want*2 && found.any {i<it.size}) {found.forEach {list->list.getOrNull(i)?.let {mixed+=it}};i++}
        return (catalog+mixed).distinctBy {it.url}
    }

    /** "app" when the request is a tool or app (calculator, tracker, estimate builder...), "game" for a game, otherwise "site". */
    fun guessKind(text:String):String {
        val t=text.lowercase()
        return when {
            Regex("\\b(game|arcade|puzzle|quiz game)\\b").containsMatchIn(t) -> "game"
            Regex("\\b(app|tool|calculator|dashboard|tracker|planner|software|system|crm|checklist|inventory|invoice|estimate|quote builder|scheduler|booking manager|manager|organizer|converter|generator)\\b").containsMatchIn(t) &&
                !Regex("\\b(website|web site|landing page|homepage|home page)\\b").containsMatchIn(t) -> "app"
            else -> "site"
        }
    }

    suspend fun start(context:Context,dir:File,a:JSONObject,recentPacks:List<String>,recentBlueprints:List<String>,
        fetch:suspend (String,File)->String = {u,f->Pictures.fetch(u,f)},stock:suspend (String,Int)->List<Pictures.Stock> = {q,n->Pictures.stock(q,n)}):Started {
        val packs=Studio.packs(context)
        require(packs.isNotEmpty()) {"The design studio files are missing from this version of the app."}
        val business=a.getString("business");val feel=a.optString("feel")
        val kind=a.optString("kind").lowercase().trim().let {k->if(k.isBlank() || k=="auto") guessKind("$business ${a.optString("feel")}") else k}
        val facts=Blueprints.parseFacts(a.optString("facts")).let {if(it.name.isBlank()) it.copy(name=business.substringBefore(',').substringBefore(" - ").trim().take(60).takeIf {n->n.split(' ').size<=6}.orEmpty()) else it}
        val all=Blueprints.all(context)
        val wanted=a.optString("blueprint").trim().lowercase()
        val blueprint=if(wanted=="none" || wanted=="custom") null else all.firstOrNull {it.id==wanted}?:Blueprints.pick(all,kind,"$business $feel",recentBlueprints)
        val hasPage=File(dir,"index.html").isFile
        val fresh=a.optBoolean("fresh")
        val useBlueprint=blueprint!=null
        // A second project in the same files gets its own file names (<blueprint>.html, <blueprint>-style.css ...), so it never replaces or restyles the first one.
        val prefix=if(hasPage && !fresh) (blueprint?.id?:"project-"+kind) else ""
        val mainName=if(prefix.isBlank()) "index.html" else "$prefix.html"
        fun named(name:String)=when {prefix.isBlank()->name;name=="index.html"->mainName;else->prefix+"-"+name}
        val pack=Studio.pick(packs,"$business $feel",recentPacks,preferred=Blueprints.preferred(blueprint,packs.map {it.id}))?:error("No style could be chosen.")
        dir.mkdirs()
        val written=ArrayList<String>()
        if(useBlueprint && hasPage && fresh) {
            // Starting over: the old files are kept under old- names, never thrown away.
            blueprint!!.files.plus("index.html").distinct().forEach {name->
                File(dir,name).takeIf {f->f.isFile}?.let {f->f.copyTo(File(dir,"old-$name"),true)}
            }
        }
        written+=Studio.kit(context,dir,pack,prefix)
        val out=JSONObject()
        val missing=ArrayList<String>()
        val placedList=JSONArray()
        var credits=""
        if(useBlueprint) {
            val bp=blueprint!!
            val texts=bp.files.mapNotNull {n->Blueprints.text(context,bp,n)?.let {t->n to t}}.toMap()
            val slotNames=(bp.slots.map {it.name}+Blueprints.slotsIn(texts.filterKeys {it.endsWith(".html") || it.endsWith(".css") || it.endsWith(".js")}.values)).distinct()
            val slotInfo=slotNames.map {n->bp.slots.firstOrNull {it.name==n}?:Blueprints.Slot(n,"","")}
            val topics=a.optString("photo_topics").split(',',';','\n').map {it.trim()}.filter {it.isNotEmpty()}.take(4).ifEmpty {listOf(business.take(70))}
            val candidates=if(slotInfo.isEmpty()) emptyList() else pool(context,topics,slotInfo.size,stock)
            val assigned=Blueprints.assign(slotInfo,candidates)
            val placed=LinkedHashMap<String,Blueprints.Placed>()
            val spare=ArrayList(candidates.filter {c->assigned.values.none {it.url==c.url}})
            val tried=coroutineScope {assigned.map {(slot,photo)->async {slot to runCatching {fetch(photo.url,dir)}.map {f->Blueprints.Placed(f,photo.alt.ifBlank {facts.name.ifBlank {business}},photo.credit)}.getOrNull()}}.awaitAll()}
            tried.forEach {(slot,p)->
                if(p!=null) placed[slot]=p
                else while(spare.isNotEmpty() && slot !in placed) {
                    val next=spare.removeAt(0)
                    runCatching {fetch(next.url,dir)}.getOrNull()?.let {f->placed[slot]=Blueprints.Placed(f,next.alt.ifBlank {facts.name.ifBlank {business}},next.credit)}
                }
            }
            val colors=Studio.colors(pack)
            slotNames.filter {it !in placed}.forEach {slot->
                val art=named("art-$slot.svg")
                File(dir,art).writeText(Blueprints.art(slot,colors));written+=art
                placed[slot]=Blueprints.Placed(art,"")
            }
            credits=placed.values.map {it.credit}.filter {it.isNotBlank()}.distinct().joinToString("; ")
            val values=Blueprints.tokens(facts,java.time.Year.now().value)
            missing+=Blueprints.missing(facts,Blueprints.tokensIn(texts.values))
            // The page's links to its own files follow the new names.
            val local=bp.files.filter {it!="index.html"}+listOf("theme.css","fonts.css","motion.css","motion.js","appkit.css","appkit.js")
            fun retarget(text:String)=if(prefix.isBlank()) text else local.fold(text) {acc,old->acc.replace(Regex("(?<![A-Za-z0-9._-])"+Regex.escape(old)+"(?![A-Za-z0-9])"),named(old))}
            texts.forEach {(name,text)->
                var body=retarget(Blueprints.fill(name,text,values,placed))
                if(name=="index.html" && credits.isNotBlank() && !body.contains(credits.take(20))) {
                    val line="<p class=\"photo-credits\" style=\"font-size:.8rem;opacity:.7;margin:1rem auto;max-width:var(--max-width,1120px);padding-inline:var(--gutter,16px)\">Photos: ${credits.replace("&","&amp;").replace("<","&lt;")}</p>"
                    body=if(body.contains("</footer>")) body.replace("</footer>",line+"</footer>") else body.replace("</body>",line+"</body>")
                }
                File(dir,named(name)).writeText(body);written+=named(name)
            }
            placed.forEach {(slot,p)->placedList.put(obj("slot" to slot,"file" to p.file,"alt" to p.alt,"credit" to p.credit,"is_photo" to !p.file.endsWith(".svg")))}
            out.put("blueprint",obj("id" to bp.id,"name" to bp.name,"kind" to bp.kind,"summary" to bp.summary,"sections" to (bp.json.optJSONArray("sections")?:JSONArray()),
                "behaviors" to (bp.json.optJSONArray("behaviors")?:JSONArray()),"remove_if_no_facts" to (bp.json.optJSONArray("remove_if_no_facts")?:JSONArray()),
                "photo_slots" to (bp.json.optJSONArray("photo_slots")?:JSONArray())))
        }
        val isApp=(blueprint?.kind=="app" && useBlueprint) || kind=="app"
        if(isApp) written+=Studio.appkit(context,dir,prefix)
        out.put("style",pack.name).put("style_rules",Studio.rules(pack)).put("files_written",JSONArray(written.distinct())).put("main_page",mainName)
        if(useBlueprint) {
            out.put("photos",placedList)
            out.put("missing_facts",JSONArray(missing))
            if(credits.isNotBlank()) out.put("photo_credits",credits)
            out.put("result","Style: ${pack.name}. Blueprint: ${blueprint!!.name} (${blueprint.id}) is now your page: $mainName, ${named("style.css")} and the other files listed, with the style applied"+
                (if(placedList.length()>0) ", and ${(0 until placedList.length()).count {placedList.getJSONObject(it).optBoolean("is_photo")}} real photo(s) in place" else "")+". It is already a finished design: KEEP its structure, layout, motion and styling."+
                (if(prefix.isNotBlank()) " You already had a page (index.html): it is untouched; this is a separate project with its own file names (starting with $prefix), so open and edit $mainName, never index.html." else ""))
            out.put("next",JSONArray(listOf(
                "1. Read $mainName once (file_read), then rewrite ONLY the words with the owner's real facts, using file_edit or section_replace. Replace every sample sentence; delete sections you have no facts for (see remove_if_no_facts).",
                "2. Fill the missing facts by asking the owner only if you truly can't proceed; otherwise remove or generalise that part. Never invent phone numbers, prices, reviews, years or statistics.",
                "3. Look at the photos (file_view) if you doubt them; swap a photo by changing the file name in its img src (the owner's own photos are best; studio_photos finds more for ANY subject; photo_prepare shrinks the owner's phone photos for the web).",
                "4. Restyle by changing variables in ${named("theme.css")} (colours, radius, spacing, fonts) rather than rewriting the CSS.",
                "5. page_screenshot $mainName (phone, then computer), fix every 'fix:' line and anything that looks off, and look once more before you say it's done.")))
        } else {
            out.put("result","Style chosen: "+pack.name+". The files below are in your files; link them from your page. Follow the brief and the style's rules."+
                (if(prefix.isNotBlank()) " Your existing page (index.html) is untouched: this project's style files start with $prefix-, so link those names and name your page $mainName." else "")+
                (if(isApp) " The app toolkit (${named("appkit.css")}, ${named("appkit.js")}) is in your files: build the app with it (guide below)." else ""))
            out.put("link_these","<link rel=\"stylesheet\" href=\"${named("fonts.css")}\"> <link rel=\"stylesheet\" href=\"${named("theme.css")}\"> <link rel=\"stylesheet\" href=\"${named("motion.css")}\"> <script src=\"${named("motion.js")}\"></script> (motion.js goes in the head with no defer; then your own style.css and app.js)"+
                (if(isApp) " <link rel=\"stylesheet\" href=\"${named("appkit.css")}\"> <script src=\"${named("appkit.js")}\" defer></script> and set <html data-kind=\"app\">." else ""))
        }
        if(kind=="game") out.put("game_hint","For a game or toy, call game_template (and game_library): they give you a finished, playable starting point with sound, juice and mobile controls. Use this style's fonts and colours for its menus.")
        if(isApp) out.put("appkit_guide",Studio.appkitGuide(context))
        out.put("motion_guide",Studio.motionGuide(context)).put("brief",Studio.brief(context))
        out.put("available_blueprints",JSONArray(all.map {obj("id" to it.id,"kind" to it.kind,"for" to it.json.optJSONArray("best_for"))}))
        return Started(out,pack.id,if(useBlueprint) blueprint!!.id else "")
    }
}
