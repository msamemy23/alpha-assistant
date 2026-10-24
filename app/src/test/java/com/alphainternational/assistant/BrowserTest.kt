package com.alphainternational.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BrowserTest {
    @Test fun browserPresentsAsChrome() {
        val webView="Mozilla/5.0 (Linux; Android 14; SM-S921U Build/UP1A.231005.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/129.0.6668.100 Mobile Safari/537.36"
        assertEquals("Mozilla/5.0 (Linux; Android 14; SM-S921U Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.6668.100 Mobile Safari/537.36",
            BrowserScripts.chromeAgent(webView))
    }
    private fun target(kind:String,href:String,label:String,page:String="https://shop.example.com/list",action:String="")=
        JSONObject().put("kind",kind).put("href",href).put("label",label).put("page",page).put("action",action)
    @Test fun onlyPlainLinksSkipTheApprovalCard() {
        assertTrue(BrowserScripts.plainLink(target("A","https://shop.example.com/item/42","Brake pads")))
        assertTrue(BrowserScripts.plainLink(target("A","https://www.facebook.com/alpha","Facebook")))
        // Opened over https:// or refused, never loaded unencrypted.
        assertTrue(BrowserScripts.plainLink(target("A","http://smallshop.example.com/hours","Hours")))
        assertFalse(BrowserScripts.plainLink(target("BUTTON","","Send")))
        assertFalse(BrowserScripts.plainLink(target("A","https://shop.example.com/list#","Menu")))
        assertFalse(BrowserScripts.plainLink(target("A","https://shop.example.com/cart","Buy now")))
        assertFalse(BrowserScripts.plainLink(target("A","https://mail.example.com/x","Delete message")))
        assertFalse(BrowserScripts.plainLink(target("A","https://shop.example.com/go","Go",action="https://shop.example.com/form")))
        assertFalse(BrowserScripts.plainLink(target("A","javascript:void(0)","Open")))
    }
    @Test fun pageScriptsCarryTheTextSafely() {
        val script=BrowserScripts.type("He said \"hi\"\n</script>")
        assertTrue(script.startsWith("var v=\"He said \\\"hi\\\"\\n<\\/script>\";"))
        assertTrue(BrowserScripts.scroll(-1).endsWith("})(-1)"))
    }
    @Test fun textingFollowsTheOwnersChoice() {
        assertEquals("phone",AiSettings.parse(null).texting)
        assertEquals("google_web",AiSettings.parse(JSONObject(AiSettings(texting="google_web").json().toString())).texting)
        assertEquals("phone",AiSettings.parse(JSONObject().put("texting","fax")).texting)
        assertTrue(Texting.instructions("phone").startsWith("To text someone, use sms_send"))
        assertTrue(Texting.instructions("google_web").contains("messages.google.com/web"))
        assertTrue(Texting.instructions("ask").contains("ask the owner once"))
        assertEquals("Google Messages web",Texting.label("google_web"))
    }
}
