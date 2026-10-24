package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The record of an outreach campaign, so that "did it work?" has a number for an answer. Every message a bot sends to a business or a person for the owner (an email, a Facebook
 * message, a text) is written down the moment it goes out, with the message version and the industry; replies, bounces, interest and appointments are written down when they show up.
 * The report gives the real counts, which message version is doing better, and who is due a follow-up. It also guards the owner's account: it refuses a second message to the same
 * contact on the same channel within 30 days, and stops at a daily limit (a new bulk pattern from a personal Gmail is what makes Google ask "verify it's you").
 *
 * Why it exists: on 2026-10-13 a growth bot had contacted five companies, tracked them in a sheet it wrote by hand, and could say only that no message "demonstrated better performance yet".
 */
object Campaign {
    const val DAILY_CAP=60
    const val DUPLICATE_DAYS=30
    const val FOLLOW_UP_DAYS=3
    /** Fewest messages per version before the report names a better one. */
    const val MIN_FOR_BEST=5
    val channels=setOf("email","facebook","sms","instagram","linkedin","other")
    val outcomes=setOf("replied","interested","appointment","bounced","not_interested","no_reply")
    private val positive=setOf("replied","interested","appointment")
    private const val DAY=24*60*60*1000L

    class Entry(val who:String,val contact:String,val channel:String,val version:String,val industry:String,val sentAt:Long,val outcome:String="sent",val outcomeAt:Long=0L,val note:String="") {
        fun json():JSONObject=JSONObject().put("who",who).put("contact",contact).put("channel",channel).put("version",version).put("industry",industry).put("sent",sentAt).put("outcome",outcome).put("outcome_at",outcomeAt).put("note",note)
    }

    private fun norm(s:String)=s.lowercase().trim().replace(Regex("\\s+")," ")
    private fun same(e:Entry,who:String,contact:String):Boolean =
        (contact.isNotBlank() && norm(e.contact)==norm(contact)) || (norm(e.who).isNotEmpty() && norm(e.who)==norm(who))
    private fun day(ms:Long)=SimpleDateFormat("MMM d",Locale.US).format(Date(ms))

    class Result(val entries:List<Entry>,val answer:JSONObject)

    /** One message sent. Refuses a repeat to the same contact on the same channel within [DUPLICATE_DAYS] days and anything past the daily limit. */
    fun sent(entries:List<Entry>,who:String,contact:String,channel:String,version:String,industry:String,now:Long):Result {
        require(who.isNotBlank() || contact.isNotBlank()) {"Say who it went to (the company or person, and their email or other contact)."}
        val ch=norm(channel).let {if(it in channels) it else if(it.contains("mail")) "email" else if(it.contains("text")) "sms" else "other"}
        val earlier=entries.lastOrNull {it.channel==ch && same(it,who,contact) && now-it.sentAt<DUPLICATE_DAYS*DAY}
        require(earlier==null) {"Already contacted "+(who.ifBlank {contact})+" by "+ch+" on "+day(earlier!!.sentAt)+". Skip them and go on to the next one; never send the same person a second message this soon."}
        val today=entries.count {it.channel==ch && now-it.sentAt<DAY}
        require(today<DAILY_CAP) {"The daily limit of "+DAILY_CAP+" "+ch+" messages is reached ("+today+" in the last 24 hours). Stop sending for now: this protects the owner's account, and the rest can go tomorrow. Tell the owner the counts."}
        val next=entries+Entry(who.trim(),contact.trim(),ch,version.trim().ifBlank {"A"}.take(12),industry.trim().take(40),now)
        return Result(next,JSONObject().put("result","recorded").put("sent_today",today+1).put("left_today",DAILY_CAP-today-1).put("total_sent",next.size))
    }

    /** What happened to someone contacted before (the newest message to them). */
    fun outcome(entries:List<Entry>,who:String,channel:String,outcome:String,note:String,now:Long):Result {
        val o=norm(outcome).replace(' ','_')
        require(o in outcomes) {"Outcome must be one of: "+outcomes.joinToString()+"."}
        val ch=norm(channel)
        val at=entries.indexOfLast {same(it,who,who) && (ch.isBlank() || it.channel==ch)}
        require(at>=0) {"Nobody called \""+who+"\" is in the campaign record. Record the message with campaign_sent first."}
        val old=entries[at]
        val updated=entries.toMutableList().also {it[at]=Entry(old.who,old.contact,old.channel,old.version,old.industry,old.sentAt,o,now,note.trim().take(200))}
        return Result(updated,JSONObject().put("result","recorded").put("who",old.who).put("outcome",o))
    }

    /** The real numbers: totals, by message version, industry and channel, the better version once there is enough to tell, who is due a follow-up. */
    fun report(entries:List<Entry>,now:Long):JSONObject {
        fun count(o:String)=entries.count {it.outcome==o}
        fun group(by:(Entry)->String):JSONArray {
            val out=JSONArray()
            entries.groupBy(by).entries.sortedByDescending {it.value.size}.forEach {(name,list)->
                val good=list.count {it.outcome in positive}
                out.put(JSONObject().put("name",name.ifBlank {"(none)"}).put("sent",list.size).put("positive_replies",good).put("rate_percent",if(list.isEmpty()) 0 else Math.round(100.0*good/list.size).toInt()))
            }
            return out
        }
        val byVersion=entries.groupBy {it.version}.filter {it.value.size>=MIN_FOR_BEST}
            .map {(v,list)->Triple(v,list.size,list.count {it.outcome in positive}.toDouble()/list.size)}.sortedByDescending {it.third}
        val best=when {
            entries.isEmpty()->"nothing sent yet"
            byVersion.size<2->"not enough to tell yet (needs at least "+MIN_FOR_BEST+" messages of two different versions)"
            byVersion[0].third==byVersion[1].third->"no difference yet"
            else->"version "+byVersion[0].first+" ("+Math.round(byVersion[0].third*100)+"% got a positive reply, of "+byVersion[0].second+" sent)"
        }
        val waiting=JSONArray()
        entries.filter {it.outcome=="sent" && now-it.sentAt>=FOLLOW_UP_DAYS*DAY}.sortedBy {it.sentAt}.take(10).forEach {
            waiting.put(JSONObject().put("who",it.who.ifBlank {it.contact}).put("channel",it.channel).put("sent",day(it.sentAt)))
        }
        val todayByChannel=JSONObject()
        channels.forEach {ch->val n=entries.count {it.channel==ch && now-it.sentAt<DAY};if(n>0) todayByChannel.put(ch,n)}
        return JSONObject().put("sent_total",entries.size).put("sent_last_24h",todayByChannel)
            .put("positive_replies",entries.count {it.outcome in positive}).put("replied",count("replied")).put("interested",count("interested")).put("appointments",count("appointment"))
            .put("bounced",count("bounced")).put("not_interested",count("not_interested")).put("no_reply_yet",entries.count {it.outcome=="sent"})
            .put("better_version",best).put("by_version",group {it.version}).put("by_industry",group {it.industry}).put("by_channel",group {it.channel})
            .put("due_for_follow_up",waiting).put("daily_limit_per_channel",DAILY_CAP)
            .put("instruction","Tell the owner these real numbers in one or two lines (sent, replies, which version and industry work better, who is due a follow-up, how many are left to reach their number). Never claim results these numbers do not show.")
    }

    fun parse(text:String):List<Entry> = runCatching {
        val list=JSONArray(text)
        (0 until list.length()).mapNotNull {i->
            val j=list.optJSONObject(i)?:return@mapNotNull null
            Entry(j.optString("who"),j.optString("contact"),j.optString("channel"),j.optString("version").ifBlank {"A"},j.optString("industry"),j.optLong("sent"),j.optString("outcome").ifBlank {"sent"},j.optLong("outcome_at"),j.optString("note"))
        }
    }.getOrDefault(emptyList())

    fun write(entries:List<Entry>):String=JSONArray(entries.map {it.json()}).toString()

    /** The same record as a sheet the owner can open (Computer, Files). */
    fun csv(entries:List<Entry>):String {
        fun q(s:String)="\""+s.replace("\"","\"\"")+"\""
        val fmt=SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US)
        return (listOf("company,contact,channel,version,industry,sent,outcome,outcome date,note")+entries.map {e->
            listOf(q(e.who),q(e.contact),q(e.channel),q(e.version),q(e.industry),q(fmt.format(Date(e.sentAt))),q(e.outcome),q(if(e.outcomeAt>0) fmt.format(Date(e.outcomeAt)) else ""),q(e.note)).joinToString(",")
        }).joinToString("\n")+"\n"
    }
}

/** The three campaign tools, keeping each bot's record in its own folder (campaign_ledger.json, with campaign_ledger.csv beside it for the owner). */
object CampaignTools {
    val tools=listOf(
        Tool("campaign_sent","Record ONE outreach message you just sent for the owner (an email, a Facebook message, a text). Call it right after each send. who: the company or person; contact: their email, phone or page (empty if unknown); channel: email, facebook, sms, instagram, linkedin or other; version: your message version label (A, B, ...); industry: their kind of business. It refuses a repeat to the same contact within 30 days and stops at the daily limit: obey it.",
            schema("who" to "string","contact" to "string","channel" to "string","version" to "string","industry" to "string")),
        Tool("campaign_outcome","Record what happened with someone you contacted: outcome is replied, interested, appointment, bounced, not_interested or no_reply. who must match the name used in campaign_sent. channel may be empty.",
            schema("who" to "string","channel" to "string","outcome" to "string","note" to "string")),
        Tool("campaign_report","The real campaign numbers so far: sent, replies, bounces, appointments, which message version and industry do better, who is due a follow-up, how much of today's limit is left. Call it after every batch and in every check-in; give the owner these numbers.",schema())
    )
    val names:Set<String> = tools.map {it.name}.toSet()

    fun run(name:String,a:JSONObject,folder:File,now:Long):JSONObject = synchronized(this) {
        val json=File(folder,"campaign_ledger.json")
        val entries=if(json.isFile) Campaign.parse(json.readText()) else emptyList()
        fun save(next:List<Campaign.Entry>) {folder.mkdirs();json.writeText(Campaign.write(next));File(folder,"campaign_ledger.csv").writeText(Campaign.csv(next))}
        // The sheet always matches the record, even if a bot overwrote the record file by hand.
        if(json.isFile) File(folder,"campaign_ledger.csv").writeText(Campaign.csv(entries))
        when(name) {
            "campaign_sent"->Campaign.sent(entries,a.optString("who"),a.optString("contact"),a.optString("channel"),a.optString("version"),a.optString("industry"),now).also {save(it.entries)}.answer
            "campaign_outcome"->Campaign.outcome(entries,a.optString("who"),a.optString("channel"),a.optString("outcome"),a.optString("note"),now).also {save(it.entries)}.answer
            else->Campaign.report(entries,now)
        }
    }
}
