package com.alphainternational.assistant

import java.net.URI

/** Teach by demonstration (Grok Bot's "Teach a task"): the owner does a task once in Alpha's browser and Alpha drafts a skill. */
object TeachSteps {
    /** Where the owner was, without query strings that may hold personal data. */
    fun place(url:String):String = runCatching {val u=URI(url);(u.host.orEmpty()+u.rawPath.orEmpty()).trimEnd('/').take(100)}.getOrDefault("")

    /** Page script: reports taps, choices and which fields were filled, never the typed values. */
    fun recorder(key:String):String {
        require(key.matches(Regex("[A-Za-z0-9_-]{8,40}")))
        return """(function(){
 if(window.__alphaTeach==='__KEY__')return; window.__alphaTeach='__KEY__';
 var P='ALPHA_TEACH___KEY__:';
 function label(e){ var t=(e.getAttribute&&e.getAttribute('aria-label'))||'';
  if(!t&&e.labels&&e.labels.length)t=e.labels[0].innerText;
  if(!t)t=e.placeholder||e.name||e.title||'';
  if(!t&&e.tagName!=='INPUT'&&e.tagName!=='TEXTAREA'&&e.tagName!=='SELECT')t=e.innerText||e.value||'';
  return String(t).replace(/\s+/g,' ').trim().slice(0,80); }
 document.addEventListener('click',function(ev){
  var e=ev.target&&ev.target.closest?(ev.target.closest('a,button,[role=button],input[type=submit],input[type=button],input[type=checkbox],input[type=radio],summary,[onclick]')||ev.target):null;
  if(!e||!e.tagName)return;
  if(e.tagName==='TEXTAREA'||e.tagName==='SELECT'||(e.tagName==='INPUT'&&!/^(submit|button|checkbox|radio|reset|image)$/.test(e.type||'text')))return;
  var l=label(e); if(l) console.log(P+'Tapped "'+l+'"');
 },true);
 document.addEventListener('change',function(ev){
  var e=ev.target; if(!e||!e.tagName)return; var l=label(e)||e.type||'a field';
  if(e.tagName==='SELECT'){var o=e.options[e.selectedIndex];console.log(P+'Chose "'+(o?o.text:'').trim().slice(0,60)+'" for "'+l+'"');}
  else if(e.type==='checkbox'||e.type==='radio'){console.log(P+(e.checked?'Checked':'Unchecked')+' "'+l+'"');}
  else if(e.type==='password'){console.log(P+'Entered a password (not recorded)');}
  else {console.log(P+'Filled "'+l+'" (what was typed is not recorded)');}
 },true);
 document.addEventListener('submit',function(){console.log(P+'Submitted the form');},true);
})();""".replace("__KEY__",key)
    }

    /** The request that turns a demonstration into a saved skill. */
    fun prompt(steps:List<String>):String =
        "I just showed you how I do a task in your browser. These are the steps I took (what I typed was not recorded):\n"+
        steps.mapIndexed {i,s->"${i+1}. $s"}.joinToString("\n")+
        "\n\nTurn this into a reusable skill with skill_save. Work out which parts change each time (names, dates, amounts, search words) and ask me about anything unclear first. "+
        "Write steps you can follow with browser_open, browser_read, browser_click and browser_fill, and mark where I must log in, pay or approve. Don't run the task now."
}
