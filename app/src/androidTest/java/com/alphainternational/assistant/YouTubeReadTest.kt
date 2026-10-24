package com.alphainternational.assistant

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Reads a real video from the real YouTube (live web: if YouTube changes how it answers, this is the test that says so). */
class YouTubeReadTest {
    @Test fun aRealVideoIsReadWithItsWordsAndTimes() {
        val result=runBlocking {YouTubeTranscript.read("https://youtu.be/UF8uR6Z6KLc")}
        assertTrue(result.toString().take(400),result.getString("title").contains("Stanford",true))
        assertTrue(result.getString("length").startsWith("15:"))
        assertFalse(result.optString("transcript_missing"),result.has("transcript_missing"))
        val all=StringBuilder(result.getString("transcript"));var next=if(result.isNull("next_offset")) -1 else result.getInt("next_offset");var guard=0
        while(next>=0 && guard++<20) {
            val part=runBlocking {YouTubeTranscript.read("UF8uR6Z6KLc",next)}
            all.append(part.getString("transcript"));next=if(part.isNull("next_offset")) -1 else part.getInt("next_offset")
        }
        val text=all.toString()
        assertTrue("the transcript has [m:ss] lines: "+text.take(200),Regex("(?m)^\\[\\d+:\\d{2}\\] ").containsMatchIn(text))
        assertTrue(text.contains("Stay hungry",true))
        // The last line is near the end of a 15-minute speech
        val last=Regex("(?m)^\\[(\\d+):(\\d{2})\\]").findAll(text).last()
        assertTrue("last line at "+last.value,last.groupValues[1].toInt() in 13..16)
    }

    @Test fun aLinkThatIsNotAVideoIsRefusedInPlainWords() {
        val error=runCatching {runBlocking {YouTubeTranscript.read("https://example.com/")}}.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message,error.message!!.contains("not a YouTube link"))
    }
}
