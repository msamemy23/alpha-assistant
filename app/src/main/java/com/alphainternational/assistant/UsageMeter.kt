package com.alphainternational.assistant

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the AI has been asked to read and write, counted per day, so a pay-as-you-go key (or a hosted model) never comes as a surprise. The ChatGPT plan is
 * a flat subscription with its own limits, so only the meter shows for it; the caps below apply to services that bill by the token.
 */
object UsageMeter {
    /** One job may use this many tokens (read plus written) on a metered service before its tools are taken away and it must answer with what it has. */
    const val JOB_CAP:Long=3_000_000
    /** One notice a day when a metered service passes this many tokens. */
    const val DAY_WARN:Long=15_000_000
    private const val KEEP_DAYS=30

    data class Day(val calls:Int=0,val input:Long=0,val cached:Long=0,val output:Long=0) {
        val total:Long get()=input+output
    }

    fun today(now:Long=System.currentTimeMillis()):String=SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date(now))

    /** Adds one model reply's token counts to [day] and drops days older than a month. Returns the updated record (the input is changed in place too). */
    fun add(days:JSONObject,day:String,input:Int,cached:Int,output:Int):JSONObject {
        val d=days.optJSONObject(day)?:JSONObject().also {days.put(day,it)}
        d.put("calls",d.optInt("calls")+1).put("input",d.optLong("input")+input.coerceAtLeast(0))
            .put("cached",d.optLong("cached")+cached.coerceAtLeast(0)).put("output",d.optLong("output")+output.coerceAtLeast(0))
        days.keys().asSequence().toList().sorted().dropLast(KEEP_DAYS).forEach {days.remove(it)}
        return days
    }

    fun of(days:JSONObject,day:String):Day {
        val d=days.optJSONObject(day)?:return Day()
        return Day(d.optInt("calls"),d.optLong("input"),d.optLong("cached"),d.optLong("output"))
    }

    /** The last [count] days, newest first, with their numbers. */
    fun recent(days:JSONObject,count:Int):List<Pair<String,Day>> =
        days.keys().asSequence().toList().sortedDescending().take(count).map {it to of(days,it)}

    /** A short human number: 1,234 → "1.2K", 3,400,000 → "3.4M". */
    fun short(n:Long):String = when {
        n>=1_000_000 -> String.format(Locale.US,"%.1fM",n/1_000_000.0)
        n>=1_000 -> String.format(Locale.US,"%.1fK",n/1_000.0)
        else -> n.toString()
    }

    fun summary(d:Day):String = if(d.calls==0) "Nothing yet today" else d.calls.toString()+" AI calls · "+short(d.input)+" read · "+short(d.output)+" written"

    /** Whether a job on a metered service has used its share and must wrap up. */
    fun jobOver(metered:Boolean,jobTokens:Long)=metered && jobTokens>=JOB_CAP

    /** True once a day, on the first reply that takes a metered service over the warning line. */
    fun shouldWarn(metered:Boolean,d:Day,alreadyWarnedToday:Boolean)=metered && !alreadyWarnedToday && d.total>=DAY_WARN
}

/**
 * The limits on how many texts one person may get in a day, and the daily choices Settings offers. (The log and the rules that use them are in [SendLedger].)
 * A bot that goes wrong, or is steered by something it read, could otherwise text everyone in the contacts.
 */
object SmsCap {
    const val PER_PERSON_GENERIC=3
    const val PER_PERSON_OWNER=10
    val choices=listOf(10,30,100,500)
}
