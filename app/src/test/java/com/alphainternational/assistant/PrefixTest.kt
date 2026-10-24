package com.alphainternational.assistant

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class PrefixTest {
    private fun tool(name:String)=Tool(name,"does "+name,schema("x" to "string"))
    private val tools=listOf(tool("a"),tool("b"),tool("c"))
    private fun history(rounds:Int)=JSONArray().apply {
        put(obj("role" to "user","content" to "Please do the thing"))
        for(i in 1..rounds) {
            put(obj("type" to "function_call","call_id" to "c$i","name" to "a","arguments" to "{\"x\":\"$i\"}"))
            put(obj("type" to "function_call_output","call_id" to "c$i","output" to "{\"ok\":true,\"n\":$i}"))
        }
    }

    @Test fun twoRoundsOfTheSameJobStartWithIdenticalBytes() {
        val instructions="You are the bot named Alpha.\nHow you work: finish the job."
        val one=Prefix.Round(instructions,tools,history(1),"Right now: Saturday 9:41 AM.\nWhat you've learned: Dave likes short texts.")
        val two=Prefix.Round(instructions,tools,history(2),"Right now: Saturday 9:42 AM.\nWhat you've learned: Dave likes short texts.\nProgress: 1 of 3 done, now: Compare prices.")
        assertTrue("round two must start exactly like round one",two.startsLike(one))
        // Byte for byte: the instructions, the tools, and the saved history of round one are the beginning of round two.
        assertEquals(one.instructions,two.instructions)
        assertEquals(one.toolsText(),two.toolsText())
        val savedStart=one.history.toString().dropLast(1)
        assertTrue(two.input().toString().startsWith(savedStart))
        // Only the very last message differs, and it is never part of the saved history.
        assertNotEquals(one.tail,two.tail)
        val last=two.input().getJSONObject(two.input().length()-1)
        assertEquals("developer",last.getString("role"))
        assertEquals(two.tail,last.getString("content"))
        assertEquals(two.history.length()+1,two.input().length())
        assertFalse(two.history.toString().contains("Right now"))
    }

    @Test fun aChangeInTheInstructionsOrTheToolsBreaksTheStartButTheTailDoesNot() {
        val base=Prefix.Round("instructions",tools,history(1),"t1")
        assertTrue(Prefix.Round("instructions",tools,history(2),"completely different").startsLike(base))
        assertFalse(Prefix.Round("instructions!",tools,history(2),"t1").startsLike(base))
        assertFalse(Prefix.Round("instructions",tools+tool("d"),history(2),"t1").startsLike(base))
        assertFalse(Prefix.Round("instructions",tools.reversed(),history(2),"t1").startsLike(base))
        // Rewriting an earlier saved item breaks it too.
        val edited=history(2);edited.put(0,obj("role" to "user","content" to "Please do another thing"))
        assertFalse(Prefix.Round("instructions",tools,edited,"t1").startsLike(base))
    }

    @Test fun addingToolGroupsKeepsTheStartOfTheListAndOnlyThatPartChanges() {
        val pool=allTools
        val first=ToolGroups.visible(pool,emptyList())
        val later=ToolGroups.append(first,pool,listOf("documents"))
        assertEquals(Prefix.toolsText(first),Prefix.toolsText(later).lines().take(first.size).joinToString("\n"))
        assertNotEquals(Prefix.toolsText(first),Prefix.toolsText(later))
    }

    @Test fun oneCacheKeyPerBotNotPerTask() {
        assertEquals(Prefix.cacheKey("Alpha"),Prefix.cacheKey("Alpha"))
        assertNotEquals(Prefix.cacheKey("Alpha"),Prefix.cacheKey("Personal"))
        assertEquals("alpha-customerdesk",Prefix.cacheKey("Customer Desk"))
        assertEquals("alpha-bot",Prefix.cacheKey(" ! "))
        assertTrue(Prefix.cacheKey("A".repeat(60)).length<=30)
    }

    @Test fun theRoundLogFingerprintShowsWhenTheStartChanged() {
        val one=Prefix.fingerprint("instructions",tools,3)
        assertEquals(one,Prefix.fingerprint("instructions",tools,3))
        assertNotEquals(one,Prefix.fingerprint("instructions 2",tools,3))
        assertNotEquals(one,Prefix.fingerprint("instructions",tools.drop(1),3))
        assertTrue(one.startsWith("pfx=") && one.contains(" tl=") && one.endsWith("hist=3"))
        assertEquals(8,Prefix.hash("x").length)
    }

    @Test fun oldResultsAreShortenedInStepsSoTheStartStaysTheSameForSeveralRounds() {
        fun big(n:Int)=JSONArray().apply {
            put(obj("role" to "user","content" to "go"))
            for(i in 1..n) {
                put(obj("type" to "function_call","call_id" to "c$i","name" to "web_fetch","arguments" to "{}"))
                put(obj("type" to "function_call_output","call_id" to "c$i","output" to obj("text" to "x".repeat(900),"n" to i).toString()))
            }
        }
        fun shortened(n:Int)=TaskProtocol.compact(big(n),limit=1000,keep=8,stride=8).objects().count {it.optString("output").contains("trimmed")}
        // Without steps, every new result shortens one more old result; with steps of 8 the set stays put for 8 rounds.
        fun shortenedPlain(n:Int)=TaskProtocol.compact(big(n),limit=1000,keep=8).objects().count {it.optString("output").contains("trimmed")}
        assertEquals(listOf(3,4,5),(11..13).map {shortenedPlain(it)})
        val stepped=(24..31).map {shortened(it)}
        assertEquals(List(8) {16},stepped)
        assertEquals(24,shortened(32))
    }

    @Test fun theInstructionsTemplateMarksOnlyRealGroupsAndEveryMarkerIsClosed() {
        val file=File("src/main/java/com/alphainternational/assistant/Controller.kt")
        assumeTrue("source not found from "+File(".").absolutePath,file.isFile)
        val text=file.readText()
        val opens=Regex("^\\s*\\[\\[(\\w+)\\]\\]\\s*$",RegexOption.MULTILINE).findAll(text).map {it.groupValues[1]}.toList()
        val closes=Regex("^\\s*\\[\\[/(\\w+)\\]\\]\\s*$",RegexOption.MULTILINE).findAll(text).map {it.groupValues[1]}.toList()
        assertTrue(opens.isNotEmpty())
        assertEquals(opens,closes)
        opens.forEach {assertNotNull("unknown group $it",ToolGroups.find(it))}
        // Nothing that changes from round to round is left in the stable part.
        val stable=text.substringAfter("private fun instructions(task:TaskRecord,groups:List<String> = emptyList()):String {").substringBefore("private fun volatileNotes")
        listOf("__TIME__","__REACTIONS__","\$memory","\$connectionInfo").forEach {assertFalse(it,stable.contains(it))}
    }
}

class BriefTest {
    private val text="a\n[[media]]\nvideo stuff\n[[/media]]\nb\n[[studio]]\nsite\nmore site\n[[/studio]]\nc\n"
    @Test fun sectionsOfGroupsThatAreOffAreTakenOutAndMarkersNeverReachTheModel() {
        assertEquals("a\nb\nc\n",Brief.sections(text,emptyList()))
        assertEquals("a\nvideo stuff\nb\nc\n",Brief.sections(text,listOf("media")))
        assertEquals("a\nvideo stuff\nb\nsite\nmore site\nc\n",Brief.sections(text,listOf("media","studio")))
        listOf(emptyList(),listOf("media"),listOf("media","studio")).forEach {assertTrue(Brief.markersIn(Brief.sections(text,it)).isEmpty())}
    }
    @Test fun indentedMarkersWorkAndTextWithoutMarkersIsUntouched() {
        assertEquals("x\ny",Brief.sections("x\n    [[a]]\n    inside\n    [[/a]]\ny",emptyList()))
        assertEquals("no markers [here]\nat all",Brief.sections("no markers [here]\nat all",emptyList()))
    }
    @Test fun theNotesTellTheModelAboutGroupsAndThePlan() {
        assertTrue(Brief.NOTES.contains("tools_enable") && Brief.NOTES.contains("plan_set") && Brief.NOTES.contains("plan_update"))
        ToolGroups.groups.forEach {assertFalse(Brief.NOTES.contains("[["))}
    }
}
