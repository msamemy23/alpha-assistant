package com.alphainternational.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.UUID

class ChatGptAuth(
    private val context:Context,
    private val get:()->JSONObject,
    private val save:(JSONObject)->Unit,
    private val hostId:String,
    private val isForeground:()->Boolean
):TokenSource {
    private val refreshLock=Mutex()
    private var listener:ServerSocket?=null
    fun cancel() { runCatching { listener?.close() }; listener=null }

    suspend fun signIn(onStatus:(String)->Unit):JSONObject = withContext(Dispatchers.IO) {
        cancel()
        val previous=get()
        val returningId=previous.optString("client_id").takeIf { it.isNotBlank() }
        val state=randomUrl(); val nonce=randomUrl(); val verifier=randomUrl()
        val challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val server=ServerSocket(0,4,InetAddress.getByName("127.0.0.1")).apply { soTimeout=600_000 }
        listener=server
        val redirect="http://127.0.0.1:" + server.localPort + "/auth/callback"
        val query=linkedMapOf(
            "client_id" to (returningId?:"dynamic_agent_client"),
            "ext_agent_host_id" to hostId,
            "response_type" to "code","redirect_uri" to redirect,
            "scope" to "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct",
            "resource" to "https://api.openai.com/v1","state" to state,"nonce" to nonce,
            "code_challenge_method" to "S256","code_challenge" to challenge
        )
        if(returningId==null) query["agent_name_hint"]="Alpha Assistant"
        if(returningId!=null && previous.optString("id_token").isNotBlank()) query["id_token_hint"]=previous.getString("id_token")
        val authUrl="https://auth.openai.com/api/accounts/authorize?" + Http.form(query)
        withContext(Dispatchers.Main) {
            onStatus("Finish sign-in in your browser. This app is waiting on your phone.")
            context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(authUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        try {
            while(true) {
                val socket=server.accept()
                socket.soTimeout=5_000
                socket.use {
                    val reader=it.getInputStream().bufferedReader()
                    val first=reader.readLine()?.take(12_000).orEmpty()
                    val target=first.split(' ').getOrNull(1).orEmpty()
                    val uri=Uri.parse("http://127.0.0.1" + target)
                    if(!first.startsWith("GET ") || uri.path!="/auth/callback") {
                        respond(it,"404 Not Found","This address is not a sign-in callback.")
                        return@use
                    }
                    val params=uri.queryParameterNames.associateWith { k->uri.getQueryParameter(k).orEmpty() }
                    val issued=try { Rules.validateCallback(params,state,returningId) } catch(e:Exception) {
                        respond(it,"400 Bad Request","The callback could not be verified. Return to Alpha Assistant and start again.")
                        throw e
                    }
                    respond(it,"200 OK","Approval received. Return to Alpha Assistant to finish connecting.",returnToApp=true)
                    withContext(Dispatchers.Main) { onStatus("Approval received. Return to Alpha Assistant to finish connecting.") }
                    awaitSignInForeground(isForeground)
                    withContext(Dispatchers.Main) { onStatus("Exchanging your sign-in with OpenAI…") }
                    val result=JSONObject(Http.text(
                        "https://auth.openai.com/api/accounts/oauth/token","POST",body=Http.form(mapOf(
                            "grant_type" to "authorization_code","client_id" to issued,"code" to params.getValue("code"),
                            "code_verifier" to verifier,"redirect_uri" to redirect,"resource" to "https://api.openai.com/v1"
                        )),contentType="application/x-www-form-urlencoded"))
                    withContext(Dispatchers.Main) { onStatus("Verifying your ChatGPT account…") }
                    val claims=verifyIdToken(result.getString("id_token"),issued,nonce,previous.optString("subject").takeIf { returningId!=null && it.isNotBlank() })
                    require(result.optString("scope").split(' ').contains("chatgpt.tokens.use.direct")) { "Sign-in succeeded, but permission to use your ChatGPT plan was not granted." }
                    require(result.optString("token_type").equals("Bearer",true) && result.optString("access_token").isNotBlank()) { "Sign-in did not return a usable access token." }
                    result.put("client_id",issued).put("subject",claims.getString("sub")).put("email",claims.optString("email"))
                        .put("expires_at",System.currentTimeMillis()+result.optLong("expires_in",3600)*1000).put("host_id",hostId)
                    withContext(Dispatchers.Main) { save(result) }
                    return@withContext result
                }
            }
            @Suppress("UNREACHABLE_CODE") JSONObject()
        } finally { server.close(); listener=null }
    }
    private fun respond(socket:java.net.Socket,status:String,message:String,returnToApp:Boolean=false) {
        val button=if(returnToApp) "<p><a href='intent://signin/complete#Intent;scheme=alphaassistant;package="+BuildConfig.APPLICATION_ID+";end' style='display:inline-block;background:#a8efca;color:#10251d;padding:18px 24px;border-radius:12px;text-decoration:none;font-weight:bold'>Return to Alpha Assistant</a></p><p style='font-size:14px'>You can also reopen Alpha Assistant from your home screen.</p>" else ""
        val html="<!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'><title>Alpha Assistant</title><body style='background:#10141b;color:#f5f7fa;font:18px sans-serif;padding:32px'><h1>Alpha Assistant</h1><p>"+message+"</p>"+button+"</body>"
        val bytes=html.toByteArray()
        socket.getOutputStream().write(("HTTP/1.1 "+status+"\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "+bytes.size+"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray()+bytes)
        socket.getOutputStream().flush()
    }
    override suspend fun accessToken():String = refreshLock.withLock {
        val current=get()
        require(current.optString("access_token").isNotBlank()) { "Connect ChatGPT in Marketplace first." }
        require(current.optString("scope").split(' ').contains("chatgpt.tokens.use.direct")) { "Your account has not granted ChatGPT plan usage." }
        if(current.optLong("expires_at")>System.currentTimeMillis()+60_000) return@withLock current.getString("access_token")
        withContext(Dispatchers.IO) {
            require(current.optString("refresh_token").isNotBlank()) { "Your sign-in expired. Connect ChatGPT again." }
            val refreshed=JSONObject(Http.text("https://auth.openai.com/api/accounts/oauth/token","POST",body=Http.form(mapOf(
                "grant_type" to "refresh_token","client_id" to current.getString("client_id"),"refresh_token" to current.getString("refresh_token"),
                "resource" to "https://api.openai.com/v1"
            )),contentType="application/x-www-form-urlencoded"))
            if(refreshed.optString("id_token").isNotBlank()) verifyIdToken(refreshed.getString("id_token"),current.getString("client_id"),null,current.getString("subject"))
            require(refreshed.optString("access_token").isNotBlank()) { "Token renewal did not return an access token." }
            val updated=JSONObject(current.toString())
            refreshed.keys().forEach { k->updated.put(k,refreshed.get(k)) }
            require(updated.optString("scope").split(' ').contains("chatgpt.tokens.use.direct")) { "ChatGPT plan permission was removed. Reconnect your account." }
            updated.put("expires_at",System.currentTimeMillis()+refreshed.optLong("expires_in",3600)*1000)
            withContext(Dispatchers.Main) { save(updated) }
            updated.getString("access_token")
        }
    }
    suspend fun models():List<Pair<String,String>> = catalog().objects().filter { it.optString("visibility")=="list" }.map { it.getString("slug") to it.optString("display_name",it.getString("slug")) }
    /** Every model the plan reports, including ones not marked "list" (shown under More models). */
    suspend fun catalog():JSONArray =
        Http.json("https://api.openai.com/v1/models",headers=mapOf("Authorization" to ("Bearer "+accessToken()))).optJSONArray("models")?:JSONArray()
    private suspend fun verifyIdToken(token:String,clientId:String,nonce:String?,subject:String?):JSONObject {
        val parts=token.split('.'); require(parts.size==3) { "Invalid account identity token." }
        val decoder=Base64.getUrlDecoder()
        val header=JSONObject(String(decoder.decode(parts[0])))
        require(header.optString("alg")=="RS256") { "Unsupported account signature algorithm." }
        val discovery=Http.json("https://auth.openai.com/.well-known/openid-configuration")
        val jwksUrl=discovery.getString("jwks_uri")
        require(Rules.origin(jwksUrl)=="https://auth.openai.com") { "Unexpected identity signing-key location." }
        val jwk=Http.json(jwksUrl).getJSONArray("keys").objects().firstOrNull { it.optString("kid")==header.optString("kid") && it.optString("kty")=="RSA" }
            ?:error("The account signing key could not be found.")
        val key=KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(BigInteger(1,decoder.decode(jwk.getString("n"))),BigInteger(1,decoder.decode(jwk.getString("e")))))
        val signature=Signature.getInstance("SHA256withRSA").apply { initVerify(key); update((parts[0]+"."+parts[1]).toByteArray()) }
        require(signature.verify(decoder.decode(parts[2]))) { "The account identity signature was invalid." }
        val claims=JSONObject(String(decoder.decode(parts[1])))
        val audience=claims.opt("aud")
        require(audience==clientId || (audience is JSONArray && audience.strings().contains(clientId))) { "Account identity was issued for a different app." }
        require(claims.optString("iss")==discovery.getString("issuer") && discovery.getString("issuer")=="https://auth.openai.com") { "Unexpected account issuer." }
        require(claims.optLong("exp")*1000>System.currentTimeMillis()-30_000) { "Account identity expired." }
        require(claims.optLong("nbf",0)*1000<System.currentTimeMillis()+30_000) { "Account identity is not yet valid." }
        if(nonce!=null) require(claims.optString("nonce")==nonce) { "Account sign-in nonce did not match." }
        if(subject!=null) require(claims.optString("sub")==subject) { "A different account was returned. Disconnect before switching accounts." }
        require(claims.optString("sub").isNotBlank()) { "Account identity is missing." }
        return claims
    }
}
