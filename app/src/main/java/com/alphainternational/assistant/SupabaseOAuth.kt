package com.alphainternational.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Base64

/** Per-installation DCR + PKCE for the official hosted MCP. Tokens stay in SecureStore. */
class SupabaseOAuth(private val context:Context,private val get:()->JSONObject,
    private val save:(JSONObject)->Unit,private val foreground:()->Boolean) {
    private val refreshLock=Mutex()
    private var listener:ServerSocket?=null
    fun cancel(){runCatching {listener?.close()};listener=null}
    suspend fun signIn(project:String,organization:String="",status:suspend (String)->Unit):JSONObject=withContext(Dispatchers.IO) {
        val ref=project.trim();require(ref.matches(Regex("[a-z0-9]{20}"))) {"Enter a Supabase project ID."}
        val org=organization.trim();require(org.isEmpty() || org.matches(Regex("[a-z0-9_-]{3,80}"))) {"Enter an organization slug or leave it blank."}
        cancel()
        val resource=Http.json("https://mcp.supabase.com/.well-known/oauth-protected-resource/mcp")
        require(resource.getString("resource")==RESOURCE && resource.getJSONArray("authorization_servers").strings().contains(ISSUER)) {"Supabase authorization discovery changed. Recheck the connector."}
        val metadata=Http.json("$ISSUER/.well-known/oauth-authorization-server")
        verifyMetadata(metadata)
        val previous=get()
        val oldPort=runCatching {Uri.parse(previous.optString("redirect_uri")).port}.getOrDefault(-1)
        val server=runCatching {require(oldPort in 1024..65535);ServerSocket(oldPort,4,InetAddress.getByName("127.0.0.1"))}
            .getOrElse {ServerSocket(0,4,InetAddress.getByName("127.0.0.1"))}.apply {soTimeout=600000}
        listener=server
        val redirect="http://127.0.0.1:${server.localPort}/supabase/callback"
        val candidate=obj("project" to ref,"url" to endpoint(ref),"redirect_uri" to redirect,
            "token_endpoint" to metadata.getString("token_endpoint"),"resource" to RESOURCE,"auth_type" to "oauth",
            "organization_slug" to org,"registration_scope" to SCOPES)
        try {
            val registration=if(canReuseRegistration(previous,redirect))previous
            else {
                status("Preparing Supabase sign-in…")
                Http.json(metadata.getString("registration_endpoint"),"POST",body=registrationBody(redirect))
            }
            require(registration.optString("client_id").isNotBlank() && registration.optString("client_secret").isNotBlank()) {"Supabase did not register this phone for sign-in."}
            candidate.put("client_id",registration.getString("client_id")).put("client_secret",registration.getString("client_secret"))
            val state=randomUrl();val verifier=randomUrl()
            val challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
            val address=metadata.getString("authorization_endpoint")+"?"+Http.form(mapOf(
                "response_type" to "code","client_id" to candidate.getString("client_id"),"redirect_uri" to redirect,
                "state" to state,"code_challenge" to challenge,"code_challenge_method" to "S256","resource" to RESOURCE)+
                if(org.isBlank())emptyMap() else mapOf("organization_slug" to org))
            withContext(Dispatchers.Main) {
                status("Choose the Alpha organization in your browser and grant database access.")
                context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(address)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            while(true) {
                server.accept().use {socket->
                    socket.soTimeout=5000
                    val line=socket.getInputStream().bufferedReader().readLine().orEmpty().take(12000)
                    val uri=Uri.parse("http://127.0.0.1"+line.split(' ').getOrNull(1).orEmpty())
                    if(!line.startsWith("GET ") || uri.path!="/supabase/callback") {respond(socket,false);return@use}
                    val params=uri.queryParameterNames.associateWith {uri.getQueryParameter(it).orEmpty()}
                    val code=callbackCode(params,state)
                    respond(socket,true)
                    status("Approval received. Return to Alpha Assistant to finish connecting.")
                    if(!foreground()) Updates.notify(context,"supabase-signin","Supabase approved","Tap to finish connecting Alpha to Supabase.",destination="connect")
                    awaitSignInForeground(foreground)
                    status("Checking access to your Supabase tables…")
                    val tokens=exchange(candidate,mapOf("grant_type" to "authorization_code","code" to code,
                        "code_verifier" to verifier,"redirect_uri" to redirect))
                    tokens.keys().forEach {key->candidate.put(key,tokens.get(key))}
                    return@withContext candidate
                }
            }
            @Suppress("UNREACHABLE_CODE") JSONObject()
        }finally {server.close();listener=null}
    }
    suspend fun accessToken():String=refreshLock.withLock {
        val current=get()
        if(current.optString("auth_type")!="oauth")return@withLock current.optString("token")
        require(current.optString("token").isNotBlank()) {"Connect Supabase in Marketplace first."}
        if(current.optLong("expires_at")>System.currentTimeMillis()+60000)return@withLock current.getString("token")
        require(current.optString("refresh_token").isNotBlank()) {"Supabase sign-in expired. Reconnect in Marketplace."}
        val tokens=exchange(current,mapOf("grant_type" to "refresh_token","refresh_token" to current.getString("refresh_token")))
        tokens.keys().forEach {key->current.put(key,tokens.get(key))}
        withContext(Dispatchers.Main){save(current)}
        current.getString("token")
    }
    private suspend fun exchange(client:JSONObject,grant:Map<String,String>):JSONObject {
        require(client.getString("token_endpoint")=="$ISSUER/v1/oauth/token")
        val tokens=JSONObject(Http.text(client.getString("token_endpoint"),"POST",body=Http.form(grant+mapOf(
            "client_id" to client.getString("client_id"),"client_secret" to client.getString("client_secret"),"resource" to RESOURCE)),
            contentType="application/x-www-form-urlencoded"))
        require(tokens.optString("token_type").equals("Bearer",true) && tokens.optString("access_token").isNotBlank()) {"Supabase did not return database access."}
        return tokens.put("token",tokens.getString("access_token")).put("expires_at",System.currentTimeMillis()+tokens.optLong("expires_in",3600)*1000)
    }
    private fun respond(socket:java.net.Socket,success:Boolean) {
        val html=if(success) "<!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'><title>Alpha Assistant</title><body style='font:20px sans-serif;padding:30px'><h1>Supabase approval received</h1><p>Return to Alpha to check your project connection.</p><a href='intent://signin/complete#Intent;scheme=alphaassistant;package="+BuildConfig.APPLICATION_ID+";end'>Return to Alpha Assistant</a></body>" else "Unknown callback address."
        val bytes=html.toByteArray()
        socket.getOutputStream().write(("HTTP/1.1 "+(if(success)"200 OK" else "404 Not Found")+"\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray()+bytes)
        socket.getOutputStream().flush()
    }
    companion object {
        const val RESOURCE="https://mcp.supabase.com/mcp"
        const val ISSUER="https://api.supabase.com"
        const val SCOPES="organizations:read projects:read database:read database:write"
        fun registrationBody(redirect:String)=obj(
            "client_name" to "Alpha Assistant on Android","redirect_uris" to arr(listOf(redirect)),
            "grant_types" to arr(listOf("authorization_code","refresh_token")),"response_types" to arr(listOf("code")),
            "token_endpoint_auth_method" to "client_secret_post","scope" to SCOPES)
        fun canReuseRegistration(previous:JSONObject,redirect:String)=previous.optString("redirect_uri")==redirect &&
            previous.optString("registration_scope")==SCOPES && previous.optString("client_id").isNotBlank() && previous.optString("client_secret").isNotBlank()
        fun endpoint(ref:String)="$RESOURCE?project_ref=$ref&features=database,docs"
        fun verifyMetadata(m:JSONObject) {
            require(m.optString("issuer")==ISSUER && m.optString("authorization_endpoint")=="$ISSUER/v1/oauth/authorize" &&
                m.optString("token_endpoint")=="$ISSUER/v1/oauth/token" && m.optString("registration_endpoint")=="$ISSUER/platform/oauth/apps/register") {"Supabase returned an unexpected authorization server."}
            require(m.optJSONArray("code_challenge_methods_supported")?.strings()?.contains("S256")==true) {"Supabase must support secure phone sign-in."}
        }
        fun callbackCode(params:Map<String,String>,expected:String):String {
            require(params["state"]==expected) {"The Supabase callback could not be verified. Start sign-in again."}
            require(params["error"].isNullOrBlank()) {"Supabase access was not granted. Reconnect when ready."}
            return params["code"]?.takeIf {it.isNotBlank()}?:error("Supabase returned no authorization code.")
        }
    }
}
