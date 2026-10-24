package com.alphainternational.assistant

/**
 * The ways in to Alpha that are not the app icon: launcher shortcuts, the Quick Settings tile, the home-screen widget and notifications. They all send
 * the same small "destination" word to MainActivity, and these pure rules decide what a word means, so every route is tested without a phone.
 */
object Doors {
    /** Every destination an intent may carry. The first seven are the older ones notifications use; the last three are the newer front doors. */
    val destinations=setOf("chat","tasks","call","connect","review","team","away","goals","proactive","ask","talk","away_toggle")

    /**
     * What a launch means: the destination word, or null for a plain open of the app. [destination] is the intent's own extra; the two flags are the
     * older ways a notification said "call" and "tasks"; [signInReturn] is the browser handing back from a sign-in page. Unknown words are ignored,
     * because MainActivity can be started by any app and must never act on something it does not know.
     */
    fun resolve(destination:String?,openCall:Boolean=false,openTasks:Boolean=false,signInReturn:Boolean=false):String? {
        val word=when {
            signInReturn->"connect"
            destination!=null->destination.trim().lowercase()
            openCall->"call"
            openTasks->"tasks"
            else->""
        }
        return word.takeIf {it in destinations}
    }

    /** Where the app goes for a destination, so shortcuts, the tile and the widget agree with the screens. [screen] null means stay where you are, [dialog] "" closes any open window, and [composer] is true when the text box should get the cursor. */
    data class Route(val screen:String?,val dialog:String? = null,val composer:Boolean = false)

    /**
     * "ask" lands in the text box (the Home composer once Home has one; until then the chat), "talk" opens the call window and "away_toggle" the Away screen.
     * None of them starts a call or changes a setting by itself: this activity can be started by any app, so a launch alone never turns the microphone on.
     */
    fun route(destination:String):Route? = when(destination) {
        "ask"->Route(ASK_SCREEN,"",composer=true)
        "talk"->Route(null,"call")
        "away","away_toggle"->Route("home/away","")
        "goals"->Route("home/goals","")
        "proactive"->Route("home/proactive","")
        "chat"->Route("home/chat","")
        "tasks"->Route("home/chat/activity","")
        "team"->Route("home/team","")
        "connect"->Route("home/marketplace","")
        "review"->Route("home/chat","")
        "call"->Route(null,"call")
        else->null
    }
    /** The screen "ask" opens: the Team chat (Home has no text box of its own: it is the list of bots, like a messages inbox). */
    const val ASK_SCREEN="home/team"

    /** What the widget and the tile know. */
    data class WidgetState(val running:Int=0,val needsYou:Int=0,val awayOn:Boolean=false,val awayWaiting:Int=0)

    private fun things(n:Int)=if(n==1) "1 thing" else "$n things"
    /** The one line under the widget's title: "Working on 2 things", "1 needs you", "Away on", or "Idle". */
    fun statusLine(s:WidgetState):String {
        val parts=mutableListOf<String>()
        if(s.running>0) parts+="Working on "+things(s.running)
        if(s.needsYou>0) parts+=if(s.needsYou==1) "1 needs you" else "${s.needsYou} need you"
        if(s.awayOn) parts+="Away on"+(if(s.awayWaiting>0) ", ${s.awayWaiting} waiting" else "")
        return parts.joinToString(" · ").ifEmpty {"Idle"}
    }
    /** The word on the widget's Away button. */
    fun awayLabel(on:Boolean)=if(on) "Away: on" else "Away: off"

    /** The bots to offer as long-press shortcuts: the ones used most recently first (never-used ones keep their order), at most [limit]. */
    fun topBots(names:List<String>,lastUsed:Map<String,Long>,limit:Int=2):List<String> =
        names.withIndex().sortedWith(compareByDescending<IndexedValue<String>> {lastUsed[it.value]?:0L}.thenBy {it.index}).map {it.value}.distinct().take(limit)
}
