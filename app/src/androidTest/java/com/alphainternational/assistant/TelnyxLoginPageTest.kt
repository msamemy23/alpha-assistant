package com.alphainternational.assistant

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Test
import java.io.File

/** Looks at the Telnyx sign-in page the way Alpha's browser sees it (no password is ever typed): does it draw, does its bot check load, what does the page think the browser is. */
class TelnyxLoginPageTest {
    @Test fun lookAtTheTelnyxSignInPage() {
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        instrument.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val c=(context.applicationContext as AlphaApplication).controller
        val report=StringBuilder()
        runBlocking {
            withContext(Dispatchers.Main) {
                var tries=0;while(c.browser==null && tries++<50) delay(100)
                val web=c.web()
                val page=JSONObject(web.open("https://portal.telnyx.com/#/login/sign-in"))
                report.appendLine("url="+page.optString("url")+" title="+page.optString("title"))
                report.appendLine("text="+page.optString("text").take(600).replace('\n',' '))
                delay(6_000)
                val probe=web.probe("(function(){var ifr=[].slice.call(document.querySelectorAll('iframe')).map(function(f){return f.src.slice(0,80)});" +
                    "var inputs=[].slice.call(document.querySelectorAll('input')).map(function(i){return i.type+':'+(i.name||i.id||i.placeholder)});" +
                    "var buttons=[].slice.call(document.querySelectorAll('button,a')).map(function(b){return (b.innerText||'').trim().slice(0,30)}).filter(Boolean).slice(0,20);" +
                    "return JSON.stringify({ua:navigator.userAgent,wd:navigator.webdriver,hash:location.hash,iframes:ifr,inputs:inputs,buttons:buttons,cookies:document.cookie.split(';').map(function(c){return c.split('=')[0].trim()}),ls:Object.keys(localStorage).length,body:document.body.innerText.slice(0,500)})})()")
                report.appendLine("probe="+probe)
                fun elements(p:JSONObject)=p.optJSONArray("elements")?.objects().orEmpty()
                // A: the password form (nothing is typed): does the bot check finish by itself?
                val before=JSONObject(web.read())
                val pw=elements(before).firstOrNull {it.optString("label").contains("Sign in with password",true)}
                report.appendLine("password button="+pw?.toString())
                if(pw!=null) {
                    report.appendLine("click="+web.click(pw.getString("id")))
                    for(second in listOf(4,10,18)) {
                        delay((if(second==4) 4_000 else 6_000).toLong())
                        report.appendLine("after ~${second}s: "+web.probe("(function(){var t=document.querySelector('input[name=cf-turnstile-response]');var f=[].slice.call(document.querySelectorAll('iframe')).map(function(x){return x.src.slice(0,60)});" +
                            "var inputs=[].slice.call(document.querySelectorAll('input')).map(function(i){return i.type+':'+(i.name||i.id||i.placeholder)});return JSON.stringify({tokenLength:t?t.value.length:-1,iframes:f,inputs:inputs,text:document.body.innerText.replace(/\\s+/g,' ').slice(0,300)})})()"))
                    }
                }
                // B: the Google button: does Google accept this browser?
                web.open("https://portal.telnyx.com/#/login/sign-in")
                delay(5_000)
                val again=JSONObject(web.read())
                val google=elements(again).firstOrNull {it.optString("label").equals("Google",true)}
                report.appendLine("google button="+google?.toString())
                if(google!=null) {
                    web.allowPopups(true)
                    report.appendLine("click="+runCatching {web.click(google.getString("id"))}.getOrElse {"error "+it.message})
                    delay(10_000)
                    val window=web.popup
                    report.appendLine("popup opened="+(window!=null)+" popup url="+window?.url?.substringBefore('?'))
                    report.appendLine("main page url="+web.currentUrl)
                    if(window!=null) {
                        val done=kotlinx.coroutines.CompletableDeferred<String>()
                        window.evaluateJavascript("(function(){return JSON.stringify({opener:!!window.opener,text:document.body.innerText.replace(/\\s+/g,' ').slice(0,300)})})()") {done.complete(it.orEmpty())}
                        report.appendLine("popup page="+done.await())
                    }
                    report.appendLine("problems="+web.recentProblems())
                    web.allowPopups(false)
                    report.appendLine("popup after turning off="+(web.popup!=null))
                }
            }
        }
        File(context.getExternalFilesDir(null),"telnyx-login-page.txt").writeText(report.toString())
    }
}