package com.alphainternational.assistant

import org.junit.Assert.*
import org.junit.Test

class CallTurnsTest {
    @Test fun UnmutingDuringAReplyWaitsForAllSpeechToStop() {
        val turns=CallTurns();turns.connect()
        val input=turns.beginInput()!!
        val first=turns.beginOutput()
        assertFalse(turns.isCurrentInput(input))
        turns.setMuted(true);turns.setMuted(false)
        assertNull(turns.beginInput())
        val replacement=turns.beginOutput()
        turns.finishOutput(first)
        assertNull(turns.beginInput())
        turns.finishOutput(replacement)
        assertNotNull(turns.beginInput())
    }
    @Test fun LateRecognitionAfterInterruptCannotBecomeANewRequest() {
        val turns=CallTurns();turns.connect()
        val oldInput=turns.beginInput()!!
        turns.stopInput()
        val nextInput=turns.beginInput()!!
        assertFalse(turns.finishInput(oldInput))
        assertTrue(turns.isCurrentInput(nextInput))
        assertTrue(turns.finishInput(nextInput))
        assertFalse(turns.finishInput(nextInput))
    }
    @Test fun ReviewPromptKeepsTheTaskWaitingAfterItFinishesSpeaking() {
        val turns=CallTurns();turns.connect()
        turns.awaitTask("reviewed-task")
        val prompt=turns.beginOutput();turns.finishOutput(prompt)
        turns.setMuted(true);turns.setMuted(false)
        assertNull(turns.beginInput())
        assertFalse(turns.finishTask("a-different-task"))
        assertTrue(turns.finishTask("reviewed-task"))
        val answer=turns.beginOutput()
        assertNull(turns.beginInput())
        turns.finishOutput(answer)
        assertNotNull(turns.beginInput())
    }
    @Test fun ANewCallRejectsCallbacksAndSpeechCleanupFromThePreviousCall() {
        val turns=CallTurns();turns.connect()
        val oldInput=turns.beginInput()!!
        val oldSpeech=turns.beginOutput()
        val oldSession=turns.session
        turns.end();turns.connect()
        assertNotEquals(oldSession,turns.session)
        val newSpeech=turns.beginOutput()
        turns.finishOutput(oldSpeech)
        assertNull(turns.beginInput())
        assertFalse(turns.finishInput(oldInput))
        turns.finishOutput(newSpeech)
        assertNotNull(turns.beginInput())
    }
    @Test fun MutedAndEndedCallsDoNotRestartRecognitionWhenSpeechFinishes() {
        val turns=CallTurns();turns.connect()
        val speech=turns.beginOutput()
        turns.setMuted(true);turns.finishOutput(speech)
        assertNull(turns.beginInput())
        turns.end();turns.setMuted(false)
        assertNull(turns.beginInput())
    }
}
