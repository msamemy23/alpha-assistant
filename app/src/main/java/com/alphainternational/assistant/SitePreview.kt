package com.alphainternational.assistant

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrintManager
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.ByteArrayInputStream
import java.io.File

/**
 * A bot's website file shown as the real page, full screen, the way a customer's phone would show it. The page runs in an isolated
 * WebView with no way into Alpha's data (no file access). Pictures and pages saved next to it in the bot's files load too.
 * Phone, text and email links open the phone's own apps; web links open the browser.
 *
 * An app a bot made needs a few things a plain WebView lacks, so the preview adds them, each one handled by the phone's own screens and
 * approved by the owner each time: choosing or taking a photo, saving a file (export, backup, a report), printing (to PDF or a printer) and sharing.
 */
object SitePage {
    const val HOST="preview.alpha.invalid"
    /** The bot's own files for a page shown at https://preview.alpha.invalid/<name>: pictures, styles, scripts, fonts and other pages next to it. */
    fun serve(dir:File,uri:android.net.Uri):WebResourceResponse? {
        if(uri.host!=HOST) return null
        val path=uri.path.orEmpty().trimStart('/')
        val target=runCatching {File(dir,Rules.fileName(path))}.getOrNull()?.takeIf {it.isFile}
        return if(target==null) WebResourceResponse("text/plain","utf-8",404,"Not found",emptyMap(),ByteArrayInputStream(ByteArray(0)))
        else WebResourceResponse(mime(target.name),"utf-8",target.inputStream())
    }
    fun mime(name:String)=when(name.substringAfterLast('.').lowercase()) {
        "html"->"text/html";"png"->"image/png";"jpg","jpeg"->"image/jpeg";"webp"->"image/webp";"gif"->"image/gif"
        "css"->"text/css";"js"->"application/javascript";"json","webmanifest"->"application/json";"svg"->"image/svg+xml";"xml"->"application/xml";"woff2"->"font/woff2";"mp3"->"audio/mpeg";"wav"->"audio/wav";"ogg"->"audio/ogg";"m4a"->"audio/mp4";"mp4"->"video/mp4";"webm"->"video/webm";"txt","md","csv"->"text/plain";else->"application/octet-stream"
    }

    /** Biggest file a page may hand to the save screen (an export or a report; photos in a backup can make it large). */
    const val MAX_SAVE_BYTES=24_000_000

    /**
     * Makes a page's own Save, Print and Share work in a plain WebView: a download made from a Blob or data address is handed to the phone's "save as" screen,
     * window.print opens the phone's print screen, and navigator.share opens the share sheet. Nothing here reads anything: it only passes the page's own file on.
     */
    val SHIM="""
        (function(){
          if(window.__alphaHost || !window.AlphaHost) return; window.__alphaHost=1;
          var blobs={}, make=URL.createObjectURL;
          URL.createObjectURL=function(b){ var u=make.call(URL,b); blobs[u]=b; return u; };
          function send(name,blob){
            if(blob.size>$MAX_SAVE_BYTES){ alert('That file is too big to save from here.'); return; }
            var r=new FileReader();
            r.onload=function(){ var s=String(r.result); AlphaHost.saveFile(name||'download', blob.type||'application/octet-stream', s.substring(s.indexOf(',')+1)); };
            r.readAsDataURL(blob);
          }
          function grab(a){
            var n=a.getAttribute('download'); if(n===null) return false;
            var h=a.href||'';
            if(blobs[h]){ send(n,blobs[h]); return true; }
            if(h.indexOf('data:')===0){ fetch(h).then(function(x){return x.blob();}).then(function(b){send(n,b);}); return true; }
            if(h.indexOf('${"https://"+HOST}')===0){ fetch(h).then(function(x){return x.blob();}).then(function(b){send(n,b);}); return true; }
            return false;
          }
          var click=HTMLAnchorElement.prototype.click;
          HTMLAnchorElement.prototype.click=function(){ if(!grab(this)) return click.call(this); };
          document.addEventListener('click',function(e){ var a=e.target&&e.target.closest&&e.target.closest('a[download]'); if(a&&grab(a)) e.preventDefault(); },true);
          window.print=function(){ AlphaHost.print(document.title||'Document'); };
          if(!navigator.share){
            navigator.share=function(d){ d=d||{}; AlphaHost.share(d.title||'',[d.text||'',d.url||''].filter(Boolean).join('\n')); return Promise.resolve(); };
            navigator.canShare=function(){ return true; };
          }
        })();
    """.trimIndent()
}

/** What a page may ask the phone to do. Each action opens one of the phone's own screens, so the owner chooses the destination every time. */
class SiteBridge(private val activity:Activity?,private val save:(String,String,ByteArray)->Unit,private val webView:()->WebView?) {
    private val main=Handler(Looper.getMainLooper())
    @JavascriptInterface fun saveFile(name:String,mime:String,base64:String) {
        val bytes=runCatching {Base64.decode(base64,Base64.DEFAULT)}.getOrNull()?.takeIf {it.isNotEmpty() && it.size<=SitePage.MAX_SAVE_BYTES}?:return
        val clean=name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._ -]"),"_").take(80).ifBlank {"download"}
        main.post {save(clean,mime.take(80).ifBlank {"application/octet-stream"},bytes)}
    }
    @JavascriptInterface fun share(title:String,text:String) {
        val a=activity?:return
        main.post {runCatching {a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT,title.take(120)).putExtra(Intent.EXTRA_TEXT,text.take(20_000)),title.ifBlank {"Share"}))}}
    }
    @JavascriptInterface fun print(title:String) {
        val a=activity?:return
        main.post {
            val view=webView()?:return@post
            runCatching {
                val manager=a.getSystemService(Context.PRINT_SERVICE) as PrintManager
                val job=title.take(60).ifBlank {"Document"}
                manager.print(job,view.createPrintDocumentAdapter(job),PrintAttributes.Builder().build())
            }
        }
    }
}

private fun Context.activity():Activity? {
    var c=this
    while(c is ContextWrapper) {if(c is Activity) return c;c=c.baseContext}
    return null
}

@SuppressLint("SetJavaScriptEnabled")
@Composable fun SitePreview(c:Controller,name:String,share:()->Unit,showCode:()->Unit,close:()->Unit) {
    val context=LocalContext.current
    val file=remember(name,c.workspace) {c.file(name)}
    val html=remember(file) {runCatching {file.readText()}.getOrDefault("")}
    // A page's "choose a photo or a file" and "save this file" use the phone's own pickers.
    var pickerReply by remember {mutableStateOf<ValueCallback<Array<Uri>>?>(null)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {result->
        pickerReply?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode,result.data));pickerReply=null
    }
    var pendingSave by remember {mutableStateOf<Triple<String,String,ByteArray>?>(null)}
    val saver=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) {uri->
        val data=pendingSave;pendingSave=null
        if(uri!=null && data!=null) runCatching {context.contentResolver.openOutputStream(uri)?.use {it.write(data.third)}}
    }
    var webRef by remember {mutableStateOf<WebView?>(null)}
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize(),color=Color.White) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().background(Color(0xFF111111)).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(name,color=Color.White,fontSize=14.sp,fontWeight=FontWeight.SemiBold,maxLines=1,modifier=Modifier.weight(1f).padding(start=8.dp))
                    TextButton(onClick=showCode) {Text("Code")}
                    TextButton(onClick=share) {Text("Share")}
                    TextButton(onClick=close) {Text("Close")}
                }
                AndroidView(modifier=Modifier.weight(1f).fillMaxWidth(),onRelease={it.destroy()},factory={ctx->
                    WebView(ctx).apply {
                        webRef=this
                        settings.javaScriptEnabled=true;settings.domStorageEnabled=true
                        settings.allowFileAccess=false;settings.allowContentAccess=false;settings.setSupportMultipleWindows(false)
                        settings.javaScriptCanOpenWindowsAutomatically=false;settings.setGeolocationEnabled(false)
                        settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.useWideViewPort=true;settings.loadWithOverviewMode=true
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this,false)
                        // The only things a page can ask for: save a file, print, share (each opens the phone's own screen).
                        addJavascriptInterface(SiteBridge(ctx.activity(),{n,m,b->pendingSave=Triple(n,m,b);runCatching {saver.launch(n)}.onFailure {pendingSave=null}},{webRef}),"AlphaHost")
                        webChromeClient=object:WebChromeClient() {
                            override fun onShowFileChooser(view:WebView,callback:ValueCallback<Array<Uri>>,params:FileChooserParams):Boolean {
                                pickerReply?.onReceiveValue(null)
                                pickerReply=callback
                                return try {picker.launch(params.createIntent());true} catch(e:Exception) {pickerReply=null;callback.onReceiveValue(null);false}
                            }
                        }
                        setDownloadListener {url,_,_,mime,_->
                            // A plain link to one of the page's own files (an export it saved next to itself).
                            val uri=Uri.parse(url)
                            val target=if(uri.host==SitePage.HOST) runCatching {File(file.parentFile,Rules.fileName(uri.path.orEmpty().trimStart('/')))}.getOrNull()?.takeIf {it.isFile} else null
                            if(target!=null && target.length()<=SitePage.MAX_SAVE_BYTES) {pendingSave=Triple(target.name,mime.orEmpty().ifBlank {SitePage.mime(target.name)},target.readBytes());runCatching {saver.launch(target.name)}.onFailure {pendingSave=null}}
                        }
                        webViewClient=object:WebViewClient() {
                            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean {
                                val uri=request.url
                                if(!request.isForMainFrame || uri.host==SitePage.HOST) return false
                                val action=if(uri.scheme=="tel") Intent.ACTION_DIAL else Intent.ACTION_VIEW
                                if(uri.scheme in setOf("tel","sms","smsto","mailto","geo","https","http"))
                                    runCatching {context.startActivity(Intent(action,uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
                                return true
                            }
                            override fun onPageFinished(view:WebView,url:String?) {view.evaluateJavascript(SitePage.SHIM,null)}
                            // Pictures and other pages saved next to this one in the bot's files.
                            override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse? {
                                val uri=request.url
                                if(uri.host!=SitePage.HOST) return null
                                val path=uri.path.orEmpty().trimStart('/')
                                val target=if(path.isBlank()) file else runCatching {File(file.parentFile,Rules.fileName(path))}.getOrNull()?.takeIf {it.isFile}
                                return if(target==null || !target.isFile) WebResourceResponse("text/plain","utf-8",404,"Not found",emptyMap(),ByteArrayInputStream(ByteArray(0)))
                                else WebResourceResponse(SitePage.mime(target.name),"utf-8",target.inputStream())
                            }
                        }
                        // Installed before the page's own scripts run, so its first print or download already works.
                        if(androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT))
                            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(this,SitePage.SHIM,setOf("https://"+SitePage.HOST))
                        loadDataWithBaseURL("https://"+SitePage.HOST+"/"+name,html,"text/html","utf-8",null)
                    }
                })
            }
        }
    }
}
