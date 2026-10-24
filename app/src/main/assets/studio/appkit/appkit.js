/*! appkit.js 1.0 | Alpha Studio app kit | own work, CC0; some icon paths adapted from Lucide (ISC, (c) Lucide Contributors)
 No dependencies, ES2019, offline, flat folder. Everything is on window.AK; reference: APPKIT.md */
(() => {
'use strict';
var AK = window.AK = {};
var D = document, R = D.documentElement, W = window;
AK.version = '1.0';

/* == core == */
var lis = (el, t, f, o) => el.addEventListener(t, f, o);
var box = e => e.getBoundingClientRect();
var toEl = x => typeof x === 'string' ? D.querySelector(x) : x;
var $$ = (s, r) => Array.from((r || D).querySelectorAll(s));
var within = (r, s) => (r.matches && r.matches(s) ? [r] : []).concat($$(s, r));
var ESC = {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'};
var esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ESC[c]);
function Safe(s) {this.s = s;}
Safe.prototype.toString = function () {return this.s;};
var sv = v => v instanceof Safe ? v.s : Array.isArray(v) ? v.map(sv).join('') : v == null || v === false ? '' : esc(v);
AK.esc = esc;
AK.raw = s => new Safe(String(s == null ? '' : s));
AK.html = (strs, ...vals) => new Safe(strs.reduce((o, s, i) => o + s + (i < vals.length ? sv(vals[i]) : ''), ''));
var idn = 0;
AK.id = () => Date.now().toString(36) + (idn++ % 1296).toString(36).padStart(2, '0') + Math.random().toString(36).slice(2, 6);
var frag = h => {var t = D.createElement('template'); t.innerHTML = h; return t.content;};
var still = () => R.classList.contains('shot') || R.classList.contains('ak-still') || W.matchMedia('(prefers-reduced-motion: reduce)').matches;
var ns = () => R.dataset.app || location.pathname || '/';
AK.still = () => still();
var ls = {
 get: k => {try {return localStorage.getItem(k);} catch (e) {return null;}},
 set: (k, v) => {try {localStorage.setItem(k, v); return true;} catch (e) {return false;}}
};
var r1 = n => Math.round(n * 10) / 10;
if (/[?&]shot\b/.test(location.search)) R.classList.add('ak-still');

var PROPS = {value: 1, checked: 1, selected: 1, disabled: 1, hidden: 1, multiple: 1, required: 1, readOnly: 1};
function add(e, kids) {
 [].concat(kids == null ? [] : kids).forEach(k => {
  if (k == null || k === false) return;
  if (Array.isArray(k)) return add(e, k);
  if (k.nodeType) e.append(k); else if (k instanceof Safe) e.append(frag(k.s)); else e.append(String(k));
 });
}
AK.el = (tag, attrs, kids) => {
 var e = D.createElement(tag);
 if (attrs == null || typeof attrs !== 'object' || Array.isArray(attrs) || attrs.nodeType || attrs instanceof Safe) {kids = attrs; attrs = {};}
 Object.keys(attrs).forEach(k => {
  var v = attrs[k];
  if (v == null || v === false) return;
  if (k === 'class') e.className = v;
  else if (k === 'style' && typeof v === 'object') Object.assign(e.style, v);
  else if (k === 'html') e.innerHTML = String(v);
  else if (k === 'text') e.textContent = v;
  else if (/^on/.test(k) && typeof v === 'function') lis(e, k.slice(2).toLowerCase(), v);
  else if (PROPS[k]) e[k] = v;
  else e.setAttribute(k, v === true ? '' : v);
 });
 add(e, kids);
 return e;
};
AK.render = (el, c) => {
 el = toEl(el); if (!el) return null;
 el._akKeys = null;
 if (c == null || typeof c === 'string' || typeof c === 'number' || c instanceof Safe) el.innerHTML = c == null ? '' : String(c);
 else {el.replaceChildren(); add(el, c);}
 enhance(el);
 return el;
};
AK.on = (root, type, sel, fn) => {
 root = toEl(root) || D;
 var h = e => {var t = e.target && e.target.closest ? e.target.closest(sel) : null; if (t && (root === D || root.contains(t))) fn.call(t, e, t);};
 lis(root, type, h);
 return () => root.removeEventListener(type, h);
};
var acts = {};
AK.act = (name, fn) => {if (typeof name === 'object') Object.assign(acts, name); else acts[name] = fn;};
/* keyed list view: re-renders only the rows whose HTML changed */
AK.list = (el, items, render, o) => {
 el = toEl(el); o = o || {};
 var key = o.key || 'id';
 if (!items || !items.length) {el._akKeys = null; el.innerHTML = o.empty == null ? '' : String(typeof o.empty === 'object' && !(o.empty instanceof Safe) ? AK.empty(o.empty) : o.empty); return el;}
 var first = !el._akKeys, old = el._akKeys || new Map(), next = new Map(), prev = null;
 if (first) el.replaceChildren();
 items.forEach((it, i) => {
  var k = String(it && it[key] != null ? it[key] : i), h = String(render(it, i)), n = old.get(k);
  if (!n || n._akHtml !== h) {
   var nn = frag(h).firstElementChild || D.createElement('div');
   nn._akHtml = h;
   if (!first && !n && !still()) nn.classList.add('ak-new');
   if (n) n.replaceWith(nn);
   n = nn;
  }
  var want = prev ? prev.nextSibling : el.firstChild;
  if (n !== want) el.insertBefore(n, want);
  next.set(k, n); prev = n;
 });
 old.forEach((n, k) => {if (!next.has(k)) n.remove();});
 while (prev && prev.nextSibling) prev.nextSibling.remove();
 el._akKeys = next;
 enhance(el);
 return el;
};

/* == icons (24px stroke, currentColor): AK.icons.name or AK.icon(name) == */
var C = (x, y, r) => 'M' + (x - r) + ' ' + y + 'a' + r + ' ' + r + ' 0 1 0 ' + 2 * r + ' 0a' + r + ' ' + r + ' 0 1 0 ' + -2 * r + ' 0';
var I = {
 plus: 'M12 5v14M5 12h14', minus: 'M5 12h14', x: 'M18 6 6 18M6 6l12 12', check: 'M20 6 9 17l-5-5',
 'check-circle': C(12, 12, 10) + 'M16 9l-5.5 5.5L8 12', 'x-circle': C(12, 12, 10) + 'M15 9l-6 6M9 9l6 6',
 trash: 'M3 6h18M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6M10 11v6M14 11v6',
 edit: 'M12 20h9M16.4 3.6a2.1 2.1 0 1 1 3 3L7 19l-4 1 1-4z', copy: 'M10 8h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-8a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2zM4 16V6a2 2 0 0 1 2-2h10',
 search: 'M21 21l-4.3-4.3' + C(11, 11, 7.5), filter: 'M22 3H2l8 9.5V19l4 2v-8.5z', sort: 'M7 4v16M3 8l4-4 4 4M17 20V4M13 16l4 4 4-4',
 more: C(5, 12, 1) + C(12, 12, 1) + C(19, 12, 1), menu: 'M4 6h16M4 12h16M4 18h16',
 settings: 'M21 4h-7M10 4H3M21 12h-9M8 12H3M21 20h-5M12 20H3M14 2v4M8 10v4M16 18v4',
 home: 'M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8M3 10a2 2 0 0 1 .7-1.5l7-6a2 2 0 0 1 2.6 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z',
 list: 'M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01', grid: 'M3 3h7v7H3zM14 3h7v7h-7zM14 14h7v7h-7zM3 14h7v7H3z',
 calendar: 'M8 2v4M16 2v4M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zM3 10h18', clock: C(12, 12, 10) + 'M12 6v6l4 2',
 user: 'M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2' + C(12, 7, 4), users: 'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M22 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75' + C(9, 7, 4),
 phone: 'M13.8 16.6a1 1 0 0 0 1.2-.3l.4-.5A2 2 0 0 1 17 15h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2A18 18 0 0 1 2 4a2 2 0 0 1 2-2h3a2 2 0 0 1 2 2v3a2 2 0 0 1-.8 1.6l-.5.4a1 1 0 0 0-.3 1.2 14 14 0 0 0 6.4 6.4',
 message: 'M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z', mail: 'M4 4h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zM22 7l-10 6L2 7',
 'map-pin': 'M20 10c0 5-5.5 10.2-7.4 11.8a1 1 0 0 1-1.2 0C9.5 20.2 4 15 4 10a8 8 0 0 1 16 0' + C(12, 10, 3),
 camera: 'M14 4a2 2 0 0 1 1.76 1.05l.49.9A2 2 0 0 0 18 7h2a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2h2a2 2 0 0 0 1.76-1.05l.49-.9A2 2 0 0 1 10 4z' + C(12, 13, 3),
 image: 'M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zM21 15l-5-5L5 21' + C(9, 9, 2),
 file: 'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zM14 2v6h6', 'file-text': 'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zM14 2v6h6M16 13H8M16 17H8M10 9H8',
 clipboard: 'M9 2h6a1 1 0 0 1 1 1v2a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1zM16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2M9 14l2 2 4-4',
 download: 'M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3', upload: 'M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M17 8l-5-5-5 5M12 3v12',
 printer: 'M6 9V2h12v7M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2M6 14h12v8H6z', share: 'M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8M16 6l-4-4-4 4M12 2v13',
 link: 'M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7',
 chart: 'M3 3v16a2 2 0 0 0 2 2h16M8 17v-4M13 17V8M18 17v-7', 'trend-up': 'M22 7l-8.5 8.5-5-5L2 17M16 7h6v6', 'trend-down': 'M22 17l-8.5-8.5-5 5L2 7M16 17h6v-6',
 dollar: 'M12 2v20M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6',
 card: 'M4 5h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2zM2 10h20M6 15h3', receipt: 'M5 2h14v20l-3-2-2 2-2-2-2 2-2-2-3 2zM9 7h6M9 11h6M9 15h4',
 tag: 'M12.6 2.6A2 2 0 0 0 11.2 2H4a2 2 0 0 0-2 2v7.2a2 2 0 0 0 .6 1.4l8.7 8.7a2.4 2.4 0 0 0 3.4 0l6.6-6.6a2.4 2.4 0 0 0 0-3.4zM7.5 7.5h.01',
 box: 'M11 21.7a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16V8a2 2 0 0 0-1-1.7l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.7zM12 22V12M3.3 7 12 12l8.7-5M7.5 4.3l9 5.1',
 wrench: 'M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.1-3.1c.3-.3.9-.2 1 .2a6 6 0 0 1-8.3 7.1l-7.9 7.9a1 1 0 0 1-3-3l7.9-7.9a6 6 0 0 1 7.1-8.3c.4.1.5.7.2 1z',
 car: 'M19 17h2c.6 0 1-.4 1-1v-3c0-.9-.7-1.7-1.5-1.9C18.7 10.6 16 10 16 10s-1.3-1.4-2.2-2.3c-.5-.4-1.1-.7-1.8-.7H5c-.6 0-1.1.4-1.4.9l-1.4 2.9A3.7 3.7 0 0 0 2 12v4c0 .6.4 1 1 1h2M9 17h6' + C(7, 17, 2) + C(17, 17, 2),
 truck: 'M14 18V6a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v11a1 1 0 0 0 1 1h2M15 18H9M19 18h2a1 1 0 0 0 1-1v-3.6a1 1 0 0 0-.2-.6l-3.5-4.4A1 1 0 0 0 17.5 8H14' + C(17, 18, 2) + C(7, 18, 2),
 star: 'M12 2l3.1 6.3 6.9 1-5 4.9 1.2 6.8L12 17.8 5.8 21 7 14.2 2 9.3l6.9-1z', heart: 'M19 14c1.5-1.5 3-3.2 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.8 0-3 .5-4.5 2-1.5-1.5-2.7-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4 3 5.5l7 7z',
 bell: 'M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9M10.3 21a1.9 1.9 0 0 0 3.4 0', info: C(12, 12, 10) + 'M12 16v-4M12 8h.01',
 alert: 'M21.7 18l-8-14a2 2 0 0 0-3.5 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.7-3M12 9v4M12 17h.01', help: C(12, 12, 10) + 'M9.1 9a3 3 0 0 1 5.8 1c0 2-3 3-3 3M12 17h.01',
 lock: 'M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2zM7 11V7a5 5 0 0 1 10 0v4', eye: 'M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z' + C(12, 12, 3),
 'chevron-right': 'M9 18l6-6-6-6', 'chevron-left': 'M15 18l-6-6 6-6', 'chevron-down': 'M6 9l6 6 6-6', 'chevron-up': 'M18 15l-6-6-6 6',
 'arrow-right': 'M5 12h14M12 5l7 7-7 7', 'arrow-left': 'M19 12H5M12 19l-7-7 7-7', 'arrow-up': 'M12 19V5M5 12l7-7 7 7', 'arrow-down': 'M12 5v14M19 12l-7 7-7-7',
 refresh: 'M21 12a9 9 0 0 1-15.5 6.2L3 16M3 12a9 9 0 0 1 15.5-6.2L21 8M21 3v5h-5M3 21v-5h5', undo: 'M9 14L4 9l5-5M4 9h10.5a5.5 5.5 0 0 1 0 11H11',
 external: 'M15 3h6v6M10 14 21 3M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6',
 inbox: 'M22 12h-6l-2 3h-4l-2-3H2M5.5 5.1L2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.5-6.9A2 2 0 0 0 16.8 4H7.2a2 2 0 0 0-1.7 1.1z',
 folder: 'M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.7-.9L9.6 3.9A2 2 0 0 0 7.9 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2z',
 send: 'M14.5 21.7a.5.5 0 0 0 .9 0l6.5-19a.5.5 0 0 0-.6-.6l-19 6.5a.5.5 0 0 0 0 .9l7.9 3.2a2 2 0 0 1 1.1 1.1zM21.9 2.1 10.9 13.1',
 grip: C(9, 6, 1) + C(9, 12, 1) + C(9, 18, 1) + C(15, 6, 1) + C(15, 12, 1) + C(15, 18, 1),
 zap: 'M13 2 3 14h9l-1 8 10-12h-9z',
 store: 'M3 9l2-6h14l2 6M3 9h18v1a3 3 0 0 1-6 0 3 3 0 0 1-6 0 3 3 0 0 1-6 0zM5 12v9h14v-9M10 21v-5h4v5',
 timer: 'M10 2h4M12 14l3-3' + C(12, 14, 8)
};
var svg = (d, cls) => '<svg class="i' + (cls ? ' ' + cls : '') + '" viewBox="0 0 24 24" aria-hidden="true"><path d="' + d + '"/></svg>';
AK.icons = {};
Object.keys(I).forEach(k => {Object.defineProperty(AK.icons, k, {enumerable: true, get: () => new Safe(svg(I[k]))});});
AK.icon = (n, cls) => new Safe(svg(I[n] || I.info, cls));
AK.avatar = (name, cls) => {
 var p = String(name || '?').trim().split(/\s+/), t = (p[0] ? p[0][0] : '?') + (p.length > 1 ? p[p.length - 1][0] : '');
 return new Safe('<span class="avatar' + (cls ? ' ' + esc(cls) : '') + '" aria-hidden="true">' + esc(t.toUpperCase()) + '</span>');
};

/* == enhance (runs on new DOM) + global handlers == */
function stepBtn(d) {return AK.el('button', {type: 'button', 'data-step': d, 'aria-label': d > 0 ? 'Increase' : 'Decrease', html: svg(d > 0 ? I.plus : I.minus)});}
function enhance(root) {
 if (!root || !root.querySelectorAll) return;
 within(root, '.stepper').forEach(st => {
  if (st._ak) return; st._ak = 1;
  var inp = st.querySelector('input');
  if (inp && !st.querySelector('button')) {st.insertBefore(stepBtn(-1), inp); st.append(stepBtn(1));}
 });
 within(root, '.tabs').forEach(initTabs);
}
AK.enhance = enhance;
var tabList = t => {var l = t.querySelector('.tablist,[role=tablist]'); return l ? Array.from(l.children).filter(b => b.matches('button,a,[role=tab]')) : [];};
var tabPanels = t => Array.from(t.children).filter(p => p.matches('.panel,[role=tabpanel]'));
function initTabs(t) {
 if (t._ak) return;
 var tabs = tabList(t); if (!tabs.length) return;
 t._ak = 1;
 t.querySelector('.tablist,[role=tablist]').setAttribute('role', 'tablist');
 var ps = tabPanels(t);
 tabs.forEach((b, i) => {
  b.id = b.id || 'akt-' + AK.id(); b.setAttribute('role', 'tab'); if (b.tagName === 'BUTTON') b.type = 'button';
  var p = ps[i];
  if (p) {p.id = p.id || 'akp-' + AK.id(); p.setAttribute('role', 'tabpanel'); p.setAttribute('aria-labelledby', b.id); b.setAttribute('aria-controls', p.id);}
 });
 var s = tabs.findIndex(b => b.getAttribute('aria-selected') === 'true');
 selectTab(t, s < 0 ? 0 : s, false, true);
}
function selectTab(t, i, focus, quiet) {
 var tabs = tabList(t), ps = tabPanels(t);
 if (!tabs[i]) return;
 tabs.forEach((b, j) => {b.setAttribute('aria-selected', String(j === i)); b.tabIndex = j === i ? 0 : -1; if (ps[j]) ps[j].hidden = j !== i;});
 if (focus) tabs[i].focus();
 if (!quiet) t.dispatchEvent(new CustomEvent('tabchange', {bubbles: true, detail: {index: i, id: tabs[i].dataset.tab || tabs[i].id}}));
}
AK.tabs = (el, o) => {
 el = toEl(el); o = o || {}; initTabs(el);
 if (o.onChange) lis(el, 'tabchange', e => {if (e.target === el) o.onChange(e.detail.index, e.detail);});
 return {select: i => selectTab(el, i)};
};
AK.accordion = (el, o) => {el = toEl(el); if (!o || o.single !== false) el.setAttribute('data-single', ''); return el;};
lis(D, 'click', e => {
 var t = e.target; if (!t || !t.closest) return;
 var chip = t.closest('.chip[aria-pressed]');
 if (chip) {
  var g = chip.closest('[data-single]');
  if (g) $$('.chip[aria-pressed]', g).forEach(c => c.setAttribute('aria-pressed', String(c === chip)));
  else chip.setAttribute('aria-pressed', String(chip.getAttribute('aria-pressed') !== 'true'));
 }
 var st = t.closest('[data-step]');
 if (st && st.closest('.stepper')) {
  var inp = st.parentNode.querySelector('input');
  if (inp && !inp.disabled) {
   try {if (+st.dataset.step > 0) inp.stepUp(); else inp.stepDown();} catch (x) {inp.value = (+inp.value || 0) + (+st.dataset.step);}
   inp.dispatchEvent(new Event('input', {bubbles: true})); inp.dispatchEvent(new Event('change', {bubbles: true}));
  }
 }
 var tb = t.closest('[role=tab]');
 if (tb && tb.closest('.tabs')) {var ts = tb.closest('.tabs'); selectTab(ts, tabList(ts).indexOf(tb));}
 var cp = t.closest('[data-copy]');
 if (cp) AK.copy(cp.getAttribute('data-copy') || cp.textContent.trim());
}, true);
lis(D, 'click', e => {
 var a = e.target && e.target.closest ? e.target.closest('[data-act]') : null;
 if (a && acts[a.dataset.act] && !a.disabled) acts[a.dataset.act](a, e);
});
lis(D, 'keydown', e => {
 var tb = e.target.closest ? e.target.closest('[role=tab]') : null;
 if (tb && tb.closest('.tabs') && /^(ArrowLeft|ArrowRight|Home|End)$/.test(e.key)) {
  var t = tb.closest('.tabs'), tabs = tabList(t), i = tabs.indexOf(tb);
  i = e.key === 'Home' ? 0 : e.key === 'End' ? tabs.length - 1 : (i + (e.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length;
  selectTab(t, i, true); e.preventDefault();
 }
});
lis(D, 'toggle', e => {
 var d = e.target;
 if (d.matches && d.matches('details.acc') && d.open) {var g = d.closest('[data-single]'); if (g) $$('details.acc[open]', g).forEach(x => {if (x !== d) x.open = false;});}
}, true);
lis(D, 'focusout', e => {
 var t = e.target;
 if (t.matches && t.matches('input[data-money]') && t.value.trim() !== '') t.value = AK.parseMoney(t.value).toFixed(2);
});

/* == toast == */
var toastBox = null, TI = {ok: 'check-circle', bad: 'x-circle', warn: 'alert', info: 'info'};
AK.toast = (msg, o) => {
 o = typeof o === 'string' ? {type: o} : o || {};
 if (!toastBox || !toastBox.isConnected) {
  toastBox = AK.el('div', {class: 'toasts', role: 'status', 'aria-live': 'polite'});
  if (toastBox.showPopover) toastBox.setAttribute('popover', 'manual');
  D.body.append(toastBox);
 }
 if (toastBox.showPopover) {try {toastBox.hidePopover();} catch (e) {} try {toastBox.showPopover();} catch (e) {}}
 var act = o.undo ? {label: o.undoLabel || 'Undo', onClick: o.undo} : o.action, timer;
 var t = AK.el('div', {class: 'toast' + (o.type ? ' ' + o.type : '')}, [o.type && TI[o.type] ? AK.icon(TI[o.type]) : null, AK.el('span', {text: msg})]);
 var close = () => {clearTimeout(timer); if (!t.isConnected) return; if (still()) {t.remove(); return;} t.classList.add('out'); setTimeout(() => t.remove(), 180);};
 if (act) t.append(AK.el('button', {type: 'button', class: 't-act', text: act.label, onclick: () => {close(); if (act.onClick) act.onClick();}}));
 toastBox.append(t);
 while (toastBox.children.length > 3) toastBox.firstChild.remove();
 timer = setTimeout(close, o.duration || (act ? 6000 : 3200));
 return {close};
};

/* == dialogs: modal, sheet (bottom sheet on phones, side panel >= 900px), confirm, prompt == */
var layers = [], popIgnore = 0, popQueue = [];
lis(W, 'popstate', () => {
 if (popIgnore > 0) {popIgnore--; while (!popIgnore && popQueue.length) popQueue.shift()(); return;}
 var top = layers[layers.length - 1];
 if (top && top.pushed) {top.pushed = false; top.close(undefined, true);}
});
var settle = f => {if (popIgnore > 0) popQueue.push(f); else f();};
/* history steps run one at a time (a back() must land before the next push/back); closeAll = one step back for all dialogs */
var closeAll = () => {
 var n = 0;
 layers.slice().reverse().forEach(L => {if (L.pushed) {n++; L.pushed = false;} L.close(undefined, true);});
 if (n) settle(() => {popIgnore++; history.go(-n);});
};
AK.closeAll = closeAll;
function layer(kind, o) {
 o = o || {};
 var id = AK.id(), hasHead = kind === 'sheet' || (o.title != null && o.title !== '');
 var d = AK.el('dialog', {class: 'ak-dlg ' + kind + (o.class ? ' ' + o.class : ''), 'aria-labelledby': o.title ? 'h' + id : null, 'aria-label': o.title ? null : (o.label || 'Dialog')});
 d.innerHTML = (kind === 'sheet' ? '<div class="grab" aria-hidden="true"></div>' : '') +
  (hasHead ? '<header class="dlg-head"><h2 id="h' + id + '">' + esc(o.title || '') + '</h2><button type="button" class="btn icon ghost sm" data-close aria-label="Close">' + svg(I.x) + '</button></header>' : '') +
  '<div class="dlg-body" tabindex="-1"></div>' + (o.actions && o.actions.length ? '<footer class="dlg-foot"></footer>' : '');
 var body = d.querySelector('.dlg-body'), foot = d.querySelector('.dlg-foot'), resolve;
 if (o.body != null) {if (typeof o.body === 'string' || o.body instanceof Safe) body.innerHTML = String(o.body); else add(body, o.body);}
 var L = {el: d, body, pushed: false, closed: false, result: new Promise(r => resolve = r)};
 L.close = (v, fromPop) => {
  if (L.closed) return; L.closed = true;
  var i = layers.indexOf(L); if (i >= 0) layers.splice(i, 1);
  if (L.pushed && !fromPop) {L.pushed = false; settle(() => {popIgnore++; history.back();});}
  var fin = () => {try {d.close();} catch (e) {} d.remove(); if (!layers.length) R.classList.remove('ak-lock'); if (o.onClose) o.onClose(v); resolve(v);};
  if (still()) fin(); else {d.classList.add('closing'); setTimeout(fin, kind === 'sheet' ? 210 : 150);}
 };
 (o.actions || []).forEach(a => {
  var b = AK.el('button', {type: 'button', class: 'btn' + (a.primary ? ' primary' : '') + (a.danger ? ' danger' : '') + (a.ghost ? ' ghost' : '') + (a.class ? ' ' + a.class : '')}, [a.icon ? AK.icon(a.icon) : null, a.label]);
  lis(b, 'click', () => {
   if (!a.onClick) return L.close(a.value);
   var r = a.onClick(L, b), fin = v => {if (v !== false) L.close(a.value !== undefined ? a.value : v);};
   if (r && r.then) {b.setAttribute('aria-busy', 'true'); r.then(v => {b.removeAttribute('aria-busy'); fin(v);}, err => {b.removeAttribute('aria-busy'); AK.toast(err && err.message ? err.message : 'Something went wrong', 'bad');});}
   else fin(r);
  });
  foot.append(b);
 });
 var downOut = false;
 lis(d, 'pointerdown', e => downOut = e.target === d);
 lis(d, 'click', e => {
  var c = e.target.closest('[data-close]');
  if (c && d.contains(c)) L.close(c.value || undefined);
  else if (e.target === d && downOut && o.dismiss !== false) L.close();
 });
 lis(d, 'cancel', e => {e.preventDefault(); if (o.dismiss !== false) L.close();});
 lis(d, 'submit', e => {e.preventDefault(); var p = foot && foot.querySelector('.btn.primary,.btn.danger'); if (p) p.click();});
 D.body.append(d);
 d.showModal(); R.classList.add('ak-lock'); layers.push(L);
 settle(() => {if (!L.closed) try {history.pushState(Object.assign({}, history.state, {akLayer: id}), ''); L.pushed = true;} catch (e) {}});
 enhance(body);
 var f = o.focus ? body.querySelector(typeof o.focus === 'string' ? o.focus : 'input:not([type=hidden]),select,textarea') : null;
 (f || body).focus({preventScroll: true});
 if (kind === 'sheet') swipeClose(d, L);
 if (o.onOpen) o.onOpen(L);
 return L;
}
function swipeClose(d, L) {
 var y0 = null, dy = 0;
 var down = e => {if (W.innerWidth >= 900 || e.target.closest('button')) return; y0 = e.clientY; dy = 0; d.style.transition = 'none'; try {e.currentTarget.setPointerCapture(e.pointerId);} catch (x) {}};
 var move = e => {if (y0 == null) return; dy = Math.max(0, e.clientY - y0); d.style.transform = 'translateY(' + dy + 'px)';};
 var up = () => {if (y0 == null) return; y0 = null; if (dy > 90) {d.style.transition = ''; L.close(); return;} d.style.transition = 'transform .2s'; d.style.transform = '';};
 $$('.dlg-head,.grab', d).forEach(h => {lis(h, 'pointerdown', down); lis(h, 'pointermove', move); lis(h, 'pointerup', up); lis(h, 'pointercancel', up);});
}
AK.modal = o => layer('modal', o);
AK.sheet = o => layer('sheet', o);
AK.confirm = (title, o) => {
 o = o || {};
 return layer('modal', {class: 'alert', label: title, body: AK.html`<div class="alert-ico${o.danger ? ' bad' : ''}">${AK.icon(o.icon || (o.danger ? 'trash' : 'help'))}</div><h2>${title}</h2>${o.text ? AK.html`<p>${o.text}</p>` : ''}`,
  actions: [{label: o.cancel || 'Cancel', value: false}, {label: o.ok || 'OK', value: true, danger: !!o.danger, primary: !o.danger}]}).result.then(v => v === true);
};
AK.prompt = (title, o) => {
 o = typeof o === 'string' ? {value: o} : o || {};
 var fid = 'akq' + AK.id();
 var L = layer('modal', {title, focus: true,
  body: AK.html`<form class="form" novalidate>${o.text ? AK.html`<p class="muted">${o.text}</p>` : ''}<div class="field">${o.label ? AK.html`<label for="${fid}">${o.label}</label>` : ''}<input id="${fid}" name="v" type="${o.type || 'text'}" placeholder="${o.placeholder || ''}" aria-label="${o.label || title}"></div></form>`,
  actions: [{label: o.cancel || 'Cancel', value: null}, {label: o.ok || 'OK', primary: true, onClick: l => {
   var inp = l.body.querySelector('input'), v = inp.value.trim();
   if (o.required !== false && !v) {setErr(inp, 'Please fill this in'); inp.focus(); return false;}
   return o.type === 'number' ? +v : v;
  }}]});
 var inp = L.body.querySelector('input');
 inp.value = o.value == null ? '' : o.value;
 if (o.type === 'number') inp.setAttribute('inputmode', 'decimal');
 setTimeout(() => {try {inp.select();} catch (e) {}}, 30);
 return L.result.then(v => v == null ? null : v);
};

/* == store: persistent state with undo, array helpers, backup == */
var clone = v => v == null ? v : W.structuredClone ? structuredClone(v) : JSON.parse(JSON.stringify(v));
AK.store = (name, defaults) => {
 defaults = defaults || {};
 var key = 'ak:' + ns() + ':' + name, subs = new Set(), hist = [], warned = false;
 var persistent = ls.set(key + ':probe', '1');
 if (persistent) try {localStorage.removeItem(key + ':probe');} catch (e) {}
 var load = () => {
  var raw = ls.get(key), v = null;
  if (raw) try {v = JSON.parse(raw);} catch (e) {v = null;}
  if (!v || typeof v !== 'object') return clone(defaults);
  if (!Array.isArray(v) && !Array.isArray(defaults)) Object.keys(defaults).forEach(k => {if (!(k in v)) v[k] = clone(defaults[k]);});
  return v;
 };
 var state = load();
 var save = () => {
  if (!persistent) return;
  try {localStorage.setItem(key, JSON.stringify(state));}
  catch (e) {if (!warned) {warned = true; AK.toast(/quota/i.test(e.name + ' ' + e.message) ? 'Storage is full. Back up, then remove old photos.' : 'Could not save on this device.', {type: 'bad', duration: 7000});}}
 };
 var emit = meta => {subs.forEach(f => {try {f(state, meta);} catch (e) {console.error(e);}});};
 var commit = (next, meta) => {hist.push(state); if (hist.length > 30) hist.shift(); state = next; save(); emit(meta || {}); return state;};
 var parts = p => String(p).split('.');
 var getP = (o, p) => parts(p).reduce((a, k) => a == null ? undefined : a[k], o);
 var setP = (o, p, v) => {
  var ks = parts(p), root = Array.isArray(o) ? o.slice() : Object.assign({}, o), cur = root;
  ks.forEach((k, i) => {if (i === ks.length - 1) cur[k] = v; else {var nx = cur[k]; cur[k] = Array.isArray(nx) ? nx.slice() : Object.assign({}, nx); cur = cur[k];}});
  return root;
 };
 var arr = l => {var a = getP(state, l); return Array.isArray(a) ? a : [];};
 var same = (x, id) => x && String(x.id) === String(id);
 if (!persistent) setTimeout(() => {AK.toast('Private mode: changes last until you close this page.', {type: 'warn', duration: 6000});}, 600);
 var S = {
  key: key, persistent,
  get: p => p == null ? state : getP(state, p),
  set: (a, b) => {return commit(typeof a === 'string' ? setP(state, a, b) : Object.assign({}, state, a), {type: 'set'});},
  update: (a, b, c) => {
   if (typeof a === 'function') {var dr = clone(state), r = a(dr); return commit(r === undefined ? dr : r, {type: 'update'});}
   var item = null, next = arr(a).map(x => {if (same(x, b)) {item = Object.assign({}, x, typeof c === 'function' ? c(clone(x)) : c); return item;} return x;});
   if (!item) return null;
   commit(setP(state, a, next), {type: 'update', list: a, id: b, item});
   return item;
  },
  add: (list, item, o) => {
   item = Object.assign({id: AK.id()}, item); var a = arr(list);
   commit(setP(state, list, o && o.first ? [item].concat(a) : a.concat([item])), {type: 'add', list, id: item.id, item});
   return item;
  },
  remove: (list, id) => {
   var a = arr(list), item = a.find(x => same(x, id));
   if (!item) return null;
   commit(setP(state, list, a.filter(x => x !== item)), {type: 'remove', list, id, item});
   return item;
  },
  find: (list, id) => arr(list).find(x => same(x, id)) || null,
  subscribe: fn => {subs.add(fn); return () => subs.delete(fn);},
  undo: () => {if (!hist.length) return false; state = hist.pop(); save(); emit({type: 'undo'}); return true;},
  canUndo: () => hist.length > 0,
  reset: to => {return commit(clone(to || defaults), {type: 'reset'});},
  export: file => {return AK.download(file || name + '-backup-' + AK.today() + '.json', JSON.stringify({app: ns(), store: name, saved: new Date().toISOString(), data: state}, null, 2));},
  import: src => {
   return pickFile(src, '.json,application/json').then(f => f ? f.text() : null).then(t => {
    if (t == null) return null;
    var j; try {j = JSON.parse(t);} catch (e) {AK.toast('That file is not a backup.', 'bad'); return null;}
    var data = j && j.store && j.data ? j.data : j;
    if (!data || typeof data !== 'object') {AK.toast('That backup is empty.', 'bad'); return null;}
    commit(data, {type: 'import'});
    return state;
   });
  }
 };
 lis(W, 'storage', e => {if (e.key === key) {state = load(); emit({type: 'sync'});}});
 return S;
};
function pickFile(src, accept, opts) {
 return new Promise(res => {
  if (src instanceof Blob) return res(src);
  var inp = src && src.tagName === 'INPUT' && src.type === 'file' ? src : null;
  if (inp && inp.files && inp.files[0]) return res(inp.files[0]);
  var temp = !inp;
  if (temp) {
   inp = AK.el('input', {type: 'file', accept, class: 'sr-only', tabindex: '-1', 'aria-hidden': 'true'});
   if (opts && opts.camera) inp.setAttribute('capture', 'environment');
   (layers.length ? layers[layers.length - 1].el : D.body).append(inp);
  }
  var done = f => {if (temp) inp.remove(); res(f || null);};
  lis(inp, 'change', () => done(inp.files[0]), {once: true});
  lis(inp, 'cancel', () => done(null), {once: true});
  inp.click();
 });
}

/* == format, money, dates, forms == */
var LOC = () => AK.locale || navigator.language || 'en-US';
var nfs = {};
var NF = (k, o) => {k = LOC() + k; return nfs[k] || (nfs[k] = new Intl.NumberFormat(LOC(), o));};
var iso = d => d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
var toDate = d => {
 if (d instanceof Date) return d;
 if (d == null || d === '') return null;
 if (typeof d === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(d)) {var p = d.split('-'); return new Date(+p[0], p[1] - 1, +p[2]);}
 if (typeof d === 'string' && /^\d{1,2}:\d{2}/.test(d)) {var t = d.split(':'), n = new Date(); n.setHours(+t[0], +t[1], 0, 0); return n;}
 return new Date(d);
};
var dayDiff = d => {var n = new Date(); return Math.round((new Date(d.getFullYear(), d.getMonth(), d.getDate()) - new Date(n.getFullYear(), n.getMonth(), n.getDate())) / 864e5);};
AK.today = plusDays => {var d = new Date(); if (plusDays) d.setDate(d.getDate() + plusDays); return iso(d);};
AK.currency = 'USD';
var DF = {short: {month: 'numeric', day: 'numeric', year: '2-digit'}, long: {weekday: 'long', month: 'long', day: 'numeric'}, full: {weekday: 'long', year: 'numeric', month: 'long', day: 'numeric'}, day: {month: 'short', day: 'numeric'}};
AK.fmt = {
 money: (n, cur, dec) => {
  if (typeof cur === 'number') {dec = cur; cur = null;}
  cur = cur || AK.currency || 'USD'; var dd = dec == null ? 2 : dec;
  try {return NF('m' + cur + dd, {style: 'currency', currency: cur, minimumFractionDigits: dd, maximumFractionDigits: dd}).format(+n || 0);} catch (e) {return cur + ' ' + (+n || 0).toFixed(dd);}
 },
 number: (n, d) => {return NF('n' + d, d == null ? {maximumFractionDigits: 2} : {minimumFractionDigits: d, maximumFractionDigits: d}).format(+n || 0);},
 compact: n => {return NF('c', {notation: 'compact', maximumFractionDigits: 1}).format(+n || 0);},
 percent: (n, d) => {return NF('p' + (d || 0), {style: 'percent', maximumFractionDigits: d || 0}).format(+n || 0);},
 date: (d, style) => {d = toDate(d); return !d || isNaN(d) ? '' : d.toLocaleDateString(LOC(), DF[style] || {month: 'short', day: 'numeric', year: 'numeric'});},
 time: d => {d = toDate(d); return !d || isNaN(d) ? '' : d.toLocaleTimeString(LOC(), {hour: 'numeric', minute: '2-digit'});},
 day: d => {
  d = toDate(d); if (!d || isNaN(d)) return '';
  var n = dayDiff(d);
  if (n === 0) return 'Today'; if (n === 1) return 'Tomorrow'; if (n === -1) return 'Yesterday';
  if (n > 1 && n < 7) return d.toLocaleDateString(LOC(), {weekday: 'long'});
  return d.toLocaleDateString(LOC(), d.getFullYear() === new Date().getFullYear() ? {month: 'short', day: 'numeric'} : {month: 'short', day: 'numeric', year: 'numeric'});
 },
 ago: d => {
  d = toDate(d); if (!d || isNaN(d)) return '';
  var s = (d - Date.now()) / 1000, rtf = new Intl.RelativeTimeFormat(LOC(), {numeric: 'auto'}), n = dayDiff(d);
  if (d.getHours() + d.getMinutes() === 0 && Math.abs(n) < 7) return rtf.format(n, 'day');
  var u = [['year', 31536000], ['month', 2592000], ['week', 604800], ['day', 86400], ['hour', 3600], ['minute', 60]];
  for (var i = 0; i < u.length; i++) if (Math.abs(s) >= u[i][1] || i === u.length - 1) return rtf.format(Math.round(s / u[i][1]), u[i][0]);
 }
};
AK.parseMoney = v => {
 if (typeof v === 'number') return v;
 var raw = String(v == null ? '' : v), s = raw.replace(/[^\d.,]/g, '');
 if (!s) return 0;
 var lc = s.lastIndexOf(','), ld = s.lastIndexOf('.');
 if (lc > -1 && ld > -1) s = lc > ld ? s.replace(/\./g, '').replace(',', '.') : s.replace(/,/g, '');
 else if (lc > -1) s = /^[^,]*,\d{1,2}$/.test(s) ? s.replace(',', '.') : s.replace(/,/g, '');
 else if ((s.match(/\./g) || []).length > 1) s = s.replace(/\./g, '');
 var n = parseFloat(s);
 return isFinite(n) ? (/-|\(/.test(raw) ? -n : n) : 0;
};
AK.values = form => {
 form = toEl(form); var o = {};
 Array.from(form.elements).forEach(e => {
  if (!e.name || e.disabled || /^(submit|button|reset|file|fieldset)$/.test(e.type)) return;
  if (e.type === 'checkbox') {
   if (form.querySelectorAll('input[type=checkbox][name="' + CSS.escape(e.name) + '"]').length > 1) {o[e.name] = o[e.name] || []; if (e.checked) o[e.name].push(e.value);}
   else o[e.name] = e.checked;
  } else if (e.type === 'radio') {if (e.checked) o[e.name] = e.value; else if (!(e.name in o)) o[e.name] = null;}
  else if (e.type === 'number' || e.type === 'range') o[e.name] = e.value === '' ? null : +e.value;
  else if (e.hasAttribute('data-money')) o[e.name] = e.value.trim() === '' ? null : AK.parseMoney(e.value);
  else if (e.multiple) o[e.name] = Array.from(e.selectedOptions).map(x => x.value);
  else o[e.name] = e.value;
 });
 return o;
};
AK.fill = (form, data) => {
 form = toEl(form); data = data || {};
 Array.from(form.elements).forEach(e => {
  if (!e.name || !(e.name in data)) return;
  var v = data[e.name];
  if (e.type === 'checkbox') e.checked = Array.isArray(v) ? v.indexOf(e.value) > -1 : !!v;
  else if (e.type === 'radio') e.checked = String(v) === e.value;
  else if (e.hasAttribute('data-money') && v != null && v !== '') e.value = (+v).toFixed(2);
  else e.value = v == null ? '' : v;
 });
 return form;
};
function setErr(el, msg) {
 var f = el.closest('.field') || el.parentElement, er = f.querySelector(':scope > .error');
 if (!er && msg) {er = AK.el('p', {class: 'error', id: 'ake' + AK.id()}); f.append(er);}
 if (er) {er.textContent = msg || ''; var ids = (el.getAttribute('aria-describedby') || '').split(' ').filter(x => x && x !== er.id); if (msg) ids.push(er.id); el.setAttribute('aria-describedby', ids.join(' '));}
 f.classList.toggle('invalid', !!msg);
 if (msg) el.setAttribute('aria-invalid', 'true'); else el.removeAttribute('aria-invalid');
}
AK.validate = (form, rules) => {
 form = toEl(form); rules = rules || {};
 var values = AK.values(form), errors = {}, firstEl = null;
 var names = Array.from(form.elements).filter(e => e.name && !e.disabled).map(e => e.name).concat(Object.keys(rules));
 names.filter((n, i) => names.indexOf(n) === i).forEach(n => {
  var list = form.elements.namedItem(n); if (!list) return;
  var el = list.tagName ? list : list[0], r = rules[n] || {}, v = values[n], msg = '';
  var raw = el.type === 'checkbox' ? (Array.isArray(v) ? v.length : el.checked) : el.type === 'radio' ? v : String(el.value == null ? '' : el.value).trim();
  var empty = raw === '' || raw === false || raw === 0 && el.type === 'checkbox' || raw == null;
  if (empty) {if (r.required || el.required) msg = el.type === 'checkbox' ? 'Please tick this' : el.type === 'radio' || el.tagName === 'SELECT' ? 'Please choose one' : 'Please fill this in';}
  else {
   var num = typeof v === 'number' ? v : AK.parseMoney(v);
   var min = r.min != null ? r.min : el.min !== '' && el.min != null && /number|range/.test(el.type) ? +el.min : null;
   var max = r.max != null ? r.max : el.max !== '' && el.max != null && /number|range/.test(el.type) ? +el.max : null;
   var pat = r.pattern || el.getAttribute('pattern');
   if ((r.email || el.type === 'email') && !/^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(raw)) msg = 'Enter a full email address';
   else if ((r.phone || el.type === 'tel') && String(raw).replace(/\D/g, '').length < 7) msg = 'Enter a phone number';
   else if ((r.number || r.money || el.type === 'number' || el.hasAttribute('data-money')) && !/\d/.test(String(raw))) msg = 'Enter a number';
   else if (min != null && num < min) msg = 'Must be ' + min + ' or more';
   else if (max != null && num > max) msg = 'Must be ' + max + ' or less';
   else if (r.minLength && String(raw).length < r.minLength) msg = 'Use at least ' + r.minLength + ' characters';
   else if (pat && !new RegExp('^(?:' + pat + ')$').test(raw)) msg = 'Check the format';
   if (!msg && r.test) {var t = r.test(v, values); if (t !== true && t !== undefined && t !== null) msg = typeof t === 'string' ? t : 'Check this';}
  }
  if (msg && r.message) msg = r.message;
  setErr(el, msg);
  if (msg) {errors[n] = msg; if (!firstEl) firstEl = el;}
 });
 if (firstEl) {firstEl.focus({preventScroll: true}); firstEl.scrollIntoView({block: 'center', behavior: still() ? 'auto' : 'smooth'});}
 if (!form._akVal) {form._akVal = 1; lis(form, 'input', e => {if (e.target.name && e.target.getAttribute('aria-invalid') === 'true') setErr(e.target, '');}); lis(form, 'change', e => {if (e.target.name && e.target.closest('.field.invalid')) setErr(e.target, '');});}
 return {ok: !firstEl, values, errors};
};
AK.setError = setErr;

/* == table + csv == */
var cmp = (a, b) => {
 var ea = a == null || a === '', eb = b == null || b === '';
 if (ea || eb) return ea && eb ? 0 : ea ? 1 : -1;
 if (typeof a === 'number' && typeof b === 'number') return a - b;
 return String(a).localeCompare(String(b), undefined, {numeric: true, sensitivity: 'base'});
};
AK.table = (el, o) => {
 el = toEl(el);
 if (el._akT) {el._akT.set(o); return el._akT.api;}
 var st = {o, q: '', sk: o.sort ? o.sort.key : null, sd: o.sort && o.sort.dir === 'desc' ? 'desc' : 'asc'};
 var cols = () => {return st.o.columns.map(c => {return typeof c === 'string' ? {key: c, label: c} : c;});};
 var val = (c, r) => c.value ? c.value(r) : r[c.key];
 var cell = (c, r, plain) => {
  if (c.html && !plain) return String(c.html(r));
  var v = val(c, r), f = c.format, t = typeof f === 'function' ? f(v, r) : f === 'money' ? AK.fmt.money(v) : f === 'date' ? AK.fmt.date(v) : f === 'number' ? AK.fmt.number(v) : v;
  return plain ? String(t == null ? '' : t) : esc(t);
 };
 el.classList.add('table-wrap');
 el.innerHTML = '<div class="table-tools"></div><div class="table-body"></div>';
 var tools = el.firstChild, bodyEl = el.lastChild, search = o.search && o.search.nodeType ? o.search : null, sel = null;
 if (o.search === true) {tools.insertAdjacentHTML('beforeend', '<div class="search"><input type="search" placeholder="' + esc(o.placeholder || 'Search') + '" aria-label="' + esc(o.placeholder || 'Search') + '"></div>'); search = tools.querySelector('input');}
 if (search) lis(search, 'input', () => {st.q = search.value.trim(); draw();});
 var sortable = cols().filter(c => c.sort !== false);
 if (sortable.length) {
  tools.insertAdjacentHTML('beforeend', '<select class="sort-phone" aria-label="Sort by"><option value="">Sort by</option>' + sortable.map(c => {
   var w = c.num || c.align === 'right' ? ['low to high', 'high to low'] : c.format === 'date' ? ['oldest first', 'newest first'] : ['A to Z', 'Z to A'];
   return '<option value="' + esc(c.key) + ':asc">' + esc(c.label) + ', ' + w[0] + '</option><option value="' + esc(c.key) + ':desc">' + esc(c.label) + ', ' + w[1] + '</option>';
  }).join('') + '</select>');
  sel = tools.querySelector('select');
  lis(sel, 'change', () => {var p = sel.value.split(':'); st.sk = p[0] || null; st.sd = p[1] || 'asc'; draw();});
 }
 if (!search) tools.classList.add('hide-wide');
 if (!tools.children.length) tools.remove();
 function draw() {
  var C = cols(), oo = st.o, list = (oo.rows || []).map((r, i) => {return {r, i};});
  if (st.q) {
   var q = st.q.toLowerCase();
   list = list.filter(x => oo.filter ? oo.filter(x.r, st.q) : C.some(c => (String(val(c, x.r) == null ? '' : val(c, x.r)) + ' ' + cell(c, x.r, true)).toLowerCase().indexOf(q) > -1));
  }
  if (st.sk) {var sc = C.find(c => c.key === st.sk); if (sc) list.sort((a, b) => cmp(val(sc, a.r), val(sc, b.r)) * (st.sd === 'desc' ? -1 : 1));}
  if (sel) sel.value = st.sk ? st.sk + ':' + st.sd : '';
  if (!list.length) {bodyEl.innerHTML = String(AK.empty(st.q ? {art: 'search', title: 'No matches', text: 'Nothing matches "' + st.q + '". Try fewer letters.'} : oo.empty || {art: 'list', title: 'Nothing here yet'})); return;}
  var hasP = C.some(c => c.primary);
  bodyEl.innerHTML = '<table class="table">' + (oo.caption ? '<caption class="sr-only">' + esc(oo.caption) + '</caption>' : '') + '<thead><tr>' + C.map(c => {
   var n = c.num || c.align === 'right', s = c.sort !== false, on = st.sk === c.key;
   return '<th scope="col"' + (n ? ' class="num"' : '') + (s ? ' aria-sort="' + (on ? (st.sd === 'asc' ? 'ascending' : 'descending') : 'none') + '"' : '') + '>' +
    (s ? '<button type="button" data-sort="' + esc(c.key) + '">' + esc(c.label) + svg(I[on ? (st.sd === 'asc' ? 'arrow-up' : 'arrow-down') : 'sort']) + '</button>' : esc(c.label)) + '</th>';
  }).join('') + '</tr></thead><tbody>' + list.map(x => {
   return '<tr' + (oo.onRow ? ' tabindex="0" data-i="' + x.i + '"' : '') + '>' + C.map((c, j) => {
    var cls = [(c.num || c.align === 'right') ? 'num' : '', (c.primary || (!hasP && j === 0)) ? 'primary' : '', c.class || ''].join(' ').trim();
    return '<td data-label="' + esc(c.label) + '"' + (cls ? ' class="' + cls + '"' : '') + '>' + cell(c, x.r) + '</td>';
   }).join('') + '</tr>';
  }).join('') + '</tbody></table>';
 }
 lis(el, 'click', e => {
  var b = e.target.closest('[data-sort]');
  if (b) {var k = b.dataset.sort; st.sd = st.sk === k && st.sd === 'asc' ? 'desc' : 'asc'; st.sk = k; draw(); var nb = el.querySelector('[data-sort="' + CSS.escape(k) + '"]'); if (nb) nb.focus(); return;}
  var tr = e.target.closest('tr[data-i]');
  if (tr && st.o.onRow && !e.target.closest('a,button,input,select,textarea')) st.o.onRow(st.o.rows[+tr.dataset.i], e);
 });
 lis(el, 'keydown', e => {var tr = e.target.closest && e.target.closest('tr[data-i]'); if (tr && e.target === tr && (e.key === 'Enter' || e.key === ' ')) {e.preventDefault(); st.o.onRow(st.o.rows[+tr.dataset.i], e);}});
 draw();
 var api = {el, update: rows => {st.o.rows = rows; draw();}, search: q => {st.q = q || ''; if (search) search.value = st.q; draw();}, sort: (k, d) => {st.sk = k; st.sd = d || 'asc'; draw();}};
 el._akT = {api, set: no => {st.o = Object.assign({}, st.o, no); draw();}};
 return api;
};
AK.csv = {
 export: (rows, name, cols) => {
  rows = rows || [];
  var keys = cols || rows.reduce((s, r) => {Object.keys(r).forEach(k => {if (s.indexOf(k) < 0) s.push(k);}); return s;}, []);
  var Cs = keys.map(c => {return typeof c === 'string' ? {key: c, label: c} : c;});
  var cl = v => {
   v = v == null ? '' : typeof v === 'object' ? JSON.stringify(v) : String(v);
   if (/^[=+@\t\r]/.test(v) || /^-[^\d.]/.test(v)) v = "'" + v;
   return /[",\n\r;]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v;
  };
  var text = [Cs.map(c => cl(c.label || c.key)).join(',')].concat(rows.map(r => Cs.map(c => cl(typeof c.value === 'function' ? c.value(r) : r[c.key])).join(','))).join('\r\n');
  AK.download(name || 'export.csv', new Blob(['\ufeff' + text], {type: 'text/csv;charset=utf-8'}));
  return text;
 },
 parse: t => {
  t = String(t == null ? '' : t).replace(/^\ufeff/, '');
  var line = t.split(/\r?\n/)[0] || '', dl = (line.match(/;/g) || []).length > (line.match(/,/g) || []).length ? ';' : (line.match(/\t/g) || []).length > (line.match(/,/g) || []).length ? '\t' : ',';
  var rows = [], row = [], f = '', q = false;
  for (var i = 0; i < t.length; i++) {
   var c = t[i];
   if (q) {if (c === '"') {if (t[i + 1] === '"') {f += '"'; i++;} else q = false;} else f += c;}
   else if (c === '"' && f === '') q = true;
   else if (c === dl) {row.push(f); f = '';}
   else if (c === '\n' || c === '\r') {if (c === '\r' && t[i + 1] === '\n') i++; row.push(f); rows.push(row); row = []; f = '';}
   else f += c;
  }
  if (f !== '' || row.length) {row.push(f); rows.push(row);}
  rows = rows.filter(r => r.some(x => x.trim() !== ''));
  if (!rows.length) return [];
  var head = rows.shift().map(h => h.trim());
  return rows.map(r => {var o = {}; head.forEach((h, j) => o[h] = r[j] == null ? '' : r[j].trim()); return o;});
 },
 import: src => pickFile(src, '.csv,text/csv,text/plain').then(f => f ? f.text() : null).then(t => t == null ? null : AK.csv.parse(t))
};

/* == charts: inline SVG, themed by --ak-c1..c5, tap/arrow keys for values == */
var charts = new Map(), RO = null;
var pal = i => i < 5 ? 'var(--ak-c' + (i + 1) + ')' : 'color-mix(in srgb,var(--ak-c' + (i % 5 + 1) + ') 55%,var(--ak-surface))';
function nice(lo, hi, t) {
 if (hi === lo) hi = lo + (lo === 0 ? 1 : Math.abs(lo) * .5);
 var raw = (hi - lo) / t, mag = Math.pow(10, Math.floor(Math.log10(raw))), nr = raw / mag;
 var step = (nr <= 1 ? 1 : nr <= 2 ? 2 : nr <= 2.5 ? 2.5 : nr <= 5 ? 5 : 10) * mag, a = Math.floor(lo / step) * step, b = Math.ceil(hi / step) * step, ticks = [];
 for (var v = a; v <= b + step / 2; v += step) ticks.push(+v.toFixed(10));
 return {lo: a, hi: b, ticks};
}
function smooth(p) {
 var n = p.length, f = r1;
 if (n < 3) return 'M' + p.map(q => f(q[0]) + ',' + f(q[1])).join('L');
 var d = [], m = [], i;
 for (i = 0; i < n - 1; i++) d.push((p[i + 1][1] - p[i][1]) / (p[i + 1][0] - p[i][0]));
 m[0] = d[0]; m[n - 1] = d[n - 2];
 for (i = 1; i < n - 1; i++) m[i] = d[i - 1] * d[i] <= 0 ? 0 : (d[i - 1] + d[i]) / 2;
 for (i = 0; i < n - 1; i++) {
  if (d[i] === 0) {m[i] = m[i + 1] = 0; continue;}
  var a = m[i] / d[i], b = m[i + 1] / d[i], s = a * a + b * b;
  if (s > 9) {var k = 3 / Math.sqrt(s); m[i] = k * a * d[i]; m[i + 1] = k * b * d[i];}
 }
 var out = 'M' + f(p[0][0]) + ',' + f(p[0][1]);
 for (i = 0; i < n - 1; i++) {var h = (p[i + 1][0] - p[i][0]) / 3; out += 'C' + f(p[i][0] + h) + ',' + f(p[i][1] + m[i] * h) + ' ' + f(p[i + 1][0] - h) + ',' + f(p[i + 1][1] - m[i + 1] * h) + ' ' + f(p[i + 1][0]) + ',' + f(p[i + 1][1]);}
 return out;
}
function numFmt(f) {
 if (typeof f === 'function') return (v, ax) => String(f(v, ax));
 if (f === 'money') return (v, ax) => {return ax && Math.abs(v) >= 1000 ? NF('mc' + AK.currency, {style: 'currency', currency: AK.currency || 'USD', notation: 'compact', maximumFractionDigits: 1}).format(v) : AK.fmt.money(v, ax || v % 1 === 0 ? 0 : 2);};
 if (f === 'percent') return v => AK.fmt.percent(v);
 return (v, ax) => ax ? AK.fmt.compact(v) : AK.fmt.number(v);
}
var grad = (id, col, op) => '<defs><linearGradient id="' + id + '" x1="0" y1="0" x2="0" y2="1"><stop offset="0" style="stop-color:' + col + ';stop-opacity:' + op + '"/><stop offset="1" style="stop-color:' + col + ';stop-opacity:0"/></linearGradient></defs>';
var srTable = (title, labels, S, F) => {
 return '<table class="sr-only">' + (title ? '<caption>' + esc(title) + '</caption>' : '') + '<tr><th>Label</th>' + S.map(s => '<th>' + esc(s.name || 'Value') + '</th>').join('') + '</tr>' +
  labels.map((l, i) => '<tr><th>' + esc(l) + '</th>' + S.map(s => '<td>' + esc(F(s.v[i])) + '</td>').join('') + '</tr>').join('') + '</table>';
};
function xy(c, F) {
 var o = c.o, W0 = c.w, type = c.type;
 var S = (o.series || [{name: o.name || '', values: o.values || []}]).map((s, i) => {return {name: s.name || '', v: (s.values || []).map(x => +x || 0), col: s.color || pal(i)};});
 var n = Math.max.apply(null, S.map(s => s.v.length).concat([0]));
 if (!n) {c.g = null; return '<p class="muted small">No data yet</p>';}
 var labels = (o.labels || []).slice(0, n).map(String);
 while (labels.length < n) labels.push(String(labels.length + 1));
 var stacked = !!o.stacked && type === 'bar';
 var vals = stacked ? labels.map((_, i) => S.reduce((a, s) => a + Math.max(0, s.v[i] || 0), 0)) : [].concat.apply([], S.map(s => s.v));
 var sc = nice(o.min != null ? o.min : Math.min.apply(null, vals.concat([0])), o.max != null ? o.max : Math.max.apply(null, vals.concat([0])), o.ticks || 4);
 var H = o.height || Math.round(Math.max(180, Math.min(280, W0 * .56)));
 var yl = sc.ticks.map(t => F(t, true)), pl = Math.min(84, Math.max(30, Math.max.apply(null, yl.map(s => s.length)) * 7.4 + 12)), pr = 6, pt = 12, pb = 28;
 var pw = Math.max(40, W0 - pl - pr), ph = H - pt - pb, band = pw / n;
 var Y = v => pt + ph - (v - sc.lo) / (sc.hi - sc.lo) * ph, X = i => pl + band * (i + .5);
 var base = Y(Math.min(Math.max(0, sc.lo), sc.hi)), every = Math.max(1, Math.ceil(n / Math.max(1, Math.floor(pw / 58))));
 var s = '<g class="grid">' + sc.ticks.map((t, k) => {var y = r1(Y(t)); return '<line x1="' + pl + '" x2="' + (W0 - pr) + '" y1="' + y + '" y2="' + y + '"/><text x="' + (pl - 8) + '" y="' + y + '" dy=".32em" text-anchor="end">' + esc(yl[k]) + '</text>';}).join('') + '</g>';
 s += labels.map((l, i) => i % every ? '' : '<text x="' + r1(X(i)) + '" y="' + (H - 7) + '" text-anchor="middle">' + esc(l.length > 10 ? l.slice(0, 9) + '…' : l) + '</text>').join('');
 if (type === 'bar') {
  var rr = Math.min(6, parseFloat(getComputedStyle(c.el).getPropertyValue('--ak-r-sm')) || 0), multi = S.length > 1 && !stacked;
  var gw = band * (multi ? .76 : .58), bw = stacked ? gw : gw / S.length, acc = labels.map(() => 0);
  S.forEach((se, si) => {
   se.v.forEach((v, i) => {
    var y0 = 0, y1 = v;
    if (stacked) {y0 = acc[i]; acc[i] += Math.max(0, v); y1 = acc[i];}
    var top = Y(Math.max(y0, y1)), bot = Y(Math.min(y0, y1)), x = (stacked ? X(i) - gw / 2 : X(i) - gw / 2 + si * bw) + (multi ? 1 : 0);
    var w = Math.max(1, bw - (multi ? 2 : 0)), h = Math.max(0, bot - top), r = Math.min(rr, w / 2, h), rt = !stacked || si === S.length - 1, d;
    if (!rt && v >= 0) r = 0;
    var e0 = v < 0 ? top + h : top, b0 = v < 0 ? top : top + h, k = v < 0 ? -r : r;
    d = 'M' + [r1(x), r1(b0)] + 'V' + r1(e0 + k) + 'Q' + [r1(x), r1(e0)] + ' ' + [r1(x + r), r1(e0)] + 'H' + r1(x + w - r) + 'Q' + [r1(x + w), r1(e0)] + ' ' + [r1(x + w), r1(e0 + k)] + 'V' + r1(b0) + 'Z';
    s += '<path class="bar" data-i="' + i + '" d="' + d + '" style="fill:' + se.col + ';transform-origin:0 ' + r1(base) + 'px;animation-delay:' + (i * 28 + si * 60) + 'ms"/>';
   });
  });
 } else {
  S.forEach((se, si) => {
   var pts = se.v.map((v, i) => [X(i), Y(v)]), d = smooth(pts), gid = 'akg' + AK.id(), g = '';
   if (type === 'area') g += grad(gid, se.col, .3) + '<path d="' + d + 'L' + r1(X(n - 1)) + ',' + r1(base) + 'L' + r1(X(0)) + ',' + r1(base) + 'Z" style="fill:url(#' + gid + ')"/>';
   g += '<path class="line" d="' + d + '" style="stroke:' + se.col + '"/>';
   if (si === 0) g += '<circle class="dot" cx="' + r1(X(n - 1)) + '" cy="' + r1(Y(se.v[n - 1])) + '" r="4.5" style="fill:' + se.col + '"/>';
   s += '<g class="grow" style="transform-origin:0 ' + r1(base) + 'px;animation-delay:' + si * 90 + 'ms">' + g + '</g>';
  });
 }
 s += '<line class="base" x1="' + pl + '" x2="' + (W0 - pr) + '" y1="' + r1(base) + '" y2="' + r1(base) + '"/><g class="active"></g>';
 c.g = {pl, band, n, X, Y, S, labels, F, pt, ph, stacked};
 var label = (o.title || (type === 'bar' ? 'Bar chart' : 'Line chart')) + ': ' + labels.slice(0, 12).map((l, i) => l + ' ' + F(S[0].v[i])).join(', ');
 return '<svg width="' + W0 + '" height="' + H + '" viewBox="0 0 ' + W0 + ' ' + H + '" role="img" tabindex="0" aria-label="' + esc(label) + '">' + s + '</svg><div class="chart-tip" aria-hidden="true"></div>' + srTable(o.title, labels, S, F);
}
var fitVal = (t, str, inner) => {t.textContent = str; t.style.fontSize = Math.round(Math.min(26, inner * 1.55 / Math.max(4, str.length))) + 'px';};
function donut(c, F) {
 var o = c.o, vals = (o.values || []).map(v => Math.max(0, +v || 0)), labels = (o.labels || []).slice(), total = vals.reduce((a, b) => a + b, 0);
 while (labels.length < vals.length) labels.push('Item ' + (labels.length + 1));
 var size = o.size || Math.round(Math.max(130, Math.min(190, c.w * .55))), th = o.thickness || Math.round(size * .15), r = (size - th) / 2 - 1, Cf = 2 * Math.PI * r, cx = size / 2;
 var gap = vals.filter(v => v > 0).length > 1 ? 2.5 : 0, acc = 0;
 var segs = vals.map((v, i) => {
  var len = total ? v / total * Cf : 0;
  var out = len > 0 ? '<circle class="seg" data-i="' + i + '" cx="' + cx + '" cy="' + cx + '" r="' + r1(r) + '" style="stroke:' + pal(i) + ';stroke-width:' + th + ';stroke-dasharray:' + r1(Math.max(.01, len - gap)) + ' ' + r1(Cf) + ';stroke-dashoffset:' + r1(-acc) + ';transform-origin:' + cx + 'px ' + cx + 'px;animation-delay:' + i * 70 + 'ms"/>' : '';
  acc += len; return out;
 }).join('');
 c.g = {n: vals.length, vals, labels, total, F, center: o.center || 'Total', inner: size - 2 * th};
 var tv = F(total), fs = Math.round(Math.min(26, c.g.inner * 1.55 / Math.max(4, tv.length)));
 var label = (o.title || 'Donut chart') + ': ' + labels.map((l, i) => l + ' ' + F(vals[i])).join(', ');
 return '<div class="donut"><svg width="' + size + '" height="' + size + '" viewBox="0 0 ' + size + ' ' + size + '" role="img" tabindex="0" aria-label="' + esc(label) + '"><circle class="track" cx="' + cx + '" cy="' + cx + '" r="' + r1(r) + '" style="stroke-width:' + th + '"/><g transform="rotate(-90 ' + cx + ' ' + cx + ')">' + segs + '</g>' +
  '<text class="d-val" x="' + cx + '" y="' + cx + '" text-anchor="middle" dy=".1em" style="font-size:' + fs + 'px">' + esc(tv) + '</text><text class="d-lbl" x="' + cx + '" y="' + cx + '" text-anchor="middle" dy="1.75em">' + esc(c.g.center) + '</text></svg>' +
  '<ul class="legend">' + labels.map((l, i) => '<li><span class="k" style="background:' + pal(i) + '"></span><span>' + esc(l) + '</span><span class="v">' + esc(F(vals[i])) + (total ? ' · ' + Math.round(vals[i] / total * 100) + '%' : '') + '</span></li>').join('') + '</ul></div>' +
  srTable(o.title, labels, [{name: o.name || 'Value', v: vals}], F);
}
function spark(c) {
 var o = c.o, v = (o.values || []).map(x => +x || 0), w = c.w, h = o.height || 36;
 c.g = null;
 if (v.length < 2) return '';
 var lo = Math.min.apply(null, v), hi = Math.max.apply(null, v), sp = hi - lo || 1;
 var P = v.map((y, i) => [i / (v.length - 1) * (w - 8) + 4, h - 5 - (y - lo) / sp * (h - 10)]), d = smooth(P), col = o.color || 'var(--ak-c1)', gid = 'akg' + AK.id(), last = P[P.length - 1];
 return '<svg width="' + w + '" height="' + h + '" viewBox="0 0 ' + w + ' ' + h + '"' + (o.label ? ' role="img" aria-label="' + esc(o.label) + '"' : ' aria-hidden="true"') + '>' +
  (o.area !== false ? grad(gid, col, .28) + '<path d="' + d + 'L' + (w - 4) + ',' + h + 'L4,' + h + 'Z" style="fill:url(#' + gid + ')"/>' : '') +
  '<g class="grow" style="transform-origin:0 ' + h + 'px"><path class="line" d="' + d + '" style="stroke:' + col + ';stroke-width:2"/></g><circle class="dot" cx="' + r1(last[0]) + '" cy="' + r1(last[1]) + '" r="3.5" style="fill:' + col + ';stroke-width:2"/></svg>';
}
function drawChart(c, quiet) {
 var el = c.el;
 c.w = Math.round(el.clientWidth) || 320; c.i = -1; el.classList.remove('has-active');
 var F = numFmt(c.o.format);
 el.innerHTML = c.type === 'donut' ? donut(c, F) : c.type === 'spark' ? spark(c) : xy(c, F);
 var s = el.querySelector('svg');
 if (s && (quiet || still())) s.classList.add('still');
}
function tip(c, i) {
 var el = c.el, g = c.g; if (!g) return;
 c.i = i;
 el.classList.toggle('has-active', i >= 0);
 $$('.on', el).forEach(n => n.classList.remove('on'));
 if (c.type === 'donut') {
  var tv = el.querySelector('.d-val'), tl = el.querySelector('.d-lbl');
  if (i >= 0) {$$('.seg[data-i="' + i + '"]', el).forEach(n => n.classList.add('on')); fitVal(tv, g.F(g.vals[i]), g.inner); tl.textContent = g.labels[i];}
  else {fitVal(tv, g.F(g.total), g.inner); tl.textContent = g.center;}
  return;
 }
 var act = el.querySelector('.active'), tp = el.querySelector('.chart-tip');
 if (i < 0) {if (act) act.innerHTML = ''; if (tp) tp.classList.remove('on'); return;}
 $$('.bar[data-i="' + i + '"]', el).forEach(n => n.classList.add('on'));
 var x = g.X(i), top = c.type === 'bar' ? g.Y(g.stacked ? g.S.reduce((a, s) => a + Math.max(0, s.v[i]), 0) : Math.max.apply(null, g.S.map(s => s.v[i]).concat([0]))) : Math.min.apply(null, g.S.map(s => g.Y(s.v[i])));
 act.innerHTML = c.type === 'bar' ? '' : '<line class="guide" x1="' + r1(x) + '" x2="' + r1(x) + '" y1="' + g.pt + '" y2="' + (g.pt + g.ph) + '"/>' + g.S.map(s => '<circle class="dot" cx="' + r1(x) + '" cy="' + r1(g.Y(s.v[i])) + '" r="5" style="fill:' + s.col + '"/>').join('');
 tp.innerHTML = '<b>' + esc(g.labels[i]) + '</b>' + g.S.map(s => '<div>' + (g.S.length > 1 ? '<span class="k" style="background:' + s.col + '"></span>' + esc(s.name) + ' ' : '') + '<strong>' + esc(g.F(s.v[i])) + '</strong></div>').join('');
 tp.classList.add('on');
 var tw = tp.offsetWidth, th = tp.offsetHeight;
 tp.style.left = r1(Math.max(tw / 2, Math.min(c.w - tw / 2, x))) + 'px';
 tp.style.top = r1(top) + 'px';
 tp.classList.toggle('below', top - th - 14 < 0);
}
function chartOf(el, type, o) {
 el = toEl(el); if (!el) return null;
 charts.forEach((k, e) => {if (!e.isConnected) {charts.delete(e); if (RO) RO.unobserve(e);}});
 var c = charts.get(el);
 if (!c) {
  c = {el, i: -1, w: 0}; charts.set(el, c); el.classList.add('chart');
  if (W.ResizeObserver) {
   RO = RO || new ResizeObserver(es => {
    es.forEach(en => {
     var k = charts.get(en.target); if (!k) return;
     if (!en.target.isConnected) {charts.delete(en.target); RO.unobserve(en.target); return;}
     var w = Math.round(en.contentRect.width);
     if (w && Math.abs(w - k.w) > 2 && !k.raf) k.raf = requestAnimationFrame(() => {k.raf = 0; if (k.el.isConnected) drawChart(k, true);});
    });
   });
   RO.observe(el);
  }
  var at = e => {
   var g = c.g; if (!g) return;
   if (c.type === 'donut') {var sg = e.target.closest ? e.target.closest('.seg') : null; tip(c, sg ? +sg.dataset.i : -1); return;}
   var rc = box(el), i = Math.floor((e.clientX - rc.left - g.pl) / g.band);
   tip(c, i >= 0 && i < g.n ? i : -1);
  };
  lis(el, 'pointerdown', at);
  lis(el, 'pointermove', e => {if (e.pointerType === 'mouse') at(e);});
  lis(el, 'pointerleave', e => {if (e.pointerType === 'mouse') tip(c, -1);});
  lis(el, 'keydown', e => {
   if (!c.g) return;
   if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') {e.preventDefault(); var n = c.g.n; tip(c, c.i < 0 ? 0 : (c.i + (e.key === 'ArrowRight' ? 1 : -1) + n) % n);}
   else if (e.key === 'Escape') tip(c, -1);
  });
  lis(el, 'focusout', () => tip(c, -1));
 }
 c.type = type; c.o = o || {};
 drawChart(c, false);
 return {el, update: no => {c.o = Object.assign({}, c.o, no); drawChart(c, true);}};
}
lis(D, 'pointerdown', e => {charts.forEach(c => {if (c.i >= 0 && !c.el.contains(e.target)) tip(c, -1);});}, true);
AK._live = () => {charts.forEach((k, e) => {if (!e.isConnected) charts.delete(e);}); return charts.size;};
AK.chart = {
 bar: (el, o) => chartOf(el, 'bar', o),
 line: (el, o) => chartOf(el, 'line', o),
 area: (el, o) => chartOf(el, 'area', o),
 donut: (el, o) => chartOf(el, 'donut', o),
 spark: (el, values, o) => {return chartOf(el, 'spark', Array.isArray(values) ? Object.assign({values}, o) : values);}
};

/* == photo + signature == */
AK.photo = (src, o) => {
 o = o || {};
 return pickFile(src, 'image/*', o).then(f => {
  if (!f) return null;
  var url = null;
  var loadImg = () => {
   return W.createImageBitmap ? createImageBitmap(f, {imageOrientation: 'from-image'}).catch(() => W.createImageBitmap(f)) : Promise.reject(new Error('no bitmap'));
  };
  return loadImg().catch(() => {
   return new Promise((ok, no) => {var im = new Image(); url = URL.createObjectURL(f); im.onload = () => ok(im); im.onerror = no; im.src = url;});
  }).then(img => {
   var w = img.width, h = img.height, s = Math.min(1, (o.max || 1280) / Math.max(w, h)), cv = D.createElement('canvas');
   cv.width = Math.max(1, Math.round(w * s)); cv.height = Math.max(1, Math.round(h * s));
   var x = cv.getContext('2d'); x.fillStyle = '#fff'; x.fillRect(0, 0, cv.width, cv.height); x.drawImage(img, 0, 0, cv.width, cv.height);
   if (img.close) img.close(); if (url) URL.revokeObjectURL(url);
   return cv.toDataURL('image/jpeg', o.quality || .8);
  }).catch(() => {AK.toast('Could not read that picture.', 'bad'); return null;});
 });
};
AK.signature = (canvas, o) => {
 canvas = toEl(canvas); o = o || {};
 if (canvas._akSig) return canvas._akSig;
 canvas.classList.add('sig');
 if (!canvas.hasAttribute('aria-label')) canvas.setAttribute('aria-label', 'Signature pad: sign with your finger or mouse');
 var ctx = canvas.getContext('2d'), strokes = [], cur = null, w = 0, h = 0;
 var ink = () => o.color || getComputedStyle(canvas).color || '#000';
 var path = (c, s, Wd, Hd) => {
  var p = s.map(q => [q[0] * Wd, q[1] * Hd]);
  c.beginPath();
  if (p.length === 1) {c.arc(p[0][0], p[0][1], c.lineWidth / 2, 0, 7); c.fillStyle = c.strokeStyle; c.fill(); return;}
  c.moveTo(p[0][0], p[0][1]);
  for (var i = 1; i < p.length - 1; i++) c.quadraticCurveTo(p[i][0], p[i][1], (p[i][0] + p[i + 1][0]) / 2, (p[i][1] + p[i + 1][1]) / 2);
  c.lineTo(p[p.length - 1][0], p[p.length - 1][1]); c.stroke();
 };
 var paint = (c, Wd, Hd, col, lw) => {c.clearRect(0, 0, Wd, Hd); c.lineCap = c.lineJoin = 'round'; c.strokeStyle = col; c.lineWidth = lw; strokes.forEach(s => path(c, s, Wd, Hd));};
 var rp = () => paint(ctx, w, h, ink(), o.width || 2.6);
 var size = () => {
  var r = box(canvas), dpr = W.devicePixelRatio || 1;
  if (!r.width) return;
  w = r.width; h = r.height; canvas.width = Math.round(w * dpr); canvas.height = Math.round(h * dpr);
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0); rp();
 };
 var pt = e => {var r = box(canvas); return [(e.clientX - r.left) / r.width, (e.clientY - r.top) / r.height];};
 lis(canvas, 'pointerdown', e => {if (e.button > 0) return; if (!w) size(); try {canvas.setPointerCapture(e.pointerId);} catch (x) {} cur = [pt(e)]; strokes.push(cur); rp(); e.preventDefault();});
 lis(canvas, 'pointermove', e => {
  if (!cur) return;
  (e.getCoalescedEvents ? e.getCoalescedEvents() : [e]).forEach(ev => cur.push(pt(ev)));
  rp();
 });
 var end = () => {if (!cur) return; cur = null; if (o.onChange) o.onChange(api);};
 lis(canvas, 'pointerup', end); lis(canvas, 'pointercancel', end);
 if (W.ResizeObserver) {var ro = new ResizeObserver(() => requestAnimationFrame(() => {if (!canvas.isConnected) {ro.disconnect(); return;} var r = box(canvas); if (Math.round(r.width) !== Math.round(w) || Math.round(r.height) !== Math.round(h)) size();})); ro.observe(canvas);}
 var api = {
  el: canvas,
  clear: () => {strokes = []; rp(); if (o.onChange) o.onChange(api);},
  undo: () => {strokes.pop(); rp(); if (o.onChange) o.onChange(api);},
  isEmpty: () => !strokes.length,
  toJSON: () => strokes.map(s => s.map(q => ([r1(q[0] * 1000) / 1000, r1(q[1] * 1000) / 1000]))),
  load: data => {strokes = (data || []).map(s => s.slice()); rp();},
  toDataURL: opt => {
   opt = opt || {};
   var sc = opt.scale || 2, cw = Math.max(1, Math.round((w || 600) * sc)), chh = Math.max(1, Math.round((h || 190) * sc)), cv = D.createElement('canvas');
   cv.width = cw; cv.height = chh;
   var x = cv.getContext('2d');
   if (opt.bg) {x.fillStyle = opt.bg; x.fillRect(0, 0, cw, chh);}
   paint(x, cw, chh, opt.color || '#000', (o.width || 2.6) * sc);
   if (opt.bg) {x.globalCompositeOperation = 'destination-over'; x.fillStyle = opt.bg; x.fillRect(0, 0, cw, chh);}
   return cv.toDataURL(opt.type || 'image/png');
  }
 };
 canvas._akSig = api;
 requestAnimationFrame(size);
 return api;
};

/* == drag to reorder + kanban (mouse: drag; touch: hold then drag, or drag a .handle) == */
function autoScroll(x, y, sc) {
 var vh = W.innerHeight;
 if (y < 110) W.scrollBy(0, -Math.ceil((110 - y) / 6)); else if (y > vh - 120) W.scrollBy(0, Math.ceil((y - vh + 120) / 6));
 if (sc) {var r = box(sc); if (x < r.left + 48) sc.scrollLeft -= Math.ceil((r.left + 48 - x) / 4); else if (x > r.right - 48) sc.scrollLeft += Math.ceil((x - r.right + 48) / 4);}
}
function dragger(root, o) {
 var noClick = false;
 lis(root, 'click', e => {if (noClick) {e.preventDefault(); e.stopPropagation(); noClick = false;}}, true);
 lis(root, 'contextmenu', e => {if (R.classList.contains('ak-dragging') || o.pressing) e.preventDefault();});
 lis(root, 'pointerdown', e => {
  if (e.button > 0) return;
  var it = o.item(e.target); if (!it) return;
  var onHandle = o.handle && e.target.closest(o.handle);
  if (o.handle && !onHandle && !o.anywhere()) return;
  if (!onHandle && e.target.closest('button,a,input,select,textarea,label')) return;
  var touch = e.pointerType !== 'mouse', instant = !touch || !!onHandle, sx = e.clientX, sy = e.clientY, x = sx, y = sy, on = false, timer = 0, raf = 0;
  var loop = () => {raf = requestAnimationFrame(loop); autoScroll(x, y, o.scroller); o.move(x, y);};
  var begin = () => {on = true; o.pressing = false; R.classList.add('ak-dragging'); o.start(it, x, y); if (touch && navigator.vibrate) try {navigator.vibrate(8);} catch (err) {} loop();};
  var stop = ok => {
   clearTimeout(timer); cancelAnimationFrame(raf); o.pressing = false;
   W.removeEventListener('pointermove', mv); W.removeEventListener('pointerup', up); W.removeEventListener('pointercancel', cc); W.removeEventListener('touchmove', tm);
   if (on) {R.classList.remove('ak-dragging'); noClick = true; setTimeout(() => noClick = false, 60); o.end(ok);}
   on = false;
  };
  var mv = ev => {x = ev.clientX; y = ev.clientY; if (!on) {if (Math.abs(x - sx) + Math.abs(y - sy) > 8) {if (instant) begin(); else stop(false);} return;} ev.preventDefault();};
  var tm = ev => {if (on && ev.cancelable) ev.preventDefault();};
  var up = () => stop(true), cc = () => stop(false);
  if (!instant) {o.pressing = true; timer = setTimeout(begin, o.press || 320);}
  lis(W, 'pointermove', mv, {passive: false}); lis(W, 'pointerup', up); lis(W, 'pointercancel', cc); lis(W, 'touchmove', tm, {passive: false});
 });
}
AK.drag = (list, opt) => {
 list = toEl(list);
 if (list._akDrag) {list._akDrag.o = opt || {}; return list;}
 var S = {o: opt || {}}, st = null;
 list._akDrag = S;
 var hs = () => S.o.handle || '.handle';
 var kids = () => Array.from(list.children);
 var item = t => {var n = t; while (n && n.parentElement !== list) n = n.parentElement; return n || null;};
 var done = (from, to) => {if (S.o.onSort) S.o.onSort(kids().map(n => n.dataset.id), from, to);};
 dragger(list, {
  item: item, scroller: null,
  get handle() {return hs();},
  anywhere: () => !list.querySelector(hs()),
  start: (it, x, y) => {
   var items = kids(), sy = W.scrollY, rects = items.map(n => {var r = box(n); return {top: r.top + sy, h: r.height};});
   st = {it, items, rects, from: items.indexOf(it), to: items.indexOf(it), y0: y + sy, gap: rects.length > 1 ? rects[1].top - rects[0].top - rects[0].h : 0};
   list.classList.add('ak-sorting'); it.classList.add('dragging');
  },
  move: (x, y) => {
   if (!st) return;
   var dy = y + W.scrollY - st.y0, r0 = st.rects[st.from], c = r0.top + r0.h / 2 + dy, to = st.from;
   st.rects.forEach((r, i) => {var mid = r.top + r.h / 2; if (i < st.from && c < mid) to = Math.min(to, i); if (i > st.from && c > mid) to = Math.max(to, i);});
   st.to = to; st.it.style.transform = 'translateY(' + dy + 'px)';
   var hh = r0.h + st.gap;
   st.items.forEach((n, i) => {if (n === st.it) return; var s = st.from < to && i > st.from && i <= to ? -hh : st.from > to && i < st.from && i >= to ? hh : 0; n.style.transform = s ? 'translateY(' + s + 'px)' : '';});
  },
  end: ok => {
   if (!st) return;
   var s = st; st = null;
   s.items.forEach(n => n.style.transform = '');
   list.classList.remove('ak-sorting'); s.it.classList.remove('dragging');
   if (ok && s.to !== s.from) {var ref = s.items[s.to]; if (s.to > s.from) ref.after(s.it); else ref.before(s.it); done(s.from, s.to);}
  }
 });
 lis(list, 'keydown', e => {
  var h = e.target.closest ? e.target.closest(hs()) : null;
  if (!h || !/^Arrow(Up|Down)$/.test(e.key)) return;
  var it = item(h), items = kids(), from = items.indexOf(it), to = from + (e.key === 'ArrowUp' ? -1 : 1);
  if (to < 0 || to >= items.length) return;
  e.preventDefault();
  if (e.key === 'ArrowUp') items[to].before(it); else items[to].after(it);
  h.focus(); done(from, to);
 });
 return list;
};
AK.kanban = (el, opt) => {
 el = toEl(el);
 if (el._akK) {el._akK.set(opt); return el._akK.api;}
 var S = {o: opt, cards: (opt.cards || []).slice()}, drag = null, did = 'akkd' + AK.id();
 el.classList.add('kanban');
 var find = id => S.cards.find(c => String(c.id) === String(id));
 var body = c => S.o.card ? String(S.o.card(c)) : '<p class="kt">' + esc(c.title || '') + '</p>' + (c.sub ? '<p class="ks">' + esc(c.sub) + '</p>' : '');
 function draw() {
  el.innerHTML = S.o.columns.map(col => {
   var l = S.cards.filter(c => c.col === col.id);
   return '<div class="kcol" data-col="' + esc(col.id) + '"><div class="kcol-head"><h2>' + esc(col.title) + '</h2><span class="n">' + l.length + '</span></div><div class="kcards" data-empty="' + esc(S.o.emptyText || 'Nothing here') + '">' +
    l.map(c => '<article class="kcard" tabindex="0" data-id="' + esc(c.id) + '" aria-describedby="' + did + '">' + body(c) + '</article>').join('') + '</div></div>';
  }).join('') + '<p class="sr-only" id="' + did + '">Press Enter to open. Shift and arrow keys move the card.</p><p class="sr-only" aria-live="polite"></p>';
 }
 function commit(card, from, idx0) {
  var to = card.closest('.kcol').dataset.col, idx = Array.from(card.parentNode.children).indexOf(card), c = find(card.dataset.id);
  if (!c) return;
  var order = $$('.kcard', el).map(n => n.dataset.id);
  c.col = to;
  S.cards.sort((a, b) => order.indexOf(String(a.id)) - order.indexOf(String(b.id)));
  $$('.kcol', el).forEach(k => k.querySelector('.n').textContent = k.querySelectorAll('.kcard').length);
  if (to !== from || idx !== idx0) {
   var ct = S.o.columns.find(x => x.id === to);
   el.querySelector('[aria-live]').textContent = (c.title || 'Card') + ' moved to ' + (ct ? ct.title : to);
   if (S.o.onMove) S.o.onMove(c, to, idx, from);
  }
 }
 var idxOf = n => Array.from(n.parentNode.children).indexOf(n);
 dragger(el, {
  item: t => t.closest('.kcard'), scroller: el, handle: null, anywhere: () => true,
  start: (card, x, y) => {
   var r = box(card), g = card.cloneNode(true);
   g.classList.add('kghost'); g.removeAttribute('id'); g.style.width = r.width + 'px';
   D.body.append(g); card.classList.add('kph');
   drag = {card, g, dx: x - r.left, dy: y - r.top, from: card.closest('.kcol').dataset.col, idx: idxOf(card)};
  },
  move: (x, y) => {
   if (!drag) return;
   drag.g.style.transform = 'translate(' + r1(x - drag.dx) + 'px,' + r1(y - drag.dy) + 'px) rotate(1.5deg)';
   var t = D.elementFromPoint(x, y), col = t && t.closest ? t.closest('.kcol') : null;
   $$('.kcol.over', el).forEach(k => {if (k !== col) k.classList.remove('over');});
   if (!col || !el.contains(col)) return;
   col.classList.add('over');
   var list = col.querySelector('.kcards'), after = Array.from(list.children).find(k => {if (k === drag.card) return false; var b = box(k); return y < b.top + b.height / 2;});
   if (after) {if (drag.card.nextElementSibling !== after) list.insertBefore(drag.card, after);}
   else if (list.lastElementChild !== drag.card) list.append(drag.card);
  },
  end: ok => {
   if (!drag) return;
   var d = drag; drag = null;
   d.g.remove(); d.card.classList.remove('kph');
   $$('.kcol.over', el).forEach(k => k.classList.remove('over'));
   if (ok) commit(d.card, d.from, d.idx); else draw();
  }
 });
 lis(el, 'click', e => {var k = e.target.closest('.kcard'); if (k && S.o.onOpen && !e.target.closest('a,button,input,select')) S.o.onOpen(find(k.dataset.id));});
 lis(el, 'keydown', e => {
  var k = e.target.closest ? e.target.closest('.kcard') : null;
  if (!k || e.target !== k) return;
  if ((e.key === 'Enter' || e.key === ' ') && S.o.onOpen) {e.preventDefault(); S.o.onOpen(find(k.dataset.id)); return;}
  if (!e.shiftKey || !/^Arrow/.test(e.key)) return;
  e.preventDefault();
  var from = k.closest('.kcol').dataset.col, i0 = idxOf(k), cols = $$('.kcol', el), ci = cols.indexOf(k.closest('.kcol'));
  if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') {var nc = cols[ci + (e.key === 'ArrowRight' ? 1 : -1)]; if (!nc) return; nc.querySelector('.kcards').append(k);}
  else {var sib = e.key === 'ArrowUp' ? k.previousElementSibling : k.nextElementSibling; if (!sib) return; if (e.key === 'ArrowUp') sib.before(k); else sib.after(k);}
  k.focus(); commit(k, from, i0);
 });
 draw();
 var api = {el, update: cards => {S.cards = (cards || []).slice(); draw();}};
 el._akK = {api, set: no => {S.o = Object.assign({}, S.o, no); if (no.cards) S.cards = no.cards.slice(); draw();}};
 return api;
};

/* == output: print, share, copy, download, sms/mail/tel links == */
AK.print = (title, el) => {
 if (title && title.nodeType) {el = title; title = null;}
 el = toEl(el) || D.querySelector('.doc');
 $$('.ak-print-root').forEach(n => n.remove());
 var old = D.title, root = null;
 if (title) D.title = title;
 if (el) {
  root = AK.el('div', {class: 'ak-print-root'});
  var cl = el.cloneNode(true), dst = $$('canvas', cl);
  $$('canvas', el).forEach((cv, i) => {var im = new Image(); im.alt = ''; im.className = cv.className; im.src = cv._akSig ? cv._akSig.toDataURL({color: '#000'}) : cv.toDataURL(); dst[i].replaceWith(im);});
  root.append(cl); D.body.append(root); R.classList.add('ak-printing');
 }
 var done = () => {W.removeEventListener('afterprint', done); if (root) root.remove(); R.classList.remove('ak-printing'); D.title = old;};
 lis(W, 'afterprint', done);
 setTimeout(() => {try {W.print();} catch (e) {done(); AK.toast('Printing is not available here.', 'warn');}}, 60);
};
var cleanNum = n => String(n == null ? '' : n).replace(/[^\d+]/g, '');
AK.tel = n => 'tel:' + cleanNum(n);
AK.sms = (n, text) => 'sms:' + cleanNum(n) + (text ? '?body=' + encodeURIComponent(text) : '');
AK.mail = (to, subject, body) => {
 var q = [subject ? 'subject=' + encodeURIComponent(subject) : '', body ? 'body=' + encodeURIComponent(body) : ''].filter(Boolean).join('&');
 return 'mailto:' + encodeURIComponent(to || '').replace(/%40/g, '@').replace(/%2C/gi, ',') + (q ? '?' + q : '');
};
AK.copy = (text, o) => {
 o = o || {}; text = String(text == null ? '' : text);
 var fallback = () => {
  var t = AK.el('textarea', {readonly: 'true', class: 'sr-only'}); t.value = text;
  (layers.length ? layers[layers.length - 1].el : D.body).append(t); t.select();
  var ok = false; try {ok = D.execCommand('copy');} catch (e) {ok = false;}
  t.remove(); return ok;
 };
 var p = navigator.clipboard && W.isSecureContext ? navigator.clipboard.writeText(text).then(() => true, fallback) : Promise.resolve(fallback());
 return p.then(ok => {if (o.toast !== false) AK.toast(ok ? (o.done || 'Copied') : 'Could not copy on this device', ok ? 'ok' : 'bad'); return ok;});
};
var MIME = {csv: 'text/csv;charset=utf-8', json: 'application/json', html: 'text/html;charset=utf-8', txt: 'text/plain;charset=utf-8', svg: 'image/svg+xml', md: 'text/markdown'};
AK.download = (name, data, type) => {
 var ext = String(name).split('.').pop().toLowerCase();
 var blob = data instanceof Blob ? data : new Blob([typeof data === 'string' ? data : JSON.stringify(data, null, 2)], {type: type || MIME[ext] || 'application/octet-stream'});
 var u = URL.createObjectURL(blob), a = AK.el('a', {href: u, download: name, class: 'sr-only'});
 D.body.append(a); a.click(); a.remove();
 setTimeout(() => URL.revokeObjectURL(u), 5000);
 return true;
};
AK.share = o => {
 o = o || {};
 var text = [o.text, o.url].filter(Boolean).join('\n');
 if (navigator.share && !o.sheet) {
  return navigator.share({title: o.title, text: o.text, url: o.url}).then(() => 'shared', e => {return e && e.name === 'AbortError' ? 'cancelled' : AK.share(Object.assign({}, o, {sheet: true}));});
 }
 var row = (tag, attr, ic, t, s) => '<' + tag + ' class="item" data-close ' + attr + '><span class="lead">' + svg(I[ic]) + '</span><span class="body"><span class="title">' + t + '</span><span class="sub">' + s + '</span></span></' + tag + '>';
 var L = layer('sheet', {title: 'Share', body: '<div class="list">' + row('a', 'href="' + esc(AK.sms(o.phone, text)) + '"', 'message', 'Text message', 'Opens your messages app') +
  row('a', 'href="' + esc(AK.mail(o.email, o.title, text)) + '"', 'mail', 'Email', 'Opens your email app') + row('button', 'type="button" data-copy="' + esc(text) + '"', 'copy', 'Copy', 'Paste it anywhere') + '</div>'});
 return L.result.then(() => 'sheet');
};

/* == empty states, illustrations, skeletons == */
var ILL = {
 list: '<rect class="il-s" x="52" y="20" width="64" height="84" rx="9"/><path class="il-l" d="M64 42h40M64 56h40M64 70h26"/><circle class="il-x" cx="114" cy="90" r="15"/><path class="il-k" d="M107.5 90l4.5 4.5 8.5-8.5"/>',
 search: '<rect class="il-s" x="40" y="24" width="62" height="78" rx="9"/><path class="il-l" d="M52 44h38M52 58h38M52 72h22"/><circle class="il-s" cx="110" cy="72" r="17" style="stroke:var(--ak-accent-fill);stroke-width:5"/><path class="il-o" d="M122 84l13 13" style="stroke-width:7"/>',
 inbox: '<path class="il-s" d="M36 70l14-34h68l14 34v24a7 7 0 0 1-7 7H43a7 7 0 0 1-7-7z"/><path class="il-l" d="M37 70h29l6 10h24l6-10h29"/><rect class="il-x" x="68" y="14" width="32" height="24" rx="5" transform="rotate(-8 84 26)"/>',
 chart: '<rect class="il-s" x="36" y="22" width="96" height="80" rx="9"/><path class="il-l" d="M58 88V72M76 88V60M94 88V66" style="stroke-width:10"/><path class="il-o" d="M112 88V44" style="stroke-width:10"/>',
 photo: '<rect class="il-s" x="38" y="26" width="92" height="72" rx="9"/><path class="il-o" d="M50 88l20-22 14 14 10-10 24 18" style="fill:none"/><circle class="il-x" cx="104" cy="48" r="8"/>',
 people: '<circle class="il-s" cx="70" cy="50" r="15"/><path class="il-s" d="M44 98a26 26 0 0 1 52 0z"/><circle class="il-x" cx="108" cy="56" r="12"/><path class="il-o" d="M90 98a20 20 0 0 1 38 0" style="fill:none"/>'
};
AK.illus = name => new Safe('<svg class="illus" viewBox="0 0 168 120" aria-hidden="true"><circle class="il-a" cx="84" cy="64" r="52"/>' + (ILL[name] || ILL.list) + '</svg>');
AK.empty = o => {
 o = o || {};
 var a = o.action, btn = '';
 if (a) {
  var nm = a.act || 'empty:' + a.label;
  if (a.onClick) acts[nm] = a.onClick;
  btn = '<button type="button" class="btn primary" data-act="' + esc(nm) + '">' + (a.icon ? svg(I[a.icon] || I.plus) : '') + esc(a.label) + '</button>';
 }
 return new Safe('<div class="empty">' + AK.illus(o.art || 'list') + (o.title ? '<p class="title">' + esc(o.title) + '</p>' : '') + (o.text ? '<p class="text">' + esc(o.text) + '</p>' : '') + btn + '</div>');
};
AK.skeleton = (n, kind) => {
 n = n || 3;
 if (kind === 'stats') return new Safe('<div class="stats" aria-hidden="true">' + Array(n).fill('<div class="stat"><span class="skel short"></span><span class="skel title"></span></div>').join('') + '</div>');
 return new Safe('<div class="list" aria-hidden="true">' + Array(n).fill('<div class="skel-row"><span class="skel circle"></span><div><span class="skel title"></span><span class="skel short"></span></div></div>').join('') + '</div>');
};

/* == app shell: AK.app({title, tabs:[{id,label,icon,render(page,ctx)}], actions, fab}) == */
AK.app = o => {
 var tabs = o.tabs || [], lastKey = 'ak:' + ns() + ':tab';
 var root = AK.el('div', {class: 'app'});
 var mark = o.icon ? svg(I[o.icon] || I.home) : esc((o.mark || o.title || 'A').trim().charAt(0).toUpperCase());
 var brand = '<div class="brand"><span class="mark" aria-hidden="true">' + mark + '</span><span class="name">' + esc(o.title || '') + '</span></div>';
 root.innerHTML = '<header class="topbar"><button type="button" class="btn icon ghost back" aria-label="Back" hidden>' + svg(I['arrow-left']) + '</button><div class="title-wrap">' + brand + '<div class="title" aria-hidden="true"></div></div><div class="actions"></div></header>' +
  '<nav class="tabbar" aria-label="Main">' + brand + tabs.map(t => '<button type="button" class="tab" data-tab="' + esc(t.id) + '"><span class="ico">' + svg(I[t.icon] || I.list) + '</span><span class="lbl">' + esc(t.label) + '</span><span class="badge" hidden></span></button>').join('') + '</nav>' +
  '<main class="main" id="main"><div class="page" tabindex="-1"></div></main>';
 var q = s => root.querySelector(s);
 var top = q('.topbar'), page = q('.page'), titleEl = q('.topbar .title'), backBtn = q('.back'), actions = q('.topbar .actions');
 (o.actions || []).forEach(a => {actions.append(AK.el('button', {type: 'button', class: 'btn icon ghost', 'aria-label': a.label, 'data-act': a.act || null, onclick: a.onClick ? e => a.onClick(e) : null, html: svg(I[a.icon] || I.more)}));});
 var fab = AK.el('button', {type: 'button', class: 'fab', hidden: true}), fabDesk = AK.el('button', {type: 'button', class: 'btn primary sm hide-phone', hidden: true});
 actions.append(fabDesk); root.append(fab);
 (o.mount ? toEl(o.mount) : D.body).prepend(root);
 var cur = {}, cleanup = null, io = null, memo = {}, stack = [];
 var parse = () => {var p = decodeURIComponent(location.hash.replace(/^#\/?/, '')).split('/'), t = tabs.find(x => x.id === p[0]); return t ? {tab: t.id, param: p.slice(1).join('/') || null} : null;};
 var keyOf = r => r.tab ? r.tab + '/' + (r.param || '') : '';
 function render(refresh) {
  var r = parse();
  if (!r) {var last = ls.get(lastKey), t0 = tabs.find(x => x.id === last) || tabs[0]; history.replaceState(history.state, '', '#/' + t0.id); r = {tab: t0.id, param: null};}
  var changed = keyOf(r) !== keyOf(cur), prev = cur;
  if (changed && cur.tab) memo[keyOf(cur)] = W.scrollY;
  if (typeof cleanup === 'function') {try {cleanup();} catch (e) {console.error(e);}}
  cleanup = null; cur = r; ls.set(lastKey, r.tab);
  var tab = tabs.find(x => x.id === r.tab);
  $$('.tab', root).forEach(b => {if (b.dataset.tab === r.tab) b.setAttribute('aria-current', 'page'); else b.removeAttribute('aria-current');});
  backBtn.hidden = !r.param;
  var ae = D.activeElement, aid = refresh && ae && page.contains(ae) ? ae.id : null, sel = null;
  if (aid) try {sel = [ae.selectionStart, ae.selectionEnd];} catch (e) {sel = null;}
  page.replaceChildren(); page._akKeys = null;
  var ctx = {tab: r.tab, param: r.param, app: api, go, back, page, title: null};
  var out = tab.render(page, ctx);
  if (typeof out === 'function') cleanup = out; else if (out !== undefined) AK.render(page, out); else enhance(page);
  page.classList.remove('enter');
  if (changed && prev.tab && !still()) {void page.offsetWidth; page.classList.add('enter');}
  var h1 = page.querySelector('h1');
  titleEl.textContent = ctx.title || (h1 ? h1.textContent : tab.label);
  if (io) io.disconnect();
  if (h1 && W.IntersectionObserver) {io = new IntersectionObserver(en => top.classList.toggle('show-title', !en[0].isIntersecting && en[0].boundingClientRect.top < 100), {rootMargin: '-60px 0px 0px 0px'}); io.observe(h1);}
  else top.classList.toggle('show-title', !h1);
  var f = r.param || tab.fab === false ? null : tab.fab || o.fab || null;
  root.classList.toggle('has-fab', !!f);
  [fab, fabDesk].forEach(b => {
   b.hidden = !f;
   if (f) {b.innerHTML = svg(I[f.icon || 'plus']) + '<span>' + esc(f.label) + '</span>'; b.onclick = e => {if (f.onClick) f.onClick(ctx, e); else if (f.act && acts[f.act]) acts[f.act](b, e);};}
  });
  if (changed) {W.scrollTo(0, r.param ? 0 : memo[keyOf(r)] || 0); if (prev.tab) page.focus({preventScroll: true});}
  else if (aid) {var n = D.getElementById(aid); if (n) {n.focus({preventScroll: true}); if (sel && sel[0] != null) try {n.setSelectionRange(sel[0], sel[1]);} catch (e) {}}}
  if (changed && o.onChange) o.onChange(r.tab, r.param);
 }
 function go(path, replace) {
  var h = '#/' + String(path).replace(/^[#/]+/, '');
  closeAll();
  settle(() => {
   if (location.hash === h) {render(true); return;}
   if (replace) location.replace(h); else {stack.push(location.hash); location.hash = h;}
  });
 }
 function back() {if (stack.length) history.back(); else go('/' + cur.tab, true);}
 lis(backBtn, 'click', back);
 lis(W, 'hashchange', () => {if (stack.length && location.hash === stack[stack.length - 1]) stack.pop(); render();});
 lis(q('.tabbar'), 'click', e => {
  var b = e.target.closest('.tab'); if (!b) return;
  var id = b.dataset.tab;
  if (id === cur.tab && !cur.param) {W.scrollTo({top: 0, behavior: still() ? 'auto' : 'smooth'}); return;}
  go('/' + id, !(cur.tab === tabs[0].id && !cur.param));
 });
 lis(W, 'scroll', () => top.classList.toggle('is-scrolled', W.scrollY > 4), {passive: true});
 var api = {
  el: root, page, go, back,
  refresh: () => render(true),
  route: () => {return {tab: cur.tab, param: cur.param};},
  badge: (id, n) => {var b = root.querySelector('.tab[data-tab="' + CSS.escape(id) + '"] .badge'); if (b) {b.hidden = !n; b.textContent = n > 99 ? '99+' : String(n || '');}},
  title: t => titleEl.textContent = t
 };
 render();
 return api;
};

/* == pwa: manifest from a Blob URL + generated icons (no service worker, nothing needs a server) == */
var deferredInstall = null;
lis(W, 'beforeinstallprompt', e => {e.preventDefault(); deferredInstall = e;});
AK.pwa = o => {
 o = o || {};
 var hex = c => c ? '#' + c.slice(0, 3).map(v => Math.round(v).toString(16).padStart(2, '0')).join('') : null;
 var name = o.name || D.title || 'App', short = o.short_name || name.split(/[\s|]+/)[0].slice(0, 12);
 var theme = o.theme_color || hex(probe('var(--c-accent,#2f6fed)')) || '#2f6fed', bg = o.background_color || hex(probe('var(--c-bg,#ffffff)')) || '#ffffff', fg = hex(probe('var(--c-on-accent,#ffffff)')) || '#ffffff';
 var make = () => {
  var icon = s => {
   var cv = D.createElement('canvas'); cv.width = cv.height = s;
   var x = cv.getContext('2d'); x.fillStyle = theme; x.fillRect(0, 0, s, s); x.fillStyle = fg;
   x.font = '700 ' + Math.round(s * .46) + 'px ' + (getComputedStyle(R).getPropertyValue('--font-display').trim() || 'sans-serif');
   x.textAlign = 'center'; x.textBaseline = 'middle'; x.fillText((o.letter || short.charAt(0) || 'A').toUpperCase(), s / 2, s * .53);
   return cv.toDataURL('image/png');
  };
  var base = location.href.split('#')[0];
  var m = {name, short_name: short, start_url: base, scope: base.replace(/[^/]*$/, ''), display: 'standalone', background_color: bg, theme_color: theme, icons: [192, 512].map(s => {return {src: icon(s), sizes: s + 'x' + s, type: 'image/png', purpose: 'any maskable'};})};
  var put = (sel, tag, attrs) => {var n = D.head.querySelector(sel) || D.head.appendChild(D.createElement(tag)); Object.keys(attrs).forEach(k => n.setAttribute(k, attrs[k]));};
  put('link[rel=manifest]', 'link', {rel: 'manifest', href: URL.createObjectURL(new Blob([JSON.stringify(m)], {type: 'application/manifest+json'}))});
  put('meta[name=theme-color]', 'meta', {name: 'theme-color', content: theme});
  put('link[rel=apple-touch-icon]', 'link', {rel: 'apple-touch-icon', href: m.icons[0].src});
   return m;
 };
 return (D.fonts && D.fonts.ready ? D.fonts.ready : Promise.resolve()).then(make);
};
AK.pwa.canInstall = () => !!deferredInstall;
AK.pwa.install = () => {
 if (!deferredInstall) return Promise.resolve(false);
 var e = deferredInstall; deferredInstall = null; e.prompt();
 return e.userChoice.then(r => r.outcome === 'accepted');
};

/* == tune: contrast-safe accent/status colours for this style, dark/light, hard shadows == */
var probeEl = null;
function probe(v) {
 if (!probeEl) {probeEl = D.createElement('i'); probeEl.style.cssText = 'position:absolute;width:0;height:0;overflow:hidden;visibility:hidden';}
 if (!probeEl.isConnected) R.appendChild(probeEl);
 probeEl.style.color = ''; probeEl.style.color = v;
 var c = getComputedStyle(probeEl).color, m = c.match(/rgba?\(([^)]+)\)/), p;
 if (m) {p = m[1].split(/[\s,/]+/).filter(Boolean).map(parseFloat); return [p[0], p[1], p[2]];}
 m = c.match(/color\(srgb ([^)]+)\)/);
 if (m) {p = m[1].split(/[\s/]+/).filter(Boolean).map(parseFloat); return [p[0] * 255, p[1] * 255, p[2] * 255];}
 return null;
}
var lum = c => {var f = v => {v /= 255; return v <= .03928 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4);}; return .2126 * f(c[0]) + .7152 * f(c[1]) + .0722 * f(c[2]);};
var contrast = (a, b) => {var x = lum(a), y = lum(b); return (Math.max(x, y) + .05) / (Math.min(x, y) + .05);};
var mixC = (a, b, p) => [0, 1, 2].map(i => a[i] * p + b[i] * (1 - p));
var rgbS = c => 'rgb(' + c.map(v => Math.round(v)).join(' ') + ')';
AK.tune = () => {
 var bg = probe('var(--c-bg,#f5f6f8)'), sf = probe('var(--c-surface,#ffffff)'), ink = probe('var(--c-ink,#15181e)'), ac = probe('var(--c-accent,#2f6fed)');
 if (!bg || !ink || !sf || !ac) return;
 var fit = (col, min, against, tint) => {
  for (var p = 1; p >= -0.001; p -= .05) {
   var c = mixC(col, ink, p), list = against.concat(tint ? [mixC(c, sf, tint)] : []);
   if (list.every(b => contrast(c, b) >= min)) return c;
  }
  return ink;
 };
 var dark = lum(bg) < .2, s = R.style;
 R.classList.toggle('ak-dark', dark); s.colorScheme = dark ? 'dark' : 'light';
 s.setProperty('--ak-accent-fg', rgbS(fit(ac, 4.6, [bg, sf], .16)));
 s.setProperty('--ak-accent-fill', rgbS(fit(ac, 2.2, [bg, sf])));
 [['ok', [22, 163, 74]], ['warn', [217, 119, 6]], ['bad', [220, 38, 38]]].forEach(k => s.setProperty('--ak-' + k[0], rgbS(fit(k[1], 4.7, [bg, sf], .14))));
 var sh = getComputedStyle(R).getPropertyValue('--shadow-card');
 R.classList.toggle('ak-hard', /(^|,)\s*-?[1-9]\d*px\s+-?[1-9]\d*px\s+0(px)?\s/.test(sh + ' '));
};

/* == boot == */
AK.tune();
function boot() {
 AK.tune(); enhance(D.body);
 new MutationObserver(ms => {ms.forEach(m => {m.addedNodes.forEach(n => {if (n.nodeType === 1) enhance(n);});});}).observe(D.body, {childList: true, subtree: true});
}
if (D.body) boot(); else lis(D, 'DOMContentLoaded', boot);
})();

