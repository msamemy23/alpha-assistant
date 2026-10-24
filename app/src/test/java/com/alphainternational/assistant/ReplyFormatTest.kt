package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The richer reply: a headline answer, option rows with buttons, follow-up buttons, what the bot looked at, and plain lines for its steps. */
class ReplyFormatTest {
    private val reply="""# Best value: the used pair, ${'$'}215.99

Both sensors fit the 2019 to 2022 Pilot.

> Check the connector in the photos before you buy.

```rows
Driver/left 36936-TG7-A23 | Used OEM, 30-day returns | ${'$'}149.99 | https://www.ebay.com/itm/1
**Passenger/right 36931-TG7-A23** | Used OEM, ${'$'}6 shipping | ${'$'}59.99 | https://www.ebay.com/itm/2
Call the dealer | Parts counter | | tel:+17135550100
```

```followups
- Check Amazon too
- Text this to Dave
"Watch the price"
```
"""

    @Test fun aReplyHasAHeadlineQuoteRowsAndHiddenFollowups() {
        val blocks=ChatMarkdown.blocks(reply)
        assertEquals(ChatMarkdown.Block.Heading(1,"Best value: the used pair, \$215.99"),blocks[0])
        assertEquals(ChatMarkdown.Block.Para("Both sensors fit the 2019 to 2022 Pilot."),blocks[1])
        assertEquals(ChatMarkdown.Block.Quote("Check the connector in the photos before you buy."),blocks[2])
        val rows=(blocks[3] as ChatMarkdown.Block.Rows).rows
        assertEquals(3,rows.size)
        assertEquals(ChatMarkdown.Row("Driver/left 36936-TG7-A23","Used OEM, 30-day returns","\$149.99","https://www.ebay.com/itm/1"),rows[0])
        assertEquals("Passenger/right 36931-TG7-A23",rows[1].title)
        assertEquals(listOf("Open","Open","Call"),rows.map {it.action})
        assertEquals("",rows[2].value)
        // The followups block is never drawn as text.
        assertEquals(4,blocks.size)
    }

    @Test fun followUpsAreFoundAndRemovedFromTheText() {
        assertEquals(listOf("Check Amazon too","Text this to Dave","Watch the price"),ChatMarkdown.followups(reply))
        val clean=ChatMarkdown.withoutFollowups(reply)
        assertFalse(clean.contains("followups") || clean.contains("Check Amazon too"))
        assertTrue(clean.contains("```rows"))
        assertEquals(emptyList<String>(),ChatMarkdown.followups("Just a plain answer."))
        // A block that is still being written (no closing fence yet) never leaks into the text or the buttons list as broken text.
        assertEquals(listOf("One"),ChatMarkdown.followups("Hi\n```followups\n- One"))
        assertEquals("Hi",ChatMarkdown.withoutFollowups("Hi\n```followups\n- One"))
        assertEquals(1,ChatMarkdown.blocks("Hi\n```followups\n- One").size)
        // Rows that are not finished yet are not drawn either; ordinary code still is.
        assertEquals(1,ChatMarkdown.blocks("Hi\n```rows\nA | b").size)
        assertEquals(ChatMarkdown.Block.Code("a=1"),ChatMarkdown.blocks("```kotlin\na=1\n```")[0])
    }

    @Test fun phoneAndTextLinksWorkInsideAnswers() {
        val spans=ChatMarkdown.spans("Call [Dave](tel:+17135550100) or [text him](sms:+17135550100) or see [the page](https://x.com/a).")
        assertEquals(listOf("tel:+17135550100","sms:+17135550100","https://x.com/a"),spans.filter {it.url.isNotBlank()}.map {it.url})
    }

    @Test fun pagesAreRecognisedAsTheSameDespiteTrackingAndSlashes() {
        assertEquals(Evidence.key("https://www.ebay.com/itm/123?utm_source=x#top"),Evidence.key("http://ebay.com/itm/123/"))
        assertNotEquals(Evidence.key("https://ebay.com/itm/123"),Evidence.key("https://ebay.com/itm/124"))
        assertNotEquals(Evidence.key("https://ebay.com/sch?q=pilot"),Evidence.key("https://ebay.com/sch?q=civic"))
        assertEquals("ebay.com",Evidence.domain("https://www.ebay.com/itm/1"))
        assertEquals("",Evidence.domain("not a url"))
    }

    @Test fun whatTheBotLookedAtGrowsWithoutRepeatsAndKeepsTheScreenshot() {
        var list=emptyList<Source>()
        list=Evidence.add(list,Source("https://ebay.com/itm/1","",Evidence.domain("https://ebay.com/itm/1")))
        list=Evidence.add(list,Source("https://ebay.com/itm/2","Pilot sensor",""))
        list=Evidence.add(list,Source("https://www.ebay.com/itm/1/?utm_x=1","Honda sensor, left","ebay.com",shot="a.jpg"))
        list=Evidence.add(list,Source("javascript:alert(1)","bad","x"))
        list=Evidence.add(list,Source("","empty",""))
        assertEquals(2,list.size)
        assertEquals("Honda sensor, left",list[0].title);assertEquals("a.jpg",list[0].shot)
        assertEquals(listOf("https://ebay.com/itm/2"),Evidence.toShoot(list).map {it.url})
        // Round trip through the saved form.
        assertEquals(list,Evidence.parse(Evidence.encode(list)))
        assertEquals(emptyList<Source>(),Evidence.parse("not json"))
        // Files and non-pages never get a screenshot; only the first few pages do.
        val many=(1..9).map {Source("https://s.test/p$it","t$it","s.test")}+Source("https://s.test/doc.PDF","pdf","s.test")
        assertEquals(Evidence.MAX_SHOTS,Evidence.toShoot(many).size)
        assertTrue(Evidence.toShoot(listOf(Source("https://s.test/doc.pdf","pdf","s.test"))).isEmpty())
        assertEquals("ebay.com",Evidence.title("","https://www.ebay.com/x"))
    }

    @Test fun stepsAreShortPlainLines() {
        fun l(tool:String,vararg kv:Pair<String,String>)=StepWords.line(tool,JSONObject().also {j->kv.forEach {(k,v)->j.put(k,v)}})
        assertEquals("Read ebay.com",l("web_fetch","url" to "https://www.ebay.com/itm/1"))
        assertEquals("Searched for “brake pads for a 2018 Camry”",l("browser_search","query" to "brake pads for a 2018 Camry"))
        assertEquals("Wrote index.html",l("file_edit","name" to "index.html"))
        assertEquals("",l("task_plan"));assertEquals("",l("file_read"))
        assertEquals("Asked Customer Desk",l("ask_colleague","colleague" to "Customer Desk"))
        assertEquals("Made the document",l("document_pdf"))
        val output=JSONArray("""[{"type":"reasoning"},{"type":"web_search_call","action":{"type":"search","query":"honda pilot blind spot sensor"}},
            {"type":"web_search_call","action":{"type":"open_page","url":"https://www.ebay.com/itm/1"}},{"type":"web_search_call","action":{"type":"search","queries":["a","b"]}},{"type":"message"}]""")
        assertEquals(listOf("Searched for “honda pilot blind spot sensor”","Opened ebay.com","Searched for “a”"),StepWords.searches(output))
    }

    @Test fun pagesComeFromToolsCitationsAndLinksButNotSearchOrSignInPages() {
        fun page(url:String,title:String="T")=JSONObject().put("url",url).put("title",title)
        assertEquals(Source("https://www.ebay.com/itm/1","Honda sensor","ebay.com"),Evidence.fromTool("web_fetch",JSONObject(),page("https://www.ebay.com/itm/1?utm_source=x","Honda sensor")))
        assertEquals("https://a.test/x",Evidence.fromTool("browser_open",JSONObject().put("url","https://a.test/x"),JSONObject().put("title","X"))!!.url)
        assertEquals("https://b.test/p",Evidence.fromTool("browser_click",JSONObject(),JSONObject().put("page_now",page("https://b.test/p")))!!.url)
        assertNull(Evidence.fromTool("web_fetch",JSONObject(),JSONObject().put("error","blocked")))
        assertNull(Evidence.fromTool("browser_open",JSONObject(),page("https://www.google.com/search?q=x")))
        assertNull(Evidence.fromTool("browser_open",JSONObject(),page("https://shop.test/account/login")))
        assertNull(Evidence.fromTool("file_read",JSONObject(),page("https://a.test/x")))
        val parts=listOf(JSONObject("""{"type":"output_text","text":"x","annotations":[{"type":"url_citation","title":"Parts site","url":"https://parts.test/a?utm_source=openai"},{"type":"file_citation"}]}"""))
        assertEquals(listOf(Source("https://parts.test/a","Parts site","parts.test")),Evidence.fromParts(parts))
        assertEquals(listOf("https://x.test/1","https://y.test/2"),Evidence.fromLinks("See [one](https://x.test/1) and [two](https://y.test/2), not [mail](mailto:a@b.co).").map {it.url})
        // The pages an answer cites come first.
        val seen=listOf(Source("https://a.test/1","a","a.test"),Source("https://b.test/2","b","b.test"),Source("https://c.test/3","c","c.test"))
        assertEquals(listOf("c","a","b"),Evidence.pick(seen,listOf(Source("https://c.test/3/","c","c.test"))).map {it.title})
        assertEquals(2,Evidence.pick(seen,emptyList(),2).size)
    }

    @Test fun onlyPublicHttpsPagesGetAPictureAndEachPageNamesItsOwnFile() {
        assertTrue(Evidence.shootable("https://www.ebay.com/itm/1"))
        listOf("http://www.ebay.com/itm/1","https://localhost/x","https://192.168.1.1/admin","https://[::1]/","https://printer.local/","file:///data/x","javascript:alert(1)","not a url").forEach {assertFalse(it,Evidence.shootable(it))}
        val now=1_800_000_000_000L
        assertEquals(Evidence.shotName("https://ebay.com/itm/1/",now),Evidence.shotName("https://www.ebay.com/itm/1?utm_source=a",now))
        assertNotEquals(Evidence.shotName("https://ebay.com/itm/1",now),Evidence.shotName("https://ebay.com/itm/2",now))
        assertNotEquals(Evidence.shotName("https://ebay.com/itm/1",now),Evidence.shotName("https://ebay.com/itm/1",now+7*3_600_000))
        assertTrue(Evidence.shotName("https://ebay.com/itm/1",now).matches(Regex("ev-[0-9a-f]{8,16}\\.jpg")))
    }

    @Test fun quickChatsStayTextsAndRealAnswersBecomeReports() {
        assertFalse(ChatMarkdown.isReport("Sure thing, I'll text you when it's ready."))
        assertFalse(ChatMarkdown.isReport("Got it.\n\nWorking on it now.\n\n```followups\n- x\n```"))
        assertTrue(ChatMarkdown.isReport("# The answer\n\nBecause."))
        assertTrue(ChatMarkdown.isReport("| a | b |\n|---|---|\n| 1 | 2 |"))
        assertTrue(ChatMarkdown.isReport("x".repeat(500)))
    }

    @Test fun anAnswerSharesAsPlainTextAndNotifiesWithItsHeadline() {
        val shared=ChatMarkdown.readable(reply)
        assertFalse(shared.contains("```") || shared.contains("**") || shared.contains("Check Amazon too"))
        assertTrue(shared.startsWith("BEST VALUE: THE USED PAIR"))
        assertTrue(shared.contains("Driver/left 36936-TG7-A23 - Used OEM, 30-day returns - \$149.99 - https://www.ebay.com/itm/1"))
        val (head,body)=ChatMarkdown.notice(reply)
        assertEquals("Best value: the used pair, \$215.99",head)
        assertTrue(body.startsWith("Both sensors fit the 2019 to 2022 Pilot."))
        assertFalse(body.contains("\n"))
        assertEquals("" to "Sure thing.",ChatMarkdown.notice("Sure thing."))
        // On a call the answer is said, not shown: no marks, no web addresses, no code.
        assertEquals("The used pair wins. Both fit. See the page.",ChatMarkdown.spoken("# The used pair wins\n\nBoth fit. See [the page](https://x.test/a).\n\n```\nVIN 123\n```\n\n```followups\n- More\n```"))
        assertFalse(body.contains("http"))
    }

    @Test fun theStepsSummaryIsOneQuietLine() {
        assertEquals("",StepWords.summary(emptyList()))
        assertEquals("Searched 2 times · looked at 3 pages · 1 more step",StepWords.summary(listOf("Searched for “a”","Searched for “b”","Read a.com","Opened b.com","Read c.com","Wrote index.html")))
        assertEquals("Searched once",StepWords.summary(listOf("Searched for “a”")))
        assertEquals("2 steps",StepWords.summary(listOf("Made the document","Wrote index.html")))
    }
}
