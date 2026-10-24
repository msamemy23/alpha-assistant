/* Numbers dashboard | app.js | blueprint "dashboard-kpi", built on appkit (window.AK, see APPKIT.md)
   The markup of every screen lives in index.html as <template id="view-..."> blocks; this file copies a template into the
   page and fills the parts marked data-text="..." (words, numbers) and data-slot="..." (lists, charts, show/hide).
   Sections:
     1. Saved data and settings          2. Small helpers                 3. Dates and date ranges
     4. The numbers (KPIs, buckets)      5. Reading tables (paste / CSV)  6. Overview screen
     7. Filters bar (dates, categories)  8. Entries screen                9. Add data screen + column check
    10. Entry sheet (add / edit)        11. Saved views                  12. Settings + backup
    13. Report                          14. Sample data                  15. Actions (buttons)        16. Start */
(() => {
'use strict';

/* ==== 1. Saved data and settings ==== */
const SETTINGS = {name: 'Sales', unit: 'money', currency: 'USD', higher: true, weekStart: 0};
const DATA = {version: 1, rows: [], imports: [], views: [], settings: SETTINGS};
// what the owner is looking at: date range, chosen categories, chart style
const LOOK = {range: '30d', from: '', to: '', cats: [], chart: 'area', compare: true, split: 'cat'};
// rows: {id, date: 'YYYY-MM-DD', amount, cat, label, imp (import id), sample}
const db = AK.store('dashboard-kpi', DATA);
const ui = AK.store('dashboard-kpi-ui', LOOK);
const BUSINESS = ((document.querySelector('meta[name="application-name"]') || {}).content || '').trim() || 'Numbers';
const settings = () => Object.assign({}, SETTINGS, db.get('settings'));
const look = () => Object.assign({}, LOOK, ui.get(), {cats: (ui.get('cats') || []).slice()});
const UNCAT = 'Uncategorized';
let app = null, pasteDraft = '', entryQuery = '', entrySort = {key: 'date', dir: 'desc'};

/* ==== 2. Small helpers ==== */
const $ = (sel, root) => (root || document).querySelector(sel);
const $$ = (sel, root) => Array.from((root || document).querySelectorAll(sel));
const pad = n => String(n).padStart(2, '0');
const loc = () => AK.locale || navigator.language || 'en-US';
const slot = (root, key) => $('[data-slot="' + key + '"]', root);
const show = (el, on) => { if (el) el.hidden = !on; };
const clip = (v, n) => String(v == null ? '' : v).replace(/\s+/g, ' ').trim().slice(0, n);
const catName = c => c || UNCAT;
/** a copy of <template id=...> with its <i data-icon> placeholders turned into icons */
function tpl(id) { const f = document.getElementById(id).content.cloneNode(true); hydrate(f); return f; }
function hydrate(root) { $$('i[data-icon]', root).forEach(i => i.replaceWith(icon(i.dataset.icon, i.getAttribute('class')))); }
function icon(name, cls) { const t = document.createElement('template'); t.innerHTML = String(AK.icon(name, cls || '')); return t.content.firstChild; }
/** sets the text of every [data-text=key] inside root */
function put(root, key, value) { $$('[data-text="' + key + '"]', root).forEach(el => { el.textContent = value == null ? '' : String(value); }); }
/** one repeated piece (first element of a template), filled by fn */
function piece(id, fn) { const el = tpl(id).firstElementChild; fn(el); return el; }
/** money rounded to cents (1.005 -> 1.01, -2.345 -> -2.35) */
function round2(n) { const r = Math.round(Math.abs(+n || 0) * 100 + 1e-7) / 100; return n < 0 ? -r : r; }
const centsSum = list => list.reduce((t, r) => t + Math.round(r.amount * 100), 0) / 100;
const isMoney = () => settings().unit !== 'number';
function curDigits(cur) { try { return new Intl.NumberFormat('en', {style: 'currency', currency: cur}).resolvedOptions().maximumFractionDigits; } catch (e) { return 2; } }
/** full value: $1,234.56 or 1,234.5 */
function full(n) { const s = settings(); return isMoney() ? AK.fmt.money(n, s.currency, curDigits(s.currency)) : AK.fmt.number(n); }
/** short value for big cards: $1,234.56, $12,345, $1.2M */
function short(n) {
  const a = Math.abs(n), s = settings();
  if (a >= 1e6) return compact(n);
  if (!isMoney()) return AK.fmt.number(n, a >= 1000 ? 0 : undefined);
  return AK.fmt.money(n, s.currency, a >= 10000 ? 0 : curDigits(s.currency));
}
function compact(n) {
  try { return new Intl.NumberFormat(loc(), isMoney() ? {style: 'currency', currency: settings().currency, notation: 'compact', maximumFractionDigits: 1} : {notation: 'compact', maximumFractionDigits: 1}).format(n); }
  catch (e) { return AK.fmt.compact(n); }
}
function signedPct(x) {
  if (x == null) return '';
  if (x === Infinity) return 'New';
  const v = Math.abs(x);
  return (x > 0.0005 ? '+' : x < -0.0005 ? '−' : '') + AK.fmt.percent(v, v < 0.1 && v > 0 ? 1 : 0);
}
const plural = (n, one, many) => AK.fmt.number(n) + ' ' + (n === 1 ? one : many || one + 's');
function slugify(s) { return String(s || 'numbers').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'numbers'; }

/* ==== 3. Dates and date ranges (all dates are local 'YYYY-MM-DD' strings) ==== */
const iso = d => d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
const isIso = s => /^\d{4}-\d{2}-\d{2}$/.test(String(s || ''));
const toDate = s => { const p = s.split('-'); return new Date(+p[0], +p[1] - 1, +p[2]); };
const addDays = (s, n) => { const d = toDate(s); d.setDate(d.getDate() + n); return iso(d); };
const dayCount = (a, b) => Math.round((toDate(b) - toDate(a)) / 864e5) + 1;
const firstOfMonth = (s, plus) => { const d = toDate(s); return iso(new Date(d.getFullYear(), d.getMonth() + (plus || 0), 1)); };
const lastOfMonth = s => { const d = toDate(s); return iso(new Date(d.getFullYear(), d.getMonth() + 1, 0)); };
const minDate = (a, b) => a < b ? a : b;
function sameDayYearsAgo(s, n) { const y = +s.slice(0, 4) - n, m = +s.slice(5, 7), d = Math.min(+s.slice(8, 10), new Date(y, m, 0).getDate()); return y + '-' + pad(m) + '-' + pad(d); }
const RANGE_NAMES = {'7d': 'Last 7 days', '30d': 'Last 30 days', '90d': 'Last 90 days', mtd: 'This month', lm: 'Last month', '12m': 'Last 12 months', ytd: 'This year', all: 'All dates', custom: 'Picked dates'};
const PREV_NAMES = {'7d': 'the 7 days before', '30d': 'the 30 days before', '90d': 'the 90 days before', mtd: 'the same days last month', lm: 'the month before', '12m': 'the 12 months before', ytd: 'the same days last year'};
function dataSpan(rows) {
  if (!rows.length) return null;
  let a = rows[0].date, b = a;
  rows.forEach(r => { if (r.date < a) a = r.date; if (r.date > b) b = r.date; });
  return {from: a, to: b};
}
/** the chosen dates and the period before them (for "change") */
function rangeFor(u, rows, today) {
  today = today || AK.today();
  let key = RANGE_NAMES[u.range] ? u.range : '30d', from, to, pf = null, pt = null;
  if (key === 'custom' && !(isIso(u.from) && isIso(u.to))) key = '30d';
  const n = {'7d': 7, '30d': 30, '90d': 90}[key];
  if (n) { to = today; from = addDays(today, 1 - n); pt = addDays(from, -1); pf = addDays(from, -n); }
  else if (key === 'mtd') { from = firstOfMonth(today); to = today; pf = firstOfMonth(today, -1); pt = minDate(addDays(pf, dayCount(from, to) - 1), lastOfMonth(pf)); }
  else if (key === 'lm') { from = firstOfMonth(today, -1); to = lastOfMonth(from); pf = firstOfMonth(today, -2); pt = lastOfMonth(pf); }
  else if (key === '12m') { from = firstOfMonth(today, -11); to = today; pf = firstOfMonth(today, -23); pt = addDays(from, -1); }
  else if (key === 'ytd') { from = today.slice(0, 4) + '-01-01'; to = today; pf = (+today.slice(0, 4) - 1) + '-01-01'; pt = sameDayYearsAgo(today, 1); }
  else if (key === 'all') { const sp = dataSpan(rows) || {from: today, to: today}; from = sp.from; to = sp.to; }
  else { from = u.from; to = u.to; if (from > to) { const t = from; from = to; to = t; } const len = dayCount(from, to); pt = addDays(from, -1); pf = addDays(from, -len); }
  const prevName = PREV_NAMES[key] || (pf ? 'the ' + plural(dayCount(from, to), 'day') + ' before' : '');
  return {key, name: RANGE_NAMES[key], from, to, prevFrom: pf, prevTo: pt, prevName, days: dayCount(from, to)};
}
/** "Sep 10 – Oct 9" (this year) or "Dec 3, 2025 – Jan 9, 2026" */
function spanText(a, b) {
  if (a === b) return AK.fmt.date(a);
  const o = {month: 'short', day: 'numeric'}, oy = {month: 'short', day: 'numeric', year: 'numeric'};
  const sameY = a.slice(0, 4) === b.slice(0, 4), thisY = b.slice(0, 4) === AK.today().slice(0, 4);
  return toDate(a).toLocaleDateString(loc(), sameY ? o : oy) + ' – ' + toDate(b).toLocaleDateString(loc(), sameY && thisY ? o : oy);
}

/* ==== 4. The numbers: filter, totals, change, categories, buckets for charts ==== */
const grainFor = days => days <= 35 ? 'day' : days <= 200 ? 'week' : days <= 1100 ? 'month' : 'year';
function weekStartOf(s, ws) { const d = toDate(s); d.setDate(d.getDate() - ((d.getDay() - ws + 7) % 7)); return iso(d); }
function bucketOf(s, g, ws) { return g === 'day' ? s : g === 'week' ? weekStartOf(s, ws) : g === 'month' ? s.slice(0, 7) : s.slice(0, 4); }
function nextBucket(k, g) { return g === 'day' ? addDays(k, 1) : g === 'week' ? addDays(k, 7) : g === 'month' ? firstOfMonth(k + '-01', 1).slice(0, 7) : String(+k + 1); }
function bucketLabel(k, g, multiYear) {
  if (g === 'year') return k;
  if (g === 'month') return toDate(k + '-01').toLocaleDateString(loc(), multiYear ? {month: 'short', year: '2-digit'} : {month: 'short'});
  return AK.fmt.date(k, 'day');
}
/** totals per day / week / month / year from..to, with empty buckets as 0 */
function series(rows, from, to, g, ws) {
  const keys = [], last = bucketOf(to, g, ws);
  for (let k = bucketOf(from, g, ws), i = 0; i < 2000; i++) { keys.push(k); if (k >= last) break; k = nextBucket(k, g); }
  const at = new Map(keys.map((k, i) => [k, i])), cents = keys.map(() => 0), counts = keys.map(() => 0);
  rows.forEach(r => { const i = at.get(bucketOf(r.date, g, ws)); if (i != null) { cents[i] += Math.round(r.amount * 100); counts[i]++; } });
  const multi = from.slice(0, 4) !== to.slice(0, 4);
  return {keys, labels: keys.map(k => bucketLabel(k, g, multi)), values: cents.map(c => c / 100), counts};
}
function byCategory(list) {
  const m = new Map();
  list.forEach(r => { const k = r.cat || ''; const e = m.get(k) || {cat: k, cents: 0, count: 0}; e.cents += Math.round(r.amount * 100); e.count++; m.set(k, e); });
  return Array.from(m.values()).map(e => ({cat: e.cat, total: e.cents / 100, count: e.count})).sort((a, b) => b.total - a.total || b.count - a.count);
}
/** everything the Overview, Entries and the report show, for the chosen dates and categories */
function summarize(rows, u, s, today) {
  const r = rangeFor(u, rows, today), ws = +s.weekStart || 0;
  const dated = rows.filter(x => x.date >= r.from && x.date <= r.to);
  const pick = u.cats && u.cats.length ? new Set(u.cats) : null;
  const list = pick ? dated.filter(x => pick.has(x.cat || '')) : dated;
  const prevList = r.prevFrom ? rows.filter(x => x.date >= r.prevFrom && x.date <= r.prevTo && (!pick || pick.has(x.cat || ''))) : null;
  const total = centsSum(list), count = list.length, prevTotal = prevList ? centsSum(prevList) : null;
  const change = prevList == null ? null : prevTotal === 0 ? (total === 0 ? 0 : Infinity) : (total - prevTotal) / Math.abs(prevTotal);
  const g = grainFor(r.days), cur = series(list, r.from, r.to, g, ws);
  let prev = null;
  if (prevList) { prev = series(prevList, r.prevFrom, r.prevTo, g, ws); prev.values = cur.values.map((v, i) => prev.values[i] || 0); }
  const weekdays = [0, 0, 0, 0, 0, 0, 0], dow = new Map();
  list.forEach(x => { let d = dow.get(x.date); if (d == null) { d = toDate(x.date).getDay(); dow.set(x.date, d); } weekdays[d] += Math.round(x.amount * 100); });
  return {
    range: r, rows: list, dated, total, count, avg: count ? round2(total / count) : 0, perDay: round2(total / r.days),
    prevTotal, prevCount: prevList ? prevList.length : null, change, grain: g, cur, prev,
    byCat: byCategory(list), catCounts: byCategory(dated), weekdays: weekdays.map(c => c / 100),
    largest: list.reduce((m, x) => !m || Math.abs(x.amount) > Math.abs(m.amount) ? x : m, null)
  };
}
/** summarize(), remembered until the entries or the chosen dates / categories change (switching chart style is instant) */
let memo = {key: '', rows: null, st: null};
function summary(rows, u, s) {
  const key = [u.range, u.from, u.to, (u.cats || []).join('\u0001'), s.weekStart, AK.today()].join('|');
  if (memo.rows !== rows || memo.key !== key) memo = {key, rows, st: summarize(rows, u, s)};
  return memo.st;
}
/** one sentence for the top of the Overview */
function insight(st, s) {
  const parts = [short(st.total) + ' from ' + plural(st.count, 'entry', 'entries')];
  if (st.change == null) return parts[0] + ', ' + spanText(st.range.from, st.range.to) + '.';
  if (st.change === Infinity) return parts[0] + '. Nothing in ' + st.range.prevName + ' to compare with.';
  if (Math.abs(st.change) < 0.005) return parts[0] + ', about the same as ' + st.range.prevName + '.';
  return parts[0] + ', ' + (st.change > 0 ? 'up ' : 'down ') + AK.fmt.percent(Math.abs(st.change)) + ' on ' + st.range.prevName + '.';
}

/* ==== 5. Reading tables: paste or CSV -> columns -> rows ==== */
const MONTHS = ['january', 'february', 'march', 'april', 'may', 'june', 'july', 'august', 'september', 'october', 'november', 'december'];
const CURRENCY = /^(?:usd|eur|gbp|cad|aud|nzd|mxn|inr|jpy|chf|sek|nok|dkk|brl|zar|php|sgd|aed|ngn|kes|hkd|cny|pln|us\$|c\$|a\$|r\$|[$€£¥₹₩₽₺₪₫฿¢]|kr|zł)/i;
const HINT = {
  date: /date|day|time|when|created|posted|issued|period|fecha|datum/i,
  amount: /amount|total|sales|revenue|income|price|value|sum|net|gross|paid|cost|expense|spend|charge|fee|importe|monto|betrag|valor|montant/i,
  qty: /qty|quantity|units|count|number of/i,
  cat: /categor|type|group|class|kind|dept|department|service|product|item|account|channel|source|method|payment|tag|status|location|staff|tech|vendor|supplier/i,
  label: /desc|name|customer|client|memo|note|detail|title|payee|merchant|reference|job|vehicle|comment/i,
  id: /(^|\W)(id|no|num|number|ref|zip|postal|phone|tel|year|code)($|\W)|#/i
};
const LOCALE_ORDER = /^en(-US|-CA|-PH)?$/i.test(navigator.language || 'en-US') ? 'mdy' : 'dmy';
function monthNo(w) { w = String(w).toLowerCase().replace('.', ''); if (w.length < 3) return 0; const i = MONTHS.findIndex(m => m.indexOf(w) === 0); return i + 1; }
function ymd(y, m, d) {
  y = +y; m = +m; d = +d;
  if (y < 100) y += y < 70 ? 2000 : 1900;
  if (y < 1900 || y > 2100 || m < 1 || m > 12 || d < 1 || d > new Date(y, m, 0).getDate()) return null;
  return y + '-' + pad(m) + '-' + pad(d);
}
/** a date written almost any way -> 'YYYY-MM-DD' (order: 'mdy' or 'dmy' for 03/04/2026; loose: allow 20261003 and Excel day numbers) */
function parseDate(v, order, loose) {
  const s = String(v == null ? '' : v).trim();
  let m;
  if (!s) return null;
  if ((m = s.match(/^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})(?:$|[T\s,])/))) return ymd(m[1], m[2], m[3]);
  if ((m = s.match(/^(\d{1,2})[-/.](\d{1,2})[-/.](\d{4}|\d{2})(?:$|[T\s,])/))) return order === 'dmy' ? ymd(m[3], m[2], m[1]) : ymd(m[3], m[1], m[2]);
  if ((m = s.match(/^(?:[a-z]+\.?,?\s+)?([a-z]{3,9})\.?[\s-]+(\d{1,2})(?:st|nd|rd|th)?,?[\s-]+(\d{4}|\d{2})(?:$|[T\s,])/i)) && monthNo(m[1])) return ymd(m[3], monthNo(m[1]), m[2]);
  if ((m = s.match(/^(?:[a-z]+\.?,?\s+)?(\d{1,2})(?:st|nd|rd|th)?[\s-]+([a-z]{3,9})\.?,?[\s-]+(\d{4}|\d{2})(?:$|[T\s,])/i)) && monthNo(m[2])) return ymd(m[3], monthNo(m[2]), m[1]);
  if (loose && (m = s.match(/^(\d{4})(\d{2})(\d{2})$/))) return ymd(m[1], m[2], m[3]);
  if (loose && /^\d{5}(\.\d+)?$/.test(s)) { const n = Math.floor(+s); if (n > 20000 && n < 80000) return iso(new Date(1899, 11, 30 + n)); }
  return null;
}
/** which way round 03/04/2026 is written in this column: 'mdy', 'dmy' or null when every value could be either */
function dateOrderOf(values) {
  let md = 0, dm = 0, dots = 0, n = 0;
  values.forEach(v => { const m = String(v).trim().match(/^(\d{1,2})([-/.])(\d{1,2})[-/.]\d{2,4}/); if (m) { n++; if (m[2] === '.') dots++; if (+m[1] > 12) dm++; if (+m[3] > 12) md++; } });
  // 01.10.2026 (dots) is written day first almost everywhere
  return dm > md ? 'dmy' : md > dm ? 'mdy' : n && dots > n / 2 ? 'dmy' : null;
}
/** true for "1,250.00", "$45", "(12.50)", "-3", "12,5", "1 234,56 kr", "USD 900" */
function looksNumber(v) {
  let t = String(v == null ? '' : v).trim();
  if (!t || t.length > 32 || !/\d/.test(t)) return false;
  t = t.replace(/[\s ]/g, '').replace(/^\((.*)\)$/, '$1').replace(/^[+-]/, '').replace(/-$/, '');
  t = t.replace(CURRENCY, '').replace(/^[+-]/, '').replace(/(?:usd|eur|gbp|cad|aud|[$€£¥₹%]|kr|zł)$/i, '');
  return /^\d+(?:[,.'’]\d{3})*(?:[.,]\d+)?$/.test(t) || /^\d*[.,]\d+$/.test(t);
}
/** '.' or ',' as the decimal mark of a column ("12,50" and "1.234,56" vote for a comma) */
function decimalOf(values, fallback) {
  let eu = 0, us = 0;
  values.forEach(v => {
    const t = String(v).replace(/[^\d.,]/g, '');
    if (/,\d{1,2}$/.test(t) || /\.\d{3},/.test(t)) eu++;
    else if (/\.\d{1,2}$/.test(t) || /,\d{3}\./.test(t)) us++;
  });
  return eu > us ? ',' : us > eu ? '.' : (fallback || '.');
}
/** "1,250.00" -> 1250, "(45.00)" -> -45, "12,50" with dec ',' -> 12.5; NaN when it is not a number */
function toNumber(v, dec) {
  if (!looksNumber(v)) return NaN;
  const raw = String(v).trim(), neg = /^\(.*\)$/.test(raw.replace(/\s/g, '')) || /-/.test(raw);
  let t = raw.replace(/[^\d.,]/g, '');
  t = dec === ',' ? t.replace(/\./g, '').replace(',', '.') : t.replace(/,/g, '');
  if ((t.match(/\./g) || []).length > 1) t = t.replace(/\./g, '');
  const n = parseFloat(t);
  return isFinite(n) ? (neg ? -n : n) : NaN;
}
/** split CSV / pasted text into cells (quotes, "" escapes, line breaks inside quotes, \r\n) */
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
  // each row remembers its line in the file (row.line), so "skipped rows" match what the owner sees in a spreadsheet
  return rows.filter(r => r.some(x => x.trim() !== '')).map(r => { const o = r.map(x => x.trim()); o.line = r.line; return o; });
}
function guessDelimiter(text) {
  const sample = text.split(/\r\n|\n|\r/).filter(l => l.trim()).slice(0, 40).join('\n');
  let best = null;
  ['\t', ';', ',', '|'].forEach(d => {
    const counts = parseCells(sample, d).map(r => r.length);
    if (!counts.length) return;
    const freq = {};
    counts.forEach(c => { freq[c] = (freq[c] || 0) + 1; });
    const mode = +Object.keys(freq).sort((a, b) => freq[b] - freq[a] || b - a)[0];
    if (mode < 2) return;
    const score = freq[mode] / counts.length * 10 + Math.min(mode, 8) * 0.1;
    if (!best || score > best.score + 1e-9) best = {d, score};
  });
  return best ? best.d : null;
}
/** text -> {head: [names], rows: [[cells]], hadHeader} */
function splitTable(text) {
  let t = String(text == null ? '' : text).replace(/^﻿/, '');
  let dl = null;
  const sep = t.match(/^sep=(.)\s*(\r\n|\n|\r)/i);
  if (sep) { dl = sep[1]; t = t.slice(sep[0].length); }
  dl = dl || guessDelimiter(t);
  let rows = dl ? parseCells(t, dl) : t.split(/\r\n|\n|\r/).map((l, i) => { const o = l.trim().split(/\t| {2,}/).map(x => x.trim()); o.line = i + 1; return o; }).filter(r => r.some(Boolean));
  if (!rows.length) return {head: [], rows: [], hadHeader: false};
  const width = Math.max.apply(null, rows.map(r => r.length));
  const first = rows[0];
  const hadHeader = first.some(c => c) && first.every(c => !c || (!looksNumber(c) && !parseDate(c, 'mdy') && !parseDate(c, 'dmy')));
  const names = [];
  for (let i = 0; i < width; i++) {
    let n = clip(hadHeader ? first[i] : '', 40) || 'Column ' + (i + 1), k = 2;
    const base = n;
    while (names.indexOf(n) >= 0) n = base + ' (' + k++ + ')';
    names.push(n);
  }
  if (hadHeader) rows = rows.slice(1);
  rows = rows.map(r => { const c = r.slice(0, width); while (c.length < width) c.push(''); c.line = r.line; return c; });
  return {head: names, rows, hadHeader, delimiter: dl};
}
/** look at each column: how many dates, numbers, repeats */
function profile(table) {
  return table.head.map((name, i) => {
    const vals = [];
    for (let j = 0; j < table.rows.length && vals.length < 400; j++) { const v = table.rows[j][i]; if (v) vals.push(v); }
    const n = vals.length || 1, hinted = HINT.date.test(name);
    const dates = vals.filter(v => parseDate(v, 'mdy', hinted) || parseDate(v, 'dmy', hinted)).length;
    const nums = vals.filter(looksNumber).length, distinct = new Set(vals.map(v => v.toLowerCase())).size;
    const examples = Array.from(new Set(vals)).slice(0, 3), times = vals.filter(v => /^\d{1,2}:\d{2}(:\d{2})?(\s*[ap]\.?m\.?)?$/i.test(v)).length, alpha = vals.filter(v => /[a-z]/i.test(v)).length;
    return {i, name, filled: vals.length, vals, dateRatio: dates / n, numRatio: nums / n, timeRatio: times / n, alphaRatio: alpha / n, distinct, uniq: distinct / n, examples, hinted,
      money: vals.some(v => /[.,]\d{2}(\D*)$/.test(v) || CURRENCY.test(v.replace(/^[(\-+\s]+/, '')))};
  });
}
/** guess which column is the date, the amount, the category and the description */
function detectColumns(table) {
  const cols = profile(table).filter(c => c.filled);
  const best = (list, score) => list.map(c => ({c, s: score(c)})).filter(x => x.s > 0).sort((a, b) => b.s - a.s)[0];
  const d = best(cols.filter(c => c.dateRatio >= 0.6), c => c.dateRatio + (c.hinted ? 0.5 : 0) - (c.numRatio > 0.9 && !c.hinted ? 0.5 : 0));
  const dateCol = d ? d.c : null;
  const a = best(cols.filter(c => c !== dateCol && c.numRatio >= 0.6), c => c.numRatio + (HINT.amount.test(c.name) ? 0.6 : 0) + (/total/i.test(c.name) ? 0.2 : 0) +
    (c.money ? 0.25 : 0) - (HINT.id.test(c.name) && !HINT.amount.test(c.name) ? 0.8 : 0) - (HINT.qty.test(c.name) && !HINT.amount.test(c.name) ? 0.3 : 0) - (c.dateRatio > 0.5 ? 1 : 0));
  const amountCol = a ? a.c : null;
  const text = cols.filter(c => c !== dateCol && c !== amountCol && c.numRatio < 0.5 && c.dateRatio < 0.5 && c.timeRatio < 0.5);
  const k = best(text.filter(c => c.distinct >= 2 && (c.uniq <= 0.6 || c.distinct <= 12)), c => 0.2 + (HINT.cat.test(c.name) ? 0.6 : 0) + (1 - c.uniq) - (HINT.label.test(c.name) && c.uniq > 0.5 ? 0.4 : 0));
  const catCol = k ? k.c : null;
  const l = best(text.filter(c => c !== catCol), c => 0.1 + c.uniq * 0.6 + c.alphaRatio * 0.5 + (HINT.label.test(c.name) ? 0.5 : 0));
  const dateVals = dateCol ? dateCol.vals : [];
  const order = dateOrderOf(dateVals);
  return {
    date: dateCol ? dateCol.i : -1, amount: amountCol ? amountCol.i : -1, cat: catCol ? catCol.i : -1, label: l ? l.c.i : -1,
    order: order || LOCALE_ORDER, orderKnown: !!order, numericDates: dateVals.some(v => /^\d{1,2}[-/.]\d{1,2}[-/.]\d{2,4}/.test(String(v).trim())),
    dec: amountCol ? decimalOf(amountCol.vals, table.delimiter === ';' ? ',' : '.') : '.', loose: !!(dateCol && dateCol.hinted)
  };
}
/** the owner's column choice -> clean rows, plus the numbers of the rows that had no date or amount */
function buildRows(table, map) {
  const rows = [], skipped = [], at = [], first = table.hadHeader ? 2 : 1;
  const dec = map.amount >= 0 ? decimalOf(table.rows.slice(0, 400).map(r => r[map.amount]).filter(Boolean), table.delimiter === ';' ? ',' : '.') : '.';
  const loose = map.date >= 0 && HINT.date.test(table.head[map.date] || '');
  table.rows.forEach((r, i) => {
    const date = map.date >= 0 ? parseDate(r[map.date], map.order, loose) : null;
    const amount = map.amount >= 0 && map.amount !== map.date ? toNumber(r[map.amount], dec) : NaN;
    if (!date || !isFinite(amount)) { skipped.push(r.line || i + first); at.push(-1); return; }
    at.push(rows.length);
    rows.push({date, amount: round2(amount), cat: map.cat >= 0 ? clip(r[map.cat], 60) : '', label: map.label >= 0 ? clip(r[map.label], 80) : ''});
  });
  return {rows, skipped, at};
}
const rowKey = r => [r.date, (r.cat || '').toLowerCase(), round2(r.amount), (r.label || '').toLowerCase()].join('|');
/** add (or replace with) imported rows; rows already there are skipped; sample rows always go */
function importRows(list, name, mode) {
  const id = AK.id(), res = {added: 0, dupes: 0, removedSample: false};
  db.update(s => {
    res.removedSample = s.rows.some(r => r.sample);
    const keep = mode === 'replace' ? [] : s.rows.filter(r => !r.sample), have = new Map(), fresh = [];
    keep.forEach(r => { const k = rowKey(r); have.set(k, (have.get(k) || 0) + 1); });
    list.forEach(r => {
      const k = rowKey(r), n = have.get(k) || 0;
      if (n > 0) { have.set(k, n - 1); res.dupes++; return; }
      fresh.push(Object.assign({id: AK.id()}, r, {imp: id}));
    });
    res.added = fresh.length;
    s.rows = keep.concat(fresh);
    s.imports = (s.imports || []).filter(m => s.rows.some(r => r.imp === m.id));
    if (fresh.length) s.imports.push({id, name: clip(name, 60) || 'Import', at: new Date().toISOString(), count: fresh.length});
  });
  return res;
}

/* ==== 6. Overview screen ==== */
function renderOverview(page) {
  const s = settings(), rows = db.get('rows'), u = look();
  AK.currency = s.currency;
  page.append(tpl('view-overview'));
  put(page, 'metric', s.name || 'Numbers');
  show(slot(page, 'sample-note'), rows.some(r => r.sample));
  if (!rows.length) {
    put(page, 'period', 'Your dashboard');
    put(page, 'insight', 'Add your numbers to get started.');
    show(slot(page, 'welcome'), true);
    return;
  }
  const st = summary(rows, u, s);
  put(page, 'period', st.range.name + ' · ' + spanText(st.range.from, st.range.to));
  put(page, 'insight', st.count ? insight(st, s) : 'No entries for this choice yet.');
  mountFilters(slot(page, 'filters'), st, true);
  mountViews(page, u);
  if (!st.count) {
    const span = dataSpan(rows), note = slot(page, 'no-match'), btn = $('.btn', note), byCat = st.dated.length > 0;
    put(page, 'span-note', byCat ? 'None of the chosen categories have entries in these dates.' : 'Your entries run from ' + spanText(span.from, span.to) + '.');
    btn.dataset.act = byCat ? 'clear-cats' : 'show-all-dates';
    btn.textContent = byCat ? 'Show all categories' : 'Show all dates';
    show(note, true);
    return;
  }
  show(slot(page, 'dash'), true);
  fillKpis(page, st, s);
  drawTrend(page, st, s, u);
  drawShare(page, st);
  drawSplit(page, st, s, u);
  fillRecent(slot(page, 'recent'), st.rows, 6);
}
/** good / bad / flat, following "Higher is better" */
function trendOf(change, s) {
  if (change == null || change === Infinity || Math.abs(change) < 0.005) return 'flat';
  return (change > 0) === (s.higher !== false) ? 'good' : 'bad';
}
function deltaBadge(el, change, s, words) {
  if (change == null || change === Infinity) { el.hidden = true; return; }
  const t = trendOf(change, s);
  el.className = 'delta' + (t === 'good' ? ' up' : t === 'bad' ? ' down' : '');
  el.replaceChildren(icon(change > 0 ? 'trend-up' : change < 0 ? 'trend-down' : 'minus'), document.createTextNode(' ' + AK.fmt.percent(Math.abs(change)) + ' ' + words));
}
function fillKpis(page, st, s) {
  const name = (s.name || '').trim();
  put(page, 'total-label', 'Total' + (name ? ' ' + name.toLowerCase() : ''));
  put(page, 'total', short(st.total));
  $('[data-text="total"]', page).title = full(st.total);
  deltaBadge(slot(page, 'total-delta'), st.change, s, 'vs ' + st.range.prevName.replace(/^the /, ''));
  const spark = slot(page, 'spark');
  if (st.cur.values.length > 1) AK.chart.spark(spark, st.cur.values, {height: 56, label: 'Trend of ' + (name || 'the total')}); else spark.hidden = true;
  // change versus the period before
  const card = slot(page, 'change-card');
  card.dataset.trend = trendOf(st.change, s);
  if (st.change == null) { put(page, 'change', '—'); put(page, 'change-meta', 'Pick a date range to compare'); }
  else { put(page, 'change', signedPct(st.change)); put(page, 'change-meta', st.change === Infinity ? 'Nothing in ' + st.range.prevName : 'Was ' + short(st.prevTotal) + ' in ' + st.range.prevName); }
  // average and count
  put(page, 'avg', short(st.avg));
  put(page, 'avg-meta', 'per entry · about ' + short(st.perDay) + ' a day');
  put(page, 'count', AK.fmt.number(st.count));
  if (st.prevCount == null) put(page, 'count-meta', 'over ' + plural(st.range.days, 'day'));
  else { const d = st.count - st.prevCount; put(page, 'count-meta', (d > 0 ? '+' : d < 0 ? '−' : '') + AK.fmt.number(Math.abs(d)) + ' vs ' + st.range.prevName.replace(/^the /, '')); }
  // best category (or the largest entry when there are no categories)
  const named = st.byCat.filter(c => c.cat);
  if (named.length) {
    const top = st.byCat[0];
    put(page, 'best-label', 'Best category');
    put(page, 'best', catName(top.cat));
    put(page, 'best-meta', short(top.total) + (st.total > 0 && top.total > 0 ? ' · ' + AK.fmt.percent(top.total / st.total) + ' of total' : ''));
  } else {
    put(page, 'best-label', 'Largest entry');
    put(page, 'best', short(st.largest.amount));
    put(page, 'best-meta', (st.largest.label ? st.largest.label + ' · ' : '') + AK.fmt.date(st.largest.date, 'day'));
  }
}
function drawTrend(page, st, s, u) {
  const el = slot(page, 'trend'), canCompare = !!st.prev && st.prevCount > 0, compare = u.compare !== false && canCompare, F = isMoney() ? 'money' : undefined;
  const set = [{name: 'This period', values: st.cur.values}];
  if (compare) set.push({name: 'Previous period', values: st.prev.values});
  let type = u.chart === 'bar' ? 'bar' : compare ? 'line' : 'area';
  if (st.cur.values.length < 2) type = 'bar';
  const by = {day: 'day', week: 'week', month: 'month', year: 'year'}[st.grain];
  put(page, 'trend-title', (s.name || 'Total') + ' by ' + by);
  AK.chart[type](el, {labels: st.cur.labels, series: set, format: F, title: (s.name || 'Total') + ' by ' + by});
  $$('input[name="chart"]', page).forEach(r => { r.checked = r.value === (u.chart === 'bar' ? 'bar' : 'area'); r.addEventListener('change', () => ui.set({chart: r.value})); });
  const cb = $('input[name="compare"]', page);
  cb.checked = compare;
  show(cb.closest('.compare'), canCompare);
  cb.addEventListener('change', () => ui.set({compare: cb.checked}));
  show(slot(page, 'legend-prev'), compare);
  put(page, 'legend-now', spanText(st.range.from, st.range.to));
  if (canCompare) put(page, 'legend-prev', spanText(st.range.prevFrom, st.range.prevTo));
}
/** top N categories, the rest summed as "Other" */
function topCats(byCat, n) {
  if (byCat.length <= n + 1) return byCat.map(c => ({name: catName(c.cat), total: c.total}));
  const head = byCat.slice(0, n).map(c => ({name: catName(c.cat), total: c.total}));
  head.push({name: 'Other', total: round2(byCat.slice(n).reduce((t, c) => t + c.total, 0))});
  return head;
}
function drawShare(page, st) {
  const el = slot(page, 'share'), named = st.byCat.some(c => c.cat);
  show(slot(page, 'share-empty'), !named);
  if (!named) { el.hidden = true; return; }
  const top = topCats(st.byCat, 5);
  AK.chart.donut(el, {labels: top.map(c => c.name), values: top.map(c => c.total), format: isMoney() ? 'money' : undefined, center: 'Total', title: 'Share by category'});
}
function weekdayNames(ws) { const out = []; for (let i = 0; i < 7; i++) out.push(new Date(2023, 0, 1 + ((i + ws) % 7)).toLocaleDateString(loc(), {weekday: 'short'})); return out; }
function drawSplit(page, st, s, u) {
  const el = slot(page, 'split'), named = st.byCat.some(c => c.cat), mode = named && u.split !== 'day' ? 'cat' : 'day', ws = +s.weekStart || 0;
  show(slot(page, 'split-seg'), named);
  $$('input[name="split"]', page).forEach(r => { r.checked = r.value === mode; r.addEventListener('change', () => ui.set({split: r.value})); });
  let labels, values;
  if (mode === 'cat') { const top = topCats(st.byCat, 7); labels = top.map(c => c.name); values = top.map(c => c.total); el.style.minWidth = labels.length * 76 + 'px'; }
  else { labels = weekdayNames(ws); values = labels.map((_, i) => st.weekdays[(i + ws) % 7]); el.style.minWidth = ''; }
  AK.chart.bar(el, {labels, values, format: isMoney() ? 'money' : undefined, title: mode === 'cat' ? 'By category' : 'By day of the week'});
}
/** newest entries first (same date: the one added last first) */
function newest(list, n) { return list.map((r, i) => [r, i]).sort((a, b) => (a[0].date < b[0].date ? 1 : a[0].date > b[0].date ? -1 : b[1] - a[1])).slice(0, n).map(x => x[0]); }
function fillRecent(ul, list, n) {
  newest(list, n).forEach(r => ul.append(piece('tpl-entry-row', li => {
    $('button', li).dataset.id = r.id;
    put(li, 'mon', toDate(r.date).toLocaleDateString(loc(), {month: 'short'}));
    put(li, 'day', +r.date.slice(8));
    put(li, 'title', r.label || catName(r.cat));
    put(li, 'sub', [r.label ? catName(r.cat) : '', AK.fmt.day(r.date)].filter(Boolean).join(' · '));
    put(li, 'amount', full(r.amount));
  })));
}

/* ==== 7. Filters bar: date range + category chips (Overview and Entries) ==== */
const MAX_CHIPS = 8;
function chipEl(name, n, pressed) {
  return piece('tpl-chip', c => { put(c, 'name', name); put(c, 'n', n == null ? '' : AK.fmt.number(n)); c.setAttribute('aria-pressed', String(!!pressed)); if (n == null) $('.n', c).remove(); });
}
function mountFilters(host, st, withSave) {
  host.append(tpl('part-filters'));
  const u = look(), sel = slot(host, 'range'), from = slot(host, 'from'), to = slot(host, 'to');
  sel.value = st.range.key;
  show(slot(host, 'custom'), st.range.key === 'custom');
  show(slot(host, 'save-view'), withSave);
  from.value = st.range.from; to.value = st.range.to;
  sel.addEventListener('change', () => ui.set(sel.value === 'custom' ? {range: 'custom', from: st.range.from, to: st.range.to} : {range: sel.value}));
  const setCustom = () => { if (isIso(from.value) && isIso(to.value)) ui.set({range: 'custom', from: from.value, to: to.value}); };
  from.addEventListener('change', setCustom); to.addEventListener('change', setCustom);
  // categories (chips scroll sideways on phones; the rest sit behind "More")
  const chips = slot(host, 'cats'), counts = st.catCounts.slice();
  u.cats.forEach(c => { if (!counts.some(x => x.cat === c)) counts.push({cat: c, count: 0}); });
  if (counts.length < 2 && !u.cats.length) { chips.hidden = true; return; }
  chips.append(chipEl('All', st.dated.length, !u.cats.length));
  chips.lastChild.dataset.cat = '*';
  const shown = counts.slice(0, MAX_CHIPS).concat(counts.slice(MAX_CHIPS).filter(c => u.cats.indexOf(c.cat) >= 0));
  shown.forEach(c => { const el = chipEl(catName(c.cat), c.count, u.cats.indexOf(c.cat) >= 0); el.dataset.cat = c.cat; chips.append(el); });
  if (counts.length > shown.length) { const more = chipEl('More', counts.length - shown.length, false); more.dataset.cat = '+'; more.removeAttribute('aria-pressed'); chips.append(more); }
  AK.on(chips, 'click', '.chip', (e, c) => {
    if (c.dataset.cat === '+') return pickCategories(counts);
    if (c.dataset.cat === '*') return ui.set({cats: []});
    const cats = look().cats, i = cats.indexOf(c.dataset.cat);
    if (i >= 0) cats.splice(i, 1); else cats.push(c.dataset.cat);
    ui.set({cats: cats.length >= counts.length ? [] : cats});
  });
}
function pickCategories(counts) {
  const body = tpl('tpl-cat-sheet'), list = slot(body, 'list'), cats = look().cats;
  counts.forEach(c => list.append(piece('tpl-cat-check', l => {
    put(l, 'name', catName(c.cat)); put(l, 'n', plural(c.count, 'entry', 'entries'));
    const box = $('input', l); box.value = c.cat; box.checked = cats.indexOf(c.cat) >= 0;
  })));
  AK.sheet({title: 'Categories', body, actions: [
    {label: 'Show all', onClick: () => { ui.set({cats: []}); }},
    {label: 'Apply', primary: true, onClick: L => { const on = $$('input:checked', L.body).map(b => b.value); ui.set({cats: on.length >= counts.length ? [] : on}); }}
  ]});
}

/* ==== 8. Entries screen: sortable, searchable table (200 rows at a time, then "Show more") ==== */
// one shared collator: localeCompare with options builds a new one on every call, which is very slow in big sorts
const COLLATOR = new Intl.Collator(undefined, {numeric: true, sensitivity: 'base'});
const PAGE_ROWS = 200;
let entryLimit = PAGE_ROWS, hay = {rows: null, map: null};
const entryWhat = r => r.label || catName(r.cat);
function sortEntries(list, sort) {
  const dir = sort.dir === 'asc' ? 1 : -1, k = sort.key;
  const by = k === 'amount' ? (a, b) => a.amount - b.amount : k === 'date' ? (a, b) => (a.date < b.date ? -1 : a.date > b.date ? 1 : 0) :
    k === 'cat' ? (a, b) => COLLATOR.compare(catName(a.cat), catName(b.cat)) : (a, b) => COLLATOR.compare(entryWhat(a), entryWhat(b));
  return list.map((r, i) => [r, i]).sort((a, b) => by(a[0], b[0]) * dir || b[1] - a[1]).map(x => x[0]);   // ties: the one added last first
}
/** lower-case search text per entry, cached until the entries change */
function haystack(r) {
  const all = db.get('rows');
  if (hay.rows !== all) hay = {rows: all, map: new Map()};
  let h = hay.map.get(r.id);
  if (h == null) { h = [r.label, catName(r.cat), r.date, r.amount.toFixed(2)].join(' ').toLowerCase(); hay.map.set(r.id, h); }
  return h;
}
function renderEntries(page) {
  const s = settings(), rows = db.get('rows');
  AK.currency = s.currency;
  page.append(tpl('view-entries'));
  if (!rows.length) {
    put(page, 'period', 'Entries'); put(page, 'summary', 'Nothing here yet.');
    ['entry-actions', 'tools', 'table'].forEach(k => show(slot(page, k), false));
    const none = slot(page, 'none');
    AK.render(none, AK.empty({art: 'list', title: 'No entries yet', text: 'Paste a table, import a CSV file or add entries one at a time.', action: {label: 'Add data', icon: 'upload', act: 'go-add'}}));
    show(none, true);
    return;
  }
  const st = summary(rows, look(), s);
  put(page, 'period', st.range.name + ' \u00b7 ' + spanText(st.range.from, st.range.to));
  put(page, 'summary', plural(st.count, 'entry', 'entries') + ' \u00b7 ' + full(st.total) + ' in total');
  mountFilters(slot(page, 'filters'), st, false);
  const search = $('#entry-search', page), sortSel = $('#entry-sort', page), table = slot(page, 'table');
  search.value = entryQuery;
  sortSel.value = entrySort.key + ':' + entrySort.dir;
  let timer = 0;
  search.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(() => { entryQuery = search.value; entryLimit = PAGE_ROWS; drawEntries(page, st); }, 120); });
  sortSel.addEventListener('change', () => { const p = sortSel.value.split(':'); entrySort = {key: p[0], dir: p[1]}; drawEntries(page, st); });
  AK.on(table, 'click', '[data-sort]', (e, b) => {
    const k = b.dataset.sort;
    entrySort = {key: k, dir: entrySort.key === k && entrySort.dir === 'asc' ? 'desc' : 'asc'};
    drawEntries(page, st);
    const nb = $('[data-sort="' + k + '"]', table); if (nb) nb.focus();
  });
  table.addEventListener('keydown', e => { const tr = e.target.closest && e.target.closest('tr[data-id]'); if (tr && e.target === tr && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); editEntry(tr.dataset.id); } });
  drawEntries(page, st);
}
function drawEntries(page, st) {
  const words = entryQuery.toLowerCase().split(/\s+/).filter(Boolean);
  const found = words.length ? st.rows.filter(r => { const h = haystack(r); return words.every(w => h.indexOf(w) >= 0); }) : st.rows;
  const sorted = sortEntries(found, entrySort), shown = sorted.slice(0, entryLimit), f = document.createDocumentFragment();
  shown.forEach(r => f.append(piece('tpl-entry-tr', tr => {
    tr.dataset.id = r.id;
    put(tr, 'what', entryWhat(r)); put(tr, 'date', AK.fmt.date(r.date)); put(tr, 'cat', catName(r.cat)); put(tr, 'amount', full(r.amount));
  })));
  slot(page, 'rows').replaceChildren(f);
  $$('th[aria-sort]', page).forEach(th => {
    const b = $('[data-sort]', th), on = b.dataset.sort === entrySort.key, up = entrySort.dir === 'asc';
    th.setAttribute('aria-sort', on ? (up ? 'ascending' : 'descending') : 'none');
    $('.i', b).replaceWith(icon(on ? (up ? 'arrow-up' : 'arrow-down') : 'sort'));
  });
  const none = slot(page, 'none');
  show(slot(page, 'table'), shown.length > 0); show(none, !shown.length);
  if (!shown.length) AK.render(none, AK.empty(words.length ? {art: 'search', title: 'No matches', text: 'Nothing matches "' + clip(entryQuery, 30) + '". Try fewer letters.'} : {art: 'search', title: 'No entries in these dates', text: 'Pick other dates or categories above.'}));
  show(slot(page, 'more'), sorted.length > shown.length);
  put(page, 'shown', 'Showing ' + AK.fmt.number(shown.length) + ' of ' + AK.fmt.number(sorted.length));
}function exportCsv(list, file) {
  const cols = [{key: 'date', label: 'Date'}, {key: 'cat', label: 'Category'}, {key: 'label', label: 'Description'}, {key: 'amount', label: 'Amount'}];
  AK.csv.export(newest(list, list.length).reverse(), file, cols);
  AK.toast('Saved ' + plural(list.length, 'entry', 'entries') + ' as ' + file, 'ok');
}

/* ==== 9. Add data screen + the column check sheet ==== */
function renderImport(page) {
  const rows = db.get('rows'), real = rows.filter(r => !r.sample);
  page.append(tpl('view-import'));
  const box = $('#paste-box', page), form = slot(page, 'paste-form');
  box.value = pasteDraft;
  box.addEventListener('input', () => { pasteDraft = box.value; });
  form.addEventListener('submit', e => { e.preventDefault(); if (!box.value.trim()) { AK.toast('Paste a table into the box first.', 'warn'); box.focus(); return; } readTable(box.value, 'Pasted table'); });
  const file = $('#csv-file', page);
  file.addEventListener('change', () => { const f = file.files && file.files[0]; if (f) readFile(f, file.closest('.file-pick')); file.value = ''; });
  // what is already here
  const span = dataSpan(real), cats = new Set(real.map(r => r.cat || ''));
  put(page, 'rows', AK.fmt.number(real.length));
  put(page, 'span', span ? spanText(span.from, span.to) : 'None yet');
  put(page, 'cats', real.length ? AK.fmt.number(cats.size) : '0');
  show(slot(page, 'sample-hint'), rows.length > real.length);
  const ul = slot(page, 'imports');
  (db.get('imports') || []).slice().reverse().forEach(m => ul.append(piece('tpl-import-row', li => {
    put(li, 'name', m.name);
    put(li, 'sub', plural(m.count, 'entry', 'entries') + ' · added ' + AK.fmt.ago(m.at));
    $('[data-act="remove-import"]', li).dataset.id = m.id;
  })));
  show(ul, ul.children.length > 0);
}
/** read a picked file (UTF-8, UTF-16 or Windows-1252) */
function readFile(f, busyEl) {
  if (f.size > 15e6) { AK.toast('That file is too big for this dashboard (over 15 MB).', 'bad'); return; }
  if (busyEl) busyEl.setAttribute('aria-busy', 'true');
  f.arrayBuffer().then(buf => {
    const b = new Uint8Array(buf);
    let text;
    if (b[0] === 0xff && b[1] === 0xfe) text = new TextDecoder('utf-16le').decode(buf);
    else if (b[0] === 0xfe && b[1] === 0xff) text = new TextDecoder('utf-16be').decode(buf);
    else { text = new TextDecoder('utf-8').decode(buf); if (text.indexOf('�') >= 0) try { text = new TextDecoder('windows-1252').decode(buf); } catch (e) { /* keep utf-8 */ } }
    if (busyEl) busyEl.removeAttribute('aria-busy');
    readTable(text, f.name);
  }, () => { if (busyEl) busyEl.removeAttribute('aria-busy'); AK.toast('Could not read that file.', 'bad'); });
}
function readTable(text, name) {
  const table = splitTable(text);
  if (!table.rows.length) { AK.toast('No rows found. Copy the cells with the header row and try again.', 'warn'); return null; }
  if (table.head.length < 2) { AK.toast('Only one column found. A date column and an amount column are needed.', 'warn'); return null; }
  return openMapping(table, detectColumns(table), name);
}
function openMapping(table, map, name) {
  const body = tpl('tpl-mapping'), form = $('form', body), sel = n => form.elements.namedItem(n);
  const fill = (s, none) => { s.append(new Option(none, '-1')); table.head.forEach((h, i) => s.append(new Option(h, String(i)))); };
  fill(sel('date'), 'Pick a column'); fill(sel('amount'), 'Pick a column'); fill(sel('cat'), 'No categories'); fill(sel('label'), 'No description');
  ['date', 'amount', 'cat', 'label'].forEach(k => { sel(k).value = String(map[k]); });
  sel('order').value = map.order;
  put(form, 'found', 'Found ' + plural(table.head.length, 'column') + ' and ' + plural(table.rows.length, 'row') + (name ? ' in ' + name : '') + '. Check that each one is right:');
  const realRows = db.get('rows').filter(r => !r.sample).length;
  show(slot(form, 'mode-field'), realRows > 0);
  let built = {rows: [], skipped: []};
  const eg = i => { const v = []; for (let j = 0; j < table.rows.length && v.length < 3; j++) { const x = table.rows[j][i]; if (x && v.indexOf(x) < 0) v.push(x); } return v.length ? 'For example: ' + v.join(', ') : 'This column is empty'; };
  const update = L => {
    const m = {date: +sel('date').value, amount: +sel('amount').value, cat: +sel('cat').value, label: +sel('label').value, order: sel('order').value};
    ['date', 'amount', 'cat', 'label'].forEach(k => put(form, k + '-eg', m[k] >= 0 ? eg(m[k]) : ''));
    const dv = m.date >= 0 ? table.rows.map(r => r[m.date]).filter(Boolean) : [];
    show(slot(form, 'order-field'), dv.some(v => /^\d{1,2}[-/.]\d{1,2}[-/.]\d{2,4}/.test(String(v).trim())) && !dateOrderOf(dv.slice(0, 400)));
    built = buildRows(table, m);
    const tb = slot(form, 'preview');
    tb.replaceChildren();
    table.rows.slice(0, 6).forEach((r, i) => {
      const k = built.at[i], ok = k >= 0, b = ok ? built.rows[k] : null;
      tb.append(AK.el('tr', {class: ok ? '' : 'skip'}, [
        AK.el('td', {text: b ? AK.fmt.date(b.date) : (m.date >= 0 ? r[m.date] : '') || '?'}),
        AK.el('td', {text: b ? catName(b.cat) : m.cat >= 0 ? r[m.cat] : ''}),
        AK.el('td', {text: b ? b.label : m.label >= 0 ? r[m.label] : ''}),
        AK.el('td', {class: 'num', text: b ? full(b.amount) : 'Skipped'})]));
    });
    const res = slot(form, 'result'), sk = built.skipped;
    res.textContent = m.date < 0 || m.amount < 0 ? 'Pick the date column and the amount column.' : m.date === m.amount ? 'The date and the amount need different columns.' :
      plural(built.rows.length, 'entry', 'entries') + ' ready.' + (sk.length ? ' ' + plural(sk.length, 'row') + ' skipped because the date or amount is missing (row ' + sk.slice(0, 5).join(', ') + (sk.length > 5 ? ' and more' : '') + ').' : '');
    const btn = L && $('.dlg-foot .btn.primary', L.el);
    if (btn) btn.textContent = built.rows.length ? 'Import ' + plural(built.rows.length, 'entry', 'entries') : 'Import';
  };
  let sheet = null;
  form.addEventListener('change', () => update(sheet));
  sheet = AK.sheet({title: 'Check your columns', body, onOpen: L => update(L), actions: [
    {label: 'Cancel', value: null},
    {label: 'Import', primary: true, onClick: () => {
      if (!built.rows.length) { AK.toast('Nothing to import yet: pick the date and amount columns.', 'warn'); return false; }
      const mode = (form.querySelector('input[name="mode"]:checked') || {}).value || 'add';
      const res = importRows(built.rows, name, mode);
      const newestDate = dataSpan(db.get('rows')).to;
      ui.set({cats: [], range: newestDate < addDays(AK.today(), -29) ? 'all' : look().range});
      pasteDraft = '';
      AK.toast('Imported ' + plural(res.added, 'entry', 'entries') + (res.dupes ? ' (' + AK.fmt.number(res.dupes) + ' already here)' : '') + (res.removedSample ? '. Sample data removed.' : ''), {type: 'ok', undo: () => db.undo()});
      return 'imported';
    }}]});
  sheet.result.then(v => { if (v === 'imported') app.go('/overview'); });
  return sheet;
}

/* ==== 10. Entry sheet: add or edit one entry ==== */
function currencySymbol() {
  try { return new Intl.NumberFormat(loc(), {style: 'currency', currency: settings().currency, maximumFractionDigits: 0}).formatToParts(0).find(p => p.type === 'currency').value; } catch (e) { return '$'; }
}
function editEntry(id) {
  const row = id ? db.find('rows', id) : null;
  if (id && !row) return;
  const body = tpl('tpl-entry-form'), form = $('form', body), list = $('#ef-cats', form);
  Array.from(new Set(db.get('rows').map(r => r.cat).filter(Boolean))).sort().forEach(c => list.append(new Option(c)));
  const cur = $('[data-text="cur"]', form);
  if (isMoney()) cur.textContent = currencySymbol(); else cur.remove();
  AK.fill(form, row ? {date: row.date, amount: row.amount, cat: row.cat, label: row.label} : {date: AK.today()});
  show(slot(form, 'again'), !row);
  const save = () => {
    const v = AK.validate(form, {date: {required: true, test: x => isIso(x) || 'Pick a date'}, amount: {required: true, money: true}});
    if (!v.ok) return false;
    const data = {date: v.values.date, amount: round2(v.values.amount), cat: clip(v.values.cat, 60), label: clip(v.values.label, 80)};
    if (row) { db.update('rows', row.id, data); AK.toast('Entry saved', {type: 'ok', undo: () => db.undo()}); return true; }
    db.add('rows', data);
    AK.toast('Added ' + full(data.amount) + (data.cat ? ' to ' + data.cat : ''), {type: 'ok', undo: () => db.undo()});
    if (!v.values.again) return true;
    AK.fill(form, {amount: '', label: ''});
    setTimeout(() => form.elements.namedItem('amount').focus(), 30);
    return false;
  };
  const actions = [];
  if (row) actions.push({label: 'Delete', class: 'ghost del', onClick: () => AK.confirm('Delete this entry?', {text: full(row.amount) + ' on ' + AK.fmt.date(row.date), ok: 'Delete', danger: true}).then(yes => {
    if (!yes) return false;
    db.remove('rows', row.id); AK.toast('Entry deleted', {undo: () => db.undo()}); return true;
  })});
  actions.push({label: 'Cancel', value: null}, {label: row ? 'Save' : 'Add entry', primary: true, onClick: save});
  form.addEventListener('submit', e => e.preventDefault());
  return AK.sheet({title: row ? 'Edit entry' : 'New entry', body, focus: row ? false : '#ef-amount', actions});
}

/* ==== 11. Saved views: dates + categories + chart style under a name ==== */
const VIEW_KEYS = ['range', 'from', 'to', 'cats', 'chart', 'compare', 'split'];
const sameSet = (a, b) => (a || []).length === (b || []).length && (a || []).every(x => (b || []).indexOf(x) >= 0);
const isCurrent = (v, u) => v.range === u.range && (v.range !== 'custom' || (v.from === u.from && v.to === u.to)) && sameSet(v.cats, u.cats);
function mountViews(page, u) {
  const views = db.get('views') || [];
  if (!views.length) return;
  show(slot(page, 'views-row'), true);
  const host = slot(page, 'views');
  views.forEach(v => { const c = chipEl(v.name, null, isCurrent(v, u)); c.dataset.view = v.id; host.append(c); });
  AK.on(host, 'click', '.chip', (e, c) => { const v = db.find('views', c.dataset.view); if (v) { const o = {}; VIEW_KEYS.forEach(k => { if (k in v) o[k] = v[k]; }); ui.set(o); } });
}
function viewSummary(v) { return [RANGE_NAMES[v.range] || 'Dates', v.range === 'custom' ? spanText(v.from, v.to) : '', v.cats && v.cats.length ? v.cats.map(catName).join(', ') : 'all categories'].filter(Boolean).join(' · '); }
function saveView() {
  const u = look(), named = u.cats.map(catName);
  const guess = RANGE_NAMES[u.range] + (named.length ? ', ' + named.slice(0, 2).join(' + ') + (named.length > 2 ? ' + more' : '') : '');
  return AK.prompt('Save this view', {label: 'Name', value: guess, ok: 'Save', text: 'Keeps these dates, categories and chart style one tap away.'}).then(name => {
    if (!name) return;
    const v = {name: clip(name, 40)};
    VIEW_KEYS.forEach(k => { v[k] = u[k]; });
    db.add('views', v);
    AK.toast('View saved', 'ok');
  });
}

/* ==== 12. Settings + backup ==== */
function renderSettings(page) {
  const s = settings(), rows = db.get('rows'), form = () => slot(page, 'settings-form');
  page.append(tpl('view-settings'));
  const f = form();
  AK.fill(f, {name: s.name, unit: s.unit, currency: s.currency, higher: s.higher !== false, weekStart: String(+s.weekStart || 0)});
  show(slot(page, 'currency-field'), s.unit !== 'number');
  f.addEventListener('submit', e => e.preventDefault());
  f.addEventListener('change', () => {
    const v = AK.values(f);
    db.set('settings', {name: clip(v.name, 40) || 'Sales', unit: v.unit === 'number' ? 'number' : 'money', currency: v.currency || 'USD', higher: !!v.higher, weekStart: +v.weekStart || 0});
  });
  // saved views
  const views = db.get('views') || [], ul = slot(page, 'views');
  views.forEach(v => ul.append(piece('tpl-view-row', li => {
    put(li, 'name', v.name); put(li, 'sub', viewSummary(v));
    $$('[data-act]', li).forEach(b => { b.dataset.id = v.id; });
  })));
  show(ul, views.length > 0); show(slot(page, 'views-empty'), !views.length);
  // sample data
  const hasSample = rows.some(r => r.sample), real = rows.some(r => !r.sample);
  show(slot(page, 'sample-load'), !rows.length);
  show(slot(page, 'sample-clear'), hasSample);
  put(page, 'sample-text', hasSample ? 'The made-up entries are still here. Clear them before you add your own, or import and they go automatically.' :
    real ? 'Sample data is only offered when the dashboard is empty.' : 'Fill the dashboard with made-up entries to see how it works.');
  const restore = $('#restore-file', page);
  restore.addEventListener('change', () => { const file = restore.files && restore.files[0]; restore.value = ''; if (file) file.text().then(restoreFrom, () => AK.toast('Could not read that file.', 'bad')); });
}
function backupText() { return JSON.stringify({app: 'numbers-dashboard', version: 1, saved: new Date().toISOString(), data: db.get(), view: ui.get()}); }
const str = (v, n) => clip(typeof v === 'string' || typeof v === 'number' ? v : '', n);
/** keep only well-formed rows and views from a backup */
function cleanBackup(d) {
  const rows = (Array.isArray(d.rows) ? d.rows : []).filter(r => r && isIso(r.date) && isFinite(+r.amount) && r.amount !== '' && r.amount !== null).map(r => {
    const o = {id: str(r.id, 40) || AK.id(), date: r.date, amount: round2(+r.amount), cat: str(r.cat, 60), label: str(r.label, 80)};
    if (r.imp) o.imp = str(r.imp, 40);
    if (r.sample) o.sample = true;
    return o;
  });
  const views = (Array.isArray(d.views) ? d.views : []).filter(v => v && v.name && RANGE_NAMES[v.range]).map(v => {
    const o = {id: str(v.id, 40) || AK.id(), name: str(v.name, 40)};
    VIEW_KEYS.forEach(k => { if (k in v) o[k] = k === 'cats' ? (Array.isArray(v.cats) ? v.cats.map(c => str(c, 60)) : []) : v[k]; });
    return o;
  });
  const imports = (Array.isArray(d.imports) ? d.imports : []).filter(m => m && m.id).map(m => ({id: str(m.id, 40), name: str(m.name, 60) || 'Import', at: str(m.at, 40), count: +m.count || 0}));
  return {version: 1, rows, imports, views, settings: Object.assign({}, SETTINGS, d.settings && typeof d.settings === 'object' ? d.settings : {})};
}
function restoreFrom(text) {
  let j = null;
  try { j = JSON.parse(String(text || '').trim()); } catch (e) { j = null; }
  const d = j && j.data && Array.isArray(j.data.rows) ? j.data : j && Array.isArray(j.rows) ? j : null;
  if (!d) { AK.toast('That is not a backup from this dashboard.', 'bad'); return Promise.resolve(false); }
  const clean = cleanBackup(d);
  return AK.confirm('Replace what is on this device?', {text: 'The backup has ' + plural(clean.rows.length, 'entry', 'entries') + ' and ' + plural(clean.views.length, 'saved view') + '.', ok: 'Restore'}).then(yes => {
    if (!yes) return false;
    db.reset(clean);
    if (j.view && typeof j.view === 'object') ui.reset(Object.assign({}, LOOK, j.view));
    AK.toast('Backup restored', {type: 'ok', undo: () => db.undo()});
    return true;
  });
}

/* ==== 13. Report: printable summary with the business letterhead ==== */
function reportText(st, s) {
  const lines = [BUSINESS + ' · ' + (s.name || 'Numbers') + ' report', st.range.name + ': ' + spanText(st.range.from, st.range.to), '',
    'Total: ' + full(st.total), 'Entries: ' + AK.fmt.number(st.count), 'Average: ' + full(st.avg)];
  if (st.change != null) lines.push('Change: ' + signedPct(st.change) + ' vs ' + st.range.prevName + ' (' + full(st.prevTotal) + ')');
  if (st.byCat.some(c => c.cat)) { lines.push('', 'By category:'); st.byCat.forEach(c => lines.push('- ' + catName(c.cat) + ': ' + full(c.total) + ' (' + plural(c.count, 'entry', 'entries') + ')')); }
  return lines.join('\n');
}
function openReport() {
  const rows = db.get('rows');
  if (!rows.length) { AK.toast('Add some numbers first.', 'warn'); return null; }
  const s = settings(), u = look(), st = summary(rows, u, s), body = tpl('tpl-report');
  AK.currency = s.currency;
  put(body, 'metric', s.name || 'Numbers');
  put(body, 'period', st.range.name + ' · ' + spanText(st.range.from, st.range.to) + (u.cats.length ? ' · ' + u.cats.map(catName).join(', ') : ''));
  put(body, 'total', full(st.total)); put(body, 'count', AK.fmt.number(st.count)); put(body, 'avg', full(st.avg));
  put(body, 'change', st.change == null ? '—' : signedPct(st.change) + ' vs ' + st.range.prevName);
  const td = (t, num) => AK.el('td', {class: num ? 'num' : null, text: t});
  const cats = slot(body, 'cats');
  st.byCat.forEach(c => cats.append(AK.el('tr', [td(catName(c.cat)), td(AK.fmt.number(c.count), 1), td(st.total > 0 && c.total > 0 ? AK.fmt.percent(c.total / st.total) : '—', 1), td(full(c.total), 1)])));
  if (!st.byCat.length) cats.append(AK.el('tr', [td('No entries'), td('0', 1), td('—', 1), td(full(0), 1)]));
  const by = {day: 'Day', week: 'Week of', month: 'Month', year: 'Year'}[st.grain];
  put(body, 'time-title', 'By ' + st.grain); put(body, 'time-col', by);
  const time = slot(body, 'time');
  st.cur.keys.forEach((k, i) => time.append(AK.el('tr', [td(st.grain === 'month' ? toDate(k + '-01').toLocaleDateString(loc(), {month: 'long', year: 'numeric'}) : st.grain === 'year' ? k : AK.fmt.date(k)), td(AK.fmt.number(st.cur.counts[i]), 1), td(full(st.cur.values[i]), 1)])));
  put(body, 'foot', 'Made ' + AK.fmt.date(new Date(), 'full') + '. Totals of the entries saved in this dashboard.');
  let L = null;
  L = AK.sheet({title: 'Report', body, actions: [
    {label: 'Copy summary', icon: 'copy', onClick: () => { AK.copy(reportText(st, s), {done: 'Summary copied'}); return false; }},
    {label: 'Print', icon: 'printer', primary: true, onClick: () => { AK.print(BUSINESS + ' ' + (s.name || 'Numbers') + ' report', $('.doc', L.el)); return false; }}]});
  return L;
}

/* ==== 14. Sample data: clearly made-up entries over the last six months ==== */
function sampleRows(today) {
  let seed = 20261009;
  const rnd = () => { seed = (seed * 48271) % 2147483647; return seed / 2147483647; };
  const cats = [['Sample A', 160, 1.6], ['Sample B', 90, 1.25], ['Sample C', 340, 0.5], ['Sample D', 55, 0.95], ['Sample E', 220, 0.35]];
  const out = [];
  let n = 0;
  for (let back = 181; back >= 0; back--) {
    const date = addDays(today, -back), busy = [0.25, 1, 0.95, 1, 1.1, 1.25, 0.7][toDate(date).getDay()], trend = 0.78 + 0.22 * (1 - back / 181);
    cats.forEach(c => {
      let k = Math.floor(c[2] * busy + rnd());
      while (k-- > 0) { n++; out.push({id: 'sample-' + n, date, amount: round2(c[1] * trend * (0.6 + rnd() * 0.8)), cat: c[0], label: 'Sample entry ' + n, sample: true}); }
    });
  }
  return out;
}
function loadSample(quiet) {
  if (db.get('rows').length) { AK.toast('Sample data is only for an empty dashboard.', 'warn'); return; }
  db.update(s => { s.rows = sampleRows(AK.today()); s.imports = []; });
  ui.set({range: '30d', cats: []});
  if (!quiet) { AK.toast('Sample data added. Clear it any time.', 'ok'); if (app) app.go('/overview'); }
}

/* ==== 15. Actions: every data-act="..." button ==== */
function whenShown(sel, fn, tries) { const el = $(sel); if (el) fn(el); else if ((tries || 0) < 20) requestAnimationFrame(() => whenShown(sel, fn, (tries || 0) + 1)); }
AK.act({
  'new-entry': () => editEntry(null),
  'edit-entry': el => editEntry(el.dataset.id),
  'go-entries': () => app.go('/entries'),
  'entries-more': () => { entryLimit += PAGE_ROWS; app.refresh(); },
  'go-add': () => app.go('/add'),
  'start-paste': () => { app.go('/add'); whenShown('#paste-box', b => b.focus()); },
  'start-file': () => { app.go('/add'); whenShown('#csv-file', f => f.click()); },
  'paste-clipboard': () => {
    const fail = () => AK.toast('Press and hold in the box, then choose Paste.', 'info');
    if (!navigator.clipboard || !navigator.clipboard.readText) return fail();
    navigator.clipboard.readText().then(t => {
      if (!t) return AK.toast('The clipboard is empty.', 'warn');
      pasteDraft = t; const b = $('#paste-box'); if (b) b.value = t;
    }, fail);
  },
  'load-sample': () => loadSample(false),
  'clear-sample': () => {
    db.update(s => { s.rows = s.rows.filter(r => !r.sample); });
    AK.toast('Sample data cleared', {undo: () => db.undo()});
  },
  'save-view': () => saveView(),
  'rename-view': el => {
    const v = db.find('views', el.dataset.id); if (!v) return;
    AK.prompt('Rename view', {label: 'Name', value: v.name, ok: 'Save'}).then(n => { if (n) db.update('views', v.id, {name: clip(n, 40)}); });
  },
  'delete-view': el => {
    const v = db.find('views', el.dataset.id); if (!v) return;
    AK.confirm('Delete the view "' + v.name + '"?', {text: 'Your entries stay as they are.', ok: 'Delete', danger: true}).then(yes => {
      if (yes) { db.remove('views', v.id); AK.toast('View deleted', {undo: () => db.undo()}); }
    });
  },
  'show-all-dates': () => ui.set({range: 'all'}),
  'clear-cats': () => ui.set({cats: []}),
  'export-csv': () => { const st = summary(db.get('rows'), look(), settings()); exportCsv(st.rows, slugify(settings().name) + '-' + st.range.from + '-to-' + st.range.to + '.csv'); },
  'export-all': () => { const rows = db.get('rows'); if (!rows.length) return AK.toast('There are no entries yet.', 'warn'); exportCsv(rows, slugify(settings().name) + '-all-' + AK.today() + '.csv'); },
  'open-report': () => openReport(),
  'backup-download': () => { AK.download('numbers-backup-' + AK.today() + '.json', backupText()); AK.toast('Backup saved to your downloads', 'ok'); },
  'backup-copy': () => AK.copy(backupText(), {done: 'Backup copied. Paste it somewhere safe.'}),
  'restore-paste': () => {
    const body = tpl('tpl-paste-backup');
    AK.modal({title: 'Restore a backup', body, focus: 'textarea', actions: [{label: 'Cancel', value: null}, {label: 'Restore', primary: true, onClick: L => {
      const t = $('textarea', L.body).value;
      if (!t.trim()) { AK.setError($('textarea', L.body), 'Paste the backup text first'); return false; }
      setTimeout(() => restoreFrom(t), 0);
      return true;
    }}]});
  },
  'erase-all': () => AK.confirm('Erase all data?', {text: 'Every entry, import and saved view on this device goes. Settings stay.', ok: 'Erase', danger: true}).then(yes => {
    if (!yes) return;
    db.update(s => { s.rows = []; s.imports = []; s.views = []; });
    ui.set({cats: []});
    AK.toast('Everything erased', {undo: () => db.undo()});
  }),
  'remove-import': el => {
    const m = (db.get('imports') || []).find(x => x.id === el.dataset.id); if (!m) return;
    AK.confirm('Remove "' + m.name + '"?', {text: 'Its ' + plural(m.count, 'entry', 'entries') + ' are removed from the dashboard.', ok: 'Remove', danger: true}).then(yes => {
      if (!yes) return;
      db.update(s => { s.rows = s.rows.filter(r => r.imp !== m.id); s.imports = s.imports.filter(x => x.id !== m.id); });
      AK.toast('Import removed', {undo: () => db.undo()});
    });
  }
});

/* ==== 16. Start ==== */
if (/[?&]sample\b/.test(location.search) && !db.get('rows').length) loadSample(true);   // index.html?sample opens with the sample data
AK.currency = settings().currency;
app = AK.app({
  title: BUSINESS, icon: 'chart',
  actions: [{icon: 'printer', label: 'Report', act: 'open-report'}],
  fab: {icon: 'plus', label: 'Add entry', act: 'new-entry'},
  tabs: [
    {id: 'overview', label: 'Overview', icon: 'home', render: renderOverview},
    {id: 'entries', label: 'Entries', icon: 'list', render: renderEntries},
    {id: 'add', label: 'Add data', icon: 'upload', render: renderImport, fab: false},
    {id: 'settings', label: 'Settings', icon: 'settings', render: renderSettings, fab: false}
  ]
});
db.subscribe(() => app.refresh());
ui.subscribe(() => app.refresh());
AK.pwa({name: BUSINESS + ' Numbers', short_name: 'Numbers'});
// for tests and for a bot that wants to script the dashboard
window.NumbersApp = {db, ui, app, splitTable, detectColumns, buildRows, parseDate, toNumber, looksNumber, decimalOf, summarize, rangeFor, importRows, sampleRows, readTable, restoreFrom, backupText, round2};
})();
