package com.alphainternational.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URL
import java.util.Base64

/**
 * Sign-in for any MCP server that follows the MCP authorization spec: protected-resource discovery,
 * authorization-server metadata, dynamic client registration and authorization code + PKCE in the phone browser
 * with a loopback redirect (RFC 8252). Tokens stay in encrypted storage.
 */
class McpOAuth(private val context:Context,private val foreground:()->Boolean) {
    private val refreshLock=Mutex()
    private var listener:ServerSocket?=null
    fun cancel() {runCatching {listener?.close()};listener=null}

    /** Returns the server's config with OAuth endpoints, client registration and tokens added. */
    suspend fun signIn(server:JSONObject,status:suspend (String)->Unit):JSONObject=withContext(Dispatchers.IO) {
        val url=Rules.httpsUrl(server.getString("url"))
        cancel()
        status("Finding "+server.optString("name")+"'s sign-in…")
        val (resource,scopes,metadata)=discover(url)
        val socket=ServerSocket(0,4,InetAddress.getByName("127.0.0.1")).apply {soTimeout=600_000}
        listener=socket
        val redirect="http://127.0.0.1:${socket.localPort}/callback"
        try {
            val registration=register(metadata,redirect,scopes)
            val state=randomUrl();val verifier=randomUrl()
            val challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
            val params=linkedMapOf("response_type" to "code","client_id" to registration.getString("client_id"),"redirect_uri" to redirect,
                "state" to state,"code_challenge" to challenge,"code_challenge_method" to "S256","resource" to resource)
            if(scopes.isNotBlank()) params["scope"]=scopes
            val authorize=Rules.httpsUrl(metadata.getString("authorization_endpoint"))
            val address=authorize+(if(authorize.contains('?')) "&" else "?")+Http.form(params)
            withContext(Dispatchers.Main) {
                status("Approve Alpha in your browser, then come back.")
                context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(address)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            while(true) {
                socket.accept().use {client->
                    client.soTimeout=5000
                    val line=client.getInputStream().bufferedReader().readLine().orEmpty().take(12000)
                    val uri=Uri.parse("http://127.0.0.1"+line.split(' ').getOrNull(1).orEmpty())
                    if(!line.startsWith("GET ") || uri.path!="/callback") {respond(client,false,"");return@use}
                    val query=uri.queryParameterNames.associateWith {uri.getQueryParameter(it).orEmpty()}
                    val code=callbackCode(query,state)
                    respond(client,true,server.optString("name"))
                    status("Approval received. Return to Alpha to finish.")
                    if(!foreground()) Updates.notify(context,"app-signin-"+server.optString("id"),server.optString("name")+" approved","Tap to finish connecting it to Alpha.",destination="connect")
                    awaitSignInForeground(foreground)
                    status("Checking the connection…")
                    val client_=JSONObject(server.toString()).put("auth","oauth").put("token_endpoint",Rules.httpsUrl(metadata.getString("token_endpoint")))
                        .put("client_id",registration.getString("client_id")).put("client_secret",registration.optString("client_secret"))
                        .put("redirect_uri",redirect).put("resource",resource)
                    val tokens=exchange(client_,mapOf("grant_type" to "authorization_code","code" to code,"code_verifier" to verifier,"redirect_uri" to redirect))
                    return@withContext merge(client_,tokens)
                }
            }
            @Suppress("UNREACHABLE_CODE") JSONObject()
        } finally {socket.close();listener=null}
    }

    /** A valid bearer token for [server], refreshing it when it is about to expire. */
    suspend fun token(server:JSONObject,save:suspend (JSONObject)->Unit):String=refreshLock.withLock {
        if(server.optString("auth")!="oauth") return@withLock server.optString("token")
        if(server.optLong("expires_at")==0L || server.optLong("expires_at")>System.currentTimeMillis()+60_000) return@withLock server.getString("token")
        require(server.optString("refresh_token").isNotBlank()) {server.optString("name")+" sign-in expired. Reconnect it in Marketplace."}
        val updated=merge(JSONObject(server.toString()),exchange(server,mapOf("grant_type" to "refresh_token","refresh_token" to server.getString("refresh_token"))))
        save(updated)
        updated.getString("token")
    }

    data class Discovery(val resource:String,val scopes:String,val metadata:JSONObject)
    /** Public, read-only discovery of where and how the server signs users in. Registers nothing. */
    suspend fun discover(url:String):Discovery {
        val resourceMeta=protectedResource(url)
        val issuer=resourceMeta?.optJSONArray("authorization_servers")?.strings()?.firstOrNull()?:origin(url)
        val metadata=authorizationServer(Rules.httpsUrl(issuer))
        require(metadata.optJSONArray("code_challenge_methods_supported")?.strings()?.contains("S256")!=false) {"This service does not support secure phone sign-in."}
        return Discovery(resourceMeta?.optString("resource")?.takeIf {it.isNotBlank()}?:url,
            resourceMeta?.optJSONArray("scopes_supported")?.strings()?.joinToString(" ").orEmpty(),metadata)
    }
    private suspend fun protectedResource(url:String):JSONObject? {
        // The server's 401 names its metadata document; otherwise use the well-known locations.
        val hinted=withContext(Dispatchers.IO) {
            runCatching {
                val connection=URL(url).openConnection() as HttpURLConnection
                connection.requestMethod="POST";connection.doOutput=true;connection.instanceFollowRedirects=false
                connection.connectTimeout=15_000;connection.readTimeout=15_000
                connection.setRequestProperty("Content-Type","application/json");connection.setRequestProperty("Accept","application/json, text/event-stream")
                try {
                    connection.outputStream.use {it.write("""{"jsonrpc":"2.0","id":"probe","method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"Alpha Assistant Android","version":"probe"}}}""".toByteArray())}
                    connection.responseCode
                    resourceMetadataHint(connection.getHeaderField("WWW-Authenticate").orEmpty())
                } finally {connection.disconnect()}
            }.getOrNull()
        }
        val uri=URI(url);val path=uri.rawPath.orEmpty().trimEnd('/')
        val candidates=listOfNotNull(hinted,origin(url)+"/.well-known/oauth-protected-resource"+path,origin(url)+"/.well-known/oauth-protected-resource")
        for(candidate in candidates.distinct()) runCatching {return Http.json(Rules.httpsUrl(candidate))}
        return null
    }
    private suspend fun authorizationServer(issuer:String):JSONObject {
        val uri=URI(issuer);val path=uri.rawPath.orEmpty().trimEnd('/');val base=origin(issuer)
        val candidates=listOf("$base/.well-known/oauth-authorization-server$path","$base/.well-known/openid-configuration$path",
            "$issuer/.well-known/openid-configuration","$base/.well-known/oauth-authorization-server").distinct()
        for(candidate in candidates) {
            val meta=runCatching {Http.json(candidate)}.getOrNull()?:continue
            if(meta.optString("authorization_endpoint").isNotBlank() && meta.optString("token_endpoint").isNotBlank()) return meta
        }
        error("This service did not publish a phone sign-in. It may need an API key instead.")
    }
    private suspend fun register(metadata:JSONObject,redirect:String,scopes:String):JSONObject {
        val endpoint=metadata.optString("registration_endpoint")
        require(endpoint.isNotBlank()) {"This service only allows pre-approved apps to sign in. Use its API key or personal link option if it has one."}
        val body=obj("client_name" to "Alpha Assistant on Android","redirect_uris" to arr(listOf(redirect)),
            "grant_types" to arr(listOf("authorization_code","refresh_token")),"response_types" to arr(listOf("code")),
            "token_endpoint_auth_method" to "none","application_type" to "native")
        if(scopes.isNotBlank()) body.put("scope",scopes)
        val registration=Http.json(Rules.httpsUrl(endpoint),"POST",body=body)
        require(registration.optString("client_id").isNotBlank()) {"The service did not register Alpha for sign-in."}
        return registration
    }
    private suspend fun exchange(client:JSONObject,grant:Map<String,String>):JSONObject {
        val form=grant.toMutableMap();form["client_id"]=client.getString("client_id");form["resource"]=client.optString("resource")
        if(client.optString("client_secret").isNotBlank()) form["client_secret"]=client.getString("client_secret")
        val tokens=JSONObject(Http.text(Rules.httpsUrl(client.getString("token_endpoint")),"POST",headers=mapOf("Accept" to "application/json"),
            body=Http.form(form.filterValues {it.isNotBlank()}),contentType="application/x-www-form-urlencoded"))
        require(tokens.optString("access_token").isNotBlank()) {"The service did not return access. Try connecting again."}
        return tokens
    }
    private fun respond(socket:java.net.Socket,success:Boolean,name:String) {
        val safe=name.replace(Regex("[^A-Za-z0-9 .&-]"),"")
        val html=if(success) "<!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'><title>Alpha Assistant</title><body style='font:20px sans-serif;padding:30px;background:#000;color:#eee'><h1>$safe approved</h1><p>Return to Alpha to finish connecting.</p><a style='color:#fff' href='intent://signin/complete#Intent;scheme=alphaassistant;package=${BuildConfig.APPLICATION_ID};end'>Return to Alpha Assistant</a></body>" else "Unknown callback address."
        val bytes=html.toByteArray()
        socket.getOutputStream().write(("HTTP/1.1 "+(if(success)"200 OK" else "404 Not Found")+"\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray()+bytes)
        socket.getOutputStream().flush()
    }
    companion object {
        fun origin(url:String):String {val uri=URI(url);return "https://"+uri.host.lowercase()}
        /** Reads `resource_metadata="..."` from a WWW-Authenticate challenge. */
        fun resourceMetadataHint(header:String):String? = Regex("resource_metadata=\"([^\"]+)\"").find(header)?.groupValues?.get(1)?.takeIf {it.startsWith("https://")}
        fun callbackCode(params:Map<String,String>,expected:String):String {
            require(params["state"]==expected) {"The sign-in callback could not be verified. Start again."}
            require(params["error"].isNullOrBlank()) {"Access was not granted: "+params["error_description"].orEmpty().ifBlank {params["error"].orEmpty()}}
            return params["code"]?.takeIf {it.isNotBlank()}?:error("No authorization code was returned.")
        }
        fun merge(client:JSONObject,tokens:JSONObject):JSONObject {
            client.put("token",tokens.getString("access_token"))
            tokens.optString("refresh_token").takeIf {it.isNotBlank()}?.let {client.put("refresh_token",it)}
            val expires=tokens.optLong("expires_in",0)
            client.put("expires_at",if(expires>0) System.currentTimeMillis()+expires*1000 else 0L)
            return client
        }
    }
}
