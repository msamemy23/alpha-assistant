package com.alphainternational.assistant

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

/** The AI service's own catch-all error is recognised, retried by itself, and the job is asked a little differently before the owner is told anything. */
class ServiceGlitchTest {
    private val models=ModelPlan.catalog(JSONArray("""[
        {"slug":"astra","display_name":"Astra","visibility":"list","supported_reasoning_levels":["low","medium","high","xhigh"]},
        {"slug":"sol","display_name":"Sol","visibility":"list","supported_reasoning_levels":["low","medium","high"]},
        {"slug":"luna","display_name":"Luna","visibility":"list","supported_reasoning_levels":["minimal","low","medium"]}
    ]"""))
    private val message="An error occurred while processing your request. You can retry your request, or contact us through our help center at help.openai.com if the error persists. Please include the request ID req_427282890."

    @Test fun theCatchAllErrorIsRecognisedAndRetriedLater() {
        listOf(message,"ChatGPT returned HTTP 500: "+message,"The server_error code","The server had an error while processing your request.").forEach {
            assertTrue(it,TaskProtocol.serviceGlitch(it)); assertTrue(it,TaskProtocol.transient(it))
        }
        // Real problems are not mistaken for it.
        listOf("HTTP 429: usage limit reached","The model returned no answer. Resume the saved task.","Choose a supported text, PDF or image file.","").forEach {
            assertFalse(it,TaskProtocol.serviceGlitch(it))
        }
        assertFalse(TaskProtocol.transient("usage limit reached. You can retry your request tomorrow"))
    }

    @Test fun theOwnerSeesPlainWordsNotARequestId() {
        val said=TaskProtocol.plainSnag(message)
        assertEquals("ChatGPT had a problem on its side and couldn't finish this one.",said)
        assertFalse(said.contains("req_"))
        // Other snags keep their wording.
        assertEquals("I couldn't reach ChatGPT just now (connection trouble).",TaskProtocol.plainSnag("ChatGPT returned HTTP 503: busy"))
        assertEquals("I got stuck: the site is down.",TaskProtocol.plainSnag("the site is down."))
    }

    @Test fun theLadderAsksTheSameThenWithoutSearchThenLowerThenOtherModels() {
        val ways=ModelPlan.steadier(AiSettings(),models,"astra","high",true,"astra")
        assertEquals(ModelPlan.Ask("astra","high",true),ways.first())
        assertEquals(ModelPlan.Ask("astra","high",false),ways[1])
        assertEquals(ModelPlan.Ask("astra","medium",false),ways[2])
        // Then the Everyday and Quick models (each at the level its tier uses), never repeating a way.
        assertTrue(ways.drop(3).all {it.model!="astra" || it.effort!="medium"})
        assertEquals(ways.size,ways.toSet().size)
        assertTrue(ways.none {it.model.isBlank()})
        assertTrue(ways.drop(1).none {it.search})
        assertEquals("luna",ways.last().model)
        assertEquals("low",ways.last().effort)
    }

    @Test fun theLadderNeverRepeatsOrGoesBelowLow() {
        val low=ModelPlan.steadier(AiSettings(),models,"luna","low",false,"luna")
        assertEquals(ModelPlan.Ask("luna","low",false),low.first())
        assertEquals(low.size,low.toSet().size)
        // Nothing lower than "low" to try, and search is already off: the current ask is not listed twice.
        assertTrue(low.none {it.model=="luna" && it.effort=="minimal"})
        // A job with no model name yet never asks for a blank model.
        assertTrue(ModelPlan.steadier(AiSettings(),emptyList(),"","",true,"").all {it.model.isNotBlank()})
    }

    @Test fun aFixedModelStillGetsTheOtherTiersAsAFallback() {
        val fixed=AiSettings(model="astra",effort="high")
        val ways=ModelPlan.steadier(fixed,models,"astra","high",true,"astra")
        assertTrue(ways.any {it.model!="astra"})
    }
}
