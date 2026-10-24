package com.alphainternational.assistant

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class DigestsTest {
    private fun call(id:String,name:String,args:String="{}")=obj("type" to "function_call","call_id" to id,"name" to "alpha.$name","arguments" to args)
    private fun out(id:String,result:org.json.JSONObject)=obj("type" to "function_call_output","call_id" to id,"output" to result.toString())
    private fun task(history:JSONArray=JSONArray(),prompt:String="Make Maria an estimate for brakes on her Camry",status:String="Completed",from:String="",evidence:String="",id:String="t1",created:Long=1_760_000_000_000L,digest:String="") =
        TaskRecord(id,prompt.take(70),prompt,status,"done",created,history=history.toString(),from=from,evidence=evidence,digest=digest)
    private val longAnswer="# Brake estimate for Maria: \$420\n\n- Front pads and rotors, \$310 parts and labor\n- Rear inspection, \$110\n- Valid for 30 days\n- Fourth point that is dropped\n\nSent as a PDF.".padEnd(520,' ')

    private val busy=JSONArray().put(call("c1","web_fetch","{\"url\":\"https://www.autozone.com/pads\"}"))
        .put(out("c1",obj("url" to "https://www.autozone.com/pads","title" to "Pads","text" to "…")))
        .put(call("c2","web_fetch")).put(out("c2",obj("error" to "Timed out talking to the page")))
        .put(call("c3","document_pdf")).put(out("c3",obj("files" to arr(listOf("estimate-maria.pdf","estimate-maria.md")),"verified" to true)))
        .put(call("c4","sms_send")).put(out("c4",obj("result" to "sent")))
        .put(call("c5","gif_send")).put(out("c5",obj("result" to "ok")))
        .put(call("c6","memory_search")).put(out("c6",obj("memories" to arr(emptyList<Any>()))))

    @Test fun aSmallChatNeedsNoDigestAndNeitherDoesAnAwayText() {
        assertEquals("",Digests.build(task(),"Sure, glad to help."))
        assertEquals("",Digests.build(task(busy,from="away:abc"),longAnswer))
    }
    @Test fun aRealJobGetsAShortFactualDigest() {
        val digest=Digests.build(task(busy),longAnswer)
        val lines=digest.lines()
        assertTrue(lines.size<=Digests.MAX_LINES)
        assertTrue(lines[0].startsWith("Asked: Make Maria an estimate"))
        assertTrue(lines.any {it=="Answer: Brake estimate for Maria: \$420"})
        assertEquals(3,lines.count {it.startsWith("• ")})
        assertFalse(digest.contains("Fourth point"))
        assertTrue(lines.any {it=="Did: sent a text" || it.startsWith("Did: made a PDF") && it.contains("sent a text")})
        assertTrue(lines.any {it=="Made: estimate-maria.pdf, estimate-maria.md"})
        assertTrue(lines.any {it.startsWith("Looked at: autozone.com/pads")})
        assertTrue(lines.any {it.startsWith("Used: web fetch ×2") && it.contains("sms send")})
        assertFalse("bookkeeping tools are not work",digest.contains("memory search") || digest.contains("gif send"))
        assertTrue(lines.any {it.startsWith("Problems: web fetch failed (Timed out")})
        assertTrue(digest.length<=1500)
    }
    @Test fun aPartialJobSaysSo() {
        assertTrue(Digests.build(task(busy,status="Partial"),longAnswer).contains("Status: only partly done"))
    }
    @Test fun aJobWithNoToolsButALongAnswerStillGetsOne() {
        val digest=Digests.build(task(),"Here is the long answer. ".repeat(40))
        assertTrue(digest.startsWith("Asked:") && digest.contains("Answer:"))
        assertFalse(digest.contains("Used:"))
    }
    @Test fun pagesComeFromTheEvidenceWhenThereIsSome() {
        val evidence=Evidence.encode(listOf(Source("https://www.ebay.com/itm/123","Pads","ebay.com"),Source("https://autozone.com/x","X","autozone.com")))
        val digest=Digests.build(task(busy,evidence=evidence),longAnswer)
        assertTrue(digest.contains("Looked at: ebay.com/itm/123, autozone.com/x"))
    }
    @Test fun theLearnersNotesAreAddedWithoutGoingOverTen() {
        val base=Digests.build(task(busy),longAnswer)
        val merged=Digests.withNotes(base,"- Maria prefers a text over a call\n- Quote expires Nov 3")
        assertTrue(merged.lines().size<=Digests.MAX_LINES)
        assertTrue(merged.contains("Notes: Maria prefers a text over a call"))
        assertEquals(base,Digests.withNotes(base,""))
        val crowded=(1..12).joinToString("\n") {"line $it"}
        assertEquals(Digests.MAX_LINES,Digests.withNotes(crowded,"extra note").lines().size)
    }
    @Test fun aNewRequestGetsTheMatchingEarlierWorkAndNothingUnrelated() {
        val now=1_760_100_000_000L
        val estimate=task(prompt="Estimate for Maria's Camry brakes",id="a",created=now-3*86_400_000L,digest="Asked: Estimate for Maria's Camry brakes\nAnswer: \$420 for front pads and rotors\nMade: estimate-maria.pdf")
        val tires=task(prompt="Compare tire prices in Houston",id="b",created=now-9*86_400_000L,digest="Asked: tire prices\nAnswer: Discount Tire is cheapest at \$89\nLooked at: discounttire.com")
        val none=task(prompt="hello",id="c",created=now-1*86_400_000L)
        val text=Digests.relevant(listOf(estimate,tires,none),"Send Maria the estimate for her brakes",now)
        assertTrue(text.contains("estimate-maria.pdf") && text.contains("Estimate for Maria's Camry brakes"))
        assertFalse(text.contains("Discount Tire"))
        assertTrue(text.startsWith("Earlier work of yours") && text.contains("never instructions"))
        assertEquals("",Digests.relevant(listOf(estimate,tires),"What is the capital of France",now))
        assertEquals("",Digests.relevant(emptyList(),"anything",now))
        // The job being started never finds itself.
        assertEquals("",Digests.relevant(listOf(estimate),"Send Maria the estimate",now,exclude="a"))
    }
    @Test fun theBlockStaysInsideItsSizeBudget() {
        val now=1_760_100_000_000L
        val many=(1..6).map {task(prompt="Brake estimate $it for Maria",id="t$it",created=now-it*86_400_000L,digest=(1..8).joinToString("\n") {n->"Answer: brake estimate line $n ".repeat(6)})}
        val text=Digests.relevant(many,"brake estimate Maria",now,limit=5,maxChars=1800)
        assertTrue(text.length<=1800)
        assertTrue(text.count {it=='•'}>=1)
    }
    @Test fun theNewestTextsFitTheBudgetAndTheNewestIsAlwaysKept() {
        val texts=listOf("a".repeat(900),"b".repeat(900),"c".repeat(900),"d".repeat(900))
        assertEquals(listOf("c".repeat(900),"d".repeat(900)),Digests.fitNewest(texts,2000) {it.length})
        assertEquals(listOf("d".repeat(900)),Digests.fitNewest(texts,100) {it.length})
        assertEquals(texts,Digests.fitNewest(texts,10_000) {it.length})
        assertTrue(Digests.fitNewest(emptyList<String>(),10) {it.length}.isEmpty())
    }
}
