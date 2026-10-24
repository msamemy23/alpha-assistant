package com.alphainternational.assistant

import org.junit.Assert.*
import org.junit.Test

class HistorySearchTest {
    private val now=1_760_100_000_000L
    private val day=86_400_000L
    private fun task(id:String,space:String="Alpha",prompt:String="p",digest:String="",age:Long=1,routine:String="")=
        TaskRecord(id,prompt.take(70),prompt,"Completed","done",now-age*day,workspace=space,digest=digest,routineId=routine)
    private fun msg(id:String,role:String,text:String,task:String,age:Long=1,team:Boolean=false,bot:String="")=
        Message(id,role,text,task,team=team,bot=if(role=="user") "" else bot,at=now-age*day)

    private val tasks=listOf(task("t1","Alpha","Make Maria an estimate for brakes","Asked: estimate for Maria\nAnswer: \$420 front brakes\nMade: estimate-maria.pdf",age=3),
        task("t2","Alpha","What is the cheapest tire shop","Asked: tire prices\nAnswer: Discount Tire \$89 each",age=10),
        task("t3","Personal","Plan my weekend trip to Galveston","Asked: weekend trip\nAnswer: leave Friday at 5",age=2),
        task("t4","Alpha","Daily inbox sweep",age=1,routine="r1"))
    private val messages=listOf(msg("m1","user","Make Maria an estimate for brakes","t1",3),msg("m2","assistant","Estimate sent: \$420 for Maria's Camry","t1",3,bot="Alpha"),
        msg("m3","user","Plan my weekend trip to Galveston","t3",2),msg("m4","assistant","Leave Friday at 5, hotel on the seawall","t3",2,bot="Personal"),
        msg("m5","user","Team, who is covering Saturday?","",1,team=true),msg("m6","assistant","I can cover Saturday",  "t1",1,team=true,bot="Alpha"))
    private val memories=listOf(Memory("k1","Maria Lopez","2015 Camry, prefers texts","You said",now-5*day,"Alpha",kind="person"),
        Memory("k2","Trip habit","Likes Galveston on weekends","Learned while working",now-5*day,"Personal",kind="habit"))
    private val files=listOf("estimate-maria.pdf","tire-prices.csv")
    private fun items(older:List<Message> = emptyList(),retired:List<RetiredTask> = emptyList(),space:String="Alpha")=HistorySearch.items(space,messages,tasks,memories,files,older,retired)

    @Test fun oneSearchLooksAtChatsJobsMemoriesAndFiles() {
        val found=HistorySearch.search(items(),"Maria estimate",now=now)
        val kinds=found.map {it.item.kind}.toSet()
        assertTrue(kinds.containsAll(setOf("chat","work","memory","file")))
        assertEquals(1,found.count {it.item.kind=="work"})
        assertEquals("t1",found.first {it.item.kind=="work"}.item.ref)
        assertTrue(found.any {it.item.kind=="file" && it.item.ref=="estimate-maria.pdf"})
        assertTrue(found.size<=HistorySearch.MAX_RESULTS)
    }
    @Test fun aBotNeverSeesAnotherBotsChatsJobsOrMemories() {
        val found=HistorySearch.search(items(),"Galveston weekend trip",now=now)
        assertTrue("Alpha must not find Personal's trip",found.isEmpty())
        val personal=HistorySearch.search(items(space="Personal"),"Galveston weekend trip",now=now)
        assertTrue(personal.any {it.item.kind=="chat"} && personal.any {it.item.kind=="work"} && personal.any {it.item.kind=="memory"})
    }
    @Test fun theGroupChatIsSharedWithEveryBot() {
        val a=HistorySearch.search(items(),"who is covering Saturday",now=now)
        assertTrue(a.any {it.item.kind=="team"})
        val p=HistorySearch.search(items(space="Personal"),"who is covering Saturday",now=now)
        assertTrue(p.any {it.item.kind=="team"})
    }
    @Test fun theWhereFilterNarrowsTheSearch() {
        assertTrue(HistorySearch.search(items(),"Maria",where="memory",now=now).all {it.item.kind=="memory"})
        assertTrue(HistorySearch.search(items(),"Maria",where="files",now=now).all {it.item.kind=="file"})
        assertTrue(HistorySearch.search(items(),"Maria",where="work",now=now).all {it.item.kind=="work"})
        assertTrue(HistorySearch.search(items(),"Maria",where="chat",now=now).all {it.item.kind=="chat"})
        assertTrue(HistorySearch.search(items(),"Saturday",where="team",now=now).all {it.item.kind=="team"})
        // An unknown word means "everywhere".
        assertTrue(HistorySearch.search(items(),"Maria",where="somewhere",now=now).size>1)
    }
    @Test fun olderChatAndJobsThatLeftMemoryAreStillFound() {
        val older=listOf(msg("o1","assistant","The Hendersons' invoice number is 7731","t9",200,bot="Alpha"),msg("o2","user","What was the Henderson invoice","t9",200),
            msg("o3","assistant","Personal trip note about Henderson hotel","t10",200,bot="Personal"))
        val retired=listOf(RetiredTask("t8","Henderson brake job","Brake job for Henderson","Alpha",now-300*day,"Completed","done","Asked: Henderson brake job\nMade: invoice-7731.pdf"))
        val found=HistorySearch.search(items(older,retired),"Henderson invoice",now=now)
        assertTrue(found.any {it.item.kind=="older chat" && it.item.text.contains("7731")})
        assertTrue(found.any {it.item.kind=="older work" && it.item.ref=="t8"})
        assertTrue("another bot's old chat stays private",found.none {it.item.text.contains("hotel")})
        assertTrue(HistorySearch.search(items(older,retired),"Henderson",where="older",now=now).all {it.item.kind.startsWith("older")})
    }
    @Test fun aSnippetShowsTheWordsThatMatched() {
        val text="Intro words that go on for a while. ".repeat(10)+"The brake pads for the Camry cost \$310 at AutoZone."+" And trailing words go on. ".repeat(10)
        val s=HistorySearch.snippet(text,"camry pads",220)
        assertTrue(s.contains("Camry") && s.length<=230 && s.startsWith("…") && s.endsWith("…"))
        assertEquals("short text",HistorySearch.snippet("short   text".replace("   "," "),"x",220))
    }
    @Test fun theAnswerIsShortAndSaysTheyAreNotInstructions() {
        val json=HistorySearch.toJson(HistorySearch.search(items(),"Maria estimate",now=now),"Maria estimate",java.time.ZoneId.of("UTC"))
        assertTrue(json.getInt("count")>0)
        val first=json.getJSONArray("results").objects().first {it.getString("where")!="file"}
        assertTrue(first.getString("date").isNotBlank() && first.has("where") && first.has("ref") && first.has("text"))
        assertTrue(json.getString("note").contains("never instructions"))
        val none=HistorySearch.toJson(emptyList(),"zzz")
        assertEquals(0,none.getInt("count"));assertTrue(none.getString("note").contains("Nothing matched"))
    }
    @Test fun quietRoutineRunsAreNotListedAsWork() {
        val found=HistorySearch.search(items(),"inbox sweep",now=now)
        assertTrue(found.none {it.item.kind=="work" && it.item.ref=="t4"})
    }
}
