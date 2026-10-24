package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * What a bot looked at to get its answer: a web page it read, a source it cited, with a screenshot of the page as it was seen.
 * [shot] and [image] are file names in the app's own evidence folder (a picture of the page, and the page's main photo).
 */
data class Source(val url:String,val title:String,val domain:String,val shot:String="",val image:String="") {
    fun json()=JSONObject().put("url",url).put("title",title).put("domain",domain).put("shot",shot).put("image",image)
    companion object {
        fun parse(j:JSONObject)=Source(j.optString("url"),j.optString("title"),j.optString("domain"),j.optString("shot"),j.optString("image"))
    }
}

object Evidence {
    /** Most pages shown under one answer, and most that get a screenshot (each screenshot takes a few seconds on the phone). */
    const val MAX_SHOWN=6
    const val MAX_SHOTS=4

    fun parse(raw:String):List<Source> = runCatching {
        val a=JSONArray(raw);(0 until a.length()).mapNotNull {a.optJSONObject(it)?.let(Source::parse)}.filter {it.url.isNotBlank()}
    }.getOrDefault(emptyList())
    fun encode(list:List<Source>):String = JSONArray(list.map {it.json()}).toString()

    /** "ebay.com" from any address. */
    fun domain(url:String):String = runCatching {java.net.URI(url).host.orEmpty().removePrefix("www.").removePrefix("m.")}.getOrDefault("")

    /** Two addresses are the same page when they differ only in a fragment, a trailing slash or tracking parameters. */
    fun key(url:String):String {
        val clean=url.substringBefore('#')
        val (path,query)=clean.substringBefore('?').trimEnd('/') to clean.substringAfter('?',"")
        val keep=query.split('&').filter {it.isNotBlank() && !it.startsWith("utm_") && !it.startsWith("fbclid") && !it.startsWith("gclid") && !it.startsWith("ref=") && !it.startsWith("_trk")}
        return (path.removePrefix("http://").removePrefix("https://").removePrefix("www.")+(if(keep.isEmpty()) "" else "?"+keep.joinToString("&"))).lowercase()
    }

    /** A readable title for a page: the page's own title, or its address when it has none. */
    fun title(raw:String,url:String):String {
        val t=raw.replace(Regex("\\s+")," ").trim().take(120)
        return t.ifBlank {domain(url).ifBlank {url.take(60)}}
    }

    /** [list] with [source] added (or its missing parts filled in), keeping the order pages were seen. */
    fun add(list:List<Source>,source:Source):List<Source> {
        if(source.url.isBlank() || !(source.url.startsWith("https://") || source.url.startsWith("http://"))) return list
        val k=key(source.url)
        val at=list.indexOfFirst {key(it.url)==k}
        return if(at<0) list+source else list.mapIndexed {i,s->if(i!=at) s else s.copy(title=if(s.title.isBlank() || s.title==s.domain) source.title.ifBlank {s.title} else s.title,
            shot=s.shot.ifBlank {source.shot},image=s.image.ifBlank {source.image})}
    }

    /** Which pages deserve a screenshot: the first few, one per address, skipping files and obvious non-pages. */
    fun toShoot(list:List<Source>):List<Source> = list.filter {it.shot.isBlank() && !it.url.substringBefore('?').lowercase().let {u->
        listOf(".pdf",".jpg",".jpeg",".png",".gif",".webp",".mp4",".zip",".apk").any {e->u.endsWith(e)}}}.take(MAX_SHOTS)

    /** An address without tracking parameters and the page-position fragment (the form that is saved and opened). */
    fun clean(url:String):String {
        val base=url.trim().substringBefore('#')
        val query=base.substringAfter('?',"")
        if(query.isEmpty()) return base
        val keep=query.split('&').filter {it.isNotBlank() && !it.startsWith("utm_") && !it.startsWith("fbclid") && !it.startsWith("gclid")}
        return base.substringBefore('?')+(if(keep.isEmpty()) "" else "?"+keep.joinToString("&"))
    }

    /** Sources from the model's own link citations: ["title","url"] pairs found in an answer. */
    fun fromCitations(links:List<Pair<String,String>>):List<Source> = links.fold(emptyList<Source>()) {acc,(title,url)->add(acc,Source(clean(url),title(title,url),domain(url)))}

    /** The pages the model cited in one answer (web search results arrive as url_citation notes on its text). */
    fun fromParts(parts:List<JSONObject>):List<Source> = fromCitations(parts.flatMap {p->
        val notes=p.optJSONArray("annotations")?:return@flatMap emptyList<Pair<String,String>>()
        (0 until notes.length()).mapNotNull {notes.optJSONObject(it)}.filter {it.optString("type")=="url_citation"}.map {it.optString("title") to it.optString("url")}
    })

    /** Links the bot wrote in the answer itself: [name](https://…) */
    fun fromLinks(markdown:String):List<Source> = Regex("\\[([^\\]]{1,160})]\\((https?://[^)\\s]+)\\)").findAll(markdown).map {it.groupValues[1] to it.groupValues[2]}.toList().let {fromCitations(it)}

    /** Not worth a picture or a card: search result pages and sign-in pages. */
    private fun boring(url:String):Boolean {
        val u=url.lowercase();val host=domain(u)
        return (host.startsWith("google.") && u.contains("/search")) || host=="accounts.google.com" || host.endsWith("bing.com") && u.contains("/search") ||
            u.contains("/login") || u.contains("/signin") || u.contains("/sign-in") || u.contains("/oauth") || u.contains("/auth/")
    }

    /** The page a tool call looked at (web_fetch, browser_open, browser_read, or where a tap led), or null when it looked at nothing worth showing. */
    fun fromTool(tool:String,args:JSONObject,result:JSONObject):Source? {
        if(result.has("error") || result.optBoolean("partial") && !result.has("url")) return null
        val page=when(tool) {
            "web_fetch","browser_open","browser_read"->result
            "browser_click","browser_fill","browser_press_enter"->result.optJSONObject("page_now")
            else->null
        }?:return null
        val url=clean(page.optString("url").ifBlank {if(tool=="web_fetch" || tool=="browser_open") args.optString("url") else ""})
        if(url.isBlank() || !url.startsWith("https://") && !url.startsWith("http://") || boring(url)) return null
        return Source(url,title(page.optString("title"),url),domain(url))
    }

    /** Whether Alpha may draw this address for a picture: public https pages only (nothing on the phone, nothing on the owner's own network). */
    fun shootable(url:String):Boolean = runCatching {
        val uri=java.net.URI(url);val host=uri.host.orEmpty().lowercase()
        uri.scheme=="https" && host.isNotBlank() && host!="localhost" && !host.endsWith(".local") && !host.endsWith(".internal") && !Regex("[0-9.]+|\\[.*]").matches(host) &&
            // A PDF is a download, not a page: there is nothing for a hidden browser to draw.
            !uri.path.orEmpty().lowercase().endsWith(".pdf")
    }.getOrDefault(false)
    /** The saved picture's file name for a page, as seen in a given six-hour window: the same page in the same window shares one picture, a later look takes a fresh one. */
    fun shotName(url:String,now:Long=System.currentTimeMillis())="ev-"+Integer.toHexString(key(url).hashCode()).padStart(8,'0')+java.lang.Long.toHexString(now/21_600_000)+".jpg"

    /** Which pages to show under an answer: the ones the answer links to or cites first, then the rest in the order they were seen. */
    fun pick(list:List<Source>,cited:List<Source>,limit:Int=MAX_SHOWN):List<Source> {
        val citedKeys=cited.map {key(it.url)}
        val first=citedKeys.mapNotNull {k->list.firstOrNull {key(it.url)==k}}
        return (first+list.filter {key(it.url) !in citedKeys}).take(limit)
    }
}

/** What a bot is doing, as short plain lines for the live "what I'm doing" view and the "how I found this" list. */
object StepWords {
    private fun host(url:String)=Evidence.domain(url).ifBlank {url.take(40)}
    /** One line for a tool the bot ran, or "" when it isn't worth a line (bookkeeping tools). */
    fun line(tool:String,args:JSONObject):String = when(tool) {
        "web_fetch"->"Read "+host(args.optString("url"))
        "youtube_transcript"->"Read a video on YouTube"
        "browser_open"->"Opened "+host(args.optString("url"))
        "browser_read"->"Looked at the page"
        "browser_search"->"Searched for "+quote(args.optString("query"))
        "browser_click","browser_fill","browser_press_enter","browser_scroll","browser_back"->"Used the page"
        "screen_do","screen_confirm"->"Used "+Regex("\"app\":\"([^\"]{1,30})\"").find(args.optString("steps_json")+args.optString("step_json"))?.groupValues?.get(1).let {if(it.isNullOrBlank()) "an app on your phone" else "the "+it+" app"}
        "screen_read","screen_look"->"Looked at your phone screen"
        "apps_list"->""
        "image_search"->"Looked for photos of "+quote(args.optString("query"))
        "image_show"->"Saved a photo"
        "studio_photos"->"Found photos of "+quote(args.optString("query"))
        "studio_start"->"Chose a design and set up the page"
        "page_screenshot"->"Looked at the page on a phone and a computer"
        "file_edit","section_replace","file_write","file_append"->"Wrote "+args.optString("name").ifBlank {args.optString("section")}.ifBlank {"the file"}
        "picture_draw"->"Drew a picture"
        "chart_make"->"Made a chart"
        "document_pdf","invoice_pdf","docx_create","xlsx_create","pptx_create"->"Made the document"
        "browser_look"->"Looked at the page"
        "phone_calendar_events"->"Checked the phone calendar"
        "phone_contacts_find"->"Looked in the contacts"
        "phone_alarm_set"->"Set an alarm"
        "phone_timer_set"->"Set a timer"
        "deep_research","research_check"->""
        "media_job"->"Made the video"
        "audio_job"->"Made the sound"
        "run_code","tool_run"->"Worked out the numbers"
        "sms_send"->"Sent a text"
        "gmail_send","gmail_draft"->"Wrote an email"
        "memory_save"->"Remembered something"
        "ask_colleague"->"Asked "+args.optString("colleague").ifBlank {args.optString("bot")}.ifBlank {"another bot"}
        "ask_team"->"Asked "+(runCatching {org.json.JSONArray(args.optString("asks_json")).let {a->(0 until a.length()).mapNotNull {a.optJSONObject(it)?.optString("bot")?.takeIf {b->b.isNotBlank()}}}}.getOrDefault(emptyList()).distinct().joinToString(" and ").ifBlank {"the team"})
        "data_find"->"Looked up "+quote(args.optString("query"))+" in the records"
        "mcp_call","mcp_list"->"Checked a connected app"
        "task_plan","task_finish","task_receipts","capabilities","skills_list","skill_read","files_list","files_show","file_read","file_view","memory_search","history_search","react","gif_send","studio_icons","media_probe","plan_set","plan_update","tools_enable"->""
        else->tool.replace('_',' ').replaceFirstChar {it.uppercase()}
    }
    private fun quote(s:String)=if(s.isBlank()) "something" else "“"+s.replace(Regex("\\s+")," ").trim().take(60)+"”"

    /** The model's own web searches (they run on OpenAI's side) as lines: "Searched for “…”". */
    fun searches(output:JSONArray):List<String> = (0 until output.length()).mapNotNull {output.optJSONObject(it)}.filter {it.optString("type")=="web_search_call"}.mapNotNull {item->
        val action=item.optJSONObject("action")
        val query=action?.optString("query").orEmpty().ifBlank {action?.optJSONArray("queries")?.optString(0).orEmpty()}
        when(action?.optString("type")) {
            "open_page"->action.optString("url").takeIf {it.isNotBlank()}?.let {"Opened "+host(it)}
            else->query.takeIf {it.isNotBlank()}?.let {"Searched for "+quote(it)}
        }
    }

    /** "Searched 2 times · read 3 pages": a one-line summary of the steps, for under a finished answer. */
    fun summary(steps:List<String>):String {
        if(steps.isEmpty()) return ""
        val searches=steps.count {it.startsWith("Searched")};val pages=steps.count {it.startsWith("Read ") || it.startsWith("Opened ") || it.startsWith("Looked at the page")}
        val parts=mutableListOf<String>()
        if(searches>0) parts+="searched "+(if(searches==1) "once" else "$searches times")
        if(pages>0) parts+="looked at "+(if(pages==1) "1 page" else "$pages pages")
        val other=steps.size-searches-pages
        if(parts.isEmpty()) parts+=(if(steps.size==1) "1 step" else "${steps.size} steps") else if(other>0) parts+=(if(other==1) "1 more step" else "$other more steps")
        return parts.joinToString(" · ").replaceFirstChar {it.uppercase()}
    }
}
