package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

/** Deep research (the plan, the budget, and the check that a report only cites pages the job read), PDFs from the web, the MCP tool-list cache, and how the file tools turn a call into a file. */
class ResearchReachTest {
    // ------------------------------------------------------------ deep research

    @Test fun onlyARealResearchRequestGetsTheLongBudget() {
        listOf("Do deep research on electric trucks","deep-research the brake pad market for me","Please research the used truck market thoroughly","I want a comprehensive report on shop software",
            "write me a research report about tire prices","Give me an in-depth analysis of the local competition","Make a report with sources on ceramic brake pads","dig deep into this").forEach {assertTrue(it,DeepResearch.wants(it))}
        listOf("What time does the parts store close?","Make me an estimate for brakes","Research brake pads","Text Dave his car is ready","Look up the price of a 2015 Civic alternator","hi","","Can you make a report?").forEach {assertFalse(it,DeepResearch.wants(it))}
        assertFalse(DeepResearch.wants("deep research ".repeat(400)))
        assertEquals(DeepResearch.STOP_MINUTES,DeepResearch.budgetMinutes("deep research on tires please"));assertEquals(0,DeepResearch.budgetMinutes("what is 2+2"))
        val budget=DeepResearch.budget("deep research on tires please")!!
        assertEquals(30,budget.wrapUpMinutes);assertEquals(40,budget.stopMinutes);assertNull(DeepResearch.budget("what is 2+2"))
        // Longer than the ordinary jobs and than building a site.
        assertTrue(budget.stopMinutes>AgentRules.budget(true).stopMinutes && budget.wrapUpMinutes>AgentRules.budget(true).wrapUpMinutes)
        assertEquals("Deep research: ",DeepResearch.SUGGESTION)
        // A bot that starts deep research on its own (the words did not say so) gets the long budget too, from the second round on.
        val started=JSONArray().put(JSONObject().put("type","function_call").put("name","deep_research").put("call_id","c1").put("arguments","{}"))
        assertTrue(DeepResearch.started(started));assertFalse(DeepResearch.started(JSONArray().put(JSONObject().put("type","function_call").put("name","web_fetch"))));assertFalse(DeepResearch.started(JSONArray()))
        assertEquals(40,DeepResearch.budgetFor("what is 2+2",started)!!.stopMinutes);assertEquals(40,DeepResearch.budgetFor("deep research on tires please",JSONArray())!!.stopMinutes);assertNull(DeepResearch.budgetFor("what is 2+2",JSONArray()))
    }

    @Test fun theNotesFileAndThePlanAreReady() {
        assertEquals("research-notes-electric-trucks-in-2026.md",DeepResearch.notesName("Electric trucks in 2026?"))
        assertEquals("research-notes-topic.md",DeepResearch.notesName("???"))
        assertTrue(DeepResearch.notesName("x".repeat(300)).length<=60)
        assertTrue(Rules.fileName(DeepResearch.notesName("Where do I buy ceramic pads & rotors? (cheap)")).endsWith(".md"))
        val start=DeepResearch.notesStart("Which brake pads last longest?")
        assertTrue(start.startsWith("# Research notes: Which brake pads last longest?") && start.contains("## Sources read") && start.contains("## Not found"))
        val guide=DeepResearch.guide("Which brake pads last longest?","research-notes-x.md")
        listOf("research-notes-x.md","research_check","document_pdf","## Sources","Never fill a gap","[1]","file_view","web_fetch").forEach {assertTrue(it,guide.contains(it))}
        assertTrue(guide.contains("Do not ask the owner anything"))
    }

    private fun message(text:String,vararg cited:String)=JSONObject().put("type","message").put("role","assistant").put("content",JSONArray().put(JSONObject().put("type","output_text").put("text",text)
        .put("annotations",JSONArray(cited.map {JSONObject().put("type","url_citation").put("url",it).put("title","t")}))))
    private fun output(tool:String,result:JSONObject)=JSONObject().put("type","function_call_output").put("call_id",tool).put("output",result.put("_receipt",JSONObject().put("id","r1").put("tool",tool)).toString())

    @Test fun thePagesAJobReadAreFoundInItsHistory() {
        val history=JSONArray()
            .put(JSONObject().put("type","function_call").put("name","web_fetch").put("call_id","c1").put("arguments","{\"url\":\"https://never-read.example/arguments-only\"}"))
            .put(output("web_fetch",JSONObject().put("url","https://www.Example.com/Report/?utm_source=x&id=7#top").put("title","Report").put("text","…")))
            .put(output("web_fetch",JSONObject().put("error","The site returned HTTP 403.")))
            .put(output("browser_open",JSONObject().put("result","opened").put("page_now",JSONObject().put("url","https://shop.example/parts/"))))
            .put(message("According to it…","https://news.example/story?ref=home","not a link"))
            .put(JSONObject().put("type","web_search_call").put("action",JSONObject().put("type","open_page").put("url","https://opened.example/p/")))
            .put(JSONObject().put("type","web_search_call").put("action",JSONObject().put("type","search").put("query","x").put("sources",JSONArray().put(JSONObject().put("type","url").put("url","https://listed.example/a")))))
            .put(JSONObject().put("type","function_call_output").put("output","plain text, not json"))
        val read=DeepResearch.readUrls(history)
        assertEquals(setOf("example.com/Report?id=7","shop.example/parts","news.example/story","opened.example/p","listed.example/a"),read)
        // A page only named in a tool's arguments, or never opened, is not "read".
        assertFalse(read.any {it.contains("never-read")})
    }

    @Test fun addressesAreComparedWithoutTrackingBits() {
        assertEquals("example.com/a/b",DeepResearch.normalize("https://www.example.com/a/b/"))
        assertEquals(DeepResearch.normalize("https://example.com/a?utm_medium=x&id=3"),DeepResearch.normalize("http://EXAMPLE.com/a/?id=3#frag"))
        assertEquals("example.com/a?id=3",DeepResearch.normalize("https://example.com/a?id=3&fbclid=zzz&gclid=1"))
        assertEquals(listOf("https://a.com/x","https://b.org/y?z=1"),DeepResearch.urlsIn("See [One](https://a.com/x), and https://b.org/y?z=1. Also https://a.com/x again!"))
        assertTrue(DeepResearch.urlsIn("no links here").isEmpty())
    }

    private val report="""
        # Brake pads: ceramic last longest
        Ceramic pads wear slowest [1].

        ## Wear
        Ceramic pads lasted 70,000 miles in a fleet test [1]. Semi-metallic pads lasted 45,000 [2].

        ## Cost
        Ceramic pads cost about 30% more (https://store.example/pads).

        ## Noise
        Ceramic pads are quieter than any other kind.

        ## Sources
        1. Fleet test - https://fleet.example/study?utm_source=mail
        2. Pad guide - https://guide.example/pads
        3. Invented - https://made-up.example/never-opened
    """.trimIndent()

    @Test fun aReportIsCheckedAgainstWhatWasReallyRead() {
        val read=setOf("fleet.example/study","guide.example/pads","store.example/pads")
        val c=DeepResearch.check(report,read)
        assertEquals(4,c.sources.size)
        assertEquals(listOf("https://made-up.example/never-opened"),c.unread)
        assertEquals(listOf("Noise"),c.uncitedSections)
        assertFalse(c.ok)
        val answer=DeepResearch.checkAnswer(c,read.size)
        assertFalse(answer.getBoolean("ok"));assertEquals(3,answer.getInt("pages_read"))
        assertTrue(answer.getString("result").contains("made-up.example") && answer.getString("result").contains("Noise"))
        // Taking out the invented source and sourcing the noise claim passes; "Sources" itself needs no source of its own.
        val fixed=report.replace("3. Invented - https://made-up.example/never-opened","").replace("quieter than any other kind.","quieter than any other kind [2].")
        val ok=DeepResearch.check(fixed,read)
        assertTrue(ok.ok);assertTrue(DeepResearch.checkAnswer(ok,3).getString("result").contains("You can give the report"))
        // A report with no sources at all fails, and a source the job saw only with different tracking bits still counts.
        assertFalse(DeepResearch.check("# Answer\n\n## Facts\nIt is true.",read).ok)
        assertTrue(DeepResearch.check("# A\n\n## Facts\nTrue (https://fleet.example/study?utm_source=zzz&utm_campaign=1).",read).ok)
        assertTrue(DeepResearch.checkAnswer(DeepResearch.check("# A\n\nplain",read),0).getString("result").contains("no source links"))
    }

    // ------------------------------------------------------------ PDFs from the web

    @Test fun aPdfIsRecognisedByWhatTheServerSaysAndByItsAddress() {
        assertTrue(PdfFetch.isPdf("application/pdf","https://x.org/a"));assertTrue(PdfFetch.isPdf("Application/PDF; charset=binary","https://x.org/a"))
        assertTrue(PdfFetch.isPdf("application/octet-stream","https://x.org/files/Report.PDF"));assertTrue(PdfFetch.isPdf("","https://x.org/a.pdf"))
        assertFalse(PdfFetch.isPdf("application/octet-stream","https://x.org/a.zip"));assertFalse(PdfFetch.isPdf("text/html","https://x.org/a.pdf"));assertFalse(PdfFetch.isPdf("text/html","https://x.org/"))
    }

    @Test fun thePdfSizeLimitIsEnforcedWhileItArrives() {
        assertEquals(20_000_000,Attachments.PDF_LIMIT)
        assertFalse(PdfFetch.tooBig(-1));assertFalse(PdfFetch.tooBig(20_000_000));assertTrue(PdfFetch.tooBig(20_000_001))
        // A server that says nothing about the size (or lies) is still stopped at the limit.
        val out=ByteArrayOutputStream()
        assertEquals(5000,PdfFetch.copyCapped(ByteArrayInputStream(ByteArray(5000)),out,limit=5000));assertEquals(5000,out.size())
        val e=runCatching {PdfFetch.copyCapped(ByteArrayInputStream(ByteArray(5001)),ByteArrayOutputStream(),limit=5000)}.exceptionOrNull()
        assertTrue(e is IllegalArgumentException && e.message!!.contains("larger than"))
        assertTrue(runCatching {PdfFetch.copyCapped(ByteArrayInputStream(ByteArray(100_000)),ByteArrayOutputStream(),limit=40_000)}.isFailure)
        // The message for a big file is plain and says what to do.
        assertTrue(e!!.message!!.contains("Look for a shorter version"))
    }

    @Test fun onlyRealPdfsAreKept() {
        assertTrue(PdfFetch.looksLikePdf("%PDF-1.7\n...".toByteArray()));assertTrue(PdfFetch.looksLikePdf("junk\n\n%PDF-1.4".toByteArray()))
        assertFalse(PdfFetch.looksLikePdf("<html><body>Please sign in</body></html>".toByteArray()));assertFalse(PdfFetch.looksLikePdf(ByteArray(0)))
    }

    @Test fun aFetchedPdfGetsASafeUniqueName() {
        val name=PdfFetch.fileName("https://www.irs.gov/pub/irs-pdf/p463.pdf?x=1")
        assertTrue(name,name.matches(Regex("fetched-irs-gov-p463-[0-9a-f]{4}\\.pdf")))
        assertEquals(name,Rules.fileName(name))
        assertNotEquals(PdfFetch.fileName("https://a.org/x.pdf"),PdfFetch.fileName("https://a.org/y/x.pdf"))
        assertEquals(PdfFetch.fileName("https://a.org/x.pdf"),PdfFetch.fileName("https://a.org/x.pdf"))
        assertTrue(Rules.fileName(PdfFetch.fileName("https://a.org/"+"very-long-name-".repeat(30)+".pdf")).length<=100)
        assertTrue(PdfFetch.fileName("not a url").startsWith("fetched-"))
        // What a bot downloaded is not something it made: it stays out of the Library, while its own PDFs are in.
        assertNull(Library.kindOf(name));assertEquals("document",Library.kindOf("quarterly-report.pdf"))
        val a=PdfFetch.answer("https://a.org/x.pdf",name,2_500_000,12)
        assertTrue(a.getString("result").contains("file_view") && a.getString("result").contains("2.5 MB") && a.getString("result").contains("12 pages"))
        assertFalse(a.has("files") || a.has("name"))    // not shown to the owner as something that was made
    }

    // ------------------------------------------------------------ the MCP tool list

    @Test fun theToolListIsKeptForAFewMinutes() {
        var now=1_000L
        val cache=ToolListCache(ttlMs=300_000,clock={now})
        assertNull(cache.fresh());assertFalse(cache.has("create_page"))
        cache.put(JSONObject("""{"tools":[{"name":"create_page"},{"name":"search"}]}"""))
        assertTrue(cache.has("search") && cache.has("create_page") && !cache.has("delete_all"))
        now+=299_999;assertTrue(cache.has("search"))
        now+=2;assertFalse("an old list is not trusted",cache.has("search"));assertNull(cache.fresh())
        cache.put(JSONObject("""{"tools":[{"name":"new_tool"}]}"""));assertTrue(cache.has("new_tool") && !cache.has("search"))
        cache.clear();assertFalse(cache.has("new_tool"))
        // A clock that goes backwards does not keep a list forever.
        cache.put(JSONObject("""{"tools":[{"name":"a"}]}"""));now-=10_000;assertFalse(cache.has("a"))
        assertFalse(ToolListCache().has("anything"))
    }

    // ------------------------------------------------------------ the file tools

    private val png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")

    @Test fun aWordCallBecomesAWordFile() {
        val built=ReachTools.build("docx_create",JSONObject().put("file_name","Quarterly Report.docx").put("title","Q3").put("blocks_json","""[{"type":"heading","level":1,"text":"Hello"},{"type":"paragraph","text":"World & **more**"}]"""))
        assertEquals("quarterly-report.docx",built.name);assertTrue(built.summary.contains("2 blocks"))
        assertEquals("word",Office.verify(built.name,built.bytes).kind)
        assertEquals("# Q3\n# Hello\nWorld & more",Office.readText(built.name,built.bytes))
        // Markdown works as the content, and a bad call explains itself.
        assertTrue(Office.readText("a.docx",ReachTools.build("docx_create",JSONObject().put("file_name","a").put("title","").put("blocks_json","# Hi\n\n- one\n- two")).bytes).contains("- two"))
        assertTrue(runCatching {ReachTools.build("docx_create",JSONObject().put("file_name","a").put("title","").put("blocks_json",""))}.exceptionOrNull()!!.message!!.contains("no content"))
        assertTrue(runCatching {ReachTools.build("docx_create",JSONObject().put("file_name","   ").put("title","").put("blocks_json","hi"))}.isFailure)
    }

    @Test fun anExcelCallBecomesAnExcelFile() {
        val built=ReachTools.build("xlsx_create",JSONObject().put("file_name","march-budget").put("sheets_json","""[{"name":"Budget","rows":[["Item","Cost"],["Rent",1200],["Parts",3400.5],["Total","=SUM(B2:B3)"]]}]"""))
        assertEquals("march-budget.xlsx",built.name);assertTrue(built.summary.contains("1 sheet") && built.summary.contains("4 rows"))
        val text=Office.readText(built.name,built.bytes)
        assertTrue(text,text.contains("row 4: Total | =SUM(B2:B3) (4600.5)"))
    }

    @Test fun aPowerPointCallBecomesADeckWithItsPicture() {
        var asked=""
        val built=ReachTools.build("pptx_create",JSONObject().put("file_name","spring-promo").put("title","Spring").put("slides_json","""[{"title":"Spring promo"},{"title":"Offer","bullets":["Brake check free","Tires 10% off"],"notes":"Smile","image":"shop.png"}]"""),
            loadImage={name->asked=name;Office.SlideImage("png",png)})
        assertEquals("shop.png",asked);assertEquals("spring-promo.pptx",built.name);assertTrue(built.summary.contains("2 slides"))
        val text=Office.readText(built.name,built.bytes)
        assertTrue(text.contains("## Slide 1\nSpring promo") && text.contains("## Slide 2\nOffer\nBrake check free\nTires 10% off\nNotes: Smile"))
        assertTrue(Office.unzip(built.bytes) {it.startsWith("ppt/media/")}.keys.single()=="ppt/media/image2.png")
        // A missing picture is a plain sentence from the loader.
        val e=runCatching {ReachTools.build("pptx_create",JSONObject().put("file_name","x").put("title","").put("slides_json","""[{"title":"t","bullets":["b"],"image":"gone.jpg"}]"""),loadImage={error("gone.jpg is not in this bot's files.")})}.exceptionOrNull()
        assertTrue(e!!.message!!.contains("not in this bot's files"))
    }

    @Test fun theFileNameIsMadeSafe() {
        assertEquals("brake-estimate-smith.docx",ReachTools.build("docx_create",JSONObject().put("file_name","Brake Estimate (Smith).docx").put("title","").put("blocks_json","hi")).name)
        assertEquals("a.xlsx",ReachTools.build("xlsx_create",JSONObject().put("file_name","../../a.xlsx").put("sheets_json","[[1]]")).name)
        assertTrue(Rules.fileName(ReachTools.build("pptx_create",JSONObject().put("file_name","x".repeat(300)).put("title","").put("slides_json","""[{"title":"t"}]""")).name).length<=100)
    }

    @Test fun aPageCaptureIsShrunkToAReadableSize() {
        assertEquals(720 to 1560,ReachTools.lookSize(1080,2340))
        assertEquals(720 to 405,ReachTools.lookSize(1440,810))
        assertEquals(390 to 844,ReachTools.lookSize(390,844))          // never enlarged
        val tall=ReachTools.lookSize(720,6000);assertEquals(1600,tall.second);assertTrue(tall.first<720)
        assertEquals(1 to 1,ReachTools.lookSize(0,0))
    }

    @Test fun theNewToolsAreDescribedForTheModelAndHaveNoCardOfTheirOwn() {
        assertEquals(setOf("docx_create","xlsx_create","pptx_create","browser_look","phone_calendar_events","phone_contacts_find","phone_alarm_set","phone_timer_set","deep_research","research_check"),ReachTools.names)
        ReachTools.tools.forEach {t->
            assertTrue(t.name,t.description.length in 60..2500)
            assertFalse(t.name+" asks the owner something",t.review)
            // The mutually exclusive route for each file kind is spelled out, so a bot picks the right tool.
            assertTrue(t.name,AgentRules.connectionFor(t.name)==null)
        }
        assertTrue(ReachTools.tools.first {it.name=="docx_create"}.description.contains("document_pdf"))
        assertTrue(ReachTools.tools.first {it.name=="xlsx_create"}.description.contains("=SUM") || ReachTools.tools.first {it.name=="xlsx_create"}.description.contains("formula"))
        // The browser tool follows the browser's busy/free rule through its name.
        assertTrue(ReachTools.names.filter {it.startsWith("browser_")}==listOf("browser_look"))
    }

    @Test fun theOwnerSeesPlainWordsForTheNewSteps() {
        val none=JSONObject()
        assertEquals("Made the document",StepWords.line("docx_create",none));assertEquals("Made the document",StepWords.line("xlsx_create",none));assertEquals("Made the document",StepWords.line("pptx_create",none))
        assertEquals("Looked at the page",StepWords.line("browser_look",none));assertEquals("Checked the phone calendar",StepWords.line("phone_calendar_events",none))
        assertEquals("Looked in the contacts",StepWords.line("phone_contacts_find",none));assertEquals("Set an alarm",StepWords.line("phone_alarm_set",none));assertEquals("Set a timer",StepWords.line("phone_timer_set",none))
        // The research bookkeeping is not worth a line, and a fetched PDF is not something to draw a picture of.
        assertEquals("",StepWords.line("deep_research",none));assertEquals("",StepWords.line("research_check",none))
        assertFalse(Evidence.shootable("https://www.irs.gov/pub/irs-pdf/p463.PDF"));assertTrue(Evidence.shootable("https://www.irs.gov/forms-pubs"))
        // The report format the plan asks for is the one the chat sets: a headline, fenced rows, sources.
        assertTrue(DeepResearch.guide("q question here","notes.md").contains("three backticks and the word rows"))
        assertTrue(ChatMarkdown.isReport("# Brake pads\n\nCeramic wins [1].\n\n## Sources\n1. Fleet test - https://fleet.example/study"))
        val rows=ChatMarkdown.parseRows(listOf("Ceramic pads | Quiet, 70,000 miles | \$48 | https://store.example/pads"))
        assertEquals(1,rows.size);assertEquals("Open",rows[0].action)
    }
}
