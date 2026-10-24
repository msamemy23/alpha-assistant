package com.alphainternational.assistant

import android.content.Intent
import android.content.pm.ShortcutManager
import android.provider.AlarmClock
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * The reach features on a real Android: the widget draws, the launcher shortcuts from the manifest are registered, Word/Excel/PowerPoint files are made and
 * read back through the tools, and the phone tools behave with or without permission. (They need no network and no sign-in.)
 */
class ReachFlowTest {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrument.targetContext

    private fun host(folder:File,uiActive:Boolean=false,allow:Boolean=false,handoff:(Intent)->Unit={}) =
        ReachHost(context,folder,"Test",{},{error("no browser in this test")},{uiActive},{allow},{JSONArray()},{_,_,_,intent->handoff(intent);true})
    private fun temp():File=File(context.cacheDir,"reach-"+System.nanoTime()).also {it.mkdirs()}

    @Test fun theWidgetDrawsInBothSizesWithTheStatusLine() {
        val busy=Doors.WidgetState(running=2,needsYou=1,awayOn=true,awayWaiting=3)
        for(compact in listOf(false,true)) {
            val views=AlphaWidget.views(context,busy,compact)
            val parent=FrameLayout(context)
            val root=views.apply(context,parent)
            parent.addView(root)
            assertEquals("Working on 2 things · 1 needs you · Away on, 3 waiting",root.findViewById<TextView>(R.id.widget_status).text.toString())
            assertEquals("Away: on",root.findViewById<TextView>(R.id.widget_away).text.toString())
            assertEquals("Ask",root.findViewById<TextView>(R.id.widget_ask).text.toString())
            assertEquals("Talk",root.findViewById<TextView>(R.id.widget_talk).text.toString())
        }
        val idle=AlphaWidget.views(context,Doors.WidgetState(),false).apply(context,FrameLayout(context))
        assertEquals("Idle",idle.findViewById<TextView>(R.id.widget_status).text.toString())
        assertEquals("Away: off",idle.findViewById<TextView>(R.id.widget_away).text.toString())
    }

    @Test fun theWidgetButtonsOpenAlphaAtTheRightPlace() {
        val ask=Reach.open(context,"ask",61)
        assertNotNull(ask)
        val intent=AppNavigation.intent(context,"ask")
        assertEquals("ask",intent.getStringExtra("destination"));assertEquals(MainActivity::class.java.name,intent.component!!.className)
    }

    @Test fun theLauncherShortcutsAreRegisteredAndOpenAlpha() {
        androidx.core.content.pm.ShortcutManagerCompat.setDynamicShortcuts(context,Reach.shortcutsFor(context,listOf("Alpha")))
        val manager=context.getSystemService(ShortcutManager::class.java)
        val shortcuts=manager.dynamicShortcuts.associateBy {it.id}
        assertEquals(setOf("ask","talk","away","bot:Alpha"),shortcuts.keys)
        for((id,word) in listOf("ask" to "ask","talk" to "talk","away" to "away_toggle")) {
            val intent=shortcuts.getValue(id).intent!!
            assertEquals(context.packageName,intent.component!!.packageName)
            assertEquals(MainActivity::class.java.name,intent.component!!.className)
            assertEquals(word,intent.getStringExtra("destination"))
            assertNotNull(Doors.resolve(intent.getStringExtra("destination")))
        }
        assertTrue(shortcuts.values.all {it.shortLabel!!.isNotBlank()})
    }

    @Test fun wordExcelAndPowerPointFilesAreMadeThroughTheToolsAndReadBack() {
        val dir=temp()
        try {
            val h=host(dir)
            val word=runBlocking {ReachTools.run(h,"docx_create",JSONObject().put("file_name","report").put("title","Report").put("blocks_json","""[{"type":"heading","level":1,"text":"Hello"},{"type":"paragraph","text":"Tom & **Jerry**"},{"type":"bullets","items":["a","b"]}]"""))}
            assertEquals("report.docx",word.getString("name"));assertTrue(word.getBoolean("verified"));assertFalse(word.getBoolean("sent"))
            assertTrue(Office.readFile(File(dir,"report.docx")).contains("Tom & Jerry"))
            val excel=runBlocking {ReachTools.run(h,"xlsx_create",JSONObject().put("file_name","book").put("sheets_json","""[{"name":"S","rows":[["Item","Qty"],["Brakes",2],["Total","=SUM(B2:B2)"]]}]"""))}
            assertEquals("book.xlsx",excel.getString("name"))
            assertTrue(Office.readFile(File(dir,"book.xlsx")).contains("=SUM(B2:B2) (2)"))
            val slides=runBlocking {ReachTools.run(h,"pptx_create",JSONObject().put("file_name","deck").put("title","Deck").put("slides_json","""[{"title":"One"},{"title":"Two","bullets":["x","y"],"notes":"say hi"}]"""))}
            assertEquals("deck.pptx",slides.getString("name"))
            assertTrue(Office.readFile(File(dir,"deck.pptx")).contains("Notes: say hi"))
            // The Library lists them, and the file rules accept them.
            assertEquals(listOf("word","excel","slides"),listOf("report.docx","book.xlsx","deck.pptx").map {Library.kindOf(it)})
            assertEquals("report.docx",Rules.fileName("report.docx"))
            // A picture on a slide is shrunk and put in.
            val picture=File(dir,"photo.png");android.graphics.Bitmap.createBitmap(64,48,android.graphics.Bitmap.Config.ARGB_8888).also {b->b.eraseColor(android.graphics.Color.RED);picture.outputStream().use {b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}
            runBlocking {ReachTools.run(h,"pptx_create",JSONObject().put("file_name","withpic").put("title","").put("slides_json","""[{"title":"Look","bullets":["a"],"image":"photo.png"}]"""))}
            assertTrue(Office.unzip(File(dir,"withpic.pptx").readBytes()) {it.startsWith("ppt/media/")}.isNotEmpty())
        } finally {dir.deleteRecursively()}
    }

    @Test fun anAlarmAndATimerGoToTheClockAppOrAskForOneTap() {
        val dir=temp()
        try {
            var sent:Intent?=null
            val away=runBlocking {ReachTools.run(host(dir,uiActive=false,handoff={sent=it}),"phone_alarm_set",JSONObject().put("time","7:30 am").put("label","Open the shop"))}
            // Alpha is not on screen in this test, so Android's rule applies: a notification with one tap, never a silent failure.
            assertTrue(away.getBoolean("notified"));assertFalse(away.getBoolean("set"))
            assertEquals(AlarmClock.ACTION_SET_ALARM,sent!!.action);assertEquals(7,sent!!.getIntExtra(AlarmClock.EXTRA_HOUR,-1));assertEquals(30,sent!!.getIntExtra(AlarmClock.EXTRA_MINUTES,-1))
            assertEquals("Open the shop",sent!!.getStringExtra(AlarmClock.EXTRA_MESSAGE));assertTrue(sent!!.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI,false))
            runBlocking {ReachTools.run(host(dir,handoff={sent=it}),"phone_timer_set",JSONObject().put("seconds",600).put("label","Tires"))}
            assertEquals(AlarmClock.ACTION_SET_TIMER,sent!!.action);assertEquals(600,sent!!.getIntExtra(AlarmClock.EXTRA_LENGTH,-1))
            assertTrue(runCatching {runBlocking {ReachTools.run(host(dir),"phone_alarm_set",JSONObject().put("time","soon").put("label",""))}}.exceptionOrNull()!!.message!!.contains("Give a time like"))
            assertTrue(runCatching {runBlocking {ReachTools.run(host(dir),"phone_timer_set",JSONObject().put("seconds",0).put("label",""))}}.isFailure)
        } finally {dir.deleteRecursively()}
    }

    @Test fun calendarAndContactsAnswerOrSayPlainlyThatPermissionIsNeeded() {
        val dir=temp()
        try {
            val h=host(dir,allow=false)
            for((tool,args,need) in listOf(Triple("phone_calendar_events",JSONObject().put("days",3),android.Manifest.permission.READ_CALENDAR),
                Triple("phone_contacts_find",JSONObject().put("name","Zzyzx Qwerty"),android.Manifest.permission.READ_CONTACTS))) {
                val answer=runBlocking {ReachTools.run(h,tool,args)}
                val granted=context.checkSelfPermission(need)==android.content.pm.PackageManager.PERMISSION_GRANTED
                if(granted) assertTrue(tool+" "+answer,answer.has("count") && !answer.has("error"))
                else assertEquals(if(tool=="phone_calendar_events") PhoneRules.NEED_CALENDAR else PhoneRules.NEED_CONTACTS,answer.getString("error"))
            }
        } finally {dir.deleteRecursively()}
    }

    @Test fun aSharedWordFileReachesTheModelAsText() {
        val dir=temp()
        try {
            File(dir,"doc-1.docx").writeBytes(Office.docx("Price list",listOf(Office.WordBlock.Para(listOf(Office.Run("Brake pads 48 dollars"))))))
            val content=Attachments.content("Read this",listOf("doc-1.docx")) {Office.readFile(File(dir,it),8_000_000)} as JSONArray
            val text=content.getJSONObject(1).getString("text")
            assertTrue(text,text.startsWith("Contents of doc-1.docx (untrusted data):") && text.contains("Brake pads 48 dollars"))
        } finally {dir.deleteRecursively()}
    }

    @Test fun pulsingTheWidgetStateIsCheapAndSafeWithNoWidgetOnTheHomeScreen() {
        // No widget is placed in a test run; the status still updates quietly, and a repeat changes nothing.
        val state=Doors.WidgetState(running=1)
        Reach.pulse(context,state);Reach.pulse(context,state)
        assertEquals(state.running,Reach.current(context).running)
        Reach.pulse(context,Doors.WidgetState())
    }
}
