package com.alphainternational.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The live phone, 2026-10-11: "watch this video and give me its parts with times" had no way to read a video at all. */
class YouTubeTranscriptTest {
    @Test fun aVideoIdComesOutOfEveryKindOfLink() {
        val id="UF8uR6Z6KLc"
        listOf("https://www.youtube.com/watch?v=$id","youtu.be/$id","https://youtu.be/$id?t=58","https://m.youtube.com/watch?feature=share&v=$id","https://www.youtube.com/shorts/$id",
            "https://www.youtube.com/embed/$id","https://www.youtube.com/live/$id?si=abc","  $id  ","https://music.youtube.com/watch?v=$id&list=PL1").forEach {assertEquals(it,id,YouTubeTranscript.videoId(it))}
        listOf("https://example.com/watch?v=$id","hello","https://www.youtube.com/","","https://www.youtube.com/watch?v=short").forEach {assertNull(it,YouTubeTranscript.videoId(it))}
    }

    @Test fun theRequestIsTheIphoneAppsOwnAndNamesTheVideo() {
        val body=YouTubeTranscript.playerRequest("UF8uR6Z6KLc")
        assertEquals("UF8uR6Z6KLc",body.getString("videoId"))
        assertEquals("IOS",body.getJSONObject("context").getJSONObject("client").getString("clientName"))
    }

    private val player=JSONObject("""{
        "playabilityStatus":{"status":"OK"},
        "videoDetails":{"title":"Intro to Large Language Models","author":"Some Channel","lengthSeconds":"3569","shortDescription":"A talk.\n\n0:00 Intro\n0:20 LLM Inference\n0:26 LLM Training\n- 0:42 Dreams\nnot a chapter line"},
        "microformat":{"playerMicroformatRenderer":{"publishDate":"2023-11-23"}},
        "captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[
            {"baseUrl":"https://www.youtube.com/api/timedtext?v=x&lang=ar","languageCode":"ar","name":{"simpleText":"Arabic"}},
            {"baseUrl":"https://www.youtube.com/api/timedtext?v=x&lang=en&kind=asr","languageCode":"en","kind":"asr","name":{"runs":[{"text":"English (auto-generated)"}]}},
            {"baseUrl":"https://www.youtube.com/api/timedtext?v=x&lang=en","languageCode":"en","name":{"simpleText":"English"}},
            {"baseUrl":"https://evil.example.com/api/timedtext?v=x&lang=en","languageCode":"en","name":{"simpleText":"Elsewhere"}}
        ]}}}""")

    @Test fun theBestEnglishTrackIsPickedAndOnlyYouTubeAddressesAreKept() {
        val tracks=YouTubeTranscript.tracks(player)
        assertEquals(3,tracks.size)
        assertTrue(tracks.none {it.url.contains("evil.example.com")})
        assertEquals("the written English captions come before the automatic ones",false,YouTubeTranscript.pick(tracks,"en")!!.auto)
        assertEquals(true,YouTubeTranscript.pick(tracks.filter {it.auto || it.language=="ar"},"en")!!.auto)
        assertEquals("ar",YouTubeTranscript.pick(tracks.filter {it.language=="ar"},"en")!!.language)
        assertNull(YouTubeTranscript.pick(emptyList()))
        assertEquals("English (auto-generated)",tracks.first {it.auto}.name)
    }

    @Test fun captionsReadFromJsonAndFromBothXmlForms() {
        val json="""{"events":[{"tStartMs":0,"dDurationMs":1000},{"tStartMs":1570,"segs":[{"utf8":"[Music]"}]},{"tStartMs":7510,"segs":[{"utf8":"hi"},{"utf8":" there\n"}]},{"tStartMs":9000,"segs":[{"utf8":"\n"}]}]}"""
        assertEquals(listOf(YouTubeTranscript.Cue(1570,"[Music]"),YouTubeTranscript.Cue(7510,"hi there")),YouTubeTranscript.cuesFromJson(json))
        assertTrue(YouTubeTranscript.cuesFromJson("not json").isEmpty())
        val xml3="""<timedtext format="3"><body><p t="1570" d="7390" w="1">[Music]</p><p t="7510" d="1450" w="1" a="1">it&#39;s <s>fine</s> &amp; good</p><p t="9000" d="10">
</p></body></timedtext>"""
        assertEquals(listOf(YouTubeTranscript.Cue(1570,"[Music]"),YouTubeTranscript.Cue(7510,"it's fine & good")),YouTubeTranscript.cuesFromXml(xml3))
        val old="""<transcript><text start="1.5" dur="2">hello &quot;you&quot;</text><text start="61.25" dur="2">next</text></transcript>"""
        assertEquals(listOf(YouTubeTranscript.Cue(1500,"hello \"you\""),YouTubeTranscript.Cue(61250,"next")),YouTubeTranscript.cuesFromXml(old))
    }

    @Test fun speechBecomesLinesWithTimes() {
        assertEquals("0:58",YouTubeTranscript.clock(58_000));assertEquals("5:38",YouTubeTranscript.clock(338_900));assertEquals("1:02:03",YouTubeTranscript.clock(3_723_000))
        val cues=listOf(YouTubeTranscript.Cue(58_000,"Today I want to tell you"),YouTubeTranscript.Cue(61_000,"three stories"),YouTubeTranscript.Cue(90_000,"The first story"),YouTubeTranscript.Cue(95_000,"is about dots"))
        assertEquals("[0:58] Today I want to tell you three stories\n[1:30] The first story is about dots",YouTubeTranscript.format(cues))
        assertEquals("",YouTubeTranscript.format(emptyList()))
    }

    @Test fun chaptersComeFromTheDescriptionOnlyWhenThereAreAtLeastTwo() {
        assertEquals(listOf("0:00" to "Intro","0:20" to "LLM Inference","0:26" to "LLM Training","0:42" to "Dreams"),YouTubeTranscript.chapters(player.getJSONObject("videoDetails").getString("shortDescription")))
        assertTrue(YouTubeTranscript.chapters("Recorded at 5:30 pm\nthanks").isEmpty())
        assertEquals(listOf("1:02:03" to "Q and A","1:30:00" to "End"),YouTubeTranscript.chapters("1:02:03 - Q and A\n1:30:00 End"))
    }

    @Test fun theAnswerCarriesTheFactsTheTranscriptAndPagesThroughALongOne() {
        val cues=(0 until 400).map {YouTubeTranscript.Cue(it*30_000L,"line number $it with some words in it")}
        val track=YouTubeTranscript.pick(YouTubeTranscript.tracks(player))
        val first=YouTubeTranscript.describe("UF8uR6Z6KLc",player,cues,track,0,chunk=2000)
        assertEquals("Intro to Large Language Models",first.getString("title"));assertEquals("Some Channel",first.getString("channel"))
        assertEquals("59:29",first.getString("length"));assertEquals("2023-11-23",first.getString("published"))
        assertEquals(4,first.getJSONArray("chapters").length())
        assertEquals("en",first.getString("language"));assertFalse(first.getBoolean("auto_generated"))
        val text=first.getString("transcript")
        assertTrue(text.startsWith("[0:00] line number 0"))
        assertTrue("a chunk ends at a whole line",text.endsWith("\n") || text.last().isLetterOrDigit())
        assertFalse(first.isNull("next_offset"))
        // reading on from next_offset loses and repeats nothing
        val all=StringBuilder(first.getString("transcript"));var next=first.optInt("next_offset",-1);var guard=0
        while(next>=0 && guard++<100) {
            val part=YouTubeTranscript.describe("UF8uR6Z6KLc",player,cues,track,next,chunk=2000)
            all.append(part.getString("transcript"));next=if(part.isNull("next_offset")) -1 else part.getInt("next_offset")
        }
        assertEquals(YouTubeTranscript.format(cues).trimEnd(),all.toString().trimEnd().replace("\n\n","\n"))
        // a video with no captions says so rather than inventing words
        val none=YouTubeTranscript.describe("UF8uR6Z6KLc",JSONObject(),emptyList(),null,0)
        assertEquals("",none.getString("transcript"))
        assertFalse(none.has("language"))
    }

    @Test fun aVideoThatCannotBePlayedIsExplainedInPlainWords() {
        assertNull(YouTubeTranscript.unplayable(player))
        assertNull(YouTubeTranscript.unplayable(JSONObject()))
        val gone=JSONObject("""{"playabilityStatus":{"status":"ERROR","reason":"Video unavailable"}}""")
        assertTrue(YouTubeTranscript.unplayable(gone)!!.contains("Video unavailable"))
        assertTrue(YouTubeTranscript.unplayable(JSONObject("""{"playabilityStatus":{"status":"LOGIN_REQUIRED"}}"""))!!.contains("age-restricted"))
    }
}
