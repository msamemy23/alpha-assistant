package com.alphainternational.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File

/**
 * Music and sound effects made on the phone, free and offline. A bot writes a "score" (instruments, notes, drum patterns, effects) as JSON, or Tone.js
 * code, and a hidden page running the bundled engine (a real General MIDI sound bank, a synthesizer and a retro sound-effect maker) renders it faster
 * than real time into a 44.1 kHz stereo WAV file. No account, no key, no network.
 */
object AudioRender {
    class Done(val file:File,val seconds:Double,val meta:JSONObject)

    private const val HOST="appassets.androidplatform.net"
    const val MAX_SCORE_CHARS=120_000
    const val MAX_CODE_CHARS=40_000

    /** The score format and instrument list, in words for a bot; or one example score by name. */
    fun guide(context:Context,topic:String):JSONObject {
        fun read(path:String)=runCatching {context.assets.open(path).use {String(it.readBytes(),Charsets.UTF_8)}}.getOrNull()
        val examples=runCatching {context.assets.list("audio/examples")?.map {it.removeSuffix(".json")}?.filter {it!="index"}?.sorted()}.getOrNull().orEmpty()
        val wanted=topic.trim().lowercase().removeSuffix(".json")
        if(wanted in setOf("code","tone","tone.js")) return obj("tone_code" to (read("audio/TONE-CODE.md")?:"Tone.js code guide missing."),"result" to "The code you pass to audio_code is the body of an async function using these variables.")
        if(wanted=="index") return obj("examples" to (read("audio/examples/index.json")?:"[]"),"result" to "What each example is for; get one by name.")
        if(wanted.isNotBlank() && wanted !in setOf("guide","schema","help")) {
            val example=read("audio/examples/$wanted.json")
                ?:return obj("error" to "No example called \"$wanted\".","examples" to arr(examples))
            return obj("example" to wanted,"score" to example,"result" to "A working score. Change the notes, instruments and tempo, then pass it to audio_job.")
        }
        return obj("guide" to (read("audio/SCORE-SCHEMA.md")?:"The score guide is missing from this version of the app; read an example instead."),"examples" to arr(examples),
            "result" to "Read the guide, copy an example that is close to what the owner wants (audio_guide with the example's name; audio_guide with example \"index\" says what each is for), change it, and call audio_job. For Tone.js code, audio_guide with example \"code\".")
    }

    /**
     * Renders [input] (a score as JSON, or Tone.js code when [tone] is true) into [output] (a .wav) in [dir]. Runs on the main looper because it
     * owns a hidden page; the audio work itself happens inside that page. [progress] gets 0..100 and a short stage name.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun render(context:Context,dir:File,input:String,tone:Boolean,output:String,progress:(Int,String)->Unit):Done {
        require(input.isNotBlank()) {"Give a score (JSON) or Tone.js code to render."}
        require(input.length<=if(tone) MAX_CODE_CHARS else MAX_SCORE_CHARS) {"That is too long to render: keep a score under ${MAX_SCORE_CHARS/1000},000 characters and code under ${MAX_CODE_CHARS/1000},000."}
        val name=output.trim().ifBlank {"sound.wav"}.let {if(it.endsWith(".wav",true)) it else "$it.wav"}
        runCatching {Rules.fileName(name)}.getOrElse {throw IllegalArgumentException("output must be a simple file name like jingle.wav.")}
        val temp=File(dir,".render-"+System.nanoTime()+".wav")
        val finished=CompletableDeferred<Unit>()
        val metaIn=CompletableDeferred<JSONObject>()
        val pieces=java.util.TreeMap<Int,String>()
        val main=Handler(Looper.getMainLooper())
        val bridge=object {
            @JavascriptInterface fun postMeta(json:String) {metaIn.complete(runCatching {JSONObject(json)}.getOrDefault(JSONObject()))}
            // The finished sound arrives in pieces (a long track is tens of megabytes of text), each decoded and written in order.
            @JavascriptInterface fun postChunk(index:Int,count:Int,piece:String) {
                synchronized(pieces) {
                    pieces[index]=piece
                    if(pieces.size==count) {
                        try {
                            temp.outputStream().use {out->pieces.values.forEach {out.write(Base64.decode(it,Base64.DEFAULT))}}
                            pieces.clear()
                            require(temp.length()>1000) {"The sound came out empty."}
                            finished.complete(Unit)
                        } catch(e:Exception) {finished.completeExceptionally(e)}
                    }
                }
            }
            @JavascriptInterface fun postResult(base64:String) {
                try {
                    val bytes=Base64.decode(base64,Base64.DEFAULT)
                    require(bytes.size>1000) {"The sound came out empty."}
                    temp.writeBytes(bytes);finished.complete(Unit)
                } catch(e:Exception) {finished.completeExceptionally(e)}
            }
            @JavascriptInterface fun postError(message:String) {finished.completeExceptionally(IllegalStateException(message.take(600)))}
            @JavascriptInterface fun postProgress(percent:Int,stage:String) {main.post {progress(percent.coerceIn(0,100),stage.take(40))}}
        }
        val view=withContext(Dispatchers.Main) {
            val assets=WebViewAssetLoader.Builder().setDomain(HOST).addPathHandler("/assets/",WebViewAssetLoader.AssetsPathHandler(context.applicationContext)).build()
            WebView(context.applicationContext).also {v->
                v.settings.javaScriptEnabled=true
                v.settings.allowFileAccess=false
                v.settings.allowContentAccess=false
                v.settings.blockNetworkLoads=false   // the asset loader answers from the app; nothing else is allowed through shouldInterceptRequest
                v.addJavascriptInterface(bridge,"AlphaAudio")
                v.webViewClient=object:WebViewClient() {
                    private var started=false
                    override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse? =
                        assets.shouldInterceptRequest(request.url)?:WebResourceResponse("text/plain","utf-8",403,"Blocked",emptyMap(),java.io.ByteArrayInputStream(ByteArray(0)))
                    override fun onPageFinished(page:WebView,url:String?) {
                        if(started) return
                        started=true
                        val call=if(tone) "renderToneCode(" else "renderScore("
                        // The engine reports success and failure to the bridge itself; the catch is only for a script that never got that far.
                        page.evaluateJavascript("(function(){try{"+call+JSONObject.quote(input)+",{chunkSize:524288}).catch(function(e){AlphaAudio.postError(String(e&&e.message||e))});}catch(e){AlphaAudio.postError(String(e&&e.message||e))}})()",null)
                    }
                }
                v.loadUrl("https://$HOST/assets/audio/audio-render.html")
            }
        }
        try {
            withTimeout(4*60_000L) {finished.await()}
            val result=withTimeoutOrNull(3_000L) {metaIn.await()}?:JSONObject()
            val out=File(dir,name)
            out.delete()
            require(temp.renameTo(out)) {"The finished sound couldn't be saved."}
            progress(100,"done")
            // 44.1 kHz, 16-bit, stereo: 176,400 bytes a second after the 44-byte header (used when the engine's own summary didn't arrive).
            return Done(out,result.optDouble("durationSec",(out.length()-44)/176_400.0),result)
        } finally {
            temp.delete()
            withContext(Dispatchers.Main) {runCatching {view.stopLoading();view.destroy()}}
        }
    }

    /** What the bot is told about the finished sound, since it can't hear it: length, loudness, and a plain warning if it is silent or clipping. */
    fun report(done:Done):JSONObject {
        val m=done.meta;val peak=m.optDouble("peak",0.0);val warnings=mutableListOf<String>()
        if(peak<0.02) warnings+="The sound is almost silent. Check that the notes sit inside the length and the track volumes are above 0."
        if(peak>=0.999) warnings+="The sound hits the loudest possible level and may crackle. Lower the track volumes a little."
        if(done.seconds<0.2) warnings+="The sound is shorter than a fifth of a second."
        return obj("name" to done.file.name,"seconds" to Math.round(done.seconds*100)/100.0,"peak" to Math.round(peak*1000)/1000.0,"rms" to Math.round(m.optDouble("rms",0.0)*1000)/1000.0,
            "lufs" to (if(m.has("lufs") && !m.isNull("lufs")) m.optDouble("lufs") else JSONObject.NULL),"warnings" to arr(warnings),"files" to arr(listOf(done.file.name)))
    }
}
