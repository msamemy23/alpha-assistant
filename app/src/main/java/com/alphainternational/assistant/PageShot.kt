package com.alphainternational.assistant

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Eyes for the bots. Shows one of the bot's own pages (a website, a game, a tool) in a hidden browser at phone or desktop size, scrolls it
 * the way a visitor would, and brings back pictures of what is on screen plus the page's own error messages and a few automatic design checks.
 * The bot looks at the result and fixes what's wrong before it tells the owner it's done.
 *
 * Where the page is drawn matters: a WebView only renders when it is in a real window. The first choice is a private virtual screen that belongs to
 * Alpha (a Presentation on a VirtualDisplay): nothing shows on the owner's screen, it works whether or not Alpha is open, and the picture is read
 * straight from the screen's own frames. If that isn't possible the page is put behind the app's own screen, then drawn in a hidden view.
 */
object PageShot {
    class Result(val picture:Bitmap?,val problem:String?,val errors:List<String>,val checks:JSONArray,val pageHeight:Int,
        /** The first screen on its own, 360 wide: a small picture of the page for the Library and the chat. */
        val thumb:Bitmap?=null)

    /** Phone (390 wide) or desktop (1280 wide) windows, in CSS pixels. */
    fun size(device:String)=if(device.equals("desktop",true)) 1280 to 800 else 390 to 844

    /** Runs the bundled design checks (25 of them: contrast, overflow, tap targets, alt text, fonts, invented claims, errors) and returns [{id,sev,problems}]. */
    private suspend fun runLint(context:Context,view:WebView):JSONArray {
        val lint=Studio.asset(context,"studio/lint.js")?:return JSONArray()
        evaluate(view,lint+";1")
        evaluate(view,"window.__lintOut=null;window.__lintRun().then(function(r){window.__lintOut=JSON.stringify(r)}).catch(function(e){window.__lintOut=JSON.stringify([{id:'lint',sev:'warn',problems:['the design checks crashed: '+e.message]}])});1")
        repeat(32) {
            delay(250)
            val out=evaluate(view,"window.__lintOut")
            if(out!="null") return runCatching {JSONArray(unquote(out))}.getOrDefault(JSONArray())
        }
        return JSONArray()
    }

    /** A place a page can be drawn, and how to read a picture of it. */
    internal class Stage(val name:String,val view:WebView,val grab:suspend ()->Bitmap,val close:()->Unit)

    /** A private virtual screen owned by Alpha: a window there renders for real, invisible to the owner, with or without the app on screen. */
    internal fun virtualStage(context:Context,width:Int,height:Int):Stage {
        val dpi=context.resources.displayMetrics.densityDpi
        val reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,3)
        val main=Handler(Looper.getMainLooper())
        val frames=java.util.concurrent.atomic.AtomicInteger()
        val latest=java.util.concurrent.atomic.AtomicReference<Bitmap?>()
        reader.setOnImageAvailableListener({r->
            val image=runCatching {r.acquireLatestImage()}.getOrNull()?:return@setOnImageAvailableListener
            try {
                val plane=image.planes[0];val rowPixels=plane.rowStride/plane.pixelStride
                val full=Bitmap.createBitmap(rowPixels,image.height,Bitmap.Config.ARGB_8888)
                full.copyPixelsFromBuffer(plane.buffer)
                val cropped=if(rowPixels==image.width) full else Bitmap.createBitmap(full,0,0,image.width,image.height).also {full.recycle()}
                latest.getAndSet(cropped)?.recycle();frames.incrementAndGet()
            } finally {image.close()}
        },main)
        val manager=context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        var display:VirtualDisplay?=manager.createVirtualDisplay("alpha-page-shot",width,height,dpi,reader.surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION)
        var presentation:Presentation?=null
        try {
            val shown=display?.display?:error("No private screen could be made.")
            presentation=Presentation(context,shown)
            val view=WebView(presentation.context)
            presentation.setContentView(view,FrameLayout.LayoutParams(width,height))
            presentation.show()
            return Stage("virtual screen",view,{
                // The newest frame the screen produced since the page last changed (waiting a moment for it).
                val before=frames.get()
                view.invalidate()
                var waited=0
                while(frames.get()==before && waited<1_500) {delay(60);waited+=60}
                val picture=latest.get()?:error("The private screen produced no picture.")
                picture.copy(Bitmap.Config.ARGB_8888,false)
            },{
                runCatching {presentation?.dismiss()};runCatching {display?.release()};runCatching {reader.close()};latest.get()?.recycle()
            })
        } catch(e:Exception) {
            runCatching {presentation?.dismiss()};runCatching {display?.release()};runCatching {reader.close()}
            throw e
        }
    }

    /** The page behind the app's own screen (on screen so it renders, covered so the owner never sees it), drawn into a bitmap. */
    private fun attachedStage(context:Context,host:ViewGroup,width:Int,height:Int):Stage {
        val view=WebView(context)
        view.layoutParams=FrameLayout.LayoutParams(width,height)
        host.addView(view,0)
        return Stage("behind the app",view,{drawView(view,width,height)},{runCatching {host.removeView(view)}})
    }

    /** Last resort: a laid-out view that is not in any window. */
    private fun hiddenStage(context:Context,width:Int,height:Int):Stage {
        val view=WebView(context)
        return Stage("hidden view",view,{drawView(view,width,height)},{})
    }

    private suspend fun drawView(view:WebView,width:Int,height:Int):Bitmap {
        val bmp=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        if(isBlank(bmp)) {
            // Some WebViews only paint into a software layer; ask for one and try again.
            view.setLayerType(View.LAYER_TYPE_SOFTWARE,null);delay(400)
            view.draw(Canvas(bmp))
        }
        return bmp
    }

    private suspend fun shoot(context:Context,stage:Stage,dir:File,page:String,html:String,device:String,tiles:Int,knownChecks:JSONArray?):Result {
        val (cssW,cssH)=size(device)
        val density=context.resources.displayMetrics.density
        val width=(cssW*density).toInt();val height=(cssH*density).toInt()
        val view=stage.view
        val errors=mutableListOf<String>()
        val loaded=CompletableDeferred<Unit>()
        view.settings.apply {
            javaScriptEnabled=true;domStorageEnabled=true;allowFileAccess=false;allowContentAccess=false
            setSupportMultipleWindows(false);mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort=true;loadWithOverviewMode=true;mediaPlaybackRequiresUserGesture=true
        }
        view.setBackgroundColor(Color.WHITE)
        view.webChromeClient=object:WebChromeClient() {
            override fun onConsoleMessage(m:ConsoleMessage):Boolean {
                if(m.messageLevel()==ConsoleMessage.MessageLevel.ERROR && errors.size<12)
                    errors+=m.message().take(220)+(if(m.lineNumber()>0) " (line "+m.lineNumber()+")" else "")
                return true
            }
        }
        view.webViewClient=object:WebViewClient() {
            override fun shouldOverrideUrlLoading(v:WebView,r:WebResourceRequest):Boolean=r.isForMainFrame && r.url.host!=SitePage.HOST
            override fun shouldInterceptRequest(v:WebView,r:WebResourceRequest):WebResourceResponse?=SitePage.serve(dir,r.url)
            override fun onPageFinished(v:WebView,url:String) {if(!loaded.isCompleted) loaded.complete(Unit)}
            override fun onReceivedError(v:WebView,r:WebResourceRequest,e:android.webkit.WebResourceError) {
                if(r.url.host==SitePage.HOST && errors.size<12) errors+="Missing file: "+(r.url.path.orEmpty().trimStart('/'))
            }
        }
        if(stage.name!="virtual screen") {
            view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
            view.layout(0,0,width,height)
        }
        // Records the page's own script errors from the very start, for the error check.
        if(androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT))
            Studio.asset(context,"studio/lint-preamble.js")?.let {androidx.webkit.WebViewCompat.addDocumentStartJavaScript(view,it,setOf("*"))}
        view.loadDataWithBaseURL("https://"+SitePage.HOST+"/"+page,html,"text/html","utf-8",null)
        withTimeout(25_000) {loaded.await()}
        delay(1_000)
        // Make scroll-triggered effects show, then walk down the page like a visitor.
        evaluate(view,"document.documentElement.classList.add('shot');(window.Motion&&Motion.shot&&Motion.shot());1")
        val total=(evaluate(view,"Math.max(document.documentElement.scrollHeight,document.body?document.body.scrollHeight:0)").toIntOrNull()?:cssH).coerceIn(cssH,20_000)
        val positions=run {
            val n=tiles.coerceIn(1,5);val maxY=(total-cssH).coerceAtLeast(0)
            if(n==1||maxY==0) listOf(0) else (0 until n).map {maxY*it/(n-1)}
        }
        // Walk the whole page once so lazy pictures and reveal animations fire.
        var y=0
        while(y<total) {evaluate(view,"window.scrollTo(0,$y);1");delay(160);y+=(cssH*0.8).toInt()}
        val checks=knownChecks?:runLint(context,view)
        val shots=mutableListOf<Bitmap>()
        for(pos in positions) {
            evaluate(view,"window.scrollTo(0,$pos);1");delay(450)
            shots+=runCatching {stage.grab()}.getOrElse {android.util.Log.w("AlphaShot","${stage.name}: ${it.javaClass.simpleName}: ${it.message}");Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)}
        }
        val blank=shots.all {isBlank(it)}
        android.util.Log.i("AlphaShot","${stage.name}: ${shots.size} picture(s), blank=$blank, page ${total}px, viewport ${width}x$height")
        if(blank) {shots.forEach {it.recycle()};return Result(null,"The page rendered blank (${stage.name}).",errors,checks,total)}
        val thumb=runCatching {shots.first().let {Bitmap.createScaledBitmap(it,360,(it.height*360/it.width.toFloat()).toInt().coerceAtLeast(1),true)}}.getOrNull()
        return Result(sheet(shots,device),null,errors,checks,total,thumb)
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun take(context:Context,dir:File,page:String,device:String,tiles:Int,attachTo:android.view.ViewGroup?):Result=withContext(Dispatchers.Main) {
        val (cssW,cssH)=size(device)
        val density=context.resources.displayMetrics.density
        val width=(cssW*density).toInt();val height=(cssH*density).toInt()
        val html=File(dir,Rules.textFileName(page)).takeIf {it.isFile}?.readText()?:return@withContext Result(null,"$page isn't in this bot's files.",emptyList(),JSONArray(),0)
        var checks:JSONArray?=null
        var last:Result?=null
        val makers=listOf<Pair<String,()->Stage>>(
            "virtual screen" to {virtualStage(context,width,height)},
            "behind the app" to {attachTo?.let {attachedStage(context,it,width,height)}?:error("The app isn't on screen.")},
            "hidden view" to {hiddenStage(context,width,height)})
        for((name,make) in makers) {
            val stage=try {make()} catch(e:Exception) {android.util.Log.w("AlphaShot","$name unavailable: ${e.javaClass.simpleName}: ${e.message}");continue}
            try {
                val r=shoot(context,stage,dir,page,html,device,tiles,checks)
                if(r.checks.length()>0) checks=r.checks
                if(r.picture!=null) return@withContext r
                last=r
            } catch(e:Exception) {
                // A page that merely timed out falls through to the next way of drawing it; a job that was cancelled (the owner left the screen) stops here.
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                android.util.Log.w("AlphaShot","$name failed: ${e.javaClass.simpleName}: ${e.message}")
                last=Result(null,"Couldn't open the page: "+(e.message?:e.javaClass.simpleName),emptyList(),checks?:JSONArray(),0)
            } finally {
                runCatching {stage.view.stopLoading();stage.view.destroy()}
                stage.close()
            }
        }
        last?.let {Result(null,"The page rendered blank in the hidden browser, so no picture could be taken. The page's own checks and errors are still reported.",it.errors,checks?:it.checks,it.pageHeight)}
            ?:Result(null,"No picture could be taken on this phone.",emptyList(),JSONArray(),0)
    }

    private suspend fun evaluate(view:WebView,script:String):String {
        val signal=CompletableDeferred<String>()
        view.evaluateJavascript(script) {signal.complete(it?:"null")}
        return withTimeout(8_000) {signal.await()}
    }
    /** evaluateJavascript hands back a JSON-encoded string; a returned JSON text arrives as a quoted, escaped string. */
    private fun unquote(raw:String):String = runCatching {JSONArray("["+raw+"]").getString(0)}.getOrDefault(raw)

    /** True when the picture is one flat colour (nothing was drawn). */
    fun isBlank(bitmap:Bitmap):Boolean {
        val first=bitmap.getPixel(bitmap.width/2,bitmap.height/2)
        var different=0
        val stepX=(bitmap.width/24).coerceAtLeast(1);val stepY=(bitmap.height/24).coerceAtLeast(1)
        var y=0
        while(y<bitmap.height) {var x=0;while(x<bitmap.width) {if(bitmap.getPixel(x,y)!=first) different++;x+=stepX};y+=stepY}
        return different<6
    }

    /** The screenshots side by side, at a size a model can read, with the scroll position labelled. */
    fun sheet(shots:List<Bitmap>,device:String):Bitmap {
        val tileW=if(device.equals("desktop",true)) 760 else 360
        val scaled=shots.map {Bitmap.createScaledBitmap(it,tileW,(it.height*tileW/it.width.toFloat()).toInt(),true).also {_->it.recycle()}}
        val gap=10;val pad=10
        val out=Bitmap.createBitmap(pad*2+scaled.sumOf {it.width}+gap*(scaled.size-1),pad*2+scaled.maxOf {it.height}+22,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(out);canvas.drawColor(Color.rgb(32,32,36))
        val label=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(200,200,205);textSize=15f}
        var x=pad
        scaled.forEachIndexed {i,b->
            canvas.drawText("screen ${i+1} of ${scaled.size}",x.toFloat(),(pad+14).toFloat(),label)
            canvas.drawBitmap(b,null,Rect(x,pad+22,x+b.width,pad+22+b.height),null)
            x+=b.width+gap;b.recycle()
        }
        return out
    }

    /**
     * The lint results as short plain lines for the bot: problems that must be fixed first ("fix: …"), then advice ("better: …").
     * Each line says which check found it. Passing checks are left out, so an empty list means the page passed all of them.
     */
    fun summary(checks:JSONArray):JSONArray {
        val must=mutableListOf<String>();val should=mutableListOf<String>()
        for(i in 0 until checks.length()) {
            val c=checks.optJSONObject(i)?:continue
            val problems=c.optJSONArray("problems")?:continue
            for(j in 0 until problems.length()) {
                val text=problems.optString(j).trim();if(text.isBlank()) continue
                val advisory=c.optString("sev")=="warn" || text.startsWith("WARN")
                (if(advisory) should else must)+=(if(advisory) "better: " else "fix: ")+c.optString("id")+": "+text.removePrefix("WARN").trim(' ',':').take(220)
            }
        }
        return JSONArray((must+should).take(16))
    }
}
