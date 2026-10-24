package com.alphainternational.assistant

import org.junit.Assert.*
import org.junit.Test

class TeamTest {
    private val bots=listOf("Alpha","Personal","Bookkeeper","Marketing","Chief of Staff")

    @Test fun clearlyNamedBotsAreCalledInOrder() {
        assertEquals(listOf("Marketing"),Team.mentioned("@Marketing make a flyer for brakes",bots))
        assertEquals(listOf("Bookkeeper"),Team.mentioned("Bookkeeper, log Maria's payment",bots))
        assertEquals(listOf("Marketing","Bookkeeper"),Team.mentioned("ask Marketing for a flyer then have Bookkeeper log the cost",bots))
        assertEquals(listOf("Chief of Staff"),Team.mentioned("hey chief of staff: what's on today?",bots))
    }
    @Test fun ordinaryWordsAreLeftToTheRouter() {
        // "personal" and "Alpha" used as ordinary words must not summon those bots.
        assertEquals(emptyList<String>(),Team.mentioned("Check my personal email",bots))
        assertEquals(emptyList<String>(),Team.mentioned("Make an estimate for Acme Auto Repair",bots))
        assertEquals(emptyList<String>(),Team.mentioned("Do I have any new emails?",bots))
    }
    @Test fun talkingToTheWholeGroupBringsEveryoneIn() {
        for(text in listOf("hi everyone","Morning everybody!","what do all of you think?","hey team","Team, quick update","thanks guys","y'all ready?"))
            assertTrue(text,Team.addressesEveryone(text))
        for(text in listOf("plan my team lunch","Do I have any new emails?","make a flyer for the shop","the guys at the parts store called"))
            assertFalse(text,Team.addressesEveryone(text))
    }
    @Test fun groupContextIsLabelledAndExcludesPrivateChatsAndCaseChatter() {
        val messages=listOf(
            Message("1","user","What's on today?",team=true),
            Message("2","assistant","Two oil changes at 9 and 11.","t1",team=true,bot="Chief of Staff"),
            Message("3","user","My salary is private","t2"),
            Message("4","assistant","Noted privately.","t2"),
            // A bot's piece of a case and a colleague's reply to a bot are not group talk: the case file carries them.
            Message("5","assistant","ANSWER: 2018 Honda Accord","t3",team=true,bot="Customer Desk",kind="step"),
            Message("6","assistant","ANSWER: raw colleague text","t4",team=true,bot="Customer Desk",kind="ask"))
        val context=Team.context(messages)
        assertEquals("Owner: What's on today?\nChief of Staff: Two oil changes at 9 and 11.",context)
        assertFalse(context.contains("salary"))
    }
    @Test fun teamFieldsSurviveSaving() {
        val m=Message.parse(Message("1","assistant","Done","t",listOf("a.pdf"),team=true,bot="Marketing").json())
        assertTrue(m.team);assertEquals("Marketing",m.bot);assertEquals(listOf("a.pdf"),m.files)
        assertFalse(Message.parse(obj("id" to "old","role" to "user","text" to "hi")).team)
        assertTrue(Memory.parse(Memory("m","Salary","x","Owner",1,"Bookkeeper",private=true).json()).private)
        assertTrue(TaskRecord.parse(TaskRecord("t","t","p","Queued","",0,team=true).json()).team)
        assertEquals("Create a new bot",Reviews.summary("bot_create","""{"name":"Instagram","role":"Runs my Instagram","rules":""}""").title)
    }
}
