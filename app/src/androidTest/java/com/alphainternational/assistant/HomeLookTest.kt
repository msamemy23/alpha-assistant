package com.alphainternational.assistant

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Draws the new front door (Home), a chat with a growing answer and a result strip, and Settings, in the dark and the light look, and saves a picture of
 * each to the app's files folder, so the look can be checked on the emulator without signing in anywhere. Nothing is sent and no setting is changed.
 */
@RunWith(AndroidJUnit4::class)
class HomeLookTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext

    private fun capture(activity:Activity,out:File) {
        val view=activity.window.decorView
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        val done=CountDownLatch(1)
        PixelCopy.request(activity.window,bitmap,{done.countDown()},Handler(Looper.getMainLooper()))
        assertTrue("the screen copy timed out",done.await(8,TimeUnit.SECONDS))
        out.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }

    private fun show(scenario:ActivityScenario<MainActivity>,content:@Composable (MainActivity)->Unit) {
        scenario.onActivity {activity->
            val view=ComposeView(activity)
            view.setContent {content(activity)}
            activity.setContentView(view)
        }
    }

    /** A sample chat: your question, a finished answer with its result strip, and an answer still being written. */
    @Composable private fun SampleChat() {
        val scroll=androidx.compose.foundation.rememberScrollState()
        val chat=ChatActions(link={},copy={},share={},ask={},openSource={},evidence={null})
        val answer=Message("m1","assistant","# Three drafts are ready, nothing is sent\n\nI wrote one reply for each customer who asked about an estimate. They are saved as drafts so you can read them first.\n\n- Dave: brake pads for the Camry\n- Maria: oil change this Friday\n- Lee: a quote for new tires",
            "t1",bot="Alpha",at=System.currentTimeMillis(),receipt=org.json.JSONArray(listOf("3 drafts saved","0 sent","File estimates-oct.pdf made")).toString())
        CompositionLocalProvider(LocalChat provides chat) {
            Column(Modifier.fillMaxSize().background(Background).systemBarsPadding().verticalScroll(scroll).padding(horizontal=14.dp,vertical=14.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                UserMessage("Answer the three estimate requests from today","") {}
                AssistantMessage("Alpha",System.currentTimeMillis(),true,answer,false,null,{}) {}
                UserMessage("Now find the cheapest set of Camry brake pads","") {}
                LiveAnswer("Researcher","# The cheapest set is about \$38\n\nI compared three shops. The best price so far is at **RockAuto**, and it ships in two days.\n\n| Shop | Price |\n|---|---|\n| RockAuto | \$38 |\n| AutoZone | \$54 |",null) {}
            }
        }
    }

    private fun lookAt(look:String,tag:String) {
        val outDir=context.getExternalFilesDir(null)!!
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        var shown:Activity?=null
        show(scenario) {activity->
            shown=activity
            val c=activity.c
            AlphaTheme(look) {
                Box(Modifier.fillMaxSize().background(Background).systemBarsPadding()) {
                    HomeScreen(c,{},{},{},{},{},{},{},activity,{})
                }
            }
        }
        Thread.sleep(3000)
        capture(shown!!,File(outDir,"home-$tag.png"))
        show(scenario) {AlphaTheme(look) {SampleChat()}}
        Thread.sleep(2500)
        capture(shown!!,File(outDir,"chat-$tag.png"))
        show(scenario) {activity->
            AlphaTheme(look) {
                Box(Modifier.fillMaxSize().background(Background).systemBarsPadding()) {
                    SettingsScreen(activity,activity.c,{},{},{},{})
                }
            }
        }
        Thread.sleep(2500)
        capture(shown!!,File(outDir,"settings-$tag.png"))
        show(scenario) {activity->
            AlphaTheme(look) {
                Box(Modifier.fillMaxSize().background(Background).systemBarsPadding()) {BrainstormScreen(activity,activity.c) {}}
            }
        }
        Thread.sleep(2500)
        capture(shown!!,File(outDir,"brainstorm-$tag.png"))
        scenario.close()
        for(name in listOf("home","chat","settings","brainstorm")) assertTrue("$name-$tag.png is too small",File(outDir,"$name-$tag.png").length()>10_000)
    }

    @Test fun drawTheNewHomeChatAndSettingsInTheDarkLook() = lookAt("dark","dark")

    @Test fun drawTheSameInTheLightLook() = lookAt("light","light")
}
