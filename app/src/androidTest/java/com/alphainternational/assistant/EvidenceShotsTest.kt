package com.alphainternational.assistant

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** A picture of a real web page is taken in the hidden browser, saved small, and never taken for addresses on the phone or the owner's network. */
class EvidenceShotsTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun aRealPageGetsASavedPicture() {
        val name=runBlocking {EvidenceShots.capture(context,"https://example.com/")}
        assertNotNull("no picture was taken of example.com",name)
        val file=EvidenceShots.file(context,name!!)
        assertNotNull(file)
        assertTrue("picture too small: ${file!!.length()}",file.length()>3_000)
        val bounds=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true}
        android.graphics.BitmapFactory.decodeFile(file.path,bounds)
        assertEquals(640,bounds.outWidth)
        assertTrue(bounds.outHeight>400)
        // Copy it out so the picture can be looked at.
        file.copyTo(File(context.getExternalFilesDir(null),"evidence-example.jpg"),overwrite=true)
        // The same page again in the same window is served from the saved picture.
        assertEquals(name,runBlocking {EvidenceShots.capture(context,"https://example.com/")})
    }

    @Test fun addressesOnThePhoneOrTheLocalNetworkAreRefused() {
        listOf("http://example.com/","https://localhost/","https://127.0.0.1/","https://192.168.0.1/","file:///data/data/x").forEach {url->
            assertNull(url,runBlocking {EvidenceShots.capture(context,url)})
        }
    }

    @Test fun aSavedPictureLoadsForTheChat() {
        val folder=EvidenceShots.folder(context)
        val bitmap=android.graphics.Bitmap.createBitmap(64,100,android.graphics.Bitmap.Config.ARGB_8888).also {it.eraseColor(android.graphics.Color.RED)}
        File(folder,"ev-deadbeef1.jpg").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,80,it)}
        assertNotNull(EvidenceShots.load(context,"ev-deadbeef1.jpg"))
        // Only our own picture names are ever opened.
        assertNull(EvidenceShots.load(context,"../shared_prefs/x.xml"))
        assertNull(EvidenceShots.load(context,"ev-ffff.png"))
    }
}
