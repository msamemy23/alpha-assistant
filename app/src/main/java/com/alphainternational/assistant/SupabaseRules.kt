package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * When a Supabase action needs the owner's OK. The owner's wish is clear: ask once in chat and it's done. So looking never asks, and a
 * change the owner asked for just runs. A change still asks only when something the bot read from outside (a web page, an email, a file)
 * could have steered it, or when an unattended routine wants to change data.
 */
object SupabaseRules {
    private val readTools=Regex("^(list_|get_|search_)[a-z_]*$|^(generate_typescript_types|query_logs)$")
    private val startsLikeARead=Regex("^(select|with|explain|show|values|table)\\b",RegexOption.IGNORE_CASE)
    // Anything that writes, defines, locks, or has side effects. Plain column names like created_at, updated_at, owner or settings don't match.
    private val changes=Regex("\\b(insert|update|delete|drop|alter|create|grant|revoke|truncate|copy|merge|into|call|do|vacuum|reindex|refresh|lock|notify|listen|unlisten|set|reset)\\b" +
        "|pg_terminate|pg_cancel|pg_reload|pg_notify|pg_sleep|pg_read|pg_ls|pg_advisory|pg_stat_reset|set_config|nextval|setval|lo_import|lo_export|dblink|txid_",RegexOption.IGNORE_CASE)

    /** A look that can't change anything: listing, getting, searching docs, logs, or a single SELECT. */
    fun readOnly(tool:String,args:JSONObject):Boolean {
        if(readTools.matches(tool)) return true
        if(tool!="execute_sql") return false
        val sql=args.optString("query").trim().trimEnd(';').trim()
        if(sql.isBlank() || sql.contains(';') || sql.contains("--") || sql.contains("/*")) return false
        return startsLikeARead.containsMatchIn(sql) && !changes.containsMatchIn(sql)
    }

    /** The owner's own words name the database, so a change is exactly what they asked for. */
    fun ownerNamedIt(request:String)=Regex("supabase|database|\\bsql\\b|\\btables?\\b",RegexOption.IGNORE_CASE).containsMatchIn(request)

    /** Whether something the bot read from outside could have steered it: web search, a page, email, a file, calendar, docs, an app. */
    fun tainted(history:String):Boolean {
        val untrusted=Regex("^(web_fetch|browser_[a-z_]+|gmail_[a-z_]+|mcp_call|file_read|file_view|drive_[a-z_]+|calendar_[a-z_]+|docs_read|sheets_read|tasks_[a-z_]+|contacts_read|social_prepare|" +
            "screen_[a-z_]+|youtube_[a-z_]+|image_search|page_screenshot|telnyx_[a-z_]+)$")
        return runCatching {
            JSONArray(history).objects().any {item->
                item.optString("type")=="web_search_call" ||
                    (item.optString("type")=="function_call" && untrusted.matches(item.optString("name").substringAfterLast('.')))
            }
        }.getOrDefault(true)
    }

    /** The whole rule in one place. */
    fun needsCard(readOnly:Boolean,routine:Boolean,tainted:Boolean,ownerNamedIt:Boolean)=when {
        readOnly->false
        routine->true
        tainted->!ownerNamedIt
        else->false
    }
}
