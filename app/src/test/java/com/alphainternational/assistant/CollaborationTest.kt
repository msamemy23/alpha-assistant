package com.alphainternational.assistant

import org.junit.Assert.*
import org.junit.Test

class CollaborationTest {
    private fun task(prompt:String)=TaskRecord("t1","x",prompt,"Running","",0,workspace="Estimate Invoice Pro")

    @Test fun aPacketCarriesTheOwnersWordsWhatIsKnownAndWhatIsNeeded() {
        val p=TeamRules.packet("Estimate Invoice Pro","What was the last invoice for Ben Toyota?","It's for a 2003 Tacoma","the total and amount due","abcdef1234567890")
        assertTrue(p.startsWith("From Estimate Invoice Pro (case abcdef12)"))
        assertTrue(p.contains("“What was the last invoice for Ben Toyota?”"))
        assertTrue(p.contains("It's for a 2003 Tacoma"))
        assertTrue(p.contains("the total and amount due"))
        assertTrue(p.contains("ANSWER:") && p.contains("NEED-INPUT:") && p.contains("CANNOT:") && p.contains("NOT-MINE:"))
        assertTrue(TeamRules.isPacket(p))
        assertFalse(TeamRules.isPacket("From Alpha: hello"))
    }

    @Test fun anAnswerIsReadByItsFirstWord() {
        assertEquals(TeamRules.Answer(TeamRules.Status.ANSWER,"Invoice INV-69, $1,250, $450 due."),TeamRules.parseAnswer("ANSWER: Invoice INV-69, \$1,250, \$450 due."))
        assertEquals(TeamRules.Status.NEED_INPUT,TeamRules.parseAnswer("need-input - which Ben, there are two").status)
        assertEquals("which Ben, there are two",TeamRules.parseAnswer("NEED-INPUT: which Ben, there are two").text)
        assertEquals(TeamRules.Status.CANNOT,TeamRules.parseAnswer("**CANNOT:** the table is empty").status)
        assertEquals(TeamRules.Status.NOT_MINE,TeamRules.parseAnswer("NOT-MINE: ask Marketing").status)
        // No marker still counts as an answer.
        assertEquals(TeamRules.Answer(TeamRules.Status.ANSWER,"It was \$40."),TeamRules.parseAnswer("It was \$40."))
    }

    @Test fun aColleagueIsRelayedWithTheirNameAndTheirWords() {
        assertEquals("Customer Desk says: INV-69 is \$1,250",TeamRules.relay("Customer Desk",TeamRules.Answer(TeamRules.Status.ANSWER,"INV-69 is \$1,250")))
        assertTrue(TeamRules.relay("Customer Desk",TeamRules.Answer(TeamRules.Status.NEED_INPUT,"which Ben?")).contains("needs to know"))
        assertTrue(TeamRules.relay("Customer Desk",TeamRules.Answer(TeamRules.Status.CANNOT,"empty")).contains("couldn't find it"))
    }

    @Test fun aBotAnsweringAQuestionCannotAskOnward() {
        assertTrue(TeamRules.mayAsk(task("What was the last invoice?")))
        assertTrue(TeamRules.mayAsk(task(TeamRules.reply("Customer Desk","INV-69","last invoice?"))))
        assertFalse(TeamRules.mayAsk(task(TeamRules.packet("Alpha","x","","","case1"))))
        assertFalse(TeamRules.mayAsk(task(TeamRules.question("Alpha","do x"))))
    }

    @Test fun theDirectoryTellsABotWhoOwnsWhat() {
        val bots=listOf(Bot("Alpha","Runs the shop","",apps=listOf("gmail")),Bot("Customer Desk","Customers and records","",apps=listOf("supabase","gmail")),Bot("Marketing","Posts","",apps=emptyList()))
        val d=TeamRules.directory(bots,"Alpha")
        assertFalse(d.contains("Alpha:"))
        assertTrue(d.contains("Customer Desk: Customers and records. Has access to the shop database"))
        assertTrue(d.contains("email"))
        assertTrue(d.contains("Marketing: Posts"))
        assertFalse(d.lines().first {it.contains("Marketing")}.contains("Has access"))
    }

    @Test fun theTeamToolsAreOfferedAndLimitsAreSane() {
        val names=allTools.map {it.name}
        assertTrue("ask_colleague" in names && "ask_team" in names && "data_find" in names)
        // bot_delegate is merged into ask_colleague.
        assertFalse("bot_delegate" in names)
        assertTrue(TeamRules.MAX_PER_CALL<=TeamRules.MAX_ASKS)
        assertEquals(120,TeamRules.DEFAULT_WAIT_S)
        assertTrue(TeamRules.DEFAULT_WAIT_S in 10..TeamRules.MAX_WAIT_S && TeamRules.MAX_WAIT_S>=240)
        // Looking up and asking together are read-only, so a bot may do several in one turn.
        listOf("data_find","ask_team","ask_colleague").forEach {assertTrue(it,it in AgentRules.parallelSafe)}
        // data_find needs the database; the colleague tools are always there.
        assertEquals("supabase",AgentRules.connectionFor("data_find"))
        assertNull(AgentRules.connectionFor("ask_team"))
    }

    @Test fun aColleagueMayBeAskedAgainOnlyAfterNeedInputOrAFailure() {
        assertTrue(TeamRules.mayAskAgain(null))
        assertTrue(TeamRules.mayAskAgain("need-input"))
        assertTrue(TeamRules.mayAskAgain("cannot"))
        assertFalse(TeamRules.mayAskAgain("answer"))
        assertFalse(TeamRules.mayAskAgain("asked"))
        assertFalse(TeamRules.mayAskAgain("waiting"))
    }

    @Test fun theTeamPromptIsOneConsistentSetOfShortRules() {
        val me=TaskRecord("t1","x","What car does Creyton have?","Running","",0,workspace="Alpha",team=true)
        val colleagues=listOf(Bot("Customer Desk","Customers and records",apps=listOf("supabase")))
        val text=TeamPrompt.section(me,colleagues,null,TeamRules.directory(colleagues+Bot("Alpha","x"),"Alpha"),dataTools=true)
        // Tells the bot to look it up and to ask colleagues; never to wait and not to wait in the same breath.
        assertTrue(text,text.contains("data_find") && text.contains("ask_team"))
        assertFalse(text.contains("bot_delegate"))
        assertFalse(text.contains("NO_REPLY"))
        assertFalse(text.contains("don't wait for the others"))
        assertTrue(text.contains("Customer Desk: Customers and records"))
        // A colleague answering a question gets the answering rules and cannot ask onward.
        val packetTask=TaskRecord("t2","x",TeamRules.packet("Alpha","what car?","","the car","c1"),"Running","",0,workspace="Customer Desk",team=true)
        val answering=TeamPrompt.section(packetTask,listOf(Bot("Alpha","x")),null,"",dataTools=true)
        assertTrue(answering.contains("ANSWER:") && answering.contains("data_find") && !answering.contains("ask_team"))
        // Without a database there is no data_find rule.
        assertFalse(TeamPrompt.section(me,colleagues,null,"",dataTools=false).contains("data_find"))
    }
}
