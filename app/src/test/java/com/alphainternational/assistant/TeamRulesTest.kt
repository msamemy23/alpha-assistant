package com.alphainternational.assistant

import org.junit.Assert.*
import org.junit.Test

class TeamRulesTest {
    private val bots=listOf("Alpha","Personal","Customer Desk","AutoZone Scout")

    @Test fun onlyNamedBotsOrEveryoneSkipThePlanner() {
        // Named: just them. "Hi everyone": every bot. Anything else is the planner's job (empty here).
        assertEquals(listOf("Customer Desk"),TeamRules.addressed("Customer Desk, what car does Creyton have?",bots))
        assertEquals(bots,TeamRules.addressed("hi everyone",bots))
        assertEquals(emptyList<String>(),TeamRules.addressed("Find me the parts for his car. AutoZone and Amazon",bots))
        // A phone with many bots still caps at twelve.
        assertEquals(12,TeamRules.addressed("hey team",(1..15).map {"Bot"+it}).size)
    }

    @Test fun aCoworkersReplyWakesTheBotThatAsked() {
        val prompt=TeamRules.reply("Customer Desk","2018 Honda Accord, 2.4L\n(from his customer record)","Find me the parts for Creyton's car")
        assertTrue(TeamRules.isReplyPrompt(prompt))
        assertTrue(prompt,prompt.contains("Customer Desk texted back"))
        assertTrue(prompt.contains("2018 Honda Accord, 2.4L (from his customer record)"))
        assertTrue(prompt.contains("Find me the parts for Creyton's car"))
        assertFalse(TeamRules.isReplyPrompt("Find me the parts"))
        assertEquals("From AutoZone Scout: what car does Creyton have?",TeamRules.question("AutoZone Scout","  what car does Creyton have? "))
        // Bots can't text each other in a long chain.
        assertEquals(3,TeamRules.MAX_HOPS)
    }

    @Test fun botsKnowWhatEachColleagueDoesAndWorksWith() {
        assertEquals("Customer Desk (Customers and their vehicles; works with Supabase, Gmail)",TeamRules.colleague(Bot("Customer Desk","Customers and their vehicles",apps=listOf("Supabase","Gmail"))))
        assertEquals("Alpha (general assistant)",TeamRules.colleague(Bot("Alpha","")))
    }

    @Test fun eachBotCanHaveItsOwnBrainButNeverAuto() {
        val known=setOf("astra","luna")
        assertEquals("astra" to "high",TeamRules.pick(Bot("Customer Desk","x",model="astra",effort="high"),known,"luna","max"))
        // Blank follows the main setting.
        assertEquals("luna" to "max",TeamRules.pick(Bot("Alpha","x"),known,"luna","max"))
        // A model that's no longer on the plan falls back to the main one.
        assertEquals("luna" to "low",TeamRules.pick(Bot("Old","x",model="gone",effort="low"),known,"luna","max"))
    }

    @Test fun newFieldsSurviveSaving() {
        val bot=Bot("Customer Desk","x",model="astra",effort="high")
        assertEquals("astra",Bot.parse(bot.json()).model);assertEquals("high",Bot.parse(bot.json()).effort)
        assertEquals("",Bot.parse(Bot("A","x").json().apply {remove("model");remove("effort")}).model)
        val t=TaskRecord("1","t","p","Queued","",5,hops=2,group="named:abc",caseId="c1",step="s2")
        val back=TaskRecord.parse(t.json())
        assertEquals(2,back.hops);assertEquals("named:abc",back.group);assertEquals("c1",back.caseId);assertEquals("s2",back.step)
        assertEquals("named:g1",TeamRules.groupId("g1"))
    }

    @Test fun aPickedBotKnowsItsOwnPartAndWhoElseWasCalled() {
        assertEquals("find the latest invoice",TeamRules.assignment("find the latest invoice","",emptyList()))
        val a=TeamRules.assignment("Find parts for Creyton's car","the parts",listOf("Customer Desk" to "the car's details"))
        assertTrue(a,a.startsWith("Find parts for Creyton's car") && a.contains("you were picked for this message: the parts.") && a.contains("Customer Desk for the car's details") && a.contains("Leave their parts to them"))
        // One bot alone gets the owner's words untouched.
        assertEquals("What was the last invoice?",TeamRules.assignment("What was the last invoice?","",emptyList()))
    }

    @Test fun aBotWaitsForAColleagueAlreadyAnsweringTheSameMessageInsteadOfRefusingOrDoingItTwice() {
        fun t(id:String,bot:String,group:String,status:String="Running",prompt:String=id)=TaskRecord(id,id,prompt,status,"",0,workspace=bot,team=true,group=group)
        val tasks=listOf(t("a","Alpha","named:g1"),t("c","Customer Desk","named:g1"),t("e","Estimate Invoice Pro","named:g2"),t("done","Marketing","named:g1","Completed"))
        // Alpha and Customer Desk were both called on for the same message: Alpha attaches to Customer Desk's job.
        assertEquals("c",TeamRules.runningFor(tasks[0],"Customer Desk",tasks)?.id)
        assertEquals("c",TeamRules.runningFor(tasks[0],"customer desk",tasks)?.id)
        // A bot called on for another message, or one that already finished, is asked normally; a private chat has no group.
        assertNull(TeamRules.runningFor(tasks[0],"Estimate Invoice Pro",tasks))
        assertNull(TeamRules.runningFor(tasks[0],"Marketing",tasks))
        assertNull(TeamRules.runningFor(TaskRecord("p","p","p","Running","",0,workspace="Alpha"),"Customer Desk",tasks))
        // Another bot's question to Customer Desk is not "the same message".
        val packet=TeamRules.packet("Marketing","x","","","case1")
        assertNull(TeamRules.runningFor(tasks[0],"Customer Desk",listOf(tasks[0],t("q","Customer Desk","named:g1",prompt=packet))))
    }
}
