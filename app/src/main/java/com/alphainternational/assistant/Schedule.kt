package com.alphainternational.assistant

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Calendar-style routine schedules: "weekdays at 08:00", "mon,wed,fri at 17:30" or every N hours. */
object Schedule {
    private val names=mapOf("mon" to DayOfWeek.MONDAY,"tue" to DayOfWeek.TUESDAY,"wed" to DayOfWeek.WEDNESDAY,"thu" to DayOfWeek.THURSDAY,
        "fri" to DayOfWeek.FRIDAY,"sat" to DayOfWeek.SATURDAY,"sun" to DayOfWeek.SUNDAY)
    private val weekdays=setOf(DayOfWeek.MONDAY,DayOfWeek.TUESDAY,DayOfWeek.WEDNESDAY,DayOfWeek.THURSDAY,DayOfWeek.FRIDAY)

    /** Normalizes a days expression; throws for anything that is not a recognizable set of days. */
    fun days(value:String):String {
        val text=value.trim().lowercase().ifBlank {"daily"}
        if(text in setOf("daily","every day","everyday"))return "daily"
        if(text in setOf("weekdays","weekday","mon-fri"))return "weekdays"
        if(text in setOf("weekends","weekend","sat,sun"))return "weekends"
        val picked=text.split(Regex("[,\\s]+")).filter {it.isNotBlank()}.map {
            names[it.take(3)]?:throw IllegalArgumentException("Use daily, weekdays, weekends or day names like mon,wed,fri.")
        }.toSet()
        require(picked.isNotEmpty()) {"Choose at least one day."}
        return DayOfWeek.entries.filter {it in picked}.joinToString(",") {it.name.take(3).lowercase()}
    }
    fun daySet(value:String):Set<DayOfWeek> = when(val text=days(value)) {
        "daily"->DayOfWeek.entries.toSet()
        "weekdays"->weekdays
        "weekends"->setOf(DayOfWeek.SATURDAY,DayOfWeek.SUNDAY)
        else->text.split(',').map {names.getValue(it)}.toSet()
    }
    /** Accepts 24-hour "08:00"/"8:00" and "8:00 am"/"5pm". Returns canonical HH:mm. */
    fun time(value:String):String {
        val text=value.trim().lowercase().replace(".","")
        val match=Regex("^(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?$").find(text)?:throw IllegalArgumentException("Use a time like 08:00 or 5:30 pm.")
        var hour=match.groupValues[1].toInt();val minute=match.groupValues[2].ifBlank {"0"}.toInt();val half=match.groupValues[3]
        require(minute in 0..59) {"Use minutes from 00 to 59."}
        if(half.isNotEmpty()) {require(hour in 1..12) {"Use an hour from 1 to 12 with am/pm."};hour=hour%12+(if(half=="pm")12 else 0)}
        else require(match.groupValues[2].isNotBlank() && hour in 0..23) {"Use a time like 08:00 or 5:30 pm."}
        return "%02d:%02d".format(hour,minute)
    }
    /** The first matching day/time strictly after [after], in [after]'s zone. */
    fun next(after:ZonedDateTime,time:String,days:String):ZonedDateTime {
        val at=LocalTime.parse(time(time));val allowed=daySet(days)
        for(offset in 0..7) {
            val day=after.toLocalDate().plusDays(offset.toLong())
            if(day.dayOfWeek !in allowed)continue
            val candidate=day.atTime(at).atZone(after.zone)
            if(candidate.isAfter(after))return candidate
        }
        error("No upcoming run time.")
    }
    fun describe(routine:Routine):String {
        if(routine.time.isBlank())return if(routine.hours==24)"Every day" else "Every "+routine.hours+" hours"
        val clock=LocalTime.parse(routine.time).format(DateTimeFormatter.ofPattern("h:mm a",Locale.US))
        val when_=when(val d=days(routine.days)) {
            "daily"->"Every day";"weekdays"->"Weekdays";"weekends"->"Weekends"
            else->d.split(',').joinToString(", ") {it.replaceFirstChar {c->c.uppercase()}}
        }
        return "$when_ at $clock"
    }
}
