package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelPlanTest {
    private val raw=JSONArray("""[
        {"slug":"astra","display_name":"Astra","visibility":"list","default_reasoning_level":"medium",
         "supported_reasoning_levels":[{"effort":"low"},{"effort":"medium"},{"effort":"high"},{"effort":"xhigh"}]},
        {"slug":"sol","display_name":"Sol","visibility":"list","supported_reasoning_levels":[{"effort":"low"},{"effort":"medium"},{"effort":"high"}]},
        {"slug":"luna","display_name":"Luna","visibility":"list","supported_reasoning_levels":["minimal","low","medium"]},
        {"slug":"old-model","display_name":"Old Model","visibility":"hide"},
        {"display_name":"No slug"}
    ]""")
    private val models=ModelPlan.catalog(raw)

    @Test fun catalogKeepsEveryModelWithListedOnesFirst() {
        assertEquals(listOf("astra","sol","luna","old-model"),models.map {it.slug})
        assertEquals(listOf(true,true,true,false),models.map {it.listed})
        assertEquals(listOf("low","medium","high","xhigh"),models[0].efforts)
        assertEquals("medium",models[0].defaultEffort)
        assertEquals(listOf("minimal","low","medium"),models[2].efforts)
        assertTrue(models[3].efforts.isEmpty())
        // Saved catalog survives a round trip.
        assertEquals(models,models.map {AiModel.parse(JSONObject(it.json().toString()))})
    }
    @Test fun settingsRoundTripAndDefaultToAuto() {
        val fresh=AiSettings.parse(null)
        assertEquals(ModelPlan.AUTO,fresh.model);assertEquals(ModelPlan.AUTO,fresh.effort);assertEquals("quick",fresh.routines)
        assertTrue(fresh.escalate && !fresh.label && fresh.webSearch && fresh.readOnlyApps)
        assertEquals(AiSettings.DEFAULT_APPROVALS,fresh.approvals)
        val custom=AiSettings("sol","high","luna","sol","astra","hard",escalate=false,label=false,webSearch=false,readOnlyApps=false)
        assertEquals(custom,AiSettings.parse(JSONObject(custom.json().toString())))
    }
    @Test fun requestsAreSortedByHowHardTheyAre() {
        assertEquals(ModelPlan.Level.QUICK,ModelPlan.level("hi"))
        assertEquals(ModelPlan.Level.QUICK,ModelPlan.level("Good morning!"))
        assertEquals(ModelPlan.Level.QUICK,ModelPlan.level("what's the weather tomorrow?"))
        assertEquals(ModelPlan.Level.EVERYDAY,ModelPlan.level("email John that his car is ready"))
        assertEquals(ModelPlan.Level.EVERYDAY,ModelPlan.level("What should I say to a customer who is upset that their repair took two extra days?"))
        // Shop paperwork is everyday work: it must not run on the slow, careful setting.
        assertEquals(ModelPlan.Level.EVERYDAY,ModelPlan.level("Make an estimate for front brakes on a 2016 Camry"))
        assertEquals(ModelPlan.Level.EVERYDAY,ModelPlan.level("Make me an invoice and a sample PDF with the zip code 77001"))
        assertEquals(ModelPlan.Level.HARD,ModelPlan.level("research the best shop management software and compare prices"))
        // A photo needs a real look, but not extra thinking.
        assertEquals(ModelPlan.Level.EVERYDAY,ModelPlan.level("what is this?",files=1))
        assertEquals(ModelPlan.Level.EVERYDAY,ModelPlan.level("a".repeat(400)))
        assertEquals(ModelPlan.Level.HARD,ModelPlan.level("a".repeat(700)))
    }
    @Test fun autoPicksEachTierFromTheWholeCatalog() {
        assertEquals(Triple("luna","sol","astra"),ModelPlan.defaults(models,"sol"))
        val auto=AiSettings()
        assertEquals("luna",ModelPlan.model(auto,models,ModelPlan.Level.QUICK))
        assertEquals("astra",ModelPlan.model(auto,models,ModelPlan.Level.HARD))
        // The owner's own tier choices win, even a "More models" one.
        val chosen=AiSettings(quick="old-model",hard="sol")
        assertEquals("old-model",ModelPlan.model(chosen,models,ModelPlan.Level.QUICK))
        assertEquals("sol",ModelPlan.model(chosen,models,ModelPlan.Level.HARD))
        // A removed model falls back to the default for that tier.
        assertEquals("astra",ModelPlan.model(AiSettings(hard="gone"),models,ModelPlan.Level.HARD))
    }
    @Test fun aFixedModelAlwaysWins() {
        val fixed=AiSettings(model="sol")
        ModelPlan.Level.values().forEach {assertEquals("sol",ModelPlan.model(fixed,models,it))}
        // If it disappears from the plan, Auto takes over.
        assertEquals("luna",ModelPlan.model(AiSettings(model="gone"),models,ModelPlan.Level.QUICK))
    }
    @Test fun routinesUseTheirOwnTier() {
        assertEquals("luna",ModelPlan.model(AiSettings(),models,ModelPlan.Level.HARD,routine=true))
        assertEquals("astra",ModelPlan.model(AiSettings(routines="hard"),models,ModelPlan.Level.QUICK,routine=true))
        assertEquals("old-model",ModelPlan.model(AiSettings(routines="old-model"),models,ModelPlan.Level.QUICK,routine=true))
    }
    @Test fun intelligenceIsAutoOrFixedAndAlwaysSupported() {
        val astra=models[0];val luna=models[2];val old=models[3]
        assertEquals("low",ModelPlan.effort(AiSettings(),astra,ModelPlan.Level.QUICK))
        assertEquals("medium",ModelPlan.effort(AiSettings(),astra,ModelPlan.Level.EVERYDAY))
        assertEquals("high",ModelPlan.effort(AiSettings(),astra,ModelPlan.Level.HARD))
        assertEquals("xhigh",ModelPlan.effort(AiSettings(effort="xhigh"),astra,ModelPlan.Level.QUICK))
        // Luna tops out at medium, so High becomes Medium.
        assertEquals("medium",ModelPlan.effort(AiSettings(),luna,ModelPlan.Level.HARD))
        assertEquals("medium",ModelPlan.effort(AiSettings(effort="xhigh"),luna,ModelPlan.Level.QUICK))
        // A model that reports no levels gets the wanted level; the app drops it if ChatGPT refuses.
        assertEquals("high",ModelPlan.effort(AiSettings(),old,ModelPlan.Level.HARD))
    }
    @Test fun labelsNameTheModelAndLevel() {
        assertEquals("Astra · High",ModelPlan.label(models,"astra","high"))
        assertEquals("Luna · Extra high",ModelPlan.label(models,"luna","xhigh"))
        assertEquals("custom",ModelPlan.label(models,"custom",null))
    }
}
