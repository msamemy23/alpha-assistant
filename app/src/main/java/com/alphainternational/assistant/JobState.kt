package com.alphainternational.assistant

/**
 * What the agent loop remembers about one run of a job, in memory only (a restarted job starts a fresh one and rebuilds what it needs from its saved history).
 * It holds the things that must stay fixed while the job runs (the tool list and the instructions, so every round starts with identical text), the clock that
 * decides when the job must wrap up, and the counters that keep the finish gate from ever looping.
 */
class JobState(val startedAt:Long) {
    // ---- fixed for the run (see Prefix) ----
    /** The tool groups switched on, in their fixed order; grows only. */
    var groups:List<String> = emptyList()
    /** The tools sent every round: the everyday tools, then each group's tools in the order the groups were switched on. Grows only, at the end. */
    var tools:List<Tool> = emptyList()
    /** The instructions sent every round; rebuilt only when a group is switched on. */
    var brief:String=""
    /** The job continues a website, app or game build, so it gets a build's long time budget even though its own words don't say so. */
    var inheritsBuild=false

    // ---- the clock ----
    /** How many results the job had gathered when last counted, and when that number last went up. */
    var results=0
    var lastResultAt=startedAt
    /** Counts [count] results at time [now]; true when the number went up (the job is making progress). */
    fun noteResults(count:Int,now:Long):Boolean {
        if(count<=results) return false
        results=count;lastResultAt=now
        return true
    }
    /** The wrap-up notice was sent (the time or step limit was near), and the tools were taken away at the stop time. */
    var wrapUpSent=false
    var toolsTaken=false
    /** Tokens this job has used so far (read plus written), for the cap on services that bill by the token. */
    var tokens=0L
    /** Whether the job ended because of the time or step limit and still had work left: the answer says so, and the chat offers Keep going. */
    fun cutShort(planHasOpenSteps:Boolean)=toolsTaken || (wrapUpSent && planHasOpenSteps)

    // ---- recovery ----
    /** Whether each recent tool result worked (newest last); a run of failures makes the job think harder. */
    val outcomes=ArrayDeque<Boolean>()
    fun noteOutcome(ok:Boolean) {outcomes.addLast(ok);while(outcomes.size>8) outcomes.removeFirst()}
    var escalations=0
    /** Replies cut off by the length limit, one after another. */
    var cutOffs=0
    /** A one-off note for the next round only (for example "your reply was cut off"). */
    var note:String?=null
    /** Whether this job's phone work is getting anywhere, and the ladder it is walked up when it is not (see [PhoneProgress]). */
    val phone=PhoneProgress()
    /** The saved routes shown to this job (chosen once, when the phone tools are on), so a good or bad end can be counted for them (see [PhoneRoutes]). */
    var routes:List<PhoneRoutes.Route>?=null
    /** Colleague jobs this job has started (see [TeamRules.MAX_HELPER_JOBS]): a bot that keeps sending the same request round again is stopped from it. */
    var helperJobs=0

    // ---- the finish gate ----
    /** Extra rounds the gate has added (never more than [Verify.MAX_REPAIRS]), and which kinds were already used. */
    var repairs=0
    var claimsRepaired=false
    var nudged=false
    var criticRan=false
    /** The answer claimed a file or picture was attached with nothing attached, and the bot was asked once to attach it or say otherwise. */
    var attachRepaired=false
    /** The owner asked for a kind of file (Word, Excel, a js file...) and none was made, and the bot was asked once to make it. */
    var filesRepaired=false
    /** The answer still claims something no result shows, after the model was asked once. */
    var unconfirmed=false
    /** The next round is a checking round (it thinks one notch harder than the quickest setting). */
    var verifying=false
}
