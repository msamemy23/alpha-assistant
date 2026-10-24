package com.alphainternational.assistant

import com.alphainternational.assistant.media.PcmClip
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.abs

class MusicBedTest {
    // One second of a constant level, mono, at 44.1 kHz or 48 kHz.
    private fun flat(level:Int,rate:Int=44_100,seconds:Double=1.0,channels:Int=1)=PcmClip(rate,channels,ShortArray((rate*seconds).toInt()*channels) {level.toShort()})

    @Test fun aShortTrackLoopsToTheLengthOfTheVideo() {
        val out=MusicBed.mix(listOf(MusicBed.Track(flat(1000),0.5,true)),3.0,0.0,0.0)
        assertEquals(3*44_100*2,out.size)
        // Level 1000 at volume 0.5, both channels, at the start, middle and very end of the third second.
        listOf(0,44_100*2+10,3*44_100*2-2).forEach {assertEquals(500,out[it].toInt())}
    }

    @Test fun aTrackThatIsNotLoopedEndsAndLeavesSilence() {
        val out=MusicBed.mix(listOf(MusicBed.Track(flat(1000),1.0,false)),2.0,0.0,0.0)
        assertEquals(1000,out[100].toInt())
        assertEquals(0,out[44_100*2+400].toInt())
    }

    @Test fun fadesGoFromSilenceUpAndBackDown() {
        val out=MusicBed.mix(listOf(MusicBed.Track(flat(10_000),1.0,true)),4.0,1.0,1.0)
        assertEquals(0,out[0].toInt())
        assertTrue(abs(out[(0.5*44_100).toInt()*2].toInt()-5_000)<=60)
        assertEquals(10_000,out[2*44_100*2].toInt())
        assertTrue(abs(out[(3.5*44_100).toInt()*2].toInt()-5_000)<=60)
        assertTrue(abs(out[out.size-2].toInt())<10)
    }

    @Test fun tracksAreMixedAndNeverWrapAround() {
        val loud=MusicBed.mix(listOf(MusicBed.Track(flat(30_000),1.0,true),MusicBed.Track(flat(30_000),1.0,true)),1.0,0.0,0.0)
        // 60,000 would overflow a 16-bit sample; it is held at the maximum instead.
        assertEquals(32_767,loud[1000].toInt())
        val quiet=MusicBed.mix(listOf(MusicBed.Track(flat(1000),1.0,true),MusicBed.Track(flat(2000),0.5,true)),1.0,0.0,0.0)
        assertEquals(2000,quiet[1000].toInt())
    }

    @Test fun otherSampleRatesAndStereoAreBroughtTo44100Stereo() {
        val stereo=PcmClip(48_000,2,ShortArray(48_000*2) {i->(if(i%2==0) 4000 else -4000).toShort()})
        val out=MusicBed.mix(listOf(MusicBed.Track(stereo,1.0,false)),1.0,0.0,0.0)
        assertEquals(44_100*2,out.size)
        assertEquals(4000,out[500].toInt())
        assertEquals(-4000,out[501].toInt())
    }

    @Test fun theWavFileHasAStandardHeaderAndAllTheSamples() {
        val file=File.createTempFile("bed",".wav")
        try {
            val pcm=MusicBed.mix(listOf(MusicBed.Track(flat(1234),1.0,true)),0.5,0.0,0.0)
            MusicBed.writeWav(file,pcm)
            val bytes=file.readBytes()
            assertEquals(44+pcm.size*2,bytes.size)
            assertEquals("RIFF",String(bytes,0,4));assertEquals("WAVE",String(bytes,8,4));assertEquals("data",String(bytes,36,4))
            fun u32(at:Int)=(bytes[at].toInt() and 255) or ((bytes[at+1].toInt() and 255) shl 8) or ((bytes[at+2].toInt() and 255) shl 16) or ((bytes[at+3].toInt() and 255) shl 24)
            assertEquals(44_100,u32(24));assertEquals(pcm.size*2,u32(40))
            assertEquals(1234,(bytes[44].toInt() and 255) or (bytes[45].toInt() shl 8))
        } finally {file.delete()}
    }
}
