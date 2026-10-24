package com.alphainternational.assistant

import org.junit.Assert.*
import org.junit.Test

class TextBackTest {
    @Test fun workRequestsGetAnOnItTextThatPromisesATextBack() {
        val line=Acknowledge.line("Make me an estimate for a Camry brake job")
        assertTrue(line.contains("text you") || line.contains("let you know"))
        // The same request always gets the same words, so a resend doesn't read differently.
        assertEquals(line,Acknowledge.line("Make me an estimate for a Camry brake job"))
    }

    @Test fun aGifOnItsOwnIsAReactionNotARequest() {
        assertEquals(SENT_GIF,justFiles(listOf("gif-1700000000000.gif")))
        assertEquals("Take a look at this.",justFiles(listOf("photo-1.jpg","gif-2.gif")))
        assertEquals("",justFiles(emptyList()))
    }

    private val catalog=Gifs.catalog(java.io.File("src/main/assets/animated-emoji.tsv").readText())

    @Test fun botsPickAFittingAnimatedEmojiWithNoAccount() {
        assertTrue(catalog.size>800)
        // An emoji in the request wins, even with a skin tone or extra words around it.
        assertEquals("1f389",Gifs.pick(catalog,"🎉")?.code)
        assertEquals("1f44d_1f3fd",Gifs.pick(catalog,"👍🏽 nice")?.code)
        // A skin tone Google didn't animate falls back to the plain emoji.
        assertEquals("1f389",Gifs.pick(catalog,"🎉🏽")?.code)
        assertEquals("2764_fe0f",Gifs.pick(catalog,"❤️")?.code)
        assertEquals("2764_fe0f",Gifs.pick(catalog,"❤")?.code)
        // Words work too.
        assertEquals("1f44d",Gifs.pick(catalog,"thumbs up")?.code)
        assertEquals("1f389",Gifs.pick(catalog,"party popper")?.code)
        assertEquals("1f525",Gifs.pick(catalog,"fire")?.code)
        assertEquals("1f697",Gifs.pick(catalog,"automobile gif")?.code)
        // Nothing fits: the bot is told to use a plain emoji instead.
        assertNull(Gifs.pick(catalog,"qwzx"))
        assertNull(Gifs.pick(catalog,""))
    }

    @Test fun animatedEmojiAreSafeFileNamesThatPlayBig() {
        val name=Gifs.name(Gifs.pick(catalog,"🎉")!!)
        assertTrue(name,name.matches(Regex("emoji-[a-z0-9-]+-[a-z0-9]{1,4}\\.webp")))
        assertTrue(Rules.fileName(name).isNotBlank())
        assertTrue(Gifs.isEmoji(name) && Gifs.plays(name))
        assertTrue(Gifs.plays("gif-1700000000000.gif") && !Gifs.isEmoji("gif-1700000000000.gif"))
        assertFalse(Gifs.plays("photo-1.jpg"))
        assertTrue(Gifs.on(org.json.JSONObject()))
        assertFalse(Gifs.on(org.json.JSONObject().put("off",true)))
    }
}
