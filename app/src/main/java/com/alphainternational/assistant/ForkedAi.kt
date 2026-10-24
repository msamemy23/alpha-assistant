package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * A connection that makes a fresh one for every request (keeping what the first learned about the plan), so callers that work at the same time, like
 * several bots finishing together and each looking back at what they learned, never share one connection. With a scripted model (tests) it just passes
 * the request on.
 */
class ForkedAi(private val base:AiGateway):AiGateway {
    @Volatile private var effort:String?=null
    @Volatile private var searchOff=false
    override suspend fun respond(model:String,history:JSONArray,instructions:String,tools:List<Tool>,onText:suspend (String)->Unit):JSONObject {
        val gateway=base.fork()
        gateway.setEffort(effort);gateway.setSearchOff(searchOff)
        return gateway.respond(model,history,instructions,tools,onText)
    }
    override fun cancel() {}
    override fun setEffort(effort:String?) {this.effort=effort}
    override fun setSearchOff(off:Boolean) {searchOff=off}
}
