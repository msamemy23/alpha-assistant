package com.alphainternational.assistant

import org.junit.Assert.*
import org.junit.Test

class MediaSpecTest {
    private fun fail(json:String)=assertThrows(IllegalArgumentException::class.java) {MediaSpec.parse(json)}.message.orEmpty()

    @Test fun aReelEditListIsReadAndTimed() {
        val s=MediaSpec.parse("""{"output":"brake-special","clips":[{"file":"a.mp4","start":2,"end":8},{"image":"shop.jpg","seconds":3},{"file":"b.mp4","start":0,"end":4,"speed":2}],
            "text":[{"text":"Brake Special 99 dollars","start":0.5,"end":4,"position":"center"}],"captions":[{"text":"Hello","start":1,"end":2.5}],
            "pictures":[{"file":"logo.png","position":"top-left","scale":0.15}],"audio":[{"file":"music.wav","volume":0.3}]}""")
        assertEquals("brake-special.mp4",s.output)
        assertEquals(1080 to 1920,s.width to s.height)
        // 6 s + 3 s + (4 s at double speed = 2 s)
        assertEquals(11.0,s.total,0.001)
        assertEquals(true,s.clips[1].image)
        assertEquals("center",s.texts[0].position)
        assertEquals(0.15,s.pictures[0].scale,0.001)
        assertEquals(0.3,s.sounds[0].volume,0.001)
        assertTrue(s.sounds[0].loop)
        // Text with no end lasts to the end of the video.
        assertEquals(11.0,MediaSpec.parse("""{"clips":[{"image":"a.jpg","seconds":11}],"text":[{"text":"x"}]}""").texts[0].end,0.001)
    }

    @Test fun badEditListsSayWhatToFix() {
        assertTrue(fail("not json").contains("isn't valid JSON"))
        assertTrue(fail("""{"clips":[]}""").contains("between 1 and"))
        assertTrue(fail("""{"output":"a.mp4"}""").contains("at least one clip"))
        assertTrue(fail("""{"clips":[{"file":"a.mp4","start":5,"end":2}]}""").contains("end must be later"))
        assertTrue(fail("""{"clips":[{"file":"a.mp4","start":0,"end":5,"speed":9}]}""").contains("speed"))
        assertTrue(fail("""{"clips":[{"file":"notes.txt","start":0,"end":5}]}""").contains("can't be used as a video clip"))
        assertTrue(fail("""{"clips":[{"image":"a.mp4","seconds":3}]}""").contains("picture clip"))
        assertTrue(fail("""{"width":1081,"clips":[{"image":"a.jpg","seconds":3}]}""").contains("even"))
        assertTrue(fail("""{"clips":[{"file":"a.mp4","start":0,"end":500}]}""").contains("seconds"))
        assertTrue(fail("""{"clips":[{"file":"a.mp4","start":0,"end":5,"fit":"stretch"}]}""").contains("fit must be"))
        assertTrue(fail("""{"clips":[{"image":"a.jpg","seconds":3}],"text":[{"text":"hi","position":"left"}]}""").contains("position"))
        assertTrue(fail("""{"clips":[{"image":"a.jpg","seconds":3}],"text":[{"text":"hi","color":"red"}]}""").contains("#ffffff"))
        assertTrue(fail("""{"clips":[{"image":"a.jpg","seconds":3}],"audio":[{"file":"a.mp3"},{"file":"b.mp3"},{"file":"c.mp3"},{"file":"d.mp3"}]}""").contains("At most 3"))
        assertTrue(fail("""{"output":"../x.mp4","clips":[{"image":"a.jpg","seconds":3}]}""").contains("simple file name"))
    }

    @Test fun overlayTextIsWrappedToFitTheFrame() {
        val wrapped=MediaSpec.wrap("Brake special this week only, fifty percent off pads and rotors",1080,64)
        val lines=wrapped.split('\n')
        assertTrue(lines.size>1)
        // About 0.86 of the width at roughly half a text height per character: 27 characters per line here.
        assertTrue(lines.all {it.length<=27})
        assertEquals("Brake special this week only, fifty percent off pads and rotors",lines.joinToString(" "))
        assertEquals("Hi",MediaSpec.wrap("  Hi ",1080,64))
        // One very long word is kept whole rather than split.
        assertEquals(1,MediaSpec.wrap("Supercalifragilisticexpialidocious_and_more",300,64).split('\n').size)
    }

    @Test fun captionsAreFoundByTime() {
        val c=listOf(MediaSpec.Caption("one",0.0,1.0),MediaSpec.Caption("two",1.0,2.0))
        assertEquals("one",MediaSpec.captionAt(c,0.5)?.text)
        assertEquals("two",MediaSpec.captionAt(c,1.0)?.text)
        assertNull(MediaSpec.captionAt(c,2.0))
    }

    @Test fun spokenWordsBecomeShortCaptionLines() {
        val words="Your brakes are squealing because the pads are worn. We can fix that today.".split(' ').mapIndexed {i,w->Triple(w,i*0.4,i*0.4+0.35)}
        val lines=MediaSpec.lines(words,maxChars=24)
        assertTrue(lines.all {it.text.length<=24})
        assertEquals("Your brakes are squealing because the pads are worn. We can fix that today.",lines.joinToString(" ") {it.text})
        // A sentence end starts a new line, and lines never run backwards in time.
        assertTrue(lines.any {it.text.endsWith("worn.")})
        assertTrue(lines.zipWithNext().all {(a,b)->a.end<=b.start+0.001})
        assertTrue(MediaSpec.lines(emptyList()).isEmpty())
    }
}
