package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * A small visible plan for jobs with several steps (plan_set / plan_update). It is kept in [TaskRecord.plan] next to the old one-line "the request is
 * the plan" entry (kind "live"); only entries with kind "step" are the model's plan. The phone adds one line at the end of every round ("Progress: 2 of 5
 * done, now: …") and shows it on the live card, and nudges once at the end if steps are still open. Models don't have to use it for a one-step job.
 */
object Plan {
    const val KIND="step"
    val statuses=setOf("open","doing","done","skipped")
    data class Item(val id:String,val text:String,val status:String)
    /** What the screen shows for a running job: "2 of 5 done", the step it is on, and its number. */
    data class Progress(val done:Int,val total:Int,val now:String,val step:Int,val items:List<Item>) {
        /** For the live card: "Step 3 of 6: Compare prices" (blank when every step is done). */
        val live:String get()=if(now.isBlank()) "" else "Step $step of $total: $now"
        val finished:Boolean get()=total>0 && done>=total
    }

    fun items(planJson:String):List<Item> = runCatching {
        JSONArray(planJson).objects().filter {it.optString("kind")==KIND}.map {Item(it.optString("id"),it.optString("description"),it.optString("status","open").takeIf {s->s in statuses}?:"open")}
    }.getOrDefault(emptyList())

    /** Validates the model's steps (plain words, 2 to 12) and returns the plan to save: the first step is "doing", the rest "open". */
    fun set(steps:List<String>):JSONArray {
        val clean=steps.map {it.replace(Regex("\\s+")," ").trim()}.filter {it.isNotBlank()}
        require(clean.size>=2) {"A plan needs at least two steps. For a one-step job, just do it."}
        require(clean.size<=12) {"Keep the plan to 12 steps or fewer; group small steps together."}
        require(clean.all {it.length<=140}) {"Keep each step to one short line (under 140 characters)."}
        return arr(clean.mapIndexed {i,text->obj("id" to (i+1).toString(),"description" to text,"kind" to KIND,"status" to if(i==0) "doing" else "open")})
    }

    /** The step ids are "1", "2"…; "step 2" and "#2" are understood too. Returns the new plan, or fails with a plain message. */
    fun update(planJson:String,id:String,status:String):JSONArray {
        val list=items(planJson)
        require(list.isNotEmpty()) {"There is no plan yet. Use plan_set first (only for jobs with 3 or more steps)."}
        val wanted=status.trim().lowercase()
        require(wanted in statuses) {"Status must be one of: open, doing, done, skipped."}
        val key=Regex("\\d+").find(id)?.value?.toIntOrNull()?.toString()?:id.trim()
        require(list.any {it.id==key}) {"There is no step "+id.trim()+". Steps: "+list.joinToString {it.id}+"."}
        val updated=list.map {if(it.id==key) it.copy(status=wanted) else it}
        return arr(updated.map {obj("id" to it.id,"description" to it.text,"kind" to KIND,"status" to it.status)})
    }

    fun progress(planJson:String):Progress? {
        val list=items(planJson)
        if(list.isEmpty()) return null
        val done=list.count {it.status=="done" || it.status=="skipped"}
        val now=list.firstOrNull {it.status=="doing"}?:list.firstOrNull {it.status=="open"}
        return Progress(done,list.size,now?.text.orEmpty(),if(now==null) list.size else list.indexOf(now)+1,list)
    }

    /** The one line added at the end of the model's input every round. Blank when there is no plan. */
    fun line(planJson:String):String {
        val p=progress(planJson)?:return ""
        return if(p.finished) "Progress: all ${p.total} steps are done." else "Progress: ${p.done} of ${p.total} done, now: ${p.now}."
    }

    fun open(planJson:String):List<Item> = items(planJson).filter {it.status=="open" || it.status=="doing"}

    /** What the model is told, once, when it tries to finish with steps still open. */
    fun nudge(open:List<Item>):String = "Before you finish: these steps of your plan are still open: "+open.joinToString("; ") {it.id+". "+it.text}+
        ". Do them now, or mark each one done or skipped with plan_update if it is already finished or not needed, then give the owner your answer."
}
