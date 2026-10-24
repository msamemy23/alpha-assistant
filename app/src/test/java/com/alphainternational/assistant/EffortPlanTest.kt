package com.alphainternational.assistant

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class EffortPlanTest {
    private val models=ModelPlan.catalog(JSONArray("""[
        {"slug":"astra","display_name":"Astra","visibility":"list","supported_reasoning_levels":[{"effort":"low"},{"effort":"medium"},{"effort":"high"},{"effort":"xhigh"}]},
        {"slug":"sol","display_name":"Sol","visibility":"list","supported_reasoning_levels":[{"effort":"low"},{"effort":"medium"},{"effort":"high"}]},
        {"slug":"luna","display_name":"Luna","visibility":"list","supported_reasoning_levels":["minimal","low","medium"]}
    ]"""))
    private val bot=Bot("Alpha","Assistant")

    @Test fun shortRepliesCarryOnTheJobBeforeThem() {
        listOf("yes do it","Yes please","go ahead","ok","okay do that","the second one","do it again","sure","no thanks","try again","continue","that one","yep","Keep going","Sounds good!","do that","use the first one","fix it")
            .forEach {assertTrue(it,EffortPlan.isContinuation(it))}
        listOf("hi","what time do you close","Make me an estimate for Maria","email John that his car is ready","research the best shop management software and compare prices","","   ")
            .forEach {assertFalse(it,EffortPlan.isContinuation(it))}
        // A short answer to a question the bot just asked is a continuation too.
        assertTrue(EffortPlan.isContinuation("2016 Camry","Which car is it?"))
        assertFalse(EffortPlan.isContinuation("2016 Camry","Here is your estimate."))
        assertFalse(EffortPlan.isContinuation("Please write a long and careful letter to the county about the permit and the three open violations on the lot","Which one?"))
    }

    @Test fun aContinuationNeverDropsBelowTheJobItContinues() {
        val q=ModelPlan.Level.QUICK;val e=ModelPlan.Level.EVERYDAY;val h=ModelPlan.Level.HARD
        assertEquals(h,EffortPlan.initial(q,true,h))
        assertEquals(e,EffortPlan.initial(q,true,e))
        assertEquals(h,EffortPlan.initial(h,true,q))
        // Not a continuation, or no earlier job: the request alone decides.
        assertEquals(q,EffortPlan.initial(q,false,h))
        assertEquals(q,EffortPlan.initial(q,true,null))
        // "yes do it" really did look like small talk to the old rule.
        assertEquals(q,ModelPlan.level("yes do it"))
        assertEquals(h,EffortPlan.initial(ModelPlan.level("yes do it"),EffortPlan.isContinuation("yes do it"),EffortPlan.levelOfJob("research the best shop software and compare prices","high",false)))
    }

    @Test fun theLevelOfAFinishedJobComesFromItsRequestAndItsIntelligence() {
        assertEquals(ModelPlan.Level.HARD,EffortPlan.levelOfJob("research the best shop software","",false))
        assertEquals(ModelPlan.Level.HARD,EffortPlan.levelOfJob("hi","xhigh",false))
        assertEquals(ModelPlan.Level.EVERYDAY,EffortPlan.levelOfJob("email John that his car is ready","medium",false))
        assertEquals(ModelPlan.Level.QUICK,EffortPlan.levelOfJob("hi","",false))
        assertEquals(ModelPlan.Level.HARD,EffortPlan.levelOfJob("hi","",true))
        assertNull(EffortPlan.levelOfEffort(""))
    }

    @Test fun checkingRoundsThinkOneNotchHarderThanTheQuickestSetting() {
        assertEquals(ModelPlan.Level.EVERYDAY,EffortPlan.verifyLevel(ModelPlan.Level.QUICK))
        assertEquals(ModelPlan.Level.HARD,EffortPlan.verifyLevel(ModelPlan.Level.HARD))
        assertEquals("medium",EffortPlan.verifyEffort("low",listOf("low","medium","high")))
        assertEquals("low",EffortPlan.verifyEffort("minimal",listOf("low","medium")))
        assertEquals("high",EffortPlan.verifyEffort("high",listOf("low","medium","high")))
        assertEquals("",EffortPlan.verifyEffort("",listOf("low","medium")))
        assertEquals("medium",EffortPlan.verifyEffort("low",emptyList()))
    }

    @Test fun oneNotchUpAmongWhatTheModelSupports() {
        assertEquals("medium",EffortPlan.nextEffort("low",listOf("low","medium","high")))
        assertEquals("high",EffortPlan.nextEffort("medium",listOf("low","medium","high")))
        assertEquals("high",EffortPlan.nextEffort("high",listOf("low","medium","high")))
        // Auto never moves a job past "high" on its own (the live phone: a minute or two a round at the deepest level, and failing pages don't need it).
        assertEquals("high",EffortPlan.nextEffort("high",listOf("low","medium","high","xhigh")))
        assertEquals("a model without \"high\" is not pushed to \"xhigh\"","medium",EffortPlan.nextEffort("medium",listOf("low","medium","xhigh")))
        assertEquals("xhigh",EffortPlan.nextEffort("high",listOf("low","medium","high","xhigh"),ceiling="xhigh"))
        assertEquals("medium",EffortPlan.nextEffort("medium",listOf("minimal","low","medium")))
        assertEquals("medium",EffortPlan.nextEffort("low",emptyList()))
        assertEquals("high",EffortPlan.nextEffort("medium",emptyList()))
        assertEquals("xhigh",EffortPlan.nextEffort("xhigh",emptyList()))
        assertEquals("medium",EffortPlan.nextEffort("",listOf("low","medium")))
    }

    @Test fun repeatedToolErrorsMakeAJobStruggleButOneMissDoesNot() {
        assertFalse(EffortPlan.struggling(emptyList()))
        assertFalse(EffortPlan.struggling(listOf(false)))
        assertFalse(EffortPlan.struggling(listOf(false,false)))
        assertTrue(EffortPlan.struggling(listOf(true,true,false,false,false)))
        assertTrue(EffortPlan.struggling(listOf(false,true,false,true,false)))
        assertFalse(EffortPlan.struggling(listOf(true,true,true,false,true)))
        assertFalse(EffortPlan.struggling(listOf(false,true,true,true,false)))
        assertTrue(EffortPlan.struggling(listOf(false,false,false)))
    }

    @Test fun onlyAutoAdaptsAPinnedModelOrLevelStaysExactlyAsChosen() {
        assertEquals(EffortPlan.Adaptable(true,true),EffortPlan.adaptable(AiSettings(),bot))
        assertEquals(EffortPlan.Adaptable(false,true),EffortPlan.adaptable(AiSettings(model="sol"),bot))
        assertEquals(EffortPlan.Adaptable(true,false),EffortPlan.adaptable(AiSettings(effort="high"),bot))
        assertEquals(EffortPlan.Adaptable(false,false),EffortPlan.adaptable(AiSettings(model="luna",effort="max"),bot))
        assertEquals(EffortPlan.Adaptable(false,true),EffortPlan.adaptable(AiSettings(),bot.copy(model="luna")))
        assertEquals(EffortPlan.Adaptable(true,false),EffortPlan.adaptable(AiSettings(),bot.copy(effort="low")))
        assertFalse(EffortPlan.adaptable(AiSettings(model="sol",effort="high"),bot).any)
        assertTrue(EffortPlan.adaptable(AiSettings(model="sol"),bot).any)
    }

    @Test fun theTierAJobRunsAtIsReadFromItsModel() {
        assertEquals(ModelPlan.Level.HARD,EffortPlan.tierOf("astra",AiSettings(),models,""))
        assertEquals(ModelPlan.Level.EVERYDAY,EffortPlan.tierOf("sol",AiSettings(),models,""))
        assertEquals(ModelPlan.Level.QUICK,EffortPlan.tierOf("luna",AiSettings(),models,""))
        assertEquals(ModelPlan.Level.QUICK,EffortPlan.tierOf("something-else",AiSettings(),models,""))
        assertEquals(ModelPlan.Level.EVERYDAY,EffortPlan.bump(ModelPlan.Level.QUICK))
        assertEquals(ModelPlan.Level.HARD,EffortPlan.bump(ModelPlan.Level.EVERYDAY))
        assertEquals(ModelPlan.Level.HARD,EffortPlan.bump(ModelPlan.Level.HARD))
    }
}
