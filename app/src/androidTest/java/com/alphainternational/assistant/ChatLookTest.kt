package com.alphainternational.assistant

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Draws the redesigned chat with a sample answer and live work card, so the look can be checked on the emulator without signing in anywhere. */
@RunWith(AndroidJUnit4::class)
class ChatLookTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext

    private fun fakePage(name:String,title:String,color:Int) {
        val bitmap=Bitmap.createBitmap(640,1050,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(AColor.WHITE)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color=color;canvas.drawRect(0f,0f,640f,86f,paint)
        paint.color=AColor.WHITE;paint.textSize=34f;canvas.drawText(title,24f,56f,paint)
        paint.color=AColor.rgb(232,232,236);canvas.drawRect(24f,120f,616f,420f,paint)
        paint.color=AColor.rgb(60,60,70);paint.textSize=30f;canvas.drawText("Honda Pilot blind spot sensor",24f,480f,paint)
        paint.color=color;paint.textSize=44f;canvas.drawText("\$149.99",24f,550f,paint)
        File(EvidenceShots.folder(context),name).outputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,80,it)}
    }

    private fun capture(activity:Activity,out:File) {
        val view=activity.window.decorView
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        val done=CountDownLatch(1)
        PixelCopy.request(activity.window,bitmap,{done.countDown()},Handler(Looper.getMainLooper()))
        assertTrue("the screen copy timed out",done.await(8,TimeUnit.SECONDS))
        out.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }

    @Test fun drawTheNewChat() {
        fakePage("ev-aaaa1111.jpg","eBay",AColor.rgb(229,50,56))
        fakePage("ev-bbbb2222.jpg","RockAuto",AColor.rgb(30,90,160))
        fakePage("ev-cccc3333.jpg","Honda Parts",AColor.rgb(200,30,30))
        val sources=listOf(
            Source("https://www.ebay.com/itm/1","Honda Pilot 2019-2022 blind spot sensor left OEM 36936-TG7-A23","ebay.com",shot="ev-aaaa1111.jpg"),
            Source("https://www.rockauto.com/x","Blind spot sensor, Honda Pilot, RockAuto","rockauto.com",shot="ev-bbbb2222.jpg"),
            Source("https://parts.honda.com/z","Genuine Honda parts catalog","parts.honda.com",shot="ev-cccc3333.jpg"),
            Source("https://example.com/p","A page that is still being photographed","example.com"))
        val text=("""# The used pair is the best value at ¤215.99

Both sensors fit the 2019 to 2022 Honda Pilot, and the used OEM pair costs about ¤60 less than new aftermarket. I checked eBay and two parts sites.

> Check the connector in the photos before you buy. Fitment for the 2018 isn't confirmed.

## The options

```rows
Driver / left, 36936-TG7-A23 | Used OEM, 30-day returns | ¤149.99 | https://www.ebay.com/itm/1
Passenger / right, 36931-TG7-A23 | Used OEM, ¤6 shipping | ¤59.99 | https://www.ebay.com/itm/2
Honda parts counter | Ask about a new pair | | tel:+17135550100
```

## Price check

| Source | Left | Right |
|---|---|---|
| eBay | ¤149.99 | ¤59.99 |
| RockAuto | ¤212.00 | ¤198.00 |

- Both eBay listings have free returns
- New from Honda runs about ¤420 a pair

Here is some code too:

```
VIN 5FNYF6H05LB012345
```
""").replace('¤','$')
        val message=Message("m1","assistant",text,"t1",bot="Researcher",at=System.currentTimeMillis(),react="👍",evidence=Evidence.encode(sources),
            steps=org.json.JSONArray(listOf("Searched for “honda pilot blind spot sensor”","Read ebay.com","Read rockauto.com","Opened parts.honda.com","Worked out the numbers")).toString(),
            followups=listOf("Check Amazon too","Text this to Dave","Watch the price for me"))
        val chat=ChatActions(link={},copy={},share={},ask={},openSource={},evidence={name->EvidenceShots.load(context,name)})
        val scroll=ScrollState(0)
        val outDir=context.getExternalFilesDir(null)!!
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        var shown:Activity?=null
        scenario.onActivity {activity->
            shown=activity
            val view=ComposeView(activity)
            view.setContent {
                AlphaTheme {
                    CompositionLocalProvider(LocalChat provides chat) {
                        Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(scroll).padding(horizontal=14.dp,vertical=14.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                            UserMessage("Find me the cheapest blind spot sensor pair for a 2019 Honda Pilot",""){}
                            AssistantMessage("Researcher",System.currentTimeMillis(),true,message,true,null,{}) {}
                            LiveWork("Researcher","Reading the web",listOf("Searched for “brake pads for a 2018 Camry”","Read autozone.com","Read rockauto.com","Opened ebay.com"),sources.take(3),{},{})
                            ThinkingDots("Researcher") {}
                            AssistantMessage("Alpha",System.currentTimeMillis(),true,Message("m2","assistant","Sure thing, I'll text you back as soon as it's ready.","t2"),false,null,{}) {}
                        }
                    }
                }
            }
            activity.setContentView(view)
        }
        Thread.sleep(3500)
        // The screen copy is answered on the main thread, so it is asked for and waited on from here, not from inside onActivity.
        capture(shown!!,File(outDir,"chat-look-1.png"))
        for(step in 2..4) {
            scenario.onActivity {runBlocking {scroll.scrollTo(scroll.value+1500)}}
            Thread.sleep(900)
            capture(shown!!,File(outDir,"chat-look-$step.png"))
        }
        scenario.close()
        assertTrue(File(outDir,"chat-look-1.png").length()>10_000)
    }
}
