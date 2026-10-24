package com.alphainternational.assistant

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The ways in that are not the app icon (launcher shortcuts, the Quick Settings tile, the widget): what a launch means, the widget's words, and that the manifest wires them up correctly. */
class DoorsTest {
    private fun xml(path:String)=DocumentBuilderFactory.newInstance().apply {isNamespaceAware=true}.newDocumentBuilder().parse(File(path))
    private val android="http://schemas.android.com/apk/res/android"
    private fun Element.attr(name:String)=getAttributeNS(android,name)
    private fun Element.children(tag:String)=(0 until getElementsByTagName(tag).length).map {getElementsByTagName(tag).item(it) as Element}

    @Test fun aLaunchMeansTheWordItCarries() {
        // The older words notifications use.
        listOf("chat","tasks","call","connect","review","team","away").forEach {assertEquals(it,Doors.resolve(it))}
        // The newer front doors.
        listOf("ask","talk","away_toggle").forEach {assertEquals(it,Doors.resolve(it))}
        assertEquals("ask",Doors.resolve("  ASK "))
        // The two older flags still work, and the destination wins over them.
        assertEquals("call",Doors.resolve(null,openCall=true));assertEquals("tasks",Doors.resolve(null,openTasks=true));assertEquals("chat",Doors.resolve("chat",openCall=true))
        assertEquals("call",Doors.resolve(null,openCall=true,openTasks=true))
        // Coming back from a sign-in page always means the Marketplace.
        assertEquals("connect",Doors.resolve("chat",signInReturn=true));assertEquals("connect",Doors.resolve(null,signInReturn=true))
        // A plain open, and anything this app does not know (it can be started by any app), mean nothing.
        assertNull(Doors.resolve(null));assertNull(Doors.resolve(""));assertNull(Doors.resolve("delete_everything"));assertNull(Doors.resolve("chat; away"));assertNull(Doors.resolve("../chat"))
        assertNull(Doors.resolve("hogwash",openCall=true))
    }

    @Test fun eachFrontDoorOpensTheRightPlaceAndStartsNothingByItself() {
        val ask=Doors.route("ask")!!
        assertEquals("Ask opens the team chat (Home is the list of bots, with no text box)","home/team",Doors.ASK_SCREEN);assertEquals(Doors.ASK_SCREEN,ask.screen);assertEquals("",ask.dialog);assertTrue(ask.composer)
        val talk=Doors.route("talk")!!
        assertNull(talk.screen);assertEquals("call",talk.dialog);assertFalse(talk.composer)
        assertEquals("home/away",Doors.route("away_toggle")!!.screen);assertEquals("home/away",Doors.route("away")!!.screen)
        assertEquals("home/chat/activity",Doors.route("tasks")!!.screen);assertEquals("home/marketplace",Doors.route("connect")!!.screen)
        // Every destination that can arrive has a place to go, and only "ask" moves the cursor.
        Doors.destinations.forEach {assertNotNull(it,Doors.route(it))}
        assertEquals(setOf("ask"),Doors.destinations.filter {Doors.route(it)!!.composer}.toSet())
        assertNull(Doors.route("something else"))
    }

    @Test fun theWidgetLineSaysWhatAlphaIsDoing() {
        fun line(running:Int=0,needs:Int=0,away:Boolean=false,waiting:Int=0)=Doors.statusLine(Doors.WidgetState(running,needs,away,waiting))
        assertEquals("Idle",line())
        assertEquals("Working on 1 thing",line(running=1));assertEquals("Working on 2 things",line(running=2))
        assertEquals("1 needs you",line(needs=1));assertEquals("3 need you",line(needs=3))
        assertEquals("Away on",line(away=true));assertEquals("Away on, 2 waiting",line(away=true,waiting=2))
        assertEquals("Working on 2 things · 1 needs you · Away on",line(2,1,true))
        // Texts waiting only matter while Away mode is on.
        assertEquals("Idle",line(waiting=4))
        assertEquals("Away: on",Doors.awayLabel(true));assertEquals("Away: off",Doors.awayLabel(false))
    }

    @Test fun theBotsOffered_asShortcutsAreTheOnesUsedMost() {
        val names=listOf("Alpha","Personal","Parts","Writer")
        assertEquals(listOf("Alpha","Personal"),Doors.topBots(names,emptyMap()))
        assertEquals(listOf("Writer","Parts"),Doors.topBots(names,mapOf("Parts" to 5L,"Writer" to 9L)))
        assertEquals(listOf("Parts","Alpha"),Doors.topBots(names,mapOf("Parts" to 5L)))
        assertEquals(listOf("Alpha"),Doors.topBots(listOf("Alpha","Alpha"),emptyMap(),3))
        assertTrue(Doors.topBots(emptyList(),emptyMap()).isEmpty())
        assertEquals(1,Doors.topBots(names,emptyMap(),1).size)
    }

    // ------------------------------------------------------------ the manifest and resources

    @Test fun theLauncherShortcutsAllOpenAKnownDestination() {
        // Ask, Talk and Away are made in code (Reach.shortcutsFor): their words must mean something, and their labels and icons must exist.
        val strings=File("src/main/res/values/reach.xml").readText()
        for((id,word) in listOf("ask" to "ask","talk" to "talk","away" to "away_toggle")) {
            assertNotNull(word,Doors.resolve(word))
            listOf("shortcut_${id}_short","shortcut_${id}_long").forEach {assertTrue("$it is missing",strings.contains("name=\"$it\""))}
            assertTrue(File("src/main/res/drawable/ic_shortcut_$id.xml").isFile)
        }
    }

    @Test fun theManifestWiresTheFrontDoorsCorrectly() {
        val manifest=xml("src/main/AndroidManifest.xml").documentElement
        val application=manifest.children("application").single()
        val main=application.children("activity").single {it.attr("name")==".MainActivity"}
        // The widget is a receiver other apps (the launcher) must be able to reach; its Away button's receiver must not be.
        val widget=application.children("receiver").single {it.attr("name")==".AlphaWidget"}
        assertEquals("true",widget.attr("exported"))
        assertTrue(widget.children("action").any {it.attr("name")=="android.appwidget.action.APPWIDGET_UPDATE"})
        assertTrue(widget.children("meta-data").any {it.attr("name")=="android.appwidget.provider" && it.attr("resource")=="@xml/alpha_widget_info"})
        assertEquals("false",application.children("receiver").single {it.attr("name")==".WidgetReceiver"}.attr("exported"))
        // The tile is a service only the system may bind.
        val tile=application.children("service").single {it.attr("name")==".AlphaTile"}
        assertEquals("true",tile.attr("exported"));assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE",tile.attr("permission"))
        assertTrue(tile.children("action").any {it.attr("name")=="android.service.quicksettings.action.QS_TILE"})
        assertTrue(tile.attr("label").startsWith("@string/") && tile.attr("icon").startsWith("@drawable/"))
        // Nothing else that listens for outside intents lost its protection.
        application.children("receiver").filter {it.attr("name")!=".AlphaWidget"}.forEach {assertEquals(it.attr("name"),"false",it.attr("exported"))}
        // The permissions the phone tools ask for at the moment they need them.
        val permissions=manifest.children("uses-permission").map {it.attr("name")}
        listOf("android.permission.READ_CALENDAR","android.permission.READ_CONTACTS","com.android.alarm.permission.SET_ALARM").forEach {assertTrue(it,it in permissions)}
        // The share sheet takes Word, Excel and PowerPoint files too.
        val sendTypes=main.children("intent-filter").first {f->f.children("action").any {it.attr("name")=="android.intent.action.SEND"}}.children("data").map {it.attr("mimeType")}
        assertTrue(sendTypes.containsAll(listOf(Office.DOCX_MIME,Office.XLSX_MIME,Office.PPTX_MIME,"application/pdf")))
    }

    @Test fun theWidgetFilesFitTogether() {
        val info=xml("src/main/res/xml/alpha_widget_info.xml").documentElement
        assertEquals("horizontal|vertical",info.attr("resizeMode"))
        assertEquals("@layout/widget_alpha",info.attr("initialLayout"))
        listOf("widget_alpha","widget_alpha_compact").forEach {layout->
            val text=File("src/main/res/layout/$layout.xml").readText()
            // Both layouts carry every id the code fills in, so either one can be drawn.
            listOf("widget_status","widget_ask","widget_talk","widget_away").forEach {id->assertTrue("$layout lacks $id",text.contains("@+id/$id"))}
            // Only the kinds of view a widget may use.
            Regex("<([A-Za-z]+)[ \n]").findAll(text).map {it.groupValues[1]}.toSet().forEach {assertTrue("$layout uses $it",it in setOf("LinearLayout","TextView","ImageView"))}
        }
        val strings=File("src/main/res/values/reach.xml").readText()
        Regex("@string/(\\w+)").findAll(File("src/main/res/layout/widget_alpha.xml").readText()+File("src/main/res/layout/widget_alpha_compact.xml").readText()+File("src/main/res/xml/alpha_widget_info.xml").readText())
            .map {it.groupValues[1]}.toSet().forEach {assertTrue("$it is missing",strings.contains("name=\"$it\""))}
        Regex("@drawable/(\\w+)").findAll(File("src/main/res/layout/widget_alpha.xml").readText()).map {it.groupValues[1]}.toSet().forEach {assertTrue("$it is missing",File("src/main/res/drawable/$it.xml").isFile)}
    }
}
