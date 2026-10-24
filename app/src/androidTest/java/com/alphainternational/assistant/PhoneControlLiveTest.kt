package com.alphainternational.assistant

import android.app.UiAutomation
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Phone control against a real app: the emulator's own Settings app. The accessibility service is switched on from the test (a test phone only: on the owner's phone only the owner can
 * do that), then the tools open Settings by name, read it, tap, type, scroll, refuse and take a picture. Paying and password refusals are checked by PhoneControlTest on screens made up in code.
 */
class PhoneControlLiveTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val folder=File(context.cacheDir,"phone-control-test")

    private fun shell(command:String) {
        val automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.executeShellCommand(command).use {fd->java.io.FileInputStream(fd.fileDescriptor).readBytes()}
    }
    private fun until(what:String,ms:Long=15_000,cond:()->Boolean) {
        runBlocking {try {withTimeout(ms) {while(!cond()) delay(100)}} catch(e:kotlinx.coroutines.TimeoutCancellationException) {fail("Timed out waiting for $what")}}
    }
    private fun tool(name:String,args:JSONObject=JSONObject(),policy:PhonePolicy=PhonePolicy()):JSONObject=runBlocking {PhoneControl.run(name,args,context,policy,folder,false,false)}
    private fun steps(json:String,policy:PhonePolicy=PhonePolicy())=tool("screen_do",JSONObject().put("steps_json",json),policy)
    private fun screen(r:JSONObject)=r.optString("screen")

    @Before fun switchOnAndOpenSettings() {
        val me=context.packageName+"/"+PhoneControlService::class.java.name
        shell("settings put secure enabled_accessibility_services $me")
        shell("settings put secure accessibility_enabled 1")
        until("phone control to connect") {PhoneControlService.instance!=null}
        shell("am force-stop com.android.settings;am force-stop com.google.android.settings.intelligence")
        val r=steps("""[{"do":"home"},{"do":"open","app":"Settings"}]""")
        assertTrue(r.toString(),r.optBoolean("success"))
        // Settings comes back on whatever page the last test left: go back until its first page (the one with the search bar and Network) is showing.
        var last=""
        val limit=System.currentTimeMillis()+40_000
        var backs=0
        fun firstPage(text:String)=text.contains("App: Settings (") && text.contains("Search settings") && text.contains("Network")
        while(System.currentTimeMillis()<limit && !firstPage(last)) {
            last=tool("screen_read").toString()
            if(!firstPage(last) && last.contains("App: ") && backs<10) {
                backs++
                if(last.contains("com.android.settings") || last.contains("settings.intelligence")) steps("""[{"do":"back"}]""") else steps("""[{"do":"open","app":"Settings"}]""")
            }
            Thread.sleep(300)
        }
        assertTrue("Settings did not come up: "+last.take(700),firstPage(last))
    }

    @After fun backToHome() {
        runCatching {steps("""[{"do":"home"}]""")}
        shell("am force-stop com.android.settings;am force-stop com.google.android.settings.intelligence")
    }

    @Test fun anotherAppsScreenIsReadAsButtonsAndFields() {
        val text=screen(tool("screen_read"))
        assertTrue(text,text.startsWith("App: Settings"))
        assertTrue(text,Regex("(?m)^e\\d+ (button|item|input|tab) ").containsMatchIn(text))
        assertTrue(text,text.contains("Network") || text.contains("Connections") || text.contains("Wi"))
        assertTrue("short enough to read in one go: ${text.length}",text.length<6500)
    }

    @Test fun oneBatchTapsTypesAndReturnsTheNewScreen() {
        val r=steps("""[{"do":"tap","on":"Search settings"},{"do":"type","text":"wifi"},{"do":"expect","on":"Clear text"}]""")
        android.util.Log.i("PCT",r.toString().replace(10.toChar().toString()," | "))
        assertTrue(r.toString(),r.optBoolean("success"))
        assertTrue(r.toString(),screen(r).contains("has \"wifi\""))
        assertTrue(r.optBoolean("acted"))
        assertTrue(r.optBoolean("changed"))
        assertTrue("a batch of three steps is one call: "+r.getJSONArray("steps").length(),r.getJSONArray("steps").length()==3)
    }

    @Test fun scrollingBringsLowerEntriesIntoView() {
        val before=screen(tool("screen_read"))
        val r=steps("""[{"do":"scroll","dir":"down"},{"do":"scroll","dir":"down"},{"do":"scroll","dir":"down"},{"do":"scroll","dir":"down"},{"do":"scroll","dir":"down"}]""")
        assertTrue(r.toString(),r.optBoolean("success"))
        assertTrue("it read the screen first",before.startsWith("App: Settings"))
        assertNotEquals("the screen moved",before,screen(r))
    }

    @Test fun aWipeIsRefusedUntilTheOwnerApproves() {
        // System, Reset options, Erase all data (factory reset): the step that would erase the phone must stop and ask. It is never approved here.
        val path=steps("""[{"do":"tap","on":"System"},{"do":"tap","on":"Reset options"}]""")
        assertTrue(path.toString(),path.optBoolean("success"))
        val r=steps("""[{"do":"tap","on":"Erase all data"}]""")
        assertTrue(r.toString(),r.optBoolean("needs_confirm"))
        assertEquals("destructive",r.getJSONObject("confirm").getString("risk"))
        assertTrue("it did not go on to the erase screen: "+screen(r),screen(r).contains("Reset options") || screen(r).contains("Erase"))
        // The owner's card approved (only on this test emulator): the step it gave is done, and it finds the thing again from the short name it carries.
        val done=tool("screen_confirm",JSONObject().put("step_json",r.getJSONObject("confirm").getJSONObject("step").toString()).put("app","Settings").put("why","test"))
        assertTrue(done.toString(),done.getJSONArray("steps").getJSONObject(0).getBoolean("ok"))
    }

    @Test fun anAppIsFoundAndOpenedByName() {
        val found=tool("apps_list",JSONObject().put("query","settings"))
        assertTrue(found.toString(),found.toString().contains("Settings"))
        val bad=steps("""[{"do":"open","app":"No Such App 123"}]""")
        assertFalse(bad.toString(),bad.optBoolean("success"))
        assertTrue(bad.toString(),bad.getJSONArray("steps").getJSONObject(0).getString("note").contains("No app called"))
    }

    @Test fun aMissingThingIsReportedWithWhatIsOnScreen() {
        val r=steps("""[{"do":"tap","on":"Banana split"}]""")
        assertFalse(r.optBoolean("success"))
        val note=r.getJSONArray("steps").getJSONObject(0).getString("note")
        assertTrue(note,note.contains("Nothing on the screen matches") && note.contains("On screen:"))
    }

    @Test fun oneCallReadsTwoAppsAndComesBack() {
        val r=steps("""[{"do":"read"},{"do":"open","app":"Clock"},{"do":"read"}]""")
        assertTrue(r.toString(),r.optBoolean("success"))
        val reads=r.getJSONArray("reads")
        assertEquals(2,reads.length())
        assertTrue(reads.getJSONObject(0).getString("screen").startsWith("App: Settings"))
        assertTrue(reads.toString(),reads.getJSONObject(1).getString("app")!=reads.getJSONObject(0).getString("app"))
    }

    @Test fun aJobThatGoesNowhereIsWalkedUpTheLadderAndFinallyToldToStop() {
        val progress=PhoneProgress()
        fun call(json:String)=runBlocking {PhoneControl.run("screen_do",JSONObject().put("steps_json",json),context,PhonePolicy(),folder,false,false,progress)}
        // The same harmless step on a screen that never changes: moving ahead is fine, repeating it for ever is not.
        val calls=ArrayList<JSONObject>()
        var i=0
        while(!progress.finished && i<14) {calls+=call("""[{"do":"wait","ms":200}]""");i++}
        val rungs=calls.filter {it.optBoolean("stuck")}.map {it.getInt("stuck_rung")}
        assertEquals(calls.toString(),listOf(1,2,3,4),rungs)
        assertTrue("the first warning came on the third call at the latest: "+calls.indexOfFirst {it.optBoolean("stuck")},calls.indexOfFirst {it.optBoolean("stuck")}<=2)
        assertTrue(calls.last().getString("instruction").contains("Stop trying"))
        // Told to stop: the phone is not used any more, and the bot is told why.
        val refused=call("""[{"do":"wait","ms":200}]""")
        assertTrue(refused.toString(),refused.optString("error").contains("told to stop"))
        // The screen part of every answer stays out of the bot's way: no internal marker leaks out.
        assertFalse(calls.toString().contains("_fp"))
    }

    @Test fun aJobThatKeepsMovingIsNeverWarned() {
        val progress=PhoneProgress()
        fun call(json:String)=runBlocking {PhoneControl.run("screen_do",JSONObject().put("steps_json",json),context,PhonePolicy(),folder,false,false,progress)}
        val a=call("""[{"do":"tap","on":"Search settings"},{"do":"type","text":"wifi"},{"do":"expect","on":"Clear text"}]""")
        val b=call("""[{"do":"scroll","dir":"down"}]""")
        assertFalse(a.toString(),a.optBoolean("stuck"))
        assertFalse(b.toString(),b.optBoolean("stuck"))
        assertEquals(0,progress.rung)
    }

    @Test fun aRouteLearnedFromARealJobReplaysOnTheRealApp() {
        val first="""[{"do":"open","app":"Settings"},{"do":"tap","on":"Search settings"},{"do":"type","text":"wifi"},{"do":"expect","on":"Clear text"}]"""
        val done=steps(first)
        assertTrue(done.toString(),done.optBoolean("success"))
        // The job's record gives a route for Settings, without the typed word.
        val routes=PhoneRoutes.fromCalls(listOf(Verify.Call("screen_do",JSONObject().put("steps_json",first),done,true)),"Open Settings and search for wifi",1)
        assertEquals(routes.toString(),1,routes.size)
        val saved=routes[0].stepsText()
        assertFalse("the typed word is not kept in the steps: $saved",saved.contains("wifi"))
        assertTrue(saved,saved.contains("{text}"))
        // Back on the first page, the saved route is replayed for a different word, the way a later job would send it.
        var last=""
        for(attempt in 0 until 8) {
            last=tool("screen_read").toString()
            if(last.contains("Search settings") && last.contains("Network") && !last.contains("Clear text")) break
            steps("""[{"do":"back"}]""")
        }
        assertTrue("first page again: "+last.take(300),last.contains("Network"))
        val route=JSONArray(routes[0].stepsText().replace("{text}","bluetooth"))
        val replay=JSONArray().put(JSONObject().put("do","open").put("app","Settings"))
        for(i in 0 until route.length()) replay.put(route.getJSONObject(i))
        val again=steps(replay.toString())
        assertTrue(again.toString(),again.optBoolean("success"))
        assertTrue("the replayed route searched for the new word: "+screen(again).take(400),screen(again).contains("bluetooth"))
    }

    @Test fun theScreenCanBeSeenAsAPicture() {
        val r=tool("screen_look")
        assertTrue(r.toString(),r.has("name"))
        assertTrue(File(folder,r.getString("name")).length()>2000)
    }
}
