package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Saved routes: how a job on one of the owner's apps was done the last time it worked, kept per app, so the next job like it starts from the proven steps in its very first call
 * instead of reading and feeling its way again (the phone-agent research calls these shortcuts: a short sequence of steps reused instead of being worked out again).
 *
 * A route is learned from a job that finished well, from its own record and with no extra model call: the steps that worked, from each `open` onward. Text the bot typed or shared
 * is never kept (it is replaced by {text}), steps that send, pay, delete or confirm are never kept, nor are messaging and mail apps, nor steps that point at a screen number (they
 * change every time). When a later job finds a shorter way for the same kind of job, the shorter route replaces the old one, so routes get cleaner with use. A route that was shown
 * to a job that then went nowhere is marked, and dropped after it keeps missing.
 */
object PhoneRoutes {
    class Route(val id:String,val app:String,val goal:String,val steps:List<JSONObject>,val ok:Int=1,val miss:Int=0,val used:Long=0L) {
        fun json():JSONObject=JSONObject().put("id",id).put("app",app).put("goal",goal).put("steps",JSONArray(steps)).put("ok",ok).put("miss",miss).put("used",used)
        fun stepsText():String=JSONArray(steps).toString()
        val words:Set<String> by lazy {keywords(goal)}
    }

    const val MAX_PER_APP=6
    const val MAX_TOTAL=60
    private const val MAX_STEPS=30
    private const val MAX_CHARS=900
    private val skipApps=Regex("\\b(messages?|messaging|sms|mail|gmail|outlook|email|whatsapp|messenger|telegram|signal|instagram|facebook|snapchat|tiktok|contacts|phone|dialer|wallet|pay|bank)\\b",RegexOption.IGNORE_CASE)
    private val risky=Regex("\\b(send|post|publish|pay|buy|purchase|order|delete|erase|remove|reset|submit|confirm|checkout|check out|transfer|uninstall|sign out|log out)\\b",RegexOption.IGNORE_CASE)
    private val stop=setOf("the","and","for","with","then","open","app","tell","give","both","about","this","that","you","your","from","into","please","them","find","show","what","when","which","have","will","would","could","make","take","get","use","its","are","was","not","all","any","can","one","now","how")

    /** The words of a request that say what kind of job it is (short filler words and "open" left out). */
    fun keywords(text:String):Set<String> = text.lowercase().split(Regex("[^a-z0-9]+")).filter {it.length>=3 && it !in stop}.toSet()

    /** A route is never kept for an app the owner's private conversations or money live in. */
    fun appAllowed(app:String)=app.isNotBlank() && !skipApps.containsMatchIn(app)

    /** The routes in a finished job's calls: for each app the job opened, the steps that worked, in order. */
    fun fromCalls(calls:List<Verify.Call>,goal:String,now:Long):List<Route> {
        val out=ArrayList<Route>()
        var app="";var steps=ArrayList<JSONObject>();var unusable=false
        fun finish() {
            if(app.isNotBlank() && steps.isNotEmpty() && !unusable) tidy(steps)?.let {tidied->
                val text=JSONArray(tidied).toString()
                if(appAllowed(app) && tidied.size<=MAX_STEPS && text.length<=MAX_CHARS) out+=Route(idOf(app,text),app,goal.replace(Regex("\\s+")," ").trim().take(90),tidied,1,0,now)
            }
            app="";steps=ArrayList();unusable=false
        }
        for(c in calls) {
            if(c.name!="screen_do") continue
            val parsed=runCatching {PhoneSteps.parse(c.args.optString("steps_json"))}.getOrNull()?:continue
            val results=c.result?.optJSONArray("steps")
            parsed.forEachIndexed {i,st->
                if(results?.optJSONObject(i)?.optBoolean("ok")!=true) return@forEachIndexed
                when(st.op) {
                    "open"->{finish();app=st.app}
                    "read"->{}
                    else->if(app.isNotBlank()) {
                        // A step that points at a screen number cannot be replayed: the numbers are new on every screen.
                        if(st.target.id.isNotBlank() && st.target.words.isBlank()) unusable=true
                        steps+=general(st)
                    }
                }
            }
        }
        finish()
        return out
    }

    /** A step as it is kept: what it does and where, never what was typed. */
    private fun general(st:Step):JSONObject {
        val j=st.json()
        if(st.op=="type" || st.op=="share") j.put("text","{text}")
        // The parser reads a share step's text as its target words as well; it must not leak out through there.
        if(st.op=="share") {j.remove("on");j.remove("id")}
        if(st.target.nth>0) j.put("nth",st.target.nth)
        if(st.target.role.isNotBlank()) j.put("role",st.target.role)
        return j
    }

    /** Leaves out what only got a job out of a muddle, and the whole route when a step could send, pay or delete. Null when nothing useful is left. */
    private fun tidy(raw:List<JSONObject>):List<JSONObject>? {
        val steps=ArrayList(raw)
        while(steps.isNotEmpty() && steps.first().optString("do") in setOf("home","back")) steps.removeAt(0)
        while(steps.isNotEmpty() && steps.last().optString("do") in setOf("home","back","scroll")) steps.removeAt(steps.lastIndex)
        val clean=ArrayList<JSONObject>()
        for(s in steps) {
            val op=s.optString("do")
            if(op=="back" && clean.lastOrNull()?.optString("do")=="back") continue
            if(op=="lock") return null
            if(op in setOf("tap","long") && risky.containsMatchIn(s.optString("on"))) return null
            clean+=s
        }
        // A run of one-character taps is data being entered (digits on a calculator, letters on a keypad): it differs every time and a bot copying it only makes mistakes.
        if(clean.count {it.optString("do")=="tap" && it.optString("on").length==1}>=4) return null
        return clean.takeIf {it.isNotEmpty()}
    }

    private fun idOf(app:String,stepsText:String):String =
        MessageDigest.getInstance("SHA-1").digest((app.lowercase()+"|"+stepsText).toByteArray()).take(5).joinToString("") {"%02x".format(it)}

    /** How alike two requests are, from 0 to 1 (the words of the smaller one found in the other). */
    fun alike(a:Set<String>,b:Set<String>):Double {
        if(a.isEmpty() || b.isEmpty()) return 0.0
        return a.intersect(b).size.toDouble()/minOf(a.size,b.size)
    }

    /** Adds what a job learned to what is kept: the same kind of job keeps its shortest route; the lists are trimmed per app and in all. */
    fun merge(kept:List<Route>,learned:List<Route>):List<Route> {
        val all=ArrayList(kept)
        for(n in learned) {
            val twin=all.indexOfFirst {it.app.equals(n.app,true) && (it.id==n.id || alike(it.words,n.words)>=0.6)}
            if(twin<0) {all+=n;continue}
            val old=all[twin]
            all[twin]=if(n.steps.size<=old.steps.size) Route(n.id,n.app,n.goal,n.steps,old.ok+1,0,n.used) else Route(old.id,old.app,old.goal,old.steps,old.ok+1,old.miss,n.used)
        }
        val perApp=all.groupBy {it.app.lowercase()}.values.flatMap {list->list.sortedWith(compareByDescending<Route> {it.ok-it.miss}.thenByDescending {it.used}).take(MAX_PER_APP)}
        return perApp.sortedByDescending {it.used}.take(MAX_TOTAL)
    }

    /** What happened to jobs that were shown these routes: a good job confirms them; one that went nowhere marks them, and a route that keeps missing is dropped. */
    fun noteResult(kept:List<Route>,ids:Collection<String>,good:Boolean):List<Route> =
        kept.mapNotNull {r->
            if(r.id !in ids) r
            else if(good) Route(r.id,r.app,r.goal,r.steps,r.ok+1,r.miss,r.used)
            else Route(r.id,r.app,r.goal,r.steps,r.ok,r.miss+1,r.used).takeUnless {it.miss>=2 && it.miss>it.ok}
        }

    /** The routes worth showing a job, best first: those for an app the request names, then those for a similar kind of job. */
    fun relevant(kept:List<Route>,request:String,max:Int=2):List<Route> {
        val ask=keywords(request);val lower=request.lowercase()
        return kept.map {r->
            val named=if(lower.contains(r.app.lowercase())) 0.6 else 0.0
            r to (named+alike(ask,r.words)*0.8+0.02*(r.ok-r.miss).coerceIn(-5,5))
        }.filter {it.second>=0.55}.sortedByDescending {it.second}.take(max).map {it.first}
    }

    /** What the bot is told, once at the start of a job: the routes, as steps it can send as they are. */
    fun note(routes:List<Route>):String {
        if(routes.isEmpty()) return ""
        return "Routes that worked on this phone before (done, and the result was checked). Start your first screen_do from the closest one and change only what differs for this job; " +
            "{text} marks text you must write yourself; the words on screen can differ a little, so if a step fails carry on as usual and ignore the route:\n"+
            routes.joinToString("\n") {"- "+it.app+", for \""+it.goal.take(70)+"\": "+it.stepsText()}
    }

    fun parse(text:String):List<Route> = runCatching {
        val list=JSONArray(text)
        (0 until list.length()).mapNotNull {i->
            val j=list.optJSONObject(i)?:return@mapNotNull null
            val steps=j.optJSONArray("steps")?.let {a->(0 until a.length()).mapNotNull {a.optJSONObject(it)}}.orEmpty()
            if(steps.isEmpty() || j.optString("app").isBlank()) null else Route(j.optString("id"),j.optString("app"),j.optString("goal"),steps,j.optInt("ok",1),j.optInt("miss",0),j.optLong("used"))
        }
    }.getOrDefault(emptyList())

    fun write(routes:List<Route>):String = JSONArray(routes.map {it.json()}).toString()

    /** The saved routes in a file on the phone. */
    class Store(private val file:File) {
        private var kept:List<Route> = runCatching {if(file.exists()) parse(file.readText()) else emptyList()}.getOrDefault(emptyList())
        @Synchronized fun all():List<Route> = kept
        @Synchronized fun count():Int = kept.size
        @Synchronized fun add(learned:List<Route>) {if(learned.isNotEmpty()) {kept=merge(kept,learned);save()}}
        @Synchronized fun result(ids:Collection<String>,good:Boolean) {if(ids.isNotEmpty()) {kept=noteResult(kept,ids,good);save()}}
        @Synchronized fun relevant(request:String,max:Int=2):List<Route> = PhoneRoutes.relevant(kept,request,max)
        @Synchronized fun clear() {kept=emptyList();runCatching {file.delete()}}
        private fun save() {runCatching {file.writeText(write(kept))}}
    }
}
