package com.alphainternational.assistant

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * [ChatCompletionsAi] against a tiny web server on this computer that talks the Chat Completions streaming format, the way OpenRouter, Ollama or Groq do.
 * (Written on a plain socket: the unit tests are compiled against Android's library, which has no built-in web server.)
 */
class ChatCompletionsWireTest {
    private class Reply(val code:Int,val body:String,val type:String="text/event-stream")
    private lateinit var socket:ServerSocket
    private val hits=AtomicInteger()
    @Volatile private var path=""
    @Volatile private var auth:String?=null
    @Volatile private var body=JSONObject()
    @Volatile private var respond:(Int)->Reply={Reply(200,"")}

    @Before fun start() {
        socket=ServerSocket(0,10,InetAddress.getByName("127.0.0.1"))
        thread(isDaemon=true) {
            while(!socket.isClosed) {
                val client=try {socket.accept()} catch(_:Exception) {break}
                try {
                    val input=client.getInputStream()
                    fun line():String {val out=ByteArrayOutputStream();while(true) {val b=input.read();if(b<0 || b=='\n'.code) break;if(b!='\r'.code) out.write(b)};return out.toString("UTF-8")}
                    val request=line().split(" ")
                    var length=0;var key:String?=null
                    while(true) {
                        val header=line();if(header.isEmpty()) break
                        val name=header.substringBefore(':').lowercase();val value=header.substringAfter(':').trim()
                        if(name=="content-length") length=value.toInt()
                        if(name=="authorization") key=value
                    }
                    val bytes=ByteArray(length);var read=0
                    while(read<length) {val n=input.read(bytes,read,length-read);if(n<0) break;read+=n}
                    val n=hits.incrementAndGet()
                    path=request.getOrElse(1) {""};auth=key;body=JSONObject(String(bytes))
                    val reply=respond(n)
                    val data=reply.body.toByteArray()
                    client.getOutputStream().apply {
                        write(("HTTP/1.1 ${reply.code} X\r\nContent-Type: ${reply.type}\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n").toByteArray())
                        write(data);flush()
                    }
                } catch(_:Exception) {} finally {runCatching {client.close()}}
            }
        }
    }
    @After fun stop() {socket.close()}

    private val base get()="http://127.0.0.1:"+socket.localPort+"/v1"
    private fun sse(vararg events:String)=events.joinToString("") {"data: $it\n\n"}+"data: [DONE]\n\n"
    private fun history()=JSONArray().put(JSONObject().put("role","user").put("content","What is in the file?"))

    @Test fun aStreamedAnswerAndToolCallComeBackAndTheRequestIsRight() {
        respond={Reply(200,sse(
            "{\"choices\":[{\"delta\":{\"content\":\"Let me \"}}]}",
            "{\"choices\":[{\"delta\":{\"content\":\"look.\",\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"file_read\",\"arguments\":\"{\\\"na\"}}]}}]}",
            "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"me\\\":\\\"a.txt\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}",
            "{\"choices\":[],\"usage\":{\"prompt_tokens\":42,\"completion_tokens\":9}}"))}
        val words=StringBuilder()
        val ai=ChatCompletionsAi(base) {"key-123"}
        val result=runBlocking {ai.respond("some-model",history(),"Be brief.",listOf(Tool("file_read","Read a file.",schema("name" to "string")))) {words.append(it)}}
        assertEquals("Let me look.",words.toString())
        val out=result.getJSONArray("output")
        assertEquals("message",out.getJSONObject(0).getString("type"))
        val call=out.getJSONObject(1)
        assertEquals("file_read",call.getString("name"));assertEquals("call_1",call.getString("call_id"))
        assertEquals("a.txt",JSONObject(call.getString("arguments")).getString("name"))
        assertEquals(42,result.getJSONObject("usage").getInt("input_tokens"))
        // what was sent
        assertEquals("/v1/chat/completions",path);assertEquals("Bearer key-123",auth)
        assertEquals("some-model",body.getString("model"));assertTrue(body.getBoolean("stream"))
        assertEquals("system",body.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertEquals("file_read",body.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"))
    }

    @Test fun aModelOnThisPhoneNeedsNoKey() {
        respond={Reply(200,sse("{\"choices\":[{\"delta\":{\"content\":\"hi\"},\"finish_reason\":\"stop\"}]}"))}
        runBlocking {ChatCompletionsAi(base) {""}.respond("llama",history(),"",emptyList()) {}}
        assertNull(auth)
    }

    @Test fun aBusyServiceIsTriedAgain() {
        respond={n->if(n==1) Reply(429,"{\"error\":{\"message\":\"slow down\"}}","application/json")
            else Reply(200,sse("{\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}"))}
        var retried=0
        val ai=ChatCompletionsAi(base) {"k"};ai.setOnRetry {retried++}
        val result=runBlocking {ai.respond("m",history(),"",emptyList()) {}}
        assertEquals(2,hits.get());assertEquals(1,retried)
        assertEquals("ok",result.getJSONArray("output").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test fun aRefusalIsNotTriedAgainAndAKeyInItsTextIsHidden() {
        respond={Reply(401,"{\"error\":{\"message\":\"bad key sk-abcdefghijklmnop1234\"}}","application/json")}
        val failure=runCatching {runBlocking {ChatCompletionsAi(base) {"k"}.respond("m",history(),"",emptyList()) {}}}.exceptionOrNull()
        assertTrue(failure is ApiFailure);assertEquals(401,(failure as ApiFailure).code)
        assertEquals("a refusal is not retried",1,hits.get())
        assertFalse(failure.message.orEmpty().contains("abcdefghijklmnop1234"))
    }

    @Test fun aModelNameIsRequired() {
        val failure=runCatching {runBlocking {ChatCompletionsAi(base) {"k"}.respond("",history(),"",emptyList()) {}}}.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }
}
