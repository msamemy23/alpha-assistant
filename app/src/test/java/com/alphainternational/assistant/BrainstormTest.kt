package com.alphainternational.assistant

import org.junit.Assert.*
import org.junit.Test

/** Brainstorm: the ChatGPT-like chat that is its own thing (owner, 2026-10-11). */
class BrainstormTest {
    @Test fun aChatIsNamedFromTheFirstThingTheOwnerSays() {
        assertEquals("Should I open a second bay for tires?",Brainstorm.titleOf("Should I open a second bay for tires?"))
        assertEquals(Brainstorm.UNTITLED,Brainstorm.titleOf("   \n  "))
        val long=Brainstorm.titleOf("I have been thinking about whether it makes sense to start offering mobile oil changes to fleet customers around Houston")
        assertTrue(long,long.length<=50 && long.endsWith("…") && long.startsWith("I have been thinking"))
        assertEquals("Marketing ideas",Brainstorm.titleOf("**Marketing** `ideas`"))
        var chat=Brainstorm.newChat("c1",100)
        chat=Brainstorm.add(chat,"user","Ideas for slow Mondays",200)
        assertEquals("Ideas for slow Mondays",chat.title)
        chat=Brainstorm.add(chat,"assistant","A few: a Monday special…",300)
        chat=Brainstorm.add(chat,"user","What about texting past customers",400)
        assertEquals("the name does not change after the first text","Ideas for slow Mondays",chat.title)
        assertEquals(listOf("user","assistant","user"),chat.messages.map {it.role})
        assertEquals(400,chat.updated)
        assertSame("a blank text adds nothing",chat,Brainstorm.add(chat,"user","  ",500))
    }

    @Test fun chatsAreKeptNewestFirstWithoutEmptyOnesAndWithinTheLimits() {
        val chats=(1..50).map {i->Brainstorm.add(Brainstorm.newChat("c$i",i.toLong()),"user","idea $i",i*10L)}+Brainstorm.newChat("empty",9_999)
        val tidy=Brainstorm.tidy(chats)
        assertEquals(Brainstorm.MAX_CHATS,tidy.size)
        assertEquals("c50",tidy.first().id);assertTrue(tidy.none {it.id=="empty"})
        var chat=Brainstorm.newChat("long",0)
        repeat(Brainstorm.MAX_MESSAGES+30) {chat=Brainstorm.add(chat,if(it%2==0) "user" else "assistant","text $it",it.toLong())}
        assertEquals(Brainstorm.MAX_MESSAGES,chat.messages.size)
        assertEquals("text ${Brainstorm.MAX_MESSAGES+29}",chat.messages.last().text)
        assertEquals(Brainstorm.MAX_CHARS,Brainstorm.add(Brainstorm.newChat("x",0),"user","a".repeat(Brainstorm.MAX_CHARS+500),1).messages.single().text.length)
    }

    @Test fun savedChatsComeBackExactlyAndBadDataIsIgnored() {
        var chat=Brainstorm.newChat("c1",1)
        chat=Brainstorm.add(chat,"user","Line one\nLine two with \"quotes\" and emoji 👍",10)
        chat=Brainstorm.add(chat,"assistant","# Answer\n\n- a\n- b",20)
        val back=Brainstorm.parse(Brainstorm.toJson(listOf(chat)))
        assertEquals(listOf(chat),back)
        assertTrue(Brainstorm.parse(null).isEmpty());assertTrue(Brainstorm.parse("").isEmpty());assertTrue(Brainstorm.parse("not json").isEmpty())
        assertTrue(Brainstorm.parse("""{"chats":[{"title":"no id"},{"id":"x","messages":[{"role":"user","text":""}]}]}""").single().messages.isEmpty())
        // an unknown role is read as the owner's
        assertEquals("user",Brainstorm.parse("""{"chats":[{"id":"x","messages":[{"role":"system","text":"hi"}]}]}""").single().messages.single().role)
    }

    @Test fun onlyTheNewestTextsThatFitGoToTheAiAndTheLastIsAlwaysTheOwnersLatest() {
        val messages=(1..31).map {Brainstorm.Msg(if(it%2==1) "user" else "assistant","m$it "+"x".repeat(5_000),it.toLong())}
        val input=Brainstorm.input(messages)
        assertTrue(input.length().toString(),input.length() in 8..14)
        assertEquals("user",input.getJSONObject(input.length()-1).getString("role"))
        assertTrue(input.getJSONObject(input.length()-1).getString("content").startsWith("m31 "))
        assertFalse("the oldest texts were left out",input.toString().contains("\"m1 "))
        val small=Brainstorm.input(listOf(Brainstorm.Msg("user","hi",1),Brainstorm.Msg("assistant","hello",2),Brainstorm.Msg("user","and?",3)))
        assertEquals(3,small.length());assertEquals("hi",small.getJSONObject(0).getString("content"))
        // one huge text is still sent (the newest is never dropped)
        assertEquals(1,Brainstorm.input(listOf(Brainstorm.Msg("user","y".repeat(Brainstorm.CONTEXT_CHARS+100),1))).length())
    }

    @Test fun aBrainstormIsNeverTreatedAsSmallTalk() {
        assertEquals(ModelPlan.Level.EVERYDAY,Brainstorm.level("hi"))
        assertEquals(ModelPlan.Level.EVERYDAY,Brainstorm.level("what if we sold gift cards"))
        assertEquals(ModelPlan.Level.HARD,Brainstorm.level("compare the options for a second location and plan the strategy"))
    }

    @Test fun theInstructionsSayThereAreNoToolsAndUseTheShopOnlyWhenThereIsOne() {
        val owner=Brainstorm.instructions("Acme Auto Repair","Sunday, October 11, 2026")
        assertTrue(owner.contains("no phone tools") && owner.contains("Sunday, October 11, 2026") && owner.contains("Acme Auto Repair"))
        val friend=Brainstorm.instructions("","Monday")
        assertFalse(friend.contains("The owner runs"))
    }
}
