package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * What the bots did, in one list the owner can read, kept on the phone (newest last, at most [MAX] entries). A few of the things a bot can do can be put back
 * (a routine, a trigger or a taught skill it made); a text that went out or a page that was published cannot, and the list says so plainly.
 */
object ActionLog {
    const val MAX=400

    /** [undo] is "kind:id" while the action can still be put back (routine, trigger, skill), and empty otherwise. [undone] is set once it was. */
    data class Entry(val id:String,val at:Long,val bot:String,val tool:String,val title:String,val detail:String,val ok:Boolean,val undo:String="",val undone:Boolean=false) {
        val canUndo:Boolean get()=undo.isNotBlank() && !undone
        fun json():JSONObject=JSONObject().put("id",id).put("at",at).put("bot",bot).put("tool",tool).put("title",title).put("detail",detail).put("ok",ok).put("undo",undo).put("undone",undone)
    }

    /** The tools whose use belongs in the list: everything that sends, writes, publishes, changes a setup or acts on a page or the phone. */
    val logged=setOf("sms_send","gmail_send","gmail_draft","calendar_create","drive_create_text","docs_create","sheets_create","sheets_append","tasks_create","vercel_preview",
        "site_publish","supabase_update","supabase_management_call","supabase_sample_invoice","browser_click","browser_fill","browser_press_enter","mcp_call","file_write","file_append",
        "file_edit","section_replace","document_pdf","invoice_pdf","routine_create","routine_delete","trigger_create","trigger_delete","skill_save","skill_delete","tool_create",
        "bot_create","bot_update","phone_action","screen_do","screen_confirm","media_save","social_prepare")

    fun shouldLog(tool:String,review:Boolean)=review || tool in logged

    /** What undoing this would be, "kind:id", when the result names the thing that was made; empty when the action cannot be put back. */
    fun undoFor(tool:String,result:JSONObject):String {
        val id=result.optString("id").takeIf {it.isNotBlank()}?:return ""
        return when(tool) {"routine_create"->"routine:$id";"skill_save"->"skill:$id";"trigger_create"->"trigger:$id";else->""}
    }

    fun parse(log:JSONArray):List<Entry> = (0 until log.length()).mapNotNull {i->
        log.optJSONObject(i)?.let {o->Entry(o.optString("id"),o.optLong("at"),o.optString("bot"),o.optString("tool"),o.optString("title"),o.optString("detail"),
            o.optBoolean("ok",true),o.optString("undo"),o.optBoolean("undone"))}
    }

    /** The log with [entry] added, oldest dropped past [MAX]. */
    fun add(log:JSONArray,entry:Entry):JSONArray {
        val all=parse(log)+entry
        return JSONArray().also {out->all.takeLast(MAX).forEach {out.put(it.json())}}
    }

    /** The log with this entry marked as put back. */
    fun markUndone(log:JSONArray,id:String):JSONArray = JSONArray().also {out->parse(log).forEach {e->out.put((if(e.id==id) e.copy(undone=true) else e).json())}}

    /** One plain line: "Sent a text · Camry ready · Alpha · 3:15 PM". */
    fun line(e:Entry,time:String)=listOf(e.title,e.detail.takeIf {it.isNotBlank()},e.bot,time).filterNotNull().joinToString(" · ")
}
