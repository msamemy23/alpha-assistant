package com.alphainternational.assistant

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class VoiceStyleTest {
    @Test fun SamplingPreservesVariationWithoutSelectingTheStartToken() {
        val scores=FloatArray(6563){Float.NEGATIVE_INFINITY}
        scores[1]=0f;scores[2]=0f;scores[6561]=100f
        val rng=Random(7319)
        val samples=(0 until 100).map {VoiceStyle.sample(scores,"Expressive",rng)}.toSet()
        assertEquals(setOf(1L,2L),samples)
    }
    @Test fun NucleusExcludesNegligibleTokensAndAllowsCompletion() {
        val scores=FloatArray(6563){-1000f};scores[6562]=1000f
        assertEquals(6562L,VoiceStyle.sample(scores,"Natural",Random(1)))
        scores[2]=Float.NaN
        assertThrows(IllegalArgumentException::class.java) {VoiceStyle.sample(scores,"Expressive")}
        assertThrows(IllegalArgumentException::class.java) {VoiceStyle.sample(FloatArray(3),"Natural")}
    }
}
