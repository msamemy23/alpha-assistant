package com.alphainternational.assistant

import android.webkit.WebViewClient
import java.net.URI

/**
 * HTTPS-First, like Chrome's mode of the same name: an http:// address is opened as the same address over https://.
 * Nothing is ever loaded unencrypted (the manifest blocks cleartext for the whole app, browser included), so when the
 * secure version fails, the bot and the owner are told why in plain words instead of the tap silently doing nothing.
 */
object HttpsFirst {
    const val NO_ANSWER="It tried the secure https:// version first, but that didn't answer."
    const val SECURITY="It tried the secure https:// version first, but that has a security problem."
    const val TOO_SLOW="It tried the secure https:// version first, but that took too long to answer."
    const val BOUNCED="It tried the secure https:// version first, but the site sends visitors back to http://."
    const val NO_SECURE_ADDRESS="It can't be tried securely at that address."
    const val FORM="The form there would send what was typed unencrypted."

    fun isHttp(url:String)=runCatching {URI(url.trim()).scheme.equals("http",true)}.getOrDefault(false)

    /** The secure address: http:// becomes https:// (port 80 becomes the standard port). Anything else must already be https. */
    fun upgrade(url:String):String {
        val uri=URI(url.trim())
        if(!uri.scheme.equals("http",true)) return Rules.httpsUrl(url)
        require(uri.port==-1 || uri.port==80) {"An unencrypted site on port "+uri.port+" can't be opened securely."}
        return Rules.httpsUrl("https://"+uri.rawAuthority.orEmpty().removeSuffix(":80")+uri.rawPath.orEmpty()+
            uri.rawQuery?.let {"?"+it}.orEmpty()+uri.rawFragment?.let {"#"+it}.orEmpty())
    }

    /** Why the secure version failed, from the browser's error code. */
    fun reason(webViewError:Int)=when(webViewError) {
        WebViewClient.ERROR_FAILED_SSL_HANDSHAKE->SECURITY
        WebViewClient.ERROR_TIMEOUT->TOO_SLOW
        WebViewClient.ERROR_REDIRECT_LOOP->BOUNCED
        else->NO_ANSWER
    }

    private fun site(address:String)=runCatching {URI(address.trim()).host}.getOrNull()?.removePrefix("www.")?.ifBlank {null}?:"This website"

    /** What the bot is told: what happened, why it is safe, and what to do instead. */
    fun refusal(address:String,why:String)=site(address)+" only works without encryption (http://), so Alpha's browser didn't open it. "+why+
        " Unencrypted pages can be read or changed by others on the network, so Alpha never opens them, fills them in or signs in on them; nothing was sent unencrypted."+
        " Get the information another way (web search or another site), or tell the owner they can look at it in Chrome themselves."

    /** The shorter notice the owner sees on screen. */
    fun notice(address:String,why:String)=site(address)+" only works without encryption, so Alpha's browser didn't open it. "+why+
        " If you trust the site, you can look at it in Chrome."
}
