package com.alphainternational.assistant

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ForkedAiTest {
    private class Recording:AiGateway {
        val efforts=mutableListOf<String?>();val searchOff=mutableListOf<Boolean>();var asked=0
        override fun cancel() {}
        override fun setEffort(effort:String?) {efforts+=effort}
        override fun setSearchOff(off:Boolean) {searchOff+=off}
        override suspend fun respond(model:String,history:JSONArray,instructions:String,tools:List<Tool>,onText:suspend (String)->Unit):JSONObject {asked++;return obj("output" to JSONArray())}
    }

    @Test fun everyRequestCarriesTheSettingsSetBeforeIt() = runBlocking {
        val base=Recording();val forked=ForkedAi(base)
        forked.setEffort("low");forked.setSearchOff(true)
        forked.respond("m",JSONArray(),"i",emptyList()) {}
        forked.setEffort("medium")
        forked.respond("m",JSONArray(),"i",emptyList()) {}
        assertEquals(2,base.asked)
        assertEquals(listOf<String?>("low","medium"),base.efforts)
        assertEquals(listOf(true,true),base.searchOff)
        // Cancelling the forked connection must not cancel anything else.
        forked.cancel()
    }

    @Test fun aGatewayThatOnlyKnowsTheBasicsStillWorks() = runBlocking {
        val gateway=object:AiGateway {
            override fun cancel() {}
            override suspend fun respond(model:String,history:JSONArray,instructions:String,tools:List<Tool>,onText:suspend (String)->Unit)=obj("output" to JSONArray())
        }
        // The two new optional hooks have do-nothing defaults, so older scripted gateways need no change.
        gateway.setOnRetry {};gateway.setThinkingSink {};gateway.setOnRetry(null);gateway.setThinkingSink(null)
        gateway.setEffort("low");gateway.setSearchOff(false)
        assertEquals(0,gateway.respond("m",JSONArray(),"i",emptyList()) {}.getJSONArray("output").length())
    }
}
