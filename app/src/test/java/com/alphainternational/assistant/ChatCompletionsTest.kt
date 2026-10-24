package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The translation between Alpha's Responses-style items and the Chat Completions format other AI services speak, and the provider settings around it. */
class ChatCompletionsTest {
    private fun call(id:String,name:String,args:String="{}")=obj("type" to "function_call","call_id" to id,"name" to name,"arguments" to args)
    private fun result(id:String,out:String)=obj("type" to "function_call_output","call_id" to id,"output" to out)
    private fun said(text:String)=obj("type" to "message","role" to "assistant","content" to arr(listOf(obj("type" to "output_text","text" to text))))
    private fun history(vararg items:JSONObject)=JSONArray().also {a->items.forEach {a.put(it)}}

    @Test fun aConversationWithToolCallsBecomesChatMessages() {
        val m=ChatCompletions.messages("Be brief.",history(
            obj("role" to "user","content" to "Read a and fetch b"),
            call("c1","alpha.file_read","{\"name\":\"a\"}"),call("c2","web_fetch"),
            result("c1","A"),result("c2","B"),
            said("Done.")))
        val roles=(0 until m.length()).map {m.getJSONObject(it).getString("role")}
        assertEquals(listOf("system","user","assistant","tool","tool","assistant"),roles)
        val calls=m.getJSONObject(2).getJSONArray("tool_calls")
        assertEquals(2,calls.length())
        assertEquals("file_read",calls.getJSONObject(0).getJSONObject("function").getString("name"))   // the namespace is dropped
        assertEquals("{\"name\":\"a\"}",calls.getJSONObject(0).getJSONObject("function").getString("arguments"))
        assertTrue(m.getJSONObject(2).isNull("content"))
        assertEquals("c1",m.getJSONObject(3).getString("tool_call_id"));assertEquals("B",m.getJSONObject(4).getString("content"))
        assertEquals("Done.",m.getJSONObject(5).getString("content"))
    }

    @Test fun anAnswerBeforeItsToolCallsStaysInOneAssistantMessage() {
        val m=ChatCompletions.messages("",history(obj("role" to "user","content" to "go"),said("Looking it up."),call("c1","web_fetch"),result("c1","page")))
        assertEquals(3,m.length())   // user, assistant (text and the call together), tool
        assertEquals("Looking it up.",m.getJSONObject(1).getString("content"))
        assertEquals(1,m.getJSONObject(1).getJSONArray("tool_calls").length())
    }

    @Test fun notesToTheModelAreSystemMessagesAndReasoningIsLeftOut() {
        val m=ChatCompletions.messages("rules",history(obj("role" to "developer","content" to "Note: keep going."),obj("type" to "reasoning","id" to "r1"),
            obj("type" to "web_search_call","id" to "w1"),obj("role" to "user","content" to "hi")))
        assertEquals(listOf("system","system","user"),(0 until m.length()).map {m.getJSONObject(it).getString("role")})
    }

    @Test fun picturesBecomeImageParts() {
        val parts=arr(listOf(obj("type" to "input_text","text" to "What is this?"),obj("type" to "input_image","image_url" to "data:image/png;base64,AAAA")))
        val m=ChatCompletions.messages("",history(obj("role" to "user","content" to parts)))
        val content=m.getJSONObject(0).getJSONArray("content")
        assertEquals("text",content.getJSONObject(0).getString("type"))
        assertEquals("data:image/png;base64,AAAA",content.getJSONObject(1).getJSONObject("image_url").getString("url"))
        // text only goes as one plain string, the form every service accepts
        val plain=ChatCompletions.messages("",history(obj("role" to "user","content" to arr(listOf(obj("type" to "input_text","text" to "a"),obj("type" to "input_text","text" to "b"))))))
        assertEquals("a\nb",plain.getJSONObject(0).getString("content"))
    }

    @Test fun toolsGoAsPlainFunctions() {
        val t=ChatCompletions.tools(listOf(Tool("file_read","Read a file.",schema("name" to "string"))))
        assertEquals("function",t.getJSONObject(0).getString("type"))
        assertEquals("file_read",t.getJSONObject(0).getJSONObject("function").getString("name"))
        val r=ChatCompletions.request("m",history(obj("role" to "user","content" to "hi")),"sys",listOf(Tool("x","y",schema())))
        assertEquals("m",r.getString("model"));assertTrue(r.getBoolean("stream"));assertEquals(1,r.getJSONArray("tools").length())
        assertFalse(ChatCompletions.request("m",history(),"",emptyList()).has("tools"))
    }

    private fun delta(content:String?=null,calls:List<JSONObject> = emptyList(),finish:String?=null):JSONObject {
        val d=JSONObject();if(content!=null) d.put("content",content);if(calls.isNotEmpty()) d.put("tool_calls",JSONArray(calls.map {it}))
        return obj("choices" to arr(listOf(JSONObject().put("delta",d).also {if(finish!=null) it.put("finish_reason",finish)})))
    }
    private fun piece(index:Int,id:String?,name:String?,args:String?):JSONObject {
        val f=JSONObject();if(name!=null) f.put("name",name);if(args!=null) f.put("arguments",args)
        return JSONObject().put("index",index).also {if(id!=null) it.put("id",id)}.put("function",f)
    }

    @Test fun streamedPiecesAreAssembledIntoAnAnswerAndToolCalls() {
        val c=ChatCompletions.Collector()
        assertEquals("Hel",c.accept(delta("Hel")));assertEquals("lo",c.accept(delta("lo")))
        c.accept(delta(calls=listOf(piece(0,"call_1","file_read","{\"na"))))
        c.accept(delta(calls=listOf(piece(0,null,null,"me\":\"a\"}"),piece(1,"call_2","web_fetch","{}"))))
        c.accept(delta(finish="tool_calls"))
        val out=c.result().getJSONArray("output")
        assertEquals(3,out.length())
        assertEquals("message",out.getJSONObject(0).getString("type"))
        assertEquals("Hello",out.getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals("call_1",out.getJSONObject(1).getString("call_id"));assertEquals("file_read",out.getJSONObject(1).getString("name"))
        assertEquals("a",JSONObject(out.getJSONObject(1).getString("arguments")).getString("name"))
        assertEquals("web_fetch",out.getJSONObject(2).getString("name"))
    }

    @Test fun aCallWithNoDetailsOrBrokenDetailsIsHandledLikeTheOtherService() {
        val none=ChatCompletions.Collector();none.accept(delta(calls=listOf(piece(0,"c","tool_list",null))))
        assertEquals("{}",none.result().getJSONArray("output").getJSONObject(0).getString("arguments"))
        val broken=ChatCompletions.Collector();broken.accept(delta(calls=listOf(piece(0,"c","x","{\"a\":"))))
        assertTrue(JSONObject(broken.result().getJSONArray("output").getJSONObject(0).getString("arguments")).has(ResponsesStream.INVALID))
        val noId=ChatCompletions.Collector();noId.accept(delta(calls=listOf(piece(0,null,"x","{}"))))
        assertTrue(noId.result().getJSONArray("output").getJSONObject(0).getString("call_id").isNotBlank())
    }

    @Test fun aReplyCutOffByLengthOrAnUnfinishedCallIsNotUsed() {
        val long=ChatCompletions.Collector();long.accept(delta("partial"));long.accept(delta(finish="length"))
        assertThrows(ModelCutOff::class.java) {long.result()}
        val unfinished=ChatCompletions.Collector();unfinished.accept(delta(calls=listOf(piece(0,"c",null,"{}"))))
        assertThrows(ModelCutOff::class.java) {unfinished.result()}
    }

    @Test fun anErrorInTheStreamBecomesAnError() {
        val c=ChatCompletions.Collector();c.accept(obj("error" to obj("message" to "model not found")))
        val e=assertThrows(IllegalStateException::class.java) {c.result()}
        assertTrue(e.message.orEmpty().contains("model not found"))
    }

    // ---- provider settings ----

    @Test fun theDefaultIsTheChatGptPlanAndNeedsNothing() {
        val p=Provider.parse(null)
        assertEquals(Provider.CHATGPT,p.kind);assertFalse(p.custom);assertTrue(p.ready())
        assertEquals(Provider.CHATGPT,Provider.parse(obj("kind" to "something else")).kind)
    }

    @Test fun anApiKeyNeedsAKeyAndAModel() {
        assertFalse(Provider(Provider.OPENAI_KEY,model="gpt-4.1").ready())
        assertFalse(Provider(Provider.OPENAI_KEY,apiKey="sk-x").ready())
        assertTrue(Provider(Provider.OPENAI_KEY,apiKey="sk-x",model="gpt-4.1").ready())
        assertEquals("https://api.openai.com/v1",Provider(Provider.OPENAI_KEY).address)
    }

    @Test fun anotherServiceNeedsASecureAddressExceptOnThisPhone() {
        fun p(url:String)=Provider(Provider.COMPATIBLE,url,"","m")
        assertEquals("",p("https://openrouter.ai/api/v1").problem())
        assertEquals("",p("http://localhost:11434/v1").problem())
        assertEquals("",p("http://127.0.0.1:1234/v1").problem())
        assertTrue(p("http://192.168.1.5:11434/v1").problem().contains("https"))
        assertTrue(p("http://example.com/v1").problem().isNotEmpty())
        assertTrue(p("ftp://example.com").problem().isNotEmpty())
        assertTrue(p("").problem().isNotEmpty());assertTrue(p("not a url").problem().isNotEmpty())
        assertTrue(p("https://openrouter.ai/api/v1").ready());assertFalse(p("http://example.com/v1").ready())
        // a model on this phone needs no key
        assertTrue(p("http://localhost:11434/v1").ready())
    }

    @Test fun settingsRoundTripAndSummariesAreShort() {
        val p=Provider(Provider.COMPATIBLE," https://openrouter.ai/api/v1/ ","key"," model-x ")
        val back=Provider.parse(p.json())
        assertEquals("https://openrouter.ai/api/v1",back.baseUrl.trimEnd('/'));assertEquals("model-x",back.model);assertEquals("key",back.apiKey)
        assertTrue(back.summary().contains("openrouter.ai") && back.summary().contains("model-x"))
        assertEquals("Your ChatGPT plan",Provider().summary())
        assertTrue(Provider.presets.all {Provider(Provider.COMPATIBLE,it.url,"","m").problem().isEmpty()})
    }

    @Test fun anApiKeyRequestSendsPlainFunctionToolsNotANamespace() {
        val tools=listOf(Tool("file_read","Read.",schema("name" to "string")))
        val flat=AiRequest.build("m",JSONArray(),"i",tools,AiRequest.Options(namespaced=false)).getJSONArray("tools")
        assertEquals("function",flat.getJSONObject(0).getString("type"))
        val plan=AiRequest.build("m",JSONArray(),"i",tools,AiRequest.Options()).getJSONArray("tools")
        assertEquals("namespace",plan.getJSONObject(0).getString("type"))
    }
}
