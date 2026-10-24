package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * The rules for a bot using the owner's other apps. The owner asked for any and all apps, quickly and without being asked every little thing, so the line is drawn where a mistake
 * can't be taken back or where something from the screen could be steering the bot: paying, wiping, security switches, and sending or posting something the owner did not ask for.
 * Never typed by a bot, whatever the app: passwords, PINs, card numbers, codes. Never touched: Alpha itself and the apps that hold passwords and one-time codes (unless the owner allows them).
 */
class PhonePolicy(
    /** Settings, "Bots ask before": "bots" (only when making a bot: the owner's pick), "important" or "all". */
    val approvals:String="bots",
    /** The owner's own request asks for something to be sent, posted or replied to (so a Send tap in it is the owner's wish, not the screen's). */
    val ownerAskedToSend:Boolean=false,
    /** The owner allows the apps that hold passwords and one-time codes to be used. */
    val allowVaults:Boolean=false,
    /** The owner approved this very step on a card. */
    val approved:Boolean=false)

object PhoneSafety {
    enum class Risk(val words:String) {NONE(""),SEND("send or post something"),MONEY("pay or buy something"),DESTRUCTIVE("erase or remove something for good"),SECURITY("change a security setting")}

    sealed class Verdict {
        object Go:Verdict()
        class Confirm(val risk:Risk,val label:String):Verdict()
        class Block(val why:String):Verdict()
    }

    private fun r(p:String)=Regex(p,RegexOption.IGNORE_CASE)
    private val money=r("\\b(pay now|pay|payment|buy now|buy|purchase|place (?:your |my |the )?order|order now|checkout|check out|confirm (?:order|payment|purchase)|donate|transfer|send money|withdraw|cash out|authorize payment|complete (?:order|purchase)|submit order|bid now|place bid|pay with)\\b")
    private val destructive=r("\\b(factory reset|erase all|erase everything|wipe|reset phone|delete account|close account|deactivate|uninstall|clear all data|clear storage|reset all settings)\\b")
    private val security=r("play protect|unknown sources|install unknown|install from unknown|device admin|usb debugging|developer options|screen lock|remove (?:pin|password|fingerprint)|turn off (?:lock|security)|accessibility service")
    private val sending=r("^(?:send|post|tweet|publish|share|reply|reply all|forward|comment|post comment|submit|upload|send message|send email|send now|share now|post now|send to \\w.*)$")

    /** A short button or menu item that throws something away ("Delete", "Remove from device", "Empty trash"). A longer sentence that merely mentions deleting is not one. */
    private val deleting=r("^(?:(?:delete|remove|trash|erase|discard)|(?:delete|remove|erase|discard) (?:all|forever|permanently|everything|chat|conversation|thread|messages?|photos?|videos?|files?|account|contacts?|events?|notes?|drafts?|from (?:this )?device)\\b.{0,16}|permanently delete\\b.{0,16}|empty (?:the )?(?:trash|bin|recycle bin)|move to (?:trash|bin)|clear (?:all|history|chat)\\b.{0,16})$")

    /** What tapping something with this name would do. */
    fun risk(label:String):Risk {
        val l=label.replace(Regex("\\s+")," ").trim()
        if(l.isEmpty()) return Risk.NONE
        return when {
            security.containsMatchIn(l) -> Risk.SECURITY
            destructive.containsMatchIn(l) -> Risk.DESTRUCTIVE
            l.length<=34 && deleting.containsMatchIn(l) -> Risk.DESTRUCTIVE
            l.length<=40 && money.containsMatchIn(l) -> Risk.MONEY
            l.length<=34 && sending.matches(l) -> Risk.SEND
            else -> Risk.NONE
        }
    }

    /** Apps a bot is never to open or touch (null means fine). */
    private val vaults=listOf("1password","bitwarden","lastpass","dashlane","keepass","keeper","nordpass","proton.pass","aegis","authy","authenticator","passwords")
    fun appBlocked(pkg:String,policy:PhonePolicy):String? {
        val id=pkg.lowercase()
        if(id=="com.alphainternational.assistant" || id=="com.alphainternational.assistant.beta") return "That is Alpha itself, so it is left alone."
        if(!policy.allowVaults && vaults.any {id.contains(it)}) return "That app holds passwords or one-time codes, so Alpha does not open it. (Settings, What Alpha can do, lets the owner allow it.)"
        return null
    }

    private val secretField=r("password|passcode|\\bpin\\b|\\bcvv\\b|\\bcvc\\b|security code|verification code|one[- ]time|\\botp\\b|card number|credit card|debit card|\\bssn\\b|social security|routing number|account number|2fa|authenticator")
    /** Why a bot may not type into this field (null means it may). */
    fun typingBlocked(label:String,viewId:String,password:Boolean):String? {
        if(password || secretField.containsMatchIn(label) || secretField.containsMatchIn(viewId.replace('_',' ')))
            return "That is a password, PIN, card or code field. Alpha never types those: the owner enters them themselves."
        return null
    }

    /** Whether the bot may tap something with this name now. A step the owner approved on a card goes ahead unless it is blocked outright. */
    fun judgeTap(label:String,pkg:String,policy:PhonePolicy):Verdict {
        appBlocked(pkg,policy)?.let {return Verdict.Block(it)}
        val risk=risk(label)
        if(risk==Risk.NONE || policy.approved) return Verdict.Go
        return when(risk) {
            Risk.SEND -> if(policy.approvals=="bots" && policy.ownerAskedToSend) Verdict.Go else Verdict.Confirm(risk,label)
            else -> Verdict.Confirm(risk,label)
        }
    }

    // Words that plainly mean "put this in front of someone". "Tell" and "ask" count only when they name someone else ("ask Mike if…"), not "tell me what is on the screen";
    // answer, comment and submit are left out because plenty of requests use them and send nothing.
    private val sendWords=r("\\b(send|text|message|msg|reply|respond|post|tweet|publish|share|e-?mail|dm|forward|(?:tell|ask)\\s+(?!me\\b|you\\b|us\\b|if\\b|whether\\b)\\w+|let\\s+(?!me\\b)\\w+\\s+know)\\b")
    /** Whether the owner's own words ask for something to be sent or posted. */
    fun ownerAskedToSend(prompt:String):Boolean=sendWords.containsMatchIn(prompt.take(4000))

    /** Apps where pressing Enter in a box usually sends what was typed: messages, mail, chat and social apps. */
    private val talkingApps=r("messag|\\bmms\\b|\\bsms\\b|whatsapp|telegram|signal|mail|\\.gm\\b|outlook|slack|discord|teams|instagram|facebook|twitter|\\.x\\.|linkedin|snapchat|tiktok|reddit|threads|viber|wechat|zoom|chat")
    /** Pressing Enter: free in most apps (search boxes), a send in the apps people talk in, so it follows the same rule as a Send button. */
    fun judgeEnter(pkg:String,policy:PhonePolicy):Verdict {
        appBlocked(pkg,policy)?.let {return Verdict.Block(it)}
        if(policy.approved || !talkingApps.containsMatchIn(pkg)) return Verdict.Go
        return if(policy.approvals=="bots" && policy.ownerAskedToSend) Verdict.Go else Verdict.Confirm(Risk.SEND,"Enter")
    }
}

/** One thing a bot asks the phone to do. */
class Step(val op:String,val target:PhoneScreen.Target=PhoneScreen.Target(),val text:String="",val app:String="",val dir:String="",val ms:Long=0,val enter:Boolean=false,val append:Boolean=false) {
    fun json():JSONObject=JSONObject().put("do",op).apply {
        if(target.id.isNotBlank()) put("id",target.id)
        if(target.words.isNotBlank()) put(if(op=="type") "into" else "on",target.words)
        if(text.isNotBlank()) put("text",text)
        if(app.isNotBlank()) put("app",app)
        if(dir.isNotBlank()) put("dir",dir)
        if(ms>0) put("ms",ms)
        if(enter) put("enter",true)
        if(append) put("append",true)
    }
    /** A short plain description for a card or a log ("tap “Send”"). */
    fun describe():String=when(op) {
        "open"->"open "+app
        "share"->"share text with "+app
        "type"->"type “"+text.take(60)+"”"+(if(target.given) " into "+target else "")
        "scroll"->"scroll "+dir.ifBlank {"down"}
        "wait","back","home","recents","notifications","settings_panel","lock","enter","read"->op.replace('_',' ')
        else->op+" "+target
    }
}

object PhoneSteps {
    val ops=setOf("open","tap","long","type","enter","back","home","recents","notifications","settings_panel","scroll","swipe","wait","wait_for","expect","lock","read","share")
    const val MAX=25

    /** The steps in [json] (a JSON list, or one step). Says plainly what is wrong with anything it can't use. */
    fun parse(json:String):List<Step> {
        val text=json.trim()
        require(text.isNotEmpty()) {"Give the steps as a JSON list, for example [{\"do\":\"open\",\"app\":\"Messages\"},{\"do\":\"tap\",\"on\":\"New message\"}]."}
        val list=JSONArray(if(text.startsWith("[")) text else "["+text+"]")
        require(list.length() in 1..MAX) {"Give between 1 and $MAX steps at a time."}
        return (0 until list.length()).map {i->
            val j=list.optJSONObject(i)?:throw IllegalArgumentException("Step ${i+1} is not an object like {\"do\":\"tap\",\"on\":\"Send\"}.")
            val op=j.optString("do").ifBlank {j.optString("op")}.lowercase().replace(' ','_')
            require(op in ops) {"Step ${i+1}: \"$op\" is not a step. Use: "+ops.joinToString(", ")+"."}
            val words=j.optString("on").ifBlank {j.optString("into")}.ifBlank {if(op=="type") "" else j.optString("target")}.ifBlank {if(op in setOf("type","open")) "" else j.optString("text")}
            val step=Step(op,PhoneScreen.Target(j.optString("id"),words,j.optString("role"),j.optInt("nth",0)),
                if(op=="type" || op=="share") j.optString("text") else "",j.optString("app"),j.optString("dir").lowercase(),j.optLong("ms",0).coerceIn(0,15_000),
                j.optBoolean("enter"),j.optBoolean("append"))
            when(op) {
                "open"->require(step.app.isNotBlank()) {"Step ${i+1}: open needs an app name, like {\"do\":\"open\",\"app\":\"Messages\"}."}
                "share"->require(step.app.isNotBlank() && step.text.isNotBlank()) {"Step ${i+1}: share needs the app and the text, like {\"do\":\"share\",\"app\":\"Notes\",\"text\":\"...\"}."}
                "tap","long","wait_for","expect"->require(step.target.given) {"Step ${i+1}: $op needs the words on the thing (\"on\") or its number (\"id\")."}
                "type"->require(step.text.isNotEmpty()) {"Step ${i+1}: type needs the text to type (\"text\")."}
                "scroll"->require(step.dir in setOf("","up","down","left","right")) {"Step ${i+1}: scroll direction is up, down, left or right."}
            }
            step
        }
    }
}
