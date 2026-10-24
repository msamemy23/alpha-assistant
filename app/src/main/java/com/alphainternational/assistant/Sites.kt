package com.alphainternational.assistant

import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLEncoder

/**
 * Websites bots build and publish. The shop's one-page site comes from a tested template (assets/site-shop.html) filled in on this phone,
 * so a bot never has to write 10,000 characters of page code, and everything it says on the page comes from facts the bot was given.
 * Publishing puts the files on Vercel at a free .vercel.app address; it always waits for the owner's OK.
 */
object Sites {
    const val SHOP_FILE="shop-website.html"
    /** Where the shop page's quote form saves requests: a table that only accepts new rows, apart from the shop's business tables. */
    const val QUOTE_TABLE="website_quotes"

    data class Shop(val name:String,val phone:String,val address:String,val hours:String,val services:String,val tagline:String="",val blurb:String="",
                    val quoteUrl:String="",val quoteKey:String="")

    private fun esc(text:String)=text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;")
    /** A JavaScript string literal that can't close the page's script tag. */
    private fun js(text:String)=JSONObject.quote(text).replace("</","<\\/")
    private fun digits(phone:String)=phone.filter {it.isDigit()}
    /** +17135550100 for tel: and sms: links. */
    fun telephone(phone:String):String {
        val d=digits(phone)
        return when {d.length==10->"+1$d";d.length==11 && d.startsWith("1")->"+$d";else->throw IllegalArgumentException("Give the shop's phone number with its area code, like (555) 123-4567.")}
    }
    fun phoneDisplay(phone:String):String {val d=digits(phone).takeLast(10);return "("+d.take(3)+") "+d.substring(3,6)+"-"+d.substring(6)}

    /** "Mon–Fri: 8 AM–6 PM; Sat: 9 AM–3 PM; Sun: Closed" → one table row per day range. */
    fun hoursRows(text:String):List<Pair<String,String>> = text.split(';','\n').map {it.trim()}.filter {it.isNotBlank()}.take(10).map {line->
        val cut=line.indexOf(": ").takeIf {it>0}?:line.indexOf(':').takeIf {it>0 && !line[it-1].isDigit() && !(it+1<line.length && line[it+1].isDigit())}
        if(cut==null) line to "" else line.substring(0,cut).trim() to line.substring(cut+1).trim()
    }

    private val icons=listOf("brake" to "🛑","oil" to "🛢️","tire" to "🛞","wheel" to "🛞","align" to "🛞","engine" to "🔧","a/c" to "❄️","air" to "❄️","battery" to "🔋",
        "transmission" to "⚙️","inspection" to "✅","diagnos" to "🔍","check engine" to "🔍","electric" to "⚡","suspension" to "🚗","exhaust" to "💨","muffler" to "💨",
        "body" to "🚘","paint" to "🎨","glass" to "🪟","tune" to "🔧","cooling" to "🌡️","radiator" to "🌡️","used" to "🚙","sales" to "🚙")
    private fun icon(service:String)=icons.firstOrNull {service.lowercase().contains(it.first)}?.second?:"🔧"
    fun serviceList(text:String):List<String> = text.split(',',';','\n').map {it.trim().trim('-','•').trim()}.filter {it.isNotBlank()}.distinct().take(12)

    private fun cityOf(address:String):String {
        val parts=address.split(',').map {it.trim()}
        return if(parts.size>=2) parts[1] else ""
    }
    private fun jsonLd(shop:Shop,tel:String):String {
        val parts=shop.address.split(',').map {it.trim()}
        val state=parts.getOrNull(2)?.split(Regex("\\s+"))
        val node=JSONObject().put("@context","https://schema.org").put("@type","AutoRepair").put("name",shop.name).put("telephone",tel)
        if(parts.size>=3 && state!=null && state.size>=2)
            node.put("address",JSONObject().put("@type","PostalAddress").put("streetAddress",parts[0]).put("addressLocality",parts[1])
                .put("addressRegion",state[0]).put("postalCode",state[1]).put("addressCountry","US"))
        return node.toString().replace("</","<\\/")
    }

    /** The shop's one-page website from the template. Every blank that the page needs must be filled; nothing is invented. */
    fun shop(template:String,shop:Shop,year:Int=java.time.Year.now().value):String {
        require(shop.name.isNotBlank() && shop.name.length<=80) {"Give the shop's name."}
        require(shop.address.isNotBlank() && shop.address.length<=160) {"Give the shop's street address."}
        val hours=hoursRows(shop.hours)
        require(hours.isNotEmpty()) {"Use the shop's real opening hours, like \"Mon–Fri: 8 AM–6 PM; Sat: 9 AM–3 PM; Sun: Closed\". Look at its Google listing or ask the owner. Never guess hours."}
        val services=serviceList(shop.services)
        require(services.size>=2) {"List the services the shop really offers (at least two), separated by commas."}
        val tel=telephone(shop.phone);val display=phoneDisplay(shop.phone)
        require((shop.quoteUrl.isBlank())==(shop.quoteKey.isBlank())) {"The quote form needs both the project address and the public key, or neither."}
        if(shop.quoteUrl.isNotBlank()) {
            val uri=URI(Rules.httpsUrl(shop.quoteUrl))
            require(uri.host.endsWith(".supabase.co")) {"The quote form saves to a Supabase project address like https://xxxx.supabase.co."}
            publicKey(shop.quoteKey)
        }
        val city=cityOf(shop.address)
        val tagline=shop.tagline.ifBlank {"Auto repair and service in "+city.ifBlank {"your area"}}
        val blurb=shop.blurb.ifBlank {"Call or stop by, or send a quote request below and we'll get back to you."}
        val description="${shop.name}: "+services.take(4).joinToString(", ")+(if(city.isBlank()) "" else " in $city")+". Call $display."
        val cards=services.joinToString("") {"<div class=\"card\"><div class=\"ic\">"+icon(it)+"</div><h3>"+esc(it)+"</h3></div>"}
        val rows=hours.joinToString("") {"<tr><td>"+esc(it.first)+"</td><td>"+esc(it.second)+"</td></tr>"}
        val query=URLEncoder.encode(shop.name+" "+shop.address,"UTF-8").replace("+","%20")
        return template
            .replace("{{JSONLD}}",jsonLd(shop,tel))
            .replace("{{NAME}}",esc(shop.name)).replace("{{CITY}}",esc(city.ifBlank {"Auto repair"}))
            .replace("{{DESCRIPTION}}",esc(description)).replace("{{TAGLINE}}",esc(tagline)).replace("{{BLURB}}",esc(blurb))
            .replace("{{SERVICES}}",cards).replace("{{HOURS}}",rows)
            .replace("{{ADDRESS_QUERY}}",query).replace("{{ADDRESS}}",esc(shop.address))
            .replace("{{PHONE_TEL_JS}}",js(tel)).replace("{{PHONE_TEL}}",tel).replace("{{PHONE_DISPLAY}}",esc(display))
            .replace("{{QUOTE_URL_JS}}",js(shop.quoteUrl.trim().trimEnd('/'))).replace("{{QUOTE_KEY_JS}}",js(shop.quoteKey.trim()))
            .replace("{{YEAR}}",year.toString())
    }

    /** A publishable (public) Supabase key: new sb_publishable_ style, or a legacy anon JWT. Never a secret or service-role key. */
    fun publicKey(key:String):String {
        val value=key.trim()
        if(value.startsWith("sb_publishable_")) {require(value.length in 20..120 && value.none {it.isWhitespace()}) {"That public key looks incomplete."};return value}
        require(value.startsWith("eyJ") && value.count {it=='.'}==2) {"Use the project's publishable key (starts with sb_publishable_) or its anon key. Never a secret or service-role key."}
        val claims=runCatching {JSONObject(String(java.util.Base64.getUrlDecoder().decode(value.split('.')[1])))}.getOrNull()
        require(claims?.optString("role")=="anon") {"Never put a service-role key on a website. Use the publishable or anon key."}
        return value
    }

    /** The SQL that makes the quote table: customers can add a request, and only the shop's own sign-in can read, change or delete them. */
    val quoteTableSql="""
        create table if not exists public.$QUOTE_TABLE (
          id uuid primary key default gen_random_uuid(),
          created_at timestamptz not null default now(),
          name text not null check (char_length(name) between 1 and 100),
          phone text not null check (char_length(phone) between 7 and 30),
          vehicle text check (char_length(vehicle) <= 120),
          message text check (char_length(message) <= 1500),
          source text not null default 'website',
          handled boolean not null default false
        );
        alter table public.$QUOTE_TABLE enable row level security;
        revoke all on public.$QUOTE_TABLE from anon;
        grant insert on public.$QUOTE_TABLE to anon;
        drop policy if exists "Anyone can send a quote request" on public.$QUOTE_TABLE;
        create policy "Anyone can send a quote request" on public.$QUOTE_TABLE for insert to anon with check (true);
    """.trimIndent()

    /** A Vercel project name for [name]: lowercase letters, digits and hyphens, like acme-auto. */
    fun slug(name:String):String {
        val value=name.lowercase().replace(Regex("[^a-z0-9]+"),"-").trim('-').take(52).trim('-')
        require(value.length>=3) {"Pick a website name of at least 3 letters, like alpha-auto."}
        return value
    }

    /** Why a page can't go online (a secret in it), or null. Public pages must never carry keys or tokens. */
    fun problem(html:String):String? {
        if(Regex("service_role",RegexOption.IGNORE_CASE).containsMatchIn(html)) return "The page mentions a service-role key. Remove it; a public page must never hold one."
        if(Regex("(?:sk-|sk_|sb_secret_|sbp_|vcp_)[A-Za-z0-9_-]{12,}").containsMatchIn(html)) return "The page contains what looks like a secret key. Remove it before publishing."
        if(Regex("Bearer\\s+[A-Za-z0-9._~+/=-]{20,}").containsMatchIn(html)) return "The page contains a sign-in token. Remove it before publishing."
        return null
    }

    const val MAX_FILES=60
    const val MAX_BYTES=12_000_000
    private val kinds=setOf("html","png","jpg","jpeg","webp","gif","css","js","svg","xml","webmanifest","woff2","mp3","wav","ogg","m4a","mp4","webm","txt","json","md","csv")

    private val fileName=Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,98}\\.(?:html|png|jpe?g|webp|gif|css|json|js|svg|xml|webmanifest|woff2|mp3|wav|ogg|m4a|mp4|webm|txt|md|csv)(?![A-Za-z0-9])",RegexOption.IGNORE_CASE)
    private val readable=setOf("html","css","js","svg","json","webmanifest","xml")

    /**
     * Every file a page needs, found by reading it (and its styles, scripts and other pages) for names of files in the same folder: pictures, fonts,
     * stylesheets, scripts. A bot only has to name the page; the pictures and styles can no longer be forgotten, which left published sites without them.
     */
    fun needed(folder:File,page:String):List<String> {
        val seen=LinkedHashSet<String>();val queue=ArrayDeque<String>();queue.add(page)
        while(queue.isNotEmpty() && seen.size<MAX_FILES*2) {
            val name=queue.removeFirst()
            if(!File(folder,name).isFile || !seen.add(name)) continue
            if(name.substringAfterLast('.').lowercase() !in readable) continue
            val file=File(folder,name);if(file.length()>1_500_000) continue
            fileName.findAll(file.readText()).forEach {m->if(m.value !in seen && m.value!=name && File(folder,m.value).isFile) queue.add(m.value)}
        }
        return seen.toList()
    }

    /** The files to publish, by the name the web address will use: [page] becomes index.html, the others keep their names. */
    fun bundle(folder:File,page:String,extras:List<String>):Map<String,ByteArray> {
        val main=Rules.textFileName(page)
        require(main.endsWith(".html",true)) {"The page to publish must be an .html file."}
        val names=(listOf(main)+needed(folder,main)+extras.map {it.trim()}.filter {it.isNotBlank()}).distinct()
        require(names.size<=MAX_FILES) {"Publish at most $MAX_FILES files (this site uses ${names.size}). Use fewer or combined pictures."}
        val files=LinkedHashMap<String,ByteArray>()
        for(name in names) {
            Rules.fileName(name)
            require(name.substringAfterLast('.').lowercase() in kinds) {"$name can't be published."}
            val file=File(folder,name);require(file.isFile) {"$name isn't in this bot's files."}
            val bytes=file.readBytes()
            if(name.endsWith(".html",true) || name.endsWith(".js",true)) problem(String(bytes))?.let {error("$name: $it")}
            files[if(name==main) "index.html" else name]=bytes
        }
        require(files.values.sumOf {it.size}<=MAX_BYTES) {"The site is too big to publish (limit 12 MB). Use smaller pictures."}
        return files
    }

    /** The best public address among a deployment's aliases: the project's own name.vercel.app first. */
    fun publicAddress(project:String,aliases:List<String>):String {
        val clean=aliases.map {it.trim().removePrefix("https://").trimEnd('/')}.filter {it.endsWith(".vercel.app")}
        val pick=clean.firstOrNull {it=="$project.vercel.app"}?:clean.filterNot {it.contains("-git-")}.minByOrNull {it.length}?:clean.firstOrNull()?:"$project.vercel.app"
        return "https://$pick"
    }
}
