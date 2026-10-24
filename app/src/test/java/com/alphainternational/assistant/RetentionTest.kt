package com.alphainternational.assistant

import org.junit.Assert.*
import org.junit.Test

class RetentionTest {
    private val day=86_400_000L
    private val now=1_800_000_000_000L
    private fun msg(i:Int)=Message("m$i","assistant","text $i","t${i%7}",bot="Alpha",at=i.toLong())
    private fun task(id:String,ageDays:Int,status:String="Completed",workspace:String="Alpha")=TaskRecord(id,"T $id","P $id",status,"d",now-ageDays*day,workspace=workspace)

    @Test fun aChatUnderTheCapIsLeftAlone() {
        val all=(1..2000).map(::msg)
        val (kept,gone)=Retention.capMessages(all)
        assertEquals(all,kept);assertTrue(gone.isEmpty())
        // Up to one whole file over the cap still stays in memory: files only leave whole.
        val (same,none)=Retention.capMessages((1..2100).map(::msg))
        assertEquals(2100,same.size);assertTrue(none.isEmpty())
    }
    @Test fun theOldestWholeFilesLeaveFirstAndTheRestKeepTheirOrder() {
        val all=(1..2350).map(::msg)
        val (kept,gone)=Retention.capMessages(all)
        assertEquals(3,gone.size);assertTrue(gone.all {it.size==StateFiles.SEGMENT})
        assertEquals((1..300).map {"m$it"},gone.flatten().map {it.id})
        assertEquals(2050,kept.size);assertEquals("m301",kept.first().id);assertEquals("m2350",kept.last().id)
        assertTrue(kept.size>=Retention.MESSAGE_CAP)
    }
    @Test fun theLeavingFilesLineUpWithTheFilesOnDisk() {
        // After taking whole files from the front, what is left starts exactly at a file boundary, so no other chat file has to be rewritten.
        val (kept,gone)=Retention.capMessages((1..2500).map(::msg))
        assertEquals(0,(2500-kept.size)%StateFiles.SEGMENT)
        assertEquals(2500-kept.size,gone.sumOf {it.size})
    }
    @Test fun onlyOldFinishedRequestsNoChatShowsAreRetired() {
        val tasks=(1..400).map {task("t$it",ageDays=it)}
        val shown=setOf("t350","t399")
        val out=Retention.retirable(tasks,shown,now)
        val ids=out.map {it.id}.toSet()
        // The 300 newest stay; of the rest, ones a chat text still shows stay too.
        assertTrue(ids.none {it in tasks.sortedBy {t->t.created}.reversed().take(300).map {t->t.id}})
        assertFalse("t350" in ids || "t399" in ids)
        assertTrue("t301" in ids && "t400" in ids)
        assertEquals(98,out.size)
    }
    @Test fun recentOrUnfinishedWorkIsNeverRetired() {
        val tasks=(1..310).map {task("t$it",ageDays=if(it<=305) 1+it/100 else 90)}+task("run",100,status="Running")+task("wait",100,status="Needs review")+task("young",2)
        val out=Retention.retirable(tasks,emptySet(),now).map {it.id}
        assertFalse("run" in out || "wait" in out || "young" in out)
        // Younger than 30 days stays even when it is beyond the newest 300.
        assertTrue(out.all {id->tasks.first {it.id==id}.created<now-30*day})
    }
    @Test fun aSmallHistoryNeverRetiresAnything() {
        assertTrue(Retention.retirable((1..50).map {task("t$it",ageDays=500)},emptySet(),now).isEmpty())
    }
    @Test fun aRetiredRequestKeepsWhatSearchNeeds() {
        val t=task("old",500).copy(digest="Asked: x\nMade: y.pdf",prompt="p".repeat(900),detail="d".repeat(500),title="t".repeat(200))
        val r=RetiredTask.of(t)
        assertEquals("old",r.id);assertEquals("Asked: x\nMade: y.pdf",r.digest);assertTrue(r.prompt.length<=600 && r.detail.length<=300 && r.title.length<=120)
        assertEquals(r,RetiredTask.parse(r.json()))
    }
}
