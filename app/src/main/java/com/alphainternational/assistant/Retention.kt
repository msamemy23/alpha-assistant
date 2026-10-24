package com.alphainternational.assistant

/**
 * What stays in memory and what moves to the archive, so a year of use doesn't make Alpha slower or run the phone out of space.
 * Chat texts: the newest [MESSAGE_CAP] stay; older ones leave a whole chat file (100 texts) at a time, into an archive that is still searched.
 * Finished requests: one still shown in the chat, or among the newest [KEEP_TASKS], or younger than [MIN_AGE_DAYS], stays with everything it has;
 * the rest keep just a title, the request and their digest in the archive.
 */
object Retention {
    const val MESSAGE_CAP=2000
    const val KEEP_TASKS=300
    const val MIN_AGE_DAYS=30
    /** A history smaller than this (encrypted, on disk) is read before the first screen; a bigger one is read in the background. */
    const val SYNC_LOAD_BYTES=1_500_000L
    private val finished=setOf("Completed","Partial","Cancelled")

    /** The chat after the oldest whole files are taken out, and those files (in order, each exactly one chat file long). */
    fun capMessages(all:List<Message>,cap:Int=MESSAGE_CAP,segment:Int=StateFiles.SEGMENT):Pair<List<Message>,List<List<Message>>> {
        if(all.size<=cap+segment) return all to emptyList()
        val drop=((all.size-cap)/segment)*segment
        val gone=all.take(drop).chunked(segment)
        return all.drop(drop) to gone
    }

    /** The finished requests that can leave memory now. [referenced]: ids of requests that a chat text still in memory belongs to. */
    fun retirable(tasks:List<TaskRecord>,referenced:Set<String>,now:Long,keepNewest:Int=KEEP_TASKS,minAgeDays:Int=MIN_AGE_DAYS):List<TaskRecord> {
        if(tasks.size<=keepNewest) return emptyList()
        val newest=tasks.sortedByDescending {it.created}.take(keepNewest).map {it.id}.toSet()
        val cutoff=now-minAgeDays*86_400_000L
        return tasks.filter {it.status in finished && it.id !in referenced && it.id !in newest && it.created<cutoff}
    }
}
