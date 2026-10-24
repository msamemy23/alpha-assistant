package com.alphainternational.assistant

import org.json.JSONObject

/** Page scripts for Alpha's browser. They act like a person: tap the middle of an element, type into any text box, find what scrolls. */
object BrowserScripts {
    /** The WebView's own agent without the in-app markers, so pages treat it like the phone's Chrome. */
    fun chromeAgent(agent:String)=agent.replace("; wv)",")").replace(Regex("Version/[0-9.]+ "),"")

    /** A tap: scroll it into view, then pointer and mouse events at its center, then click (web apps listen for different ones). */
    const val tap="e.scrollIntoView({block:'center',inline:'center'});var r=e.getBoundingClientRect();" +
        "var o={bubbles:true,cancelable:true,composed:true,view:window,clientX:r.left+r.width/2,clientY:r.top+r.height/2,button:0,buttons:1};" +
        "var p=function(t){try{e.dispatchEvent(new PointerEvent(t,Object.assign({pointerId:1,pointerType:'touch',isPrimary:true},o)));}catch(x){}};" +
        "p('pointerdown');e.dispatchEvent(new MouseEvent('mousedown',o));if(e.focus)e.focus();p('pointerup');e.dispatchEvent(new MouseEvent('mouseup',o));e.click();"

    /**
     * Where to tap on the screen for element e: brought into view, its center in screen pixels, and whether a finger there
     * would land on it (not off screen, not under a banner). Returns JSON.
     */
    const val spot="e.scrollIntoView({block:'center',inline:'center',behavior:'instant'});var r=e.getBoundingClientRect();" +
        "var cx=r.left+r.width/2,cy=r.top+r.height/2,vv=window.visualViewport,scale=vv?vv.scale:1,ox=vv?vv.offsetLeft:0,oy=vv?vv.offsetTop:0;" +
        "var hit=document.elementFromPoint(cx,cy);var onIt=!!hit&&(hit===e||e.contains(hit)||hit.contains(e));" +
        "var inView=r.width>0&&r.height>0&&cx>=0&&cy>=0&&cx<=window.innerWidth&&cy<=window.innerHeight;" +
        "return JSON.stringify({x:(cx-ox)*scale*devicePixelRatio,y:(cy-oy)*scale*devicePixelRatio,tappable:onIt&&inView});"

    /** Types [value] into a field, a text area, or a rich text box (Gmail, chat apps) the way the page's own code expects. */
    fun type(value:String):String {
        val v=JSONObject.quote(value)
        return "var v=$v;" +
            "if(!e.isContentEditable&&['INPUT','TEXTAREA','SELECT'].indexOf(e.tagName)<0){var inner=e.querySelector('[contenteditable=\"\"],[contenteditable=\"true\"],textarea,input:not([type=hidden]):not([type=password])');if(inner)e=inner;}" +
            "if(e.isContentEditable){e.focus();document.execCommand('selectAll',false,null);if(!document.execCommand('insertText',false,v)){e.textContent=v;e.dispatchEvent(new InputEvent('input',{bubbles:true,data:v,inputType:'insertText'}));}return 'Typed into the box.';}" +
            "if(['INPUT','TEXTAREA','SELECT'].indexOf(e.tagName)<0)return 'This element cannot be typed into.';" +
            "if(e.type==='password'||e.type==='hidden')return 'Use the login vault for password fields.';" +
            "e.focus();var proto=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:e.tagName==='SELECT'?HTMLSelectElement.prototype:HTMLInputElement.prototype;" +
            "var d=Object.getOwnPropertyDescriptor(proto,'value');if(d&&d.set)d.set.call(e,v);else e.value=v;" +
            "e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));return 'Field filled.';"
    }

    /** Enter in a field. If the page's own code doesn't take it, an ordinary form is submitted. */
    const val enter="if(e.focus)e.focus();var o={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true,composed:true};" +
        "var free=e.dispatchEvent(new KeyboardEvent('keydown',o));e.dispatchEvent(new KeyboardEvent('keypress',o));e.dispatchEvent(new KeyboardEvent('keyup',o));" +
        "if(free&&e.form&&e.tagName==='INPUT'){if(e.form.requestSubmit)e.form.requestSubmit();else e.form.submit();return 'Pressed Enter and submitted the form.';}return 'Pressed Enter.';"

    /** Scrolls the biggest visible scrolling panel, or the page; returns "scrolled" or "end". */
    fun scroll(direction:Int)="(function(dir){var h=window.innerHeight,w=window.innerWidth,best=null,area=0;" +
        "document.querySelectorAll('*').forEach(function(e){if(e.scrollHeight<=e.clientHeight+20)return;var s=getComputedStyle(e).overflowY;" +
        "if(!/(auto|scroll|overlay)/.test(s))return;var r=e.getBoundingClientRect();" +
        "var a=Math.max(0,Math.min(r.bottom,h)-Math.max(r.top,0))*Math.max(0,Math.min(r.right,w)-Math.max(r.left,0));if(a>area){area=a;best=e;}});" +
        "var amount=dir*Math.max(200,h*0.8),doc=document.scrollingElement||document.documentElement;" +
        "if(best&&(doc.scrollHeight<=h+20||area>w*h*0.4)){var b=best.scrollTop;best.scrollBy({top:amount,behavior:'instant'});if(best.scrollTop!==b)return 'scrolled';}" +
        "var y=window.scrollY;window.scrollBy({top:amount,behavior:'instant'});return window.scrollY!==y?'scrolled':'end';})($direction)"

    private val risky=Regex("""\b(delete|remove|cancel|unsubscribe|log ?out|sign ?out|buy|order|pay|checkout|send|confirm|submit|post|publish|book|reserve|accept|agree)\b""",RegexOption.IGNORE_CASE)
    /**
     * A link that only opens another page (no form, not a same-page script link, no words like Buy, Send or Delete)
     * can be followed without an approval card, like opening a page. An http:// link counts: it opens over https:// or not at all.
     */
    fun plainLink(target:JSONObject):Boolean {
        val href=target.optString("href");val page=target.optString("page")
        return target.optString("kind")=="A" && (href.startsWith("https://") || href.startsWith("http://")) && target.optString("action").isBlank() &&
            href.substringBefore('#')!=page.substringBefore('#') && !risky.containsMatchIn(target.optString("label"))
    }
}
