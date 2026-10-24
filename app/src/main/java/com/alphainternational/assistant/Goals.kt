package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Goals and follow-ups: the things Alpha keeps track of for the owner across days, and the one-shot "check back" it promised.
 * Everything here is plain logic (no phone, no AI) so every rule can be tested.
 */

/** An outcome a bot is staying on top of for the owner. [status]: active, waiting (for someone else), paused (the owner's pause) or done. */
data class Goal(val id:String,val title:String,val bot:String,val status:String="active",val lastAction:String="",val lastActionAt:Long=0,
    val nextCheck:Long=0,val notes:String="",val created:Long=0,val updated:Long=0) {
    val open get()=status!="done"
    fun json()=obj("id" to id,"title" to title,"bot" to bot,"status" to status,"last_action" to lastAction,"last_action_at" to lastActionAt,"next_check" to nextCheck,
        "notes" to notes,"created" to created,"updated" to updated)
    companion object {
        fun parse(j:JSONObject)=Goal(j.optString("id"),j.optString("title"),j.optString("bot"),GoalRules.status(j.optString("status"))?:"active",j.optString("last_action"),
            j.optLong("last_action_at"),j.optLong("next_check"),j.optString("notes"),j.optLong("created"),j.optLong("updated"))
    }
}

/**
 * One check-back a bot promised. [status]: pending, fired (the job was started), skipped (its goal was already done), missed (too late to be useful),
 * cancelled. [depth] counts follow-ups that came out of a follow-up, so a bot can't keep chasing forever.
 */
data class Followup(val id:String,val bot:String,val what:String,val due:Long,val goalId:String="",val tellEither:Boolean=false,val depth:Int=0,
    val created:Long=0,val status:String="pending",val firedAt:Long=0,val note:String="",
    /** Addresses and numbers the owner named when this was promised: the only places the follow-up may send to (empty: it can only draft). */
    val sendTo:List<String> = emptyList()) {
    fun json()=obj("id" to id,"bot" to bot,"what" to what,"due" to due,"goal" to goalId,"tell_either" to tellEither,"depth" to depth,"created" to created,
        "status" to status,"fired_at" to firedAt,"note" to note,"send_to" to arr(sendTo))
    companion object {
        fun parse(j:JSONObject)=Followup(j.optString("id"),j.optString("bot"),j.optString("what"),j.optLong("due"),j.optString("goal"),j.optBoolean("tell_either"),
            j.optInt("depth"),j.optLong("created"),j.optString("status","pending").ifBlank {"pending"},j.optLong("fired_at"),j.optString("note"),j.optJSONArray("send_to")?.strings().orEmpty())
    }
}

/** The proactive message the owner last saw, so a bare "done" or "never mind" can be tied to what it was about. */
data class Nag(val task:String,val goal:String,val followup:String,val bot:String,val at:Long) {
    fun json()=obj("task" to task,"goal" to goal,"followup" to followup,"bot" to bot,"at" to at)
    companion object {fun parse(j:JSONObject?)=j?.takeIf {it.length()>0}?.let {Nag(it.optString("task"),it.optString("goal"),it.optString("followup"),it.optString("bot"),it.optLong("at"))}}
}

object GoalRules {
    val statuses=listOf("active","waiting","paused","done")
    const val MAX_PENDING_PER_BOT=5
    const val MAX_OPEN_GOALS=30
    const val MAX_DEPTH=3
    const val MIN_AHEAD_MS=5*60_000L
    const val MAX_AHEAD_MS=30*24*3_600_000L
    /** A follow-up this late is no longer "a check-back": it turns into a "missed" notice instead of running. */
    const val STALE_MS=6*3_600_000L
    /** How long after "next check" a goal may go without any update before it counts as overdue. */
    const val OVERDUE_GRACE_MS=30*60_000L

    fun status(raw:String):String?=raw.trim().lowercase().let {s->when(s) {"in progress","open","ongoing","working"->"active";"blocked","on hold","waiting on someone"->"waiting";"complete","completed","finished","closed","resolved"->"done";else->s}}.takeIf {it in statuses}
    fun clean(text:String,max:Int)=text.replace(Regex("\\s+")," ").trim().take(max)
    fun goalId(bot:String,title:String)=digest(bot.lowercase()+"|"+title.lowercase().replace(Regex("[^a-z0-9]+")," ").trim()).take(8)

    sealed class Check {
        data class Ok(val followup:Followup):Check()
        data class Same(val existing:Followup):Check()
        data class Error(val message:String):Check()
    }

    /**
     * Whether a bot may make this follow-up now. The messages are for the bot, in plain words it can repeat to the owner. A follow-up that says the same
     * thing as one already waiting is not a new one (the bot gets the existing one back).
     */
    fun checkFollowup(all:List<Followup>,goals:List<Goal>,bot:String,what:String,due:Long,now:Long,goalId:String="",depth:Int=0,tellEither:Boolean=false,sendTo:List<String> = emptyList()):Check {
        val text=clean(what,400)
        if(text.length<8) return Check.Error("Say what you will check, in a sentence, so you know what to do when the time comes.")
        if(due<now+MIN_AHEAD_MS) return Check.Error("A follow-up has to be at least 5 minutes from now. If it is that soon, just do it now.")
        if(due>now+MAX_AHEAD_MS) return Check.Error("A follow-up can be at most 30 days from now. Pick a nearer time, or keep a goal with a next check instead.")
        if(depth>=MAX_DEPTH) return Check.Error("This has been followed up on three times already. Tell the owner where it stands and leave it with them.")
        val goal=goalId.takeIf {it.isNotBlank()}?.let {id->goals.firstOrNull {it.id==id}}
        if(goalId.isNotBlank() && goal==null) return Check.Error("There is no goal with that id. Use goal_list, or leave goal_id empty.")
        if(goal != null && !goal.open) return Check.Error("That goal is done, so there is nothing to follow up on.")
        val pending=all.filter {it.status=="pending" && it.bot==bot}
        val key=text.lowercase().replace(Regex("[^a-z0-9]+")," ").trim()
        pending.firstOrNull {it.what.lowercase().replace(Regex("[^a-z0-9]+")," ").trim()==key}?.let {return Check.Same(it)}
        if(pending.size>=MAX_PENDING_PER_BOT) return Check.Error("You already have 5 follow-ups waiting. Use followup_cancel on one that no longer matters, or wait until one has run.")
        val id=digest(bot+"|"+key+"|"+due).take(8)
        return Check.Ok(Followup(id,bot,text,due,goalId,tellEither,depth,now,sendTo=sendTo))
    }
}

/** All goals and follow-ups, as one saved piece. Every change returns a new book; nothing here touches the phone. */
data class GoalBook(val goals:List<Goal> = emptyList(),val followups:List<Followup> = emptyList(),val nag:Nag?=null) {
    fun json()=obj("goals" to JSONArray(goals.map {it.json()}),"followups" to JSONArray(followups.map {it.json()}),"nag" to nag?.json())
    fun goal(id:String)=goals.firstOrNull {it.id==id}
    fun followup(id:String)=followups.firstOrNull {it.id==id}
    fun pending()=followups.filter {it.status=="pending"}.sortedBy {it.due}

    /** Starts keeping track of something. The same title for the same bot is the same goal (it is updated, not doubled). */
    fun setGoal(bot:String,title:String,nextCheck:Long,notes:String,now:Long):Pair<GoalBook,Goal> {
        val clean=GoalRules.clean(title,120)
        require(clean.length>=4) {"Give the goal a short title (a few words)."}
        val id=GoalRules.goalId(bot,clean)
        val existing=goal(id)
        if(existing!=null) {
            val next=existing.copy(status=if(existing.status=="done") "active" else existing.status,nextCheck=if(nextCheck>0) nextCheck else existing.nextCheck,
                notes=if(notes.isNotBlank()) GoalRules.clean(notes,600) else existing.notes,updated=now)
            return copy(goals=goals.map {if(it.id==id) next else it}) to next
        }
        require(goals.count {it.open}<GoalRules.MAX_OPEN_GOALS) {"There are already 30 goals open. Mark some done first."}
        val made=Goal(id,clean,bot,"active","",0,nextCheck,GoalRules.clean(notes,600),now,now)
        return copy(goals=goals+made) to made
    }

    /**
     * A bot (or the owner) changes a goal. [nextCheck]: null leaves it, 0 clears it. A done goal stays done unless the owner brings it back
     * ([byOwner]); a bot can never reopen one. Finishing a goal cancels the follow-ups that were waiting on it.
     */
    fun updateGoal(id:String,status:String?,lastAction:String?,nextCheck:Long?,notes:String?,now:Long,byOwner:Boolean=false):Pair<GoalBook,Goal> {
        val g=goal(id)?:throw IllegalArgumentException("There is no goal with that id. Use goal_list.")
        val to=status?.takeIf {it.isNotBlank()}?.let {GoalRules.status(it)?:throw IllegalArgumentException("Use active, waiting, paused or done.")}
        if(g.status=="done" && to != null && to!="done" && !byOwner) throw IllegalArgumentException("That goal is done. The owner said it is handled, so leave it alone.")
        if(g.status=="done" && to==null && !byOwner) throw IllegalArgumentException("That goal is done. The owner said it is handled, so leave it alone.")
        val next=g.copy(status=to?:g.status,lastAction=lastAction?.takeIf {it.isNotBlank()}?.let {GoalRules.clean(it,240)}?:g.lastAction,
            lastActionAt=if(!lastAction.isNullOrBlank()) now else g.lastActionAt,nextCheck=if(to=="done") 0 else nextCheck?:(if(!lastAction.isNullOrBlank() && g.nextCheck in 1 until now) 0L else g.nextCheck),
            notes=notes?.takeIf {it.isNotBlank()}?.let {GoalRules.clean(it,600)}?:g.notes,updated=now)
        val ended=if(next.status=="done") followups.map {if(it.goalId==id && it.status=="pending") it.copy(status="cancelled",note="goal done") else it} else followups
        return copy(goals=goals.map {if(it.id==id) next else it},followups=ended) to next
    }

    fun deleteGoal(id:String)=copy(goals=goals.filterNot {it.id==id},followups=followups.filterNot {it.goalId==id && it.status=="pending"},nag=nag?.takeIf {it.goal!=id})

    fun addFollowup(f:Followup)=copy(followups=(followups+f).let {tidy(it)})
    fun cancelFollowup(id:String)=copy(followups=followups.map {if(it.id==id && it.status=="pending") it.copy(status="cancelled",note="cancelled") else it})
    fun markFollowup(id:String,status:String,note:String,now:Long)=copy(followups=followups.map {if(it.id==id) it.copy(status=status,firedAt=if(status=="fired") now else it.firedAt,note=GoalRules.clean(note,240).ifBlank {it.note}) else it})
    fun withNag(n:Nag?)=copy(nag=n)

    /** What to do when a follow-up's time comes: run it, or skip it quietly (already handled, cancelled, or its goal is done or gone). */
    sealed class Due {
        data class Run(val followup:Followup,val goal:Goal?,val late:Boolean):Due()
        data class Skip(val reason:String):Due()
        data class Stale(val followup:Followup):Due()
    }
    fun due(id:String,now:Long):Due {
        val f=followup(id)?:return Due.Skip("gone")
        if(f.status!="pending") return Due.Skip("already "+f.status)
        val g=f.goalId.takeIf {it.isNotBlank()}?.let {goal(it)}
        if(f.goalId.isNotBlank() && (g==null || !g.open)) return Due.Skip("goal done")
        if(g != null && g.status=="paused") return Due.Skip("goal paused")
        if(now-f.due>GoalRules.STALE_MS) return Due.Stale(f)
        return Due.Run(f,g,now-f.due>20*60_000L)
    }

    /** Goals whose "next check" passed with no update since (the bot said it would look and never did). */
    fun overdue(now:Long)=goals.filter {(it.status=="active" || it.status=="waiting") && it.nextCheck>0 && now>it.nextCheck+GoalRules.OVERDUE_GRACE_MS && it.updated<=it.nextCheck}
    /** Pending follow-ups whose time is well past (the phone was off or busy). */
    fun late(now:Long,graceMs:Long=GoalRules.STALE_MS)=followups.filter {it.status=="pending" && now-it.due>graceMs}

    /** Applies a "that's done" from the owner. */
    fun closeFor(r:StopRules.Resolution,now:Long):GoalBook {
        var book=this
        r.goalIds.forEach {id-> if(book.goal(id)?.open==true) book=book.updateGoal(id,"done","The owner said it is handled.",0,null,now,true).first}
        r.followupIds.forEach {id-> book=book.cancelFollowup(id)}
        return book
    }

    private fun tidy(all:List<Followup>):List<Followup> {
        val keep=all.filter {it.status=="pending"}
        val recent=all.filter {it.status!="pending"}.sortedByDescending {maxOf(it.firedAt,it.due)}.take(30)
        return (keep+recent).sortedBy {it.due}
    }

    companion object {
        fun parse(j:JSONObject?):GoalBook {
            if(j==null) return GoalBook()
            return GoalBook(j.optJSONArray("goals")?.objects().orEmpty().map(Goal::parse).filter {it.id.isNotBlank() && it.title.isNotBlank()},
                j.optJSONArray("followups")?.objects().orEmpty().map(Followup::parse).filter {it.id.isNotBlank() && it.what.isNotBlank()},Nag.parse(j.optJSONObject("nag")))
        }
    }
}

/** Understands "the owner said it is done / stop / never mind". Words, not guesses: it only closes what the words clearly point at. */
object StopRules {
    data class Resolution(val goalIds:Set<String> = emptySet(),val followupIds:Set<String> = emptySet()) {val empty get()=goalIds.isEmpty() && followupIds.isEmpty()}
    const val NAG_WINDOW_MS=36*3_600_000L

    private fun normalize(text:String)=text.lowercase().replace('’','\'').replace(Regex("[^a-z0-9' ]+")," ").replace(Regex("\\s+")," ").trim()
    private val bare=Regex("^(ok |okay |yes |yep |yeah |thanks |thank you |great |cool |no |nope )*(done|all done|handled|taken care of|sorted|never ?mind|forget it|forget that|stop|stop it|stop that|stop please|no need|" +
        "drop it|leave it|all set|all good|we'?re good|that'?s (done|handled|sorted|finished|fine|taken care of|ok|okay|ok now)|it'?s (done|handled|sorted|taken care of|finished)|already (done|handled|taken care of|did it)|" +
        "i'?ve (got it|handled it|done it|taken care of it)|i (did it|handled it|took care of it|got it)|cancel (that|it))( thanks| thank you| now| please| already)*$")
    private val intent=listOf("stop reminding","stop asking","stop checking","stop following","stop bugging","stop nagging","don't remind","do not remind","dont remind","no need to remind",
        "no need to follow","no need to check","never mind","nevermind","forget about","that's handled","thats handled","is done","is handled","are done","are handled","is sorted","already done","already handled",
        "taken care of","handled it","took care of","i did it","i've done","got it handled","don't follow up","do not follow up","dont follow up","leave it alone","drop it")
    private val filler=setOf("that","this","with","about","reminding","remind","stop","done","handled","please","thanks","thank","need","dont","don't","follow","check","checking","asking","again","already",
        "never","mind","nevermind","forget","taken","care","took","have","just","been","from","them","they","there","their","what","when","will","were","your","you're","still","anymore","longer","more","also","the")

    fun isBare(text:String):Boolean = normalize(text).let {it.isNotBlank() && it.split(' ').size<=9 && bare.containsMatchIn(it)}
    fun hasIntent(text:String):Boolean = normalize(text).let {t->t.isNotBlank() && t.split(' ').size<=25 && intent.any {t.contains(it)}}
    private fun words(text:String)=normalize(text).split(' ').filter {it.length>=4 && it !in filler}.toSet()

    /**
     * What the owner's message closes. A bare "done" closes what the bot's latest automatic message was about ([nagStillLatest]: nothing else was said in that
     * chat since, so the owner is plainly answering it). A longer message closes the goals and follow-ups it names. Anything unclear closes nothing.
     */
    fun resolve(text:String,goals:List<Goal>,followups:List<Followup>,nag:Nag?,nagStillLatest:Boolean,now:Long):Resolution {
        val open=goals.filter {it.open}
        val pending=followups.filter {it.status=="pending"}
        fun viaNag():Resolution {
            if(nag==null || !nagStillLatest || now-nag.at>NAG_WINDOW_MS) return Resolution()
            val goal=nag.goal.takeIf {id->id.isNotBlank() && open.any {it.id==id}}
            val ups=pending.filter {(nag.followup.isNotBlank() && it.id==nag.followup) || (goal!=null && it.goalId==goal) || (it.bot==nag.bot && it.created>=nag.at-15*60_000L)}.map {it.id}
            return Resolution(setOfNotNull(goal),ups.toSet())
        }
        if(isBare(text)) return viaNag()
        if(!hasIntent(text)) return Resolution()
        val said=words(text)
        data class Hit(val goal:Goal?,val up:Followup?,val score:Int)
        val scored=open.map {Hit(it,null,words(it.title+" "+it.notes).intersect(said).size)}+pending.map {Hit(null,it,words(it.what).intersect(said).size)}
        val best=scored.filter {it.score>0}
        if(best.isEmpty()) return if(said.isEmpty()) viaNag() else Resolution()
        val top=best.maxOf {it.score}
        val picked=if(top>=2) best.filter {it.score>=2} else if(best.size==1) best else emptyList()
        if(picked.isEmpty()) return Resolution()
        // a follow-up that is named closes the goal it belongs to as well: "never bring it up again"
        val goalIds=(picked.mapNotNull {it.goal?.id}+picked.mapNotNull {it.up?.goalId?.takeIf {id->open.any {g->g.id==id}}}).toSet()
        val upIds=(picked.mapNotNull {it.up?.id}+pending.filter {it.goalId in goalIds}.map {it.id}).toSet()
        return Resolution(goalIds,upIds)
    }
}

/** Times as the bots write them ("in 2 hours", "tomorrow 8am", "friday 9:00", "2026-10-12 08:00") turned into a moment, and back into plain words. */
object When {
    private val months=listOf("jan","feb","mar","apr","may","jun","jul","aug","sep","oct","nov","dec")
    private val days=mapOf("mon" to DayOfWeek.MONDAY,"tue" to DayOfWeek.TUESDAY,"wed" to DayOfWeek.WEDNESDAY,"thu" to DayOfWeek.THURSDAY,"fri" to DayOfWeek.FRIDAY,"sat" to DayOfWeek.SATURDAY,"sun" to DayOfWeek.SUNDAY)
    private val number=mapOf("a" to 1.0,"an" to 1.0,"one" to 1.0,"two" to 2.0,"three" to 3.0,"four" to 4.0,"five" to 5.0,"six" to 6.0,"half an" to 0.5,"half a" to 0.5)

    private fun iso(text:String):Long? {
        try {return OffsetDateTime.parse(text).toInstant().toEpochMilli()} catch(_:Exception) {}
        try {return Instant.parse(text).toEpochMilli()} catch(_:Exception) {}
        return null
    }

    fun parse(raw:String,now:ZonedDateTime):Long? {
        val text=raw.trim()
        if(text.isBlank()) return null
        iso(text)?.let {return it}
        val zone=now.zone
        try {return LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()} catch(_:Exception) {}
        var t=text.lowercase().replace(",","").replace(Regex("\\s+")," ").trim().removeSuffix(".")
        Regex("^(?:in )?(\\d+(?:\\.\\d+)?|half an?|an?|one|two|three|four|five|six) ?(minutes?|mins?|hours?|hrs?|days?|weeks?)(?: from now)?$").find(t)?.let {m->
            val n=m.groupValues[1].toDoubleOrNull()?:number[m.groupValues[1]]?:return null
            val minutes=when {m.groupValues[2].startsWith("min") -> n; m.groupValues[2].startsWith("h") -> n*60; m.groupValues[2].startsWith("d") -> n*1440; else -> n*10080}
            return now.toInstant().toEpochMilli()+(minutes*60_000).toLong()
        }
        var day:LocalDate?=null;var defaultHour=-1
        val today=now.toLocalDate()
        fun cut(m:MatchResult) {t=t.removeRange(m.range).replace(Regex("\\s+")," ").trim()}
        Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})").find(t)?.let {m->runCatching {day=LocalDate.of(m.groupValues[1].toInt(),m.groupValues[2].toInt(),m.groupValues[3].toInt())};if(day!=null) cut(m)}
        if(day==null) Regex("\\btomorrow\\b").find(t)?.let {day=today.plusDays(1);cut(it)}
        if(day==null) Regex("\\b(today|tonight|this (morning|afternoon|evening))\\b").find(t)?.let {m->
            day=today
            defaultHour=when {m.value=="tonight"->20;m.value.endsWith("morning")->9;m.value.endsWith("afternoon")->15;m.value.endsWith("evening")->18;else->-1}
            cut(m)
        }
        var weekdayUsed=false
        if(day==null) Regex("\\b(?:(next|this) )?(monday|mon|tuesday|tues|tue|wednesday|wed|thursday|thurs|thur|thu|friday|fri|saturday|sat|sunday|sun)\\b").find(t)?.let {m->
            val want=days.getValue(m.groupValues[2].take(3))
            val base=(want.value-today.dayOfWeek.value+7)%7
            day=today.plusDays((if(m.groupValues[1]=="next" && base==0) 7 else base).toLong());weekdayUsed=true;cut(m)
        }
        val monthWords="(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"
        if(day==null) Regex("\\b$monthWords (\\d{1,2})(?:st|nd|rd|th)?\\b").find(t)?.let {m->
            val month=months.indexOf(m.groupValues[1].take(3))+1;runCatching {var d=LocalDate.of(today.year,month,m.groupValues[2].toInt());if(d.isBefore(today)) d=d.plusYears(1);day=d};if(day!=null) cut(m)
        }
        if(day==null) Regex("\\b(\\d{1,2})(?:st|nd|rd|th)? $monthWords\\b").find(t)?.let {m->
            val month=months.indexOf(m.groupValues[2].take(3))+1;runCatching {var d=LocalDate.of(today.year,month,m.groupValues[1].toInt());if(d.isBefore(today)) d=d.plusYears(1);day=d};if(day!=null) cut(m)
        }
        if(day==null) Regex("\\b(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\b").find(t)?.let {m->
            val year=m.groupValues[3].takeIf {it.isNotBlank()}?.toInt()?.let {if(it<100) 2000+it else it}
            runCatching {var d=LocalDate.of(year?:today.year,m.groupValues[1].toInt(),m.groupValues[2].toInt());if(year==null && d.isBefore(today)) d=d.plusYears(1);day=d};if(day!=null) cut(m)
        }
        // the time of day
        var hour=-1;var minute=0
        Regex("\\bnoon\\b").find(t)?.let {hour=12;cut(it)}
        if(hour<0) Regex("\\b(\\d{1,2}):(\\d{2}) ?(am|pm)?\\b").find(t)?.let {m->
            hour=m.groupValues[1].toInt();minute=m.groupValues[2].toInt()
            if(m.groupValues[3]=="pm" && hour<12) hour+=12 else if(m.groupValues[3]=="am" && hour==12) hour=0
            cut(m)
        }
        if(hour<0) Regex("\\b(\\d{1,2}) ?(am|pm)\\b").find(t)?.let {m->
            hour=m.groupValues[1].toInt()
            if(m.groupValues[2]=="pm" && hour<12) hour+=12 else if(m.groupValues[2]=="am" && hour==12) hour=0
            cut(m)
        }
        if(hour<0) Regex("\\bat (\\d{1,2})\\b").find(t)?.let {m->
            hour=m.groupValues[1].toInt().let {h->if(h in 1..6) h+12 else h};cut(m)
        }
        if(hour<0) Regex("\\b(morning|afternoon|evening|night)\\b").find(t)?.let {m->
            hour=when(m.groupValues[1]) {"morning"->9;"afternoon"->15;"evening"->18;else->20};cut(m)
        }
        if(hour !in -1..23 || minute !in 0..59) return null
        if(day==null && hour<0) return null
        // anything left over that is not filler means the text was something else
        if(t.replace(Regex("\\b(at|on|by|around|about|the|of|in|next|this|morning|afternoon|evening|night)\\b")," ").trim().isNotEmpty()) return null
        if(day==null) {
            val time=LocalTime.of(hour,minute)
            var candidate=ZonedDateTime.of(today,time,zone)
            if(!candidate.isAfter(now)) candidate=candidate.plusDays(1)
            return candidate.toInstant().toEpochMilli()
        }
        val d=day!!
        val h=if(hour>=0) hour else if(defaultHour>=0) defaultHour else if(d==today) 17 else 9
        var moment=ZonedDateTime.of(d,LocalTime.of(h,minute),zone)
        // "friday 9am" said on a Friday afternoon means next Friday
        if(!moment.isAfter(now) && weekdayUsed) moment=moment.plusDays(7)
        return moment.toInstant().toEpochMilli()
    }

    /** "today 3:05 PM", "tomorrow 8:00 AM", "Fri 8:00 AM", "Oct 21, 8:00 AM". */
    fun describe(ms:Long,now:Long=System.currentTimeMillis(),zone:ZoneId=ZoneId.systemDefault()):String {
        if(ms<=0) return "no time set"
        val at=Instant.ofEpochMilli(ms).atZone(zone);val today=Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val clock=DateTimeFormatter.ofPattern("h:mm a",Locale.US).format(at)
        val d=at.toLocalDate()
        return when {
            d==today->"today $clock"
            d==today.plusDays(1)->"tomorrow $clock"
            d==today.minusDays(1)->"yesterday $clock"
            d.isAfter(today) && d.isBefore(today.plusDays(7))->DateTimeFormatter.ofPattern("EEE",Locale.US).format(at)+" $clock"
            else->DateTimeFormatter.ofPattern("MMM d",Locale.US).format(at)+", $clock"
        }
    }
    /** "today", "tomorrow", "on Friday", "on Oct 21": the day only, for a sentence like "Two things overlap tomorrow". */
    fun onDay(ms:Long,now:Long=System.currentTimeMillis(),zone:ZoneId=ZoneId.systemDefault()):String {
        val at=Instant.ofEpochMilli(ms).atZone(zone);val today=Instant.ofEpochMilli(now).atZone(zone).toLocalDate();val d=at.toLocalDate()
        return when {
            d==today->"today"
            d==today.plusDays(1)->"tomorrow"
            d.isAfter(today) && d.isBefore(today.plusDays(7))->"on "+DateTimeFormatter.ofPattern("EEEE",Locale.US).format(at)
            else->"on "+DateTimeFormatter.ofPattern("MMM d",Locale.US).format(at)
        }
    }
    /** "just now", "5 min ago", "2 h ago", "yesterday", "3 days ago". */
    fun ago(ms:Long,now:Long=System.currentTimeMillis()):String {
        if(ms<=0) return "never"
        val minutes=(now-ms)/60_000
        return when {minutes<1->"just now";minutes<60->"$minutes min ago";minutes<24*60->(minutes/60).toString()+" h ago";minutes<48*60->"yesterday";else->(minutes/1440).toString()+" days ago"}
    }
}
