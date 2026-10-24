package com.alphainternational.assistant

import org.json.JSONObject

/**
 * Checks that a website sign-in really took, for sites whose sign-in a cookie check can't see. The owner taps Done when they believe they are in;
 * for these sites the phone asks the page itself (booleans only: never a token, a password or a cookie value) and tells the bot the truth.
 */
/** Sign-in links the owner copied from an email and pastes into Alpha's browser: only the website being signed in to is allowed, and only securely. */
object SignInLinks {
    private fun base(host:String):String {
        val labels=host.lowercase().removePrefix("www.").split('.').filter {it.isNotBlank()}
        // co.uk, com.au and the like keep one more label.
        val keep=if(labels.size>=3 && labels.last().length==2 && labels[labels.size-2].length<=3) 3 else 2
        return labels.takeLast(keep).joinToString(".")
    }
    /** Null when [link] may be opened for the sign-in to [site]; otherwise what to tell the owner. */
    fun problem(link:String,site:String):String? {
        val uri=runCatching {java.net.URI(link.trim())}.getOrNull()
        if(link.isBlank() || link.contains(' ') || uri==null || uri.host==null) return "Nothing to paste yet. Copy the sign-in link from your email first."
        if(!uri.scheme.equals("https",true)) return "That link isn't a secure website link, so Alpha won't open it."
        val wanted=runCatching {java.net.URI(site).host}.getOrNull().orEmpty()
        if(wanted.isBlank() || base(uri.host)!=base(wanted)) return "That link is for a different website than the one you are signing in to, so Alpha won't open it."
        return null
    }
}

object SignInChecks {
    enum class Verdict {SIGNED_IN,NOT_SIGNED_IN,UNKNOWN}

    /** A short read-only script for the page that is open, or null for sites with no check. */
    fun script(site:String):String? = when {
        site.contains("portal.telnyx.com") ->
            // Telnyx keeps its session in the page's own storage (not in a cookie) and routes with the part after "#".
            "(function(){try{var s=localStorage.getItem('angular-cache.caches.userInfo.data.session');var o=s?JSON.parse(s).value:null;" +
                "return JSON.stringify({host:location.host,hash:location.hash.slice(0,40),session:!!(o&&(o.api_v2_token||o.token))})}catch(e){return JSON.stringify({error:String(e).slice(0,80)})}})()"
        else -> null
    }

    fun verdict(site:String,answer:String):Verdict {
        if(script(site)==null) return Verdict.UNKNOWN
        val json=runCatching {JSONObject(answer)}.getOrNull()?:return Verdict.UNKNOWN
        if(json.has("error")) return Verdict.UNKNOWN
        // The check only means something on the site itself: if the browser has moved on to another page, it can't say.
        if(!json.optString("host").contains("telnyx.com")) return Verdict.UNKNOWN
        val onLogin=json.optString("hash").startsWith("#/login")
        return if(json.optBoolean("session") && !onLogin) Verdict.SIGNED_IN else Verdict.NOT_SIGNED_IN
    }

    fun failureNote(site:String):String = when {
        site.contains("telnyx") ->
            "The phone checked: the owner is NOT signed in to the Telnyx portal (it still shows its sign-in page). Do not just ask them to try the same way again. Tell them in plain words: " +
                "stay on the Telnyx page in Alpha's browser and sign in with email and password, or with the Google button (a small sign-in window opens on top of the page; finish there and it closes by itself), " +
                "enter any code Telnyx asks for, and only tap Done after the Telnyx dashboard shows. If Telnyx emails a sign-in link, copy it and tap the line under the title to open it in Alpha. " +
                "Or skip the portal: for who called, connect Telnyx in Marketplace with an API key (telnyx_calls then works with no sign-in). Offer that second way first if they only want call history."
        else -> "The owner says they finished signing in to "+site+" in Alpha's browser. Continue the request."
    }
}
