package com.alphainternational.assistant

import org.json.JSONObject

/** What one automatic job is allowed to do beyond reading: who it may send to (usually nobody) and what it is about. */
data class ProGuard(val allowSend:Boolean=false,val recipients:Set<String> = emptySet(),val depth:Int=0,val goalId:String="",val followupId:String="",
    val tellEither:Boolean=false,val triggerId:String="",val noticeId:String="")

/**
 * The lock-down for automatic jobs (a follow-up, a trigger, a notice's button, the morning brief). Jobs set off by outside text (a trigger, a notice) get
 * Away mode's treatment: a short list of tools, no web, and sends only to addresses the owner named. Jobs set off by the clock get everything except
 * sending, paying, deleting, publishing and changing the bots themselves. All of this is checked in code, whatever the AI asks for.
 */
object ProactiveRules {
    const val FOLLOWUP="pro-followup"
    const val BRIEF="pro-brief"
    const val TRIGGER="pro-trigger"
    const val NOTICE="pro-notice"
    /** What an automatic job answers with when there is nothing worth showing the owner. */
    const val QUIET="NOTHING_TO_REPORT"

    fun isJob(from:String)=from.startsWith("pro-")
    /** Jobs that work on text from outside. */
    fun strict(from:String)=from==TRIGGER || from==NOTICE
    /** No hosted web search and no page tools: a page could steer the job. */
    fun noWeb(from:String)=strict(from)
    /** Outside text must not be turned into the bot's memory by the learning step. */
    fun noLearn(from:String)=strict(from)
    /** A failed morning brief is not worth a "sorry" text. */
    fun silentStop(from:String)=from==BRIEF

    private val reads=setOf("memory_search","skills_list","skill_read","capabilities","files_list","file_read","file_view","gmail_search","gmail_read","gmail_drafts_search","gmail_read_draft",
        "calendar_list","calendar_events","drive_list","docs_read","sheets_read","tasks_lists","tasks_read","contacts_read","supabase_read","supabase_management_list","supabase_management_call",
        "telnyx_calls","telnyx_messages","telnyx_lookup","telnyx_numbers","run_code","mcp_list","mcp_call","tool_list","goal_list","goal_update")
    private val drafts=setOf("gmail_draft","file_write","file_append","file_edit","document_pdf","tasks_create","invoice_pdf")
    private val sends=setOf("gmail_send","sms_send")
    /** Allowed for a job set off by outside text. */
    val strictAllowed:Set<String> = reads+drafts

    /** Never for an automatic job: they stop to ask the owner, change the bots, publish, or act outside the phone. */
    private val never=setOf("task_need_user","task_need_connection","notify_important","phone_action","react","gif_send","trigger_create","trigger_delete","bot_create","bot_update",
        "skill_save","skill_delete","tool_create","routine_create","routine_delete","site_publish","supabase_update","supabase_sample_invoice","social_prepare","browser_click","browser_fill",
        "browser_press_enter","browser_signin","browser_request_login","away_decide","run_shell","install_package","media_save")
    private val risky=Regex("(?i)(delete|remove|publish|purchase|refund|transfer|checkout|deploy(?!ments?)|\\bpay\\b|_pay_|buy)")

    /** Whether the AI is shown this tool at all in this kind of job. (A call's details are checked again by [refusal].) */
    fun visible(from:String,name:String,guard:ProGuard?):Boolean {
        if(!isJob(from)) return true
        if(name in sends) return guard?.allowSend==true && guard.recipients.isNotEmpty()
        if(strict(from)) return name in strictAllowed
        return name !in never && !risky.containsMatchIn(name)
    }

    /**
     * Why this call may not happen (a sentence the AI reads), or null if it may. [lookOnly]: a database call that only reads. [mcpRead]: a connected-app
     * call its server marks read-only. A send needs the trigger or follow-up to allow it, and goes only to an address the owner named.
     */
    fun refusal(from:String,name:String,args:JSONObject,guard:ProGuard?,lookOnly:Boolean=false,mcpRead:Boolean=false):String? {
        if(!isJob(from)) return null
        if(name=="supabase_management_call") return if(lookOnly) null else "An automatic job may only look in the database, never change it."
        if(name=="mcp_call") return if(mcpRead) null else "An automatic job may only read from connected apps, never change anything in them."
        if(name in sends) {
            if(guard==null || !guard.allowSend) return "An automatic job can't send anything by itself. Save it as a draft and say it is waiting for the owner."
            val raw=args.optString("to");val to=Recipients.normal(raw)
            // one named person at a time: a list of addresses is refused even if the first one is allowed
            val several=Recipients.normalAll(raw).size>1 || raw.count {it=='@'}>1
            return if(to!=null && !several && to in guard.recipients) null else "That address or number is not one this job may send to. Save it as a draft instead."
        }
        if(strict(from)) return if(name in strictAllowed) null else "Not allowed in an automatic job that works on text from outside."
        if(name in never || risky.containsMatchIn(name)) return "Not allowed in an automatic job."
        return null
    }

    /** The guard for a job whose details were lost (the app restarted): the strictest one for its kind. */
    fun fallbackGuard(from:String)=ProGuard()
}
