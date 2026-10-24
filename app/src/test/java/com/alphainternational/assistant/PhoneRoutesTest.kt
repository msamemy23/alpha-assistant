package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * The owner: bots should "remember the working route for each app so repeat jobs run faster". A route is learned from a job that finished well, shown to the next job like it,
 * and never holds what was typed, anything that sends, pays or deletes, or a messaging app.
 */
class PhoneRoutesTest {
    private fun doCall(steps:String,okFlags:List<Boolean>):Verify.Call {
        val results=JSONArray()
        okFlags.forEachIndexed {i,ok->results.put(JSONObject().put("n",i+1).put("do","step").put("ok",ok).put("note","."))}
        return Verify.Call("screen_do",JSONObject().put("steps_json",steps),JSONObject().put("steps",results).put("success",okFlags.all {it}),okFlags.all {it})
    }
    private val calc="""[{"do":"open","app":"Calculator"},{"do":"tap","on":"2"},{"do":"tap","on":"4"},{"do":"tap","on":"×"},{"do":"tap","on":"3"},{"do":"tap","on":"="}]"""

    private val find="""[{"do":"open","app":"Settings"},{"do":"tap","on":"Search settings"},{"do":"type","into":"Search settings","text":"wifi"},{"do":"tap","on":"Wi-Fi"},{"do":"wait_for","on":"Network"}]"""

    @Test fun aGoodJobLeavesTheStepsThatWorkedForTheApp() {
        val routes=PhoneRoutes.fromCalls(listOf(doCall(find,List(5) {true})),"Open Settings and look for wifi",1000)
        assertEquals(1,routes.size)
        assertEquals("Settings",routes[0].app)
        assertEquals(4,routes[0].steps.size)
        assertEquals("tap",routes[0].steps[0].getString("do"))
        assertEquals("Search settings",routes[0].steps[0].getString("on"))
        assertTrue(routes[0].goal.startsWith("Open Settings"))
    }

    /** On the live phone a bot shown a calculator route (14 taps of digits) mangled it for the next sum: data being entered is not a route. */
    @Test fun aRunOfOneCharacterTapsIsDataEntryAndIsNotKept() {
        assertTrue(PhoneRoutes.fromCalls(listOf(doCall(calc,List(6) {true})),"work out 24 times 3 in the Calculator",1).isEmpty())
    }

    @Test fun whatWasTypedOrSharedIsNeverKept() {
        val steps="""[{"do":"open","app":"Notes"},{"do":"share","app":"Notes","text":"my private poem about Maria"},{"do":"tap","on":"Create note"},{"do":"tap","on":"Title"},{"do":"type","into":"Title","text":"secret title 123"}]"""
        val routes=PhoneRoutes.fromCalls(listOf(doCall(steps,List(5) {true})),"write a note",1)
        assertEquals(1,routes.size)
        val written=PhoneRoutes.write(routes)
        assertFalse(written,written.contains("Maria") || written.contains("secret title"))
        assertTrue(written,written.contains("{text}"))
        assertEquals("{text}",routes[0].steps.first {it.getString("do")=="share"}.getString("text"))
    }

    @Test fun stepsThatFailedAreLeftOutAndTheLaterWayIsKept() {
        val first=doCall("""[{"do":"open","app":"Notes"},{"do":"tap","on":"Create note"},{"do":"type","into":"Body","text":"x"}]""",listOf(true,true,false))
        val second=doCall("""[{"do":"share","app":"Notes","text":"x"}]""",listOf(true))
        val routes=PhoneRoutes.fromCalls(listOf(first,second),"write a note",1)
        assertEquals(listOf("tap","share"),routes[0].steps.map {it.getString("do")})
    }

    @Test fun aRouteThatNeedsAScreenNumberOrCouldSendOrPayIsNotKept() {
        val byNumber=doCall("""[{"do":"open","app":"Settings"},{"do":"tap","id":"e8"}]""",listOf(true,true))
        assertTrue(PhoneRoutes.fromCalls(listOf(byNumber),"x",1).isEmpty())
        val sends=doCall("""[{"do":"open","app":"Calculator"},{"do":"tap","on":"Send"}]""",listOf(true,true))
        assertTrue(PhoneRoutes.fromCalls(listOf(sends),"x",1).isEmpty())
        val pays=doCall("""[{"do":"open","app":"Settings"},{"do":"tap","on":"Delete account"}]""",listOf(true,true))
        assertTrue(PhoneRoutes.fromCalls(listOf(pays),"x",1).isEmpty())
    }

    @Test fun messagingMailAndMoneyAppsAreNeverRemembered() {
        listOf("Messages","Gmail","WhatsApp","Phone","Contacts","Samsung Wallet","Instagram").forEach {app->
            val c=doCall("""[{"do":"open","app":"$app"},{"do":"tap","on":"Search"}]""",listOf(true,true))
            assertTrue(app,PhoneRoutes.fromCalls(listOf(c),"x",1).isEmpty())
        }
        assertTrue(PhoneRoutes.appAllowed("Calculator") && PhoneRoutes.appAllowed("Settings") && PhoneRoutes.appAllowed("Camera"))
    }

    @Test fun aJobThatOnlyOpenedAnAppLeavesNothing() {
        assertTrue(PhoneRoutes.fromCalls(listOf(doCall("""[{"do":"open","app":"Camera"},{"do":"read"}]""",listOf(true,true))),"x",1).isEmpty())
    }

    @Test fun twoAppsInOneJobMakeTwoRoutes() {
        val steps="""[{"do":"open","app":"Chrome"},{"do":"tap","on":"Search or type URL"},{"do":"type","into":"Search","text":"q","enter":true},{"do":"open","app":"Settings"},{"do":"tap","on":"About phone"}]"""
        val routes=PhoneRoutes.fromCalls(listOf(doCall(steps,List(5) {true})),"x",1)
        assertEquals(listOf("Chrome","Settings"),routes.map {it.app})
    }

    @Test fun theShortestRouteForTheSameKindOfJobWins() {
        val long=PhoneRoutes.fromCalls(listOf(doCall("""[{"do":"open","app":"Calculator"},{"do":"back"},{"do":"tap","on":"2"},{"do":"tap","on":"4"},{"do":"tap","on":"="}]""",List(5) {true})),"work out 24 times 3 in the Calculator",1)
        val short=PhoneRoutes.fromCalls(listOf(doCall("""[{"do":"open","app":"Calculator"},{"do":"tap","on":"2"},{"do":"tap","on":"="}]""",List(3) {true})),"work out 24 times 3 in the Calculator app",2)
        val merged=PhoneRoutes.merge(PhoneRoutes.merge(emptyList(),long),short)
        assertEquals(1,merged.size)
        assertEquals(2,merged[0].steps.size)
        assertEquals(2,merged[0].ok)
        // and a longer way found later does not replace the shorter one
        val again=PhoneRoutes.merge(merged,long)
        assertEquals(2,again[0].steps.size)
        assertEquals(3,again[0].ok)
    }

    @Test fun routesAreKeptPerAppAndTrimmed() {
        val many=(1..9).map {i->PhoneRoutes.Route("id$i","Settings","kw${i}a kw${i}b kw${i}c",listOf(JSONObject().put("do","tap").put("on","x$i")),1,0,i.toLong())}
        val merged=PhoneRoutes.merge(emptyList(),many)
        assertTrue("per app cap: "+merged.size,merged.size<=PhoneRoutes.MAX_PER_APP)
    }

    @Test fun aJobIsShownTheRoutesOfTheAppsItNamesAndNotOthers() {
        val calcRoute=PhoneRoutes.Route("a","Calculator","work out 24 times 3",listOf(JSONObject().put("do","tap").put("on","2")),3,0,1)
        val settingsRoute=PhoneRoutes.Route("b","Settings","find the android version",listOf(JSONObject().put("do","tap").put("on","About phone")),1,0,1)
        val kept=listOf(calcRoute,settingsRoute)
        assertEquals(listOf("a"),PhoneRoutes.relevant(kept,"Open the Calculator and multiply 123 by 45").map {it.id})
        assertEquals(listOf("b"),PhoneRoutes.relevant(kept,"Open Settings and tell me the Android version").map {it.id})
        assertTrue(PhoneRoutes.relevant(kept,"What is the capital of Australia").isEmpty())
        // a request for the same kind of job is matched even without the app's name
        assertEquals("b",PhoneRoutes.relevant(kept,"find the android version of this phone please").first().id)
    }

    @Test fun theNoteGivesTheStepsAndSaysWhatToFillIn() {
        val r=PhoneRoutes.Route("a","Notes","write a note",listOf(JSONObject().put("do","share").put("app","Notes").put("text","{text}")),1,0,1)
        val note=PhoneRoutes.note(listOf(r))
        assertTrue(note,note.contains("Routes that worked on this phone before") && note.contains("{text}") && note.contains("\"share\""))
        assertEquals("",PhoneRoutes.note(emptyList()))
    }

    @Test fun aRouteThatKeepsMissingIsDroppedAndAGoodEndConfirmsIt() {
        val r=PhoneRoutes.Route("a","Settings","x y z",listOf(JSONObject().put("do","tap").put("on","x")),1,0,1)
        val once=PhoneRoutes.noteResult(listOf(r),listOf("a"),false)
        assertEquals(1,once.size);assertEquals(1,once[0].miss)
        val twice=PhoneRoutes.noteResult(once,listOf("a"),false)
        assertTrue("dropped after missing twice with only one success",twice.isEmpty())
        val good=PhoneRoutes.noteResult(listOf(r),listOf("a"),true)
        assertEquals(2,good[0].ok)
        // routes that were not shown to the job are left alone
        assertEquals(1,PhoneRoutes.noteResult(listOf(r),listOf("other"),false)[0].let {it.miss+1})
    }

    @Test fun theStoreKeepsRoutesInAFileAndForgetsThemOnRequest() {
        val file=File.createTempFile("routes",".json")
        try {
            val store=PhoneRoutes.Store(file)
            assertEquals(0,store.count())
            store.add(PhoneRoutes.fromCalls(listOf(doCall(find,List(5) {true})),"Open Settings and look for wifi",5))
            assertEquals(1,store.count())
            assertEquals(1,PhoneRoutes.Store(file).count())
            assertEquals("Settings",PhoneRoutes.Store(file).all()[0].app)
            store.clear()
            assertEquals(0,store.count())
            assertEquals(0,PhoneRoutes.Store(file).count())
        } finally {file.delete()}
    }

    @Test fun aBrokenFileIsIgnored() {
        val file=File.createTempFile("routes",".json")
        try {file.writeText("not json at all");assertEquals(0,PhoneRoutes.Store(file).count())} finally {file.delete()}
    }
}
