package com.alphainternational.assistant

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real Android checks for the sandboxed code runner and background page reading. */
class GeneralToolsTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun CodeRunsInTheSandboxAndCanWriteFiles() = runBlocking {
        val result=CodeRunner.run(context,"""
            var rows=files['sales.csv'].trim().split('\n').slice(1).map(function(l){return l.split(',')});
            var total=rows.reduce(function(s,r){return s+Number(r[1])},0);
            console.log('rows',rows.length);
            output['total.csv']='total\n'+total;
            return total;
        """.trimIndent(),JSONObject().put("sales.csv","item,amount\npads,89\nlabor,180\n"))
        assertEquals(269,result.getInt("result"))
        assertEquals("rows 2",result.getJSONArray("logs").getString(0))
        assertEquals("total\n269",result.getJSONObject("output").getString("total.csv"))
    }
    @Test fun CodeErrorsAndEndlessLoopsAreContained() = runBlocking {
        assertTrue(CodeRunner.run(context,"throw new Error('bad input')",JSONObject()).getString("error").contains("bad input"))
        val stopped=runCatching {CodeRunner.run(context,"while(true){}",JSONObject(),timeoutMs=3000)}
        assertTrue(stopped.exceptionOrNull()?.message.orEmpty().contains("stopped"))
        // The sandbox has no network: fetch never reaches the internet.
        val net=CodeRunner.run(context,"var x=new XMLHttpRequest();try{x.open('GET','https://example.com',false);x.send();return x.status}catch(e){return 'blocked'}",JSONObject())
        assertNotEquals(200,net.opt("result"))
    }
    @Test fun MarketplaceAppsPublishPhoneSignIn() = runBlocking {
        // Read-only discovery only: no client registration, no account, no sign-in.
        val oauth=McpOAuth(context) {true}
        for(id in listOf("notion","linear","paypal","stripe","todoist")) {
            val app=AppCatalog.find(id)!!
            val found=runCatching {oauth.discover(app.url)}
            assertTrue(id+": "+found.exceptionOrNull()?.message,found.isSuccess)
            val meta=found.getOrThrow().metadata
            assertTrue(id,meta.optString("authorization_endpoint").startsWith("https://") && meta.optString("token_endpoint").startsWith("https://"))
            assertTrue(id+" has no open registration",meta.optString("registration_endpoint").startsWith("https://"))
        }
    }
    @Test fun TeachModeRecordsTapsAndFieldsButNotTypedText() = runBlocking {
        val engine=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {BrowserEngine(context,{it},{_,_->})}
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                engine.view.loadDataWithBaseURL("https://example.com/form","<form><label>Part number<input id=p></label><button id=b type=button>Add to cart</button></form>","text/html","utf-8",null)
            }
            kotlinx.coroutines.delay(1500)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {engine.startTeaching();engine.attachRecorder()}
            kotlinx.coroutines.delay(300)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                engine.view.evaluateJavascript("var i=document.getElementById('p');i.value='SECRET-123';i.dispatchEvent(new Event('change',{bubbles:true}));document.getElementById('b').click();",null)
            }
            kotlinx.coroutines.delay(800)
            val steps=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {engine.stopTeaching()}
            assertTrue(steps.toString(),steps.any {it.contains("Part number") && it.contains("not recorded")})
            assertTrue(steps.toString(),steps.any {it.contains("Tapped \"Add to cart\"")})
            assertFalse(steps.toString().contains("SECRET-123"))
        } finally {kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {engine.destroy()}}
    }
    @Test fun PublicPagesCanBeReadInTheBackground() = runBlocking {
        val page=WebReader.read("https://example.com")
        assertTrue(page.getString("title").contains("Example"))
        assertTrue(page.getString("text").contains("documentation"))
        assertTrue(runCatching {WebReader.read("https://localhost/")}.isFailure)
    }
}
