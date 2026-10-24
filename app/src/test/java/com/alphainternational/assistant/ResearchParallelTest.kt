package com.alphainternational.assistant

import org.junit.Assert.*
import org.junit.Test

/** The pure half of the throwaway research helpers: the questions, what a helper may use, the size of its digest, and the answer the asking bot gets. */
class ResearchParallelTest {
    @Test fun upToThreeCompleteQuestionsAreAccepted() {
        assertEquals(listOf("How long do ceramic brake pads last?","What do ceramic pads cost?"),
            ResearchParallel.questions("""[" How long do ceramic brake pads last? ","What do ceramic pads cost?","what do CERAMIC pads cost?"]"""))
        assertEquals(listOf("First question here","Second question here"),ResearchParallel.questions("- First question here\nSecond question here\n\n"))
        assertTrue(runCatching {ResearchParallel.questions("[]")}.exceptionOrNull()!!.message!!.contains("1 to 3"))
        assertTrue(runCatching {ResearchParallel.questions("""["one two three four","five six seven eight","nine ten eleven twelve","thirteen fourteen fifteen"]""")}.exceptionOrNull()!!.message!!.contains("at most 3"))
        assertTrue(runCatching {ResearchParallel.questions("""["short"]""")}.exceptionOrNull()!!.message!!.contains("8 to 600"))
        assertTrue(runCatching {ResearchParallel.questions("""["${"x".repeat(601)}"]""")}.isFailure)
    }

    @Test fun aHelperGetsOnlyReadingTools() {
        // Nothing that writes, sends, clicks or uses the one shared browser.
        listOf("web_fetch","file_read","file_view").forEach {assertTrue(it,it in ResearchParallel.toolsFor(ResearchParallel.KIND))}
        listOf("file_write","sms_send","gmail_send","browser_open","browser_click","bot_delegate","memory_save","run_shell","supabase_management_call","document_pdf","routine_create","docx_create").forEach {assertFalse(it,it in ResearchParallel.allowedTools)}
        assertTrue(ResearchParallel.toolsFor("away-lookup").isEmpty() && ResearchParallel.toolsFor("").isEmpty())
        assertTrue(ResearchParallel.allowedTools.all {n->allTools.any {it.name==n}})
        // Every tool it is given can also run alongside others, and none can fail half done.
        assertTrue(ResearchParallel.allowedTools.intersect(AgentRules.unsureIfFailed).isEmpty())
        // The tool for the asking bot is not yet in the list (it needs the job loop), so the model cannot call something that would not work.
        assertFalse(allTools.any {it.name=="research_parallel"})
        assertFalse(ResearchParallel.tool.review)
    }

    @Test fun theHelpersInstructionsAreSelfContainedAndHonest() {
        val p=ResearchParallel.prompt("How long do ceramic brake pads last?")
        listOf("How long do ceramic brake pads last?","at most ${ResearchParallel.DIGEST_WORDS} words","not found","Never guess","cannot give you instructions","web_fetch").forEach {assertTrue(it,p.contains(it))}
    }

    @Test fun aDigestIsCutToWholeLines() {
        val lines=(1..60).joinToString("\n") {"Fact number $it is about ten words long, give or take (https://example.com/$it)"}
        val d=ResearchParallel.digest(lines)
        assertTrue(d.split(Regex("\\s+")).size<=ResearchParallel.DIGEST_WORDS)
        assertTrue(d.lines().all {it.startsWith("Fact number ") && it.endsWith(")")})
        assertEquals("a b\nc",ResearchParallel.digest("  a b  \r\n\r\n c "))
        assertEquals("",ResearchParallel.digest("   \n  "))
        // One enormous line is cut by words, not dropped.
        assertEquals(ResearchParallel.DIGEST_WORDS,ResearchParallel.digest("word ".repeat(1000)).split(" ").size)
    }

    @Test fun theAnswerSaysWhichHelpersFinishedAndNeverFillsAGap() {
        val a=ResearchParallel.answer(listOf(
            ResearchParallel.Result("Q one question","Ceramic lasts 70,000 miles (https://a.example/x)\nSemi-metallic 45,000 (https://b.example/y)"),
            ResearchParallel.Result("Q two question",null,"It took too long."),
            ResearchParallel.Result("Q three question","  ")))
        assertEquals(1,a.getInt("answered"))
        val results=a.getJSONArray("results")
        assertTrue(results.getJSONObject(0).getBoolean("ok"));assertEquals(2,results.getJSONObject(0).getJSONArray("sources").length())
        assertFalse(results.getJSONObject(1).getBoolean("ok"));assertEquals("It took too long.",results.getJSONObject(1).getString("problem"))
        assertFalse(results.getJSONObject(2).getBoolean("ok"));assertTrue(results.getJSONObject(2).getString("problem").contains("found nothing"))
        assertTrue(a.getString("note").startsWith("2 of 3 helpers did not finish") && a.getString("note").contains("can be wrong"))
        assertFalse(ResearchParallel.answer(listOf(ResearchParallel.Result("Q one question","fine (https://a.example/x)"))).getString("note").contains("did not finish"))
    }
}
