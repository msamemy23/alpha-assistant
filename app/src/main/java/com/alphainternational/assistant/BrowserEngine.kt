package com.alphainternational.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

@SuppressLint("SetJavaScriptEnabled")
class BrowserEngine(context:Context,private val redactor:(String)->String,private val changed:(String,String)->Unit,private val notice:(String)->Unit={},
                    private val gone:(BrowserEngine)->Unit={}) {
    /** True once Android took the page's engine away (out of memory). This browser can't be used any more; the app makes a new one. */
    @Volatile var dead=false; private set
    val view:WebView=KeepRunningWebView(context)
    private val reader=context.assets.open("browser-reader.js").bufferedReader().use {it.readText()}
    private var loaded:CompletableDeferred<Unit>?=null
    var currentUrl=""; private set
    /** HTTPS-First: an http:// page being opened as https:// instead; answered once the secure page commits. */
    private class Upgrade(val from:String,val to:String) {var answered=false}
    private var upgrade:Upgrade?=null
    /** When each http:// address was last tried securely, so a site that bounces back to http:// is refused, not looped. */
    private val upgradedAt=mutableMapOf<String,Long>()
    /** The secure address that was just refused, and the error screen showing for it (Alpha goes back to the page before). */
    private var refusedTo:String?=null
    private var errorPage:String?=null
    private var returning=false
    /** Why the latest http:// page wasn't opened, for the next tap result or read (open() reports it as its error). */
    private var refused:String?=null
    /**
     * The page that sent itself to a refused http:// address (or that Alpha went back to after one). Its own further jumps to
     * http:// are blocked quietly, so it can't loop; the bot and owner were already told. A tap or a new page ends this.
     */
    private var quietPage:String?=null
    private var skipping:String?=null
    private val upgradeTimeout=Runnable {upgrade?.takeIf {!it.answered}?.let {view.stopLoading();refuse(it.from,HttpsFirst.TOO_SLOW)}}
    private fun answered() {upgrade?.let {it.answered=true;view.removeCallbacks(upgradeTimeout)}}
    private fun site(url:String)=runCatching {android.net.Uri.parse(url).host.orEmpty().lowercase().removePrefix("www.")}.getOrDefault("")
    /** Prefix of the element labels from the latest page read; actions only find elements from that read. */
    private var readNonce="none"
    /** What the page complained about lately (console errors, failed requests), host and path only, for working out why a sign-in does not work. Never page text or addresses with queries. */
    val problems=java.util.concurrent.ConcurrentLinkedDeque<String>()
    private fun problem(text:String) {
        // Long unbroken strings (tokens, ids) are blanked before anything is kept.
        val clean=text.replace(Regex("[A-Za-z0-9_\\-.=+/]{24,}"),"…").take(200)
        problems+=clean;while(problems.size>40) problems.pollFirst()
        trace("problem "+clean)
    }
    private fun short(url:String)=runCatching {android.net.Uri.parse(url).let {it.host.orEmpty()+"/"+it.pathSegments.firstOrNull().orEmpty().take(16)}}.getOrDefault("")
    /** The page's own window for a sign-in pop-up ("Sign in with Google"), while the sign-in screen is showing; null otherwise. */
    var popup:WebView?=null; private set
    var onPopup:((WebView?)->Unit)?=null
    private var popupsOn=false
    /** Pop-ups are only given a window while the sign-in screen can show one. Otherwise a link that wants a new window opens in the page itself, as before. */
    fun allowPopups(on:Boolean) {
        popupsOn=on;view.settings.setSupportMultipleWindows(on)
        if(!on) closePopup()
    }
    fun closePopup() {
        val window=popup?:return
        popup=null;onPopup?.invoke(null)
        (window.parent as? android.view.ViewGroup)?.removeView(window)
        window.stopLoading();window.destroy()
    }
    private fun trace(text:String) {android.util.Log.i("AlphaBrowser",text)}
    /** Settings every window of this browser shares: presents as Chrome, keeps sign-ins, never loads unencrypted pages. */
    private fun configure(w:WebView) {
        w.settings.apply {
            javaScriptEnabled=true; domStorageEnabled=true; allowFileAccess=false; allowContentAccess=false
            useWideViewPort=true; loadWithOverviewMode=true
            mixedContentMode=android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture=true
        }
        w.settings.userAgentString=BrowserScripts.chromeAgent(w.settings.userAgentString)
        hideRequestedWith(w.settings)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w,true)
    }
    /** The window a page opens for itself (Google's and Microsoft's sign-in boxes): same browser, same cookies, https only, shown by the sign-in screen. */
    private fun openPopup(context:Context,resultMsg:android.os.Message):Boolean {
        val child=KeepRunningWebView(context)
        configure(child)
        child.webViewClient=object:WebViewClient() {
            override fun shouldOverrideUrlLoading(v:WebView,r:WebResourceRequest):Boolean = !r.url.scheme.equals("https",true)
            override fun onPageFinished(v:WebView,url:String) {CookieManager.getInstance().flush();trace("popup page "+short(url))}
            override fun onReceivedHttpError(v:WebView,r:WebResourceRequest,e:android.webkit.WebResourceResponse) {
                if(e.statusCode>=400) problem("popup http "+e.statusCode+" "+short(r.url.toString()))
            }
            override fun onReceivedSslError(v:WebView,handler:android.webkit.SslErrorHandler,error:android.net.http.SslError) {handler.cancel()}
        }
        child.webChromeClient=object:android.webkit.WebChromeClient() {
            override fun onCloseWindow(window:WebView) {window.post {if(popup===window) closePopup()}}
            override fun onJsAlert(v:WebView,url:String?,message:String?,result:android.webkit.JsResult):Boolean {result.cancel();return true}
            override fun onJsConfirm(v:WebView,url:String?,message:String?,result:android.webkit.JsResult):Boolean {result.cancel();return true}
            override fun onJsPrompt(v:WebView,url:String?,message:String?,default:String?,result:android.webkit.JsPromptResult):Boolean {result.cancel();return true}
        }
        closePopup()
        popup=child
        (resultMsg.obj as android.webkit.WebView.WebViewTransport).webView=child
        resultMsg.sendToTarget()
        onPopup?.invoke(child)
        trace("popup opened")
        return true
    }
    /** Teach mode: records pages, taps and which fields were filled (never what was typed) while the owner demonstrates a task. */
    var teaching=false; private set
    private var teachKey=""
    val taught=mutableListOf<String>()
    var onTaught:(()->Unit)?=null
    fun startTeaching() {
        teaching=true;teachKey=randomUrl(12);taught.clear()
        if(currentUrl.startsWith("https://")) {note("Started on "+view.title.orEmpty().take(80)+" ("+TeachSteps.place(currentUrl)+")");attachRecorder()}
    }
    /** Sends no X-Requested-With header (it would name this app). The feature constant is flagged restricted by lint but is the documented way to ask. */
    @android.annotation.SuppressLint("RestrictedApi")
    private fun hideRequestedWith(settings:android.webkit.WebSettings) {
        if(androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST))
            androidx.webkit.WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings,emptySet())
    }
    internal fun attachRecorder() {if(teaching) view.evaluateJavascript(TeachSteps.recorder(teachKey),null)}
    fun stopTeaching():List<String> {teaching=false;teachKey="";return taught.toList()}
    private fun note(step:String) {if(taught.size<80 && taught.lastOrNull()!=step) {taught+=redactor(step).take(160);onTaught?.invoke()}}
    init {
        // Full-size from the start (see BrowserSurface): pages must never see a zero-height viewport.
        view.layoutParams=android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        view.setBackgroundColor(android.graphics.Color.rgb(16,20,27))
        // Present as regular mobile Chrome. Sites such as Google Messages for web draw a blank page, and Google sign-in refuses,
        // when a page says it is an in-app browser ("; wv", "Version/4.0") or sends the app's package in X-Requested-With.
        // Sign-in pages (Google, Microsoft) keep the session in cookies shared across their own sites, as Chrome allows.
        configure(view)
        view.settings.setSupportMultipleWindows(false)
        CookieManager.getInstance().setAcceptCookie(true)
        view.webChromeClient=object:android.webkit.WebChromeClient() {
            // "Sign in with Google" opens its own small window and hands the result back to the page: only while the sign-in screen can show it.
            override fun onCreateWindow(v:WebView,isDialog:Boolean,userGesture:Boolean,resultMsg:android.os.Message):Boolean =
                popupsOn && userGesture && openPopup(v.context,resultMsg)
            override fun onCloseWindow(window:WebView) {}
            // The recorder reports through console messages tagged with a per-session key; nothing else is accepted.
            override fun onConsoleMessage(message:android.webkit.ConsoleMessage):Boolean {
                val text=message.message().orEmpty();val prefix="ALPHA_TEACH_"+teachKey+":"
                if(message.messageLevel()==android.webkit.ConsoleMessage.MessageLevel.ERROR || message.messageLevel()==android.webkit.ConsoleMessage.MessageLevel.WARNING)
                    problem("console "+message.messageLevel().name.lowercase()+": "+text.replace(Regex("(?i)(token|key|secret|password|code)=[^&\\s]+"),"$1=…"))
                if(teaching && teachKey.isNotBlank() && text.startsWith(prefix)) {note(text.removePrefix(prefix));return true}
                return false
            }
            // Page pop-ups stay suppressed, as before this client existed (the view has no Activity to show them in).
            override fun onJsAlert(v:WebView,url:String?,message:String?,result:android.webkit.JsResult):Boolean {result.cancel();return true}
            override fun onJsConfirm(v:WebView,url:String?,message:String?,result:android.webkit.JsResult):Boolean {result.cancel();return true}
            override fun onJsPrompt(v:WebView,url:String?,message:String?,default:String?,result:android.webkit.JsPromptResult):Boolean {result.cancel();return true}
        }
        view.webViewClient=object:WebViewClient() {
            override fun shouldOverrideUrlLoading(v:WebView,r:WebResourceRequest):Boolean {
                if(r.isForMainFrame && r.hasGesture() && !r.isRedirect) quietPage=null
                if(r.url.scheme.equals("https",true)) {
                    // The secure version answered with a redirect to another secure page: it works.
                    if(r.isForMainFrame && r.isRedirect) answered()
                    return false
                }
                // HTTPS-First, like Chrome: an http:// link or redirect opens the same page over https:// instead. Nothing loads unencrypted.
                if(r.isForMainFrame && r.url.scheme.equals("http",true)) {
                    // A page that already led to a refusal jumps to http:// again by itself: blocked without asking again. If Alpha was just
                    // going back to it, it's a redirect page left half loaded, so go back past it, as Chrome's back button skips such pages.
                    if(upgrade==null && !r.isRedirect && !r.hasGesture() && (returning || (quietPage!=null && v.url==quietPage))) {
                        if(returning && v.canGoBack()) {skipping=v.url;v.goBack()}
                        return true
                    }
                    if(!r.isRedirect && r.hasGesture()) upgradedAt.clear()
                    upgrade(r.url.toString())
                }
                return true
            }
            // A page of the secure site committed: it answered, so it gets as long as it needs to finish.
            override fun doUpdateVisitedHistory(v:WebView,url:String,isReload:Boolean) {
                upgrade?.let {if(site(url)==site(it.to)) answered()}
                if(!returning && url!=quietPage) quietPage=null
            }
            override fun onPageFinished(v:WebView,url:String) {
                // The error screen of a refused page is never treated as a page: go back to where the owner or bot was.
                if(url==errorPage) {errorPage=null;returning=v.canGoBack();if(returning) v.goBack();return}
                // Unencrypted pages never load (the app blocks cleartext), so an http:// address here is only an error screen.
                if(url.startsWith("http://",true)) return
                // A redirect that was cancelled for HTTPS-First also reports "finished"; nothing has loaded until the secure version answers.
                if(upgrade?.answered==false) return
                if(returning) {if(url==skipping) return;returning=false;skipping=null;quietPage=url}
                upgrade=null
                currentUrl=url; CookieManager.getInstance().flush(); changed(url,v.title.orEmpty()); loaded?.complete(Unit); loaded=null
                trace("page "+short(url))
                if(teaching && url.startsWith("https://")) {
                    note("Opened "+v.title.orEmpty().take(80)+" ("+TeachSteps.place(url)+")")
                    v.evaluateJavascript(TeachSteps.recorder(teachKey),null)
                }
            }
            override fun onReceivedHttpError(v:WebView,r:WebResourceRequest,e:android.webkit.WebResourceResponse) {
                if(e.statusCode>=400) problem("http "+e.statusCode+" "+short(r.url.toString()))
            }
            override fun onReceivedError(v:WebView,r:WebResourceRequest,e:android.webkit.WebResourceError) {
                if(!r.isForMainFrame) {problem("failed "+e.errorCode+" "+e.description+" "+short(r.url.toString()));return}
                val url=r.url.toString();val tried=upgrade
                when {
                    // The secure version of an http:// page failed. (A name that doesn't exist fails either way; that's an ordinary error.)
                    tried!=null && site(url)==site(tried.to) && e.errorCode!=ERROR_HOST_LOOKUP -> {errorPage=url;refuse(tried.from,HttpsFirst.reason(e.errorCode))}
                    refusedTo?.let {site(url)==site(it)}==true -> errorPage=url
                    // An http:// load that got past HTTPS-First was blocked before anything was sent: open it securely, or refuse a form post.
                    r.url.scheme.equals("http",true) ->
                        if(r.method.equals("GET",true) && url !in upgradedAt) v.post {upgrade(url)} else {errorPage=url;refuse(url,HttpsFirst.FORM)}
                    else -> {
                        if(tried!=null) {v.removeCallbacks(upgradeTimeout);upgrade=null}
                        loaded?.completeExceptionally(IllegalStateException("The website could not load: "+e.description)); loaded=null
                    }
                }
            }
            // Android can take the page's engine back when memory runs short (a game in the foreground, say). Without this the whole app is closed
            // with it. The bot hears what happened and the app makes a fresh browser for the next page.
            override fun onRenderProcessGone(v:WebView,detail:android.webkit.RenderProcessGoneDetail):Boolean {
                dead=true
                loaded?.completeExceptionally(IllegalStateException("The browser ran out of memory and restarted. Open the page again."));loaded=null
                v.post {gone(this@BrowserEngine)}
                return true
            }
            // Certificate problems are never clicked through. On the secure version of an http:// page, the bot and owner hear why.
            override fun onReceivedSslError(v:WebView,handler:android.webkit.SslErrorHandler,error:android.net.http.SslError) {
                handler.cancel()
                val tried=upgrade
                if(tried!=null && site(error.url)==site(tried.to)) refuse(tried.from,HttpsFirst.SECURITY)
            }
        }
    }
    /** Opens the https:// version of an http:// address. A site that sends it straight back to http:// is refused, not looped. */
    private fun upgrade(http:String) {
        val now=android.os.SystemClock.elapsedRealtime()
        upgradedAt.values.removeAll {now-it>30_000}
        view.removeCallbacks(upgradeTimeout);refusedTo=null;errorPage=null
        val secure=runCatching {HttpsFirst.upgrade(http)}.getOrNull()
        upgrade=Upgrade(http,secure.orEmpty())
        when {
            secure==null->refuse(http,HttpsFirst.NO_SECURE_ADDRESS)
            http in upgradedAt->refuse(http,HttpsFirst.BOUNCED)
            else-> {upgradedAt[http]=now;view.postDelayed(upgradeTimeout,15_000);view.loadUrl(secure)}
        }
    }
    /** Tells the owner (a notice) and the bot (open()'s error, or the next tap result or read) why an http:// page wasn't opened. */
    private fun refuse(http:String,why:String) {
        view.removeCallbacks(upgradeTimeout);refusedTo=upgrade?.to;upgrade=null
        // With no error screen to go back from, the page that made the jump is still showing: keep it from repeating it.
        if(errorPage==null) quietPage=currentUrl
        notice(HttpsFirst.notice(http,why))
        val message=HttpsFirst.refusal(http,why);val waiting=loaded;loaded=null
        if(waiting!=null) waiting.completeExceptionally(IllegalStateException(message)) else refused=message
    }
    private fun takeRefusal():String? {val why=refused;refused=null;return why}
    /** After a tap that started opening an http:// page securely: waits until the secure page answers or is refused. True if it waited. */
    private suspend fun awaitUpgrade():Boolean=withContext(Dispatchers.Main) {
        if(upgrade?.answered!=false) return@withContext false
        repeat(80) {if(upgrade?.answered!=false) return@withContext true;kotlinx.coroutines.delay(250)}
        true
    }
    /** A refused page's error screen is replaced by the page before it; reads and taps wait for that, a few seconds at most. */
    private suspend fun awaitReturn()=withContext(Dispatchers.Main) {
        repeat(40) {
            if(errorPage==null && !returning) {
                // Still on a refused page's error screen (its "finished" report never came): leave it now.
                if(refusedTo==null || view.url!=refusedTo || !view.canGoBack()) return@withContext
                refusedTo=null;returning=true;view.goBack()
            }
            kotlinx.coroutines.delay(250)
        }
        errorPage=null;returning=false;skipping=null
    }
    suspend fun open(url:String):String = withContext(Dispatchers.Main) {
        // HTTPS-First: an http:// address opens as https://, or fails with a plain explanation. Anything else must be https.
        val http=HttpsFirst.isHttp(url)
        if(!http) Rules.httpsUrl(url)
        loaded?.cancel(); val signal=CompletableDeferred<Unit>(); loaded=signal
        refused=null;refusedTo=null;errorPage=null;returning=false;quietPage=null;skipping=null;upgradedAt.clear()
        if(http) upgrade(url.trim()) else view.loadUrl(url)
        withTimeout(45_000) { signal.await() }
        // onPageFinished precedes many sites' asynchronous product rendering.
        var previous=-1;var stable=0
        repeat(12) {
            kotlinx.coroutines.delay(350)
            val length=decode(js("String(document.body ? document.body.innerText.length : 0)")).toIntOrNull()?:0
            stable=if(length==previous && length>100)stable+1 else 0;previous=length
            if(stable>=2)return@withContext read()
        }
        read()
    }
    private suspend fun js(script:String):String = withContext(Dispatchers.Main) {
        val signal=CompletableDeferred<String>()
        view.evaluateJavascript(script) { signal.complete(it?:"null") }
        withTimeout(10_000) { signal.await() }
    }
    private fun decode(raw:String):String = runCatching { org.json.JSONArray("["+raw+"]").getString(0) }.getOrDefault(raw)
    /** Runs a short read-only script in the page that is open now and returns what it answered (used to check a sign-in really took). */
    suspend fun probe(script:String):String = runCatching {decode(js(script))}.getOrDefault("")
    suspend fun read(offset:Int=0):String {
        awaitReturn()
        require(currentUrl.startsWith("https://")) { "Open a website on the Computer first." }
        require(offset in 0..160000) {"Page offset is outside the reader's limit."}
        readNonce=randomUrl(6).filter {it.isLetterOrDigit()}.ifBlank {"r"}
        val script=reader.replace("__OFFSET__",offset.toString()).replace("__NONCE__",readNonce)
        val page=redactor(decode(js(script)))
        // An http:// page refused after the last tap had finished is reported with the page the bot is still on.
        val why=takeRefusal()?:return page
        return runCatching {JSONObject(page).put("notice",redactor(why)).toString()}.getOrDefault(page)
    }
    /** Scrolls whatever scrolls on screen: the page, or the panel a web app scrolls inside (chats, inboxes, feeds). */
    suspend fun scroll(direction:String):String=withContext(Dispatchers.Main) {
        require(direction in setOf("up","down")) {"Choose up or down."}
        val moved=decode(js(BrowserScripts.scroll(if(direction=="down") 1 else -1)))
        kotlinx.coroutines.delay(700)
        val page=JSONObject(read())
        page.put("scroll",if(moved=="end") "Already at the "+(if(direction=="down") "bottom" else "top")+" of this page." else "Scrolled "+direction+".").toString()
    }
    fun back():Boolean {if(!view.canGoBack()) return false;view.goBack();return true}
    fun reload() {view.reload()}
    suspend fun describe(id:String):JSONObject {
        require(id.matches(Regex("[0-9]{1,3}"))) {"Invalid browser element."}
        val script="(function(){var e=document.querySelector('[data-alpha-id=\""+readNonce+"-"+id+"\"]');if(!e)throw Error('Element changed. Read the page again.');return JSON.stringify({page:location.href,kind:e.tagName,type:e.type||'',label:(e.getAttribute('aria-label')||e.innerText||e.placeholder||e.name||'').slice(0,120),href:e.tagName==='A'?e.href:'',action:e.form?e.form.action:''});})()"
        return JSONObject(redactor(decode(js(script))))
    }
    suspend fun click(id:String,expected:JSONObject?=null):String {
        require(id.matches(Regex("[0-9]{1,3}"))) { "Invalid browser element." }
        val guard=if(expected==null) "" else "var t="+expected.toString()+";var label=(e.getAttribute('aria-label')||e.innerText||e.placeholder||e.name||'').slice(0,120);if(location.href!==t.page||e.tagName!==t.kind||(e.type||'')!==t.type||label!==t.label||(e.tagName==='A'?e.href:'')!==t.href||(e.form?e.form.action:'')!==t.action)return 'Blocked: the page or selected element changed after review. Read the page and request a new approval.';"
        val find="var e=document.querySelector('[data-alpha-id=\""+readNonce+"-"+id+"\"]');if(!e)return 'Element changed. Read the page again.';"+guard
        // A real finger tap where the element is on screen: sites only open links, menus and new pages for real taps.
        refused=null
        val spot=decode(js("(function(){"+find+BrowserScripts.spot+"})()"))
        var point:JSONObject=runCatching {JSONObject(spot)}.getOrNull()?:return redactor(spot)
        // Busy pages (search results, feeds) still shift while they load; tap only once the target holds still.
        for(check in 1..4) {
            kotlinx.coroutines.delay(150)
            val again=runCatching {JSONObject(decode(js("(function(){"+find+BrowserScripts.spot+"})()")))}.getOrNull()?:break
            val held=Math.abs(again.getDouble("x")-point.getDouble("x"))<4 && Math.abs(again.getDouble("y")-point.getDouble("y"))<4
            point=again
            if(held) break
        }
        // A real finger tap needs the page on a screen that is showing. In the background (or with Alpha swiped away) the page is tapped by script instead.
        val result=if(point.optBoolean("tappable") && onScreen()) {
            touch(point.getDouble("x").toFloat(),point.getDouble("y").toFloat());"Clicked."
        } else decode(js("(function(){"+find+BrowserScripts.tap+"return 'Clicked.';})()"))
        return outcome(result)
    }
    /** Touch down and up on the browser itself, bypassing the guard that keeps the hidden browser from stealing your taps. */
    private suspend fun touch(x:Float,y:Float) {
        val start=android.os.SystemClock.uptimeMillis()
        fun event(action:Int,time:Long)=android.view.MotionEvent.obtain(start,time,action,x,y,0).apply {source=android.view.InputDevice.SOURCE_TOUCHSCREEN}
        event(android.view.MotionEvent.ACTION_DOWN,start).let {view.onTouchEvent(it);it.recycle()}
        kotlinx.coroutines.delay(70)
        event(android.view.MotionEvent.ACTION_UP,android.os.SystemClock.uptimeMillis()).let {view.onTouchEvent(it);it.recycle()}
    }
    /** Presses Enter in a field (search boxes, chat boxes); often submits or sends, so it is reviewed. */
    suspend fun pressEnter(id:String,expected:JSONObject?=null):String {
        require(id.matches(Regex("[0-9]{1,3}"))) { "Invalid browser element." }
        val guard=if(expected==null) "" else "var t="+expected.toString()+";if(location.href!==t.page||e.tagName!==t.kind)return 'Blocked: the page or field changed after review. Read the page and request a new approval.';"
        refused=null
        val result=decode(js("(function(){var e=document.querySelector('[data-alpha-id=\""+readNonce+"-"+id+"\"]');if(!e)return 'Element changed. Read the page again.';"+guard+BrowserScripts.enter+"})()"))
        return outcome(result)
    }
    /** Lets the page react (navigation or app rendering) before the next read. */
    private suspend fun settle() {
        kotlinx.coroutines.delay(600)
        repeat(10) {if(decode(js("document.readyState"))=="complete") return;kotlinx.coroutines.delay(300)}
    }
    /** After a tap or Enter: the page has reacted, or the http:// page it led to was refused and the result says why in plain words. */
    private suspend fun outcome(result:String):String {
        settle()
        if(awaitUpgrade()) settle()
        val why=takeRefusal()?:return redactor(result)
        awaitReturn()
        return redactor(why)
    }
    suspend fun fill(id:String,value:String,expected:JSONObject?=null):String {
        require(id.matches(Regex("[0-9]{1,3}"))) { "Invalid browser element." }
        val guard=if(expected==null) "" else "var t="+expected.toString()+";var label=(e.getAttribute('aria-label')||e.innerText||e.placeholder||e.name||'').slice(0,120);if(location.href!==t.page||e.tagName!==t.kind||(e.type||'')!==t.type||label!==t.label||(e.tagName==='A'?e.href:'')!==t.href||(e.form?e.form.action:'')!==t.action)return 'Blocked: the page or selected field changed after review. Read the page and request a new approval.';"
        val script="(function(){var e=document.querySelector('[data-alpha-id=\""+readNonce+"-"+id+"\"]');if(!e)return 'Element changed. Read the page again.';"+guard+
            "if(e.type==='password'||e.type==='hidden')return 'Use the login vault for password fields.';"+BrowserScripts.type(value)+"})()"
        val result=redactor(decode(js(script)))
        kotlinx.coroutines.delay(300)
        return result
    }
    suspend fun fillLogin(origin:String,username:String,password:String):String {
        require(Rules.origin(currentUrl)==origin) { "This login is saved for a different website." }
        val script="(function(){if(location.origin!=="+JSONObject.quote(origin)+")return 'Website changed. Login was not filled.';var p=document.querySelector('input[type=password]');var u=document.querySelector('input[autocomplete=username],input[type=email],input[name=username],input[name=login]');if(!p)return 'No password field found on this page.';if(p.form&&p.form.action&&new URL(p.form.action,location.href).origin!==location.origin)return 'Login form submits to a different website.';if(u){u.value="+JSONObject.quote(username)+";u.dispatchEvent(new Event('input',{bubbles:true}));}p.value="+JSONObject.quote(password)+";p.dispatchEvent(new Event('input',{bubbles:true}));return 'Login filled. Complete sign-in on the page.';})()"
        return decode(js(script))
    }
    /** What the pages complained about lately, host and first path word only (for working out why a sign-in did not take). */
    fun recentProblems():List<String> = problems.toList().takeLast(12)
    fun clearSession() {
        closePopup()
        view.stopLoading();currentUrl="";view.loadUrl("about:blank")
        view.clearHistory();view.clearCache(true);android.webkit.WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies(null);CookieManager.getInstance().flush()
    }
    fun destroy() { view.stopLoading(); view.destroy() }

    /** Whether the browser is on a screen the owner can see right now, so a real finger tap would reach it. */
    fun onScreen():Boolean=view.isAttachedToWindow && view.windowVisibility==android.view.View.VISIBLE && view.width>0 && view.height>0

    /**
     * Gives the page a phone-sized viewport of its own. A browser that no screen is holding (Alpha is in the background or was swiped away)
     * would otherwise be zero pixels tall, and pages built for a full screen (Gmail, Messages) draw nothing.
     */
    fun keepSized() {
        val screen=view.resources.displayMetrics
        val width=screen.widthPixels.coerceAtLeast(1);val height=screen.heightPixels.coerceAtLeast(1)
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(width,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(height,android.view.View.MeasureSpec.EXACTLY))
        view.layout(0,0,width,height)
    }
    /** A bot is about to use the browser: make sure the page is running. */
    fun wake() {view.onResume()}
    /** Nobody is using the browser and Alpha is in the background: let the page rest. */
    fun rest() {view.onPause()}
    init {
        // Whenever a screen lets go of the browser, it keeps its own viewport.
        view.addOnAttachStateChangeListener(object:android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v:android.view.View) {}
            override fun onViewDetachedFromWindow(v:android.view.View) {keepSized()}
        })
        keepSized()
    }
}

/**
 * A WebView that tells the page its window is showing even when Alpha's screen is in the background. Without this the page is treated as
 * hidden: it stops running scripts on schedule and stops answering taps, so a bot couldn't keep working once the owner switched apps.
 */
class KeepRunningWebView(context:Context):WebView(context) {
    override fun onWindowVisibilityChanged(visibility:Int) {super.onWindowVisibilityChanged(android.view.View.VISIBLE)}
}
