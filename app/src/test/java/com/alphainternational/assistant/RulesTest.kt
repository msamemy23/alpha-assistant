package com.alphainternational.assistant

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class RulesTest {
    @Test fun callbackRequiresSameAttemptAndRegistration() {
        val good=mapOf("state" to "expected","code" to "example-code","client_id" to "issued-client")
        assertEquals("issued-client",Rules.validateCallback(good,"expected",null))
        assertEquals("issued-client",Rules.validateCallback(good-"client_id","expected","issued-client"))
        assertThrows(IllegalArgumentException::class.java) { Rules.validateCallback(good,"different",null) }
        assertThrows(IllegalArgumentException::class.java) { Rules.validateCallback(good,"expected","other-client") }
        assertThrows(IllegalArgumentException::class.java) { Rules.validateCallback(good+("error" to "denied"),"expected",null) }
        assertThrows(IllegalArgumentException::class.java) { Rules.validateCallback(good-"code","expected",null) }
        assertThrows(IllegalArgumentException::class.java) { Rules.validateCallback(good+("client_id" to "dynamic_agent_client"),"expected",null) }
    }
    @Test fun URLsCannotCarryPlaintextCredentialsOrWrongProtocol() {
        assertEquals("https://example.com",Rules.origin("https://example.com/login"))
        assertThrows(IllegalArgumentException::class.java) { Rules.httpsUrl("http://example.com") }
        assertThrows(IllegalArgumentException::class.java) { Rules.httpsUrl("https://user:password@example.com") }
        assertThrows(IllegalArgumentException::class.java) { Rules.httpsUrl("javascript:alert(1)") }
        assertThrows(IllegalArgumentException::class.java) { Rules.httpsUrl("https://example.com:8080") }
    }
    @Test fun FilesCannotEscapeWorkspace() {
        assertEquals("customer-follow-up.txt",Rules.fileName("customer-follow-up.txt"))
        // Generated invoices and documents must open and share from Chat and Files.
        assertEquals("INV-1A2B3C4D.pdf",Rules.fileName("INV-1A2B3C4D.pdf"))
        assertThrows(IllegalArgumentException::class.java) { Rules.textFileName("INV-1A2B3C4D.pdf") }
        listOf("../private.txt","/tmp/file.txt","..\\private.txt","folder/note.md","notes.exe","notes","note..txt").forEach {
            assertThrows(IllegalArgumentException::class.java) { Rules.fileName(it) }
        }
    }
    @Test fun SecretsAreRemovedBeforeToolContextAndLogs() {
        val secret="customer-password-123"
        val text=Rules.redact("password="+secret+"; Bearer opaque-token.123; eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1In0.signature",listOf(secret))
        assertFalse(text.contains(secret)); assertFalse(text.contains("opaque-token")); assertFalse(text.contains("eyJ"))
        assertTrue(text.contains("Bearer [secret removed]"))
    }
    @Test fun ReplaySkipsCallsWithSavedResultsAndPreservesReasoning() {
        val history=JSONArray().put(obj("type" to "reasoning","id" to "reasoning-item"))
        val call=obj("type" to "function_call","name" to "file_write","call_id" to "call-1","arguments" to "{}")
        history.put(call)
        assertEquals(1,AgentHistory.pending(history).size)
        AgentHistory.appendOutput(history,call,"saved")
        assertTrue(AgentHistory.pending(history).isEmpty())
        assertEquals("reasoning",history.getJSONObject(0).getString("type"))
        history.put(obj("type" to "function_call","name" to "gmail_send","call_id" to "call-2","arguments" to "{}"))
        assertEquals("call-2",AgentHistory.pending(history).single().getString("call_id"))
    }
    @Test fun InterruptedMutationCannotBeAutomaticallyRepeated() {
        assertTrue(AgentHistory.canRepeat(null))
        assertTrue(AgentHistory.canRepeat(obj("status" to "approved")))
        assertFalse(AgentHistory.canRepeat(obj("status" to "started")))
        assertFalse(AgentHistory.canRepeat(obj("status" to "done")))
    }
    @Test fun TasksAndMemoryRoundTripAcrossProcessRestarts() {
        val task=TaskRecord("id","A task","original request","Paused","Step saved",123,
            history=JSONArray().put(obj("role" to "user","content" to "request")).toString(),trace=listOf("files list"),
            effects=obj("effect" to obj("status" to "done","result" to "ok")).toString(),workspace="Personal",routineId="test-routine")
        assertEquals(task,TaskRecord.parse(task.json()))
        val memory=Memory("m","Tone","Keep replies short","User",456,"Alpha")
        assertEquals(memory,Memory.parse(memory.json()))
    }
}
