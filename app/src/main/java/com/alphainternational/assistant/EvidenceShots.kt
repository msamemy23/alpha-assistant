package com.alphainternational.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.URI

/**
 * Pictures of the pages a bot looked at, so an answer can show what it found. Each page is drawn once in a private hidden browser (a virtual screen that
 * belongs to Alpha, the same way the bots check their own websites), photographed at phone size, shrunk, and saved in the app's own evidence folder.
 * The pictures stay on the phone. They are taken one at a time, in the background, after the bot has already read the page.
 */
object EvidenceShots {
    private val gate=Mutex()
    private const val WIDTH_CSS=390
    private const val HEIGHT_CSS=640
    private const val OUT_WIDTH=640
    private const val KEEP_FILES=240
    private const val KEEP_BYTES=80L*1024*1024

    fun folder(context:Context):File = File(context.filesDir,"evidence").also {it.mkdirs()}

    /** The saved picture with this file name, or null for anything that isn't one of ours. */
    fun file(context:Context,name:String):File? {
        if(!name.matches(Regex("ev-[0-9a-f]{8,16}\\.jpg"))) return null
        return File(folder(context),name).takeIf {it.isFile}
    }
    private val cache=android.util.LruCache<String,androidx.compose.ui.graphics.ImageBitmap>(32)
    /** A saved picture ready to draw (kept in memory for the few most recent ones). Call off the main thread. */
    fun load(context:Context,name:String):androidx.compose.ui.graphics.ImageBitmap? {
        cache.get(name)?.let {return it}
        val saved=file(context,name)?:return null
        val bitmap=android.graphics.BitmapFactory.decodeFile(saved.path)?:return null
        return bitmap.asImageBitmap().also {cache.put(name,it)}
    }
    /** Saves a picture of [url] and returns its file name, or null when the page wouldn't draw (it blocks automated viewing, is a file, or took too long). */
    suspend fun capture(context:Context,url:String):String? = gate.withLock {
        if(!Evidence.shootable(url)) return@withLock null
        val name=Evidence.shotName(url)
        val out=File(folder(context),name)
        if(out.isFile && out.length()>2_000) return@withLock name
        val addressOk=withContext(Dispatchers.IO) {runCatching {WebReader.publicHost(URI(url).host);true}.getOrDefault(false)}
        if(!addressOk) return@withLock null
        val picture=runCatching {withContext(Dispatchers.Main) {draw(context,url)}}.onFailure {
            android.util.Log.w("AlphaEvidence","${Evidence.domain(url)}: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()?:return@withLock null
        withContext(Dispatchers.IO) {
            try {
                val height=(picture.height*OUT_WIDTH/picture.width.toFloat()).toInt().coerceAtLeast(1)
                val small=Bitmap.createScaledBitmap(picture,OUT_WIDTH,height,true)
                out.outputStream().use {small.compress(Bitmap.CompressFormat.JPEG,72,it)}
                if(small!==picture) small.recycle()
                tidy(context)
                name
            } catch(e:Exception) {null} finally {picture.recycle()}
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun draw(context:Context,url:String):Bitmap? {
        val density=context.resources.displayMetrics.density
        val width=(WIDTH_CSS*density).toInt();val height=(HEIGHT_CSS*density).toInt()
        val stage=PageShot.virtualStage(context,width,height)
        val view=stage.view
        try {
            // A private profile when this WebView has them, so a screenshot never carries the owner's sign-ins.
            runCatching {
                if(androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.MULTI_PROFILE))
                    androidx.webkit.WebViewCompat.setProfile(view,"evidence")
            }
            view.settings.apply {
                javaScriptEnabled=true;domStorageEnabled=true;allowFileAccess=false;allowContentAccess=false
                setSupportMultipleWindows(false);mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
                useWideViewPort=true;loadWithOverviewMode=true;mediaPlaybackRequiresUserGesture=true;javaScriptCanOpenWindowsAutomatically=false
            }
            runCatching {CookieManager.getInstance().setAcceptThirdPartyCookies(view,false)}
            view.setBackgroundColor(Color.WHITE)
            val loaded=CompletableDeferred<Unit>()
            view.webViewClient=object:WebViewClient() {
                // Pages may send the reader on to their real address; anything that isn't a public https page stops here.
                override fun shouldOverrideUrlLoading(v:WebView,r:WebResourceRequest):Boolean=r.isForMainFrame && !Evidence.shootable(r.url.toString())
                override fun onPageFinished(v:WebView,address:String) {if(!loaded.isCompleted) loaded.complete(Unit)}
                override fun onReceivedError(v:WebView,r:WebResourceRequest,e:android.webkit.WebResourceError) {
                    if(r.isForMainFrame && !loaded.isCompleted) loaded.completeExceptionally(IllegalStateException("the page did not load"))
                }
            }
            view.loadUrl(url)
            withTimeout(22_000) {loaded.await()}
            // Pictures and late-rendering parts of the page get a moment, then the first screen is photographed.
            delay(1_800)
            val first=stage.grab()
            if(!PageShot.isBlank(first)) return first
            first.recycle()
            delay(1_800)
            val second=stage.grab()
            return if(PageShot.isBlank(second)) {second.recycle();null} else second
        } finally {
            runCatching {view.stopLoading();view.destroy()}
            stage.close()
        }
    }

    /** Keeps the folder small: the oldest pictures go first. */
    fun tidy(context:Context) {
        val files=folder(context).listFiles()?.filter {it.isFile}?.sortedBy {it.lastModified()}?:return
        var total=files.sumOf {it.length()};var count=files.size
        for(f in files) {
            if(count<=KEEP_FILES && total<=KEEP_BYTES) break
            total-=f.length();count--;f.delete()
        }
    }
}
