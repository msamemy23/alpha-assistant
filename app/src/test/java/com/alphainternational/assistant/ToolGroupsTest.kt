package com.alphainternational.assistant

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class ToolGroupsTest {
    private fun on(prompt:String,kit:ToolGroups.Kit=ToolGroups.Kit())=ToolGroups.select(prompt,kit)

    /** The phone group's words were written with a single backslash, which Kotlin reads as a backspace, so most of them never matched and every phone job began with a round just to switch the tools on. */
    @Test fun askingForAnAppOnThePhoneSwitchesThePhoneToolsOnFromTheFirstRound() {
        listOf("Open the Camera app and tell me which mode it is on.","Open the Calculator app and work out 15 percent of 2480","Open Settings and find the battery percentage",
            "On my phone, set a timer for 5 minutes","check my phone screen","use the Clock app to set an alarm for 7").forEach {
            assertTrue(it,"phone" in on(it))
        }
        assertFalse("phone" in on("What is the capital of Australia"))
        assertFalse("phone" in on("Write a short poem about the sea"))
    }

    @Test fun everyToolIsInExactlyOneGroupOrIsEveryday() {
        val names=allTools.map {it.name}
        assertEquals("a tool name is used twice",names.size,names.toSet().size)
        // No tool is listed in two groups, and every listed tool exists (a typo would make a tool unreachable).
        val listed=ToolGroups.groups.flatMap {it.tools}
        assertEquals("a tool is in two groups",listed.size,listed.toSet().size)
        listed.forEach {assertTrue("$it is listed in a group but is not a tool",it in names)}
        names.forEach {n->
            val g=ToolGroups.groupOf(n)
            assertTrue("$n has group $g",g=="core" || ToolGroups.find(g)!=null)
            val containing=ToolGroups.groups.filter {n in it.tools}
            assertTrue("$n is in ${containing.size} groups",containing.size<=1)
            assertEquals(containing.firstOrNull()?.id?:"core",g)
        }
    }

    @Test fun nothingIsUnreachableBecauseAnyGroupCanBeSwitchedOn() {
        val all=ToolGroups.visible(allTools,ToolGroups.ids).map {it.name}
        assertEquals(allTools.map {it.name}.toSet(),all.toSet())
        assertEquals(allTools.size,all.size)
        // Each group, on its own, brings exactly its tools on top of the everyday ones.
        val core=ToolGroups.visible(allTools,emptyList()).map {it.name}.toSet()
        ToolGroups.groups.forEach {g->
            val withIt=ToolGroups.visible(allTools,listOf(g.id)).map {it.name}.toSet()
            assertEquals(g.id,g.tools.toSet(),withIt-core)
        }
        // The model's switch lists every group, and only real ones.
        val enable=ToolGroups.enableTool()
        assertEquals(ToolGroups.ids,enable.parameters.getJSONObject("properties").getJSONObject("group").getJSONArray("enum").let {a->(0 until a.length()).map {a.getString(it)}})
        ToolGroups.ids.forEach {assertTrue(it,enable.description.contains(it))}
        assertTrue("tools_enable" in core && "plan_set" in core && "plan_update" in core)
    }

    @Test fun theEverydayListIsMuchSmallerThanEverything() {
        val core=ToolGroups.visible(allTools,emptyList())
        assertTrue("everyday tools: ${core.size} of ${allTools.size}",core.size*100<allTools.size*45)
        // The heavy tools are not in it.
        listOf("studio_start","media_job","game_template","run_shell","browser_open","mcp_call","vercel_preview","telnyx_calls","audio_job","video_captions").forEach {n->
            assertFalse(n,core.any {it.name==n})
        }
        // The tools every request may need are.
        listOf("web_fetch","file_write","file_edit","sms_send","phone_action","memory_save","run_code","capabilities","task_need_connection","gmail_search","calendar_events","skill_read").forEach {n->
            assertTrue(n,core.any {it.name==n})
        }
    }

    @Test fun ordinaryRequestsNeedNoGroup() {
        listOf("What's the weather tomorrow?","Text John that his car is ready","hi","Thanks!","that sounds good","never mind","what time do you close on Saturday",
            "How much should I charge for an oil change?","Remember that Dave likes his texts short","Look up the best price for a Camry battery","what is the best oil for a Civic",
            "Translate this to Spanish: the car is ready").forEach {assertEquals(it,emptyList<String>(),on(it))}
    }

    @Test fun requestsSwitchOnTheGroupTheyAreAbout() {
        fun assertOn(prompt:String,vararg groups:String,kit:ToolGroups.Kit=ToolGroups.Kit()) {
            val got=on(prompt,kit)
            groups.forEach {assertTrue("$prompt -> $got should have $it",it in got)}
        }
        assertOn("Make me an estimate for front brakes on a 2016 Camry","documents")
        assertOn("make an invoice for Maria","documents")
        assertOn("I need a PDF flyer for the brake special","documents")
        assertOn("draw a logo for the shop","documents")
        assertOn("Draft an email to the parts supplier","google_write")
        assertOn("summarize my emails from today","google_write")
        assertOn("add the appointment to my calendar","google_write")
        assertOn("save this to a Google Doc","google_write")
        assertOn("make a 20 second video reel for the brake special","media","shell")
        assertOn("write captions for this clip","media")
        assertOn("make a jingle for the shop","media")
        assertOn("build me a website for my bakery","studio","vercel")
        assertOn("I need an app to track my customers","studio")
        assertOn("make a snake game for the lobby","games","studio")
        assertOn("put my site online","vercel")
        assertOn("deploy the page","vercel")
        assertOn("who called yesterday?","telnyx")
        assertOn("show me my missed calls","telnyx")
        assertOn("add this to Notion","apps")
        assertOn("check my Stripe payments","apps")
        assertOn("log in to my Facebook and post this","browser")
        assertOn("fill out the form on the dealer's site","browser")
        assertOn("remind me every Monday to order oil","automation")
        assertOn("from now on always sign texts as Dave","automation")
        assertOn("make a new bot for payroll","automation")
        assertOn("convert all these photos with ffmpeg","shell")
        assertOn("run a python script on my sales file","shell")
    }

    @Test fun aShortReplyKeepsTheGroupsOfWhatTheBotJustSaid() {
        assertEquals(emptyList<String>(),on("yes do it"))
        assertOn("yes do it","media",kit=ToolGroups.Kit(before="I can make a 15 second video of that special. Want me to?"))
        assertOn("go ahead","google_write",kit=ToolGroups.Kit(before="I wrote the email to the supplier. Should I save it as a draft?"))
        // A long, new request is not matched against the old text.
        assertFalse("media" in on("what time do you close on Saturday afternoon and do you take walk-ins on Sundays or only appointments",ToolGroups.Kit(before="I can make a video")))
    }
    private fun assertOn(prompt:String,group:String,kit:ToolGroups.Kit) = assertTrue("$prompt -> ${on(prompt,kit)}",group in on(prompt,kit))

    @Test fun theBotsOwnSetUpSwitchesGroupsOn() {
        assertTrue("browser" in on("check on my order",ToolGroups.Kit(botApps=listOf("web:https://www.autozone.com"))))
        assertTrue("automation" in on("hello there my friend",ToolGroups.Kit(botTools=listOf("tire_calc"))))
        assertTrue("apps" in on("add this to notion please",ToolGroups.Kit(appNames=listOf("Notion","notion"))))
        assertFalse("apps" in on("add this to the list please",ToolGroups.Kit(appNames=listOf("Notion","notion"))))
        assertTrue("browser" in on("check the autozone site for pads",ToolGroups.Kit(signedIn=listOf("https://www.autozone.com"))))
    }

    @Test fun aSavedRecipeAndEarlierToolsBringTheirGroups() {
        assertTrue("google_write" in on("do my usual",ToolGroups.Kit(recipe="1. Write the message. 2. gmail_draft it. 3. gmail_read_draft to check.")))
        val history=JSONArray().put(obj("type" to "function_call","call_id" to "1","name" to "media_job","arguments" to "{}"))
        assertEquals(setOf("media_job"),ToolGroups.usedIn(history))
        assertEquals(listOf("media","shell"),on("keep going please",ToolGroups.Kit(used = ToolGroups.usedIn(history))))
        // (Only tools that belong to a group matter here: the everyday ones are always on.)
        assertEquals(setOf("gmail_draft"),ToolGroups.toolNames("Call gmail_draft, then gmail_read_draft. Not a_real_thing_x or file_write."))
    }

    @Test fun aSiteInTheFilesOnlyMattersWhenTheRequestIsAboutIt() {
        val site=ToolGroups.Kit(hasSite=true)
        assertTrue("studio" in on("make the header bigger",site))
        assertTrue("studio" in on("change the button color to red",site))
        assertFalse("studio" in on("send me the photo of the car",site))
        assertFalse("studio" in on("what's on the menu for lunch today",site))
        assertFalse("studio" in on("text Dave the picture of his invoice",site))
        assertFalse("studio" in on("thanks!",site))
    }

    @Test fun groupsNeededByAnotherComeAlongInOrder() {
        assertEquals(listOf("studio","games","vercel"),ToolGroups.close(listOf("games")))
        assertEquals(listOf("media","shell"),ToolGroups.close(listOf("media")))
        assertEquals(listOf("studio","vercel"),ToolGroups.close(listOf("studio")))
    }

    @Test fun theListOnlyGrowsAtTheEndSoTheStartOfTheRequestStaysTheSame() {
        val pool=allTools.filter {ToolGroups.groupOf(it.name)=="core" || ToolGroups.groupOf(it.name) in setOf("documents","media","shell","studio","vercel")}
        val first=ToolGroups.visible(pool,listOf("documents"))
        val later=ToolGroups.append(first,pool,listOf("media","shell"))
        assertEquals(first.map {it.name},later.take(first.size).map {it.name})
        assertEquals(ToolGroups.groups.first {it.id=="media"}.tools.toSet()+ToolGroups.groups.first {it.id=="shell"}.tools.toSet(),later.drop(first.size).map {it.name}.toSet())
        // Switching on a group that is already on adds nothing.
        assertEquals(later.map {it.name},ToolGroups.append(later,pool,listOf("media")).map {it.name})
        // Everyday tools come first, in the order they are defined.
        val everyday=pool.filter {ToolGroups.groupOf(it.name)=="core"}.map {it.name}
        assertEquals(everyday,first.take(everyday.size).map {it.name})
    }
}
