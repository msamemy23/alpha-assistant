package com.alphainternational.assistant

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.sqrt

class NativeVoiceTest {
    @Test fun OfficialTurboPackGeneratesNonSilentSpeechOnAndroid() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val models=VoiceModels(context)
        assertTrue("Install the official voice pack on the test emulator before this test.",models.ready)
        val tokenizer=TurboTokenizer(File(models.folder,"tokenizer.json"))
        assertArrayEquals(longArrayOf(15496,50256,50256),tokenizer.encode("Hello"))
        assertTrue(tokenizer.encode("[laugh] Hello").contains(50275))
        val start=System.currentTimeMillis()
        Chatterbox(models.folder).use {engine->
            engine.prepare(models.reference())
            val prepared=System.currentTimeMillis()
            val samples=runBlocking {engine.generate("Hello. I am Alpha.")}
            val finished=System.currentTimeMillis()
            assertTrue(samples.size>2400)
            assertTrue(samples.all {it.isFinite()})
            val rms=sqrt(samples.sumOf {it.toDouble()*it}/samples.size)
            assertTrue("Speech must not be silent.",rms>0.0001)
            Wav.write(File(context.cacheDir,"native-voice-test.wav"),samples)
            File(context.cacheDir,"native-voice-test.json").writeText(obj("prepare_ms" to (prepared-start),"generate_ms" to (finished-prepared),
                "audio_seconds" to samples.size/24000.0,"rms" to rms,"runtime" to "Android x86_64 CPU, official Turbo q4").toString(2))
        }
    }
}
