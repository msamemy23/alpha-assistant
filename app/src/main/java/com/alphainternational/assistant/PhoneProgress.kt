package com.alphainternational.assistant

/** Small rules about jobs that drive the owner's apps. */
object PhoneJobRules {
    /** The bookkeeping tools a quick phone job does without: each call is a whole model round. */
    val PLAN_TOOLS=setOf("plan_set","plan_update")
    private val listWords=Regex("\\b(?:first|then|after that|afterwards|next|finally|and also|once that)\\b|\\n\\s*(?:\\d+[.)]|[-*•])",RegexOption.IGNORE_CASE)
    /**
     * What is already known about an app that a bot would otherwise find out the slow way (a few failed rounds each time). Said once, with the "opened" note.
     * Samsung Notes: on 2026-10-13 a poem took two failed typing tries before the share step worked.
     */
    fun tip(pkg:String):String = when {
        pkg.contains("samsung.android.app.notes") -> " Tip: this editor refuses typed text. Put text in with {\"do\":\"share\",\"app\":\"Notes\",\"text\":\"...\"}; on the share screen tap Create note, then tap Title to name it, then the back arrow."
        pkg=="com.android.settings" -> " Tip: use the Search settings box at the top and open the result, instead of browsing pages."
        pkg=="com.google.android.gm" -> " Tip: typing into Gmail's compose fields often fails (live test 2026-10-14). To write an email use phone_action (action email, to, subject, body): it opens Gmail's compose screen already filled in; then, only if the owner asked you to send it, tap Send and check the Sent confirmation."
        pkg.contains("clockpackage") || pkg.contains("deskclock") -> " Tip: for a plain alarm or timer, phone_alarm_set and phone_timer_set are faster than this app."
        else -> ""
    }

    private val usePhone=Regex("\\b(?:use|using|take over|taking over|drive|control|operate)\\s+(?:over\\s+)?my\\s+(?:phone|screen|iphone|android)\\b|\\bdo (?:it|this|that) on my phone\\b|\\bon my phone,? (?:send|post|use|do)\\b",RegexOption.IGNORE_CASE)
    /** The owner said to use their phone (or take it over): the job is done in the phone's own apps, whatever connectors or tools also exist. */
    fun ownerWantsPhone(prompt:String):Boolean = usePhone.containsMatchIn(prompt.take(3000))
    const val USE_PHONE_NOTE="The owner told you to use their phone. Do this whole job in the apps on the phone with screen_do (Gmail, Messages, Facebook and so on), where they are already signed in. Do not use connectors or the browser for it, " +
        "do not ask them to sign in, share, copy or send anything by hand, and do not stop to ask whether to carry on: they asked you to take over, so take over and finish. To write an email on the phone, phone_action (action email, with to, subject and body) opens Gmail already filled in; then tap Send."
    /** A short request without a list of separate jobs in it. */
    fun isQuickJob(prompt:String):Boolean = prompt.trim().let {it.length<=260 && listWords.findAll(it).count()<3}
}

/**
 * Tells whether a job that is driving the owner's apps is getting anywhere, and walks it up a ladder when it is not. It never just gives up and it never just waits.
 *
 * Phone agents get stuck in a few recognisable shapes (the same steps again, the same error again, two screens swapping places, a screen that never changes). Time alone
 * cannot tell a slow job from a stuck one, so progress here means the phone reached a screen this job has not seen before. While that keeps happening nothing is said, however
 * long it takes. When it stops, each warning is one rung higher than the last: change the method, step back and look, take another route, and only then stop and report plainly
 * (what is done, what blocks, the one thing the owner needs to do). A new screen takes the job back to the bottom of the ladder. See also [JobBudget] (the time side).
 */
class PhoneProgress {
    class Verdict(val rung:Int,val pattern:String,val instruction:String,val last:Boolean)
    private class Call(val sig:String,val screen:String,val ok:Boolean,val error:String)

    private val recent=ArrayDeque<Call>()
    private val screens=HashSet<String>()
    private var quiet=0
    var rung=0; private set
    /** The last rung was spoken: the job was told to stop and report, so more phone calls are refused. */
    var finished=false; private set

    /** What a phone call is answered with once the job was told to stop, or null while it may go on. */
    fun refusal():String? = if(finished) "You were told to stop and report: this job got nowhere on the phone. Answer now in plain words: what is done, what blocks, and the one thing the owner needs to do or give you. Do not use the phone again." else null

    /**
     * Notes one phone call: [sig] is what was asked (the steps), [screen] the fingerprint of the screen it ended on, [ok] whether it worked, [error] what failed.
     * Returns a warning when the job is going nowhere, null while it is moving.
     */
    fun record(sig:String,screen:String?,ok:Boolean,error:String=""):Verdict? {
        val shown=screen.orEmpty()
        val fresh=shown.isNotEmpty() && screens.add(shown)
        if(screens.size>400) screens.clear()
        recent.addLast(Call(sig,shown,ok,error.take(60)))
        while(recent.size>8) recent.removeFirst()
        if(fresh) {
            quiet=0
            if(ok) rung=0
            return null
        }
        quiet++
        val pattern=shape()
        // The first warning comes after two quiet calls; each later rung gives the bot three tries on its new method before climbing.
        val need=if(rung==0) 2 else 3
        if(!((pattern!=null && quiet>=need) || quiet>=need+1)) return null
        quiet=0
        rung=minOf(rung+1,LAST)
        if(rung==LAST) finished=true
        return Verdict(rung,pattern?:"the screen has not shown anything new for several tries",instruction(rung,pattern?:"nothing new has appeared on the screen for several tries"),rung==LAST)
    }

    /** The shape of the last few calls, in plain words (null when none of them fits). */
    private fun shape():String? {
        val l=recent.toList()
        if(l.size>=3 && l.takeLast(3).all {it.sig==l.last().sig}) return "you sent the same steps three times"
        if(l.size>=4) {
            val a=l[l.size-4].screen;val b=l[l.size-3].screen
            if(a!=b && l[l.size-2].screen==a && l[l.size-1].screen==b) return "the phone keeps flipping between the same two screens"
        }
        if(l.size>=3 && l.takeLast(3).all {!it.ok && it.error.isNotEmpty() && it.error==l.last().error}) return "the same step failed three times with the same problem"
        if(l.size>=3 && l.takeLast(3).map {it.screen}.distinct().size==1) return "the screen did not change over your last three tries"
        return null
    }

    companion object {
        const val LAST=4
        fun instruction(rung:Int,why:String):String = when(rung) {
            1->"Nothing is moving: $why. Do not send that again. In one line, decide what you think is blocking you, then change the method (other words from the screen list, scroll, go back, the share step for text, or a different way into the same app)."
            2->"Still nothing new. Step back: call screen_look to see the real screen, say the goal again, and plan a different route from where the phone actually is. If you wandered into the wrong place, press home and start that part again."
            3->"Still stuck. Take another route altogether: another app or the website (browser tools) for the same result, a tool that does it without the screen (sms_send, phone_action, phone_alarm_set, the calendar), or look up how that app does it (web search). Keep what you already finished."
            else->"This is not working. Stop trying and answer now, in plain words: what is done, what blocks it, and the one thing the owner needs to do or give you. Hand over whatever you made (the text, ready to copy). Do not use the phone again."
        }
    }
}
