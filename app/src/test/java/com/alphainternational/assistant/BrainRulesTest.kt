package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Changes that outlast a job, and clicks that look like paying, ask after anything from outside was read. */
class SelfChangeTest {
    @Test fun nothingAsksWhenNothingFromOutsideWasRead() {
        listOf("routine_create","bot_update","skill_save","tool_create").forEach {assertFalse(it,SelfChange.needsCard(it,"summarize my day",false))}
        assertFalse(SelfChange.needsCard("browser_click","buy it",false,checkout=true))
    }

    @Test fun afterReadingOutsideContentTheyAskUnlessTheOwnerAskedForThatVeryThing() {
        // The owner asked for something else, and a page or email was read: a new routine, rules, skill or tool would be planted.
        assertTrue(SelfChange.needsCard("routine_create","summarize this page for me",true))
        assertTrue(SelfChange.needsCard("bot_update","what does this email say",true))
        assertTrue(SelfChange.needsCard("skill_save","what's the best price on brake pads",true))
        assertTrue(SelfChange.needsCard("tool_create","research the competition",true))
        // The owner asked for exactly that.
        assertFalse(SelfChange.needsCard("routine_create","check my email every morning at 8 and tell me what matters",true))
        assertFalse(SelfChange.needsCard("routine_create","remind me daily to order oil",true))
        assertFalse(SelfChange.needsCard("bot_update","from now on always sign texts as Dave",true))
        assertFalse(SelfChange.needsCard("skill_save","remember how I do the weekly close, save it as a skill",true))
        assertFalse(SelfChange.needsCard("tool_create","make me a tire size calculator tool",true))
        // A routine request is not permission to change the rules.
        assertTrue(SelfChange.needsCard("bot_update","check my email every morning",true))
    }

    @Test fun onlyClicksThatLookLikePayingAsk() {
        assertTrue(SelfChange.needsCard("browser_click","find the part",true,checkout=true))
        assertFalse(SelfChange.needsCard("browser_click","find the part",true,checkout=false))
        assertFalse(SelfChange.needsCard("sms_send","hi",true))
        assertFalse(SelfChange.needsCard("web_fetch","hi",true))
    }

    @Test fun checkoutLikeElementsAreRecognised() {
        fun el(label:String,action:String="",type:String="submit",href:String="")=obj("label" to label,"action" to action,"type" to type,"href" to href,"kind" to "BUTTON")
        listOf("Place order","Place your order","Buy now","Pay now","Complete purchase","Complete order","Submit order","Confirm payment","Checkout","Check out","Subscribe","Donate","Book now","Proceed to pay")
            .forEach {assertTrue(it,SelfChange.checkoutLike(el(it)))}
        listOf("Add to cart","Next","Continue shopping","Sign in","Search","View details","Read more","Cancel","Save for later","Compare").forEach {assertFalse(it,SelfChange.checkoutLike(el(it)))}
        // A plain-looking button on a form that posts to a payment address.
        assertTrue(SelfChange.checkoutLike(el("Continue","https://shop.example/checkout/complete")))
        assertFalse(SelfChange.checkoutLike(el("Continue","https://shop.example/cart/update")))
        assertFalse(SelfChange.checkoutLike(null))
        assertFalse(SelfChange.checkoutLike(JSONObject()))
    }

    @Test fun theFourPersistentTools() {
        assertEquals(setOf("routine_create","bot_update","skill_save","tool_create"),SelfChange.persistent)
        // They are the ones that carry the owner's review flag in the tool list.
        SelfChange.persistent.forEach {n->assertTrue(n,allTools.first {it.name==n}.review)}
        assertTrue(allTools.first {it.name=="browser_click"}.review)
    }
}

class RedactionTest {
    @Test fun googleTokensAreRemoved() {
        val access="ya29.a0AfH6SMBq3xK9mP2zLw8vTn4YhJcR7uEoDfG1sXbNkQ"
        val out=Rules.redact("Authorization failed for $access at 10:00")
        assertFalse(out.contains("ya29")) ; assertTrue(out.contains("[token removed]")) ; assertTrue(out.endsWith("at 10:00"))
        val refresh="1//0gAbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_AbCd"
        assertFalse(Rules.redact("refresh $refresh now").contains(refresh))
        val key="AIza"+"SyA1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q"
        assertEquals("[key removed]",Rules.redact(key))
    }
    @Test fun ordinaryTextIsLeftAlone() {
        val text="Call 713-555-0123 or see https://example.com/page//2 and version 1//2, ya29 is not a token by itself."
        assertEquals(text,Rules.redact(text))
        // The older patterns still work.
        assertEquals("Bearer [secret removed]",Rules.redact("Bearer abc.def-ghi"))
        assertTrue(Rules.redact("key sk-abcdefghijklmnop1234").contains("[key removed]"))
    }
}

class AiRequestTest {
    private val tools=listOf(Tool("file_write","d",schema("name" to "string")))
    private val input=JSONArray().put(obj("role" to "user","content" to "hi"))

    @Test fun aPlainRequestMatchesWhatWasSentBefore() {
        val r=AiRequest.build("luna",input,"be nice",tools,AiRequest.Options(search=true,parallel=true,cacheKey="alpha-alpha",effort="low"))
        assertEquals("luna",r.getString("model"));assertEquals("be nice",r.getString("instructions"));assertFalse(r.getBoolean("store"));assertTrue(r.getBoolean("stream"))
        val hosted=r.getJSONArray("tools")
        assertEquals("namespace",hosted.getJSONObject(0).getString("type"));assertEquals("alpha",hosted.getJSONObject(0).getString("name"))
        assertEquals("file_write",hosted.getJSONObject(0).getJSONArray("tools").getJSONObject(0).getString("name"))
        assertEquals("web_search",hosted.getJSONObject(1).getString("type"))
        assertTrue(r.getBoolean("parallel_tool_calls"))
        assertEquals("alpha-alpha",r.getString("prompt_cache_key"))
        assertEquals("low",r.getJSONObject("reasoning").getString("effort"))
        assertFalse(r.getJSONObject("reasoning").has("summary"))
        assertFalse(r.has("include"))
    }
    @Test fun noToolsMeansNoToolsAtAll() {
        val r=AiRequest.build("luna",input,"pick a bot",emptyList(),AiRequest.Options(search=true))
        assertFalse(r.has("tools"));assertFalse(r.has("parallel_tool_calls"));assertFalse(r.has("reasoning"));assertFalse(r.has("prompt_cache_key"))
        assertFalse(AiRequest.build("luna",input,"x",tools,AiRequest.Options(search=false)).getJSONArray("tools").length()>1)
        assertFalse(AiRequest.build("luna",input,"x",tools,AiRequest.Options(parallel=false)).getBoolean("parallel_tool_calls"))
        assertFalse(AiRequest.build("luna",input,"x",tools,AiRequest.Options(cacheKey="  ")).has("prompt_cache_key"))
    }
    @Test fun theThinkingSummaryIsOffUnlessAskedForAndNeverReplacesTheLevel() {
        val off=AiRequest.build("luna",input,"x",tools,AiRequest.Options(effort="medium"))
        assertFalse(off.has("include"));assertFalse(off.getJSONObject("reasoning").has("summary"))
        val on=AiRequest.build("luna",input,"x",tools,AiRequest.Options(effort="medium",summary=true))
        assertEquals("medium",on.getJSONObject("reasoning").getString("effort"))
        assertEquals("auto",on.getJSONObject("reasoning").getString("summary"))
        assertEquals("reasoning.encrypted_content",on.getJSONArray("include").getString(0))
        // With no level of its own the summary still goes alone.
        val only=AiRequest.build("luna",input,"x",tools,AiRequest.Options(summary=true))
        assertFalse(only.getJSONObject("reasoning").has("effort"));assertEquals("auto",only.getJSONObject("reasoning").getString("summary"))
    }
    @Test fun aRefusalIsAttributedToTheRightOptionalPiece() {
        assertEquals("summary",AiRequest.refused("Unsupported parameter: 'reasoning.summary' is not supported with this model."))
        assertEquals("summary",AiRequest.refused("Unknown parameter: 'include'."))
        assertEquals("summary",AiRequest.refused("reasoning.encrypted_content is not available"))
        assertEquals("effort",AiRequest.refused("Unsupported value: 'xhigh' for reasoning effort"))
        assertEquals("parallel",AiRequest.refused("parallel_tool_calls is not allowed"))
        assertEquals("cache",AiRequest.refused("Unknown parameter prompt_cache_key"))
        assertNull(AiRequest.refused("The model is overloaded"))
    }
}

class AgentRulesBrainTest {
    private fun task(id:String,status:String,created:Long,routine:String="") = TaskRecord(id,id,id,status,"",created,routineId=routine)

    @Test fun onlyRecentPausedChatJobsResumeByThemselves() {
        val now=10_000_000L
        val tasks=listOf(task("fresh","Paused",now-60_000),task("older","Paused",now-30*60_000),task("old","Paused",now-2*3_600_000),task("routine","Paused",now-1000,routine="r1"),
            task("running","Running",now-1000),task("other","Paused",now-5000),task("done","Completed",now-1000))
        val ids=listOf("fresh","older","old","routine","running","done")
        assertEquals(listOf("fresh","older"),AgentRules.resumable(tasks,ids,now).map {it.id})
        assertEquals(listOf("fresh"),AgentRules.resumable(tasks,listOf("fresh"),now).map {it.id})
        assertTrue(AgentRules.resumable(tasks,emptyList(),now).isEmpty())
    }

    @Test fun aJobCutShortIsRecordedAsPartialWithItsOwnSummary() {
        val r=obj("call_id" to "a","tool" to "web_fetch","success" to true,"result" to JSONObject())
        val plain=AgentRules.autoFinish(listOf(r),"Here is the answer.")
        assertEquals("completed",plain.getString("outcome"));assertFalse(plain.has("cut_short"))
        val cut=AgentRules.autoFinish(listOf(r),"Here is the answer.",cutShort=true)
        assertEquals("partial",cut.getString("outcome"));assertTrue(cut.getBoolean("cut_short"))
        assertEquals("Ran out of time before finishing. Tap Keep going to continue.",cut.getString("summary"))
        val noted=AgentRules.autoFinish(listOf(r),"I sent it.",notes=listOf("I can't confirm everything above was actually done."))
        assertEquals("partial",noted.getString("outcome"));assertEquals("I can't confirm everything above was actually done.",noted.getString("summary"))
        // The record survives saving, and the chat can tell a cut-short job from any other partial one.
        assertTrue(Verify.wasCutShort(TaskRecord.parse(TaskRecord("t","T","T","Partial","",0,completion=cut.toString()).json()).completion))
    }

    @Test fun thePlanToolsAreBookkeepingNotWork() {
        listOf("plan_set","plan_update","tools_enable").forEach {assertTrue(it,it in TaskProtocol.controlTools)}
        // So they never produce receipts, never reopen finished work and never make a quick chat look like a job.
        val history=JSONArray()
        history.put(obj("type" to "function_call","call_id" to "1","name" to "plan_set","arguments" to "{}"))
        history.put(obj("type" to "function_call_output","call_id" to "1","output" to "{\"result\":\"ok\"}"))
        assertTrue(TaskProtocol.receipts(history).isEmpty())
        assertEquals("",StepWords.line("plan_set",JSONObject()))
        assertEquals("",StepWords.line("plan_update",JSONObject()))
        assertEquals("",StepWords.line("tools_enable",JSONObject()))
        assertEquals(0,Verify.workCalls(history))
    }
}

/** A chat that merely mentions a photo or a menu must not get the strongest model and a half-hour budget because some page exists. */
class StudioWantsTest {
    @Test fun unrelatedChatsWithASiteInTheFilesAreNotBuildingJobs() {
        listOf("send me the photo of the car","what's on the menu for lunch today","text Dave the picture of his invoice","thanks!","email the form to the insurance company",
            "what is the price of that tool","call the dealer about the app they mentioned","remind me to pick up the photo prints","how do I get to the shop",
            "show me the picture from yesterday","I sent the logo to the printer","the game was great last night","what color is Dave's truck").forEach {assertFalse(it,Studio.wants(it,true))}
    }
    @Test fun realChangeRequestsStillCountWhenThereIsASite() {
        listOf("make the header bigger","change the button color to red","the page looks plain, make it better","add a photo of the shop to the page","the menu is too small on mobile",
            "update the hours on the site","fix the layout on my phone","move the logo to the left","redesign the footer","the form doesn't work").forEach {assertTrue(it,Studio.wants(it,true))}
    }
    @Test fun buildRequestsCountWithOrWithoutASite() {
        listOf("build me a website for my bakery","I want a web app for my customers","make a game for the lobby","create a landing page for brake specials").forEach {
            assertTrue(it,Studio.wants(it,false));assertTrue(it,Studio.wants(it,true))
        }
    }
}
