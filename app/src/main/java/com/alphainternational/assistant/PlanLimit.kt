package com.alphainternational.assistant

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONObject

/**
 * The ChatGPT plan's sharing limit for Alpha ("The ChatGPT user has reached their Subscription Sharing usage limit. Ask the user to try again after ..."). It is not a sign-in
 * problem and reconnecting does not change it; the plan says when it clears. On 2026-10-16 a group message woke eleven bots at once, all hit it together, every bot paused, and
 * the owner took it for a broken OAuth connection because nothing said when it would be over.
 *
 * This reads the reset time out of the error (the plan's own wording, or a Retry-After), so Alpha can wait for exactly that time instead of trying again every few minutes, and say so.
 */
object PlanLimit {
    private const val MAX_DAYS=14L
    /**
     * OpenAI's help page "Using your ChatGPT plan in other apps and sites": every app gets a weekly cap that the owner sets in ChatGPT (Settings > Usage > App limits > Manage limits), and
     * an app can hit it while the plan still has room. That page also shows when it resets. Alpha cannot change it: it is the owner's account setting.
     */
    const val RAISE_IT="To open it up now, in ChatGPT go to Settings, then Usage, then App limits, find Alpha and tap Manage limits: it shows when the limit resets and lets you raise it."
    const val RAISE_SHORT="Raise it in ChatGPT: Settings > Usage > App limits > Alpha."
    private val months=listOf("jan","feb","mar","apr","may","jun","jul","aug","sep","oct","nov","dec")
    /** Zone words in a reset time. PT, MT, CT and ET follow daylight saving; the others are the fixed offsets they name. */
    private val zones:Map<String,ZoneId> = mapOf("UTC" to ZoneOffset.UTC,"GMT" to ZoneOffset.UTC,"PST" to ZoneOffset.ofHours(-8),"PDT" to ZoneOffset.ofHours(-7),"MST" to ZoneOffset.ofHours(-7),"MDT" to ZoneOffset.ofHours(-6),
        "CST" to ZoneOffset.ofHours(-6),"CDT" to ZoneOffset.ofHours(-5),"EST" to ZoneOffset.ofHours(-5),"EDT" to ZoneOffset.ofHours(-4),
        "PT" to ZoneId.of("America/Los_Angeles"),"MT" to ZoneId.of("America/Denver"),"CT" to ZoneId.of("America/Chicago"),"ET" to ZoneId.of("America/New_York"))

    /** When the limit clears (epoch millis), or null when the error does not say. Always in the future and within two weeks. */
    fun resetAt(message:String,now:Long,zone:ZoneId=ZoneId.systemDefault()):Long? {
        val text=message.replace(Regex("\\s+")," ")
        val found=retryAfter(text,now) ?: iso(text,zone) ?: relative(text,now) ?: clock(text,now,zone)
        return found?.takeIf {it>now && it<=now+MAX_DAYS*86_400_000L}
    }

    /**
     * Seconds until the limit clears when the service says so outside the message: a field in the error event ("resets_in_seconds", "retry_after", "resets_at" as epoch seconds or millis),
     * or a header (Retry-After, or the Codex-style "x-codex-*-reset-after-seconds" of a window that is used up). Null when it does not say. [headers] has lower-case names.
     */
    fun secondsFrom(event:JSONObject,headers:Map<String,String>,now:Long):Long? {
        val places=listOfNotNull(event,event.optJSONObject("error"),event.optJSONObject("response")?.optJSONObject("error"))
        for(o in places) {
            for(key in listOf("resets_in_seconds","retry_after","retry_after_seconds","reset_after_seconds")) {
                val v=o.optDouble(key,Double.NaN)
                if(!v.isNaN() && v>0) return v.toLong()
            }
            val at=o.optDouble("resets_at",Double.NaN)
            if(!at.isNaN() && at>0) {
                val ms=if(at>1e11) at.toLong() else at.toLong()*1000
                if(ms>now) return (ms-now)/1000
            }
        }
        headers["retry-after"]?.trim()?.toLongOrNull()?.takeIf {it>0}?.let {return it}
        val windows=listOf("primary","secondary").mapNotNull {w->
            val used=headers["x-codex-$w-used-percent"]?.trim()?.toDoubleOrNull()
            val left=headers["x-codex-$w-reset-after-seconds"]?.trim()?.toLongOrNull()
            if(used!=null && used>=99.0 && left!=null && left>0) left else null
        }
        return windows.maxOrNull()
    }

    /** "Retry-After: N seconds" (the app appends it to the error text as "retry-after Ns"). */
    private fun retryAfter(text:String,now:Long):Long? =
        Regex("(?i)retry-after[ :]+(\\d{1,7})\\s*s").find(text)?.groupValues?.get(1)?.toLongOrNull()?.let {now+it*1000}

    private fun iso(text:String,zone:ZoneId):Long? {
        val m=Regex("(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2})(?::(\\d{2}))?(?:\\.\\d+)?\\s*(Z|UTC|GMT|[+-]\\d{2}:?\\d{2})?").find(text)?:return null
        val g=m.groupValues
        return runCatching {
            val local=LocalDateTime.of(g[1].toInt(),g[2].toInt(),g[3].toInt(),g[4].toInt(),g[5].toInt(),g[6].ifBlank {"0"}.toInt())
            val offset=g[7]
            val at=when {
                offset.isBlank() -> local.atZone(zone)
                offset=="Z" || offset=="UTC" || offset=="GMT" -> local.atZone(ZoneOffset.UTC)
                else -> local.atOffset(ZoneOffset.of(if(offset.contains(':')) offset else offset.substring(0,3)+":"+offset.substring(3))).toZonedDateTime()
            }
            at.toInstant().toEpochMilli()
        }.getOrNull()
    }

    /** "in 2 hours 15 minutes", "in 3 days". */
    private fun relative(text:String,now:Long):Long? {
        val after=Regex("(?i)\\bin (?:about |around |roughly )?((?:\\d+\\s*(?:days?|hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\\b[ ,and]*)+)").find(text)?.groupValues?.get(1)?:return null
        var ms=0L
        Regex("(?i)(\\d+)\\s*(days?|hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\\b").findAll(after).forEach {
            val n=it.groupValues[1].toLong();val unit=it.groupValues[2].lowercase()
            ms+=n*when {unit.startsWith("d") -> 86_400_000L;unit.startsWith("h") -> 3_600_000L;unit.startsWith("m") -> 60_000L;else -> 1_000L}
        }
        return if(ms>0) now+ms else null
    }

    /** "3:40 PM", "15:40", "Oct 20, 9:41 AM", "tomorrow at 8:15 AM", with an optional zone word (PT, CST, UTC). */
    private fun clock(text:String,now:Long,zone:ZoneId):Long? {
        // The reset time is at the end of the sentence ("... try again after Oct 20 at 9:41 AM."): look at the end, not at words like "on" inside "Subscription".
        val tail=text.takeLast(160)
        val time=Regex("(?i)\\b(\\d{1,2}):(\\d{2})(?::\\d{2})?\\s*(a\\.?m\\.?|p\\.?m\\.?)?").find(tail)
        val dateM=Regex("(?i)\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s+(\\d{4}))?").find(tail)
        if(time==null && dateM==null) return null
        val zoneWord=Regex("\\b(UTC|GMT|PST|PDT|PT|MST|MDT|MT|CST|CDT|CT|EST|EDT|ET)\\b").find(tail)?.groupValues?.get(1)
        val z:ZoneId=zoneWord?.let {zones[it]}?:zone
        val today=ZonedDateTime.ofInstant(Instant.ofEpochMilli(now),z).toLocalDate()
        var hour=0;var minute=0
        if(time!=null) {
            hour=time.groupValues[1].toInt();minute=time.groupValues[2].toInt()
            val ampm=time.groupValues[3].lowercase().replace(".","")
            if(ampm=="pm" && hour<12) hour+=12
            if(ampm=="am" && hour==12) hour=0
        }
        if(hour>23 || minute>59) return null
        val date:LocalDate = when {
            dateM!=null -> runCatching {
                val month=months.indexOf(dateM.groupValues[1].lowercase())+1
                val year=dateM.groupValues[3].toIntOrNull()?:today.year
                LocalDate.of(year,month,dateM.groupValues[2].toInt()).let {if(dateM.groupValues[3].isBlank() && it.isBefore(today)) it.plusYears(1) else it}
            }.getOrNull()?:return null
            Regex("(?i)\\btomorrow\\b").containsMatchIn(tail) -> today.plusDays(1)
            else -> today
        }
        var at=ZonedDateTime.of(date,LocalTime.of(hour,minute),z)
        if(dateM==null && at.toInstant().toEpochMilli()<=now) at=at.plusDays(1)
        return at.toInstant().toEpochMilli()
    }

    /** "in about 40 minutes", "at 3:40 PM", "tomorrow at 8:15 AM", "on Oct 20 at 9:41 AM": for the owner. */
    fun describe(until:Long,now:Long,zone:ZoneId=ZoneId.systemDefault()):String {
        val gap=until-now
        if(gap<=0) return "in a moment"
        if(gap<90*60_000L) return "in about "+maxOf(1L,Math.round(gap/60_000.0))+" minutes"
        val at=ZonedDateTime.ofInstant(Instant.ofEpochMilli(until),zone)
        val today=ZonedDateTime.ofInstant(Instant.ofEpochMilli(now),zone).toLocalDate()
        val time=at.format(DateTimeFormatter.ofPattern("h:mm a",Locale.US))
        return when(at.toLocalDate()) {
            today -> "at $time"
            today.plusDays(1) -> "tomorrow at $time"
            else -> "on "+at.format(DateTimeFormatter.ofPattern("MMM d",Locale.US))+" at $time"
        }
    }
}
