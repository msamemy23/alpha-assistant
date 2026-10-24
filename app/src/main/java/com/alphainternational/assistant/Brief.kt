package com.alphainternational.assistant

/**
 * Small helpers for the text the model is told at the start of a job (the pure part of Controller.instructions, so it can be tested).
 * The instructions mark the sections that belong to a tool group with [[group]] … [[/group]] lines; a job keeps only the sections of the
 * groups it has switched on, and the marker lines never reach the model.
 */
object Brief {
    private val section=Regex("^[ \\t]*\\[\\[(\\w+)\\]\\][ \\t]*\\n(.*?)^[ \\t]*\\[\\[/\\1\\]\\][ \\t]*\\n?",setOf(RegexOption.MULTILINE,RegexOption.DOT_MATCHES_ALL))

    /** [text] with the sections of groups that are not in [on] taken out, and the markers of the others removed. */
    fun sections(text:String,on:Collection<String>):String = section.replace(text) {m->if(m.groupValues[1] in on) m.groupValues[2] else ""}

    /** The group markers left in a text (empty when [sections] has done its work). */
    fun markersIn(text:String)=Regex("\\[\\[/?\\w+\\]\\]").findAll(text).map {it.value}.toList()

    /** What every job is told about its tools and its plan (a few lines; the details are in the tool descriptions). */
    val NOTES:String = "You start with the everyday tools. Building websites, games, video and sound, the phone's command line, Vercel, Telnyx, saving to Google (drafts, sending email, " +
        "calendar events, Docs, Sheets), connected apps, PDF documents, the browser and routines come on by themselves when the request is about them. If you need one that " +
        "isn't there, switch it on with tools_enable and carry on; never tell the owner you can't do something just because its tool isn't listed.\n" +
        "For a job with 3 or more separate steps, write a short plan first with plan_set (plain words the owner would understand), keep it current with plan_update, and finish " +
        "every step before you answer. Skip the plan for simple requests."
}
