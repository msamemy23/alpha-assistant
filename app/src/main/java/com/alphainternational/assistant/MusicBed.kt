package com.alphainternational.assistant

import com.alphainternational.assistant.media.PcmClip
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/**
 * The music under a video, made by this app instead of by the video tool: the tracks are looped or cut to the video's length, set to their volume,
 * mixed, faded in and out, and written as one plain 44.1 kHz stereo WAV. The video tool then only has to lay one ordinary sound file under the
 * pictures, which is the simplest thing it can do.
 */
object MusicBed {
    const val RATE=44_100
    class Track(val clip:PcmClip,val volume:Double,val loop:Boolean)

    /** Interleaved stereo 16-bit samples for [seconds] of video. */
    fun mix(tracks:List<Track>,seconds:Double,fadeIn:Double,fadeOut:Double):ShortArray {
        val frames=ceil(seconds*RATE).toInt().coerceAtLeast(1)
        val out=ShortArray(frames*2)
        val total=frames.toDouble()/RATE
        for(i in 0 until frames) {
            var l=0.0;var r=0.0
            for(t in tracks) {
                val c=t.clip;val n=c.frames
                if(n==0) continue
                val pos=i.toDouble()*c.sampleRate/RATE
                var i0=floor(pos).toInt();val frac=pos-i0
                var i1=i0+1
                if(t.loop) {i0%=n;i1%=n} else if(i0>=n) continue else if(i1>=n) i1=i0
                val s=c.samples;val ch=c.channels
                val l0=s[i0*ch].toDouble();val r0=s[i0*ch+(if(ch>1) 1 else 0)].toDouble()
                val l1=s[i1*ch].toDouble();val r1=s[i1*ch+(if(ch>1) 1 else 0)].toDouble()
                l+=(l0+(l1-l0)*frac)*t.volume;r+=(r0+(r1-r0)*frac)*t.volume
            }
            val time=i.toDouble()/RATE
            val up=if(fadeIn>0) (time/fadeIn).coerceIn(0.0,1.0) else 1.0
            val down=if(fadeOut>0) ((total-time)/fadeOut).coerceIn(0.0,1.0) else 1.0
            val g=up*down
            out[i*2]=(l*g).coerceIn(-32768.0,32767.0).toInt().toShort()
            out[i*2+1]=(r*g).coerceIn(-32768.0,32767.0).toInt().toShort()
        }
        return out
    }

    /** A standard 44-byte-header WAV: 44.1 kHz, 16-bit, stereo. */
    fun writeWav(file:File,pcm:ShortArray) {
        val dataBytes=pcm.size*2
        val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36+dataBytes).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2)
            .putInt(RATE).putInt(RATE*4).putShort(4).putShort(16).put("data".toByteArray()).putInt(dataBytes)
        RandomAccessFile(file,"rw").use {f->
            f.setLength(0);f.write(header.array())
            val chunk=ByteBuffer.allocate(min(pcm.size,1 shl 16)*2).order(ByteOrder.LITTLE_ENDIAN)
            var at=0
            while(at<pcm.size) {
                val n=min(pcm.size-at,chunk.capacity()/2)
                chunk.clear();for(k in 0 until n) chunk.putShort(pcm[at+k])
                f.write(chunk.array(),0,n*2);at+=n
            }
        }
    }
}
