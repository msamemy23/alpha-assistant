package com.alphainternational.assistant

import org.junit.Assert.*
import org.junit.Test

class ReactTest {
    @Test fun aReactionIsOneEmojiNoMatterWhatWasSent() {
        assertEquals("👍",Reactions.clean("👍"))
        assertEquals("❤️",Reactions.clean("  ❤️ love it"))
        assertEquals("👍🏽",Reactions.clean("👍🏽"))
        assertEquals("👩‍🔧",Reactions.clean("👩‍🔧 mechanic"))
        // Words and letters aren't reactions.
        assertEquals("",Reactions.clean("thumbs up"))
        assertEquals("",Reactions.clean(""))
        // Everything in the tap row is one clean emoji.
        Reactions.quick.forEach {assertEquals(it,Reactions.clean(it))}
    }

    @Test fun reactionsSurviveSavingAndLoading() {
        val m=Message("1","assistant","Your estimate is ready.","t1",bot="Alpha",at=5,react="👍")
        assertEquals("👍",Message.parse(m.json()).react)
        assertEquals("",Message.parse(m.copy(react="").json()).react)
        // Texts saved before reactions existed still load.
        assertEquals("",Message.parse(m.json().apply {remove("react")}).react)
    }

    @Test fun theBotSeesWhatTheOwnerReactedTo() {
        val texts=listOf(
            Message("1","assistant","Your estimate for the Camry is ready.","t1",bot="Alpha",at=1,react="👍"),
            Message("2","user","thanks","t2",at=2,react="❤️"),
            Message("3","assistant","Want me to text Maria?","t2",bot="Alpha",at=3))
        val note=Reactions.recent(texts)
        assertTrue(note,note.contains("👍 on “Your estimate for the Camry is ready.”"))
        // Only the owner's reactions to the bot's texts count; the bot's own tap-backs aren't repeated back to it.
        assertFalse(note.contains("❤️"))
        assertEquals("",Reactions.recent(emptyList()))
        assertEquals("",Reactions.recent(texts.map {it.copy(react="")}))
    }
}
