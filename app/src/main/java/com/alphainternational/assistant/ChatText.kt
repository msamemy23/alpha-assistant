package com.alphainternational.assistant

import org.json.JSONObject

/**
 * What the model writes in Chat: headings (a "# " line is the headline of the answer), bullets, numbered lists, tables, quotes, code, pictures, inline
 * bold/italic/code/links, and two fenced extras: a "rows" block (option rows with a button) and a "followups" block (suggested next questions).
 */
object ChatMarkdown {
    sealed class Block {
        data class Heading(val level:Int,val text:String):Block()
        data class Bullet(val marker:String,val text:String,val indent:Int):Block()
        data class Code(val text:String):Block()
        data class Table(val rows:List<List<String>>):Block()
        data class Para(val text:String):Block()
        data class Quote(val text:String):Block()
        /** Options to compare or act on: each row has a title, a line of detail, a value (a price) and a link (open, call, text). */
        data class Rows(val rows:List<Row>):Block()
        /** A picture from this bot's files, written on its own line as ![what it shows](photo-ab12cd.jpg). */
        data class Image(val alt:String,val name:String):Block()
        object Rule:Block()
    }
    data class Row(val title:String,val detail:String,val value:String,val url:String) {
        /** What the button on the row says, from where its link goes. */
        val action get()=when {url.startsWith("tel:")->"Call";url.startsWith("sms:")->"Text";url.startsWith("mailto:")->"Email";url.isBlank()->"";else->"Open"}
    }
    data class Span(val text:String,val bold:Boolean=false,val italic:Boolean=false,val code:Boolean=false,val url:String="")

    private val linkSchemes=listOf("https://","http://","tel:","sms:","mailto:")
    /** Lines like "Brake pads | Ceramic, front | $48 | https://store.example/pads" (or with only some of the parts) become rows. */
    fun parseRows(lines:List<String>):List<Row> = lines.mapNotNull {raw->
        val line=raw.trim().replace(Regex("^[-*•]\\s+"),"").trim()
        if(line.isBlank()) return@mapNotNull null
        val parts=line.split('|').map {it.trim()}
        val url=parts.lastOrNull {p->linkSchemes.any {p.startsWith(it)}}.orEmpty()
        val rest=parts.filter {it!=url}
        Row(rest.getOrElse(0) {""}.removeSurrounding("**"),rest.getOrElse(1) {""},rest.getOrElse(2) {""},url).takeIf {it.title.isNotBlank()}
    }.take(8)

    /** The follow-up buttons a reply asks for, from a "followups" block (one per line). */
    fun followups(markdown:String):List<String> {
        val m=Regex("(?is)```\\s*follow-?ups?\\s*\\n(.*?)(?:```|\\z)").find(markdown)?:return emptyList()
        return m.groupValues[1].lines().map {it.trim().replace(Regex("^[-*•]\\s+"),"").trim().trim('"','“','”')}
            .filter {it.length in 2..70}.take(4)
    }
    /** The reply without its followups block (the buttons are shown instead of the raw lines). */
    fun withoutFollowups(markdown:String):String = withoutPhantomLinks(markdown.replace(Regex("(?is)\\n*```\\s*follow-?ups?\\s*\\n.*?(?:```|\\z)"),"")).trimEnd()

    /**
     * A link to a file on the AI's own computer ("sandbox:", "/mnt/data/…") goes nowhere on this phone: the files a bot made are shown under its answer by
     * themselves. The link is removed and its words kept, so no dead "open or download" line is left behind.
     */
    fun withoutPhantomLinks(markdown:String):String = markdown.replace(Regex("\\[([^\\]]*)]\\((?:sandbox:|file:|attachment:|computer:)?/?(?:mnt|home|tmp)/[^)]*\\)|\\[([^\\]]*)]\\((?:sandbox|file|attachment|computer):[^)]*\\)")) {m->
        (m.groupValues[1].ifBlank {m.groupValues[2]}).replace("**","").replace(Regex("(?i)^\\s*(open|download|open or download|click|tap)( here)?( to (open|download))?:?\\s*"),"").trim()
    }

    /** A real answer (set as an article with its sources) rather than a quick text back: a headline, a table, option rows, code, or a long reply. */
    fun isReport(markdown:String):Boolean {
        val text=withoutFollowups(markdown)
        if(text.length>480) return true
        return text.lines().any {l->l.trimStart().let {it.startsWith("# ") || it.startsWith("## ") || it.startsWith("|") || it.startsWith("```") || it.startsWith("> ")}}
    }

    /** The reply as plain text for sharing: no heading marks, fences or link syntax; option rows become lines; the follow-up buttons are left out. */
    fun readable(markdown:String):String = render(blocks(withoutFollowups(markdown)))

    /** A notification's headline and body from a reply: a leading "# " line is the headline (blank otherwise); the body is the rest in plain words. */
    fun notice(markdown:String):Pair<String,String> {
        val all=blocks(withoutFollowups(markdown))
        val head=(all.firstOrNull() as? Block.Heading)?.text?.let {plainSpans(it,false)}.orEmpty()
        return head to render(if(head.isBlank()) all else all.drop(1),false).replace(Regex("\\s*\n+\\s*")," ").trim()
    }

    /** The reply as words to say aloud on a call: no marks, no addresses, no code; option rows read as "title, detail, price". */
    fun spoken(markdown:String):String = render(blocks(withoutFollowups(markdown)).filter {it !is Block.Code},false).replace(Regex("\n{2,}"),". ").replace("\n",", ").trim()

    private fun render(blocks:List<Block>,links:Boolean=true):String {
        val out=mutableListOf<String>()
        for(block in blocks) when(block) {
            is Block.Heading->out+=plainSpans(block.text,links).let {h->if(block.level==1 && links) h.uppercase() else h}
            is Block.Para->out+=plainSpans(block.text,links)
            is Block.Bullet->out+=(if(block.marker=="•") "- " else block.marker+" ")+plainSpans(block.text,links)
            is Block.Quote->out+=plainSpans(block.text,links)
            is Block.Code->out+=block.text
            is Block.Table->out+=block.rows.joinToString("\n") {r->r.joinToString("  |  ") {plainSpans(it,links)}}
            is Block.Rows->out+=block.rows.joinToString("\n") {r->listOf(r.title,r.detail,r.value,r.url.takeIf {u->links && u.startsWith("http")}.orEmpty()).filter {it.isNotBlank()}.joinToString(" - ")}
            is Block.Image->out+=block.alt.ifBlank {"(picture)"}
            Block.Rule->out+="-----"
        }
        return out.joinToString("\n\n").trim()
    }
    private fun plainSpans(text:String,links:Boolean=true)=spans(text).joinToString("") {if(links && it.url.isNotBlank() && it.url.startsWith("http")) it.text+" ("+it.url+")" else it.text}

    fun blocks(markdown:String):List<Block> {
        val out=mutableListOf<Block>();val para=mutableListOf<String>();val table=mutableListOf<List<String>>();val quote=mutableListOf<String>()
        var code:MutableList<String>?=null;var lang=""
        fun flushPara(){if(para.isNotEmpty()){out+=Block.Para(para.joinToString("\n"));para.clear()}}
        fun flushTable(){if(table.isNotEmpty()){out+=Block.Table(table.toList());table.clear()}}
        fun flushQuote(){if(quote.isNotEmpty()){out+=Block.Quote(quote.joinToString("\n"));quote.clear()}}
        fun closeFence(lines:List<String>,complete:Boolean) {
            when(lang) {
                "rows","options"->if(complete) parseRows(lines).takeIf {it.isNotEmpty()}?.let {out+=Block.Rows(it)}
                "followups","followup","follow-ups","follow-up"->{}
                else->out+=Block.Code(lines.joinToString("\n"))
            }
        }
        for(raw in markdown.replace("\r\n","\n").split('\n')) {
            val line=raw.trimEnd();val text=line.trim()
            if(code!=null) {
                if(text.startsWith("```")){closeFence(code,true);code=null} else code.add(line)
                continue
            }
            if(text.startsWith("```")){flushPara();flushTable();flushQuote();lang=text.removePrefix("```").trim().lowercase();code=mutableListOf();continue}
            if(text.startsWith("|") && text.endsWith("|") && text.length>1) {
                flushPara();flushQuote()
                val cells=text.trim('|').split('|').map {it.trim()}
                if(!cells.all {it.matches(Regex(":?-{2,}:?"))})table+=cells
                continue
            }
            flushTable()
            if(text.startsWith(">")) {flushPara();quote+=text.removePrefix(">").trim();continue}
            flushQuote()
            val indent=(line.length-line.trimStart().length)/2
            val heading=Regex("^(#{1,4})\\s+(.+)$").find(text)
            val bullet=Regex("^[-*•]\\s+(.+)$").find(text)
            val number=Regex("^(\\d{1,3})[.)]\\s+(.+)$").find(text)
            when {
                text.isEmpty()->flushPara()
                Pictures.alone(text)!=null->{flushPara();Pictures.alone(text)!!.let {out+=Block.Image(it.first,it.second)}}
                text.matches(Regex("^(-{3,}|\\*{3,}|_{3,})$"))->{flushPara();out+=Block.Rule}
                heading!=null->{flushPara();out+=Block.Heading(heading.groupValues[1].length,heading.groupValues[2])}
                bullet!=null->{flushPara();out+=Block.Bullet("•",bullet.groupValues[1],indent)}
                number!=null->{flushPara();out+=Block.Bullet(number.groupValues[1]+".",number.groupValues[2],indent)}
                else->para+=text
            }
        }
        code?.let {closeFence(it,false)}
        flushPara();flushTable();flushQuote()
        return out
    }

    private val fileLike=Regex("^[\\w.-]+\\.(xlsx|xls|docx|doc|pptx|pdf|png|jpe?g|gif|webp|csv|txt|md|html|json|mp4|mp3|wav|m4a)$",RegexOption.IGNORE_CASE)
    /**
     * A file name a bot wrote in code marks ("`chart-s-p-500-oct-2024--6d34.png`") as something readable: no code box, no stray hash, hyphens as spaces. (The bots are told
     * never to write file names; this is for the ones that do, and it never touches real code.)
     */
    fun prettyFile(name:String):String {
        val dot=name.lastIndexOf('.')
        // (a short code on the end is dropped only when it has a letter in it: a year such as 2024 is a real part of the name)
        val base=name.substring(0,dot).replace(Regex("-{1,2}(?=[0-9a-f]{0,3}[a-f])[0-9a-f]{4}$"),"").replace('-',' ').replace('_',' ').trim()
        return base+name.substring(dot).lowercase()
    }

    private val inlinePattern=Regex("\\*\\*(.+?)\\*\\*|__(.+?)__|`([^`]+)`|\\[([^\\]]+)]\\(((?:https?|tel|sms|mailto):[^)\\s]+)\\)|(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?!\\w)")
    fun spans(text:String):List<Span> {
        val out=mutableListOf<Span>();var at=0
        for(m in inlinePattern.findAll(text)) {
            if(m.range.first>at)out+=Span(text.substring(at,m.range.first))
            val g=m.groupValues
            out+=when {
                g[1].isNotEmpty()->Span(g[1],bold=true)
                g[2].isNotEmpty()->Span(g[2],bold=true)
                g[3].isNotEmpty()->if(fileLike.matches(g[3])) Span(prettyFile(g[3]),bold=true) else Span(g[3],code=true)
                g[4].isNotEmpty()->Span(g[4],url=g[5])
                else->Span(g[6],italic=true)
            }
            at=m.range.last+1
        }
        if(at<text.length)out+=Span(text.substring(at))
        return out
    }
}

/** Plain-language approval cards instead of raw tool JSON. */
object Reviews {
    data class Summary(val title:String,val rows:List<Pair<String,String>>)
    private fun label(key:String)=key.replace('_',' ').replaceFirstChar {it.uppercase()}
    fun summary(tool:String,raw:String):Summary {
        val a=runCatching {JSONObject(raw)}.getOrDefault(JSONObject())
        fun s(key:String)=a.optString(key)
        fun rows(vararg pairs:Pair<String,String>)=pairs.filter {it.second.isNotBlank()}
        return when(tool) {
            "routine_create"-> {
                val schedule=runCatching {
                    Schedule.describe(Routine("preview",s("title"),s("prompt"),a.optInt("every_hours",24).coerceIn(1,168),"Alpha",
                        if(s("time").isBlank()) "" else Schedule.time(s("time")),if(s("time").isBlank()) "daily" else Schedule.days(s("days"))))
                }.getOrDefault(listOf(s("time"),s("days")).filter {it.isNotBlank()}.joinToString(" "))
                Summary("Schedule a routine",rows("Name" to s("title"),"When" to schedule,"Each time" to s("prompt"),
                    "Results" to if(a.optBoolean("report")) "Posted in Chat with a phone notification" else "Quiet unless something needs you"))
            }
            "routine_delete"->Summary("Stop a routine",rows("Routine" to s("id")))
            "skill_save"->Summary("Save a new skill",rows("Skill" to s("title"),"Used for" to s("purpose"),"Steps" to s("instructions")))
            "skill_delete"->Summary("Remove a skill",rows("Skill" to s("id")))
            "bot_update"->Summary("Change this bot's job and rules",rows("Job" to s("role"),"Standing rules" to s("rules")))
            "bot_create"->Summary("Create a new bot",rows("Name" to s("name"),"Its job" to s("role"),"Standing rules" to s("rules")))
            "tool_create"->Summary("Make a new tool",rows("Tool" to s("title"),"What it does" to s("description"),
                "Kind" to if(s("kind")=="web") "Looks things up on "+runCatching {java.net.URI(s("code").replace(Regex("\\{[a-z0-9_]+\\}"),"x")).host}.getOrNull().orEmpty()
                    else "Calculator (no internet, no accounts)","Inputs" to s("inputs_json"),if(s("kind")=="web") "Address" to s("code") else "Code" to s("code")))
            "gmail_send"->Summary("Send an email",rows("To" to s("to"),"Subject" to s("subject"),"Message" to s("body")))
            "sms_send"->Summary("Send a text",rows("To" to runCatching {PhoneSms.number(s("to"))}.getOrDefault(s("to")),"Message" to s("body")))
            "gmail_draft"->Summary("Save an email draft (not sent)",rows("To" to s("to"),"Subject" to s("subject"),"Message" to s("body")))
            "calendar_create"->Summary("Add a calendar event",rows("Title" to s("title"),"Starts" to s("start"),"Ends" to s("end"),"Notes" to s("description")))
            "tasks_create"->Summary("Add a Google task",rows("Task" to s("title"),"Notes" to s("notes")))
            "docs_create"->Summary("Create a Google Doc",rows("Title" to s("title"),"Text" to s("text")))
            "drive_create_text"->Summary("Upload a file to Google Drive",rows("Name" to s("name"),"Text" to s("text")))
            "sheets_create"->Summary("Create a Google Sheet",rows("Title" to s("title")))
            "sheets_append"->Summary("Add rows to a Google Sheet",rows("Sheet" to s("id"),"Range" to s("range"),"Rows" to s("rows_json")))
            "browser_click"->Summary("Click on the website",rows("Element" to s("id")))
            "browser_fill"->Summary("Type into a website field",rows("Field" to s("id"),"Text" to s("value")))
            "browser_press_enter"->Summary("Press Enter on the website",rows("Field" to s("id")))
            "vercel_preview"->Summary("Start a Vercel preview",rows("Project" to s("project"),"Branch" to s("branch")))
            "site_publish"->Summary("Put a website online",rows("Website name" to s("project"),"Home page" to s("page"),"Other files" to s("files"),
                "Address" to (runCatching {Sites.slug(s("project"))}.getOrDefault(s("project"))+".vercel.app"),"Who can see it" to "Anyone on the internet","Good to know" to "Vercel's free plan is for personal, non-commercial use. A business website needs a paid Vercel plan."))
            "supabase_sample_invoice"->Summary("Save a sample invoice in Supabase",rows("Customer" to s("customer_name"),"Items" to s("items_json")))
            "screen_confirm"-> {
                val step=runCatching {JSONObject(s("step_json"))}.getOrDefault(JSONObject())
                Summary("Do this on your phone",rows("App" to s("app"),"Tap" to step.optString("on").ifBlank {step.optString("id")}.ifBlank {step.optString("do")},"Why" to s("why")))
            }
            else->Summary(label(tool),a.keys().asSequence().map {label(it) to a.opt(it)?.toString().orEmpty()}.filter {it.second.isNotBlank()}.toList())
        }
    }
}
