package com.alphainternational.assistant

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class SkillMatchTest {
    private fun id(prompt:String,user:List<UserSkill> = emptyList(),files:Int=0)=SkillMatch.pick(prompt,user,files=files)?.id

    @Test fun requestsFindTheBuiltInRecipeThatFitsThem() {
        assertEquals("invoice_pdf",id("Make an invoice for Maria Lopez, 2 hours labor"))
        assertEquals("invoice_pdf",id("I need an invoice PDF for the Camry"))
        assertEquals("repair_estimate",id("Make an estimate for front brakes on a 2016 Camry"))
        assertEquals("gmail_draft",id("Draft an email to the parts supplier about the rotors"))
        assertEquals("calendar_plan",id("What's on my calendar tomorrow?"))
        assertEquals("calendar_plan",id("Put the Smith brake job on my calendar for Friday at 10"))
        assertEquals("daily_brief",id("Give me my morning brief"))
        assertEquals("web_research",id("Research the best shop management software for a small garage"))
        assertEquals("trip_planning",id("Help me plan a trip to Austin for the weekend"))
        assertEquals("shopping_compare",id("Compare prices on three floor jacks"))
        assertEquals("product_research",id("Find me a battery for a 2014 Silverado, best price"))
        assertEquals("price_watch",id("Watch the price of that jack and tell me when it drops"))
        assertEquals("talent_scout",id("I need to hire a mechanic, write a job post"))
        assertEquals("meeting_prep",id("Prepare me for my meeting with the fleet manager tomorrow"))
        assertEquals("teach_skill",id("I want to teach you how I do my weekly close"))
        assertEquals("sample_invoice",id("Save a sample invoice in Supabase for testing"))
        assertEquals("vercel_preview",id("Make a Vercel preview of the branch"))
    }

    @Test fun chatterAndUnrelatedRequestsGetNoRecipe() {
        listOf("hi","thanks!","What's the weather tomorrow?","Text John that his car is ready","yes do it","What time do you close on Saturday?","Remember that Dave likes short texts",
            "How much should I charge for an oil change?","Tell me a joke").forEach {assertNull(it,id(it))}
        // A hand-off from another bot and a very short text never get one.
        assertNull(id("From Estimate: please make an invoice for Maria"))
        assertNull(id("invoice"))
    }

    @Test fun aPhotoWithAQuestionGetsThePhotoRecipe() {
        assertEquals("photo_help",id("What is this noise from my car?",files=1))
        assertNull(id("What is this noise from my car?",files=0))
        assertNull(id("Here you go",files=1))
    }

    private val weekly=UserSkill("weekly_specials","Weekly specials post","Write the weekly specials post for Facebook","1. Check the specials list.\n2. Write a post.\n3. Save it as a draft.",0)
    @Test fun skillsTheOwnerTaughtAreMatchedByTheirOwnWords() {
        assertEquals("weekly_specials",id("Write the weekly specials post for this week",listOf(weekly)))
        assertEquals("weekly_specials",id("can you do the weekly specials post again",listOf(weekly)))
        assertNull(id("What's the weather like for the weekend",listOf(weekly)))
        // The / menu names its skill exactly.
        assertEquals("weekly_specials",id("Use the “Weekly specials post” skill (id weekly_specials): ",listOf(weekly)))
        assertEquals("invoice_pdf",id("Use the “Make an invoice PDF” skill (id invoice_pdf): for Maria"))
        // The owner's own skill wins over a built-in one for the same words.
        val mine=UserSkill("my_invoice","My invoice way","Make an invoice the way I like it","1. Use my template.",0)
        assertEquals("my_invoice",id("Make an invoice the way I like it for Maria",listOf(mine)))
        assertTrue(SkillMatch.pick("Make an invoice the way I like it for Maria",listOf(mine))!!.taught)
        assertFalse(SkillMatch.pick("Make an invoice for Maria",emptyList())!!.taught)
    }

    @Test fun theRecipeIsAddedOnceWithItsStepsAndTheWayToTreatThem() {
        val hit=SkillMatch.pick("Make an invoice for Maria",emptyList())!!
        val note=SkillMatch.note(hit)
        assertTrue(note.startsWith(SkillMatch.MARK))
        assertTrue(note.contains("owner's words win") && note.contains("Use invoice_pdf for any invoice"))
        assertTrue(SkillMatch.alreadyIn("""[{"role":"developer","content":"$note"}]""".replace("\n"," ")))
        assertFalse(SkillMatch.alreadyIn("[]"))
        assertTrue(SkillMatch.note(SkillMatch.Hit("x","X","y".repeat(5000),true)).length<2200)
        // A resumed job finds its recipe again in its history (so its tool groups are the same).
        val history=JSONArray().put(obj("role" to "user","content" to "Make an invoice")).put(obj("role" to "developer","content" to note))
        assertEquals(note,SkillMatch.recipeIn(history))
        assertEquals("",SkillMatch.recipeIn(JSONArray().put(obj("role" to "user","content" to "x"))))
    }

    @Test fun aRecipeNamingToolsBringsTheirGroups() {
        val hit=SkillMatch.pick("Draft an email to the parts supplier about the rotors",emptyList())!!
        assertTrue("google_write" in ToolGroups.select("do it",ToolGroups.Kit(recipe=hit.steps)))
        val photo=SkillMatch.pick("Plan a trip to Austin for the weekend",emptyList())!!
        assertNotNull(photo)
    }
}
