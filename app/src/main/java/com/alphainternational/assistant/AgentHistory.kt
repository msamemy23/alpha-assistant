package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject

object AgentHistory {
    fun pending(history:JSONArray):List<JSONObject> {
        val items=history.objects()
        val done=items.filter { it.optString("type")=="function_call_output" }.map { it.optString("call_id") }.toSet()
        return items.filter { it.optString("type")=="function_call" && it.optString("call_id") !in done }
    }
    fun appendOutput(history:JSONArray,call:JSONObject,result:String) {
        history.put(obj("type" to "function_call_output","call_id" to call.getString("call_id"),"output" to result))
    }
    fun effectKey(call:JSONObject):String=digest(call.optString("call_id")+"|"+call.optString("name")+"|"+call.optString("arguments"))
    fun canRepeat(effect:JSONObject?):Boolean=effect==null || effect.optString("status") in setOf("approved","awaiting_review")
}
