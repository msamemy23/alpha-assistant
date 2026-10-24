package com.alphainternational.assistant

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class PlanRoutine(val title:String,val prompt:String,val time:String,val days:String,val hours:Int)
/** What the bot builder proposes. Nothing exists until the owner taps Create. [skills] includes the ids of [newSkills]. */
data class BotPlan(val name:String,val role:String,val rules:String,val logins:List<String>,val skills:List<String>,
    val newSkills:List<UserSkill>,val newTools:List<CustomTool>,val routines:List<PlanRoutine>,val intro:String,val summary:String)

object BotPlans {
    val google=linkedMapOf("gmail" to "Gmail","calendar" to "Calendar","drive" to "Drive, Docs & Sheets","tasks" to "Tasks","contacts" to "Contacts")
    private val googleNames=mapOf("gmail" to "Gmail","calendar" to "Google Calendar","drive" to "Google Drive, Docs & Sheets","tasks" to "Google Tasks","contacts" to "Google Contacts")

    /** A sign-in id: a Google service, supabase, vercel, a Marketplace app id, or web:<https origin> for a website login. */
    fun login(raw:String):String {
        val value=raw.trim();val key=value.lowercase()
        if(key in google || key=="supabase" || key=="vercel" || AppCatalog.find(key)!=null) return key
        val site=(if(key.startsWith("web:")) value.substring(4) else value).trim()
        val origin=Rules.origin(if(site.startsWith("http",true)) site else "https://"+site)
        require(origin.substringAfter("://").contains('.')) {"$raw is not a sign-in from the list or a website address."}
        return "web:"+origin
    }
    fun label(id:String):String = googleNames[id]?:when {
        id=="supabase"->"Supabase"
        id=="vercel"->"Vercel"
        id.startsWith("web:")->id.removePrefix("web:").substringAfter("://").removePrefix("www.")+" login"
        else->AppCatalog.find(id)?.name?:id
    }

    fun parse(a:JSONObject,bots:List<String>,skillIds:Set<String>,reservedTools:Set<String>):BotPlan {
        val name=Bot.name(a.optString("name"))
        require(bots.none {it.equals(name,true)}) {"There is already a bot named $name. Pick another name."}
        val role=a.optString("role").trim();require(role.length in 3..200) {"role must be one sentence under 200 characters."}
        val rules=a.optString("rules").trim();require(rules.length<=4000) {"Keep rules under 4,000 characters."}
        val logins=list(a,"logins_json").map {login(it.toString())}.distinct().take(12)
        val newSkills=objects(a,"new_skills_json").take(6).map {Skills.taught(it.optString("id"),it.optString("title"),it.optString("purpose"),it.optString("instructions"))}
        val skills=list(a,"skills_json").map {it.toString().trim().lowercase()}.filter {it.isNotBlank()}.distinct()
        val unknown=skills.filterNot {it in skillIds || newSkills.any {s->s.id==it}}
        require(unknown.isEmpty()) {"Unknown skill ids: "+unknown.joinToString()+". Use ids from the skill list, or add them to new_skills_json."}
        val newTools=objects(a,"new_tools_json").take(6).map {t->
            val inputs=t.optJSONArray("inputs")?.strings()?.map {it.trim().lowercase()}?:CustomTools.inputs(t.optString("inputs"))
            CustomTools.create(t.optString("id"),t.optString("title"),t.optString("description"),t.optString("kind"),inputs,t.optString("code"),reservedTools)
        }
        require(newTools.map {it.id}.distinct().size==newTools.size) {"Each new tool needs its own id."}
        val routines=objects(a,"routines_json").take(3).map {r->
            val title=r.optString("title").trim();val prompt=r.optString("prompt").trim()
            require(title.isNotBlank() && prompt.isNotBlank() && prompt.length<=4000) {"Each routine needs a title and a complete standalone prompt."}
            val time=r.optString("time").trim().let {if(it.isBlank()) "" else Schedule.time(it)}
            val hours=r.optInt("every_hours",24)
            if(time.isBlank()) require(hours in 1..168) {"A routine without a time needs every_hours from 1 to 168."}
            PlanRoutine(title.take(100),prompt,time,if(time.isBlank()) "daily" else Schedule.days(r.optString("days").ifBlank {"daily"}),if(time.isBlank()) hours else 24)
        }
        return BotPlan(name,role,rules,logins,(skills+newSkills.map {it.id}).distinct(),newSkills,newTools,routines,
            a.optString("intro").trim().take(600),a.optString("summary").trim().take(400))
    }
    private fun list(a:JSONObject,key:String):List<Any> {
        val raw=a.optString(key).trim();if(raw.isBlank()) return emptyList()
        val array=runCatching {JSONArray(raw)}.getOrElse {throw IllegalArgumentException("$key must be a JSON array.")}
        return (0 until array.length()).map {array.get(it)}
    }
    private fun objects(a:JSONObject,key:String)=list(a,key).map {it as? JSONObject?:throw IllegalArgumentException("$key must be a JSON array of objects.")}

    val tools=listOf(
        Tool("ask_owner","Ask the owner one short question, only when the answer changes the setup. choices_json: JSON array of 2 to 5 short tap answers (they can also type). Then wait for the reply.",
            schema("question" to "string","choices_json" to "string")),
        Tool("test_tool","Try a new tool before proposing it. kind code: code is a JavaScript function body that reads input.<name> and returns the answer (no internet, no files). " +
            "kind web: code is an https address on one public site with {name} placeholders; returns the page text. inputs_json: JSON array of input names. sample_input_json: JSON object of sample inputs.",
            schema("kind" to "string","code" to "string","inputs_json" to "string","sample_input_json" to "string")),
        Tool("propose_bot","Show the owner the finished bot on a card with Sign in buttons and a Create button, then wait. To change it, call again with the full updated plan. " +
            "summary: one friendly sentence above the card. name: 1-20 letters, numbers or spaces. role: its job in one sentence. rules: short standing rules. " +
            "logins_json: JSON array of sign-in ids from the list, or \"web:https://site.com\" for a website login. skills_json: JSON array of existing skill ids to use first. " +
            "new_skills_json: JSON array of {id,title,purpose,instructions}. new_tools_json: JSON array of {id,title,description,kind,inputs,code} where inputs is an array of names (test each first). " +
            "routines_json: JSON array of {title,prompt,time,days,every_hours}: time like 08:00 with days daily, weekdays, weekends or mon,wed,fri; or empty time with every_hours. " +
            "intro: what the bot says first in its new chat, with two things to try. Use [] for empty lists.",
            schema("summary" to "string","name" to "string","role" to "string","rules" to "string","logins_json" to "string","skills_json" to "string",
                "new_skills_json" to "string","new_tools_json" to "string","routines_json" to "string","intro" to "string"))
    )
}

class BuilderTurn(val role:String,val text:String,val choices:List<String> = emptyList(),val plan:BotPlan?=null,val created:String="")

/**
 * The AI bot builder: a short chat that asks what the owner needs, then proposes a complete bot (job, rules, sign-ins,
 * skills, new tools it tested, routines). The owner's Create tap is the permission; sign-ins happen on the real sign-in pages.
 */
class BotBuilder(private val c:Controller,private val ai:AiGateway) {
    var turns by mutableStateOf(listOf<BuilderTurn>()); private set
    var busy by mutableStateOf(false); private set
    var status by mutableStateOf(""); private set
    var streaming by mutableStateOf(""); private set
    private var history=JSONArray()
    private var waiting:JSONObject?=null
    private var job:Job?=null

    fun start(again:Boolean=false) {
        if(turns.isNotEmpty() && !again) return
        job?.cancel();ai.cancel();busy=false;status="";streaming="";waiting=null
        val hello="What should your new bot do? Say it in your own words, or tap one."
        turns=listOf(BuilderTurn("assistant",hello,listOf("Marketing","Bookkeeper","Sales","Chief of Staff","Researcher","Recruiter").map {"A $it bot"}))
        history=JSONArray().put(obj("role" to "assistant","content" to hello))
    }
    fun send(text:String) {
        val clean=text.trim().take(4000)
        if(clean.isBlank() || busy) return
        if(turns.isEmpty()) start()
        turns=turns+BuilderTurn("user",clean)
        val call=waiting;waiting=null
        if(call!=null) AgentHistory.appendOutput(history,call,obj("owner_reply" to clean).toString())
        else history.put(obj("role" to "user","content" to clean))
        run()
    }
    fun stop() {job?.cancel();ai.cancel()}
    /** The owner approved the plan. Returns the new bot's name, or null with a notice if something was invalid. */
    fun create(plan:BotPlan,skipRoutines:Set<Int>):String? {
        if(busy) return null
        val name=try {c.createFromPlan(plan.copy(routines=plan.routines.filterIndexed {i,_->i !in skipRoutines}))}
            catch(e:Exception) {c.showNotice(plainError(e));return null}
        waiting?.let {AgentHistory.appendOutput(history,it,obj("result" to "The owner tapped Create. $name now exists.").toString())};waiting=null
        turns=turns+BuilderTurn("assistant","$name is ready and has joined the team chat.",created=name)
        return name
    }

    private fun run() {
        job=c.scope.launch {
            busy=true;status="Thinking…"
            try {
                val slug=ModelPlan.model(c.aiSettings,c.modelCatalog,ModelPlan.Level.EVERYDAY,fallback=c.selectedModel)
                require(slug.isNotBlank()) {"Connect ChatGPT in Marketplace first."}
                repeat(8) {
                    ai.setEffort(ModelPlan.effort(c.aiSettings,c.modelCatalog.firstOrNull {it.slug==slug},ModelPlan.Level.EVERYDAY))
                    var text=""
                    val response=ai.respond(slug,history,instructions(),BotPlans.tools) {delta->
                        text+=delta;withContext(Dispatchers.Main) {streaming=c.redact(text).take(4000)}
                    }
                    streaming=""
                    val output=response.optJSONArray("output")?:JSONArray()
                    output.objects().forEach {history.put(it)}
                    val said=output.objects().filter {it.optString("type")=="message"}.flatMap {it.optJSONArray("content")?.objects().orEmpty()}
                        .filter {it.optString("type")=="output_text"}.joinToString("\n") {it.optString("text")}.trim()
                    val calls=AgentHistory.pending(history)
                    if(said.isNotBlank() || calls.isEmpty()) turns=turns+BuilderTurn("assistant",c.redact(said).ifBlank {"Tell me a little more about what this bot should do."})
                    if(calls.isEmpty()) return@launch
                    for(call in calls) {
                        val args=runCatching {JSONObject(call.optString("arguments"))}.getOrDefault(JSONObject())
                        when(call.optString("name").substringAfterLast('.')) {
                            "ask_owner"-> {
                                val choices=runCatching {JSONArray(args.optString("choices_json")).strings()}.getOrDefault(emptyList()).map {it.trim().take(80)}.filter {it.isNotBlank()}.take(5)
                                turns=turns+BuilderTurn("assistant",args.optString("question").ifBlank {"Which fits best?"},choices)
                                waiting=call;return@launch
                            }
                            "propose_bot"-> {
                                val plan=try {c.planFrom(args)} catch(e:Exception) {
                                    status="Fixing the plan…"
                                    AgentHistory.appendOutput(history,call,obj("error" to e.message.orEmpty(),"fix" to "Correct the plan and call propose_bot again.").toString());null
                                }
                                if(plan!=null) {
                                    turns=turns+BuilderTurn("assistant",plan.summary.ifBlank {"Here's your new bot. Sign in to what it needs, then tap Create."},plan=plan)
                                    waiting=call;return@launch
                                }
                            }
                            "test_tool"-> {status="Testing a new tool…";AgentHistory.appendOutput(history,call,test(args).toString().take(8000))}
                            else->AgentHistory.appendOutput(history,call,obj("error" to "Unknown tool.").toString())
                        }
                    }
                }
                turns=turns+BuilderTurn("assistant","This is taking me too long. Tell me in one line what the bot should do and I'll set it up.")
            } catch(e:CancellationException) {throw e}
            catch(e:Exception) {
                turns=turns+BuilderTurn("assistant","I couldn't finish that: "+c.redact(e.message?.takeIf {it.isNotBlank()}?:e.javaClass.simpleName).take(300),listOf("Try again"))
            } finally {busy=false;status="";streaming=""}
        }
    }
    private suspend fun test(a:JSONObject):JSONObject = try {
        val tool=CustomTools.create("draft_tool","Draft","A new tool being tested.",a.optString("kind"),CustomTools.inputs(a.optString("inputs_json")),a.optString("code"))
        c.runCustomTool(tool,CustomTools.input(tool,a.optString("sample_input_json")))
    } catch(e:CancellationException) {throw e} catch(e:Exception) {obj("error" to (e.message?:e.javaClass.simpleName))}

    private fun instructions():String {
        val logins=(BotPlans.google.keys+listOf("supabase","vercel")).joinToString("\n") {id->
            "- $id: "+BotPlans.label(id)+when(id) {"supabase"->" (the shop's database)";"vercel"->" (websites)";else->""}+status(id)
        }+"\n"+AppCatalog.all.joinToString("\n") {"- "+it.id+": "+it.name+" — "+it.blurb+status(it.id)}
        val skills=Skills.builtIn.joinToString("\n") {"- "+it.id+": "+it.title+" — "+it.purpose}+
            c.userSkills.joinToString("") {"\n- "+it.id+": "+it.title+" — "+it.purpose+" (taught by the owner)"}
        val tools=c.customTools.joinToString("\n") {"- "+it.id+": "+it.title+" — "+it.description}.ifBlank {"(none yet)"}
        val bots=c.bots.joinToString("\n") {"- "+it.name+": "+it.role}
        return """
            You are the bot builder inside Alpha Assistant, an Android app for ${Profile.builderOwner}.
            The owner is not technical. Your job: set up a great new bot quickly, with almost no effort from them.
            1. Talk like a friendly person helping them set it up, the way you'd text: short, warm, one question at a time with ask_owner
               (give 2 to 5 tap choices, and they can always type). Ask about three to five things, skipping any they already told you:
               what the bot should do for them day to day; which accounts or websites it should use (email, Instagram, a supplier site…);
               how they like it done (tone, what to always or never do); and whether it should do anything on a schedule. React briefly to
               each answer like a person would ("Got it." / "Nice."), then ask the next. When you know enough, propose_bot. Decide the name,
               job wording, skills and tools yourself.
            2. Sign-ins: include only what the job needs. Prefer Google or a Marketplace app over a website login. For apps not listed
               (Outlook, Slack, QuickBooks, Shopify, Square and thousands more) use zapier. For a site with no app (Instagram, Facebook, Yelp,
               a parts supplier), use a website login web:https://site.com: the owner signs in once on that site's real page in Alpha's browser (Forgot password works) and the bot stays
               signed in. Never ask for passwords, codes or tokens: the card's Sign in buttons open the real pages.
               Texting needs no sign-in or website login: bots text from the owner's phone, or through Google Messages web if the owner chose that in
               Settings (they pair it once themselves). Never add a website login for Google, Messages or WhatsApp; QR-code pairing can't work on the same phone.
            3. Skills: list existing skill ids that fit. When the job has a repeatable process no skill covers, write a new skill with numbered
               steps, inputs, rules, the finished result and which sign-ins or tools to use.
            4. Tools: every bot already has web search with sources, reading web pages, Alpha's browser, a calculator sandbox, documents and PDFs
               (letters, flyers, reports, invoices, estimates), texts sent from the owner's own number after approval, phone hand-offs (call, email, maps),
               memory, files, photos and PDF reading,
               routines, and hand-offs to other bots. Make a new tool only when these can't do the job well: kind code for a calculator,
               price or quote formula, converter or formatter; kind web for a lookup on one public site. Test each with test_tool and fix it before proposing.
            5. Routines only when the owner asked for something regular, or one that is clearly useful (say so in the summary).
            6. Rules keep the owner in control: drafts only; never send, post, buy, pay, book or delete without asking.
            7. After propose_bot, wait. If the owner asks for a change, call propose_bot again with the whole updated plan. Be warm and brief, in plain words.
            Sign-ins (id: what it is [status]):
            $logins
            - web:https://site.com: a website login saved on this phone
            Skills (id: title — purpose):
            $skills
            Tools bots already made:
            $tools
            Existing bots (do not reuse their names):
            $bots
        """.trimIndent()
    }
    private fun status(id:String)=if(c.loginConnected(id)) " [connected]" else ""
}
