package com.alphainternational.assistant

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.ZoneId
import java.time.ZonedDateTime

class GrokFeaturesTest {
    private val houston=ZoneId.of("America/Chicago")
    private fun at(day:Int,hour:Int,minute:Int=0)=ZonedDateTime.of(2026,10,day,hour,minute,0,0,houston)

    @Test fun schedulesUnderstandEverydayTimesAndDays() {
        assertEquals("08:00",Schedule.time("8:00 am"));assertEquals("17:30",Schedule.time("5:30 PM"));assertEquals("12:00",Schedule.time("12pm"))
        assertEquals("00:15",Schedule.time("12:15 am"));assertEquals("07:05",Schedule.time("07:05"))
        assertEquals("weekdays",Schedule.days("Weekdays"));assertEquals("mon,wed,fri",Schedule.days("fri, Monday wed"))
        for(bad in listOf("25:00","8","noon-ish","13pm")) assertTrue(bad,runCatching {Schedule.time(bad)}.isFailure)
        assertTrue(runCatching {Schedule.days("someday")}.isFailure)
    }
    @Test fun nextRunSkipsTheWeekendAndPassedTimes() {
        // October 2, 2026 is a Friday.
        assertEquals(at(2,8),Schedule.next(at(2,7,59),"08:00","weekdays"))
        assertEquals(at(5,8),Schedule.next(at(2,8,0),"08:00","weekdays"))
        assertEquals(at(3,9,30),Schedule.next(at(2,10),"09:30","weekends"))
        assertEquals(at(7,17),Schedule.next(at(5,18),"5 pm","mon,wed"))
    }
    @Test fun routinesDescribeThemselvesPlainly() {
        assertEquals("Weekdays at 8:00 AM",Schedule.describe(Routine("r","Brief","Brief me",24,"Alpha","08:00","weekdays",true)))
        assertEquals("Every 6 hours",Schedule.describe(Routine("r","Check","Check",6)))
        assertEquals("Mon, Wed at 5:00 PM",Schedule.describe(Routine("r","x","x",24,"Alpha","17:00","mon,wed")))
        val restored=Routine.parse(Routine("r","Brief","Brief me",24,"Alpha","08:00","weekdays",true).json())
        assertEquals("08:00",restored.time);assertTrue(restored.report)
        assertEquals("daily",Routine.parse(obj("id" to "old","title" to "Old","prompt" to "p","hours" to 4)).days)
    }
    @Test fun documentMarkdownBecomesStructuredBlocks() {
        val blocks=DocumentPdf.blocks("""
            # Brake special
            Front pads **installed** this week.
            Second line of the same paragraph.

            - Pads
            * Rotors checked
            1. Book online
            [ ] Bring the car
            ---
            | Item | Price |
            |---|---|
            | Pads | $89 |
        """.trimIndent())
        assertEquals(DocBlock.Heading(1,"Brake special"),blocks[0])
        assertEquals(DocBlock.Paragraph("Front pads installed this week. Second line of the same paragraph."),blocks[1])
        assertEquals(DocBlock.Space,blocks[2])
        assertEquals(DocBlock.Bullet("•","Pads"),blocks[3]);assertEquals(DocBlock.Bullet("•","Rotors checked"),blocks[4])
        assertEquals(DocBlock.Bullet("1.","Book online"),blocks[5]);assertEquals(DocBlock.Bullet("[ ]","Bring the car"),blocks[6])
        assertEquals(DocBlock.Rule,blocks[7])
        assertEquals(DocBlock.Table(listOf(listOf("Item","Price"),listOf("Pads","$89"))),blocks[8])
        assertEquals("brake-special-for-smith",DocumentPdf.baseName("Brake Special (for Smith).pdf"))
        assertEquals("See site (https://example.com)",DocumentPdf.plain("See [site](https://example.com)"))
    }
    @Test fun taughtSkillsAreValidatedAndListedWithBuiltIns() {
        val skill=Skills.taught("Weekly_Specials","Weekly specials","Post the weekly deal","1. Check the price list\n2. Draft the post")
        assertEquals("weekly_specials",skill.id)
        assertEquals("Skill the owner taught Alpha",Skills.read("weekly_specials",listOf(skill)).getString("source"))
        assertTrue(Skills.all(listOf(skill)).any {it.getString("id")=="weekly_specials" && it.getString("source")=="taught by you"})
        assertTrue(runCatching {Skills.taught("invoice_pdf","x","","steps")}.isFailure)
        assertTrue(runCatching {Skills.taught("bad id!","x","","steps")}.isFailure)
        assertTrue(runCatching {Skills.taught("ok_id","x","","")}.isFailure)
    }
    @Test fun botNamesAreShortAndClean() {
        assertEquals("Marketing Team",Bot.name("  Marketing   Team "))
        for(bad in listOf("","1Bot","Bot/../x","A very long bot name that goes on")) assertTrue(bad,runCatching {Bot.name(bad)}.isFailure)
        assertEquals(if(Profile.generic) listOf("Alpha") else listOf("Alpha","Personal"),Bot.defaults.map {it.name})
    }
    @Test fun phoneActionsOnlyPrepareSafeHandoffs() {
        assertEquals("tel:+17135550100",PhoneActions.target("call","+1 (713) 555-0100").uri)
        assertEquals("smsto:7135550100",PhoneActions.target("Text","713-555-0100",body="Your car is ready").uri)
        assertEquals("mailto:a@b.com?subject=Your%20estimate&body=Hi%20there",PhoneActions.target("email","a@b.com","Your estimate","Hi there").uri)
        assertEquals("geo:0,0?q=100%20Main%20St",PhoneActions.target("map","100 Main St").uri)
        for((action,to) in listOf("call" to "12","email" to "not-an-email","email" to "a@b.com?cc=x@y.com","web" to "http://example.com","sms" to "7135550100","call" to "7+13"))
            assertTrue("$action $to",runCatching {PhoneActions.target(action,to)}.isFailure)
    }
    @Test fun chatShowsFormattingInsteadOfRawMarkdown() {
        val blocks=ChatMarkdown.blocks("## Done\nCreated your **sample invoice**.\n\n- **Invoice:** SAMPLE-1\n  - nested\n1. First\n```\ncode line\n```\n| A | B |\n|---|---|\n| 1 | 2 |")
        assertEquals(ChatMarkdown.Block.Heading(2,"Done"),blocks[0])
        assertEquals(ChatMarkdown.Block.Para("Created your **sample invoice**."),blocks[1])
        assertEquals(ChatMarkdown.Block.Bullet("•","**Invoice:** SAMPLE-1",0),blocks[2])
        assertEquals(ChatMarkdown.Block.Bullet("•","nested",1),blocks[3])
        assertEquals(ChatMarkdown.Block.Bullet("1.","First",0),blocks[4])
        assertEquals(ChatMarkdown.Block.Code("code line"),blocks[5])
        assertEquals(ChatMarkdown.Block.Table(listOf(listOf("A","B"),listOf("1","2"))),blocks[6])
        val spans=ChatMarkdown.spans("Saved **SAMPLE-1** as `a.pdf`, see [Amazon](https://amazon.com/dp/X) and *note*.")
        assertEquals(listOf("Saved ","SAMPLE-1"," as ","a.pdf",", see ","Amazon"," and ","note","."),spans.map {it.text})
        assertTrue(spans[1].bold);assertTrue("a file name is plain bold text, not a code box",spans[3].bold && !spans[3].code);assertEquals("https://amazon.com/dp/X",spans[5].url);assertTrue(spans[7].italic)
        assertEquals(listOf("2 * 3 = 6"),ChatMarkdown.spans("2 * 3 = 6").map {it.text})
    }
    @Test fun approvalsAreDescribedInPlainWords() {
        val routine=Reviews.summary("routine_create","""{"title":"Weekday morning brief","prompt":"Brief me","time":"08:00","days":"weekdays","every_hours":0,"report":true}""")
        assertEquals("Schedule a routine",routine.title)
        assertTrue(routine.rows.contains("When" to "Weekdays at 8:00 AM"))
        val email=Reviews.summary("gmail_send","""{"to":"a@b.com","subject":"Hi","body":"Car is ready"}""")
        assertEquals("Send an email",email.title);assertEquals("a@b.com",email.rows.first {it.first=="To"}.second)
        assertEquals("Mcp call",Reviews.summary("mcp_call","""{"tool":"x","arguments_json":"{}"}""").title)
    }
    @Test fun estimatesAreLabelledAsEstimates() {
        val items="""[{"kind":"labor","description":"Brake job","quantity":2,"unit_price":120}]"""
        val doc=LocalInvoice.document("t","Maria","",items,BigDecimal.ZERO,false,"",kind="estimate")
        assertTrue(doc.getString("number").startsWith("EST-"))
        val html=InvoiceSample.html(doc)
        assertTrue(html.contains("ESTIMATE"));assertTrue(html.contains("Estimated total"));assertFalse(html.contains("INVOICE"))
        assertTrue(LocalInvoice.document("t","Maria","",items,BigDecimal.ZERO,true,"",kind="estimate").getString("number").startsWith("SAMPLE-EST-"))
        assertTrue(runCatching {LocalInvoice.document("t","Maria","",items,BigDecimal.ZERO,false,"",kind="receipt")}.isFailure)
    }
}
