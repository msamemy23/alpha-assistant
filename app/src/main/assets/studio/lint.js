/* lint-run.js | generated from lint-rules.md (do not edit by hand) | 25 checks. Usage: after load in a 390px viewport: var results = await window.__lintRun(); */
window.__H = {
  $: function (s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)); },
  cn: function (e) { return typeof e.className === 'string' ? e.className : ''; },
  vis: function (e) { var r = e.getBoundingClientRect(), s = getComputedStyle(e); return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none' && parseFloat(s.opacity) > 0; },
  rgba: function (c) {
    var m = c.match(/^rgba?\(([^)]+)\)$/), p;
    if (m) { p = m[1].split(/[ ,\/]+/).filter(Boolean).map(parseFloat); return [p[0], p[1], p[2], p.length > 3 ? p[3] : 1]; }
    m = c.match(/^color\(srgb ([^)]+)\)$/);
    if (m) { p = m[1].split(/[ \/]+/).filter(Boolean).map(parseFloat); return [p[0] * 255, p[1] * 255, p[2] * 255, p.length > 3 ? p[3] : 1]; }
    return null;
  },
  lum: function (c) { var f = function (v) { v /= 255; return v <= .03928 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4); }; return .2126 * f(c[0]) + .7152 * f(c[1]) + .0722 * f(c[2]); },
  cr: function (a, b) { var x = __H.lum(a), y = __H.lum(b), t; if (x < y) { t = x; x = y; y = t; } return (x + .05) / (y + .05); },
  over: function (f, b) { var a = f[3]; return [f[0] * a + b[0] * (1 - a), f[1] * a + b[1] * (1 - a), f[2] * a + b[2] * (1 - a), 1]; },
  bg: function (e) {
    var L = [], n = e, s, c, b = [255, 255, 255, 1];
    while (n && n.nodeType === 1) {
      s = getComputedStyle(n);
      if (s.backgroundImage !== 'none') return null;
      c = __H.rgba(s.backgroundColor);
      if (c && c[3] > 0) { L.push(c); if (c[3] >= 1) break; }
      n = n.parentElement;
    }
    if (L.length && L[L.length - 1][3] >= 1) b = L.pop();
    while (L.length) b = __H.over(L.pop(), b);
    return b;
  },
  sig: function (e) { return e.tagName + '.' + __H.cn(e) + '>' + [].map.call(e.children, function (c) { return c.tagName; }).join(','); },
  clipped: function (e) { var n = e.parentElement, s; while (n && n !== document.body) { s = getComputedStyle(n); if (s.overflowX !== 'visible') return true; n = n.parentElement; } return false; },
  hasText: function (e) { return [].some.call(e.childNodes, function (n) { return n.nodeType === 3 && n.nodeValue.trim().length > 1; }); }
};
window.__lintRun = async function () {
  var R = [
    { id: 'document-basics', sev: 'error', f: function () { return ((function () { var out = [], v = document.querySelector('meta[name=viewport]');
  if (!v) out.push('missing <meta name="viewport">');
  else if (!/width\s*=\s*device-width/.test(v.content)) out.push('viewport must contain width=device-width');
  else if (/user-scalable\s*=\s*(no|0)|maximum-scale\s*=\s*1(\.0)?\b/.test(v.content)) out.push('viewport blocks pinch zoom');
  if (!document.documentElement.getAttribute('lang')) out.push('<html> has no lang attribute');
  if (document.compatMode !== 'CSS1Compat') out.push('missing <!doctype html> (quirks mode)');
  if (!document.querySelector('meta[charset]')) out.push('missing <meta charset>');
  return out; })()); } },
    { id: 'seo-meta', sev: 'error', f: function () { return ((function () { var out = [], t = document.title.trim(), d = document.querySelector('meta[name=description]');
  if (t.length < 10 || t.length > 60) out.push('title is ' + t.length + ' characters (need 10-60)');
  if (!d || !d.content.trim()) out.push('missing meta description');
  else if (d.content.length < 50 || d.content.length > 160) out.push('meta description is ' + d.content.length + ' characters (need 50-160)');
  return out; })()); } },
    { id: 'headings', sev: 'error', f: function () { return ((function () { var out = [], h = __H.$('h1,h2,h3,h4,h5,h6').filter(function (e) { return __H.vis(e); }),
  n = h.filter(function (e) { return e.tagName === 'H1'; }).length, last = 0;
  if (n !== 1) out.push('found ' + n + ' visible h1 (need exactly 1)');
  h.forEach(function (e) { var l = +e.tagName[1];
    if (last && l > last + 1) out.push('heading jumps from h' + last + ' to h' + l + ': "' + e.textContent.trim().slice(0, 30) + '"');
    if (!e.textContent.trim()) out.push('empty ' + e.tagName.toLowerCase());
    last = l; });
  return out; })()); } },
    { id: 'horizontal-overflow-390', sev: 'error', f: function () { return ((function () { var w = document.documentElement.clientWidth, out = [];
  if (innerWidth > 400) out.push('run this check in a 390px wide viewport (now ' + innerWidth + ')');
  if (document.documentElement.scrollWidth > w + 1) out.push('page scrolls sideways: scrollWidth ' + document.documentElement.scrollWidth + ' > ' + w);
  __H.$('body *').forEach(function (e) {
    if (out.length > 12 || !__H.vis(e) || e.closest('.marquee,[data-allow-overflow]') || __H.clipped(e)) return;
    var r = e.getBoundingClientRect();
    if (r.right <= 0) return;
    if (r.right > w + 1 || r.left < -1) out.push(e.tagName.toLowerCase() + (__H.cn(e) ? '.' + __H.cn(e).split(' ')[0] : '') + ' sticks out by ' + Math.round(Math.max(r.right - w, -r.left)) + 'px');
  });
  return out; })()); } },
    { id: 'text-contrast-aa', sev: 'error', f: function () { return ((function () { var H = __H, out = [];
  H.$('body *').forEach(function (e) {
    if (/^(SCRIPT|STYLE|NOSCRIPT|SVG|PATH|TITLE)$/i.test(e.tagName) || e.closest('svg') || !H.hasText(e) || !H.vis(e)) return;
    var s = getComputedStyle(e), f = H.rgba(s.color), b = H.bg(e);
    if (!f || !b) return;
    var fg = f[3] < 1 ? H.over(f, b) : f, fs = parseFloat(s.fontSize),
      big = fs >= 24 || (fs >= 18.66 && parseInt(s.fontWeight, 10) >= 700), need = big ? 3 : 4.5, r = H.cr(fg, b);
    if (r < need) out.push(e.tagName.toLowerCase() + ' "' + e.textContent.trim().slice(0, 30) + '" ratio ' + r.toFixed(2) + ' needs ' + need);
  });
  return out.slice(0, 20); })()); } },
    { id: 'tap-target-size', sev: 'error', f: function () { return ((function () { var H = __H, out = [];
  H.$('a[href],button,input:not([type=hidden]),select,textarea,summary,[role=button],[role=link]').forEach(function (e) {
    if (!H.vis(e)) return;
    var r = e.getBoundingClientRect(), s = getComputedStyle(e);
    if (s.display === 'inline' && e.parentNode && [].some.call(e.parentNode.childNodes, function (n) { return n.nodeType === 3 && n.nodeValue.trim().length > 2; })) return;
    if (r.width < 44 || r.height < 44) out.push(e.tagName.toLowerCase() + ' "' + (e.textContent || e.getAttribute('aria-label') || e.name || '').trim().slice(0, 24) + '" is ' + Math.round(r.width) + 'x' + Math.round(r.height));
  });
  return out.slice(0, 20); })()); } },
    { id: 'img-alt', sev: 'error', f: function () { return ((function () { var out = [];
  __H.$('img').forEach(function (i) { var a = i.getAttribute('alt'), src = (i.getAttribute('src') || '').slice(-40);
    if (a === null) out.push('missing alt: ' + src);
    else if (a !== '' && (/\.(jpe?g|png|webp|gif|svg|avif)$/i.test(a.trim()) || /^(image|photo|picture|img|logo|banner|hero)\d*$/i.test(a.trim()))) out.push('useless alt "' + a + '": ' + src);
    else if (a === '' && i.getBoundingClientRect().width > 200 && !i.closest('[aria-hidden=true]')) out.push('WARN large image with empty alt, confirm it is decorative: ' + src);
  });
  __H.$('svg[role=img]:not([aria-label]):not([aria-labelledby])').forEach(function () { out.push('svg role=img without a label'); });
  return out; })()); } },
    { id: 'img-dimensions', sev: 'warn', f: function () { return ((function () { var out = [];
  __H.$('img').forEach(function (i) { var src = (i.getAttribute('src') || '').slice(-40);
    if (!i.getAttribute('width') || !i.getAttribute('height')) out.push('no width/height attributes: ' + src);
    if (i.getBoundingClientRect().top + pageYOffset > innerHeight * 1.5 && i.getAttribute('loading') !== 'lazy') out.push('WARN below the fold without loading="lazy": ' + src);
  });
  return out; })()); } },
    { id: 'placeholder-text', sev: 'error', f: function () { return ((function () { var d = document.querySelector('meta[name=description]'),
  t = document.body.innerText + ' ' + document.title + ' ' + (d ? d.content : ''),
  re = /lorem ipsum|dolor sit amet|consectetur|your (business|company|name|text|title|address|phone) here|insert (text|image|photo|logo)|(business|company) name here|\[[A-Za-z ]{3,30}\]|\{\{[^}]*\}\}|\bTODO\b|\bTBD\b|\bXX+\b|click here to edit|coming soon|example\.com|\b555[-. ]?01\d\d\b|\(555\)|000[-. ]?0000|123 (main|street)|john doe|jane doe/i,
  m = t.match(re), out = m ? ['placeholder text found: "' + m[0] + '"'] : [];
  __H.$('a[href]').forEach(function (a) { var h = a.getAttribute('href');
    if (h === '#' || h === '' || /example\.(com|org)/i.test(h)) out.push('placeholder link href="' + h + '" on "' + a.textContent.trim().slice(0, 24) + '"'); });
  return out; })()); } },
    { id: 'emoji-as-icons', sev: 'error', f: function () { return ((function () { var out = [], re = /[\p{Emoji_Presentation}️★☆✓✔✕✖❤☎✆]/u,
  w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT), n, m;
  while ((n = w.nextNode())) { if (/^(SCRIPT|STYLE|NOSCRIPT)$/.test(n.parentNode.tagName)) continue;
    m = n.nodeValue.match(re); if (m) out.push('emoji/symbol "' + m[0] + '" in <' + n.parentNode.tagName.toLowerCase() + '>'); }
  [['title', 'textContent'], ['meta[name=description]', 'content']].forEach(function (p) { var e = document.querySelector(p[0]); if (e && re.test(e[p[1]] || '')) out.push('emoji in ' + p[0]); });
  return out.slice(0, 15); })()); } },
    { id: 'fonts-allowed', sev: 'error', f: function () { return ((function () { var ok = ['oswald', 'inter', 'barlow condensed', 'barlow', 'bebas neue', 'archivo', 'archivo narrow', 'playfair display', 'source sans 3', 'space grotesk', 'dm sans', 'sora', 'fraunces', 'work sans', 'dm serif display', 'space mono'],
  gen = ['system-ui', 'sans-serif', 'serif', 'monospace', '-apple-system', 'ui-monospace', 'ui-sans-serif', 'ui-serif'], used = {}, bad = {}, out = [];
  __H.$('body, body *').forEach(function (e) {
    if (/^(SCRIPT|STYLE|NOSCRIPT|SVG|PATH|BR|IMG)$/i.test(e.tagName) || e.closest('svg') || !__H.hasText(e) || !__H.vis(e)) return;
    var f = getComputedStyle(e).fontFamily.split(',')[0].replace(/["']/g, '').trim().toLowerCase();
    used[f] = 1; if (ok.indexOf(f) < 0 && gen.indexOf(f) < 0) bad[f] = 1; });
  Object.keys(bad).forEach(function (f) { out.push('font "' + f + '" is not bundled (see fonts.css)'); });
  var names = Object.keys(used).filter(function (f) { return gen.indexOf(f) < 0; }), sys = Object.keys(used).filter(function (f) { return gen.indexOf(f) >= 0; });
  if (names.length > 2) out.push('more than two typefaces in use: ' + names.join(', '));
  if (sys.length && names.length) out.push('WARN some text uses a system font (' + sys.join(', ') + ') instead of the pairing');
  return out; })()); } },
    { id: 'repeated-card-groups', sev: 'error', f: function () { return ((function () { var H = __H, groups = {}, out = [];
  H.$('body *').forEach(function (p) {
    var k = [].filter.call(p.children, function (c) { return H.vis(c) && c.getBoundingClientRect().height > 40; });
    if (k.length < 3) return;
    var s = H.sig(k[0]);
    if (!k.every(function (c) { return H.sig(c) === s; }) || !k.some(function (c) { return c.querySelector('h2,h3,h4,svg,img'); })) return;
    (groups[s] = groups[s] || []).push(p.tagName.toLowerCase() + (H.cn(p) ? '.' + H.cn(p).split(' ')[0] : '') + ' x' + k.length); });
  Object.keys(groups).forEach(function (s) { if (groups[s].length >= 3) out.push('same card pattern used ' + groups[s].length + ' times: ' + groups[s].join(' | ')); });
  return out; })()); } },
    { id: 'centered-hero-over-three-cards', sev: 'error', f: function () { return ((function () { var H = __H, h1 = document.querySelector('h1');
  if (!h1 || getComputedStyle(h1).textAlign !== 'center') return [];
  var hero = h1.closest('section,header,[class*=hero]') || h1.parentElement, nx = hero.nextElementSibling, i = 0;
  while (nx && i < 2) {
    var hit = H.$('*', nx).concat([nx]).some(function (p) { var k = [].filter.call(p.children, function (c) { return H.vis(c); });
      return k.length === 3 && k.every(function (c) { return H.sig(c) === H.sig(k[0]); }) && !!k[0].querySelector('h2,h3,h4,svg,img'); });
    if (hit) return ['centered hero followed by a row of three identical cards'];
    nx = nx.nextElementSibling; i++; }
  return []; })()); } },
    { id: 'broken-images', sev: 'error', f: function () { return ((function () { var out = [];
  __H.$('img').forEach(function (i) { var s = i.getAttribute('src') || '';
    if (!s) out.push('img without src');
    else if (i.complete && i.naturalWidth === 0 && !/^data:image\/svg/.test(s)) out.push('image failed to load: ' + s.slice(-50)); });
  return out; })()); } },
    { id: 'broken-anchors', sev: 'error', f: function () { return ((function () { var out = [];
  __H.$('a[href^="#"]').forEach(function (a) { var h = a.getAttribute('href'); if (h.length < 2 || h.indexOf('#/') === 0) return; var id = decodeURIComponent(h.slice(1));
    if (!document.getElementById(id) && !document.getElementsByName(id).length) out.push('anchor ' + h + ' has no target'); });
  __H.$('a[href=""]').forEach(function (a) { out.push('empty href: "' + a.textContent.trim().slice(0, 24) + '"'); });
  __H.$('a:not([href])').forEach(function (a) { if (!a.getAttribute('onclick') && !a.getAttribute('role') && !a.name && !a.id) out.push('link without href: "' + a.textContent.trim().slice(0, 24) + '"'); });
  return out; })()); } },
    { id: 'broken-local-files', sev: 'error', f: function () { return ((function () { var seen = {}, list = [];
  __H.$('a[href],link[href],script[src],img[src],source[src],video[src],audio[src]').forEach(function (e) {
    var u = e.getAttribute('href') || e.getAttribute('src');
    if (!u || /^(#|mailto:|tel:|sms:|data:|javascript:|https?:|\/\/)/i.test(u)) return;
    var a = new URL(u, location.href); if (a.origin !== location.origin) return; a.hash = '';
    if (!seen[a.href]) { seen[a.href] = 1; list.push(a.href); } });
  return Promise.all(list.map(function (u) { return fetch(u).then(function (r) { return r.ok ? null : 'HTTP ' + r.status + ' ' + u.replace(location.origin, ''); }).catch(function () { return 'unreachable ' + u.replace(location.origin, ''); }); }))
    .then(function (r) { return r.filter(Boolean); }); })()); } },
    { id: 'console-errors', sev: 'error', f: function () { return ((function () { return (window.__lintErrs || ['__lintErrs hook missing: inject the PREAMBLE at document start']).slice(0, 15); })()); } },
    { id: 'external-requests', sev: 'error', f: function () { return ((function () { var out = [], o = location.origin;
  performance.getEntriesByType('resource').forEach(function (r) { try { if (!/^data:/.test(r.name) && new URL(r.name).origin !== o) out.push('network request to ' + r.name.slice(0, 80)); } catch (e) {} });
  __H.$('script[src],link[rel=stylesheet][href],link[rel=preload][href],img[src],source[src],iframe[src],video[src],audio[src]').forEach(function (e) {
    var u = e.getAttribute('src') || e.getAttribute('href');
    if (/^(https?:)?\/\//i.test(u) && new URL(u, location.href).origin !== o) out.push(e.tagName.toLowerCase() + ' loads ' + u.slice(0, 80)); });
  return out.filter(function (v, i, a) { return a.indexOf(v) === i; }).slice(0, 15); })()); } },
    { id: 'tel-link-above-fold', sev: 'error', f: function () { return ((function () { var t = __H.$('a[href^="tel:"]').filter(function (a) { return __H.vis(a); }), out = [];
  if (!t.length) return ['no visible tel: link on the page'];
  if (!t.some(function (a) { return a.getBoundingClientRect().top + pageYOffset < innerHeight; })) out.push('no tel: link above the fold (first ' + innerHeight + 'px)');
  t.forEach(function (a) { if (!/^tel:\+?[0-9][0-9 ().\-]{6,}$/.test(a.getAttribute('href'))) out.push('malformed ' + a.getAttribute('href')); });
  return out; })()); } },
    { id: 'json-ld-localbusiness', sev: 'error', f: function () { return ((function () { var out = [], s = __H.$('script[type="application/ld+json"]'), ok = false,
  types = /^(LocalBusiness|AutoRepair|AutoBodyShop|AutoDealer|AutoPartsStore|AutoWash|GasStation|MotorcycleRepair|TireShop|Plumber|Electrician|HVACBusiness|RoofingContractor|GeneralContractor|HomeAndConstructionBusiness|Locksmith|MovingCompany|HousePainter|Store|Restaurant|FoodEstablishment|HealthAndBeautyBusiness|BeautySalon|HairSalon|DaySpa|Dentist|MedicalBusiness|ProfessionalService|LegalService|AccountingService|RealEstateAgent|PetStore|Florist|Bakery|CafeOrCoffeeShop|BarOrPub|LodgingBusiness|SportsActivityLocation|ChildCare|DryCleaningOrLaundry|EntertainmentBusiness|FinancialService|InsuranceAgency)$/;
  if (!s.length) return ['no JSON-LD block (script type="application/ld+json")'];
  s.forEach(function (x) { try { var j = JSON.parse(x.textContent);
    (j['@graph'] || [j]).forEach(function (n) { if ([].concat(n['@type'] || []).some(function (v) { return types.test(v); })) { ok = true;
      ['name', 'telephone'].forEach(function (k) { if (!n[k]) out.push('JSON-LD missing ' + k); });
      if (!n.address && !n.areaServed) out.push('JSON-LD missing address or areaServed'); } });
  } catch (e) { out.push('JSON-LD does not parse: ' + e.message); } });
  if (!ok && !out.length) out.push('JSON-LD has no LocalBusiness-family @type');
  return out; })()); } },
    { id: 'sections-max-6', sev: 'error', f: function () { return ((function () { var m = document.querySelector('main') || document.body,
  n = [].filter.call(m.children, function (c) { return /^(SECTION|ARTICLE)$/.test(c.tagName) || (c.tagName === 'DIV' && !!c.querySelector('h2')); }).length;
  return n > 6 ? ['page has ' + n + ' sections (max 6)'] : []; })()); } },
    { id: 'css-safety-nets', sev: 'error', f: function () { return ((function () { var out = [], rules = [];
  [].forEach.call(document.styleSheets, function (s) { try { [].forEach.call(s.cssRules, function f(r) { rules.push(r); if (r.cssRules) [].forEach.call(r.cssRules, f); }); } catch (e) {} });
  var txt = rules.map(function (r) { return r.cssText; }).join('\n');
  if (!/:focus-visible|:focus\s*[{,]/.test(txt)) out.push('no :focus-visible styles (keyboard focus must be visible)');
  if (/outline(-style)?\s*:\s*(none|0)\b/.test(txt) && !/:focus-visible[^{]*\{[^}]*outline/.test(txt)) out.push('outline removed with no replacement focus style');
  if (!rules.some(function (r) { return r.media && /prefers-reduced-motion/.test(r.media.mediaText); })) out.push('no prefers-reduced-motion block (link motion.css)');
  return out; })()); } },
    { id: 'layout-property-animation', sev: 'warn', f: function () { return ((function () { var bad = /(^|,\s*)(all|width|height|top|left|right|bottom|margin[a-z-]*|padding[a-z-]*|font-size|line-height|border-width|max-height|min-height|flex[a-z-]*|gap)(\s*,|$)/, out = [], seen = {};
  __H.$('body, body *').forEach(function (e) { var s = getComputedStyle(e);
    if (/[1-9]/.test(s.transitionDuration) && bad.test(s.transitionProperty) && !seen[s.transitionProperty]) { seen[s.transitionProperty] = 1; out.push('transition on ' + s.transitionProperty + ' (' + e.tagName.toLowerCase() + ')'); } });
  [].forEach.call(document.styleSheets, function (sh) { try { [].forEach.call(sh.cssRules, function f(r) {
    if (r.type === 7) { var props = {}; [].forEach.call(r.cssRules, function (k) { [].forEach.call(k.style, function (p) { props[p] = 1; }); });
      var b = Object.keys(props).filter(function (p) { return !/^(transform|opacity|translate|scale|rotate|animation-timing-function|visibility)$/.test(p); });
      if (b.length) out.push('@keyframes ' + r.name + ' animates ' + b.join(', ')); }
    else if (r.cssRules) [].forEach.call(r.cssRules, f); }); } catch (e) {} });
  return out.slice(0, 12); })()); } },
    { id: 'invented-content-markers', sev: 'error', f: function () { return ((function () { var out = [], facts = window.__facts;
  __H.$('[class*=testimonial i],[id*=testimonial i],[class*=review i],[id*=review i],blockquote,[itemprop=review],[itemprop=aggregateRating]').forEach(function (e) {
    if (__H.vis(e)) out.push('WARN testimonial/review markup (' + e.tagName.toLowerCase() + '): only valid with a quote the owner supplied'); });
  __H.$('[data-count-to]').forEach(function (e) { var v = e.getAttribute('data-count-to');
    if (!facts) out.push('WARN counter ' + v + ' cannot be verified: set window.__facts to the numbers the owner gave');
    else if (facts.map(String).indexOf(String(v)) < 0) out.push('counter ' + v + ' is not one of the owner facts'); });
  var m = document.body.innerText.match(/\b(\d{1,3}(,\d{3})+|\d+)\+?\s*(happy|satisfied)\s+(customers|clients|drivers)|\b(award[- ]winning|#1|number one|best in (town|the city|\w+))\b|\b[45]\.\d\s*(\/\s*5|stars?)\b/i);
  if (m) out.push('unverifiable claim: "' + m[0] + '"');
  return out.slice(0, 12); })()); } },
    { id: 'accent-hue-count', sev: 'warn', f: function () { return ((function () { var H = __H, b = {};
  function hue(c) { var r = c[0] / 255, g = c[1] / 255, bl = c[2] / 255, mx = Math.max(r, g, bl), mn = Math.min(r, g, bl), d = mx - mn, l = (mx + mn) / 2;
    if (d < .12 || l < .12 || l > .9) return -1;
    if (d / (1 - Math.abs(2 * l - 1)) < .35) return -1;
    var h = mx === r ? ((g - bl) / d) % 6 : mx === g ? (bl - r) / d + 2 : (r - g) / d + 4; h = Math.round(h * 60); if (h < 0) h += 360; return Math.floor(h / 40) % 9; }
  H.$('body, body *').forEach(function (e) { if (!H.vis(e)) return; var s = getComputedStyle(e);
    [s.color, s.backgroundColor, s.borderTopColor].forEach(function (v, i) { var c = H.rgba(v); if (!c || c[3] < .5) return; if (i === 2 && parseFloat(s.borderTopWidth) < 1) return; var h = hue(c); if (h >= 0) b[h] = (b[h] || 0) + 1; }); });
  var k = Object.keys(b).filter(function (h) { return b[h] >= 3; }).map(Number).sort(function (x, y) { return x - y; }), clusters = 0;
  k.forEach(function (h, i) { if (!(i && h - k[i - 1] === 1)) clusters++; });
  if (k.length > 1 && k[0] === 0 && k[k.length - 1] === 8) clusters--;
  return clusters > 1 ? ['more than one accent colour family in use (' + clusters + ' hue groups)'] : []; })()); } }
  ], out = [], p, i;
  // The page says what it is (<html data-kind="local|site|app|game">): a tool or game is not a marketing page, so the local-business checks are skipped for it.
  var kind = (document.documentElement.getAttribute('data-kind') || (document.querySelector('meta[name=alpha-kind]') || {}).content || 'local').toLowerCase();
  var appChecks = ['seo-meta', 'tel-link-above-fold', 'json-ld-localbusiness', 'sections-max-6', 'repeated-card-groups', 'centered-hero-over-three-cards', 'invented-content-markers'];
  var SKIP = { app: appChecks, game: appChecks, site: ['tel-link-above-fold', 'json-ld-localbusiness', 'sections-max-6'] }[kind] || [];
  for (i = 0; i < R.length; i++) {
    if (SKIP.indexOf(R[i].id) >= 0) continue;
    try { p = await R[i].f(); } catch (e) { p = ['check crashed: ' + e.message]; }
    out.push({ id: R[i].id, sev: R[i].sev, problems: p });
  }
  return out;
};
