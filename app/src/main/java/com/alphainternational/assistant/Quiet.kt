package com.alphainternational.assistant

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * How the app looks and how talkative it is. Kept in its own saved group ("ui") so no existing setting or default changes: an old save simply
 * has no "ui" group and gets the defaults below (dark look, not quiet).
 */
object UiPrefs {
    /** "dark" (the default), "light" or "system" (follow the phone). */
    var appearance by mutableStateOf("dark")
        private set
    /** When on, only things that need the owner (an OK, a sign-in) or that the owner asked for reach the phone's notifications. */
    var quiet by mutableStateOf(false)
        private set

    val looks=listOf("dark" to "Dark","light" to "Light","system" to "Follow my phone")
    fun lookLabel(key:String)=looks.firstOrNull {it.first==key}?.second?:"Dark"

    /** Reads the saved group (called once when the app starts). Anything unknown falls back to the defaults. */
    fun load(saved:JSONObject) {
        appearance=saved.optString("appearance","dark").takeIf {key->looks.any {it.first==key}}?:"dark"
        quiet=saved.optBoolean("quiet",false)
    }
    fun setAppearance(c:Controller,value:String) {
        appearance=value.takeIf {key->looks.any {it.first==key}}?:"dark"
        c.saveConfig("ui",c.config("ui").put("appearance",appearance))
    }
    fun setQuiet(c:Controller,on:Boolean) {
        quiet=on
        c.saveConfig("ui",c.config("ui").put("quiet",on))
    }
}

/**
 * A daily limit on the notifications that are not urgent, so Alpha never nags. What needs the owner (an OK, a sign-in, a job that stopped) and
 * the answer to something the owner asked for always get through; everything else (routine reports, "Away turned off", background nudges) counts
 * against about eight a day, and none of it is shown while Quiet is on.
 */
object NoticeBudget {
    const val DAILY_CAP=8
    private val taskId=Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    /**
     * Whether a notification is one the owner is waiting on. [id] is the notification's own key: "stop:" and "reply:" are a job that stopped or answered,
     * "team:" is a group answer, and a bare job id is "this job needs you". Everything else (routines, "away-off", new kinds from other parts) is not urgent.
     */
    fun urgent(id:String,destination:String):Boolean = destination=="review" ||
        listOf("stop:","reply:","team:","handoff:","app-signin","supabase-signin","away:").any {id.startsWith(it)} || taskId.matches(id)

    /** The rule: urgent always; otherwise only when the owner isn't Quiet and fewer than [cap] went out today. */
    fun allow(quiet:Boolean,urgent:Boolean,sentToday:Int,cap:Int=DAILY_CAP)=urgent || (!quiet && sentToday<cap)

    fun dayKey(millis:Long,zone:ZoneId=ZoneId.systemDefault()):String=Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toString()

    /** How many non-urgent notifications went out on [day], from the saved "ui" group. */
    fun sentOn(saved:JSONObject,day:String):Int=saved.optJSONObject("notices")?.takeIf {it.optString("day")==day}?.optInt("n")?:0

    /** Decides for a real notification, and counts it. Called by [Updates.notify]. True means show it. */
    fun allowNow(context:Context,id:String,destination:String):Boolean {
        val urgent=urgent(id,destination)
        if(urgent) return true
        val c=(context.applicationContext as? AlphaApplication)?.controller?:return true
        val saved=c.config("ui")
        val today=dayKey(System.currentTimeMillis())
        val sent=sentOn(saved,today)
        val ok=allow(UiPrefs.quiet,false,sent)
        if(ok) c.saveConfig("ui",saved.put("notices",obj("day" to today,"n" to sent+1)))
        return ok
    }
}
