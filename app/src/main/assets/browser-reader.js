// Read rendered text, not textContent from a detached DOM clone. Input values
// and hidden/private nodes are excluded. Off-screen content remains readable.
(function(offset) {
  // Labels from earlier reads (or planted by the page) are cleared; this read's labels carry a fresh random prefix.
  document.querySelectorAll('[data-alpha-id]').forEach(function(x){x.removeAttribute('data-alpha-id');});
  function visible(e) {
    if (!e || e.closest('script,style,noscript,input,textarea,select,[hidden],[aria-hidden="true"],[data-private],.password')) return false;
    for (var p=e;p && p.nodeType===1;p=p.parentElement) {
      var s=getComputedStyle(p);
      if(s.display==='none' || s.visibility==='hidden' || s.visibility==='collapse') return false;
    }
    return true;
  }
  function text(e,limit) {
    if(!e)return '';
    var walk=document.createTreeWalker(e,NodeFilter.SHOW_TEXT), parts=[], n, count=0;
    while((n=walk.nextNode()) && count<limit) {
      if(!visible(n.parentElement))continue;
      var value=n.textContent.replace(/\s+/g,' ').trim();
      if(value){parts.push(value);count+=value.length+1;}
    }
    return parts.join('\n').slice(0,limit);
  }
  var main=document.querySelector('[role="main"],main,#search,#dp')||document.body||document.documentElement;
  var listing=document.querySelector('.s-main-slot,[data-testid="product-list"],[role="list"]');
  if(listing && text(listing,100).length>20)main=listing;
  var body=text(main,160000), start=Math.max(0,Math.floor(offset||0));
  // Everything a person could tap or type into, including web-app controls (roles, rich text boxes, tappable tiles).
  var controls='a[href],button,input,textarea,select,summary,[role="button"],[role="link"],[role="tab"],[role="menuitem"],[role="option"],'+
    '[role="checkbox"],[role="switch"],[role="radio"],[role="textbox"],[role="combobox"],[role="searchbox"],[contenteditable=""],[contenteditable="true"],[onclick]';
  var candidates=Array.from(main.querySelectorAll(controls));
  var page=document.body||document.documentElement;if(main!==page)candidates=candidates.concat(Array.from(page.querySelectorAll(controls)));
  function label(e) {
    var by=e.getAttribute('aria-labelledby'), named=by?by.split(/\s+/).map(function(i){var n=document.getElementById(i);return n?n.innerText:'';}).join(' '):'';
    var img=e.querySelector&&e.querySelector('img[alt]');
    var own=e.getAttribute('aria-label')||named||(e.isContentEditable?'':e.innerText)||e.placeholder||e.getAttribute('data-placeholder')||
      e.title||(img?img.alt:'')||(e.tagName==='INPUT'&&/^(submit|button)$/.test(e.type)?e.value:'')||e.name||'';
    if(!own && (e.isContentEditable||/textbox|searchbox|combobox/.test(e.getAttribute('role')||'')))own='Text box';
    return String(own).replace(/\s+/g,' ').trim().slice(0,120);
  }
  // A control with no words on it (a menu icon, a round avatar button) is named from what it is: its labels, its icon, its class names, or where it sits.
  function cls(x){var c=x.className;return String(c&&c.baseVal!==undefined?c.baseVal:c||"");}
  function iconName(e) {
    var own=e.getAttribute("aria-label")||e.title||e.getAttribute("data-testid")||e.getAttribute("data-test")||e.getAttribute("data-cy")||e.getAttribute("name")||"";
    if(!own){var t=e.querySelector&&e.querySelector("svg title");if(t)own=t.textContent;}
    if(!own){var ic=(e.innerText||e.textContent||"").trim();if(ic&&ic.length<=24)own=ic;}
    if(!own){
      var all=cls(e)+" "+(e.querySelectorAll?Array.from(e.querySelectorAll("[class]")).slice(0,4).map(cls).join(" "):"");
      var m=all.toLowerCase().match(/(hamburger|burger|menu|nav-?toggle|sidebar|drawer|close|search|profile|account|avatar|user|settings|cog|bell|notification|back|arrow|more|dots|filter|cart|home)/);
      if(m)own=m[1]+" icon";
    }
    if(!own){
      var r=e.getBoundingClientRect();
      if(r.width<=90&&r.height<=90)own="Icon button ("+(r.top<window.innerHeight/3?"top":r.top>window.innerHeight*0.66?"bottom":"middle")+" "+(r.left<window.innerWidth/3?"left":r.left>window.innerWidth*0.66?"right":"center")+")";
    }
    return String(own).replace(/\s+/g," ").trim().slice(0,60);
  }
  var seen=new Set(), elements=[];
  candidates.forEach(function(e) {
    if(elements.length>=150 || seen.has(e))return;
    var field=['INPUT','TEXTAREA','SELECT'].includes(e.tagName)||e.isContentEditable;
    if(!visible(e) && !field)return;
    if(e.type==='hidden' || e.closest('[data-private],[hidden],[aria-hidden="true"]'))return;
    var s=getComputedStyle(e);if(s.display==='none'||s.visibility==='hidden')return;
    if(!e.getClientRects().length)return;
    // A control inside another control is the same tap target.
    if(e.parentElement && e.parentElement.closest('a[href],button,[role="button"],[role="link"]') && !field)return;
    seen.add(e);
    // A control with nothing written on it is only listed when it is big enough to be seen and tapped (not a 1-pixel tracking link or a skip link).
    var text=label(e)||(e.getBoundingClientRect().width>=24&&e.getBoundingClientRect().height>=24?iconName(e):"");
    if(!text)return;
    var id=String(elements.length);e.setAttribute('data-alpha-id','__NONCE__-'+id);
    var kind=e.isContentEditable?'textbox':(e.getAttribute('role')||e.tagName.toLowerCase());
    var r=e.getBoundingClientRect();
    elements.push({id:id,kind:kind,type:e.type||'',label:text,href:e.tagName==='A'?e.href:undefined,secret:e.type==='password',
      on_screen:r.bottom>0&&r.top<window.innerHeight});
  });
  // Web apps often make a menu button out of a plain div or icon with a click handler. Such a thing shows the pointer cursor, and the outermost one is the control.
  var extras=[];
  var every=page.querySelectorAll("div,span,li,i,svg,img,mat-icon,td,label");
  for(var q=0;q<every.length&&q<5000&&extras.length<80;q++){
    var x=every[q];
    if(seen.has(x)||x.closest("a[href],button,[role],input,textarea,select,summary,[data-private],[hidden],[aria-hidden=\"true\"]"))continue;
    if(getComputedStyle(x).cursor!=="pointer")continue;
    var px=x.parentElement;if(px&&getComputedStyle(px).cursor==="pointer")continue;
    var rx=x.getBoundingClientRect();
    if(rx.width<12||rx.height<12||(rx.width>window.innerWidth*0.95&&rx.height>window.innerHeight*0.5)||!visible(x))continue;
    extras.push(x);
  }
  extras.sort(function(a,b){var ra=a.getBoundingClientRect(),rb=b.getBoundingClientRect();return (ra.bottom>0&&ra.top<window.innerHeight?0:1)-(rb.bottom>0&&rb.top<window.innerHeight?0:1);});
  extras.forEach(function(e) {
    if(elements.length>=150)return;
    var name=label(e)||iconName(e);
    if(!name)return;
    var id=String(elements.length);e.setAttribute("data-alpha-id","__NONCE__-"+id);
    var r=e.getBoundingClientRect();
    elements.push({id:id,kind:"tap",type:"",label:name,secret:false,on_screen:r.bottom>0&&r.top<window.innerHeight});
  });
  var products=Array.from(document.querySelectorAll('[data-component-type="s-search-result"],[itemtype*="schema.org/Product"],[data-testid="product-card"]')).slice(0,12).map(function(e) {
    var link=e.querySelector('h2 a,a:has(h2),a[href*="/dp/"],a[itemprop="url"]');
    return {name:text(e.querySelector('h2,[itemprop="name"]'),250),price:text(e.querySelector('.a-price,[itemprop="price"],[data-testid="price"]'),100),url:link?link.href:'',details:text(e,1100)};
  });
  var schemas=[];
  document.querySelectorAll('script[type="application/ld+json"]').forEach(function(e){
    try {var data=JSON.parse(e.textContent);(Array.isArray(data)?data:data['@graph']||[data]).forEach(function(x){
      if(['Product','Offer','ItemList'].includes(x['@type']) && JSON.stringify(x).length<10000)schemas.push(x);
    });}catch(ignore){}
  });
  var blocked=/captcha|enter the characters you see|verify you are human|robot check/i.test(document.title+' '+body.slice(0,3000));
  return JSON.stringify({url:location.href,title:document.title,readyState:document.readyState,page_status:blocked?'needs_human_verification':'readable',
    text:body.slice(start,start+10000),text_offset:start,total_chars:body.length,next_offset:start+10000<body.length?start+10000:null,
    products:products,structured_data:schemas.slice(0,3),elements:elements});
})(__OFFSET__);
