package com.alphainternational.assistant

import org.junit.Assert.*
import org.junit.Test

class MemoryIndexTest {
    private val day=86_400_000L
    private val now=1_800_000_000_000L
    private fun m(id:String,title:String,text:String,kind:String="fact",age:Long=0,space:String="Alpha",source:String="Learned while working",private:Boolean=false) =
        Memory(id,title,text,source,now-age*day,space,private,kind)

    @Test fun wordsAreCutDownSoPluralsAndEndingsStillMatch() {
        assertEquals(listOf("estimate","maria","camry"),MemoryIndex.tokens("Estimates for Maria's Camry"))
        assertEquals(MemoryIndex.stem("brakes"),MemoryIndex.stem("brake"))
        assertEquals(MemoryIndex.stem("texting"),MemoryIndex.stem("text"))
        assertEquals("battery",MemoryIndex.stem("batteries"))
        assertEquals(listOf("713","555","1234"),MemoryIndex.tokens("(713) 555-1234"))
        assertTrue(MemoryIndex.tokens("the and for you").isEmpty())
    }
    @Test fun aSearchFindsTheRightNoteNotJustAnySubstring() {
        val docs=listOf(m("a","Maria Lopez","2015 Camry, prefers texts","person"),m("b","Supplier","Parts come from O'Reilly on Main"),
            m("c","Labor rate","Charges 120 dollars an hour","preference"),m("d","Tom","Drives a Tacoma; brake pads last month","person")).map(MemoryIndex::docOf)
        assertEquals("a",MemoryIndex.rank(docs,"what car does Maria drive? the camry",now).first().id)
        assertEquals("d",MemoryIndex.rank(docs,"brake pads",now).first().id)
        // The old search needed the whole question inside one note; words from anywhere in it work now.
        assertEquals("c",MemoryIndex.rank(docs,"what is my hourly labor rate",now).first().id)
        // Something unrelated finds nothing, instead of something random.
        assertTrue(MemoryIndex.rank(docs,"quantum chromodynamics",now).isEmpty())
        // The start of a word is enough.
        assertEquals("a",MemoryIndex.rank(docs,"cam",now).first().id)
    }
    @Test fun aRareWordCountsMoreThanACommonOne() {
        val docs=(1..8).map {m("x$it","Customer $it","Customer called about brakes")}+m("rare","Zhang","Customer wants a window tint")
        assertEquals("rare",MemoryIndex.rank(docs.map(MemoryIndex::docOf),"customer tint",now).first().id)
    }
    @Test fun aFreshNoteBeatsAnOldOneWhenTheyMatchEqually() {
        val docs=listOf(m("old","Supplier","Parts from Acme",age=400),m("new","Supplier","Parts from Acme",age=1)).map(MemoryIndex::docOf)
        assertEquals(listOf("new","old"),MemoryIndex.rank(docs,"supplier parts",now).map {it.id})
        // But a much better match still wins over a fresher weak one.
        val mixed=listOf(m("good","Brake job","Brake job estimate for Maria Camry",age=200),m("weak","Maria","Maria called",age=0)).map(MemoryIndex::docOf)
        assertEquals("good",MemoryIndex.rank(mixed,"brake job estimate Maria Camry",now).first().id)
    }
    @Test fun aBlankQueryListsTheNewest() {
        val docs=listOf(m("1","a","x",age=5),m("2","b","y",age=1),m("3","c","z",age=9)).map(MemoryIndex::docOf)
        assertEquals(listOf("2","1"),MemoryIndex.rank(docs,"",now,limit=2).map {it.id})
        assertTrue(MemoryIndex.rank(emptyList(),"anything",now).isEmpty())
    }
    @Test fun lookAlikesAreMergedAndPrivateStaysPrivate() {
        val all=listOf(m("1","Text sign-off","Signs texts to customers as Acme Auto Repair","preference",age=10,private=true),
            m("2","Text sign-off","Signs texts to customers as Acme Auto Repair.","preference",age=1),
            m("3","Labor rate","Charges 120 an hour","preference"),
            m("4","Other bot's","Signs texts to customers as Acme Auto Repair","preference",space="Personal"))
        val (result,removed)=MemoryIndex.consolidate(all,"Alpha",now=now)
        assertEquals(1,removed)
        assertEquals(setOf("2","3","4"),result.map {it.id}.toSet())
        // The newest wording stays and, because one copy was private, the survivor is private (never less private than before).
        assertTrue(result.first {it.id=="2"}.private)
        // Another bot's identical-looking note is not touched.
        assertEquals("Personal",result.first {it.id=="4"}.workspace)
        // Different kinds with the same words are different notes.
        val kinds=listOf(m("a","Maria","Likes texts","person"),m("b","Maria","Likes texts","preference"))
        assertEquals(0,MemoryIndex.consolidate(kinds,"Alpha",now=now).second)
    }
    @Test fun aBotKeepsAtMostThreeHundredAndDropsTheLeastUsefulFirst() {
        val keepers=listOf(m("pref","Sign-off","Always sign texts Alpha Auto","preference",age=800,source="You said"),
            m("summary","Where things stand","Waiting on Maria","summary",age=200),m("mine","Owner note","Owner typed this note about Dave","person",age=900,source="You"))
        val noise=(1..400).map {i->m("n$i","Page note $i","word$i unique$i thing$i stuff$i","lesson",age=(i%60).toLong(),source="Learned from something it read")}
        val (result,removed)=MemoryIndex.consolidate(keepers+noise,"Alpha",now=now)
        assertEquals(300,result.size);assertEquals(103,removed)
        assertTrue(result.map {it.id}.containsAll(listOf("pref","summary","mine")))
        // Other bots are left alone however many notes they have.
        val other=(1..320).map {m("o$it","Note $it","alpha$it beta$it gamma$it","fact",space="Personal")}
        assertEquals(0,MemoryIndex.consolidate(keepers+other,"Alpha",now=now).second)
    }
    @Test fun onlyOneSummaryIsKept() {
        val all=listOf(m("s1","Where things stand","old news","summary",age=20),m("s2","Where things stand","new news","summary",age=1))
        val (result,removed)=MemoryIndex.consolidate(all,"Alpha",now=now)
        assertEquals(1,removed);assertEquals("s2",result.single().id)
    }
    @Test fun notesTheOwnerCalledWrongAreNotLearnedAgain() {
        var blocked=Learning.addBlocked(emptyList(),"Alpha","Maria's car","Maria drives a 2012 Civic",now)
        assertTrue(Learning.isBlocked(blocked,"Alpha","Maria car","Maria drives a Civic 2012"))
        assertTrue(Learning.isBlocked(blocked,"Alpha","Customer Maria","Maria's car: Maria drives a 2012 Civic and likes texts"))
        assertFalse(Learning.isBlocked(blocked,"Personal","Maria's car","Maria drives a 2012 Civic"))
        assertFalse(Learning.isBlocked(blocked,"Alpha","Maria's phone","Maria's number is 555 0100"))
        // Marking the same thing wrong twice keeps one entry.
        blocked=Learning.addBlocked(blocked,"Alpha","Maria's car","Maria drives a 2012 Civic",now+1)
        assertEquals(1,blocked.size)
        // It survives being saved and read back.
        val back=Learning.blockedList(arr(blocked.map {it.json()}))
        assertEquals(blocked,back)
        // A tiny blocked note only blocks near-copies, not everything that mentions the word.
        val tiny=Learning.addBlocked(emptyList(),"Alpha","Civic","",now)
        assertFalse(Learning.isBlocked(tiny,"Alpha","Dave's truck","Dave drives a Civic and a truck and works in Houston"))
    }
    @Test fun smallTalkIsNotWorthTheLearner() {
        listOf("hi","Hello!","thanks","Thank you so much","ok","okay, thanks!","👍","lol","good morning","yes","Sounds good","no thanks").forEach {
            assertFalse("'$it' should be skipped",Learning.worthLearning(it,emptyList()))
        }
        listOf("No, make it shorter","I prefer texts signed Dave","Maria's number is 555-0100","Make Maria an estimate for her Camry","yes please send it to Dave","3 pm works").forEach {
            assertTrue("'$it' should be learned from",Learning.worthLearning(it,emptyList()))
        }
        // Real work is always worth a look, however short the owner's words were.
        assertTrue(Learning.worthLearning("ok",listOf("web fetch: {...}")))
        assertFalse(Learning.worthLearning("",emptyList()))
    }
    @Test fun whereANoteCameFromIsToldInPlainWords() {
        val zone=java.time.ZoneId.of("UTC")
        val learned=Memory("1","Labor rate","120","You said",1_760_000_000_000L,"Alpha")
        assertEquals("Learned from your chat on Oct 9",Learning.origin(learned,null,zone))
        assertTrue(Learning.origin(learned,"Make Maria an estimate for her Camry",zone).contains("you had asked: “Make Maria an estimate for her Camry”"))
        assertEquals("Added by you on Oct 9",Learning.origin(learned.copy(source="You"),null,zone))
        assertTrue(Learning.origin(learned.copy(source="Learned from something it read"),null,zone).startsWith("Learned from something it read on"))
        assertTrue(Learning.origin(learned.copy(source="Dave told me"),null,zone).startsWith("Saved from: Dave told me"))
    }
}
