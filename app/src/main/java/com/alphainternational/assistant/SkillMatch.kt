package com.alphainternational.assistant

/**
 * Finds the saved skill (a proven recipe) that fits a request, so its steps can be put in front of the model without the model having to think of
 * calling skills_list first. Built-in skills are matched by what they are for; skills the owner taught are matched by the words of their name and
 * purpose. The match is deliberately cautious: a recipe that does not fit only costs a few lines, but the wrong one could mislead.
 */
object SkillMatch {
    data class Hit(val id:String,val title:String,val steps:String,val taught:Boolean)

    private fun r(pattern:String)=Regex(pattern,RegexOption.IGNORE_CASE)

    /** What each built-in skill is for, in plain request words. */
    private val triggers:List<Pair<String,Regex>> = listOf(
        "sample_invoice" to r("sample invoice[^.]{0,60}(supabase|database)|(supabase|database)[^.]{0,60}sample invoice"),
        "repair_estimate" to r("\\bestimates?\\b[^.]{0,80}\\b(repair|brakes?|car|vehicle|truck|suv|service|labor|parts?|camry|civic|accord|corolla|f-?150|silverado)\\b|\\b(repair|brakes?|car|vehicle|service)\\b[^.]{0,60}\\bestimates?\\b"),
        "invoice_pdf" to r("\\binvoices?\\b[^.]{0,60}\\b(pdf|make|create|write|generate|for|sample)\\b|\\b(make|create|generate|write|need|prepare)\\b[^.]{0,40}\\binvoices?\\b"),
        "gmail_draft" to r("\\b(draft|compose|write|prepare|save)\\b[^.]{0,40}\\b(e-?mail|gmail)\\b|\\be-?mail drafts?\\b|\\bgmail drafts?\\b"),
        "calendar_plan" to r("\\b(check|show|list|read|what'?s on|what is on)\\b[^.]{0,30}\\b(calendar|schedule|appointments)\\b|\\b(add|put|create|book)\\b[^.]{0,50}\\b(calendar|appointment)\\b|\\bam i free\\b|\\bavailab"),
        "important_updates" to r("important updates?|notify me (about|when|if)"),
        "vercel_preview" to r("vercel preview|preview deploy"),
        "social_draft" to r("\\b(write|prepare|draft|plan)\\b[^.]{0,40}\\b(facebook|instagram|linkedin|social media|tweet)\\b[^.]{0,20}\\b(post|caption)"),
        "daily_brief" to r("daily brief|morning brief|\\bbrief me\\b|what'?s on (my )?(plate|today)|catch me up|my day looks"),
        "web_research" to r("\\b(research|investigate|look into|deep dive)\\b|find out (about|everything)"),
        "product_research" to r("\\b(find|source|look for|shop for|price of|buy)\\b[^.]{0,50}\\b(part|parts|product|tires?|battery|alternator|brake pads?|rotors?|filters?|spark plugs?)\\b|best price (on|for)"),
        "shopping_compare" to r("\\b(compare|versus|vs\\.?)\\b[^.]{0,40}\\b(prices?|products?|brands?|models?|options)\\b|which (one )?should i buy|what should i buy"),
        "teach_skill" to r("teach you|turn this into a skill|save (this|these steps) as a skill|from now on when i say"),
        "data_analysis" to r("\\b(analy[sz]e|crunch|total up|sum up|average)\\b[^.]{0,50}\\b(numbers|csv|spreadsheet|sales|expenses|data|list)\\b"),
        "trip_planning" to r("\\b(plan|organi[sz]e)\\b[^.]{0,30}\\b(trip|vacation|outing|weekend|itinerary|getaway)\\b|\\bitinerary\\b"),
        "price_watch" to r("\\b(watch|track|monitor|alert me)\\b[^.]{0,40}\\b(price|page|stock|listing)\\b"),
        "write_edit" to r("\\b(rewrite|polish|proofread|improve)\\b[^.]{0,40}\\b(letter|post|review reply|bio|flyer|email|message|text|caption)\\b"),
        "meeting_prep" to r("\\b(prep|prepare|brief)\\b[^.]{0,40}\\b(meeting|call|appointment)\\b|meeting prep"),
        "expense_summary" to r("\\bexpenses?\\b[^.]{0,40}\\b(summar|total|report)|summari[sz]e[^.]{0,30}(spending|receipts|expenses)"),
        "sales_outreach" to r("\\b(find|get|look for)\\b[^.]{0,30}\\b(leads|prospects|new customers)\\b|\\boutreach\\b"),
        "talent_scout" to r("\\b(hire|hiring|job post|job description|recruit|candidates?)\\b"),
        "social_media" to r("social media|content calendar|week of posts"),
        "learn_topic" to r("help me learn|teach me|quiz me|lesson plan"),
        "customer_message" to r("\\b(text|message|e-?mail)\\b[^.]{0,30}\\b(customer|client)\\b[^.]{0,40}\\b(ready|done|back|appointment|update)\\b")
    )

    private val explicit=Regex("skill \\(id ([a-z][a-z0-9_]{1,39})\\)",RegexOption.IGNORE_CASE)
    private val stop=setOf("the","and","for","you","your","with","this","that","what","have","from","into","them","then","when","how","who","all","any","our","was","has","not","but","its","can","will","make","need","want","use","using","about")
    private fun words(text:String)=text.lowercase().split(Regex("[^a-z0-9]+")).filter {it.length>=4 && it !in stop}.toSet()

    /**
     * The recipe for this request, or null. [userSkills] are the owner's taught skills (matched by the words of their title, purpose and id); [files] is how many files came with
     * the request. Short chat, hand-offs between bots and the "use the X skill" menu text are handled: the menu text names its skill exactly.
     */
    fun pick(prompt:String,userSkills:List<UserSkill>,builtIn:List<WorkflowSkill> = Skills.builtIn,files:Int=0):Hit? {
        val text=prompt.trim()
        if(text.length<12 || text.startsWith("From ")) return null
        // "Use the “Weekly specials” skill (id weekly_specials): …" from the / menu in Chat.
        explicit.find(text)?.groupValues?.get(1)?.let {id->
            userSkills.firstOrNull {it.id==id}?.let {return Hit(it.id,it.title,it.instructions,true)}
            builtIn.firstOrNull {it.id==id}?.let {return Hit(it.id,it.title,it.instructions,false)}
        }
        // The owner's own skills first: they taught them for exactly this kind of request.
        val asked=words(text)
        val mine=userSkills.map {s->
            val key=words(s.title+" "+s.purpose+" "+s.id.replace('_',' '))
            s to (if(Regex("\\b"+Regex.escape(s.id.replace('_',' '))+"\\b",RegexOption.IGNORE_CASE).containsMatchIn(text.replace('_',' '))) 4 else asked.count {it in key})
        }.filter {it.second>=2}.maxByOrNull {it.second}
        if(mine!=null) return Hit(mine.first.id,mine.first.title,mine.first.instructions,true)
        for((id,pattern) in triggers) {
            if(!pattern.containsMatchIn(text)) continue
            val skill=builtIn.firstOrNull {it.id==id}?:continue
            // Pictures and PDFs sent with a question are the photo skill's business.
            return Hit(skill.id,skill.title,skill.instructions,false)
        }
        if(files>0 && text.length<=140 && Regex("\\b(what is|what'?s|identify|read|wrong with|tell me about)\\b",RegexOption.IGNORE_CASE).containsMatchIn(text))
            builtIn.firstOrNull {it.id=="photo_help"}?.let {return Hit(it.id,it.title,it.instructions,false)}
        return null
    }

    /** The marker that tells a resumed job the recipe is already in its history. */
    const val MARK="A saved recipe fits this request: "

    /** What goes into the job (once, right after the request): the recipe's steps, with how to treat them. */
    fun note(hit:Hit):String = MARK+"“"+hit.title+"”"+(if(hit.taught) " (taught by the owner)" else "")+". Follow its steps, adapting them to what the owner actually asked; the owner's words win over the recipe. " +
        "Do the whole recipe without checking in.\n"+hit.steps.trim().take(1800)

    fun alreadyIn(historyText:String)=historyText.contains(MARK)

    /** The recipe already put into a job's history (so a resumed job gets the tool groups the recipe needs), or "". */
    fun recipeIn(history:org.json.JSONArray):String = history.objects().firstOrNull {it.optString("role")=="developer" && it.optString("content").startsWith(MARK)}?.optString("content").orEmpty()
}
