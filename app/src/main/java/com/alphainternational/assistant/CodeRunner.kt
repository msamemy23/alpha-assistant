package com.alphainternational.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * A small computer for calculations and data work: runs JavaScript in an isolated page with no network and no
 * file access. Workspace text files go in as `files`; text the code puts in `output` can be saved back.
 */
object CodeRunner {
    fun script(code:String,files:JSONObject):String =
        "(function(){var logs=[];var console={log:function(){logs.push(Array.prototype.map.call(arguments,function(x){" +
            "return typeof x==='string'?x:JSON.stringify(x)}).join(' '))}};console.error=console.log;console.warn=console.log;" +
            "var files=" + files + ";var output={};var result;" +
            "try{result=(new Function('files','output','console'," + JSONObject.quote(code) + "))(files,output,console);}" +
            "catch(e){return JSON.stringify({error:String(e&&e.stack?e.stack:e),logs:logs.slice(0,200)});}" +
            "var out;try{out=JSON.stringify({result:result===undefined?null:result,logs:logs.slice(0,200),output:output});}" +
            "catch(e){out=JSON.stringify({result:String(result),logs:logs.slice(0,200),output:{}});}return out;})()"

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun run(context:Context,code:String,files:JSONObject,timeoutMs:Long=15_000):JSONObject {
        require(code.isNotBlank() && code.length<=40_000) {"Code must be 1 to 40,000 characters."}
        val raw=withContext(Dispatchers.Main) {
            val view=WebView(context.applicationContext)
            try {
                view.settings.javaScriptEnabled=true
                view.settings.blockNetworkLoads=true
                view.settings.allowFileAccess=false
                view.settings.allowContentAccess=false
                withTimeoutOrNull(timeoutMs) {
                    suspendCancellableCoroutine<String?> {continuation->
                        var started=false
                        view.webViewClient=object:WebViewClient() {
                            // Nothing leaves the sandbox: every request gets an empty response.
                            override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest)=WebResourceResponse("text/plain","utf-8",null)
                            override fun onPageFinished(page:WebView,url:String?) {
                                if(started)return
                                started=true
                                page.evaluateJavascript(script(code,files)) {value->if(continuation.isActive)continuation.resume(value)}
                            }
                        }
                        view.loadDataWithBaseURL(null,"<!doctype html><title>sandbox</title>","text/html","utf-8",null)
                    }
                }
            } finally {view.stopLoading();view.destroy()}
        }?:error("The code ran longer than ${timeoutMs/1000} seconds and was stopped. Simplify it or process less data.")
        // evaluateJavascript returns the script's string result encoded as a JSON literal.
        val text=runCatching {JSONArray("[$raw]").getString(0)}.getOrElse {error("The code did not return a result.")}
        return JSONObject(text)
    }
}
