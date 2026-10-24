/* Parts and stock | app.js | blueprint "inventory-tracker", built on appkit (window.AK, see APPKIT.md)
   The markup of every screen lives in index.html as <template id="view-..."> blocks; this file copies a template into the
   page and fills the parts marked data-text="..." (words, numbers) and data-slot="..." (lists, show/hide).
   Sections:
     1. Saved data and settings      2. Small helpers                3. Stock maths (status, value, reorder amounts)
     4. Changing stock + history     5. Stock screen                 6. Item screen
     7. Item sheet (add / edit)      8. Use / receive sheet          9. Reorder screen + orders to suppliers
    10. Activity screen             11. Suppliers                   12. Importing items (CSV / paste)
    13. More screen + backup        14. Printing                    15. Sample data
    16. Actions (buttons)           17. Start */
(() => {
'use strict';

/* ==== 1. Saved data and settings ==== */
const SETTINGS = {currency: 'USD', orderNote: 'Please confirm the price and delivery date. Thank you!'};
const DATA = {version: 1, items: [], moves: [], suppliers: [], settings: SETTINGS};
// items: {id, name, sku, cat, loc, qty, min, cost, price, supplier, notes, orderQty, ordered, updated, sample}
// moves (newest first): {id, item, name, sku, at, kind: use|receive|count|new|import, delta, after, note, cost, tap}
// suppliers: {id, name, phone, email, notes}
const LOOK = {show: 'all', cat: '', sort: 'name', log: 'all'};
const db = AK.store('inventory-tracker', DATA);
const ui = AK.store('inventory-tracker-ui', LOOK);
const pics = AK.store('inventory-tracker-photos', {});        // photos apart from the data, so a tap on +/- stays fast: {id: photo, id_t: thumbnail}
const BUSINESS = ((document.querySelector('meta[name="application-name"]') || {}).content || '').trim() || 'Stock';
const MAX_MOVES = 5000, TAP_MERGE_MS = 120000, ROOM = 4.6e6;
const settings = () => Object.assign({}, SETTINGS, db.get('settings'));
const look = () => Object.assign({}, LOOK, ui.get());
const STOCK_PAGE = 150;
let app = null, stockQ = '', logQ = '', logLimit = 150, stockLimit = STOCK_PAGE;

/* ==== 2. Small helpers ==== */
const $ = (sel, root) => (root || document).querySelector(sel);
const $$ = (sel, root) => Array.from((root || document).querySelectorAll(sel));
const pad = n => String(n).padStart(2, '0');
const loc = () => AK.locale || navigator.language || 'en-US';
const slot = (root, key) => $('[data-slot="' + key + '"]', root);
const show = (el, on) => { if (el) el.hidden = !on; };
const clip = (v, n) => String(v == null ? '' : v).replace(/\s+/g, ' ').trim().slice(0, n);
const num = v => { const n = +v; return isFinite(n) ? n : 0; };
const round3 = n => Math.round(num(n) * 1000) / 1000;
function round2(n) { const r = Math.round(Math.abs(num(n)) * 100 + 1e-7) / 100; return n < 0 ? -r : r; }
const iso = d => d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
const dayOf = at => iso(new Date(at));
function tpl(id) { const f = document.getElementById(id).content.cloneNode(true); hydrate(f); return f; }
function hydrate(root) { $$('i[data-icon]', root).forEach(i => i.replaceWith(icon(i.dataset.icon, i.getAttribute('class')))); }
function icon(name, cls) { const t = document.createElement('template'); t.innerHTML = String(AK.icon(name, cls || '')); return t.content.firstChild; }
function put(root, key, value) { $$('[data-text="' + key + '"]', root).forEach(el => { el.textContent = value == null ? '' : String(value); }); }
function piece(id, fn) { const el = tpl(id).firstElementChild; fn(el); return el; }
function curDigits(cur) { try { return new Intl.NumberFormat('en', {style: 'currency', currency: cur}).resolvedOptions().maximumFractionDigits; } catch (e) { return 2; } }
const money = n => { const c = settings().currency; return AK.fmt.money(n, c, curDigits(c)); };
const moneyShort = n => {
  const c = settings().currency, a = Math.abs(n);
  if (a >= 1e6) try { return new Intl.NumberFormat(loc(), {style: 'currency', currency: c, notation: 'compact', maximumFractionDigits: 1}).format(n); } catch (e) { /* fall through */ }
  return a >= 10000 ? AK.fmt.money(n, c, 0) : money(n);
};
const qtyText = n => AK.fmt.number(round3(n));
const plural = (n, one, many) => AK.fmt.number(n) + ' ' + (n === 1 ? one : many || one + 's');
const initials = name => { const p = String(name || '?').trim().split(/\s+/); return ((p[0] || '?')[0] + (p.length > 1 ? p[p.length - 1][0] : (p[0] || '')[1] || '')).toUpperCase(); };
function currencySymbol() { try { return new Intl.NumberFormat(loc(), {style: 'currency', currency: settings().currency, maximumFractionDigits: 0}).formatToParts(0).find(p => p.type === 'currency').value; } catch (e) { return '$'; } }
// one shared collator: localeCompare with options builds a new one on every call, which is very slow in big sorts
const COLLATOR = new Intl.Collator(undefined, {numeric: true, sensitivity: 'base'});
const cmpText = (a, b) => COLLATOR.compare(String(a || ''), String(b || ''));

/* ==== 3. Stock maths ==== */
/** 'out' at 0, 'low' at or under the minimum, otherwise 'ok' */
function statusOf(it) { const q = num(it.qty), m = num(it.min); return q <= 0 ? 'out' : m > 0 && q <= m ? 'low' : 'ok'; }
const STATUS = {out: {label: 'Out of stock', short: 'Out', cls: 'bad'}, low: {label: 'Low', short: 'Low', cls: 'warn'}, ok: {label: 'In stock', short: 'In stock', cls: 'ok'}};
/** order amount: the owner's number, or enough to fill back up to twice the minimum */
function suggestOrder(it) {
  if (num(it.orderQty) > 0) return num(it.orderQty);
  const q = Math.max(0, num(it.qty)), m = num(it.min);
  return Math.max(1, Math.ceil((m > 0 ? m * 2 : 1) - q));
}
const lineCost = (q, cost) => Math.round(Math.max(0, num(q)) * num(cost) * 100);
function totals(items) {
  const t = {value: 0, retail: 0, units: 0, low: 0, out: 0, count: items.length};
  items.forEach(it => { t.value += lineCost(it.qty, it.cost); t.retail += lineCost(it.qty, it.price); t.units += Math.max(0, num(it.qty)); const s = statusOf(it); if (s !== 'ok') t[s]++; });
  t.value /= 100; t.retail /= 100; t.units = round3(t.units);
  return t;
}
const needOrder = items => items.filter(it => statusOf(it) !== 'ok');
const supKey = s => String(s || '').trim().toLowerCase();
function supplierFor(name) { const k = supKey(name); return k ? (db.get('suppliers') || []).find(s => supKey(s.name) === k) || null : null; }
/** items to reorder, one group per supplier ("No supplier yet" last) */
function orderGroups(items) {
  const m = new Map();
  needOrder(items).forEach(it => { const k = supKey(it.supplier); if (!m.has(k)) m.set(k, {key: k, name: clip(it.supplier, 60), items: []}); m.get(k).items.push(it); });
  return Array.from(m.values()).map(g => {
    g.items.sort((a, b) => (statusOf(a) === 'out' ? 0 : 1) - (statusOf(b) === 'out' ? 0 : 1) || cmpText(a.name, b.name));
    g.cost = g.items.reduce((t, it) => t + lineCost(suggestOrder(it), it.cost), 0) / 100;
    return g;
  }).sort((a, b) => (a.key ? 0 : 1) - (b.key ? 0 : 1) || cmpText(a.name, b.name));
}
/** the message a supplier gets */
function orderText(g) {
  const lines = g.items.map(it => '- ' + qtyText(suggestOrder(it)) + ' x ' + it.name + (it.sku ? ' (part ' + it.sku + ')' : ''));
  return ['Hello' + (g.name ? ' ' + g.name : '') + ', this is ' + BUSINESS + '. Please send:', ''].concat(lines, ['', settings().orderNote, BUSINESS]).filter((l, i, a) => l || (a[i - 1] !== '' && i > 0)).join('\n').trim();
}
const usedSince = (id, days) => { const from = Date.now() - days * 864e5; return db.get('moves').filter(m => m.item === id && m.kind === 'use' && Date.parse(m.at) >= from).reduce((t, m) => t - m.delta, 0); };

/* ==== 4. Changing stock, and the history it writes ==== */
/** change one item inside a draft state: kind use (n out), receive (n in), count (set to n), new / import (set to n) */
function applyChange(s, id, kind, n, note, opts) {
  const item = s.items.find(x => x.id === id);
  if (!item) return null;
  const before = num(item.qty), now = new Date().toISOString();
  let after = kind === 'use' ? before - n : kind === 'receive' ? before + n : n;
  after = Math.max(0, round3(after));
  const delta = round3(after - before);
  if (!delta && kind !== 'count') return {item, before, after, delta};
  item.qty = after; item.updated = now;
  if (delta > 0 && statusOf(item) === 'ok') { item.ordered = ''; item.orderQty = null; }
  const last = s.moves[0];
  if (opts && opts.tap && last && last.tap && last.item === id && last.kind === kind && Date.now() - Date.parse(last.at) < TAP_MERGE_MS) {
    s.moves[0] = Object.assign({}, last, {delta: round3(last.delta + delta), after, at: now});     // quick taps in a row become one line
  } else {
    const m = {id: AK.id(), item: id, name: item.name, sku: item.sku || '', at: now, kind, delta, after, note: clip(note, 120), cost: num(item.cost)};
    if (opts && opts.tap) m.tap = 1;
    s.moves.unshift(m);
    if (s.moves.length > MAX_MOVES) s.moves.length = MAX_MOVES;
  }
  return {item, before, after, delta};
}
/** a shallow working copy: only the touched items are copied, so a tap stays fast with thousands of records (Undo still works) */
function draftFor(ids) { const s = db.get(), set = new Set(ids); return {items: s.items.map(x => set.has(x.id) ? Object.assign({}, x) : x), moves: s.moves.slice()}; }
function changeStock(id, kind, n, note, opts) {
  const d = draftFor([id]), res = applyChange(d, id, kind, n, note, opts);
  db.set(d);
  return res;
}
/** the big + and - buttons: one unit, with an Undo */
function tap(id, dir) {
  const it = db.find('items', id);
  if (!it) return;
  if (dir < 0 && num(it.qty) <= 0) { AK.toast('Already at 0. Use "Set the exact count" if the shelf says otherwise.', 'warn'); return; }
  quickTap = true;
  const r = changeStock(id, dir < 0 ? 'use' : 'receive', 1, '', {tap: true});
  quickTap = false;
  const st = statusOf(r.item);
  AK.toast((dir < 0 ? 'Used 1 ' : 'Received 1 ') + r.item.name + ': ' + qtyText(r.after) + ' left' + (st === 'out' ? ', out of stock' : st === 'low' ? ', running low' : ''), {type: st === 'ok' ? 'ok' : 'warn', undo: () => { quickTap = true; db.undo(); quickTap = false; }});
}
const KIND = {use: {word: 'Used', icon: 'arrow-down'}, receive: {word: 'Received', icon: 'arrow-up'}, count: {word: 'Counted', icon: 'clipboard'}, new: {word: 'Added', icon: 'plus'}, import: {word: 'Imported', icon: 'upload'}};
function moveRow(m, withName, withDate) {
  const it = db.find('items', m.item), k = KIND[m.kind] || KIND.count;
  return piece('tpl-move-row', li => {
    const lead = slot(li, 'lead');
    lead.append(icon(k.icon)); lead.classList.add('k-' + m.kind);
    put(li, 'title', withName ? k.word + ' · ' + (it ? it.name : m.name) : k.word + (m.kind === 'count' && !m.delta ? ', no change' : ''));
    put(li, 'sub', [m.note, withDate ? AK.fmt.day(m.at) + ', ' + AK.fmt.time(m.at) : AK.fmt.time(m.at)].filter(Boolean).join(' · '));
    put(li, 'delta', (m.delta > 0 ? '+' : m.delta < 0 ? '−' : '±') + qtyText(Math.abs(m.delta)));
    put(li, 'after', qtyText(m.after) + ' after');
  });
}

/* ==== 5. Stock screen: numbers, low-stock banner, search, filters, the list ==== */
function renderStock(page, ctx) {
  if (ctx.param) return renderItem(page, ctx.param);
  const items = db.get('items'), u = look();
  AK.currency = settings().currency;
  page.append(tpl('view-stock'));
  put(page, 'today', AK.fmt.date(new Date(), 'long'));
  show(slot(page, 'sample-note'), items.some(it => it.sample));
  if (!items.length) { put(page, 'summary', 'Nothing on the shelf yet.'); show(slot(page, 'welcome'), true); return; }
  show(slot(page, 'stock-body'), true);
  stockNumbers(page, items);
  // filters
  $$('input[name="show"]', page).forEach(r => { r.checked = r.value === u.show; r.addEventListener('change', () => ui.set({show: r.value})); });
  const sort = $('#stock-sort', page);
  sort.value = u.sort;
  sort.addEventListener('change', () => ui.set({sort: sort.value}));
  const cats = new Map();
  items.forEach(it => { const c = clip(it.cat, 40); if (c) cats.set(c, (cats.get(c) || 0) + 1); });
  const chips = slot(page, 'cats');
  if (cats.size < 2 && !u.cat) chips.hidden = true;
  else {
    const add = (name, n, val) => { const c = piece('tpl-chip', el => { put(el, 'name', name); put(el, 'n', AK.fmt.number(n)); el.dataset.cat = val; el.setAttribute('aria-pressed', String(u.cat === val)); }); chips.append(c); };
    add('All', items.length, '');
    Array.from(cats.keys()).sort(cmpText).forEach(c => add(c, cats.get(c), c));
    if (u.cat && !cats.has(u.cat)) add(u.cat, 0, u.cat);
    AK.on(chips, 'click', '.chip', (e, c) => ui.set({cat: c.dataset.cat}));
  }
  const search = $('#stock-search', page);
  search.value = stockQ;
  search.addEventListener('input', () => { stockQ = search.value; stockLimit = STOCK_PAGE; drawStockList(page); });
  drawStockList(page);
}
/** the summary, low-stock banner, the four numbers and the filter counts (also used after a quick +/- tap) */
function stockNumbers(page, items) {
  const t = totals(items), u = look();
  put(page, 'summary', plural(items.length, 'item') + ' \u00b7 ' + qtyText(t.units) + ' units on hand');
  put(page, 'low-title', t.out ? plural(t.out, 'item') + ' out of stock' + (t.low ? ', ' + AK.fmt.number(t.low) + ' running low.' : '.') : plural(t.low, 'item') + ' running low.');
  put(page, 'low-text', 'They are on the reorder list, grouped by supplier.');
  show(slot(page, 'low-banner'), t.low + t.out > 0);
  put(page, 'value', moneyShort(t.value));
  put(page, 'value-meta', t.retail > 0 ? 'at cost \u00b7 sells for ' + moneyShort(t.retail) : 'at what you paid');
  put(page, 'items', AK.fmt.number(items.length));
  put(page, 'items-meta', qtyText(t.units) + ' units');
  put(page, 'low', AK.fmt.number(t.low)); put(page, 'out', AK.fmt.number(t.out));
  const lowCard = $('.stat-low', page), outCard = $('.stat-out', page);
  if (t.low) lowCard.dataset.hot = 'warn'; else delete lowCard.dataset.hot;
  if (t.out) outCard.dataset.hot = 'bad'; else delete outCard.dataset.hot;
  lowCard.setAttribute('aria-pressed', String(u.show === 'low')); outCard.setAttribute('aria-pressed', String(u.show === 'out'));
  put(page, 'n-all', items.length); put(page, 'n-low', t.low); put(page, 'n-out', t.out);
}
/** after a quick tap on the stock list: redraw only the numbers and the changed row (a full redraw of a long list is slow on phones) */
let quickTap = false;
function softRefresh() {
  const r = app.route(), page = app.page;
  if (r.tab !== 'stock' || r.param || !stockUl || !page.contains(stockUl)) return false;
  const focusId = document.activeElement && page.contains(document.activeElement) ? document.activeElement.id : '';
  stockNumbers(page, db.get('items'));
  drawStockList(page);
  const back = focusId && document.getElementById(focusId);
  if (back && back !== document.activeElement) back.focus({preventScroll: true});
  return true;
}
const haystack = it => [it.name, it.sku, it.cat, it.loc, it.supplier, it.notes].join(' ').toLowerCase();
function visibleItems(items, u, q) {
  const words = String(q || '').toLowerCase().split(/\s+/).filter(Boolean);
  return items.filter(it => (u.show === 'all' || statusOf(it) === u.show) && (!u.cat || clip(it.cat, 40) === u.cat) && words.every(w => haystack(it).indexOf(w) >= 0));
}
const RANK = {out: 0, low: 1, ok: 2};
function sortItems(list, how) {
  const byName = (a, b) => cmpText(a.name, b.name);
  const by = {
    name: byName,
    urgent: (a, b) => RANK[statusOf(a)] - RANK[statusOf(b)] || num(a.qty) / (num(a.min) || 1) - num(b.qty) / (num(b.min) || 1) || byName(a, b),
    qty: (a, b) => num(a.qty) - num(b.qty) || byName(a, b),
    value: (a, b) => lineCost(b.qty, b.cost) - lineCost(a.qty, a.cost) || byName(a, b),
    loc: (a, b) => (a.loc ? 0 : 1) - (b.loc ? 0 : 1) || cmpText(a.loc, b.loc) || byName(a, b),
    recent: (a, b) => String(b.updated || '').localeCompare(String(a.updated || '')) || byName(a, b)
  };
  return list.slice().sort(by[how] || byName);
}
function drawStockList(page) {
  const items = db.get('items'), u = look(), list = sortItems(visibleItems(items, u, stockQ), u.sort), ul = slot(page, 'list') || (stockUl && page.contains(stockUl) ? stockUl : null);
  if (!ul) return;
  put(page, 'count', list.length === items.length ? '' : 'Showing ' + AK.fmt.number(list.length) + ' of ' + plural(items.length, 'item'));
  const empty = stockQ ? {art: 'search', title: 'Nothing matches "' + clip(stockQ, 30) + '"', text: 'Try part of the name, the part number or the bin.'} :
    {art: 'list', title: u.show === 'all' ? 'No items in this category' : u.show === 'low' ? 'Nothing is running low' : 'Nothing is out of stock', text: 'Pick All to see every item.'};
  // the list element is kept between redraws and rows are cached, so a tap on +/- only redraws that one row;
  // long lists show STOCK_PAGE rows at a time (search finds everything)
  if (stockUl && stockUl !== ul) ul.replaceWith(stockUl);
  stockUl = stockUl || ul;
  AK.list(stockUl, list.slice(0, stockLimit), rowHtml, {empty: '<li class="card list-empty">' + AK.empty(empty) + '</li>'});
  show(slot(page, 'list-more'), list.length > stockLimit);
  put(page, 'more-text', 'Showing ' + AK.fmt.number(Math.min(stockLimit, list.length)) + ' of ' + AK.fmt.number(list.length) + '. Search to find any item.');
}
const rowCache = new Map();
let stockUl = null;
function rowHtml(it) {
  const thumb = pics.get(it.id + '_t') || '', sig = [it.name, it.sku, it.loc, it.cat, it.supplier, it.qty, it.min, it.ordered, thumb.length].join('\u0001'), c = rowCache.get(it.id);
  if (c && c.sig === sig) return c.html;
  const html = stockRow(it).outerHTML;
  rowCache.set(it.id, {sig, html});
  return html;
}
function stockRow(it) {
  return piece('tpl-stock-row', li => {
    const st = statusOf(it), thumb = pics.get(it.id + '_t'), img = $('img', li), min = num(it.min);
    li.dataset.status = st;
    put(li, 'name', it.name);
    put(li, 'sub', [it.sku, it.loc].filter(Boolean).join(' · ') || it.cat || it.supplier || '');
    if (thumb) { img.src = thumb; img.hidden = false; $('.thumb-text', li).remove(); } else { img.remove(); put(li, 'initials', initials(it.name)); }
    const pill = slot(li, 'status');
    if (st !== 'ok') { pill.hidden = false; pill.classList.add(STATUS[st].cls); pill.textContent = STATUS[st].short; }
    show(slot(li, 'ordered'), !!it.ordered);
    const g = slot(li, 'gauge');
    if (min > 0) { g.style.setProperty('--value', String(Math.round(Math.min(100, num(it.qty) / (min * 2) * 100)))); g.classList.add(st === 'ok' ? 'ok' : STATUS[st].cls); } else g.remove();
    put(li, 'qty', qtyText(it.qty));
    $$('[data-act]', li).forEach(b => { b.dataset.id = it.id; });
    const dec = $('[data-act="dec"]', li), inc = $('[data-act="inc"]', li), mv = $('[data-act="move"]', li);
    dec.id = 'dec-' + it.id; inc.id = 'inc-' + it.id; mv.id = 'mv-' + it.id;
    dec.setAttribute('aria-label', 'Use one ' + it.name); inc.setAttribute('aria-label', 'Receive one ' + it.name);
    mv.setAttribute('aria-label', qtyText(it.qty) + ' on hand. Use or receive more of ' + it.name);
    $('[data-act="open-item"]', li).id = 'open-' + it.id;
  });
}

/* ==== 6. Item screen: big quantity, details, history ==== */
function renderItem(page, id) {
  const it = db.find('items', id);
  if (!it) { AK.render(page, AK.html`<header class="page-head"><div><h1>Item not found</h1></div></header><div class="card">${AK.empty({art: 'search', title: 'This item is gone', text: 'It may have been deleted.', action: {label: 'Back to stock', act: 'go-stock'}})}</div>`); return; }
  AK.currency = settings().currency;
  page.append(tpl('view-item'));
  const st = statusOf(it), min = num(it.min), q = num(it.qty);
  put(page, 'cat', it.cat || 'Item'); put(page, 'name', it.name);
  put(page, 'sub', [it.sku ? 'Part ' + it.sku : '', it.loc ? 'Location ' + it.loc : ''].filter(Boolean).join(' · ') || 'No part number or location yet');
  const photo = pics.get(it.id), img = slot(page, 'photo'), add = slot(page, 'photo-add');
  if (photo) { img.src = photo; img.alt = it.name; img.hidden = false; add.classList.add('on-photo'); $('span', add).textContent = 'Change photo'; slot(page, 'photo-box').classList.add('has-photo'); }
  else img.remove();
  put(page, 'qty', qtyText(q));
  const pill = slot(page, 'status');
  pill.classList.add(STATUS[st].cls); pill.textContent = STATUS[st].label + (it.ordered ? ' · ordered ' + AK.fmt.date(it.ordered, 'day') : '');
  put(page, 'min-text', min > 0 ? 'Minimum ' + qtyText(min) : 'No minimum set');
  const g = slot(page, 'gauge');
  if (min > 0) { g.style.setProperty('--value', String(Math.round(Math.min(100, q / (min * 2) * 100)))); g.classList.add(st === 'ok' ? 'ok' : STATUS[st].cls); } else g.remove();
  $$('[data-act]', page).forEach(b => { b.dataset.id = it.id; });
  const used = usedSince(it.id, 30), price = num(it.price), cost = num(it.cost);
  put(page, 'value', money(lineCost(q, cost) / 100)); put(page, 'value-meta', price ? 'sells for ' + money(lineCost(q, price) / 100) : 'on the shelf now');
  put(page, 'cost', cost ? money(cost) : '—'); put(page, 'cost-meta', 'per unit');
  put(page, 'price', price ? money(price) : '—'); put(page, 'margin', price && cost ? 'margin ' + AK.fmt.percent((price - cost) / price) : price ? 'add a cost to see margin' : 'not sold on its own');
  put(page, 'used', qtyText(used));
  put(page, 'used-meta', used > 0 ? (q > 0 ? 'about ' + plural(Math.max(1, Math.round(q / (used / 30))), 'day') + ' left at this rate' : 'none left') : 'no uses logged');
  put(page, 'sku', it.sku || '—'); put(page, 'loc', it.loc || '—'); put(page, 'supplier', it.supplier || 'None yet'); put(page, 'notes', it.notes);
  show(slot(page, 'notes-row'), !!it.notes);
  contactButtons(slot(page, 'contact'), it.supplier);
  const hist = db.get('moves').filter(m => m.item === it.id), ul = slot(page, 'history');
  hist.slice(0, 40).forEach(m => ul.append(moveRow(m, false, true)));
  put(page, 'history-count', hist.length > 40 ? 'latest 40 of ' + AK.fmt.number(hist.length) : hist.length ? plural(hist.length, 'change') : '');
  show(ul, hist.length > 0); show(slot(page, 'history-empty'), !hist.length);
  $('[data-act="dec"]', page).setAttribute('aria-label', 'Use one ' + it.name);
  $('[data-act="inc"]', page).setAttribute('aria-label', 'Receive one ' + it.name);
}
function contactButtons(host, supplierName) {
  const sp = supplierFor(supplierName);
  if (sp && sp.phone) { host.append(link(AK.tel(sp.phone), 'phone', 'Call')); host.append(link(AK.sms(sp.phone), 'message', 'Text')); }
  if (sp && sp.email) host.append(link(AK.mail(sp.email), 'mail', 'Email'));
  if (supplierName && !(sp && (sp.phone || sp.email))) {
    const b = AK.el('button', {type: 'button', class: 'btn ghost sm', 'data-act': 'edit-supplier', 'data-name': supplierName}, [AK.icon('edit'), 'Add supplier phone or email']);
    host.append(b);
  }
  show(host, host.children.length > 0);
}
const link = (href, ic, label) => AK.el('a', {class: 'btn sm', href}, [AK.icon(ic), label]);

/* ==== 7. Item sheet: add or edit, with an optional photo ==== */
function storageUsed() { let n = 0; try { for (let i = 0; i < localStorage.length; i++) { const k = localStorage.key(i); n += k.length + (localStorage.getItem(k) || '').length; } } catch (e) { n = 0; } return n; }
function makeThumb(url, size) {
  return new Promise(res => {
    const im = new Image();
    im.onload = () => { const s = Math.min(1, size / Math.max(im.width, im.height)), c = document.createElement('canvas'); c.width = Math.max(1, Math.round(im.width * s)); c.height = Math.max(1, Math.round(im.height * s)); c.getContext('2d').drawImage(im, 0, 0, c.width, c.height); res(c.toDataURL('image/jpeg', 0.7)); };
    im.onerror = () => res(null);
    im.src = url;
  });
}
function savePhoto(id, url) {
  if (!url) { pics.update(s => { delete s[id]; delete s[id + '_t']; }); return Promise.resolve(true); }
  return makeThumb(url, 120).then(th => { pics.update(s => { s[id] = url; if (th) s[id + '_t'] = th; }); return true; });
}
function editItem(id) {
  const it = id ? db.find('items', id) : null;
  if (id && !it) return null;
  const body = tpl('tpl-item-form'), form = $('form', body), items = db.get('items');
  const opts = (sel, vals) => Array.from(new Set(vals.map(v => clip(v, 60)).filter(Boolean))).sort(cmpText).forEach(v => $(sel, form).append(new Option(v)));
  opts('#if-cats', items.map(x => x.cat)); opts('#if-locs', items.map(x => x.loc)); opts('#if-sups', items.map(x => x.supplier).concat((db.get('suppliers') || []).map(x => x.name)));
  $$('[data-text="cur"]', form).forEach(el => { el.textContent = currencySymbol(); });
  put(form, 'qty-label', it ? 'On hand now' : 'Starting amount');
  AK.fill(form, it ? {name: it.name, sku: it.sku, cat: it.cat, loc: it.loc, qty: num(it.qty), min: num(it.min), cost: it.cost === '' || it.cost == null ? '' : num(it.cost), price: it.price === '' || it.price == null ? '' : num(it.price), supplier: it.supplier, notes: it.notes} : {qty: 0, min: 0});
  // photo
  let photo = it ? pics.get(it.id) : null, photoChanged = false;
  const prev = slot(form, 'photo-prev'), pick = slot(form, 'photo-pick'), drop = slot(form, 'photo-remove');
  const drawPhoto = () => { const img = $('img', prev); img.hidden = !photo; if (photo) img.src = photo; else img.removeAttribute('src'); prev.classList.toggle('on', !!photo); show(drop, !!photo); put(form, 'photo-label', photo ? 'Change photo' : 'Add photo'); };
  drawPhoto();
  pick.addEventListener('click', () => {
    pick.setAttribute('aria-busy', 'true');
    AK.photo(null, {max: 640, quality: 0.75}).then(url => {
      pick.removeAttribute('aria-busy');
      if (!url) return;
      if (storageUsed() + url.length * 1.2 > ROOM) { AK.toast('No room for more photos on this device. Remove a few photos first (More shows the space used).', {type: 'bad', duration: 7000}); return; }
      photo = url; photoChanged = true; drawPhoto();
    });
  });
  drop.addEventListener('click', () => { photo = null; photoChanged = true; drawPhoto(); });
  form.addEventListener('submit', e => e.preventDefault());
  const save = () => {
    const skuTaken = v => { const k = supKey(v); return k && db.get('items').some(x => x.id !== (it && it.id) && supKey(x.sku) === k); };
    const v = AK.validate(form, {name: {required: true}, qty: {min: 0}, min: {min: 0}, cost: {min: 0}, price: {min: 0}, sku: {test: x => skuTaken(x) ? 'Another item already uses this part number' : true}});
    if (!v.ok) return false;
    const x = v.values, data = {name: clip(x.name, 80), sku: clip(x.sku, 40), cat: clip(x.cat, 40), loc: clip(x.loc, 40), min: Math.max(0, round3(x.min)),
      cost: x.cost == null ? '' : round2(x.cost), price: x.price == null ? '' : round2(x.price), supplier: clip(x.supplier, 60), notes: String(x.notes || '').trim().slice(0, 500)};
    const q = Math.max(0, round3(x.qty)), now = new Date().toISOString();
    let itemId = it ? it.id : null;
    db.update(s => {
      if (it) {
        const item = s.items.find(y => y.id === it.id);
        Object.assign(item, data, {updated: now});
        if (q !== num(item.qty)) applyChange(s, item.id, 'count', q, 'Edited');
        else if (statusOf(item) === 'ok') { item.ordered = ''; item.orderQty = null; }
      } else {
        const item = Object.assign({id: AK.id()}, data, {qty: 0, orderQty: null, ordered: '', updated: now});
        s.items.push(item); itemId = item.id;
        if (q > 0) applyChange(s, item.id, 'new', q, 'Starting amount');
      }
    });
    if (photoChanged) savePhoto(itemId, photo);
    AK.toast(it ? 'Saved' : data.name + ' added', {type: 'ok', undo: () => db.undo()});
    return true;
  };
  return AK.sheet({title: it ? 'Edit item' : 'New item', body, focus: it ? false : '#if-name', actions: [{label: 'Cancel', value: null}, {label: it ? 'Save' : 'Add item', primary: true, onClick: save}]});
}
function deleteItem(id) {
  const it = db.find('items', id);
  if (!it) return;
  AK.confirm('Delete ' + it.name + '?', {text: 'Its history stays in Activity. You can undo right after.', ok: 'Delete', danger: true}).then(yes => {
    if (!yes) return;
    db.update(s => { s.items = s.items.filter(x => x.id !== id); });
    AK.toast(it.name + ' deleted', {undo: () => db.undo()});
    if (app.route().param === id) app.go('/stock', true);
  });
}

/* ==== 8. Use / receive / set count sheet ==== */
function openMove(id, kind) {
  const it = db.find('items', id);
  if (!it) return null;
  const body = tpl('tpl-move'), form = $('form', body), input = $('#mv-n', form), q = num(it.qty);
  let k = kind || 'use', L = null;
  $$('input[name="kind"]', form).forEach(r => { r.checked = r.value === k; });
  input.value = k === 'count' ? String(round3(q)) : '1';
  const upd = () => {
    const n = Math.max(0, num(input.value)), after = k === 'use' ? Math.max(0, q - n) : k === 'receive' ? q + n : n;
    put(form, 'amount-label', k === 'count' ? 'How many are on the shelf?' : k === 'use' ? 'How many did you use?' : 'How many came in?');
    slot(form, 'result').textContent = qtyText(q) + ' on hand now, ' + qtyText(after) + ' after' + (k === 'use' && n > q ? '. Only ' + qtyText(q) + ' on hand.' : '');
    const btn = L && $('.dlg-foot .btn.primary', L.el);
    if (btn) btn.textContent = k === 'use' ? 'Use ' + qtyText(n) : k === 'receive' ? 'Receive ' + qtyText(n) : 'Set to ' + qtyText(n);
  };
  form.addEventListener('change', e => {
    if (e.target.name === 'kind') { k = e.target.value; input.value = k === 'count' ? String(round3(q)) : '1'; }
    upd();
  });
  input.addEventListener('input', upd);
  AK.on(form, 'click', '.quick .chip', (e, c) => { input.value = c.dataset.n; upd(); });
  form.addEventListener('submit', e => e.preventDefault());
  L = AK.sheet({title: it.name, body, onOpen: () => setTimeout(upd, 0), actions: [{label: 'Cancel', value: null}, {label: 'Save', primary: true, onClick: () => {
    const n = round3(input.value);
    if (!(input.value !== '' && isFinite(+input.value)) || n < 0 || (k !== 'count' && n <= 0)) { AK.setError(input, k === 'count' ? 'Enter 0 or more' : 'Enter 1 or more'); return false; }
    if (k === 'use' && n > q) { AK.setError(input, 'Only ' + qtyText(q) + ' on hand. Fix the count first if the shelf has more.'); return false; }
    const note = clip(form.elements.namedItem('note').value, 120), r = changeStock(it.id, k, n, note);
    const st = statusOf(r.item);
    AK.toast((k === 'use' ? 'Used ' + qtyText(n) : k === 'receive' ? 'Received ' + qtyText(n) : 'Count set') + ': ' + qtyText(r.after) + ' left' + (st === 'out' ? ', out of stock' : st === 'low' ? ', running low' : ''), {type: st === 'ok' ? 'ok' : 'warn', undo: () => db.undo()});
    return true;
  }}]});
  return L;
}

/* ==== 9. Reorder screen: what to buy, by supplier, ready to text or email ==== */
function renderReorder(page) {
  const items = db.get('items'), groups = orderGroups(items);
  AK.currency = settings().currency;
  page.append(tpl('view-reorder'));
  if (!groups.length) {
    put(page, 'summary', items.length ? 'Everything is above its minimum.' : 'Add items to start.');
    show(slot(page, 'reorder-actions'), false); show(slot(page, 'help'), false);
    const box = slot(page, 'empty');
    AK.render(box, AK.empty(items.length ? {art: 'inbox', title: 'Nothing to reorder', text: 'Items land here when they reach their minimum or run out.'} : {art: 'list', title: 'No items yet', text: 'Add the parts and supplies you keep.', action: {label: 'Add an item', icon: 'plus', act: 'new-item'}}));
    show(box, true);
    return;
  }
  const n = groups.reduce((t, g) => t + g.items.length, 0), cost = groups.reduce((t, g) => t + Math.round(g.cost * 100), 0) / 100;
  put(page, 'summary', plural(n, 'item') + ' from ' + plural(groups.length, 'supplier') + (cost ? ' · about ' + money(cost) + ' at cost' : ''));
  const host = slot(page, 'groups');
  groups.forEach(g => host.append(orderGroup(g)));
}
function orderGroup(g) {
  return piece('tpl-order-group', sec => {
    const sp = supplierFor(g.name), allOrdered = g.items.every(it => it.ordered);
    sec.dataset.key = g.key;
    put(sec, 'supplier', g.name || 'No supplier yet');
    put(sec, 'meta', plural(g.items.length, 'item') + (sp && (sp.phone || sp.email) ? ' · ' + [sp.phone, sp.email].filter(Boolean).join(' · ') : g.name ? ' · no phone or email yet' : ' · set a supplier on these items'));
    put(sec, 'total', g.cost ? money(g.cost) : '—');
    const ul = slot(sec, 'lines');
    g.items.forEach(it => ul.append(piece('tpl-order-line', li => {
      const st = statusOf(it), pill = slot(li, 'status'), inp = $('input', li);
      put(li, 'name', it.name);
      put(li, 'sub', [it.sku, qtyText(it.qty) + ' on hand', num(it.min) > 0 ? 'min ' + qtyText(it.min) : ''].filter(Boolean).join(' · '));
      pill.classList.add(STATUS[st].cls); pill.textContent = STATUS[st].short;
      show(slot(li, 'ordered'), !!it.ordered);
      $('[data-act="open-item"]', li).dataset.id = it.id;
      inp.value = String(suggestOrder(it)); inp.id = 'oq-' + it.id; inp.dataset.id = it.id;
      inp.setAttribute('aria-label', 'How many ' + it.name + ' to order');
      inp.addEventListener('change', () => { const v = round3(inp.value); if (v > 0) db.update('items', it.id, {orderQty: v}); else inp.value = String(suggestOrder(it)); });
    })));
    const text = orderText(g);
    slot(sec, 'sms').href = AK.sms(sp && sp.phone, text);
    slot(sec, 'mail').href = AK.mail(sp && sp.email, 'Order from ' + BUSINESS, text);
    $$('[data-act]', sec).forEach(b => { b.dataset.key = g.key; });
    put(sec, 'mark', allOrdered ? 'Not ordered yet' : 'Mark as ordered');
    const ed = $('[data-act="edit-supplier"]', sec);
    if (g.name) { ed.dataset.name = g.name; put(sec, 'contact-label', sp && (sp.phone || sp.email) ? 'Edit supplier' : 'Add phone or email'); } else ed.remove();
  });
}
const groupByKey = key => orderGroups(db.get('items')).find(g => g.key === key) || null;
function receiveOrder(key) {
  const g = groupByKey(key);
  if (!g) return null;
  const body = tpl('tpl-receive'), form = $('form', body), ul = slot(form, 'lines');
  g.items.forEach(it => ul.append(piece('tpl-receive-line', li => {
    put(li, 'name', it.name); put(li, 'sub', qtyText(it.qty) + ' on hand' + (it.sku ? ' · ' + it.sku : ''));
    const inp = $('input', li); inp.value = String(suggestOrder(it)); inp.dataset.id = it.id; inp.setAttribute('aria-label', 'How many ' + it.name + ' came in');
  })));
  form.addEventListener('submit', e => e.preventDefault());
  return AK.sheet({title: 'Delivery from ' + (g.name || 'a supplier'), body, actions: [{label: 'Cancel', value: null}, {label: 'Add to stock', primary: true, onClick: () => {
    const lines = $$('input[type="number"]', form).map(i => ({id: i.dataset.id, n: round3(i.value)})).filter(x => x.n > 0);
    if (!lines.length) { AK.toast('Enter how many came in.', 'warn'); return false; }
    const note = clip(form.elements.namedItem('note').value, 120) || 'Delivery' + (g.name ? ' from ' + g.name : '');
    const d = draftFor(lines.map(x => x.id));
    lines.forEach(x => applyChange(d, x.id, 'receive', x.n, note));
    db.set(d);
    AK.toast('Added ' + plural(lines.length, 'item') + ' to stock', {type: 'ok', undo: () => db.undo()});
    return true;
  }}]});
}

/* ==== 10. Activity screen: every change, newest first ==== */
function renderActivity(page) {
  const moves = db.get('moves'), u = look(), month = iso(new Date()).slice(0, 8) + '01';
  AK.currency = settings().currency;
  page.append(tpl('view-activity'));
  const thisMonth = moves.filter(m => dayOf(m.at) >= month);
  const sumUnits = kind => round3(thisMonth.filter(m => m.kind === kind).reduce((t, m) => t + Math.abs(m.delta), 0));
  const sumCost = kind => thisMonth.filter(m => m.kind === kind).reduce((t, m) => t + lineCost(Math.abs(m.delta), m.cost), 0) / 100;
  put(page, 'used', qtyText(sumUnits('use'))); put(page, 'used-meta', 'units · ' + money(sumCost('use')) + ' at cost');
  put(page, 'received', qtyText(sumUnits('receive'))); put(page, 'received-meta', 'units · ' + money(sumCost('receive')) + ' at cost');
  show(slot(page, 'activity-actions'), moves.length > 0);
  $$('[data-slot="kinds"] .chip', page).forEach(c => c.setAttribute('aria-pressed', String(c.dataset.kind === u.log)));
  AK.on(slot(page, 'kinds'), 'click', '.chip', (e, c) => { logLimit = 150; ui.set({log: c.dataset.kind}); });
  const search = $('#log-search', page);
  search.value = logQ;
  search.addEventListener('input', () => { logQ = search.value; drawDays(page); });
  drawDays(page);
}
function drawDays(page) {
  const u = look(), words = logQ.toLowerCase().split(/\s+/).filter(Boolean), host = slot(page, 'days');
  const kinds = {all: null, use: ['use'], receive: ['receive'], count: ['count', 'new', 'import']}[u.log] || null;
  const list = db.get('moves').filter(m => (!kinds || kinds.indexOf(m.kind) >= 0) && words.every(w => [m.name, m.sku, m.note].join(' ').toLowerCase().indexOf(w) >= 0));
  host.replaceChildren();
  if (!list.length) {
    AK.render(host, AK.html`<div class="card">${AK.empty(words.length || kinds ? {art: 'search', title: 'No changes match', text: 'Try another word or pick All.'} : {art: 'inbox', title: 'No changes yet', text: 'Every use, delivery and count is written here, with the time.'})}</div>`);
    show(slot(page, 'more-wrap'), false);
    return;
  }
  let day = null, ul = null;
  list.slice(0, logLimit).forEach(m => {
    const d = dayOf(m.at);
    if (d !== day) {
      day = d;
      const sec = piece('tpl-day', el => put(el, 'day', AK.fmt.day(d) + (/^(Today|Yesterday)$/.test(AK.fmt.day(d)) ? ' · ' + AK.fmt.date(d, 'day') : '')));
      host.append(sec); ul = slot(sec, 'rows');
    }
    ul.append(moveRow(m, true, false));
  });
  show(slot(page, 'more-wrap'), list.length > logLimit);
}

/* ==== 11. Suppliers: names come from items; phone / email are saved here ==== */
function allSuppliers() {
  const m = new Map(), items = db.get('items');
  (db.get('suppliers') || []).forEach(s => m.set(supKey(s.name), Object.assign({count: 0}, s)));
  items.forEach(it => { const k = supKey(it.supplier); if (!k) return; if (!m.has(k)) m.set(k, {name: clip(it.supplier, 60), phone: '', email: '', notes: '', count: 0}); m.get(k).count++; });
  return Array.from(m.values()).sort((a, b) => cmpText(a.name, b.name));
}
function editSupplier(name) {
  const old = supplierFor(name) || {name: name || '', phone: '', email: '', notes: ''};
  const body = tpl('tpl-supplier-form'), form = $('form', body);
  AK.fill(form, old);
  form.addEventListener('submit', e => e.preventDefault());
  const save = () => {
    const v = AK.validate(form, {name: {required: true}, email: {email: false}});
    if (!v.ok) return false;
    const x = {name: clip(v.values.name, 60), phone: clip(v.values.phone, 30), email: clip(v.values.email, 80), notes: String(v.values.notes || '').trim().slice(0, 300)};
    const was = supKey(old.name), clash = (db.get('suppliers') || []).some(s => supKey(s.name) === supKey(x.name) && supKey(s.name) !== was);
    if (clash) { AK.setError(form.elements.namedItem('name'), 'There is already a supplier with this name'); return false; }
    db.update(s => {
      s.suppliers = (s.suppliers || []).filter(y => supKey(y.name) !== was);
      s.suppliers.push(Object.assign({id: old.id || AK.id()}, x));
      if (was && was !== supKey(x.name)) s.items.forEach(it => { if (supKey(it.supplier) === was) it.supplier = x.name; });
    });
    AK.toast('Supplier saved', {type: 'ok', undo: () => db.undo()});
    return true;
  };
  const actions = [];
  if (old.id) actions.push({label: 'Remove', class: 'ghost del', onClick: () => { db.update(s => { s.suppliers = s.suppliers.filter(y => y.id !== old.id); }); AK.toast('Contact details removed', {undo: () => db.undo()}); return true; }});
  actions.push({label: 'Cancel', value: null}, {label: 'Save', primary: true, onClick: save});
  return AK.sheet({title: old.id ? 'Edit supplier' : name ? 'Supplier details' : 'New supplier', body, focus: name ? '#sf-phone' : '#sf-name', actions});
}

/* ==== 12. Importing items: CSV file or pasted table -> check columns -> add / update ==== */
const CURRENCY = /^(?:usd|eur|gbp|cad|aud|nzd|mxn|inr|jpy|chf|sek|nok|dkk|brl|zar|php|sgd|aed|ngn|kes|hkd|cny|pln|us\$|c\$|a\$|r\$|[$€£¥₹₩₽₺₪₫฿¢]|kr|zł)/i;
function looksNumber(v) {
  let t = String(v == null ? '' : v).trim();
  if (!t || t.length > 32 || !/\d/.test(t)) return false;
  t = t.replace(/[\s ]/g, '').replace(/^\((.*)\)$/, '$1').replace(/^[+-]/, '').replace(/-$/, '');
  t = t.replace(CURRENCY, '').replace(/^[+-]/, '').replace(/(?:usd|eur|gbp|cad|aud|[$€£¥₹%]|kr|zł|pcs|pc|ea|units?)$/i, '');
  return /^\d+(?:[,.'’]\d{3})*(?:[.,]\d+)?$/.test(t) || /^\d*[.,]\d+$/.test(t);
}
function decimalOf(values, fallback) {
  let eu = 0, us = 0;
  values.forEach(v => { const t = String(v).replace(/[^\d.,]/g, ''); if (/,\d{1,2}$/.test(t) || /\.\d{3},/.test(t)) eu++; else if (/\.\d{1,2}$/.test(t) || /,\d{3}\./.test(t)) us++; });
  return eu > us ? ',' : us > eu ? '.' : (fallback || '.');
}
function toNumber(v, dec) {
  if (!looksNumber(v)) return NaN;
  const raw = String(v).trim(), neg = /^\(.*\)$/.test(raw.replace(/\s/g, '')) || /-/.test(raw);
  let t = raw.replace(/[^\d.,]/g, '');
  t = dec === ',' ? t.replace(/\./g, '').replace(',', '.') : t.replace(/,/g, '');
  if ((t.match(/\./g) || []).length > 1) t = t.replace(/\./g, '');
  const n = parseFloat(t);
  return isFinite(n) ? (neg ? -n : n) : NaN;
}
function parseCells(text, dl) {
  const rows = [];
  let row = [], f = '', q = false, line = 1, start = 1;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (q) { if (c === '"') { if (text[i + 1] === '"') { f += '"'; i++; } else q = false; } else { f += c; if (c === '\n') line++; } }
    else if (c === '"' && f.trim() === '') { q = true; f = ''; }
    else if (c === dl) { row.push(f); f = ''; }
    else if (c === '\n' || c === '\r') { if (c === '\r' && text[i + 1] === '\n') i++; row.push(f); row.line = start; rows.push(row); row = []; f = ''; start = ++line; }
    else f += c;
  }
  if (f !== '' || row.length) { row.push(f); row.line = start; rows.push(row); }
  return rows.filter(r => r.some(x => x.trim() !== '')).map(r => { const o = r.map(x => x.trim()); o.line = r.line; return o; });
}
function guessDelimiter(text) {
  const sample = text.split(/\r\n|\n|\r/).filter(l => l.trim()).slice(0, 40).join('\n');
  let best = null;
  ['\t', ';', ',', '|'].forEach(d => {
    const counts = parseCells(sample, d).map(r => r.length), freq = {};
    if (!counts.length) return;
    counts.forEach(c => { freq[c] = (freq[c] || 0) + 1; });
    const mode = +Object.keys(freq).sort((a, b) => freq[b] - freq[a] || b - a)[0];
    if (mode < 2) return;
    const score = freq[mode] / counts.length * 10 + Math.min(mode, 8) * 0.1;
    if (!best || score > best.score + 1e-9) best = {d, score};
  });
  return best ? best.d : null;
}
/** text -> {head, rows (each row knows its .line), hadHeader} */
function splitTable(text) {
  let t = String(text == null ? '' : text).replace(/^﻿/, ''), dl = null;
  const sep = t.match(/^sep=(.)\s*(\r\n|\n|\r)/i);
  if (sep) { dl = sep[1]; t = t.slice(sep[0].length); }
  dl = dl || guessDelimiter(t);
  let rows = dl ? parseCells(t, dl) : t.split(/\r\n|\n|\r/).map((l, i) => { const o = l.trim().split(/\t| {2,}/).map(x => x.trim()); o.line = i + 1; return o; }).filter(r => r.some(Boolean));
  if (!rows.length) return {head: [], rows: [], hadHeader: false};
  const width = Math.max.apply(null, rows.map(r => r.length)), first = rows[0];
  const hadHeader = first.some(c => c) && first.every(c => !c || !looksNumber(c));
  const names = [];
  for (let i = 0; i < width; i++) { let n = clip(hadHeader ? first[i] : '', 40) || 'Column ' + (i + 1), k = 2; const b = n; while (names.indexOf(n) >= 0) n = b + ' (' + k++ + ')'; names.push(n); }
  if (hadHeader) rows = rows.slice(1);
  rows = rows.map(r => { const c = r.slice(0, width); while (c.length < width) c.push(''); c.line = r.line; return c; });
  return {head: names, rows, hadHeader, delimiter: dl};
}
// each field: header words that point to it, checked in this order so "Min stock" is the minimum, not the quantity
const FIELDS = [
  {key: 'sku', label: 'Part number or SKU', hint: /\bsku\b|part ?(no|num|number|#)|item ?(no|num|number|code|#)|product ?code|\bcode\b|\bupc\b|barcode|\bmpn\b|\bref\b|article|artikel ?nr|art\.? ?nr|c[oó]digo|r[eé]f[eé]rence/i},
  {key: 'supplier', label: 'Supplier', hint: /supplier|vendor|distributor|manufacturer|brand|source|lieferant|proveedor|fournisseur/i},
  {key: 'min', label: 'Minimum to keep', hint: /\bmin|reorder|\bpar\b|low ?level|safety|mindest/i},
  {key: 'qty', label: 'On hand', hint: /on ?hand|\bqty\b|quantity|in ?stock|\bstock\b|\bcount\b|units|available|balance|level|bestand|menge|cantidad|existencia|quantit/i},
  {key: 'cost', label: 'Unit cost', hint: /cost|purchase|buy|wholesale|net price|kosten|einkauf|co[uû]t/i},
  {key: 'price', label: 'Sale price', hint: /price|sell|retail|sale|msrp|list|preis|precio|prix/i},
  {key: 'loc', label: 'Location or bin', hint: /locat|\bbin\b|shelf|aisle|rack|place|where|room|lagerort|ubicaci|emplacement/i},
  {key: 'cat', label: 'Category', hint: /categ|kategor|group|\btype\b|class|dept|department|family/i},
  {key: 'notes', label: 'Notes', hint: /note|comment|memo|remark|detail/i},
  {key: 'name', label: 'Name', hint: /name|item|product|description|title|part|article|bezeichnung|artikel|nombre|\bnom\b|d[eé]signation/i}
];
const FIELD_ORDER = ['name', 'sku', 'qty', 'min', 'cost', 'price', 'cat', 'loc', 'supplier', 'notes'];
function detectItemColumns(table) {
  const map = {}, used = new Set();
  FIELDS.forEach(f => { const i = table.head.findIndex((h, j) => !used.has(j) && table.hadHeader && f.hint.test(h)); map[f.key] = i; if (i >= 0) used.add(i); });
  if (map.name < 0) {   // no "name" header: the most varied text column
    let best = -1, score = -1;
    table.head.forEach((h, j) => {
      if (used.has(j)) return;
      const vals = table.rows.slice(0, 200).map(r => r[j]).filter(Boolean), text = vals.filter(v => !looksNumber(v)).length;
      const s = vals.length ? text / vals.length + new Set(vals).size / vals.length : 0;
      if (s > score && text > vals.length / 2) { score = s; best = j; }
    });
    map.name = best;
  }
  return map;
}
/** rows of the file -> item fields (only the mapped ones), plus the lines with no name */
function buildItems(table, map) {
  const dec = {}, rows = [], skipped = [];
  ['qty', 'min', 'cost', 'price'].forEach(k => { dec[k] = map[k] >= 0 ? decimalOf(table.rows.slice(0, 400).map(r => r[map[k]]).filter(Boolean), table.delimiter === ';' ? ',' : '.') : '.'; });
  table.rows.forEach((r, i) => {
    const name = map.name >= 0 ? clip(r[map.name], 80) : '';
    if (!name) { skipped.push(r.line || i + (table.hadHeader ? 2 : 1)); return; }
    const o = {name};
    ['sku', 'cat', 'loc', 'supplier'].forEach(k => { if (map[k] >= 0) o[k] = clip(r[map[k]], k === 'supplier' ? 60 : 40); });
    if (map.notes >= 0) o.notes = String(r[map.notes] || '').trim().slice(0, 500);
    ['qty', 'min'].forEach(k => { if (map[k] >= 0) { const n = toNumber(r[map[k]], dec[k]); o[k] = isFinite(n) ? Math.max(0, round3(n)) : null; } });
    ['cost', 'price'].forEach(k => { if (map[k] >= 0) { const n = toNumber(r[map[k]], dec[k]); o[k] = isFinite(n) ? Math.max(0, round2(n)) : null; } });
    rows.push(o);
  });
  return {rows, skipped};
}
/** match by part number, else by name: update the item, or add it; sample items always go */
function plan(rows, items) {
  const bySku = new Map(), byName = new Map();
  items.filter(it => !it.sample).forEach(it => { if (it.sku) bySku.set(supKey(it.sku), it.id); byName.set(supKey(it.name), it.id); });
  let add = 0, upd = 0;
  const seen = new Set();
  rows.forEach(r => {
    const id = (r.sku && bySku.get(supKey(r.sku))) || byName.get(supKey(r.name));
    if (id) { if (!seen.has(id)) upd++; seen.add(id); } else { add++; const nid = 'new' + add; if (r.sku) bySku.set(supKey(r.sku), nid); byName.set(supKey(r.name), nid); }
  });
  return {add, upd};
}
function mergeItems(rows) {
  const res = {added: 0, updated: 0, removedSample: false};
  db.update(s => {
    if (s.items.some(it => it.sample)) { res.removedSample = true; const gone = new Set(s.items.filter(it => it.sample).map(it => it.id)); s.items = s.items.filter(it => !it.sample); s.moves = s.moves.filter(m => !gone.has(m.item)); s.suppliers = (s.suppliers || []).filter(x => !x.sample); }
    const touched = new Set();
    rows.forEach(r => {
      let it = (r.sku && s.items.find(x => x.sku && supKey(x.sku) === supKey(r.sku))) || s.items.find(x => supKey(x.name) === supKey(r.name));
      const fields = {};
      Object.keys(r).forEach(k => { if (k !== 'qty' && r[k] != null && r[k] !== '') fields[k] = r[k]; });
      if (it) { Object.assign(it, fields, {updated: new Date().toISOString()}); if (!touched.has(it.id)) res.updated++; }
      else { it = Object.assign({id: AK.id(), name: r.name, sku: '', cat: '', loc: '', qty: 0, min: 0, cost: '', price: '', supplier: '', notes: '', orderQty: null, ordered: ''}, fields, {updated: new Date().toISOString()}); s.items.push(it); res.added++; }
      touched.add(it.id);
      if (r.qty != null && r.qty !== num(it.qty)) applyChange(s, it.id, 'import', r.qty, 'Imported');
    });
  });
  return res;
}
function readItemFile(f, busy) {
  if (f.size > 15e6) { AK.toast('That file is too big (over 15 MB).', 'bad'); return; }
  if (busy) busy.setAttribute('aria-busy', 'true');
  f.arrayBuffer().then(buf => {
    const b = new Uint8Array(buf);
    let text;
    if (b[0] === 0xff && b[1] === 0xfe) text = new TextDecoder('utf-16le').decode(buf);
    else { text = new TextDecoder('utf-8').decode(buf); if (text.indexOf('�') >= 0) try { text = new TextDecoder('windows-1252').decode(buf); } catch (e) { /* keep utf-8 */ } }
    if (busy) busy.removeAttribute('aria-busy');
    readItems(text, f.name);
  }, () => { if (busy) busy.removeAttribute('aria-busy'); AK.toast('Could not read that file.', 'bad'); });
}
function readItems(text, name) {
  const table = splitTable(text);
  if (!table.rows.length) { AK.toast('No rows found. Copy the cells with the header row and try again.', 'warn'); return null; }
  const body = tpl('tpl-mapping'), form = $('form', body), fields = slot(form, 'fields'), map = detectItemColumns(table);
  put(form, 'found', 'Found ' + plural(table.head.length, 'column') + ' and ' + plural(table.rows.length, 'row') + (name ? ' in ' + name : '') + '. Check that each one is right:');
  FIELD_ORDER.forEach(k => {
    const f = FIELDS.find(x => x.key === k);
    fields.append(piece('tpl-map-field', el => {
      const sel = $('select', el), id = 'mf-' + k;
      put(el, 'label', f.label); $('label', el).htmlFor = id; sel.id = id; sel.name = k;
      sel.append(new Option(k === 'name' ? 'Pick a column' : 'Not in this file', '-1'));
      table.head.forEach((h, i) => sel.append(new Option(h, String(i))));
      sel.value = String(map[k]);
    }));
  });
  let built = {rows: [], skipped: []}, L = null;
  const upd = () => {
    const m = {};
    FIELD_ORDER.forEach(k => { m[k] = +form.elements.namedItem(k).value; });
    built = buildItems(table, m);
    const p = plan(built.rows, db.get('items')), tb = slot(form, 'preview');
    tb.replaceChildren();
    built.rows.slice(0, 6).forEach(r => tb.append(AK.el('tr', [AK.el('td', {text: r.name}), AK.el('td', {text: r.sku || ''}), AK.el('td', {class: 'num', text: r.qty == null ? '' : qtyText(r.qty)}), AK.el('td', {class: 'num', text: r.min == null ? '' : qtyText(r.min)}), AK.el('td', {class: 'num', text: r.cost == null ? '' : money(r.cost)})])));
    const sk = built.skipped;
    slot(form, 'result').textContent = m.name < 0 ? 'Pick the column with the item names.' : plural(p.add, 'new item') + ', ' + plural(p.upd, 'update') + ' (same part number or name).' + (sk.length ? ' ' + plural(sk.length, 'row') + ' skipped because the name is empty (row ' + sk.slice(0, 5).join(', ') + (sk.length > 5 ? ' and more' : '') + ').' : '');
    const btn = L && $('.dlg-foot .btn.primary', L.el);
    if (btn) btn.textContent = built.rows.length ? 'Import ' + plural(built.rows.length, 'row') : 'Import';
  };
  form.addEventListener('change', upd);
  form.addEventListener('submit', e => e.preventDefault());
  L = AK.sheet({title: 'Check your columns', body, onOpen: upd, actions: [{label: 'Cancel', value: null}, {label: 'Import', primary: true, onClick: () => {
    if (!built.rows.length) { AK.toast('Nothing to import yet: pick the name column.', 'warn'); return false; }
    const r = mergeItems(built.rows);
    AK.toast(plural(r.added, 'item') + ' added, ' + plural(r.updated, 'item') + ' updated' + (r.removedSample ? '. Sample data removed.' : ''), {type: 'ok', undo: () => db.undo()});
    return 'imported';
  }}]});
  L.result.then(v => { if (v === 'imported') app.go('/stock'); });
  return L;
}
function pasteSheet(title, label, help, onText) {
  const body = tpl('tpl-paste');
  put(body, 'label', label); put(body, 'help', help);
  return AK.modal({title, body, focus: 'textarea', actions: [{label: 'Cancel', value: null}, {label: 'Continue', primary: true, onClick: L => {
    const ta = $('textarea', L.body);
    if (!ta.value.trim()) { AK.setError(ta, 'Paste something first'); return false; }
    const t = ta.value;
    setTimeout(() => onText(t), 0);
    return true;
  }}]});
}
const ITEM_COLS = [{key: 'name', label: 'Name'}, {key: 'sku', label: 'Part number'}, {key: 'cat', label: 'Category'}, {key: 'loc', label: 'Location'}, {key: 'qty', label: 'On hand'},
  {key: 'min', label: 'Minimum'}, {key: 'cost', label: 'Unit cost'}, {key: 'price', label: 'Sale price'}, {key: 'supplier', label: 'Supplier'}, {key: 'notes', label: 'Notes'}];

/* ==== 13. More screen: suppliers, files, backup, settings ==== */
function renderMore(page) {
  const items = db.get('items'), s = settings();
  page.append(tpl('view-more'));
  const sups = allSuppliers(), ul = slot(page, 'suppliers');
  sups.forEach(sp => ul.append(piece('tpl-supplier-row', li => {
    put(li, 'name', sp.name);
    put(li, 'sub', [[sp.phone, sp.email].filter(Boolean).join(' · ') || 'No phone or email yet', sp.count ? plural(sp.count, 'item') : ''].filter(Boolean).join(' · '));
    $('button', li).dataset.name = sp.name;
  })));
  show(ul, sups.length > 0); show(slot(page, 'suppliers-empty'), !sups.length);
  // settings
  const form = slot(page, 'settings-form');
  AK.fill(form, {currency: s.currency, orderNote: s.orderNote});
  form.addEventListener('submit', e => e.preventDefault());
  form.addEventListener('change', () => { const v = AK.values(form); db.set('settings', {currency: v.currency || 'USD', orderNote: String(v.orderNote || '').trim().slice(0, 300)}); });
  // storage
  const used = storageUsed(), photos = JSON.stringify(pics.get()).length;
  const size = n => n < 1e5 ? Math.max(1, Math.round(n / 1e3)) + ' KB' : (n / 1e6).toFixed(1) + ' MB';
  put(page, 'storage', size(used) + ' of about 5 MB');
  const bar = slot(page, 'storage-bar');
  bar.style.setProperty('--value', String(Math.min(100, Math.round(used / 5e6 * 100))));
  if (used > 4e6) bar.classList.add('warn');
  put(page, 'storage-note', (photos > 2 ? 'Photos use ' + size(photos) + '. ' : 'No photos yet. ') + (used > 4e6 ? 'Space is getting tight: download a backup, then remove a few photos.' : 'Plenty of room for items and history.'));
  // sample data
  const hasSample = items.some(it => it.sample);
  show(slot(page, 'sample-load'), !items.length); show(slot(page, 'sample-clear'), hasSample);
  put(page, 'sample-text', hasSample ? 'The made-up items are still here. Clear them before you add your own, or import a list and they go automatically.' :
    items.length ? 'Sample data is only offered when the list is empty.' : 'Fill the app with made-up items to see how it works.');
  const file = $('#items-file', page);
  file.addEventListener('change', () => { const f = file.files && file.files[0]; file.value = ''; if (f) readItemFile(f, file.closest('.file-pick')); });
  const restore = $('#restore-file', page);
  restore.addEventListener('change', () => { const f = restore.files && restore.files[0]; restore.value = ''; if (f) f.text().then(restoreFrom, () => AK.toast('Could not read that file.', 'bad')); });
}
function backupText(withPhotos) { return JSON.stringify({app: 'parts-and-stock', version: 1, saved: new Date().toISOString(), data: db.get(), photos: withPhotos ? pics.get() : undefined}); }
const str = (v, n) => clip(typeof v === 'string' || typeof v === 'number' ? v : '', n);
/** keep only well-formed records from a backup */
function cleanBackup(d) {
  const items = (Array.isArray(d.items) ? d.items : []).filter(it => it && str(it.name, 80)).map(it => {
    const o = {id: str(it.id, 40) || AK.id(), name: str(it.name, 80), sku: str(it.sku, 40), cat: str(it.cat, 40), loc: str(it.loc, 40), qty: Math.max(0, round3(it.qty)), min: Math.max(0, round3(it.min)),
      cost: it.cost === '' || it.cost == null ? '' : Math.max(0, round2(it.cost)), price: it.price === '' || it.price == null ? '' : Math.max(0, round2(it.price)),
      supplier: str(it.supplier, 60), notes: typeof it.notes === 'string' ? it.notes.slice(0, 500) : '', orderQty: num(it.orderQty) > 0 ? round3(it.orderQty) : null,
      ordered: /^\d{4}-\d{2}-\d{2}$/.test(it.ordered || '') ? it.ordered : '', updated: str(it.updated, 40)};
    if (it.sample) o.sample = true;
    return o;
  });
  const kinds = Object.keys(KIND);
  const moves = (Array.isArray(d.moves) ? d.moves : []).filter(m => m && kinds.indexOf(m.kind) >= 0 && !isNaN(Date.parse(m.at))).slice(0, MAX_MOVES).map(m => {
    const o = {id: str(m.id, 40) || AK.id(), item: str(m.item, 40), name: str(m.name, 80), sku: str(m.sku, 40), at: new Date(m.at).toISOString(), kind: m.kind, delta: round3(m.delta), after: Math.max(0, round3(m.after)), note: str(m.note, 120), cost: num(m.cost)};
    if (m.sample) o.sample = true;
    return o;
  });
  const suppliers = (Array.isArray(d.suppliers) ? d.suppliers : []).filter(x => x && str(x.name, 60)).map(x => ({id: str(x.id, 40) || AK.id(), name: str(x.name, 60), phone: str(x.phone, 30), email: str(x.email, 80), notes: str(x.notes, 300)}));
  return {version: 1, items, moves, suppliers, settings: Object.assign({}, SETTINGS, d.settings && typeof d.settings === 'object' ? {currency: str(d.settings.currency, 3) || 'USD', orderNote: str(d.settings.orderNote, 300)} : {})};
}
function restoreFrom(text) {
  let j = null;
  try { j = JSON.parse(String(text || '').trim()); } catch (e) { j = null; }
  const d = j && j.data && Array.isArray(j.data.items) ? j.data : j && Array.isArray(j.items) ? j : null;
  if (!d) { AK.toast('That is not a backup from this stock app.', 'bad'); return Promise.resolve(false); }
  const clean = cleanBackup(d);
  return AK.confirm('Replace what is on this device?', {text: 'The backup has ' + plural(clean.items.length, 'item') + ' and ' + plural(clean.moves.length, 'change') + ' in its history.', ok: 'Restore'}).then(yes => {
    if (!yes) return false;
    db.reset(clean);
    if (j.photos && typeof j.photos === 'object') { const p = {}; Object.keys(j.photos).forEach(k => { if (typeof j.photos[k] === 'string' && /^data:image\//.test(j.photos[k])) p[k] = j.photos[k]; }); pics.reset(p); }
    AK.toast('Backup restored', {type: 'ok', undo: () => db.undo()});
    return true;
  });
}

/* ==== 14. Printing: reorder list and count sheet, with the business letterhead ==== */
function printSheet(kind, title, fill) {
  const body = tpl('tpl-print'), doc = $('.doc', body);
  put(body, 'kind', kind); put(body, 'title', title); put(body, 'date', AK.fmt.date(new Date(), 'full'));
  fill(slot(body, 'body'), doc);
  let L = null;
  L = AK.sheet({title: kind, body, actions: [{label: 'Close', value: null}, {label: 'Print', icon: 'printer', primary: true, onClick: () => { AK.print(BUSINESS + ' ' + kind, $('.doc', L.el)); return false; }}]});
  return L;
}
const cell = (t, cls) => AK.el('td', {class: cls || null, text: t});
const head = cols => AK.el('thead', [AK.el('tr', cols.map(c => AK.el('th', {scope: 'col', class: c[1] || null, text: c[0]})))]);
function printOrders() {
  const groups = orderGroups(db.get('items'));
  if (!groups.length) { AK.toast('Nothing to reorder right now.', 'ok'); return null; }
  return printSheet('Reorder list', plural(groups.reduce((t, g) => t + g.items.length, 0), 'item') + ' to order', (host, doc) => {
    let grand = 0;
    groups.forEach(g => {
      const sp = supplierFor(g.name);
      host.append(AK.el('h3', {text: g.name || 'No supplier yet'}));
      if (sp && (sp.phone || sp.email)) host.append(AK.el('p', {class: 'small muted', text: [sp.phone, sp.email].filter(Boolean).join(' · ')}));
      host.append(AK.el('table', [head([['Item'], ['Part number'], ['On hand', 'num'], ['Order', 'num'], ['Unit cost', 'num'], ['Cost', 'num']]),
        AK.el('tbody', g.items.map(it => AK.el('tr', [cell(it.name), cell(it.sku || ''), cell(qtyText(it.qty), 'num'), cell(qtyText(suggestOrder(it)), 'num'), cell(it.cost === '' ? '' : money(num(it.cost)), 'num'), cell(it.cost === '' ? '' : money(lineCost(suggestOrder(it), it.cost) / 100), 'num')])))]));
      grand += Math.round(g.cost * 100);
    });
    host.append(AK.el('div', {class: 'doc-total'}, [AK.el('div', {class: 'grand'}, [AK.el('span', {text: 'Estimated total'}), AK.el('span', {text: money(grand / 100)})])]));
    put(doc, 'foot', 'Order amounts fill each shelf back up to twice its minimum, unless changed. Prices are your last unit costs.');
  });
}
function printCount() {
  const items = sortItems(db.get('items'), 'loc');
  if (!items.length) { AK.toast('Add some items first.', 'warn'); return null; }
  return printSheet('Count sheet', plural(items.length, 'item') + ' by location', (host, doc) => {
    host.append(AK.el('table', {class: 'count-table'}, [head([['Location'], ['Item'], ['Part number'], ['On hand', 'num'], ['Counted', 'num']]),
      AK.el('tbody', items.map(it => AK.el('tr', [cell(it.loc || '—'), cell(it.name), cell(it.sku || ''), cell(qtyText(it.qty), 'num'), AK.el('td', {class: 'num'}, [AK.el('span', {class: 'count-box'})])])))]));
    put(doc, 'foot', 'Write what is on the shelf, then use "Set the exact count" on each item that differs.');
  });
}

/* ==== 15. Sample data: clearly made-up items, so every screen can be tried ==== */
function sampleData() {
  const now = Date.now(), at = h => new Date(now - h * 36e5).toISOString();
  const rows = [
    ['Sample item A', 'SAMPLE-001', 'Sample category 1', 'Shelf A1', 14, 6, 4.5, 9.99, 'Sample supplier 1'],
    ['Sample item B', 'SAMPLE-002', 'Sample category 1', 'Shelf A2', 3, 5, 7.25, 15, 'Sample supplier 1'],
    ['Sample item C', 'SAMPLE-003', 'Sample category 2', 'Shelf B1', 0, 2, 22, 45, 'Sample supplier 2'],
    ['Sample item D', 'SAMPLE-004', 'Sample category 2', 'Shelf B3', 26, 10, 1.1, 2.5, 'Sample supplier 2'],
    ['Sample item E', 'SAMPLE-005', 'Sample category 3', 'Bin C4', 2, 4, 12.5, 29, 'Sample supplier 1'],
    ['Sample item F', 'SAMPLE-006', 'Sample category 3', 'Bin C5', 9, 3, 18, 35, ''],
    ['Sample item G', 'SAMPLE-007', 'Sample category 1', 'Shelf A4', 40, 12, 0.35, 1, 'Sample supplier 2'],
    ['Sample item H', 'SAMPLE-008', 'Sample category 2', 'Back room', 1, 1, 55, 99, '']
  ];
  const items = rows.map((r, i) => ({id: 'sample-' + (i + 1), name: r[0], sku: r[1], cat: r[2], loc: r[3], qty: r[4], min: r[5], cost: r[6], price: r[7], supplier: r[8],
    notes: i === 0 ? 'Sample note: size, fit, or which jobs use it.' : '', orderQty: null, ordered: i === 2 ? iso(new Date(now - 864e5)) : '', updated: at(i * 5), sample: true}));
  const mv = (h, i, kind, delta, after, note) => ({id: 'sample-m' + h, item: items[i].id, name: items[i].name, sku: items[i].sku, at: at(h), kind, delta, after, note, cost: items[i].cost, sample: true});
  const moves = [mv(1, 1, 'use', -2, 3, 'Sample job'), mv(3, 0, 'use', -1, 14, ''), mv(20, 3, 'receive', 20, 26, 'Sample delivery'), mv(26, 2, 'use', -1, 0, 'Sample job'),
    mv(30, 4, 'use', -3, 2, 'Sample job'), mv(50, 6, 'count', -2, 40, 'Monthly count'), mv(75, 7, 'use', -1, 1, ''), mv(98, 1, 'receive', 4, 5, 'Sample delivery')];
  return {items, moves, suppliers: [{id: 'sample-s1', name: 'Sample supplier 1', phone: '', email: '', notes: 'Sample supplier: add a real phone or email to send orders.', sample: true}]};
}
function loadSample(quiet) {
  if (db.get('items').length) { AK.toast('Sample data is only for an empty list.', 'warn'); return; }
  const smp = sampleData();
  db.update(s => { s.items = smp.items; s.moves = smp.moves; s.suppliers = (s.suppliers || []).concat(smp.suppliers); });
  if (!quiet) { AK.toast('Sample data added. Clear it any time.', 'ok'); if (app) app.go('/stock'); }
}
function clearSample() {
  db.update(s => {
    const gone = new Set(s.items.filter(it => it.sample).map(it => it.id));
    s.items = s.items.filter(it => !it.sample); s.moves = s.moves.filter(m => !m.sample && !gone.has(m.item)); s.suppliers = (s.suppliers || []).filter(x => !x.sample);
  });
  AK.toast('Sample data cleared', {undo: () => db.undo()});
}

/* ==== 16. Actions: every data-act="..." button ==== */
AK.act({
  'new-item': () => editItem(null),
  'edit-item': el => editItem(el.dataset.id),
  'delete-item': el => deleteItem(el.dataset.id),
  'open-item': el => app.go('/stock/' + el.dataset.id),
  'go-stock': () => app.go('/stock'),
  'go-reorder': () => app.go('/reorder'),
  'go-import': () => app.go('/more'),
  'dec': el => tap(el.dataset.id, -1),
  'inc': el => tap(el.dataset.id, 1),
  'move': el => openMove(el.dataset.id, el.dataset.kind || 'use'),
  'item-photo': el => {
    AK.photo(null, {max: 640, quality: 0.75}).then(url => {
      if (!url) return;
      if (storageUsed() + url.length * 1.2 > ROOM) { AK.toast('No room for more photos on this device.', 'bad'); return; }
      savePhoto(el.dataset.id, url).then(() => AK.toast('Photo saved', 'ok'));
    });
  },
  'filter-low': () => ui.set({show: look().show === 'low' ? 'all' : 'low'}),
  'filter-out': () => ui.set({show: look().show === 'out' ? 'all' : 'out'}),
  'copy-order': el => { const g = groupByKey(el.dataset.key); if (g) AK.copy(orderText(g), {done: 'Order copied'}); },
  // the phone's share sheet (WhatsApp, Messages, Mail...), or appkit's Text / Email / Copy sheet where there is none
  'share-order': el => { const g = groupByKey(el.dataset.key); if (!g) return; const sp = supplierFor(g.name); AK.share({title: 'Order from ' + BUSINESS, text: orderText(g), phone: sp && sp.phone, email: sp && sp.email}); },
  'mark-ordered': el => {
    const g = groupByKey(el.dataset.key); if (!g) return;
    const on = !g.items.every(it => it.ordered), day = iso(new Date()), ids = new Set(g.items.map(it => it.id));
    db.update(s => { s.items.forEach(it => { if (ids.has(it.id)) it.ordered = on ? day : ''; }); });
    AK.toast(on ? 'Marked as ordered' : 'Marked as not ordered', {undo: () => db.undo()});
  },
  'receive-order': el => receiveOrder(el.dataset.key),
  'edit-supplier': el => editSupplier(el.dataset.name || ''),
  'new-supplier': () => editSupplier(''),
  'log-more': () => { logLimit += 300; app.refresh(); },
  'stock-more': () => { stockLimit += 300; drawStockList(app.page); },
  'export-items': () => {
    const items = sortItems(db.get('items'), 'name');
    if (!items.length) return AK.toast('There are no items yet.', 'warn');
    AK.csv.export(items, 'stock-' + iso(new Date()) + '.csv', ITEM_COLS);
    AK.toast('Saved ' + plural(items.length, 'item') + ' as a CSV file', 'ok');
  },
  'export-moves': () => {
    const moves = db.get('moves');
    AK.csv.export(moves, 'stock-history-' + iso(new Date()) + '.csv', [{key: 'date', label: 'Date', value: m => dayOf(m.at)}, {key: 'time', label: 'Time', value: m => AK.fmt.time(m.at)}, {key: 'name', label: 'Item'},
      {key: 'sku', label: 'Part number'}, {key: 'kind', label: 'Change', value: m => (KIND[m.kind] || KIND.count).word}, {key: 'delta', label: 'Amount'}, {key: 'after', label: 'On hand after'}, {key: 'note', label: 'Note'}, {key: 'cost', label: 'Unit cost'}]);
    AK.toast('Saved ' + plural(moves.length, 'change') + ' as a CSV file', 'ok');
  },
  'paste-items': () => pasteSheet('Paste items', 'Paste the cells here, header row included', 'From Excel, Google Sheets or any list with one item per row.', t => readItems(t, 'Pasted list')),
  'print-orders': () => printOrders(),
  'print-count': () => printCount(),
  'backup-download': () => { AK.download('stock-backup-' + iso(new Date()) + '.json', backupText(true)); AK.toast('Backup saved to your downloads', 'ok'); },
  'backup-copy': () => AK.copy(backupText(false), {done: 'Backup copied (without photos). Paste it somewhere safe.'}),
  'restore-paste': () => pasteSheet('Restore a backup', 'Paste the backup text', 'This replaces the items, history and suppliers on this device. You can undo right after.', restoreFrom),
  'load-sample': () => loadSample(false),
  'clear-sample': () => clearSample(),
  'erase-all': () => AK.confirm('Erase all data?', {text: 'Every item, change and supplier on this device goes. Settings stay.', ok: 'Erase', danger: true}).then(yes => {
    if (!yes) return;
    db.update(s => { s.items = []; s.moves = []; s.suppliers = []; });
    AK.toast('Everything erased', {undo: () => db.undo()});
  })
});

/* ==== 17. Start ==== */
if (/[?&]sample\b/.test(location.search) && !db.get('items').length) loadSample(true);   // index.html?sample opens with the sample data
AK.currency = settings().currency;
app = AK.app({
  title: BUSINESS, icon: 'box',
  fab: {icon: 'plus', label: 'Add item', act: 'new-item'},
  tabs: [
    {id: 'stock', label: 'Stock', icon: 'box', render: renderStock},
    {id: 'reorder', label: 'Reorder', icon: 'truck', render: renderReorder, fab: false},
    {id: 'activity', label: 'Activity', icon: 'clock', render: renderActivity, fab: false},
    {id: 'more', label: 'More', icon: 'settings', render: renderMore, fab: false}
  ]
});
const badge = () => app.badge('reorder', needOrder(db.get('items')).length);
db.subscribe(() => { badge(); if (!(quickTap && softRefresh())) app.refresh(); });
ui.subscribe(() => app.refresh());
pics.subscribe(() => app.refresh());
badge();
// photos of deleted items are tidied up when the app opens
setTimeout(() => { const ids = new Set(db.get('items').map(it => it.id)), p = pics.get(), stale = Object.keys(p).filter(k => !ids.has(k.replace(/_t$/, ''))); if (stale.length) pics.update(s => { stale.forEach(k => { delete s[k]; }); }); }, 1500);
AK.pwa({name: BUSINESS + ' Stock', short_name: 'Stock'});
// for tests and for a bot that wants to script the app
window.StockApp = {db, ui, pics, app, savePhoto, statusOf, suggestOrder, totals, orderGroups, orderText, changeStock, tap, splitTable, detectItemColumns, buildItems, mergeItems, plan, readItems, restoreFrom, backupText, sortItems, visibleItems, toNumber, sampleData};
})();
