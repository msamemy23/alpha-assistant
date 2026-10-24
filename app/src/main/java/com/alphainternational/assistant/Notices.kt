package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Noticing: quiet scans that return zero or more [Notice]s, and the rules that decide how many of them reach the owner. The default is silence:
 * a notice is pushed only when it is new, there is room in today's budget and it is not quiet hours. Everything else waits in the "Noticed" list.
 */

/** How proactive Alpha is. Times are minutes after midnight, local time. */
data class ProSettings(
    /** "Pause everything proactive": no checks, no triggers, no follow-ups, no brief. */
    val paused:Boolean=false,
    /** Look for things worth telling the owner (clashes, deadlines, goals that went quiet). */
    val noticing:Boolean=true,
    /** The most notices pushed to the phone in one day. */
    val budget:Int=3,
    val quietFrom:Int=21*60,
    val quietTo:Int=7*60,
    val briefOn:Boolean=false,
    val briefTime:String="07:30",
    /** The bot that writes the brief (blank: the chief of staff, else the first bot). */
    val briefBot:String="",
    /** One cheap model pass over the few emails that look like they hold a deadline, to cut false alarms. Off by default (it uses a little of the plan). */
    val quickPass:Boolean=false) {
    fun json()=obj("paused" to paused,"noticing" to noticing,"budget" to budget,"quiet_from" to quietFrom,"quiet_to" to quietTo,"brief_on" to briefOn,"brief_time" to briefTime,
        "brief_bot" to briefBot,"quick_pass" to quickPass)
    companion object {
        fun parse(j:JSONObject?):ProSettings {
            val d=ProSettings()
            if(j==null || j.length()==0) return d
            return ProSettings(j.optBoolean("paused"),j.optBoolean("noticing",d.noticing),j.optInt("budget",d.budget).coerceIn(1,10),
                j.optInt("quiet_from",d.quietFrom).coerceIn(0,1439),j.optInt("quiet_to",d.quietTo).coerceIn(0,1439),j.optBoolean("brief_on"),
                runCatching {Schedule.time(j.optString("brief_time",d.briefTime))}.getOrDefault(d.briefTime),j.optString("brief_bot"),j.optBoolean("quick_pass"))
        }
    }
}

object Quiet {
    fun isQuiet(minute:Int,from:Int,to:Int):Boolean = if(from==to) false else if(from<to) minute in from until to else (minute>=from || minute<to)
    fun isQuiet(ms:Long,s:ProSettings,zone:ZoneId=ZoneId.systemDefault()):Boolean = Instant.ofEpochMilli(ms).atZone(zone).let {isQuiet(it.hour*60+it.minute,s.quietFrom,s.quietTo)}
    /** The first moment at or after [ms] that is outside quiet hours (a check-back that falls at 2 am waits until the morning). */
    fun nextOpen(ms:Long,s:ProSettings,zone:ZoneId=ZoneId.systemDefault()):Long {
        if(!isQuiet(ms,s,zone)) return ms
        val at=Instant.ofEpochMilli(ms).atZone(zone)
        var open=at.toLocalDate().atTime(LocalTime.of(s.quietTo/60,s.quietTo%60)).atZone(zone)
        if(!open.isAfter(at)) open=open.plusDays(1)
        return open.toInstant().toEpochMilli()
    }
    fun label(minute:Int)=LocalTime.of(minute/60,minute%60).format(DateTimeFormatter.ofPattern("h:mm a",Locale.US))
}

/** One thing worth telling the owner: what was seen, where, the obvious next step (if any), and why it matters. */
data class Notice(val id:String,val key:String,val title:String,val saw:String,val source:String,val action:String="",val actionPrompt:String="",val why:String="",
    val bot:String="",val priority:Int=1,val created:Long=0,val expires:Long=0,val state:String="waiting",val pushedAt:Long=0) {
    val open get()=state=="waiting" || state=="pushed"
    fun json()=obj("id" to id,"key" to key,"title" to title,"saw" to saw,"source" to source,"action" to action,"action_prompt" to actionPrompt,"why" to why,"bot" to bot,
        "priority" to priority,"created" to created,"expires" to expires,"state" to state,"pushed_at" to pushedAt)
    companion object {
        fun parse(j:JSONObject)=Notice(j.optString("id"),j.optString("key"),j.optString("title"),j.optString("saw"),j.optString("source"),j.optString("action"),
            j.optString("action_prompt"),j.optString("why"),j.optString("bot"),j.optInt("priority",1),j.optLong("created"),j.optLong("expires"),j.optString("state","waiting"),j.optLong("pushed_at"))
        /** A notice a scan found. The key says what it is about, so the same thing is never offered twice. */
        fun found(key:String,title:String,saw:String,source:String,action:String,actionPrompt:String,why:String,priority:Int,now:Long,expires:Long=0,bot:String="")=
            Notice(digest(key).take(10),key,GoalRules.clean(title,100),GoalRules.clean(saw,300),GoalRules.clean(source,60),GoalRules.clean(action,40),actionPrompt.take(1500),GoalRules.clean(why,120),
                bot,priority,now,expires)
    }
}

/** What has been noticed: the list the owner sees, and the keys of everything already offered (so nothing comes back). */
data class NoticeBook(val items:List<Notice> = emptyList(),val seen:Map<String,Long> = emptyMap()) {
    fun json()=obj("items" to JSONArray(items.map {it.json()}),"seen" to JSONObject().also {o->seen.forEach {(k,v)->o.put(k,v)}})
    fun open()=items.filter {it.open}.sortedWith(compareByDescending<Notice> {it.priority}.thenBy {it.created})
    fun get(id:String)=items.firstOrNull {it.id==id}
    fun with(id:String,state:String)=copy(items=items.map {if(it.id==id) it.copy(state=state) else it})
    companion object {
        const val KEEP_DAYS=14
        const val SEEN_DAYS=60
        const val SEEN_CAP=600
        fun parse(j:JSONObject?):NoticeBook {
            if(j==null) return NoticeBook()
            val seen=j.optJSONObject("seen")?.let {s->s.keys().asSequence().associateWith {k->s.optLong(k)}}.orEmpty()
            return NoticeBook(j.optJSONArray("items")?.objects().orEmpty().map(Notice::parse).filter {it.id.isNotBlank()},seen)
        }
    }
}

object NoticeRules {
    /** Notices pushed to the phone today (local calendar day). */
    fun pushedToday(book:NoticeBook,now:Long,zone:ZoneId=ZoneId.systemDefault()):Int {
        val today=Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return book.items.count {it.pushedAt>0 && Instant.ofEpochMilli(it.pushedAt).atZone(zone).toLocalDate()==today}
    }
    fun budgetLeft(book:NoticeBook,s:ProSettings,now:Long,zone:ZoneId=ZoneId.systemDefault())=(s.budget-pushedToday(book,now,zone)).coerceAtLeast(0)

    data class Offer(val book:NoticeBook,val push:List<Notice>)

    /**
     * Adds what the scans found and decides what is pushed. A finding whose key was seen before is dropped, even if the owner never saw it pushed or
     * dismissed it. Pushes need: not paused, noticing on, not quiet hours, room in today's budget. The most important go first.
     */
    fun offer(book:NoticeBook,found:List<Notice>,s:ProSettings,now:Long,zone:ZoneId=ZoneId.systemDefault()):Offer {
        val keepAfter=now-NoticeBook.KEEP_DAYS*86_400_000L
        var items=book.items.filter {n->n.created>=keepAfter && !(n.state=="waiting" && n.expires in 1 until now)}
        var seen=book.seen.filterValues {now-it<NoticeBook.SEEN_DAYS*86_400_000L}
        if(s.paused || !s.noticing) return Offer(NoticeBook(items,seen),emptyList())
        found.forEach {n->
            if(seen.containsKey(n.key)) return@forEach
            if(n.expires in 1 until now) return@forEach
            items=items+n.copy(created=now,state="waiting");seen=seen+(n.key to now)
        }
        if(seen.size>NoticeBook.SEEN_CAP) seen=seen.entries.sortedByDescending {it.value}.take(NoticeBook.SEEN_CAP).associate {it.key to it.value}
        var next=NoticeBook(items,seen)
        if(Quiet.isQuiet(now,s,zone)) return Offer(next,emptyList())
        val room=budgetLeft(next,s,now,zone)
        val push=next.items.filter {it.state=="waiting" && !(it.expires in 1 until now)}.sortedWith(compareByDescending<Notice> {it.priority}.thenBy {it.created}).take(room)
        val ids=push.map {it.id}.toSet()
        next=next.copy(items=next.items.map {if(it.id in ids) it.copy(state="pushed",pushedAt=now) else it})
        return Offer(next,push.map {it.copy(state="pushed",pushedAt=now)})
    }
}

/** Notices from what Alpha is already tracking (no phone service needed). */
object NoticeScans {
    fun goals(book:GoalBook,now:Long):List<Notice> = book.overdue(now).map {g->
        Notice.found("goal:"+g.id+":"+g.nextCheck,"Still open: "+g.title,"I planned to look at this "+When.describe(g.nextCheck,now)+" and I haven't updated it since."+
            (if(g.lastAction.isNotBlank()) " Last thing I did: "+g.lastAction else ""),"Your goals list","Check on it now",
            "Check on this goal now: "+g.title+". Notes: "+g.notes+". Last action: "+g.lastAction+". Do what moves it forward without sending anything, then call goal_update with what you found and when to look again.",
            "Its next check passed with no update",3,now,0,g.bot)
    }
    fun followups(book:GoalBook,now:Long):List<Notice> = book.late(now).map {missed(it,now)}
    /** A check-back that could not run when it was due (the phone was off or busy) and is too late to just run quietly. */
    fun missed(f:Followup,now:Long):Notice {
        val hours=((now-f.due)/3_600_000L).coerceAtLeast(1)
        return Notice.found("followup:"+f.id,"I missed a check-back","I was going to check this "+When.describe(f.due,now)+" and my phone was off or busy: "+f.what,"Your follow-ups","Check it now",
            "This check-back was due "+When.describe(f.due,now)+" and could not run then. Do it now, and say plainly that it is late: "+f.what,"Late by about $hours h",3,now,0,f.bot)
    }
}

/** Calendar events as the scans need them. */
data class CalEvent(val id:String,val title:String,val start:Long,val end:Long,val calendar:String="",val place:String="")
data class Clash(val a:CalEvent,val b:CalEvent,val overlapMinutes:Int)

object Calendars {
    /** A timed event the owner is really expected at, or null (all-day, cancelled, declined, "free" time, working location, birthday). */
    fun parse(item:JSONObject,calendar:String):CalEvent? {
        if(item.optString("status")=="cancelled" || item.optString("transparency")=="transparent") return null
        if(item.optString("eventType") in setOf("workingLocation","birthday")) return null
        if(item.optJSONArray("attendees")?.objects().orEmpty().any {it.optBoolean("self") && it.optString("responseStatus")=="declined"}) return null
        val s=item.optJSONObject("start")?.optString("dateTime").orEmpty();val e=item.optJSONObject("end")?.optString("dateTime").orEmpty()
        if(s.isBlank() || e.isBlank()) return null
        val start=runCatching {OffsetDateTime.parse(s).toInstant().toEpochMilli()}.getOrNull()?:return null
        val end=runCatching {OffsetDateTime.parse(e).toInstant().toEpochMilli()}.getOrNull()?:return null
        if(end<=start) return null
        return CalEvent(item.optString("id"),GoalRules.clean(item.optString("summary").ifBlank {"(no title)"},80),start,end,calendar,GoalRules.clean(item.optString("location"),80))
    }
    /** Pairs of events that overlap by 5 minutes or more, among events that have not ended yet. The same event on two calendars is not a clash. */
    fun clashes(events:List<CalEvent>,now:Long):List<Clash> {
        val live=events.filter {it.end>now}.distinctBy {it.id}.sortedBy {it.start}
        val out=mutableListOf<Clash>()
        for(i in live.indices) for(j in i+1 until live.size) {
            val a=live[i];val b=live[j]
            if(b.start>=a.end) break
            val overlap=((minOf(a.end,b.end)-b.start)/60_000L).toInt()
            if(overlap<5) continue
            if(a.title.equals(b.title,true) && a.start==b.start && a.end==b.end) continue
            out+=Clash(a,b,overlap)
        }
        return out
    }
    fun clock(ms:Long,zone:ZoneId=ZoneId.systemDefault())=DateTimeFormatter.ofPattern("h:mm a",Locale.US).format(Instant.ofEpochMilli(ms).atZone(zone))
    fun notice(c:Clash,now:Long,zone:ZoneId=ZoneId.systemDefault(),bot:String=""):Notice {
        val soon=c.b.start-now<24*3_600_000L
        return Notice.found("clash:"+listOf(c.a.id,c.b.id).sorted().joinToString("+")+":"+c.a.start,"Two things overlap "+When.onDay(c.b.start,now,zone),
            "“"+c.a.title+"” ("+clock(c.a.start,zone)+" to "+clock(c.a.end,zone)+") and “"+c.b.title+"” ("+clock(c.b.start,zone)+" to "+clock(c.b.end,zone)+") overlap by "+c.overlapMinutes+" minutes.",
            "Google Calendar","Suggest a fix",
            "Two events on the owner's calendar overlap (they are named in the notice above, on "+When.onDay(c.a.start,now,zone)+"). Look at the calendar around them with calendar_events and suggest which one to move and to when. " +
                "If someone needs to be told, save a draft message (do not send it, and do not change the calendar).",
            "They run at the same time",if(soon) 5 else 4,now,minOf(c.a.end,c.b.end),bot)
    }
}

data class DeadlineHit(val at:Long,val phrase:String,val sentence:String)

/** Cheap word-and-date checks for "this email has a deadline soon". A model is never needed to find candidates. */
object Deadlines {
    private val keyword=Regex("(?i)\\b(due|deadline|expires?|expiring|expiration|no later than|final notice|last day|respond by|reply by|please reply by|past due|overdue|" +
        "must be (?:paid|received|submitted|signed|returned|completed|done)|by (?:end of day|eod|close of business|cob|noon|midnight))\\b")
    private val monthWords="(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"
    private val datePhrase=Regex("(?i)\\b(today|tonight|tomorrow|(?:this |next )?(?:monday|mon|tuesday|tues|tue|wednesday|wed|thursday|thurs|thur|thu|friday|fri|saturday|sat|sunday|sun)|" +
        "$monthWords\\.? \\d{1,2}(?:st|nd|rd|th)?|\\d{1,2}(?:st|nd|rd|th)? $monthWords|\\d{1,2}/\\d{1,2}(?:/\\d{2,4})?|\\d{4}-\\d{2}-\\d{2}|(?:in|within) \\d+ (?:hours?|days?)|" +
        "end of day|eod|close of business|cob|noon|midnight)\\b(?: (?:at )?\\d{1,2}(?::\\d{2})? ?(?:am|pm))?")
    private val promo=Regex("(?i)(% off|\\bsale\\b|discount|limited time|shop now|coupon|promo code|free shipping|exclusive offer|flash sale|last chance|don't miss|unsubscribe)")

    /** Marketing mail talks about "deadlines" too ("sale ends tonight"); two promotional cues is enough to skip it. */
    fun promotional(text:String)=promo.findAll(text).map {it.value.lowercase()}.distinct().count()>=2

    private fun when_(phrase:String,now:ZonedDateTime):Long? {
        val p=phrase.lowercase().replace(".","").trim()
        val today=now.toLocalDate()
        fun endOf(d:java.time.LocalDate)=d.atTime(23,59).atZone(now.zone).toInstant().toEpochMilli()
        if(p=="end of day" || p=="eod" || p=="close of business" || p=="cob") return today.atTime(17,0).atZone(now.zone).toInstant().toEpochMilli()
        if(p=="midnight") return endOf(today)
        if(p=="today" || p=="tonight") return endOf(today)
        val relative=Regex("^(?:in|within) (\\d+) (hour|day)s?$").find(p)
        if(relative!=null) return now.toInstant().toEpochMilli()+relative.groupValues[1].toLong()*(if(relative.groupValues[2]=="hour") 3_600_000L else 86_400_000L)
        val parsed=When.parse(p.replace(Regex("^within "),"in "),now)?:return null
        val explicit=Regex("\\d\\s?(am|pm)|\\d:\\d\\d|noon").containsMatchIn(p)
        return if(explicit) parsed else endOf(Instant.ofEpochMilli(parsed).atZone(now.zone).toLocalDate())
    }

    /** The earliest deadline in the next [windowHours] hours, with the sentence it came from; null if there is none. */
    fun find(subject:String,text:String,now:ZonedDateTime,windowHours:Int=48):DeadlineHit? {
        val nowMs=now.toInstant().toEpochMilli();val limit=nowMs+windowHours*3_600_000L
        val sentences=(listOf(subject)+text.take(6000).split(Regex("(?<=[.!?])\\s+|\\n+|;\\s+"))).map {it.trim()}.filter {it.length in 6..400}
        var best:DeadlineHit?=null
        for(s in sentences) {
            if(!keyword.containsMatchIn(s)) continue
            for(m in datePhrase.findAll(s)) {
                val at=when_(m.value,now)?:continue
                if(at<nowMs || at>limit) continue
                if(best==null || at<best.at) best=DeadlineHit(at,m.value.trim(),s)
            }
        }
        return best
    }

    fun notice(id:String,subject:String,from:String,hit:DeadlineHit,now:Long,zone:ZoneId=ZoneId.systemDefault(),bot:String=""):Notice {
        val soon=hit.at-now<24*3_600_000L
        val who=from.substringBefore('<').trim().trim('"').ifBlank {from}
        return Notice.found("deadline:"+id,"A deadline "+When.onDay(hit.at,now,zone),
            "“"+GoalRules.clean(subject,70)+"” from "+GoalRules.clean(who,40)+": "+GoalRules.clean(hit.sentence,160),"Gmail","Draft the reply",
            "An email in the owner's Gmail (message id "+id+") mentions a deadline soon. Read it with gmail_read, work out what is being asked, and save a reply as a Gmail draft (gmail_draft). Do not send anything.",
            "It mentions a deadline "+hit.phrase,if(soon) 5 else 4,now,hit.at+86_400_000L,bot)
    }
}

/** What automatic work did, newest last, for the plain "Recent" list. */
data class ProLogEntry(val at:Long,val kind:String,val text:String) {
    fun json()=obj("at" to at,"kind" to kind,"text" to text)
    companion object {
        fun parse(j:JSONObject)=ProLogEntry(j.optLong("at"),j.optString("kind"),j.optString("text"))
        fun add(all:List<ProLogEntry>,entry:ProLogEntry)=(all+entry.copy(text=GoalRules.clean(entry.text,260))).takeLast(60)
        fun read(j:JSONObject?)=j?.optJSONArray("log")?.objects().orEmpty().map(::parse)
    }
}
