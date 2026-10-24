/* Money Tracker | app.js (built on appkit: window.AK, see APPKIT.md)
   Screens live in index.html as <template id="view-..."> blocks; this file fills their data-slot="..." elements.
   Sections:  1 helpers  2 icons + colours + default categories  3 data store  4 money + dates  5 repeating engine
   6 month numbers  7 shared row markup  8 Home  9 Activity  10 Budgets  11 Repeating  12 Settings + Categories  13 Month report
   14 Entry sheet  15 Category sheet  16 Repeating sheet  17 Budget sheet  18 CSV + backup  19 Sample data  20 Actions + boot */
'use strict';

/* ===== 1. HELPERS ===== */
const $ = (s, r) => (r || document).querySelector(s);
const $$ = (s, r) => Array.from((r || document).querySelectorAll(s));
const LOC = () => AK.locale || navigator.language || 'en-US';
const fact = k => {const e = $('#facts [data-fact="' + k + '"]'), v = e ? e.textContent.trim() : ''; return /^\{\{.*\}\}$/.test(v) ? '' : v;};
const BUSINESS = fact('business') || 'My business';
const node = h => {const t = document.createElement('template'); t.innerHTML = String(h).trim(); return t.content.firstElementChild;};
const slot = (root, n) => root.querySelector('[data-slot="' + n + '"]');
const setText = (root, n, v) => {const e = slot(root, n); if (e) e.textContent = v == null ? '' : v; return e;};
const setHTML = (root, n, h) => {const e = slot(root, n); if (e) e.innerHTML = String(h); return e;};
/* copy a <template> from index.html and turn every <i data-icon="name"> into an icon */
function tpl(id) {
  const f = document.getElementById(id).content.cloneNode(true);
  $$('i[data-icon]', f).forEach(i => i.replaceWith(node(icon(i.dataset.icon, i.className))));
  return f;
}
/* big numbers shrink to fit their card: CSS reads --len (see .fit in style.css) */
function fitText(el, text, html) {
  if (!el) return;
  if (html) el.innerHTML = String(html); else el.textContent = text;
  el.style.setProperty('--len', Math.max(4, String(text).length));
}
/* side-by-side stats (not the accent one) share one size so their numbers line up */
function evenFit(root) {
  $$('.stats', root).forEach(st => {
    const v = $$('.stat:not(.accent) .fit', st), n = Math.max.apply(null, [4].concat(v.map(e => +e.style.getPropertyValue('--len') || 0)));
    v.forEach(e => e.style.setProperty('--len', n));
  });
}

/* ===== 2. ICONS, COLOURS, DEFAULT CATEGORIES ===== */
const XI = { /* extra icons (24px grid, stroke) on top of AK.icons */
  fuel: 'M3 22h12M4 9h10M14 22V4a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v18M14 13h2a2 2 0 0 1 2 2v2a2 2 0 0 0 4 0V9.8a2 2 0 0 0-.6-1.4L18 5',
  coffee: 'M10 2v2M14 2v2M6 2v2M16 8a1 1 0 0 1 1 1v8a4 4 0 0 1-4 4H7a4 4 0 0 1-4-4V9a1 1 0 0 1 1-1h14a4 4 0 1 1 0 8h-1',
  shield: 'M20 13c0 5-3.5 7.5-7.7 9a1 1 0 0 1-.6 0C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.2-2.7a1.2 1.2 0 0 1 1.6 0C14.5 3.8 17 5 19 5a1 1 0 0 1 1 1z',
  megaphone: 'M3 11l18-5v12L3 14v-3zM11.6 16.8a3 3 0 1 1-5.8-1.6',
  bag: 'M6 2 3 6v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6l-3-4zM3 6h18M16 10a4 4 0 0 1-8 0',
  wallet: 'M19 7V4a1 1 0 0 0-1-1H5a2 2 0 0 0 0 4h15a1 1 0 0 1 1 1v4h-3a2 2 0 0 0 0 4h3a1 1 0 0 0 1-1v-2a1 1 0 0 0-1-1M3 5v14a2 2 0 0 0 2 2h15a1 1 0 0 0 1-1v-4',
  building: 'M6 22V4a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v18zM6 12H4a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2h2M18 9h2a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-2M10 6h4M10 10h4M10 14h4M10 18h4',
  gift: 'M3 8h18v4H3zM12 8v13M19 12v7a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-7M7.5 8a2.5 2.5 0 0 1 0-5C10 3 12 8 12 8s2-5 4.5-5a2.5 2.5 0 0 1 0 5',
  book: 'M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H20v20H6.5a2.5 2.5 0 0 1 0-5H20'
};
const icon = (n, cls) => XI[n] ? AK.raw('<svg class="i' + (cls ? ' ' + AK.esc(cls) : '') + '" viewBox="0 0 24 24" aria-hidden="true"><path d="' + XI[n] + '"/></svg>') : AK.icon(n, cls);
const CAT_ICONS = ['box', 'wrench', 'fuel', 'car', 'truck', 'building', 'home', 'zap', 'phone', 'users', 'shield', 'megaphone', 'card', 'receipt',
  'coffee', 'bag', 'store', 'file-text', 'book', 'heart', 'gift', 'tag', 'dollar', 'wallet', 'star', 'briefcase'].filter(n => XI[n] || AK.icons[n]);
const COLORS = {teal: '#0f9f8f', blue: '#2f6fed', indigo: '#5a5fd6', violet: '#8b4fd8', pink: '#d6457e', red: '#d93a3a',
  orange: '#e5681a', amber: '#c98a0b', green: '#2e9b4b', olive: '#7d8a22', brown: '#9a5b34', slate: '#64748b'};
const hex = c => COLORS[c] || COLORS.slate;
const cat0 = (id, name, ic, color, kind) => ({id, name, icon: ic, color, kind, budget: null});
/* Starting categories: rename or replace them for the owner's trade (ids are used by the sample data only). */
const DEFAULT_CATS = [
  ['parts', 'Parts & supplies', 'box', 'orange'], ['tools', 'Tools & equipment', 'wrench', 'slate'], ['fuel', 'Fuel & vehicle', 'fuel', 'amber'],
  ['rent', 'Rent', 'building', 'blue'], ['utilities', 'Utilities', 'zap', 'teal'], ['phone', 'Phone & internet', 'phone', 'indigo'],
  ['payroll', 'Payroll', 'users', 'violet'], ['insurance', 'Insurance', 'shield', 'brown'], ['marketing', 'Marketing', 'megaphone', 'pink'],
  ['fees', 'Fees & bank', 'card', 'red'], ['meals', 'Meals', 'coffee', 'olive'], ['other', 'Other', 'tag', 'slate']
].map(c => cat0(c[0], c[1], c[2], c[3], 'expense')).concat([
  ['sales', 'Sales', 'dollar', 'green'], ['services', 'Services', 'clipboard', 'teal'], ['other-in', 'Other income', 'star', 'blue']
].map(c => cat0(c[0], c[1], c[2], c[3], 'income')));

/* ===== 3. DATA STORE (browser storage, namespaced by the app) =====
   txns: {id, type:'income'|'expense', amount, cat, date:'YYYY-MM-DD', note, photo, rec, at, sample}
   recs: {id, type, amount, cat, note, freq:'weekly'|'biweekly'|'monthly'|'yearly', start, end, next, day, month, active, sample}
   cats: {id, name, icon, color, kind, budget} */
const FRESH = () => ({v: 1, currency: 'USD', cats: DEFAULT_CATS.map(c => Object.assign({}, c)), txns: [], recs: [], lastBackup: null, sampleBudgets: []});
const db = AK.store('expense-tracker', FRESH());
const S = () => db.get();
const cats = kind => S().cats.filter(c => !kind || c.kind === kind);
const cat = id => S().cats.find(c => c.id === id) || {id: '', name: 'No category', icon: 'tag', color: 'slate', kind: 'expense', budget: null};
const hasSample = () => S().txns.some(t => t.sample) || S().recs.some(r => r.sample);

/* page state (not saved) */
const ui = {month: AK.today().slice(0, 7), q: '', scope: 'month', type: 'all', cat: '', photo: false, limit: 120};

/* ===== 4. MONEY + DATES ===== */
const CUR = () => S().currency || 'USD';
const DEC = () => CUR() === 'JPY' ? 0 : 2;
const toC = n => {n = +n || 0; return Math.round(n * 100 + Math.sign(n) * 1e-6);}; /* to whole cents, 1.005 -> 101 */
const r2 = n => toC(n) / 100;
const sumC = (list, f) => list.reduce((a, x) => a + toC(f ? f(x) : x), 0) / 100;
const money = n => AK.fmt.money(r2(n), CUR(), DEC());
const signed = t => (t.type === 'income' ? '+' : '\u2212') + money(t.amount);
const sym = () => {try {return new Intl.NumberFormat(LOC(), {style: 'currency', currency: CUR()}).formatToParts(0).find(p => p.type === 'currency').value;} catch (e) {return '$';}};
/* money with smaller cents: $1,234<small>.56</small> */
function moneyHTML(n) {
  let main = '', dec = '', inDec = false;
  try {
    new Intl.NumberFormat(LOC(), {style: 'currency', currency: CUR(), minimumFractionDigits: DEC(), maximumFractionDigits: DEC()}).formatToParts(r2(n)).forEach(p => {
      if (p.type === 'decimal') inDec = true;
      if (inDec) dec += p.value; else main += p.value;
    });
  } catch (e) {main = money(n);}
  return AK.html`<span class="m-main">${main}</span>${dec ? AK.html`<span class="m-dec">${dec}</span>` : ''}`;
}
const pad = n => String(n).padStart(2, '0');
const iso = d => d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
const parseISO = s => {const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s || ''); return m ? new Date(+m[1], m[2] - 1, +m[3]) : null;};
const addDays = (s, n) => {const d = parseISO(s); d.setDate(d.getDate() + n); return iso(d);};
const dim = (y, m0) => new Date(y, m0 + 1, 0).getDate();
const monthAdd = (m, k) => iso(new Date(+m.slice(0, 4), +m.slice(5, 7) - 1 + k, 1)).slice(0, 7);
const monthDate = m => new Date(+m.slice(0, 4), +m.slice(5, 7) - 1, 1);
const monthName = (m, short) => monthDate(m).toLocaleDateString(LOC(), short ? {month: 'short'} : {month: 'long', year: 'numeric'});
const monthOnly = m => monthDate(m).toLocaleDateString(LOC(), {month: 'long'});
const thisMonth = () => AK.today().slice(0, 7);
const ordinal = n => n + (n % 100 >= 11 && n % 100 <= 13 ? 'th' : ['th', 'st', 'nd', 'rd'][n % 10] || 'th');
function dayTitle(s) {
  const d = parseISO(s), t = AK.today();
  if (s === t) return 'Today';
  if (s === addDays(t, -1)) return 'Yesterday';
  return d.toLocaleDateString(LOC(), d.getFullYear() === new Date().getFullYear() ? {weekday: 'short', month: 'short', day: 'numeric'} : {month: 'short', day: 'numeric', year: 'numeric'});
}
/* where we are in a month: 1 for past months, 0 for future ones */
function monthProgress(m) {
  const cur = thisMonth();
  if (m < cur) return 1;
  if (m > cur) return 0;
  const d = new Date();
  return d.getDate() / dim(d.getFullYear(), d.getMonth());
}

/* ===== 5. REPEATING ENGINE: adds entries on their dates (monthly keeps its day: the 31st becomes the 30th, 28th...) ===== */
const FREQ = {weekly: 'Every week', biweekly: 'Every 2 weeks', monthly: 'Every month', yearly: 'Every year'};
const PER_MONTH = {weekly: 52 / 12, biweekly: 26 / 12, monthly: 1, yearly: 1 / 12};
function nextOcc(rule, s) {
  const d = parseISO(s);
  if (rule.freq === 'weekly') return addDays(s, 7);
  if (rule.freq === 'biweekly') return addDays(s, 14);
  if (rule.freq === 'yearly') {const y = d.getFullYear() + 1; return iso(new Date(y, rule.month, Math.min(rule.day, dim(y, rule.month))));}
  const y = d.getFullYear(), m = d.getMonth() + 1;
  return iso(new Date(y, m, Math.min(rule.day, dim(y, m))));
}
function firstOnOrAfter(rule, from) {
  let d = rule.start, g = 0;
  while (d < from && g++ < 6000) d = nextOcc(rule, d);
  return d;
}
function anchor(rule) {const d = parseISO(rule.start); rule.day = d.getDate(); rule.month = d.getMonth(); return rule;}
function runRecurring() {
  const today = AK.today(), s = S(), todo = [];
  s.recs.forEach(r => {
    if (!r.active || !r.next) return;
    const have = new Set(s.txns.filter(t => t.rec === r.id).map(t => t.date)), add = [];
    let d = r.next, g = 0;
    while (d <= today && (!r.end || d <= r.end) && g++ < 1000) {if (!have.has(d)) add.push(d); d = nextOcc(r, d);}
    if (d !== r.next) todo.push({id: r.id, next: d, add});
  });
  if (!todo.length) return 0;
  let n = 0;
  db.update(st => {
    todo.forEach(t => {
      const r = st.recs.find(x => x.id === t.id); if (!r) return;
      r.next = t.next;
      t.add.forEach(date => {n++; st.txns.push(clean({id: AK.id(), type: r.type, amount: r.amount, cat: r.cat, date, note: r.note, rec: r.id, at: Date.now() + n, sample: r.sample}));});
    });
  });
  return n;
}
const clean = o => {Object.keys(o).forEach(k => {if (o[k] === undefined) delete o[k];}); return o;};
function lastGenerated(id) {return S().txns.filter(t => t.rec === id).reduce((a, t) => t.date > a ? t.date : a, '');}
function ruleText(r) {
  const d = parseISO(r.start);
  if (r.freq === 'monthly') return 'Every month on the ' + ordinal(r.day) + (r.day > 28 ? ' (or last day)' : '');
  if (r.freq === 'yearly') return 'Every year on ' + d.toLocaleDateString(LOC(), {month: 'short', day: 'numeric'});
  return FREQ[r.freq] + ' on ' + d.toLocaleDateString(LOC(), {weekday: 'long'});
}
function ruleState(r) {
  if (!r.active) return {text: 'Paused', cls: 'warn'};
  if (r.end && r.next > r.end) return {text: 'Ended', cls: ''};
  return {text: 'Next ' + AK.fmt.day(r.next), cls: 'ok'};
}
/* occurrences of every active rule between two dates (for "Coming up") */
function upcoming(days) {
  const from = AK.today(), to = addDays(from, days), out = [];
  S().recs.forEach(r => {
    if (!r.active) return;
    let d = r.next, g = 0;
    while (d && d <= to && (!r.end || d <= r.end) && g++ < 60) {if (d >= from) out.push({r, date: d}); d = nextOcc(r, d);}
  });
  return out.sort((a, b) => a.date < b.date ? -1 : a.date > b.date ? 1 : 0);
}

/* ===== 6. MONTH NUMBERS ===== */
const inMonth = m => S().txns.filter(t => t.date && t.date.slice(0, 7) === m);
function totals(list) {
  let i = 0, o = 0;
  list.forEach(t => {if (t.type === 'income') i += toC(t.amount); else o += toC(t.amount);});
  return {inc: i / 100, out: o / 100, net: (i - o) / 100};
}
function byCat(list, type) {
  const m = new Map();
  list.filter(t => t.type === type).forEach(t => m.set(t.cat, (m.get(t.cat) || 0) + toC(t.amount)));
  return Array.from(m, ([id, c]) => ({id, c: cat(id), value: c / 100})).sort((a, b) => b.value - a.value);
}
const spentIn = (m, catId) => sumC(inMonth(m).filter(t => t.type === 'expense' && t.cat === catId), t => t.amount);
function budgetRows(m) {
  const p = monthProgress(m);
  return cats('expense').filter(c => c.budget > 0).map(c => {
    const spent = spentIn(m, c.id), pct = spent / c.budget, left = r2(c.budget - spent);
    let st = {cls: 'ok', text: 'On track'};
    if (spent > c.budget) st = {cls: 'bad', text: 'Over by ' + money(spent - c.budget)};
    else if (pct >= 0.9) st = {cls: 'warn', text: 'Almost used'};
    else if (p > 0 && p < 1 && pct > p + 0.15) st = {cls: 'warn', text: 'Ahead of pace'};
    return {c, spent, pct, left, st};
  }).sort((a, b) => b.pct - a.pct);
}
const sortDesc = (a, b) => a.date < b.date ? 1 : a.date > b.date ? -1 : (b.at || 0) - (a.at || 0);

/* ===== 7. SHARED BITS OF MARKUP (small, so they live here; the app shell itself is created at the very end) ===== */
const catLead = c => AK.html`<span class="lead cat-ico" style="--cc:${hex(c.color)}">${icon(c.icon)}</span>`;
function txnRow(t, showDay) {
  const c = cat(t.cat), rule = t.rec && S().recs.find(r => r.id === t.rec);
  return AK.html`<li><button type="button" class="item txn" data-act="edit-txn" data-id="${t.id}">${catLead(c)}
    <span class="body"><span class="title">${t.note || c.name}</span><span class="sub">${t.note ? c.name : (t.type === 'income' ? 'Money in' : 'Money out')}${rule ? AK.html` \u00b7 ${icon('refresh', 'tiny')} ${FREQ[rule.freq]}` : ''}${t.photo ? AK.html` \u00b7 ${icon('camera', 'tiny')} receipt` : ''}</span></span>
    <span class="trail"><span class="value amt${t.type === 'income' ? ' in' : ''}">${signed(t)}</span>${showDay ? AK.html`<span class="sub">${AK.fmt.day(t.date)}</span>` : ''}</span></button></li>`;
}
function monthNav(page) {
  const cur = thisMonth();
  const n = slot(page, 'now-btn'); if (n) n.hidden = ui.month === cur;
  setText(page, 'mn-label', monthName(ui.month));
}
function banner(page) {
  const b = slot(page, 'banner'); if (!b) return;
  const parts = [];
  if (hasSample()) parts.push(AK.html`<div class="note sample-note">${icon('info')}<div><strong>You are looking at sample data.</strong> The entries are made up so you can try things.<span class="note-act"><button type="button" class="btn sm soft" data-act="clear-sample">${icon('x')}Clear sample data</button></span></div></div>`);
  const over = budgetRows(ui.month).filter(r => r.st.cls === 'bad');
  if (over.length) parts.push(AK.html`<button type="button" class="note bad note-btn" data-act="go" data-to="/budgets">${icon('alert')}<div><strong>${over.length === 1 ? over[0].c.name + ' is over budget' : over.length + ' budgets are over'}</strong> in ${monthOnly(ui.month)}. Tap to see.</div></button>`);
  b.innerHTML = parts.join('');
}

/* ===== 8. HOME ===== */
function renderHome(page, ctx) {
  if (ctx.param === 'report') return renderReport(page);
  page.append(tpl('view-home'));
  const s = S(), m = ui.month, cur = thisMonth(), empty = !s.txns.length && !s.recs.length;
  setText(page, 'month-title', monthName(m));
  const d = new Date();
  setText(page, 'month-sub', m === cur ? 'Day ' + d.getDate() + ' of ' + dim(d.getFullYear(), d.getMonth()) : m < cur ? 'A past month' : 'A month ahead');
  monthNav(page);
  slot(page, 'welcome').hidden = !empty;
  slot(page, 'main').hidden = empty;
  $('.month-nav', page).hidden = empty;
  if (empty) return;
  banner(page);
  /* compare with last month; while a month is still running, only with the same days of last month (fair) */
  const list = inMonth(m), t = totals(list), pmo = monthAdd(m, -1), upto = m === cur ? d.getDate() : 31;
  const prev = totals(inMonth(pmo).filter(x => +x.date.slice(8) <= upto));
  const pm = monthName(pmo, true) + (m === cur ? ' 1\u2013' + Math.min(upto, dim(+pmo.slice(0, 4), +pmo.slice(5, 7) - 1)) : '');
  /* big numbers */
  fitText(slot(page, 'net'), (t.net < 0 ? '-' : '') + money(Math.abs(t.net)), (t.net < 0 ? '\u2212' : '') + moneyHTML(Math.abs(t.net)));
  setText(page, 'net-label', m === cur ? 'Net this month' : 'Net for ' + monthOnly(m));
  const ratio = t.inc > 0 ? t.out / t.inc : 0;
  slot(page, 'flow').style.setProperty('--fill', Math.min(1, t.inc > 0 ? ratio : t.out > 0 ? 1 : 0));
  setText(page, 'net-meta', !t.inc && !t.out ? 'Nothing added for this month yet' : !t.inc ? 'No money in yet' :
    ratio <= 1 ? 'You kept ' + (ratio > 0 && ratio < 0.005 ? 'over 99%' : ratio < 1 && ratio > 0.995 ? 'under 1%' : AK.fmt.percent(1 - ratio)) + ' of what came in' : 'Spent ' + money(t.out - t.inc) + ' more than came in');
  fitText(slot(page, 'income'), money(t.inc), moneyHTML(t.inc));
  fitText(slot(page, 'spent'), money(t.out), moneyHTML(t.out));
  evenFit(page);
  delta(slot(page, 'income-delta'), t.inc, prev.inc, pm, true);
  delta(slot(page, 'spent-delta'), t.out, prev.out, pm, false);
  drawSpending(page, list);
  drawMonths(page, m);
  drawBudgetPeek(page, m);
  /* recent + coming up */
  const recent = list.slice().sort(sortDesc).slice(0, 5);
  setHTML(page, 'recent', recent.length ? AK.html`<ul class="list">${recent.map(x => txnRow(x, true))}</ul>` :
    AK.html`<div class="card">${AK.empty({art: 'list', title: 'No entries in ' + monthOnly(m), text: 'Tap Add entry to put one in.'})}</div>`);
  const up = upcoming(30).slice(0, 5);
  setHTML(page, 'upcoming', up.length ? AK.html`<ul class="list">${up.map(u => AK.html`<li><button type="button" class="item" data-act="edit-rec" data-id="${u.r.id}">${catLead(cat(u.r.cat))}
      <span class="body"><span class="title">${u.r.note || cat(u.r.cat).name}</span><span class="sub">${FREQ[u.r.freq]}</span></span>
      <span class="trail"><span class="value amt${u.r.type === 'income' ? ' in' : ''}">${signed(u.r)}</span><span class="sub">${AK.fmt.day(u.date)}</span></span></button></li>`)}</ul>` :
    AK.html`<div class="card">${AK.empty({art: 'inbox', title: 'Nothing due in the next 30 days', text: 'Rent, payroll or a phone bill? Set it once under Repeating.', action: {label: 'New repeating entry', act: 'new-rec'}})}</div>`);
}
function delta(el, now, before, label, upIsGood) {
  if (!el) return;
  if (!before) {el.textContent = now ? 'Nothing in ' + label : ''; return;}
  const ch = (now - before) / before, up = ch >= 0;
  el.className = 'delta ' + ((up === upIsGood) ? 'up' : 'down');
  el.innerHTML = String(AK.html`${icon(up ? 'trend-up' : 'trend-down')} ${AK.fmt.percent(Math.abs(ch))} vs ${label}`);
}
/* donut by category (own SVG so each slice wears its category colour) + breakdown rows */
function drawSpending(page, list) {
  const parts = byCat(list, 'expense'), total = sumC(parts, p => p.value);
  setText(page, 'spend-count', parts.length ? parts.length + (parts.length === 1 ? ' category' : ' categories') : '');
  if (!total) {
    slot(page, 'donut-wrap').hidden = true;
    setHTML(page, 'spend-empty', AK.empty({art: 'chart', title: 'No money out this month', text: 'Spending by category shows up here.'}));
    return;
  }
  const top = parts.slice(0, 6), rest = parts.slice(6);
  if (rest.length) top.push({id: '', c: {name: 'Everything else', color: 'slate', icon: 'more'}, value: sumC(rest, p => p.value), rest: true});
  const size = 200, th = 26, r = (size - th) / 2, C = 2 * Math.PI * r, gap = top.length > 1 ? 3 : 0;
  let acc = 0;
  const segs = top.map((p, i) => {
    const len = p.value / total * C, out = `<circle class="dseg" data-i="${i}" cx="100" cy="100" r="${r}" style="--cc:${hex(p.c.color)};stroke-width:${th};stroke-dasharray:${Math.max(0.01, len - gap).toFixed(2)} ${C.toFixed(2)};stroke-dashoffset:${(-acc).toFixed(2)}"/>`;
    acc += len; return out;
  }).join('');
  setHTML(page, 'donut', `<svg viewBox="0 0 ${size} ${size}" class="donut-svg" role="img" aria-label="Money out by category: ${AK.esc(top.map(p => p.c.name + ' ' + money(p.value)).join(', '))}"><circle class="dtrack" cx="100" cy="100" r="${r}" style="stroke-width:${th}"/><g transform="rotate(-90 100 100)">${segs}</g></svg>`);
  const center = (v, l) => {fitText(slot(page, 'dc-val'), money(v)); setText(page, 'dc-lbl', l);};
  center(total, 'Spent');
  const rows = parts.map((p, i) => AK.html`<li${i >= 5 ? AK.raw(' class="more-row" hidden') : ''}><button type="button" class="cat-row" data-act="show-cat" data-id="${p.id}" data-i="${i < 6 ? i : top.length - 1}">${catLead(p.c)}
    <span class="cr-body"><span class="cr-top"><span class="cr-name">${p.c.name}</span><span class="cr-val">${money(p.value)}</span></span>
    <span class="cr-low"><span class="cr-bar" style="--cc:${hex(p.c.color)};--w:${(p.value / parts[0].value).toFixed(4)}"><span></span></span><span class="cr-pct">${Math.round(p.value / total * 100)}%</span></span></span></button></li>`);
  const ul = setHTML(page, 'breakdown', AK.html`${rows}${parts.length > 5 ? AK.html`<li><button type="button" class="link-btn show-more" data-more>Show ${parts.length - 5} more</button></li>` : ''}`);
  const hi = i => {$$('.dseg', page).forEach(sg => sg.classList.toggle('dim', i != null && sg.dataset.i !== String(i))); if (i == null) center(total, 'Spent'); else center(top[i].value, top[i].c.name);};
  ul.addEventListener('pointerover', e => {const b = e.target.closest('.cat-row'); if (b) hi(+b.dataset.i);});
  ul.addEventListener('pointerleave', () => hi(null));
  ul.addEventListener('focusin', e => {const b = e.target.closest('.cat-row'); if (b) hi(+b.dataset.i);});
  ul.addEventListener('focusout', () => hi(null));
  ul.addEventListener('click', e => {if (e.target.closest('[data-more]')) {$$('.more-row', ul).forEach(li => li.hidden = false); e.target.closest('li').remove();}});
}
function drawMonths(page, m) {
  const ms = [-5, -4, -3, -2, -1, 0].map(k => monthAdd(m, k)), tt = ms.map(x => totals(inMonth(x)));
  AK.chart.spark(slot(page, 'in-spark'), tt.map(x => x.inc), {height: 30});
  AK.chart.spark(slot(page, 'out-spark'), tt.map(x => x.out), {height: 30, color: 'var(--ak-c4)'});
  /* side by side on wide screens: let the bars fill the height of the donut card next to them */
  const box = slot(page, 'bars'), card = box.closest('.card'), other = $('.spend-card', page);
  const sideBySide = other && card && Math.abs(other.getBoundingClientRect().top - card.getBoundingClientRect().top) < 2 && window.innerWidth >= 1100;
  AK.chart.bar(box, {labels: ms.map(x => monthName(x, true)), format: 'money', title: 'Money in and out by month', height: sideBySide ? Math.max(220, other.offsetHeight - 130) : undefined,
    series: [{name: 'Money in', values: tt.map(x => x.inc), color: 'var(--ak-c1)'}, {name: 'Money out', values: tt.map(x => x.out), color: 'var(--ak-c4)'}]});
  const used = tt.filter(x => x.inc || x.out);
  setText(page, 'avg', used.length > 1 ? 'Average month: in ' + money(sumC(used, x => x.inc) / used.length) + ', out ' + money(sumC(used, x => x.out) / used.length) : '');
}
function drawBudgetPeek(page, m) {
  const rows = budgetRows(m).slice(0, 3);
  setHTML(page, 'budgets', rows.length ? AK.html`<ul class="list">${rows.map(b => AK.html`<li><button type="button" class="item" data-act="edit-budget" data-id="${b.c.id}">
      <span class="ring sm-ring ${b.st.cls}" style="--value:${Math.min(100, Math.round(b.pct * 100))}"><span>${Math.round(b.pct * 100)}%</span></span>
      <span class="body"><span class="title">${b.c.name}</span><span class="sub">${money(b.spent)} of ${money(b.c.budget)}</span></span>
      <span class="trail"><span class="value st-${b.st.cls}">${money(Math.abs(b.left))}</span><span class="sub">${b.left < 0 ? 'over' : 'left'}</span></span></button></li>`)}</ul>` :
    AK.html`<div class="card">${AK.empty({art: 'chart', title: 'No budgets yet', text: 'Give a category a monthly limit and watch it fill up.', action: {label: 'Set a budget', act: 'go-budgets'}})}</div>`);
}

/* ===== 9. ACTIVITY ===== */
function filtered() {
  const q = ui.q.trim().toLowerCase();
  let list = S().txns;
  if (ui.scope === 'month') list = list.filter(t => t.date.slice(0, 7) === ui.month);
  if (ui.type !== 'all') list = list.filter(t => t.type === ui.type);
  if (ui.cat) list = list.filter(t => t.cat === ui.cat);
  if (ui.photo) list = list.filter(t => t.photo);
  if (q) {
    /* "45", "$1,200" or "12.5" also match amounts */
    const qn = /^[^\da-z]*[\d.,]+$/i.test(q) ? String(AK.parseMoney(q)) : null;
    list = list.filter(t => {
      const amt = r2(t.amount).toFixed(2);
      return [t.note, cat(t.cat).name, amt, t.date, dayTitle(t.date)].join(' ').toLowerCase().indexOf(q) > -1 || (qn && amt.indexOf(qn) > -1);
    });
  }
  return list.slice().sort(sortDesc);
}
function renderActivity(page) {
  page.append(tpl('view-activity'));
  const q = $('#act-q', page), sel = $('#act-cat', page);
  q.value = ui.q;
  $$('input[name=scope]', page).forEach(r => r.checked = r.value === ui.scope);
  slot(page, 'month-nav').hidden = ui.scope !== 'month';
  monthNav(page);
  setText(page, 'scope-label', ui.scope === 'month' ? monthName(ui.month) : 'All time');
  $$('.filter-chips .chip[data-type]', page).forEach(c => c.setAttribute('aria-pressed', String(c.dataset.type === ui.type)));
  $('.chip[data-flag=photo]', page).setAttribute('aria-pressed', String(ui.photo));
  sel.innerHTML = String(AK.html`<option value="">All categories</option>${cats().map(c => AK.html`<option value="${c.id}">${c.name}</option>`)}`);
  sel.value = ui.cat;
  sel.classList.toggle('on', !!ui.cat);
  drawActivity(page);
  q.addEventListener('input', () => {ui.q = q.value; ui.limit = 120; drawActivity(page);});
  sel.addEventListener('change', () => {ui.cat = sel.value; ui.limit = 120; sel.classList.toggle('on', !!ui.cat); drawActivity(page);});
  $$('input[name=scope]', page).forEach(r => r.addEventListener('change', () => {ui.scope = r.value; app.refresh();}));
  $('.filter-chips', page).addEventListener('click', e => {
    const c = e.target.closest('.chip[data-type],.chip[data-flag]'); if (!c) return;
    if (c.dataset.type) ui.type = c.dataset.type; else ui.photo = !ui.photo;
    $$('.filter-chips .chip[data-type]', page).forEach(x => x.setAttribute('aria-pressed', String(x.dataset.type === ui.type)));
    $('.chip[data-flag=photo]', page).setAttribute('aria-pressed', String(ui.photo));
    ui.limit = 120; drawActivity(page);
  });
}
function drawActivity(page) {
  const list = filtered(), t = totals(list), box = slot(page, 'list');
  slot(page, 'export-row').hidden = !list.length;
  setText(page, 'summary', list.length ? list.length + (list.length === 1 ? ' entry' : ' entries') + ' \u00b7 in ' + money(t.inc) + ' \u00b7 out ' + money(t.out) : 'No entries');
  if (!list.length) {
    const filteredOut = ui.q || ui.cat || ui.photo || ui.type !== 'all';
    box.innerHTML = String(AK.html`<div class="card">${filteredOut ? AK.empty({art: 'search', title: 'Nothing matches', text: 'Try fewer letters or clear a filter.', action: {label: 'Clear filters', act: 'clear-filters'}}) :
      AK.empty({art: 'list', title: ui.scope === 'month' ? 'No entries in ' + monthOnly(ui.month) : 'No entries yet', text: 'Money in and out shows up here, newest first.', action: {label: 'Add entry', act: 'new-txn', icon: 'plus'}})}</div>`);
    return;
  }
  const shown = list.slice(0, ui.limit), groups = [];
  shown.forEach(x => {const g = groups[groups.length - 1]; if (g && g.date === x.date) g.items.push(x); else groups.push({date: x.date, items: [x]});});
  box.innerHTML = String(AK.html`${groups.map(g => {
    const n = totals(g.items).net;
    return AK.html`<section class="day"><div class="day-head"><h2>${dayTitle(g.date)}</h2><span class="day-sum">${(n < 0 ? '\u2212' : '+') + money(Math.abs(n))}</span></div><ul class="list">${g.items.map(x => txnRow(x, false))}</ul></section>`;
  })}${list.length > shown.length ? AK.html`<button type="button" class="btn soft block" data-act="more-rows">Show ${Math.min(120, list.length - shown.length)} more of ${list.length - shown.length}</button>` : ''}`);
  setText(page, 'export-label', 'Export ' + (list.length === 1 ? 'this entry' : 'these ' + list.length + ' entries') + ' as CSV');
}

/* ===== 10. BUDGETS ===== */
function renderBudgets(page) {
  page.append(tpl('view-budgets'));
  const m = ui.month, rows = budgetRows(m), p = monthProgress(m), d = new Date();
  setText(page, 'month-label', monthName(m));
  setText(page, 'pace-sub', m === thisMonth() ? 'Day ' + d.getDate() + ' of ' + dim(d.getFullYear(), d.getMonth()) : m < thisMonth() ? 'A past month' : 'A month ahead');
  slot(page, 'has-budgets').hidden = !rows.length;
  slot(page, 'no-budgets').hidden = !!rows.length;
  if (!rows.length) setHTML(page, 'no-budgets', AK.empty({art: 'chart', title: 'No budgets yet', text: 'Pick a category below and give it a monthly limit. Rings fill up as you spend.'}));
  else {
    const B = sumC(rows, r => r.c.budget), Sp = sumC(rows, r => r.spent), left = r2(B - Sp);
    setText(page, 'left-label', left >= 0 ? 'Left to spend' : 'Over budget');
    fitText(slot(page, 'left'), money(Math.abs(left)), moneyHTML(Math.abs(left)));
    const bar = slot(page, 'pace-bar');
    bar.style.setProperty('--fill', Math.min(1, B ? Sp / B : 0)); bar.style.setProperty('--pace', p);
    bar.hidden = p === 0;
    setText(page, 'left-meta', !B ? '' : Sp > B ? 'Spent ' + AK.fmt.percent(Sp / B) + ' of all budgets' : p > 0 && p < 1 ? (Sp / B <= p ? 'Under an even pace. Nice.' : 'Spending faster than an even pace') : AK.fmt.percent(Sp / B) + ' of all budgets used');
    fitText(slot(page, 'budgeted'), money(B), moneyHTML(B));
    setText(page, 'budgeted-meta', rows.length + (rows.length === 1 ? ' category' : ' categories'));
    fitText(slot(page, 'spent'), money(Sp), moneyHTML(Sp));
    setText(page, 'spent-meta', 'in these budgets');
    evenFit(page);
    setHTML(page, 'alerts', rows.filter(r => r.st.cls === 'bad').map(r => AK.html`<div class="note bad">${icon('alert')}<div><strong>${r.c.name}</strong> is over budget by ${money(r.spent - r.c.budget)}.</div></div>`).join(''));
    setHTML(page, 'grid', rows.map(r => AK.html`<button type="button" class="card budget ${r.st.cls}" data-act="edit-budget" data-id="${r.c.id}">
      <span class="ring b-ring ${r.st.cls}" style="--value:${Math.min(100, Math.round(r.pct * 100))}"><span>${Math.round(r.pct * 100)}%</span></span>
      <span class="b-body"><span class="b-name"><span class="b-dot" style="--cc:${hex(r.c.color)}">${icon(r.c.icon)}</span>${r.c.name}</span>
        <span class="b-nums"><b>${money(r.spent)}</b> of ${money(r.c.budget)}</span>
        <span class="pill ${r.st.cls}">${r.st.cls === 'ok' ? money(r.left) + ' left' : r.st.text}</span></span></button>`).join(''));
  }
  const un = cats('expense').filter(c => !(c.budget > 0));
  slot(page, 'unbudgeted-wrap').hidden = !un.length;
  setHTML(page, 'unbudgeted', un.map(c => AK.html`<li><button type="button" class="item" data-act="edit-budget" data-id="${c.id}">${catLead(c)}
    <span class="body"><span class="title">${c.name}</span><span class="sub">${money(spentIn(m, c.id))} spent in ${monthOnly(m)}</span></span>
    <span class="trail row"><span class="set-link">Set budget</span>${icon('chevron-right', 'chev')}</span></button></li>`).join(''));
}

/* ===== 11. REPEATING ===== */
function renderRepeat(page) {
  page.append(tpl('view-repeat'));
  const recs = S().recs, on = recs.filter(r => r.active && !(r.end && r.next > r.end));
  const per = type => r2(on.filter(r => r.type === type).reduce((a, r) => a + r.amount * PER_MONTH[r.freq], 0)); /* round once, at the end */
  fitText(slot(page, 'out'), money(per('expense')), moneyHTML(per('expense')));
  fitText(slot(page, 'in'), money(per('income')), moneyHTML(per('income')));
  const n = Math.max(money(per('expense')).length, money(per('income')).length);
  $$('.stats .fit', page).forEach(e => e.style.setProperty('--len', n));
  slot(page, 'stats').hidden = !recs.length;
  setHTML(page, 'list', recs.length ? AK.html`<ul class="list">${recs.slice().sort((a, b) => (a.next || '') < (b.next || '') ? -1 : 1).map(r => {
    const st = ruleState(r);
    return AK.html`<li><button type="button" class="item" data-act="edit-rec" data-id="${r.id}">${catLead(cat(r.cat))}
      <span class="body"><span class="title">${r.note || cat(r.cat).name}</span><span class="sub">${FREQ[r.freq]}${st.cls === 'ok' ? ' \u00b7 next ' + AK.fmt.day(r.next) : ''}</span></span>
      <span class="trail"><span class="value amt${r.type === 'income' ? ' in' : ''}">${signed(r)}</span>${st.cls !== 'ok' ? AK.html`<span class="pill ${st.cls}">${st.text}</span>` : ''}</span></button></li>`;
  })}</ul>` : AK.html`<div class="card">${AK.empty({art: 'inbox', title: 'Nothing repeating yet', text: 'Add rent, payroll or a phone bill once. It adds itself on every due date.', action: {label: 'New repeating entry', act: 'new-rec', icon: 'plus'}})}</div>`);
}

/* ===== 12. SETTINGS + CATEGORIES ===== */
function renderSettings(page, ctx) {
  if (ctx.param === 'categories') return renderCategories(page);
  page.append(tpl('view-settings'));
  const s = S(), sel = $('#set-cur', page);
  sel.value = CUR();
  sel.addEventListener('change', () => {db.set('currency', sel.value); AK.toast('Amounts now show in ' + sel.value, 'ok');});
  setText(page, 'cat-count', cats('expense').length + ' for money out, ' + cats('income').length + ' for money in');
  setText(page, 'last-backup', s.lastBackup ? 'Last backup saved ' + AK.fmt.date(s.lastBackup) + '.' : 'No backup saved yet.');
  const chars = JSON.stringify(s).length, frac = chars / 5e6, photos = s.txns.filter(t => t.photo).length;
  const bar = slot(page, 'store-bar');
  bar.style.setProperty('--value', Math.max(1, Math.min(100, Math.round(frac * 100))));
  bar.classList.toggle('warn', frac > 0.7);
  setText(page, 'store-text', s.txns.length + ' entries, ' + photos + (photos === 1 ? ' receipt photo' : ' receipt photos') + '. About ' + (chars / 1e6).toFixed(chars < 1e5 ? 2 : 1) + ' MB of roughly 5 MB used.');
  const smp = hasSample();
  slot(page, 'sample-btn').hidden = smp;
  slot(page, 'clear-sample-btn').hidden = !smp;
  if (smp) setHTML(page, 'sample-note', AK.html`<div class="note">${icon('info')}<div><strong>Sample data is loaded.</strong> Clear it before you start for real; your own entries stay.</div></div>`);
  $('#restore-in', page).addEventListener('change', e => {const f = e.target.files[0]; e.target.value = ''; if (f) restoreBackup(f);});
  $('#csv-in', page).addEventListener('change', e => {const f = e.target.files[0]; e.target.value = ''; if (f) importCSV(f);});
}
function renderCategories(page) {
  page.append(tpl('view-categories'));
  const s = S(), uses = id => s.txns.filter(t => t.cat === id).length;
  ['expense', 'income'].forEach(k => setHTML(page, k, cats(k).map(c => AK.html`<li><button type="button" class="item" data-act="edit-cat" data-id="${c.id}">${catLead(c)}
    <span class="body"><span class="title">${c.name}</span><span class="sub">${uses(c.id)} entries${c.budget > 0 ? ' \u00b7 budget ' + money(c.budget) + ' a month' : ''}</span></span>${icon('chevron-right', 'chev')}</button></li>`).join('') ||
    String(AK.html`<li class="item"><span class="sub">No categories yet.</span></li>`)));
}

/* ===== 13. MONTH REPORT (printable, with the owner's letterhead from index.html) ===== */
function renderReport(page) {
  page.append(tpl('view-report'));
  const m = ui.month, list = inMonth(m).sort((a, b) => a.date < b.date ? -1 : a.date > b.date ? 1 : (a.at || 0) - (b.at || 0)), t = totals(list);
  setText(page, 'title', monthName(m) + ' report');
  setText(page, 'period', monthName(m));
  setText(page, 'in', money(t.inc)); setText(page, 'out', money(t.out));
  setText(page, 'net', (t.net < 0 ? '\u2212' : '') + money(Math.abs(t.net))); setText(page, 'count', String(list.length));
  const pct = (v, tot) => tot ? Math.round(v / tot * 100) + '%' : '';
  const outs = byCat(list, 'expense'), ins = byCat(list, 'income');
  setHTML(page, 'out-rows', outs.map(p => AK.html`<tr><td>${p.c.name}</td><td class="num">${p.c.budget > 0 ? money(p.c.budget) : '\u2013'}</td><td class="num">${money(p.value)}</td><td class="num">${pct(p.value, t.out)}</td></tr>`).join('') || '<tr><td colspan="4" class="muted">No money out.</td></tr>');
  setHTML(page, 'in-rows', ins.map(p => AK.html`<tr><td>${p.c.name}</td><td class="num">${money(p.value)}</td><td class="num">${pct(p.value, t.inc)}</td></tr>`).join('') || '<tr><td colspan="3" class="muted">No money in.</td></tr>');
  setHTML(page, 'rows', list.map(x => AK.html`<tr><td class="nowrap">${AK.fmt.date(x.date, 'short')}</td><td><b class="rep-cat">${cat(x.cat).name}</b>${x.note ? AK.html`<span class="rep-note">${x.note}</span>` : ''}</td><td class="num">${signed(x)}</td></tr>`).join('') || '<tr><td colspan="3" class="muted">No entries this month.</td></tr>');
  setText(page, 't-in', money(t.inc)); setText(page, 't-out', money(t.out)); setText(page, 't-net', (t.net < 0 ? '\u2212' : '') + money(Math.abs(t.net)));
  setText(page, 'prepared', AK.fmt.date(new Date(), 'full'));
  $$('[data-fact=business]', page).forEach(e => {if (/\{\{/.test(e.textContent)) e.textContent = BUSINESS;});
}
function reportText() {
  const m = ui.month, list = inMonth(m), t = totals(list);
  return [BUSINESS + ' \u2013 ' + monthName(m), 'Money in: ' + money(t.inc), 'Money out: ' + money(t.out), 'Net: ' + (t.net < 0 ? '-' : '') + money(Math.abs(t.net)), '',
    'Money out by category:'].concat(byCat(list, 'expense').map(p => '  ' + p.c.name + ': ' + money(p.value))).join('\n');
}

/* ===== 14. ENTRY SHEET (add / edit money in or out) ===== */
function catGrid(box, kind, chosen) {
  const list = cats(kind);
  if (!list.some(c => c.id === chosen)) chosen = list.length ? list[0].id : '';
  box.innerHTML = list.length ? String(AK.html`${list.map(c => AK.html`<label class="cat-opt"><input type="radio" name="cat" value="${c.id}"${c.id === chosen ? AK.raw(' checked') : ''}>
    <span class="cat-ico" style="--cc:${hex(c.color)}">${icon(c.icon)}</span><span class="nm">${c.name}</span></label>`)}`) :
    '<p class="muted small">No categories for this yet. Add one in Settings.</p>';
}
function openTxn(id, preset) {
  const t = id ? db.find('txns', id) : null, st = {photo: t ? t.photo || null : null};
  const f = tpl('form-txn'), form = $('form', f);
  $$('[data-slot=sym]', f).forEach(e => e.textContent = sym());
  const v = t || Object.assign({type: 'expense', date: AK.today(), note: ''}, preset);
  const L = AK.sheet({
    title: t ? 'Edit entry' : 'New entry', body: f, focus: t ? null : 'input[name=amount]',
    actions: (t ? [{label: 'Delete', class: 'ghost danger-text', icon: 'trash', onClick: () => {deleteTxn(t.id);}}] : [])
      .concat([{label: t ? 'Save' : 'Add entry', primary: true, onClick: () => saveTxn(form, t, st)}])
  });
  AK.fill(form, {type: v.type, amount: v.amount != null ? v.amount : '', date: v.date, note: v.note || ''});
  catGrid(slot(form, 'cats'), v.type, v.cat);
  slot(form, 'repeat-field').hidden = !!t;
  $$('input[name=type]', form).forEach(r => r.addEventListener('change', () => {const cur = (form.elements.cat && form.elements.cat.value) || ''; catGrid(slot(form, 'cats'), r.value, cur);}));
  /* receipt photo */
  const drawReceipt = () => {
    setHTML(form, 'receipt', st.photo ? AK.html`<div class="receipt-row"><button type="button" class="receipt-thumb" data-view><img src="${st.photo}" alt="Receipt photo" width="96" height="96"></button>
      <button type="button" class="btn ghost sm" data-drop>${icon('x')}Remove photo</button></div>` : '');
    setText(form, 'photo-label', st.photo ? 'Replace the photo' : 'Add a receipt photo');
  };
  drawReceipt();
  slot(form, 'photo-in').addEventListener('change', e => {
    const inp = e.target; if (!inp.files[0]) return;
    AK.photo(inp, {max: 1100, quality: 0.7}).then(url => {inp.value = ''; if (url) {st.photo = url; drawReceipt();}});
  });
  slot(form, 'receipt').addEventListener('click', e => {
    if (e.target.closest('[data-drop]')) {st.photo = null; drawReceipt();}
    else if (e.target.closest('[data-view]')) viewPhoto(st.photo);
  });
  /* made by a repeating entry? */
  const rule = t && t.rec && S().recs.find(r => r.id === t.rec);
  if (rule) {
    slot(form, 'rec-note').hidden = false;
    setText(form, 'rec-text', 'Added by the repeating entry \u201c' + (rule.note || cat(rule.cat).name) + '\u201d (' + FREQ[rule.freq].toLowerCase() + ').');
    slot(form, 'rec-edit').addEventListener('click', () => {L.close(); setTimeout(() => openRule(rule.id), 260);});
  }
  return L;
}
function saveTxn(form, t, st) {
  const r = AK.validate(form, {amount: {required: true, test: v => (v > 0 && v < 1e9) || 'Enter an amount above zero'}, date: {required: true}});
  if (!r.ok) return false;
  const v = r.values, catId = v.cat || (cats(v.type)[0] || {}).id || '';
  const item = {type: v.type, amount: r2(v.amount), cat: catId, date: v.date, note: (v.note || '').trim(), photo: st.photo || null};
  if (t) {db.update('txns', t.id, item); AK.toast('Saved', 'ok'); return;}
  if (v.repeat) {
    const rule = anchor({type: item.type, amount: item.amount, cat: item.cat, note: item.note, freq: v.repeat, start: item.date, end: '', next: item.date, active: true});
    db.add('recs', rule);
    const n = runRecurring();
    if (item.photo) {const first = S().txns.find(x => x.rec === S().recs[S().recs.length - 1].id); if (first) db.update('txns', first.id, {photo: item.photo});}
    AK.toast(n ? 'Added. It will repeat ' + FREQ[v.repeat].toLowerCase() + '.' : 'Saved. First one on ' + AK.fmt.date(item.date) + '.', 'ok');
    return;
  }
  db.add('txns', Object.assign(item, {at: Date.now()}));
  const other = item.date.slice(0, 7) !== ui.month;
  AK.toast(other ? 'Added to ' + monthName(item.date.slice(0, 7)) : (item.type === 'income' ? 'Money in added' : 'Money out added'), {type: 'ok', undo: () => db.undo()});
}
async function deleteTxn(id) {
  const t = db.find('txns', id); if (!t) return;
  if (!await AK.confirm('Delete this entry?', {text: signed(t) + ' \u00b7 ' + (t.note || cat(t.cat).name), ok: 'Delete', danger: true})) return;
  db.remove('txns', id);
  AK.toast('Entry deleted', {undo: () => db.undo()});
}
function viewPhoto(src) {
  AK.modal({title: 'Receipt', body: AK.html`<img class="receipt-big" src="${src}" alt="Receipt photo" width="800" height="1000">`, actions: [{label: 'Close', primary: true}]});
}

/* ===== 15. CATEGORY SHEET ===== */
function openCat(id) {
  const c = id ? S().cats.find(x => x.id === id) : null, f = tpl('form-category'), form = $('form', f);
  $$('[data-slot=sym]', f).forEach(e => e.textContent = sym());
  const color = c ? c.color : Object.keys(COLORS)[S().cats.length % 12], ic = c ? c.icon : 'tag';
  setHTML(f, 'colors', Object.keys(COLORS).map(k => AK.html`<label class="swatch" style="--cc:${COLORS[k]}"><input type="radio" name="color" value="${k}" aria-label="${k}"${k === color ? AK.raw(' checked') : ''}><span></span></label>`).join(''));
  setHTML(f, 'icons', CAT_ICONS.map(k => AK.html`<label class="icon-opt"><input type="radio" name="icon" value="${k}" aria-label="${k.replace('-', ' ')}"${k === ic ? AK.raw(' checked') : ''}><span>${icon(k)}</span></label>`).join(''));
  AK.sheet({title: c ? 'Edit category' : 'New category', body: f, focus: c ? null : 'input[name=name]',
    actions: (c ? [{label: 'Delete', class: 'ghost danger-text', icon: 'trash', onClick: () => {deleteCat(c.id);}}] : [])
      .concat([{label: 'Save', primary: true, onClick: () => saveCat(form, c)}])});
  AK.fill(form, {name: c ? c.name : '', kind: c ? c.kind : 'expense', budget: c && c.budget > 0 ? c.budget : ''});
  const kindChange = () => {slot(form, 'budget-field').hidden = form.elements.kind.value !== 'expense';};
  $$('input[name=kind]', form).forEach(r => r.addEventListener('change', kindChange)); kindChange();
}
function saveCat(form, c) {
  const r = AK.validate(form, {name: {required: true, test: (v, all) => !S().cats.some(x => x.kind === all.kind && x.name.toLowerCase() === v.trim().toLowerCase() && (!c || x.id !== c.id)) || 'You already have a category with this name'},
    budget: {min: 0}});
  if (!r.ok) return false;
  const v = r.values, item = {name: v.name.trim(), kind: v.kind, color: v.color || 'slate', icon: v.icon || 'tag', budget: v.kind === 'expense' && v.budget > 0 ? r2(v.budget) : null};
  if (c) db.update('cats', c.id, item); else db.add('cats', item);
  AK.toast(c ? 'Category saved' : 'Category added', 'ok');
}
async function deleteCat(id) {
  const c = S().cats.find(x => x.id === id), s = S(), n = s.txns.filter(t => t.cat === id).length + s.recs.filter(r => r.cat === id).length;
  const others = cats(c.kind).filter(x => x.id !== id);
  if (n && !others.length) {AK.toast('Keep at least one category for ' + (c.kind === 'income' ? 'money in' : 'money out') + '.', 'warn'); return;}
  if (!n) {
    if (!await AK.confirm('Delete \u201c' + c.name + '\u201d?', {ok: 'Delete', danger: true})) return;
    db.remove('cats', id); AK.toast('Category deleted', {undo: () => db.undo()}); return;
  }
  AK.modal({title: 'Delete \u201c' + c.name + '\u201d?', body: AK.html`<form class="form"><p class="muted">${n} ${n === 1 ? 'entry uses' : 'entries use'} this category. Move them to:</p>
      <div class="field"><label for="mv-to">Category</label><select id="mv-to" name="to">${others.map(o => AK.html`<option value="${o.id}">${o.name}</option>`)}</select></div></form>`,
    actions: [{label: 'Cancel'}, {label: 'Move and delete', danger: true, onClick: L => {
      const to = $('#mv-to', L.body).value;
      db.update(st => {st.txns.forEach(t => {if (t.cat === id) t.cat = to;}); st.recs.forEach(r => {if (r.cat === id) r.cat = to;}); st.cats = st.cats.filter(x => x.id !== id);});
      AK.toast('Moved and deleted', {undo: () => db.undo()});
    }}]});
}

/* ===== 16. REPEATING SHEET ===== */
function openRule(id) {
  const r = id ? S().recs.find(x => x.id === id) : null, f = tpl('form-repeat'), form = $('form', f);
  $$('[data-slot=sym]', f).forEach(e => e.textContent = sym());
  const v = r || {type: 'expense', freq: 'monthly', start: AK.today(), end: '', note: '', active: true};
  AK.sheet({title: r ? 'Edit repeating entry' : 'New repeating entry', body: f, focus: r ? null : 'input[name=amount]',
    actions: (r ? [{label: 'Delete', class: 'ghost danger-text', icon: 'trash', onClick: () => {deleteRule(r.id);}}] : [])
      .concat([{label: 'Save', primary: true, onClick: () => saveRule(form, r)}])});
  AK.fill(form, {type: v.type, amount: v.amount != null ? v.amount : '', note: v.note, freq: v.freq, start: v.start, end: v.end || '', active: v.active !== false});
  catGrid(slot(form, 'cats'), v.type, v.cat);
  $$('input[name=type]', form).forEach(x => x.addEventListener('change', () => catGrid(slot(form, 'cats'), x.value, '')));
  const line = () => {
    const st = form.elements.start.value, fr = form.elements.freq.value;
    setText(form, 'rule-text', parseISO(st) ? ruleText(anchor({freq: fr, start: st})) + (st < AK.today() && !r ? '. Dates before today are added right away.' : '.') : '');
  };
  form.addEventListener('input', line); form.addEventListener('change', line); line();
}
function saveRule(form, old) {
  const res = AK.validate(form, {amount: {required: true, test: v => (v > 0 && v < 1e9) || 'Enter an amount above zero'}, start: {required: true},
    end: {test: (v, all) => !v || v >= all.start || 'The last date must be after the first date'}});
  if (!res.ok) return false;
  const v = res.values, today = AK.today();
  const rule = anchor({type: v.type, amount: r2(v.amount), cat: v.cat || (cats(v.type)[0] || {}).id || '', note: (v.note || '').trim(), freq: v.freq, start: v.start, end: v.end || '', active: !!v.active});
  if (!old) {
    rule.next = rule.start;
    db.add('recs', rule);
    const n = runRecurring();
    AK.toast(n ? 'Saved. ' + n + (n === 1 ? ' entry' : ' entries') + ' added up to today.' : 'Saved. First one on ' + AK.fmt.date(rule.start) + '.', 'ok');
    return;
  }
  const last = lastGenerated(old.id), resumed = !old.active && rule.active;
  let from = last ? addDays(last, 1) : rule.start;
  if (resumed && today > from) from = today;
  rule.next = firstOnOrAfter(rule, from < rule.start ? rule.start : from);
  db.update('recs', old.id, rule);
  const n = runRecurring();
  AK.toast('Saved' + (n ? '. ' + n + ' added up to today.' : '. Changes apply from the next one.'), 'ok');
}
async function deleteRule(id) {
  const r = S().recs.find(x => x.id === id); if (!r) return;
  if (!await AK.confirm('Stop \u201c' + (r.note || cat(r.cat).name) + '\u201d from repeating?', {text: 'Entries already added stay where they are.', ok: 'Delete', danger: true})) return;
  db.remove('recs', id);
  AK.toast('Repeating entry deleted', {undo: () => db.undo()});
}

/* ===== 17. BUDGET SHEET ===== */
function openBudget(id) {
  const c = S().cats.find(x => x.id === id); if (!c) return;
  const f = tpl('form-budget'), form = $('form', f);
  $$('[data-slot=sym]', f).forEach(e => e.textContent = sym());
  setText(f, 'hint', money(spentIn(ui.month, id)) + ' spent on ' + c.name + ' in ' + monthOnly(ui.month) + '. A budget is a limit for each month.');
  AK.modal({title: 'Budget for ' + c.name, body: f, focus: 'input[name=budget]',
    actions: (c.budget > 0 ? [{label: 'Remove', class: 'ghost', onClick: () => {db.update('cats', id, {budget: null}); AK.toast('Budget removed', {undo: () => db.undo()});}}] : [])
      .concat([{label: 'Save', primary: true, onClick: () => {
        const r = AK.validate(form, {budget: {required: true, test: v => (v > 0 && v < 1e9) || 'Enter an amount above zero'}});
        if (!r.ok) return false;
        db.update('cats', id, {budget: r2(r.values.budget)}); AK.toast('Budget saved', 'ok');
      }}])});
  if (c.budget > 0) AK.fill(form, {budget: c.budget});
}

/* ===== 18. CSV + BACKUP ===== */
const CSV_COLS = [{key: 'date', label: 'Date'}, {key: 'type', label: 'Type', value: t => t.type === 'income' ? 'Income' : 'Expense'},
  {key: 'cat', label: 'Category', value: t => cat(t.cat).name}, {key: 'amount', label: 'Amount', value: t => r2(t.amount).toFixed(2)},
  {key: 'note', label: 'Note'}, {key: 'rec', label: 'Repeating', value: t => {const r = t.rec && S().recs.find(x => x.id === t.rec); return r ? FREQ[r.freq] : '';}},
  {key: 'photo', label: 'Receipt', value: t => t.photo ? 'yes' : ''}];
function exportCSV(scope) {
  const list = scope === 'all' ? S().txns.slice().sort(sortDesc) : filtered();
  if (!list.length) {AK.toast('Nothing to export yet.', 'warn'); return;}
  AK.csv.export(list, BUSINESS.replace(/[^\w-]+/g, '-').toLowerCase() + '-money-' + (scope === 'all' ? 'all' : ui.scope === 'month' ? ui.month : 'filtered') + '.csv', CSV_COLS);
  AK.toast('Saved ' + list.length + ' entries as CSV', 'ok');
}
/* dates: 2026-10-09, 10/9/2026, 9/10/26 (day first when the first number is over 12), 9.10.2026, Oct 9, 2026 */
function parseDate(v) {
  v = String(v || '').trim(); let m;
  if ((m = /^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})/.exec(v))) return okDate(+m[1], +m[2], +m[3]);
  if ((m = /^(\d{1,2})[/.-](\d{1,2})[/.-](\d{2,4})$/.exec(v))) {
    let a = +m[1], b = +m[2], y = +m[3]; if (y < 100) y += 2000;
    const dotted = /\./.test(v);
    return dotted || a > 12 ? okDate(y, b, a) : okDate(y, a, b);
  }
  const d = new Date(v);
  return isNaN(d) || !/[a-z]/i.test(v) ? '' : iso(d);
}
function okDate(y, mo, d) {const x = new Date(y, mo - 1, d); return x.getFullYear() === y && x.getMonth() === mo - 1 && x.getDate() === d ? iso(x) : '';}
function csvRows(rows) {
  if (!rows.length) return {ok: [], skipped: 0, dupes: 0, newCats: []};
  const keys = Object.keys(rows[0]), find = re => keys.find(k => re.test(k.trim()));
  const K = {date: find(/^(date|day|posted|transaction date|when)/i), amount: find(/^(amount|value|sum|total)/i), type: find(/^(type|kind|direction|in\/out)/i),
    cat: find(/categ/i), note: find(/^(note|notes|memo|description|details|payee|name|what)/i), debit: find(/debit|withdraw|money out|paid out|spent/i), credit: find(/credit|deposit|money in|paid in|received/i)};
  const s = S(), have = new Set(s.txns.map(t => [t.date, t.type, r2(t.amount).toFixed(2), cat(t.cat).name.toLowerCase(), (t.note || '').trim()].join('|')));
  const newCats = [], out = []; let skipped = 0, dupes = 0;
  rows.forEach(row => {
    const date = parseDate(K.date ? row[K.date] : '');
    let amt = null, type = null;
    if (K.amount && String(row[K.amount]).trim() !== '') amt = AK.parseMoney(row[K.amount]);
    else if (K.debit && String(row[K.debit]).trim() !== '') {amt = Math.abs(AK.parseMoney(row[K.debit])); type = 'expense';}
    else if (K.credit && String(row[K.credit]).trim() !== '') {amt = Math.abs(AK.parseMoney(row[K.credit])); type = 'income';}
    if (!date || amt == null || !isFinite(amt) || amt === 0) {skipped++; return;}
    const tt = K.type ? String(row[K.type]).toLowerCase() : '';
    if (!type) type = /inc|credit|deposit|sale|^in$|money in|received/.test(tt) ? 'income' : /exp|debit|withdraw|^out$|money out|spent|paid|cost/.test(tt) ? 'expense' : amt < 0 ? 'expense' : K.type ? 'expense' : 'income';
    const amount = r2(Math.abs(amt)), cname = (K.cat ? String(row[K.cat]).trim() : '') || (type === 'income' ? 'Other income' : 'Other');
    const note = K.note ? String(row[K.note]).trim().slice(0, 140) : '';
    const key = [date, type, amount.toFixed(2), cname.toLowerCase(), note].join('|');
    if (have.has(key)) {dupes++; return;}
    have.add(key);
    let c = s.cats.find(x => x.kind === type && x.name.toLowerCase() === cname.toLowerCase()) || newCats.find(x => x.kind === type && x.name.toLowerCase() === cname.toLowerCase());
    if (!c) {c = cat0(AK.id(), cname.slice(0, 40), 'tag', Object.keys(COLORS)[(s.cats.length + newCats.length) % 12], type); newCats.push(c);}
    out.push({id: AK.id(), type, amount, cat: c.id, date, note, at: Date.now() + out.length});
  });
  return {ok: out, skipped, dupes, newCats};
}
function importCSV(file) {
  return file.text().then(text => {
    const res = csvRows(AK.csv.parse(text));
    if (!res.ok.length) {AK.toast(res.dupes ? 'All ' + res.dupes + ' rows are already here.' : 'No entries found. The file needs Date and Amount columns.', 'warn'); return;}
    const bits = [res.ok.length + ' new ' + (res.ok.length === 1 ? 'entry' : 'entries')];
    if (res.dupes) bits.push(res.dupes + ' already here (skipped)');
    if (res.skipped) bits.push(res.skipped + ' without a date or amount (skipped)');
    if (res.newCats.length) bits.push(res.newCats.length + ' new ' + (res.newCats.length === 1 ? 'category' : 'categories') + ': ' + res.newCats.map(c => c.name).join(', '));
    AK.modal({title: 'Import ' + file.name + '?', body: AK.html`<ul class="import-sum">${bits.map(b => AK.html`<li>${b}</li>`)}</ul>`,
      actions: [{label: 'Cancel'}, {label: 'Import ' + res.ok.length, primary: true, onClick: () => {
        db.update(st => {st.cats = st.cats.concat(res.newCats); st.txns = st.txns.concat(res.ok);});
        AK.toast('Imported ' + res.ok.length + ' entries', {type: 'ok', undo: () => db.undo()});
      }}]});
  });
}
function backup() {
  db.set('lastBackup', AK.today());
  db.export(BUSINESS.replace(/[^\w-]+/g, '-').toLowerCase() + '-money-backup-' + AK.today() + '.json');
  AK.toast('Backup saved to your downloads', 'ok');
}
function normalize(d) {
  const base = FRESH(), arr = x => Array.isArray(x) ? x : [];
  const out = Object.assign(base, d && typeof d === 'object' ? d : {});
  out.cats = arr(out.cats).filter(c => c && c.id && c.name);
  out.txns = arr(out.txns).filter(t => t && t.id && parseISO(t.date) && isFinite(+t.amount)).map(t => Object.assign(t, {amount: r2(Math.abs(+t.amount)), type: t.type === 'income' ? 'income' : 'expense'}));
  out.recs = arr(out.recs).filter(r => r && r.id && parseISO(r.start) && FREQ[r.freq]);
  if (!out.cats.length) out.cats = base.cats;
  return out;
}
function restoreBackup(file) {
  return file.text().then(text => {
    let j; try {j = JSON.parse(text);} catch (e) {AK.toast('That file is not a backup.', 'bad'); return;}
    const data = j && j.data && j.store ? j.data : j;
    if (!data || !Array.isArray(data.txns) || !Array.isArray(data.cats)) {AK.toast('That file is not a backup from this app.', 'bad'); return;}
    const d = normalize(data);
    AK.modal({title: 'Restore this backup?', body: AK.html`<p class="muted">It has ${d.txns.length} entries, ${d.recs.length} repeating entries and ${d.cats.length} categories${j.saved ? AK.html`, saved ${AK.fmt.date(j.saved)}` : ''}. Everything on this phone now is replaced.</p>`,
      actions: [{label: 'Cancel'}, {label: 'Replace with backup', danger: true, onClick: () => {db.update(() => d); runRecurring(); AK.toast('Backup restored', {type: 'ok', undo: () => db.undo()});}}]});
  });
}

/* ===== 19. SAMPLE DATA (made up, clearly labelled, removable) ===== */
function loadSample() {
  if (hasSample()) return;
  let seed = 7;
  const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647, pick = (a, b, step) => Math.round((a + rnd() * (b - a)) / step) * step;
  const today = AK.today(), cur = thisMonth(), txns = [], has = id => S().cats.some(c => c.id === id);
  const cid = (id, kind) => has(id) ? id : (cats(kind).find(c => /other/i.test(c.name)) || cats(kind)[0] || {}).id;
  const add = (m, day, type, catId, amount, note) => {
    const d = iso(new Date(+m.slice(0, 4), +m.slice(5, 7) - 1, Math.min(day, dim(+m.slice(0, 4), +m.slice(5, 7) - 1))));
    if (d > today) return;
    txns.push({id: AK.id(), type, amount: r2(amount), cat: cid(catId, type), date: d, note: 'Sample ' + note, at: Date.now() + txns.length, sample: true});
  };
  for (let k = 5; k >= 0; k--) {
    const m = monthAdd(cur, -k);
    [2, 5, 8, 12, 15, 19, 22, 26, 29].forEach(d => add(m, d, 'income', 'sales', pick(450, 1700, 5), 'sale'));
    [9, 23].forEach(d => add(m, d, 'income', 'services', pick(150, 600, 5), 'service job'));
    [3, 10, 17, 24].forEach(d => add(m, d, 'expense', 'parts', pick(120, 650, 0.01), 'parts order'));
    [4, 11, 18, 25].forEach(d => add(m, d, 'expense', 'fuel', pick(45, 85, 0.01), 'fuel'));
    add(m, 14, 'expense', 'utilities', pick(180, 260, 0.01), 'utility bill');
    [6, 20].forEach(d => add(m, d, 'expense', 'meals', pick(18, 45, 0.01), 'team lunch'));
    add(m, 16, 'expense', 'marketing', pick(60, 220, 5), 'flyers');
    add(m, 7, 'expense', 'tools', k === 0 ? 189.5 : pick(40, 160, 0.01), 'tool');
    add(m, 27, 'expense', 'fees', pick(12, 30, 0.01), 'card fees');
  }
  const start = iso(new Date(+cur.slice(0, 4), +cur.slice(5, 7) - 6, 1)), d0 = parseISO(start);
  const rule = (catId, type, amount, note, freq, st) => anchor({id: AK.id(), type, amount, cat: cid(catId, type), note: 'Sample ' + note, freq, start: st, end: '', next: st, active: true, sample: true});
  const third = addDays(start, (5 - d0.getDay() + 7) % 7);
  const recs = [rule('rent', 'expense', 1500, 'rent', 'monthly', start), rule('phone', 'expense', 89.99, 'phone bill', 'monthly', addDays(start, 11)),
    rule('payroll', 'expense', 1850, 'payroll', 'biweekly', third), rule('insurance', 'expense', 145, 'insurance', 'monthly', addDays(start, 19))];
  const budgets = {parts: 1800, fuel: 300, meals: 120, tools: 150, marketing: 250, utilities: 260};
  db.update(st => {
    st.txns = st.txns.concat(txns); st.recs = st.recs.concat(recs); st.sampleBudgets = [];
    st.cats.forEach(c => {if (budgets[c.id] && !(c.budget > 0)) {c.budget = budgets[c.id]; st.sampleBudgets.push(c.id);}});
  });
  runRecurring();
  AK.toast('Sample data loaded. Clear it any time.', 'ok');
}
function clearSample() {
  db.update(st => {
    st.txns = st.txns.filter(t => !t.sample); st.recs = st.recs.filter(r => !r.sample);
    st.cats.forEach(c => {if ((st.sampleBudgets || []).indexOf(c.id) > -1) c.budget = null;});
    st.sampleBudgets = [];
  });
  AK.toast('Sample data cleared', {undo: () => db.undo()});
}

/* ===== 20. ACTIONS (data-act="name" buttons anywhere) + BOOT ===== */
AK.act({
  'new-txn': () => openTxn(null),
  'edit-txn': el => openTxn(el.dataset.id),
  'new-rec': () => openRule(null),
  'edit-rec': el => openRule(el.dataset.id),
  'new-cat': () => openCat(null),
  'edit-cat': el => openCat(el.dataset.id),
  'edit-budget': el => openBudget(el.dataset.id),
  'go-budgets': () => app.go('/budgets'),
  'manage-cats': () => app.go('/data/categories'),
  go: el => app.go(el.dataset.to),
  'month-prev': () => {ui.month = monthAdd(ui.month, -1); ui.limit = 120; app.refresh();},
  'month-next': () => {ui.month = monthAdd(ui.month, 1); ui.limit = 120; app.refresh();},
  'month-now': () => {ui.month = thisMonth(); app.refresh();},
  'show-type': el => {Object.assign(ui, {type: el.dataset.type, cat: '', q: '', photo: false, scope: 'month'}); app.go('/activity');},
  'show-cat': el => {if (!el.dataset.id) return; Object.assign(ui, {type: 'all', cat: el.dataset.id, q: '', photo: false, scope: 'month'}); app.go('/activity');},
  'clear-filters': () => {Object.assign(ui, {type: 'all', cat: '', q: '', photo: false}); app.refresh();},
  'more-rows': () => {ui.limit += 120; app.refresh();},
  'export-csv': el => exportCSV(el.dataset.scope),
  backup: () => backup(),
  sample: () => loadSample(),
  'clear-sample': async () => {if (await AK.confirm('Clear the sample data?', {text: 'Only the made-up entries go. Anything you added stays.', ok: 'Clear it'})) clearSample();},
  erase: async () => {
    if (!await AK.confirm('Erase everything?', {text: 'All entries, repeating entries and budgets on this phone are deleted. Save a backup first if you might want them.', ok: 'Erase', danger: true})) return;
    db.reset(FRESH()); AK.toast('Everything erased', {undo: () => db.undo()});
  },
  'print-report': () => AK.print(BUSINESS + ' ' + monthName(ui.month) + ' report', $('.report')),
  'copy-report': () => AK.copy(reportText(), {done: 'Summary copied'})
});

/* BOOT: the app shell (tabs at the bottom on phones, a sidebar on computers). Created last, because it draws the first
   screen straight away and every function and constant above must exist by then. */
AK.currency = CUR();
runRecurring();
const app = AK.app({
  title: BUSINESS, icon: 'dollar',
  fab: {icon: 'plus', label: 'Add entry', act: 'new-txn'},
  tabs: [
    {id: 'home', label: 'Home', icon: 'home', render: renderHome},
    {id: 'activity', label: 'Activity', icon: 'list', render: renderActivity},
    {id: 'budgets', label: 'Budgets', icon: 'chart', render: renderBudgets},
    {id: 'repeat', label: 'Repeating', icon: 'refresh', render: renderRepeat, fab: {icon: 'plus', label: 'New repeating', act: 'new-rec'}},
    {id: 'data', label: 'Settings', icon: 'settings', render: renderSettings, fab: false} /* id 'data' so it never clashes with another app's remembered tab on the same site */
  ]
});
db.subscribe(() => {AK.currency = CUR(); app.refresh();});
document.addEventListener('visibilitychange', () => {if (!document.hidden) runRecurring();});
AK.pwa({name: BUSINESS + ' Money', short_name: 'Money'});
