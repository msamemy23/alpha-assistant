package com.alphainternational.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class Mcp(private val config:()->JSONObject,private val accessToken:suspend ()->String={config().optString("token")}) {
    private val lock=Mutex()
    private var session=""; private var version="2025-11-25"; private var endpoint=""
    /** The server's tools, always asked for afresh (this is the owner or the bot looking at what the server offers); the answer also refreshes the saved list. */
    suspend fun list():JSONObject=lock.withLock { initialize(); fetchTools() }
    private val tools=ToolListCache()
    private suspend fun fetchTools():JSONObject=rpc("tools/list",JSONObject()).also {tools.put(it)}
    suspend fun call(name:String,args:JSONObject):JSONObject=lock.withLock {
        initialize()
        // The saved list is used; the server is asked again only when the list is old, was reset, or does not have the tool (it may have added it).
        if(!tools.has(name)) fetchTools()
        require(tools.has(name)) { "This tool is not listed by the connected MCP server." }
        rpc("tools/call",obj("name" to name,"arguments" to args))
    }
    fun reset() { session=""; endpoint=""; tools.clear() }
    private suspend fun initialize() {
        val url=config().optString("url"); require(url.isNotBlank()) { "Add an HTTPS MCP endpoint in Marketplace." }
        Rules.httpsUrl(url)
        if(endpoint==url) return
        session=""; endpoint=""; tools.clear()
        val result=rpc("initialize",obj("protocolVersion" to version,"capabilities" to JSONObject(),
            "clientInfo" to obj("name" to "Alpha Assistant Android","version" to BuildConfig.VERSION_NAME)))
        val agreed=result.getString("protocolVersion")
        require(agreed in setOf("2025-11-25","2025-06-18","2025-03-26")) { "This server requires an unsupported MCP version." }
        version=agreed
        rpc("notifications/initialized",JSONObject(),true)
        endpoint=url
    }
    private suspend fun rpc(method:String,params:JSONObject,notification:Boolean=false):JSONObject = withContext(Dispatchers.IO) {
        val c=config(); val url=Rules.httpsUrl(c.getString("url"))
        val id=UUID.randomUUID().toString()
        val body=obj("jsonrpc" to "2.0","method" to method,"params" to params)
        if(!notification) body.put("id",id)
        val connection=URL(url).openConnection() as HttpURLConnection
        connection.requestMethod="POST"; connection.doOutput=true; connection.instanceFollowRedirects=false
        connection.connectTimeout=20_000; connection.readTimeout=60_000
        connection.setRequestProperty("Content-Type","application/json")
        connection.setRequestProperty("Accept","application/json, text/event-stream")
        if(method!="initialize") connection.setRequestProperty("MCP-Protocol-Version",version)
        if(session.isNotBlank()) connection.setRequestProperty("MCP-Session-Id",session)
        val token=accessToken()
        if(token.isNotBlank()) { require(!token.contains('\r') && !token.contains('\n')); connection.setRequestProperty("Authorization","Bearer "+token) }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code=connection.responseCode
            if(code !in 200..299) {
                if(code==404) { session=""; endpoint=""; tools.clear() }
                throw ApiFailure(code,"MCP returned HTTP "+code+". Check the endpoint and its authorization. The action will not be automatically repeated.")
            }
            if(method=="initialize") session=connection.getHeaderField("MCP-Session-Id").orEmpty()
            if(notification) return@withContext JSONObject()
            val reader=connection.inputStream.bufferedReader()
            var message:JSONObject?=null
            if(connection.contentType.orEmpty().startsWith("text/event-stream")) {
                var data=StringBuilder(); var count=0
                while(message==null) {
                    val line=reader.readLine()?:break; count+=line.length
                    require(count<2_000_000) { "MCP response exceeded the phone's size limit." }
                    if(line.isBlank() && data.isNotEmpty()) {
                        val event=JSONObject(data.toString()); data=StringBuilder()
                        if(event.optString("id")==id && (event.has("result") || event.has("error"))) message=event
                    } else if(line.startsWith("data:")) { if(data.isNotEmpty()) data.append('\n'); data.append(line.substring(5).trimStart()) }
                }
                if(message==null && data.isNotEmpty()) {
                    val event=JSONObject(data.toString()); if(event.optString("id")==id) message=event
                }
            } else {
                val raw=reader.readText().take(2_000_000); message=JSONObject(raw)
            }
            val response=message?:error("MCP stream ended before returning the result. Check the service before repeating a write.")
            require(response.optString("id")==id) { "MCP returned a response for a different request." }
            if(response.has("error")) error(Rules.redact(response.getJSONObject("error").optString("message","MCP tool failed."),listOf(token)))
            response.getJSONObject("result")
        } finally { connection.disconnect() }
    }
}

/**
 * A server's tool list, kept for a few minutes. Every use of a tool used to fetch the whole list from the server first (an extra trip each time);
 * now it is fetched once, and again only when it is old, when the server is reset or changes address, or when a tool is not in it (the server may have added one).
 */
class ToolListCache(private val ttlMs:Long=300_000,private val clock:()->Long={System.currentTimeMillis()}) {
    private var list:JSONObject?=null
    private var at=0L
    /** The saved list while it is young enough, else null. */
    fun fresh():JSONObject? = list?.takeIf {clock()-at in 0 until ttlMs}
    fun put(value:JSONObject) {list=value;at=clock()}
    fun clear() {list=null}
    /** Whether the saved (young) list has a tool by this name. */
    fun has(name:String):Boolean = fresh()?.optJSONArray("tools")?.objects().orEmpty().any {it.optString("name")==name}
}
