package com.alphainternational.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Blueprints: finished, professionally designed starting points for websites and apps (landing pages, local-business sites, portfolios, event pages,
 * profile cards, estimate builders, trackers...). studio_start copies one into the bot's files with the chosen visual style applied, the owner's facts
 * filled in and real photos already in place, so the bot rewrites words and refines instead of inventing a page from nothing.
 */
object Blueprints {
    data class Slot(val name:String,val orientation:String,val hint:String)
    data class Blueprint(val id:String,val kind:String,val name:String,val json:JSONObject) {
        val summary get()=json.optString("summary")
        val files:List<String> get()=json.optJSONArray("files")?.strings().orEmpty().filter {it!="blueprint.json"}
        val slots:List<Slot> get()=json.optJSONArray("photo_slots")?.objects().orEmpty().map {Slot(it.optString("slot"),it.optString("orientation"),it.optString("hint"))}.filter {it.name.isNotBlank()}
    }
    /** What the owner told the bot about the business; blank values get a visible placeholder and are reported back as missing. */
    data class Facts(val name:String="",val tagline:String="",val phone:String="",val email:String="",val address:String="",val city:String="",val hours:String="")
    /** A photo (or drawn art) chosen for a photo slot. */
    data class Placed(val file:String,val alt:String,val credit:String="")

    private const val DIR="studio/blueprints"

    fun all(context:Context):List<Blueprint> =
        (context.assets.list(DIR)?.toList().orEmpty()).mapNotNull {dir->
            runCatching {
                val j=JSONObject(Studio.asset(context,"$DIR/$dir/blueprint.json")!!)
                Blueprint(j.optString("id").ifBlank {dir},j.optString("kind","site"),j.optString("name").ifBlank {dir},j)
            }.getOrNull()
        }.sortedBy {it.id}

    fun text(context:Context,bp:Blueprint,file:String):String? = Studio.asset(context,"$DIR/${bp.id}/$file")

    private fun words(text:String)=text.lowercase().split(Regex("[^a-z0-9]+")).filter {it.length>2}.toSet()
    private fun strs(j:JSONObject,key:String)=j.optJSONArray(key)?.strings().orEmpty()

    /** The blueprint that fits [hint] (what the business or app is, and the feel) best among those of [kind] (site, app or any); ties are broken at random. */
    fun pick(all:List<Blueprint>,kind:String,hint:String,recent:List<String>,random:java.util.Random=java.util.Random()):Blueprint? {
        val wanted=kind.lowercase().trim()
        val pool=all.filter {wanted.isBlank() || wanted=="auto" || (wanted=="app" && it.kind=="app") || (wanted=="game" && it.kind=="game") ||
            (wanted in setOf("site","website","local") && it.kind in setOf("site","local"))}.ifEmpty {if(wanted=="app" || wanted=="game") emptyList() else all}
        if(pool.isEmpty()) return null
        val want=words(hint)
        return pool.map {bp->
            val have=words((strs(bp.json,"best_for")+strs(bp.json,"mood")+bp.name+bp.summary+bp.id.replace('-',' ')).joinToString(" "))
            bp to (want.count {it in have}*3-(if(bp.id in recent) 4 else 0)+random.nextDouble()*2.0)
        }.maxByOrNull {it.second}?.first
    }

    /** The styles the blueprint's notes recommend (whole style ids named in style_notes, like dark-luxury or coastal); they get a bonus on top of the usual pick. */
    fun preferred(bp:Blueprint?,styleIds:Collection<String>):Set<String> {
        val notes=bp?.json?.optString("style_notes").orEmpty().lowercase()
        return styleIds.filter {id->Regex("(?<![a-z-])"+Regex.escape(id)+"(?![a-z-])").containsMatchIn(notes)}.toSet()
    }

    // ---- facts and tokens ----

    private fun digits(s:String)=s.filter {it.isDigit()}
    fun phoneHref(phone:String):String {
        val d=digits(phone)
        return when {
            phone.trim().startsWith("+") && d.length>=8 -> "tel:+$d"
            d.length==10 -> "tel:+1$d"
            d.length==11 && d.startsWith("1") -> "tel:+$d"
            d.length>=7 -> "tel:$d"
            else -> "#contact"
        }
    }
    fun slug(name:String):String=name.lowercase().replace("'","").replace("’","").replace(Regex("[^a-z0-9]+"),"-").trim('-').take(40).ifBlank {"my-app"}

    /** The text tokens and their values ([given] says which came from the owner, for the missing-facts report). */
    fun tokens(f:Facts,year:Int):Map<String,String> {
        // A fact the bot wasn't given shows as a [bracketed marker], which the page checker flags as unfinished until the bot fills it in or removes it.
        val name=f.name.trim().ifBlank {"[Business name]"}
        val email=f.email.trim()
        return mapOf("business" to name,"tagline" to f.tagline.trim().ifBlank {"[Your tagline here]"},
            "phone" to f.phone.trim().ifBlank {"Call us"},"phone_href" to phoneHref(f.phone),
            "email" to email.ifBlank {"Email us"},"email_href" to if(email.contains('@')) "mailto:$email" else "#contact",
            "address" to f.address.trim().ifBlank {"[Add your address]"},"city" to f.city.trim().ifBlank {"[City]"},"hours" to f.hours.trim().ifBlank {"[Add your hours]"},
            "year" to year.toString(),"initial" to (name.firstOrNull {it.isLetterOrDigit()}?.uppercase()?:"A"),"slug" to slug(name))
    }
    /** Which of the facts a bot should still ask the owner for, among those this page actually uses. */
    fun missing(f:Facts,used:Set<String>):List<String> = listOf("name" to "business","tagline" to "tagline","phone" to "phone","email" to "email","address" to "address","hours" to "hours")
        .filter {(field,token)->token in used && when(field) {"name"->f.name;"tagline"->f.tagline;"phone"->f.phone;"email"->f.email;"address"->f.address;else->f.hours}.isBlank()}.map {it.first}

    /** Facts from a JSON object or from lines like "phone: 713 555 0100". */
    fun parseFacts(raw:String):Facts {
        val values=HashMap<String,String>()
        val text=raw.trim()
        if(text.startsWith("{")) runCatching {val j=JSONObject(text);j.keys().forEach {k->values[k.lowercase().trim()]=j.opt(k)?.toString().orEmpty()}}
        else text.lines().forEach {line->
            val i=line.indexOf(':');if(i>0) values[line.substring(0,i).trim().lowercase().replace(' ','_')]=line.substring(i+1).trim()
        }
        fun get(vararg keys:String)=keys.firstNotNullOfOrNull {values[it]?.takeIf {v->v.isNotBlank() && v!="null"}}.orEmpty()
        return Facts(get("name","business","business_name","company"),get("tagline","slogan","headline"),get("phone","telephone","tel","phone_number"),get("email","e-mail"),
            get("address","street","location"),get("city","town"),get("hours","opening_hours","open","opening"))
    }

    // (Android's regex engine, unlike the desktop JVM's, rejects an unescaped closing brace, so every brace is escaped.)
    private val photoToken=Regex("\\{\\{photo:([a-z0-9-]+)\\}\\}")
    private val altToken=Regex("\\{\\{alt:([a-z0-9-]+)\\}\\}")
    private val textToken=Regex("\\{\\{([a-z_]+)\\}\\}")
    private val ldBlock=Regex("(?is)(<script[^>]*application/ld\\+json[^>]*>)(.*?)(</script>)")

    /** Every photo slot a set of files asks for, in order of first appearance. */
    fun slotsIn(texts:Collection<String>):List<String> = texts.flatMap {t->photoToken.findAll(t).map {it.groupValues[1]}.toList()}.distinct()
    /** Every text token a set of files uses. */
    fun tokensIn(texts:Collection<String>):Set<String> = texts.flatMap {t->textToken.findAll(t).map {it.groupValues[1]}.toList()}.toSet()

    private fun html(v:String)=v.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
    private fun json(v:String)=v.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","").replace("<","\\u003c")
    private fun css(v:String)=v.replace("\\","\\\\").replace("\"","\\\"").replace("\n"," ")

    /** [file] with every token replaced, each value escaped for the kind of file it lands in (page text, a JSON-LD block, a script, a stylesheet). */
    fun fill(file:String,source:String,values:Map<String,String>,photos:Map<String,Placed>):String {
        val ext=file.substringAfterLast('.').lowercase()
        val esc:(String)->String=when(ext) {"js","json","webmanifest"->::json;"css"->::css;else->::html}
        fun replace(text:String,escape:(String)->String):String {
            var out=photoToken.replace(text) {m->photos[m.groupValues[1]]?.file?:"art-"+m.groupValues[1]+".svg"}
            out=altToken.replace(out) {m->escape(photos[m.groupValues[1]]?.alt.orEmpty())}
            return textToken.replace(out) {m->values[m.groupValues[1]]?.let(escape)?:m.value}
        }
        if(ext!="html") return replace(source,esc)
        // JSON-LD blocks in a page are JSON, not HTML.
        val parts=ArrayList<String>();var last=0
        ldBlock.findAll(source).forEach {m->
            parts+=replace(source.substring(last,m.range.first),::html)
            parts+=m.groupValues[1]+replace(m.groupValues[2],::json)+m.groupValues[3]
            last=m.range.last+1
        }
        parts+=replace(source.substring(last),::html)
        return parts.joinToString("")
    }

    // ---- choosing photos ----

    /** Which of [pool]'s photos go in which slot: a photo of the right shape for each slot where there is one, never the same photo twice while others remain. */
    fun assign(slots:List<Slot>,pool:List<Pictures.Stock>):Map<String,Pictures.Stock> {
        val left=pool.toMutableList();val out=LinkedHashMap<String,Pictures.Stock>()
        slots.forEach {slot->
            val pick=left.firstOrNull {it.orientation==slot.orientation.lowercase() || slot.orientation.isBlank() || slot.orientation.lowercase()=="any"}?:left.firstOrNull()
            if(pick!=null) {out[slot.name]=pick;left.remove(pick)}
        }
        return out
    }

    /** Abstract artwork in the style's colours for a slot with no photo (offline, or nothing found): a soft gradient with a few overlapping shapes. */
    fun art(slot:String,colors:Map<String,String>):String {
        val seed=slot.fold(7) {a,c->a*31+c.code}.let {if(it<0) -it else it}
        fun n(i:Int,range:Int)=((seed/(i+1)+i*37)%range)
        val bg=colors["bg"]?:"#111111";val surface=colors["surface"]?:"#222222";val accent=colors["accent"]?:"#e0a030"
        val shapes=(0 until 4).joinToString("") {i->
            val cx=200+n(i+1,1200);val cy=150+n(i+2,700);val r=180+n(i+3,260)
            "<circle cx=\"$cx\" cy=\"$cy\" r=\"$r\" fill=\"${if(i%2==0) accent else surface}\" opacity=\"${if(i%2==0) ".22" else ".55"}\"/>"
        }
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1600 1000\" preserveAspectRatio=\"xMidYMid slice\"><defs>" +
            "<linearGradient id=\"g\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"1\"><stop offset=\"0\" stop-color=\"$surface\"/><stop offset=\"1\" stop-color=\"$bg\"/></linearGradient>" +
            "<filter id=\"b\" x=\"-20%\" y=\"-20%\" width=\"140%\" height=\"140%\"><feGaussianBlur stdDeviation=\"60\"/></filter></defs>" +
            "<rect width=\"1600\" height=\"1000\" fill=\"url(#g)\"/><g filter=\"url(#b)\">$shapes</g></svg>"
    }
}
