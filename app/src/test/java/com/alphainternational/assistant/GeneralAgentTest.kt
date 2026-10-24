package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GeneralAgentTest {
    @Test fun attachedPhotosAndPdfsTravelAsSmallReferences() {
        val content=Attachments.content("What part is this?",listOf("photo-1.jpg","doc-2.pdf","notes.csv")) {"a,b\n1,2"} as JSONArray
        val parts=content.objects()
        assertTrue(parts[0].getString("text").startsWith("What part is this?"))
        assertEquals("alpha-file:photo-1.jpg",parts[1].getString("image_url"))
        assertEquals("alpha-file:doc-2.pdf",parts[2].getString("file_data"))
        assertTrue(parts[3].getString("text").contains("a,b"))
        assertEquals("Just text",Attachments.content("Just text",emptyList()) {""})
    }
    @Test fun onlyRecentAttachmentsAreExpandedAndSearchItemsAreDropped() {
        val history=JSONArray()
        for(i in 1..3) history.put(obj("role" to "user","content" to Attachments.content("look $i",listOf("p$i.jpg")) {""}))
        history.put(obj("type" to "web_search_call","id" to "ws_1","status" to "completed"))
        val loaded=mutableListOf<String>()
        val sent=Attachments.expand(history,{name,_->loaded+=name;"data:image/jpeg;base64,AAAA"},keep=2)
        assertEquals(3,sent.length())
        assertEquals(listOf("p2.jpg","p3.jpg"),loaded)
        val first=sent.getJSONObject(0).getJSONArray("content").getJSONObject(1)
        assertEquals("input_text",first.getString("type"))
        assertEquals("data:image/jpeg;base64,AAAA",sent.getJSONObject(2).getJSONArray("content").getJSONObject(1).getString("image_url"))
        // The stored history keeps the reference, not the image data.
        assertTrue(history.toString().contains("alpha-file:p3.jpg") && !history.toString().contains("base64"))
    }
    @Test fun webSearchesCountAsEvidence() {
        val history=JSONArray().put(obj("type" to "web_search_call","id" to "ws_9","status" to "completed","action" to obj("query" to "brake pad prices")))
        val receipt=TaskProtocol.receipts(history).single()
        assertEquals("ws_9",receipt.getString("call_id"));assertEquals("web_search",receipt.getString("tool"));assertTrue(receipt.getBoolean("success"))
        val task=TaskRecord("t","t","t","Running","",0,history=history.toString(),plan=TaskProtocol.plan("""[{"id":"r","description":"Find prices","kind":"live"}]""").toString())
        assertEquals("completed",TaskProtocol.finish(task,"completed","Found prices","""[{"id":"r","status":"passed","evidence":["ws_9"]}]""").getString("outcome"))
    }
    @Test fun searchCitationsBecomeTappableSources() {
        val part=obj("type" to "output_text","text" to "Houston average is \$3.87.","annotations" to arr(listOf(
            obj("type" to "url_citation","url" to "https://gasprices.aaa.com/?state=TX&utm_source=openai","title" to "AAA Texas [gas]"),
            obj("type" to "url_citation","url" to "https://gasprices.aaa.com/?state=TX&utm_source=openai","title" to "dup"),
            obj("type" to "url_citation","url" to "http://insecure.example","title" to "x"))))
        assertEquals("\n\n**Sources**\n- [AAA Texas (gas)](https://gasprices.aaa.com/?state=TX)",TaskProtocol.sources(listOf(part),"Houston average is \$3.87."))
        assertEquals("",TaskProtocol.sources(listOf(obj("type" to "output_text","text" to "No search")),"No search"))
    }
    @Test fun webPagesBecomeReadableText() {
        val page=WebReader.text("""<html><head><title>Brake &amp; Rotor Deals</title><style>.x{color:red}</style><script>alert(1)</script></head>
            <body><h1>Front pads</h1><p>Only&nbsp;$89 &#8212; this week</p><!-- hidden --><ul><li>Ceramic</li><li>Same day</li></ul></body></html>""")
        assertEquals("Brake & Rotor Deals",page.title)
        assertEquals(listOf("Front pads","Only $89 — this week","Ceramic","Same day"),page.body.lines())
        assertFalse(page.body.contains("alert") || page.body.contains("color:red") || page.body.contains("hidden"))
    }
    @Test fun sandboxedCodeIsPassedAsDataNotSpliced() {
        val script=CodeRunner.script("return files['a.csv'].split('\\n').length; // \"); evil()",JSONObject().put("a.csv","x\ny"))
        assertTrue(script.contains("new Function('files','output','console',\"return files['a.csv']"))
        assertTrue(script.contains("var files={\"a.csv\":\"x\\ny\"}"))
        assertFalse(script.contains("console',return"))
    }
    @Test fun teachModeRecordsStepsNotSecrets() {
        assertEquals("www.example.com/orders/new",TeachSteps.place("https://www.example.com/orders/new?email=me%40x.com&token=abc"))
        val script=TeachSteps.recorder("Key_123-abc")
        assertTrue(script.contains("ALPHA_TEACH_Key_123-abc:"))
        assertTrue(script.contains("not recorded"))
        assertFalse(script.contains(".value+") || script.contains("e.value)"))
        assertTrue(runCatching {TeachSteps.recorder("bad'key")}.isFailure)
        val prompt=TeachSteps.prompt(listOf("Opened Parts Store (parts.example/search)","Filled \"Part number\" (what was typed is not recorded)","Tapped \"Add to cart\""))
        assertTrue(prompt.contains("1. Opened Parts Store") && prompt.contains("3. Tapped \"Add to cart\"") && prompt.contains("skill_save"))
    }
    @Test fun marketplaceAppsAreWellFormed() {
        val ids=AppCatalog.all.map {it.id};assertEquals(ids.size,ids.toSet().size)
        assertTrue(ids.containsAll(listOf("zapier","notion","stripe","paypal","trello","airtable")))
        AppCatalog.all.forEach {app->
            assertTrue(app.id,app.auth in setOf("oauth","token","url"))
            if(app.auth!="url") assertEquals(app.id,app.url,Rules.httpsUrl(app.url))
            if(app.auth!="oauth") assertTrue(app.id,app.tokenHelp.isNotBlank())
        }
    }
    @Test fun appSignInHelpersFollowTheMcpSpec() {
        assertEquals("https://mcp.notion.com/.well-known/oauth-protected-resource/mcp",
            McpOAuth.resourceMetadataHint("""Bearer error="invalid_token", resource_metadata="https://mcp.notion.com/.well-known/oauth-protected-resource/mcp""""))
        assertNull(McpOAuth.resourceMetadataHint("""Bearer resource_metadata="http://evil.example/x""""))
        assertEquals("abc",McpOAuth.callbackCode(mapOf("state" to "s","code" to "abc"),"s"))
        assertTrue(runCatching {McpOAuth.callbackCode(mapOf("state" to "other","code" to "abc"),"s")}.isFailure)
        assertTrue(runCatching {McpOAuth.callbackCode(mapOf("state" to "s","error" to "access_denied"),"s")}.isFailure)
        val merged=McpOAuth.merge(JSONObject(),JSONObject().put("access_token","t1").put("refresh_token","r1").put("expires_in",3600))
        assertEquals("t1",merged.getString("token"));assertEquals("r1",merged.getString("refresh_token"))
        assertTrue(merged.getLong("expires_at")>System.currentTimeMillis())
        assertEquals(0L,McpOAuth.merge(JSONObject(),JSONObject().put("access_token","t2")).getLong("expires_at"))
        val tools=listOf(obj("name" to "search","annotations" to obj("readOnlyHint" to true)),obj("name" to "delete_page","annotations" to obj("readOnlyHint" to true,"destructiveHint" to true)),
            obj("name" to "create_page"))
        assertEquals(listOf("search"),AppCatalog.readOnlyTools(tools))
        // Connected-app approvals describe the action in plain words.
        assertEquals("Mcp call",Reviews.summary("mcp_call","""{"server":"notion","tool":"create_page","arguments_json":"{}"}""").title)
    }
    @Test fun templatesCoverGrokBotSpecialistsAndAreValidBots() {
        assertTrue(BotTemplates.all.size>=10)
        BotTemplates.all.forEach {assertEquals(it.name,Bot.name(it.name));assertTrue(it.role.length in 10..200)}
        assertTrue(BotTemplates.all.map {it.name}.containsAll(listOf("Chief of Staff","Researcher","Recruiter","Bookkeeper","Sales")))
        // New general skills are readable and do not collide.
        val ids=Skills.builtIn.map {it.id};assertEquals(ids.size,ids.toSet().size)
        for(id in listOf("photo_help","data_analysis","trip_planning","shopping_compare","price_watch","write_edit","meeting_prep","talent_scout"))
            assertTrue(id,Skills.read(id).getString("instructions").isNotBlank())
    }
}
