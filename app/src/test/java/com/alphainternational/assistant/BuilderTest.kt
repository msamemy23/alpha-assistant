package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BuilderTest {
    private val skills=setOf("product_research","gmail_draft","invoice_pdf")
    private fun plan(vararg extra:Pair<String,String>)=JSONObject(mapOf("summary" to "Your marketing helper.","name" to "Marketing","role" to "Plans and writes posts and flyers.",
        "rules" to "Drafts only.","logins_json" to "[\"gmail\",\"instagram.com\",\"zapier\"]","skills_json" to "[\"product_research\"]",
        "new_skills_json" to """[{"id":"weekly_specials","title":"Weekly specials post","purpose":"A post for this week's deals.","instructions":"1. Ask for the deals. 2. Write the post."}]""",
        "new_tools_json" to """[{"id":"discount_price","title":"Discount price","description":"Price after a percent discount.","kind":"code","inputs":["price","percent"],"code":"return input.price*(1-input.percent/100)"}]""",
        "routines_json" to """[{"title":"Monday post ideas","prompt":"Give me three post ideas for this week.","time":"08:00","days":"mon","every_hours":0}]""",
        "intro" to "Hi, I'm Marketing.")+extra.toMap())

    @Test fun aCompletePlanIsReadAndChecked() {
        val p=BotPlans.parse(plan(),listOf("Alpha","Personal"),skills,setOf("run_code"))
        assertEquals("Marketing",p.name)
        assertEquals(listOf("gmail","web:https://instagram.com","zapier"),p.logins)
        assertEquals(listOf("product_research","weekly_specials"),p.skills)
        assertEquals("weekly_specials",p.newSkills.single().id)
        assertEquals(listOf("price","percent"),p.newTools.single().inputs)
        assertEquals("08:00",p.routines.single().time)
        assertEquals("Hi, I'm Marketing.",p.intro)
    }
    @Test fun badPlansExplainWhatToFix() {
        fun error(vararg extra:Pair<String,String>,bots:List<String> = emptyList())=
            runCatching {BotPlans.parse(plan(*extra),bots,skills,setOf("run_code"))}.exceptionOrNull()?.message.orEmpty()
        assertTrue(error(bots=listOf("marketing")).contains("already a bot"))
        assertTrue(error("skills_json" to "[\"made_up\"]").contains("Unknown skill"))
        assertTrue(error("logins_json" to "not json").contains("JSON array"))
        assertTrue(error("new_tools_json" to """[{"id":"run_code","title":"X","description":"Clashes with a built-in.","kind":"code","inputs":[],"code":"return 1"}]""").contains("built-in"))
        assertTrue(error("routines_json" to """[{"title":"T","prompt":"P","time":"","days":"","every_hours":500}]""").contains("every_hours"))
    }
    @Test fun signInIdsAndLabels() {
        assertEquals("calendar",BotPlans.login("Calendar"))
        assertEquals("notion",BotPlans.login("notion"))
        assertEquals("web:https://www.yelp.com",BotPlans.login("https://www.yelp.com/biz/alpha"))
        assertEquals("web:https://facebook.com",BotPlans.login("web:facebook.com"))
        assertTrue(runCatching {BotPlans.login("instagram")}.isFailure)
        assertEquals("Google Calendar",BotPlans.label("calendar"))
        assertEquals("yelp.com login",BotPlans.label("web:https://www.yelp.com"))
        assertEquals("Zapier",BotPlans.label("zapier"))
    }
    @Test fun webToolsStayOnOneSite() {
        val tool=CustomTools.create("parts_lookup","Parts lookup","Searches a parts site for a part number.","web",listOf("part"),
            "https://www.example.com/search?q={part}")
        assertEquals("https://www.example.com/search?q=brake%20pad%20%26%20rotor",CustomTools.url(tool,JSONObject().put("part","brake pad & rotor")))
        val moving=runCatching {CustomTools.create("bad_site","Bad","Looks up a city site by name.","web",listOf("city"),"https://{city}.example.com/")}
        assertTrue(moving.exceptionOrNull()?.message.orEmpty().contains("one fixed"))
        assertTrue(runCatching {CustomTools.create("plain_http","Bad","Uses plain http on purpose.","web",emptyList(),"http://example.com/")}.isFailure)
        assertTrue(runCatching {CustomTools.create("undeclared","Bad","Uses an undeclared input.","web",emptyList(),"https://example.com/{q}")}.isFailure)
    }
    @Test fun codeToolsGetOnlyTheirInputs() {
        val tool=CustomTools.create("labor_quote","Labor quote","Labor cost from hours and rate.","code",CustomTools.inputs("hours, rate"),"return input.hours*input.rate")
        val input=CustomTools.input(tool,"""{"hours":1.5,"rate":120,"secret":"x"}""")
        assertEquals(setOf("hours","rate"),input.keys().asSequence().toSet())
        assertTrue(CustomTools.script(tool,input).startsWith("var input={"))
        assertEquals(tool,CustomTool.parse(JSONObject(tool.json().toString())))
        assertEquals(listOf("a","b"),CustomTools.inputs("[\"a\",\"B\",\"a\"]"))
    }
    @Test fun botsKeepTheirSetup() {
        val bot=Bot("Marketing","Posts",rules="Drafts only",apps=listOf("gmail"),skills=listOf("weekly_specials"),tools=listOf("discount_price"))
        assertEquals(bot,Bot.parse(JSONObject(bot.json().toString())))
        // Bots saved by older versions have no setup lists.
        assertEquals(emptyList<String>(),Bot.parse(JSONObject("""{"name":"Alpha","role":"Shop"}""")).apps)
    }
    @Test fun builderToolsAreStrict() {
        BotPlans.tools.forEach {tool->
            val schema=tool.parameters
            assertEquals(JSONArray(schema.getJSONObject("properties").keys().asSequence().toList()).length(),schema.getJSONArray("required").length())
        }
    }
}
