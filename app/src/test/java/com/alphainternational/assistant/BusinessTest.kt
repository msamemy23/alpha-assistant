package com.alphainternational.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The business a person starts with Alpha: the steps, what only the person can do, how changes are applied and what a bot reads about it. */
class BusinessTest {
    private val now=1_800_000_000_000L
    private fun set(vararg p:Pair<String,Any>)=JSONObject().also {o->p.forEach {o.put(it.first,it.second)}}

    @Test fun noBusinessMeansNothingToTellABot() {
        val b=Business()
        assertFalse(b.exists);assertEquals("",b.note());assertNull(b.next());assertEquals(0 to 0,b.progress())
    }

    @Test fun theFirstThingSaidCreatesTheBusinessAndItsSteps() {
        val b=Journey.apply(Business(),set("idea" to "Mobile car detailing in my town"),null,null,now)
        assertTrue(b.exists);assertEquals(now,b.startedAt);assertEquals(Journey.IDEA,b.stage)
        assertTrue(b.steps.isNotEmpty())
        assertEquals("fit",b.next()!!.id)
        // a service business gets a price list step, a store gets a products step, neither gets the other's
        assertTrue(b.steps.any {it.id=="quote"});assertFalse(b.steps.any {it.id=="products"})
        val store=Journey.apply(Business(),set("idea" to "An online store with print on demand shirts"),null,null,now)
        assertTrue(store.steps.any {it.id=="products"});assertFalse(store.steps.any {it.id=="quote"})
        assertEquals("the steps are made only once",b.steps,Journey.apply(b,set("name" to "Shine"),null,null,now+1).steps)
    }

    @Test fun everyStepSaysWhyAndWhatOnlyThePersonCanDo() {
        val steps=Journey.starterSteps("")
        assertTrue(steps.all {it.title.isNotBlank() && it.why.isNotBlank()})
        assertEquals("ids are unique",steps.size,steps.map {it.id}.toSet().size)
        for(id in listOf("legal","money","site","test","customers")) assertTrue(id,steps.first {it.id==id}.needsYou.isNotBlank())
        assertTrue("the legal step says it is not legal advice",steps.first {it.id=="legal"}.why.contains("not legal advice"))
        // nothing promises income
        assertTrue(steps.none {s->listOf("guarantee","get rich","passive income","make \\$").any {Regex(it,RegexOption.IGNORE_CASE).containsMatchIn(s.title+" "+s.why)}})
    }

    @Test fun aStepMovesThroughDoingToDoneAndTheNextOneIsFound() {
        var b=Journey.apply(Business(),set("idea" to "Lawn care","stage" to "checking"),null,null,now)
        assertEquals(Journey.CHECKING,b.stage)
        b=Journey.apply(b,null,set("id" to "fit","status" to "doing","note" to "Comparing lawn care and pressure washing"),null,now)
        assertEquals(Journey.DOING,b.steps.first {it.id=="fit"}.status);assertEquals("fit",b.next()!!.id)
        b=Journey.apply(b,null,set("id" to "fit","status" to "done"),null,now)
        assertEquals("the note stays when only the status changes","Comparing lawn care and pressure washing",b.steps.first {it.id=="fit"}.note)
        assertEquals("numbers",b.next()!!.id)
        val (done,all)=b.progress()
        assertEquals(1,done);assertEquals(b.steps.size,all)
        b=Journey.apply(b,null,set("id" to "numbers","status" to "skipped"),null,now)
        assertEquals("a skipped step counts as passed",2,b.progress().first)
    }

    @Test fun anUnknownStepIsAddedAndBadValuesAreIgnored() {
        var b=Journey.apply(Business(),set("idea" to "Dog walking"),null,null,now)
        b=Journey.apply(b,null,set("id" to "Insurance Quote!","title" to "Get a quote","status" to "nonsense"),null,now)
        val added=b.steps.last()
        assertEquals("insurancequote",added.id);assertEquals(Journey.TODO,added.status);assertEquals("Get a quote",added.title)
        val unchanged=Journey.apply(b,set("stage" to "somewhere"),null,null,now)
        assertEquals(b.stage,unchanged.stage)
        assertEquals("a step with no id is dropped",b.steps,Journey.apply(b,null,set("title" to "No id"),null,now).steps)
    }

    @Test fun choicesAreRememberedWithTheirReason() {
        val b=Journey.apply(Business(),set("idea" to "Cleaning"),null,set("question" to "LLC or sole proprietor?","choice" to "Sole proprietor first","why" to "Cheaper while testing"),now)
        assertEquals(1,b.decisions.size);assertEquals("Sole proprietor first",b.decisions[0].choice)
        assertEquals("a half-filled choice is ignored",1,Journey.apply(b,null,null,set("question" to "Only a question"),now).decisions.size)
    }

    @Test fun whatABotReadsIsShortAndOnlyWhatIsFilledIn() {
        var b=Journey.apply(Business(),set("idea" to "Mobile car detailing","name" to "Shine On","price" to "$80 a car","customers" to "busy people near me"),null,null,now)
        b=Journey.apply(b,null,set("id" to "fit","status" to "done"),null,now)
        val note=b.note()
        assertTrue(note,note.contains("Shine On") && note.contains("Mobile car detailing") && note.contains("$80 a car"))
        assertTrue("it names the next step and what it needs",note.contains("Next: Check the real numbers"))
        assertFalse("empty fields are not mentioned",note.contains("Goal:"))
        assertTrue(note.length<900)
    }

    @Test fun theBusinessSurvivesSavingAndReadingBack() {
        var b=Journey.apply(Business(),set("idea" to "Pet sitting","kind" to "service","name" to "Paws","goal" to "10 clients"),null,null,now)
        b=Journey.apply(b,null,set("id" to "legal","status" to "doing","note" to "Waiting on EIN"),set("question" to "Name?","choice" to "Paws","why" to "Short"),now)
        assertEquals(b,Business.parse(JSONObject(b.json().toString())))
        assertEquals(Business(),Business.parse(null))
    }

    @Test fun longTextIsCutAndOldChoicesAreTrimmed() {
        var b=Journey.apply(Business(),set("idea" to "x".repeat(5000)),null,null,now)
        assertEquals(600,b.idea.length)
        repeat(150) {i->b=Journey.apply(b,null,null,set("question" to "q$i","choice" to "c$i"),now+i)}
        assertEquals(100,b.decisions.size);assertEquals("q149",b.decisions.last().question)
    }
}

/** Start mode's built-in skills: they exist, they are found from what a beginner says, and each one carries the rules that keep it honest. */
class StartSkillsTest {
    private fun pick(prompt:String)=SkillMatch.pick(prompt,emptyList())?.id

    @Test fun everyStartSkillIsRegisteredOnceAndWithTheRules() {
        val ids=StartSkills.all.map {it.id}
        assertEquals(ids.size,ids.toSet().size)
        assertTrue(ids.all {id->Skills.builtIn.count {it.id==id}==1})
        for(s in StartSkills.all) {
            assertTrue(s.id,s.instructions.contains("Never promise income"))
            assertTrue(s.id,s.instructions.contains("business_update"))
            // a recipe is cut at 1,800 characters when it is handed to a job (SkillMatch.note), so the rules at the end must still be inside
            assertTrue(s.id+" is "+s.instructions.length+" characters",s.instructions.length<=1800)
        }
    }

    @Test fun aBeginnerIsFoundFromTheirOwnWords() {
        assertEquals("start_fit",pick("I want to start a business but I don't know what"))
        assertEquals("start_fit",pick("I want to make money on the side"))
        assertEquals("start_fit",pick("Give me some business ideas that fit me"))
        assertEquals("start_numbers",pick("What should I charge for lawn mowing?"))
        assertEquals("start_numbers",pick("How much would it cost to start a cleaning business?"))
        assertEquals("start_name",pick("Help me come up with a name for my business"))
        assertEquals("start_plan",pick("Write me a business plan for the bakery"))
        assertEquals("start_legal",pick("Do I need an LLC or can I just be a sole proprietor?"))
        assertEquals("start_site_test",pick("Make a website for my business so I can test my idea"))
        assertEquals("start_customers",pick("Help me get my first three customers"))
        assertEquals("founder_brief",pick("How is my business doing this week?"))
    }

    @Test fun ordinaryRequestsAreNotTakenForStartingABusiness() {
        for(p in listOf("Make an invoice for Maria's Camry brakes","What is on my calendar tomorrow?","Write a thank you text to the customer about the pickup","Summarize this PDF for me please"))
            assertTrue(p,pick(p)?.startsWith("start_")!=true && pick(p)!="founder_brief")
    }
}
