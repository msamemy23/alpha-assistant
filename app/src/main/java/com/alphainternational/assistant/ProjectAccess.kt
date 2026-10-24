package com.alphainternational.assistant

import org.json.JSONObject

object ProjectAccess {
    fun supabase(project:String,token:String):JSONObject {
        val ref=project.trim()
        require(ref.matches(Regex("[a-z0-9]{20}"))) { "Enter the project's 20-character ID from Supabase." }
        val secret=token.trim()
        require(secret.startsWith("sbp_") && secret.length>20 && secret.none(Char::isWhitespace)) {
            "Use a Supabase account access token. Project publishable, anon and service-role keys do not sign in to project management."
        }
        return obj("project" to ref,"token" to secret,
            "url" to "https://mcp.supabase.com/mcp?project_ref=$ref&features=database,debugging,development,functions,storage,docs")
    }
    fun vercelProjects(visible:List<String>,requested:List<String>):List<String> {
        require(visible.isNotEmpty()) { "This token cannot access any projects. Choose a project when creating the Vercel token." }
        val allowed=requested.ifEmpty { visible }.distinct()
        require(allowed.all { it in visible }) { "An allowed project id is not accessible to this token." }
        return allowed
    }
}
