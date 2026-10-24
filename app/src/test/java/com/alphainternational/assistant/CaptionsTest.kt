package com.alphainternational.assistant

import com.alphainternational.assistant.media.WhisperMel
import com.alphainternational.assistant.media.resample
import com.alphainternational.assistant.media.rms
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class CaptionsTest {
    @Test fun speechIsPreparedTheWayWhisperExpects() {
        val tone=FloatArray(16_000*2) {(0.5*sin(2*PI*440*it/16000.0)).toFloat()}
        val mel=WhisperMel.logMel(tone)
        // 80 mel bands x 3000 frames for a 30-second window, all finite and inside Whisper's normalised range.
        assertEquals(80*3000,mel.size)
        assertTrue(mel.all {it.isFinite() && it>-1.5f && it<2.5f})
        // The two seconds of sound are louder than the padded silence after them.
        val frame=100;val quietFrame=2500
        val loud=(0 until 80).sumOf {mel[it*3000+frame].toDouble()};val quiet=(0 until 80).sumOf {mel[it*3000+quietFrame].toDouble()}
        assertTrue(loud>quiet)
    }

    @Test fun soundIsBroughtDownTo16kHzAndSilenceIsRecognised() {
        val oneSecond48k=FloatArray(48_000) {(0.3*sin(2*PI*220*it/48000.0)).toFloat()}
        val down=resample(oneSecond48k,48_000,16_000)
        assertTrue(kotlin.math.abs(down.size-16_000)<=2)
        assertTrue(rms(down)>0.15f)
        assertTrue(rms(FloatArray(16_000))<0.0015f)
    }

    @Test fun wordsBecomeCaptionLinesThatCanBeShiftedIntoAVideo() {
        val words=listOf("Welcome" to 0.0,"to" to 0.4,"Acme" to 0.6,"Auto." to 1.0,"Brakes" to 2.0,"are" to 2.3,"half" to 2.5,"price" to 2.8)
            .map {(w,s)->Triple(w,s,s+0.3)}
        val lines=MediaSpec.lines(words,maxChars=40)
        assertTrue(lines.size>=2)
        assertEquals("Welcome to Acme Auto.",lines.first().text)
        assertEquals(0.0,lines.first().start,0.001)
        assertTrue(lines.all {it.end>it.start})
    }
}
