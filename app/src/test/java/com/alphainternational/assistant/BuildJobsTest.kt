package com.alphainternational.assistant

import org.junit.Assert.*
import org.junit.Test

/** Making a site, an app or a game gets the time and the model it needs; cutting it off at 6 minutes is what left bots' sites unfinished and basic. */
class BuildJobsTest {
    @Test fun aQuestionKeepsTheShortBudgetAndABuildGetsAHalfHour() {
        assertEquals(AgentRules.Budget(6,9),AgentRules.budget(false))
        val build=AgentRules.budget(true)
        assertTrue(build.wrapUpMinutes>=20 && build.stopMinutes>build.wrapUpMinutes && build.stopMinutes<=40)
    }

    @Test fun buildingIsAlwaysTheHardLevel() {
        assertEquals(ModelPlan.Level.HARD,ModelPlan.level("make me a site",0,true))
        assertEquals(ModelPlan.Level.HARD,ModelPlan.level("hi",0,true))
        assertNotEquals(ModelPlan.Level.HARD,ModelPlan.level("hi",0,false))
        assertNotEquals(ModelPlan.Level.HARD,ModelPlan.level("what time do you close",0,false))
    }

    @Test fun requestsForWebsitesAndSoftwareAreRecognisedInManyWords() {
        listOf("build me a website for my bakery","I want a website","create a web page with my hours","I need an app to track my customers","make software for my shop",
            "design a landing page for brake specials","build a dashboard with my sales","I need a calculator for tire sizes","create an online store","make me an estimate builder tool",
            "code a little game for the lobby","a tracker for my oil changes").forEach {assertTrue(it,Studio.wants(it,false))}
        listOf("what's the weather","text John his car is ready","make an estimate for Maria","write an email to the parts supplier","call the tire store","summarize my emails",
            "I need an oil change quote for a Camry").forEach {assertFalse(it,Studio.wants(it,false))}
    }
}
