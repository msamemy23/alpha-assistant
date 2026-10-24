/* Bookings | blueprint booking-manager | day agenda + week + month, services, opening hours, blocked time, clash checks, reminders.
   Built on appkit (window.AK, see APPKIT.md). Every screen is a <template> in index.html; this file fills it in and wires it up.
   Data stays in this browser (AK.store 'booking-manager'): {appts:[], services:[], settings:{hours, blocks, step, buffer, capacity, template, ...}}.
   Times are 'HH:MM' strings and minutes after midnight; days are local 'YYYY-MM-DD' strings.
   MAP  1 setup  2 time + money  3 hours, blocks, clashes, free times  4 small renderers  5 Today  6 Calendar: day timeline
        7 Calendar: week + month  8 Services  9 Settings  10 booking sheet  11 reschedule, status, search  12 service + block sheets
        13 reminder text  14 printed day sheet  15 files: CSV + backup  16 sample data  17 actions + shortcuts  18 start */
(() => {
'use strict';

/* ===== 1. SETUP ===== */
const D = document;
const $ = (s, r) => (r || D).querySelector(s);
const $$ = (s, r) => Array.from((r || D).querySelectorAll(s));
const FACTS = ($('#facts') || {dataset: {}}).dataset;
const SLUG = D.documentElement.dataset.app || 'bookings';
const LANG = navigator.language || 'en-US';
const STATUS = [
  {id: 'booked', label: 'Booked', pill: '', hint: 'In the diary'},
  {id: 'confirmed', label: 'Confirmed', pill: 'info', hint: 'The customer said yes'},
  {id: 'arrived', label: 'Arrived', pill: 'warn', hint: 'Here now'},
  {id: 'done', label: 'Done', pill: 'ok', hint: 'Finished'},
  {id: 'noshow', label: 'No-show', pill: 'bad', hint: 'Did not come'},
  {id: 'cancelled', label: 'Cancelled', pill: '', hint: 'Called off; the time is free again'}
];
const ST = {};
STATUS.forEach(s => { ST[s.id] = s; });
const ACTIVE = a => a && a.status !== 'cancelled' && a.status !== 'noshow';
const DURS = [10, 15, 20, 30, 45, 60, 75, 90, 105, 120, 150, 180, 210, 240, 300, 360, 420, 480];
const TEMPLATE = 'Hi {name}, this is {business}. Just a reminder of your {service} {day} at {time}. Reply to confirm, or let us know if you need a new time.';
const SETTINGS = {business: '', phone: '', email: '', address: '', currency: '', weekStart: '', step: 30, buffer: 0, capacity: 1, template: '', hours: null, blocks: []};
const db = AK.store('booking-manager', {v: 1, appts: [], services: [], settings: {}});
const S = () => db.get();
const cfg = () => Object.assign({}, SETTINGS, S().settings || {});
const appts = () => S().appts || [];
const services = () => S().services || [];

/* view choices that are not business data (day/week/month) live in their own small key */
const PREF_KEY = 'ak:' + SLUG + ':booking-manager-view';
const prefs = (() => {
  let p = {};
  try { p = JSON.parse(localStorage.getItem(PREF_KEY)) || {}; } catch (e) { p = {}; }
  return {get: (k, d) => (k in p ? p[k] : d), set: (k, v) => { p[k] = v; try { localStorage.setItem(PREF_KEY, JSON.stringify(p)); } catch (e) { /* private mode */ } }};
})();
const ui = {view: prefs.get('view', 'day'), date: AK.today(), scroll: true};

/* ===== 2. TIME + MONEY ===== */
const pad = n => String(n).padStart(2, '0');
const iso = d => d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
const toD = s => { const p = String(s).split('-'); return new Date(+p[0], p[1] - 1, +p[2]); };
const isDate = s => /^\d{4}-\d{2}-\d{2}$/.test(s || '') && !isNaN(toD(s));
const today = () => AK.today();
const addDays = (s, n) => { const d = toD(s); d.setDate(d.getDate() + n); return iso(d); };
const addMonths = (s, n) => { const d = toD(s), day = d.getDate(); d.setDate(1); d.setMonth(d.getMonth() + n); d.setDate(Math.min(day, new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate())); return iso(d); };
const diffDays = (a, b) => Math.round((toD(b) - toD(a)) / 864e5);
const nowIso = () => new Date().toISOString();
const tm = s => { const p = String(s || '0:0').split(':'); return (+p[0] || 0) * 60 + (+p[1] || 0); };
const hm = m => pad(Math.floor(m / 60) % 24) + ':' + pad(m % 60);
const isTime = s => /^\d{1,2}:\d{2}$/.test(s || '') && tm(s) < 1440;
const fmtT = m => AK.fmt.time(hm(Math.min(m, 1439)));
const nowMin = () => { const d = new Date(); return d.getHours() * 60 + d.getMinutes(); };
const durText = m => (m < 60 ? m + ' min' : Math.floor(m / 60) + ' h' + (m % 60 ? ' ' + (m % 60) + ' min' : ''));
const durShort = m => (m < 60 ? m + 'm' : Math.floor(m / 60) + 'h' + (m % 60 ? ' ' + (m % 60) + 'm' : ''));
const plural = (n, one, many) => n + ' ' + (n === 1 ? one : many || one + 's');
const firstName = n => String(n || '').trim().split(/\s+/)[0] || 'there';
const digits = s => String(s || '').replace(/\D/g, '');
const norm = s => String(s || '').trim().toLowerCase();
function weekStart() {
  const w = String(cfg().weekStart);
  if (w === '0' || w === '1') return +w;
  const region = LANG.split('-')[1] || 'US';
  return /^(US|CA|MX|BR|JP|IL|PH|KR|TW)$/i.test(region) ? 0 : 1;
}
const weekOf = s => { const d = toD(s); d.setDate(d.getDate() - (d.getDay() - weekStart() + 7) % 7); const a = iso(d); return [a, addDays(a, 6)]; };
const dayWord = s => { const t = AK.fmt.day(s); return /^(Today|Tomorrow|Yesterday)$/.test(t) ? t.toLowerCase() : t; };
const weekday = (s, len) => toD(s).toLocaleDateString(LANG, {weekday: len || 'short'});
function timeParts(m) {
  try {
    const parts = new Intl.DateTimeFormat(LANG, {hour: 'numeric', minute: '2-digit'}).formatToParts(new Date(2000, 0, 1, Math.floor(m / 60), m % 60));
    const ap = parts.filter(p => p.type === 'dayPeriod').map(p => p.value).join('');
    return {main: parts.filter(p => p.type !== 'dayPeriod').map(p => p.value).join('').trim(), ap};
  } catch (e) { return {main: hm(m), ap: ''}; }
}
const hourLabel = h => { try { return new Date(2000, 0, 1, h).toLocaleTimeString(LANG, {hour: 'numeric'}); } catch (e) { return h + ':00'; } };
const cents = v => Math.round((typeof v === 'number' ? v : AK.parseMoney(v)) * 100) / 100;
const sum = (arr, f) => arr.reduce((t, x) => t + Math.round((+f(x) || 0) * 100), 0) / 100;
const money = n => AK.fmt.money(n);
const money0 = n => AK.fmt.money(n, Math.abs(n) >= 1000 || Math.round(n) === n ? 0 : 2);
function guessCurrency() {
  const r = (LANG.split('-')[1] || 'US').toUpperCase();
  return {US: 'USD', CA: 'CAD', GB: 'GBP', AU: 'AUD', NZ: 'NZD', IN: 'INR', ZA: 'ZAR', MX: 'MXN', IE: 'EUR', DE: 'EUR', FR: 'EUR', ES: 'EUR', IT: 'EUR', NL: 'EUR', BE: 'EUR', AT: 'EUR', PT: 'EUR', FI: 'EUR'}[r] || 'USD';
}
const applyCurrency = () => { AK.currency = cfg().currency || guessCurrency(); };
function curSymbol() { try { return new Intl.NumberFormat(LANG, {style: 'currency', currency: AK.currency}).formatToParts(0).find(p => p.type === 'currency').value; } catch (e) { return '$'; } }

/* business facts: what the owner typed in Settings, else the facts the app filled in (fallback text like "Call us" is ignored) */
const BAD = {business: v => !v || /^\[/.test(v), phone: v => digits(v).length < 7, email: v => !/@/.test(v), address: v => !v || /^\[/.test(v)};
const biz = k => { const own = String(cfg()[k] || '').trim(); if (own) return own; const f = String(FACTS[k] || '').trim(); return BAD[k](f) ? '' : f; };
const bizName = () => biz('business');

/* ===== 3. HOURS, BLOCKED TIME, CLASHES, FREE TIMES ===== */
/* opening hours start from the {{hours}} fact when it reads like "Mon-Fri 8:00-18:00, Sat 9am-1pm", else Mon-Fri 9 to 5 */
function parseHours(s) {
  if (!s || /^\[/.test(s)) return null;
  const DAYS = {sun: 0, mon: 1, tue: 2, wed: 3, thu: 4, fri: 5, sat: 6}, out = {};
  let any = false;
  for (let d = 0; d < 7; d++) out[d] = {open: false, from: '09:00', to: '17:00'};
  const to24 = (h, m, ap) => { ap = (ap || '').toLowerCase(); if (ap === 'pm' && h < 12) h += 12; if (ap === 'am' && h === 12) h = 0; return h * 60 + m; };
  String(s).split(/[;,\n]+/).forEach(part => {
    const m = part.toLowerCase().match(/([a-z]{3})[a-z]*\.?\s*(?:(?:-|–|to|through|thru)\s*([a-z]{3})[a-z]*\.?)?\s*:?\s*(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm)?\s*(?:-|–|to)\s*(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm)?/);
    if (!m || DAYS[m[1]] == null) return;
    const a = DAYS[m[1]], b = m[2] && DAYS[m[2]] != null ? DAYS[m[2]] : a;
    const from = to24(+m[3], +(m[4] || 0), m[5]);
    let to = to24(+m[6], +(m[7] || 0), m[8]);
    if (!m[8] && !m[5] && to <= from && +m[6] <= 12) to += 720;
    if (to <= from || to > 1440) return;
    for (let d = a, n = 0; n < 7; d = (d + 1) % 7, n++) { out[d] = {open: true, from: hm(from), to: hm(to)}; if (d === b) break; }
    any = true;
  });
  return any ? out : null;
}
const FACT_HOURS = parseHours(FACTS.hours);
const DEFAULT_HOURS = (() => { const h = {}; for (let d = 0; d < 7; d++) h[d] = {open: d >= 1 && d <= 5, from: '09:00', to: '17:00'}; return h; })();
const hoursAll = () => cfg().hours || FACT_HOURS || DEFAULT_HOURS;
const hoursOn = date => { const h = hoursAll()[toD(date).getDay()] || {}; const f = tm(h.from || '09:00'), t = tm(h.to || '17:00'); return {open: !!h.open && t > f, from: f, to: t}; };
const blocksOn = date => (cfg().blocks || []).filter(b => (b.date ? b.date === date : (b.days || []).indexOf(toD(date).getDay()) > -1)).map(b => ({b, from: tm(b.from), to: tm(b.to)})).filter(x => x.to > x.from);
const apptById = id => appts().find(a => a.id === id) || null;
const endOf = a => tm(a.start) + (+a.dur || 0);
const byTime = (x, y) => String(x.date).localeCompare(String(y.date)) || tm(x.start) - tm(y.start) || String(x.created || '').localeCompare(String(y.created || ''));
const dayAppts = date => appts().filter(a => a.date === date).sort(byTime);
const svcOf = a => services().find(s => s.id === a.serviceId) || null;
const svcName = a => (svcOf(a) || {}).name || a.service || 'Appointment';
const svcColor = a => { const i = services().findIndex(s => s.id === a.serviceId); return i < 0 ? 'var(--ak-c2)' : 'var(--ak-c' + (i % 5 + 1) + ')'; };
const defaultDur = () => (services()[0] ? +services()[0].dur : 30);
/* everything wrong with booking [start, start+dur) on a day: overlaps beyond capacity (counting the gap after each booking), closed, outside hours, blocked */
function clashes(date, start, dur, ignore) {
  const c = cfg(), B = +c.buffer || 0, cap = Math.max(1, +c.capacity || 1), s = start, e = start + dur, out = [];
  const ov = dayAppts(date).filter(a => ACTIVE(a) && a.id !== ignore && tm(a.start) < e + B && s < endOf(a) + B);
  if (ov.length) {
    const pts = [s].concat(ov.map(a => tm(a.start)).filter(t => t > s && t < e + B));
    if (pts.some(t => ov.filter(a => tm(a.start) <= t && t < endOf(a) + B).length >= cap)) out.push({type: 'overlap', list: ov});
  }
  const h = hoursOn(date);
  if (!h.open) out.push({type: 'closed'});
  else if (s < h.from || e > h.to) out.push({type: 'hours', h});
  const bl = blocksOn(date).filter(x => x.from < e && s < x.to);
  if (bl.length) out.push({type: 'block', list: bl});
  return out;
}
function freeSlots(date, dur, ignore, from) {
  const h = hoursOn(date);
  if (!h.open) return [];
  const step = +cfg().step || 30, out = [];
  let min = Math.max(h.from, from || 0);
  if (date === today()) min = Math.max(min, nowMin());
  for (let t = h.from + Math.ceil((min - h.from) / step) * step; t + dur <= h.to; t += step) if (!clashes(date, t, dur, ignore).length) out.push(t);
  return out;
}
function nextFree(date, start, dur, ignore) {
  for (let i = 0; i < 120; i++) { const d = addDays(date, i), sl = freeSlots(d, dur, ignore, i === 0 ? start : 0); if (sl.length) return {date: d, start: sl[0]}; }
  return null;
}
function clashText(cl) {
  return cl.map(c => {
    if (c.type === 'overlap') return 'Clashes with ' + c.list.map(a => a.name + ' (' + fmtT(tm(a.start)) + ' to ' + fmtT(endOf(a)) + ')').join(', ') + (+cfg().buffer ? ', counting the ' + cfg().buffer + ' min gap' : '');
    if (c.type === 'closed') return 'You are closed that day';
    if (c.type === 'hours') return 'Outside opening hours (' + fmtT(c.h.from) + ' to ' + fmtT(c.h.to) + ')';
    return 'During blocked time: ' + c.list.map(x => x.b.label + ' ' + fmtT(x.from) + ' to ' + fmtT(x.to)).join(', ');
  });
}
/* side-by-side columns for bookings that overlap on the day view */
function lanes(items) {
  items.sort((x, y) => x.s - y.s || y.e - x.e);
  let group = [], ends = [], gEnd = -1;
  const flush = () => { const n = ends.length; group.forEach(it => { it.n = n; }); group = []; ends = []; };
  items.forEach(it => {
    if (group.length && it.s >= gEnd) flush();
    let lane = ends.findIndex(e => e <= it.s);
    if (lane < 0) { lane = ends.length; ends.push(it.e); } else ends[lane] = it.e;
    it.lane = lane; group.push(it); gEnd = Math.max(gEnd, it.e);
  });
  flush();
  return items;
}
const hasSample = () => appts().some(a => a.sample) || services().some(s => s.sample) || (cfg().blocks || []).some(b => b.sample);
const isEmpty = () => !appts().length && !services().length;

/* ===== 4. SMALL RENDERERS ===== */
const icon = (n, c) => AK.icon(n, c);
const frag = h => { const t = D.createElement('template'); t.innerHTML = String(h); return t.content; };
const fills = root => { const o = {}; $$('[data-f]', root).forEach(e => { o[e.dataset.f] = e; }); return o; };
function hydrate(root) { $$('i[data-icon]', root).forEach(i => i.replaceWith(frag(icon(i.dataset.icon)))); return root; }
const tpl = id => hydrate(D.getElementById(id).content.cloneNode(true));
const pill = a => AK.html`<span class="pill ${ST[a.status].pill}${a.status === 'cancelled' ? ' plain' : ''}">${ST[a.status].label}</span>`;
const emptyState = o => AK.html`<div class="card"><div class="empty">${AK.illus(o.art || 'list')}<p class="title">${o.title}</p>${o.text ? AK.html`<p class="text">${o.text}</p>` : ''}
  ${o.btns ? AK.html`<div class="btns center-btns">${o.btns.map(b => AK.html`<button type="button" class="btn ${b.cls || ''}" data-act="${b.act}">${b.icon ? icon(b.icon) : ''}${b.label}</button>`)}</div>` : ''}</div></div>`;
/* one booking as a list row: time column, service colour bar, name + service, status pill (tap = change status) */
function apptRow(a, withDate) {
  const past = a.date < today() || (a.date === today() && endOf(a) <= nowMin());
  return AK.html`<li class="ag-row s-${a.status}${past ? ' past' : ''}" style="--svc:${svcColor(a)}"><button type="button" class="item" data-act="open-appt" data-id="${a.id}">
    <span class="ag-time">${withDate ? AK.html`<span class="ag-day">${AK.fmt.day(a.date)}</span>` : ''}<span class="t1">${fmtT(tm(a.start))}</span><span class="t2">${fmtT(endOf(a))}</span></span><span class="ag-bar" aria-hidden="true"></span>
    <span class="body"><span class="title">${a.name}</span><span class="sub">${svcName(a) + ' · ' + durText(+a.dur)}${a.remindedAt ? ' · reminded' : ''}</span></span></button>
    <button type="button" class="ag-st" data-act="pick-status" data-id="${a.id}" aria-label="${'Status ' + ST[a.status].label + '. Change status of ' + a.name}">${pill(a)}</button></li>`;
}
const slotChip = (date, m, pressed) => AK.html`<button type="button" class="chip slot-chip" data-act="new-at" data-date="${date}" data-time="${hm(m)}" aria-pressed="${String(!!pressed)}">${fmtT(m)}</button>`;

/* ===== 5. TODAY: next up, the numbers, today's agenda, free times ===== */
function renderToday(page) {
  page.dataset.view = 'today';
  page.append(tpl('view-today'));
  const F = fills(page), t = today(), now = nowMin(), h = hoursOn(t);
  F.date.textContent = AK.fmt.date(new Date(), 'long');
  const hoursText = h.open ? 'Open ' + fmtT(h.from) + ' to ' + fmtT(h.to) : 'Closed today';
  if (isEmpty()) { F.welcome.hidden = false; F.main.hidden = true; F.sub.textContent = hoursText; return undefined; }
  F.welcome.remove();
  F.samplenote.hidden = !hasSample();
  const list = dayAppts(t), act = list.filter(ACTIVE), done = act.filter(a => a.status === 'done').length, coming = act.filter(a => a.status !== 'done' && endOf(a) > now).length;
  F.sub.textContent = hoursText + ' · ' + plural(act.length, 'booking');
  drawHero(F.hero, now);
  F['s-count'].textContent = act.length;
  F['s-count-m'].textContent = act.length ? done + ' done · ' + coming + ' to come' : 'Nothing booked yet';
  const booked = act.reduce((m, a) => m + (+a.dur || 0), 0), open = h.open ? h.to - h.from : 0, pct = open ? Math.min(100, Math.round(booked / open * 100)) : 0;
  F['s-ring'].style.setProperty('--value', pct);
  F['s-pct'].textContent = pct + '%';
  F['s-time'].textContent = durShort(booked);
  F['s-time-m'].textContent = open ? 'of ' + durText(open) + ' open' : 'Closed today';
  F['s-money'].textContent = money0(sum(act, a => a.price));
  F['s-money-m'].textContent = money0(sum(act.filter(a => a.status === 'done'), a => a.price)) + ' done so far';
  const w = weekOf(t), wk = appts().filter(a => ACTIVE(a) && a.date >= w[0] && a.date <= w[1]);
  F['s-week'].textContent = wk.length;
  F['s-week-m'].textContent = money0(sum(wk, a => a.price)) + ' expected';
  F.agenda.innerHTML = String(list.length ? AK.html`<ul class="list agenda">${list.map(a => apptRow(a))}</ul>`
    : AK.html`<div class="card small-empty">${icon('calendar')}<span>${h.open ? 'Nothing booked today yet.' : 'Closed today. Enjoy it.'}</span></div>`);
  const nowRow = list.findIndex(a => endOf(a) > now);
  if (list.length && nowRow > 0) { const li = $$('.agenda > li', F.agenda)[nowRow]; if (li) li.classList.add('after-now'); }
  const free = freeSlots(t, defaultDur());
  if (free.length) {
    F.freeh.textContent = 'Free times left today';
    F.free.innerHTML = String(AK.html`<p class="muted small free-note">${'For a ' + durText(defaultDur()) + ' visit. Tap one to book it.'}</p><div class="slot-chips">${free.slice(0, 12).map(m => slotChip(t, m))}</div>`);
  } else {
    const nf = nextFree(addDays(t, 1), 0, defaultDur());
    F.freeh.textContent = 'Next free time';
    F.free.innerHTML = String(nf ? AK.html`<div class="card next-free"><p>${(h.open ? 'Today is full. ' : '') + 'The next free ' + durText(defaultDur()) + ' is ' + dayWord(nf.date) + ' at ' + fmtT(nf.start) + '.'}</p>${slotChip(nf.date, nf.start)}</div>` : AK.html`<p class="muted">No free time found in the next months. Check your opening hours in Settings.</p>`);
  }
  const soon = appts().filter(a => ACTIVE(a) && a.status !== 'done' && a.date > t).sort(byTime).slice(0, 5);
  F.soonwrap.hidden = !soon.length;
  F.soon.innerHTML = String(AK.html`${soon.map(a => apptRow(a, true))}`);
  return liveRefresh();
}
function drawHero(el, now) {
  const t = today();
  const cur = dayAppts(t).find(a => ACTIVE(a) && a.status !== 'done' && tm(a.start) <= now && now < endOf(a));
  const up = appts().filter(a => ACTIVE(a) && a.status !== 'done' && a !== cur && (a.date > t || (a.date === t && tm(a.start) > now))).sort(byTime);
  const a = cur || up[0];
  if (!a) {
    el.className = 'hero hero-empty';
    el.innerHTML = String(AK.html`<p class="h-label">Next up</p><p class="h-none">Nothing else booked.</p><div class="h-acts"><button type="button" class="btn" data-act="new-appt">${icon('plus')}Book someone in</button></div>`);
    return;
  }
  const mins = a.date === t ? tm(a.start) - now : 0;
  const when = cur ? 'Now · ' + durText(endOf(a) - now) + ' left' : a.date === t ? 'Next up · in ' + (mins < 60 ? mins + ' min' : durText(mins - mins % 5)) : 'Next up · ' + AK.fmt.day(a.date);
  const tp = timeParts(tm(a.start)), then = cur && up[0] && up[0].date === t ? up[0] : null;
  const next = a.status === 'arrived' ? ['done', 'Done', 'check'] : (cur || mins <= 60) && a.date === t ? ['arrived', 'Arrived', 'user'] : a.status === 'booked' ? ['confirmed', 'Confirmed', 'check'] : null;
  el.className = 'hero' + (cur ? ' is-now' : '');
  el.innerHTML = String(AK.html`<div class="h-top"><span class="h-label">${when}</span><span class="h-status">${ST[a.status].label}</span></div>
    <button type="button" class="h-main" data-act="open-appt" data-id="${a.id}"><span class="h-time">${tp.main}${tp.ap ? AK.html`<small>${tp.ap}</small>` : ''}</span>
      <span class="h-range">${'until ' + fmtT(endOf(a)) + ' · ' + durText(+a.dur)}</span><span class="h-name">${a.name}</span><span class="h-svc">${svcName(a)}${a.price != null && a.price !== '' ? ' · ' + money(a.price) : ''}</span></button>
    <div class="h-acts">${a.phone ? AK.html`<a class="btn icon" href="${AK.tel(a.phone)}" aria-label="${'Call ' + a.name}">${icon('phone')}</a>` : ''}
      ${cur ? '' : AK.html`<a class="btn" href="${smsHref(a)}" data-act="reminded" data-id="${a.id}">${icon('message')}${a.remindedAt ? 'Text again' : 'Text reminder'}</a>`}
      ${next ? AK.html`<button type="button" class="btn solid" data-act="set-status" data-id="${a.id}" data-status="${next[0]}">${icon(next[2])}${next[1]}</button>` : ''}</div>
    ${then ? AK.html`<p class="h-then">${'Then ' + fmtT(tm(then.start)) + ': ' + then.name + ', ' + svcName(then)}</p>` : ''}`);
}
const liveRefresh = () => { if (AK.still()) return undefined; const iv = setInterval(() => { if (!$('dialog[open]')) app.refresh(); }, 60000); return () => clearInterval(iv); };

/* ===== 6. CALENDAR: header, day strip, day timeline ===== */
const PX = 100 / 60; /* pixels per minute on the day view: 50 px per half hour */
function renderCalendar(page) {
  page.dataset.view = 'calendar';
  page.append(tpl('view-calendar'));
  const F = fills(page), d = ui.date, v = ui.view, t = today();
  const r = F.view.querySelector('input[value="' + v + '"]');
  if (r) r.checked = true;
  F.view.addEventListener('change', e => { ui.view = e.target.value; prefs.set('view', ui.view); ui.scroll = true; app.refresh(); });
  F.samplenote.hidden = !hasSample() || v !== 'day';
  const words = {day: ['Previous day', 'Next day'], week: ['Previous week', 'Next week'], month: ['Previous month', 'Next month']}[v];
  F.prev.setAttribute('aria-label', words[0]); F.next.setAttribute('aria-label', words[1]);
  F.todaybtn.disabled = v === 'day' ? d === t : v === 'week' ? weekOf(t)[0] === weekOf(d)[0] : t.slice(0, 7) === d.slice(0, 7);
  if (v === 'day') {
    const h = hoursOn(d), list = dayAppts(d).filter(ACTIVE), booked = list.reduce((m, a) => m + (+a.dur || 0), 0);
    F.rel.textContent = diffDays(t, d) === 0 ? 'Today' : diffDays(t, d) === 1 ? 'Tomorrow' : diffDays(t, d) === -1 ? 'Yesterday' : weekday(d, 'long');
    F.title.textContent = toD(d).toLocaleDateString(LANG, {month: 'long', day: 'numeric', year: d.slice(0, 4) === t.slice(0, 4) ? undefined : 'numeric'});
    F.info.textContent = (h.open ? fmtT(h.from) + ' to ' + fmtT(h.to) : 'Closed') + ' · ' + plural(list.length, 'booking') + (booked ? ' · ' + durText(booked) : '');
    const ws = weekOf(d)[0];
    F.strip.innerHTML = String(AK.html`${[0, 1, 2, 3, 4, 5, 6].map(i => { const x = addDays(ws, i), n = dayAppts(x).filter(ACTIVE).length, o = hoursOn(x).open;
      return AK.html`<button type="button" class="sday${x === t ? ' is-today' : ''}${o ? '' : ' is-closed'}" data-act="pick-day" data-date="${x}" aria-pressed="${String(x === d)}" aria-label="${toD(x).toLocaleDateString(LANG, {weekday: 'long', month: 'long', day: 'numeric'}) + ', ' + plural(n, 'booking') + (o ? '' : ', closed')}">
        <span class="wd">${weekday(x, 'narrow')}</span><span class="dn">${toD(x).getDate()}</span><span class="dots">${n ? AK.raw('<i></i>'.repeat(Math.min(3, n))) : ''}</span></button>`; })}`);
    drawDay(F.out, d);
  } else {
    F.strip.remove(); F.print.remove();
    if (v === 'week') {
      const w = weekOf(d), a = toD(w[0]), b = toD(w[1]);
      F.rel.textContent = 'Week of ' + a.toLocaleDateString(LANG, {weekday: 'long'});
      F.title.textContent = a.getMonth() === b.getMonth() ? a.toLocaleDateString(LANG, {month: 'short', day: 'numeric'}) + ' to ' + b.getDate() : a.toLocaleDateString(LANG, {month: 'short', day: 'numeric'}) + ' to ' + b.toLocaleDateString(LANG, {month: 'short', day: 'numeric'});
      const wk = appts().filter(x => ACTIVE(x) && x.date >= w[0] && x.date <= w[1]);
      F.info.textContent = plural(wk.length, 'booking') + ' · ' + money0(sum(wk, x => x.price)) + ' expected';
      drawWeek(F.out, d);
    } else {
      const m = d.slice(0, 7), mm = appts().filter(x => ACTIVE(x) && x.date.slice(0, 7) === m);
      F.rel.textContent = d.slice(0, 4);
      F.title.textContent = toD(d).toLocaleDateString(LANG, {month: 'long'});
      F.info.textContent = plural(mm.length, 'booking') + ' · ' + money0(sum(mm, x => x.price)) + ' expected';
      drawMonth(F.out, d);
    }
  }
  return d === t && v === 'day' ? liveRefresh() : undefined;
}
function drawDay(out, date) {
  const h = hoursOn(date), list = dayAppts(date), step = Math.max(30, +cfg().step || 30), t = today();
  let from = h.open ? h.from : 540, to = h.open ? h.to : 1020;
  list.forEach(a => { from = Math.min(from, tm(a.start)); to = Math.max(to, endOf(a)); });
  from = Math.max(0, Math.floor(from / 60) * 60); to = Math.min(1440, Math.max(from + 120, Math.ceil(to / 60) * 60));
  const y = m => (m - from) * PX, hours = [], slots = [], shade = [], blocks = blocksOn(date).filter(x => x.from < to && x.to > from);
  for (let m = from; m <= to; m += 60) hours.push(m);
  for (let m = from; m < to; m += step) slots.push(m);
  if (!h.open) shade.push([from, to]); else { if (h.from > from) shade.push([from, h.from]); if (h.to < to) shade.push([h.to, to]); }
  const items = lanes(list.map(a => ({a, s: tm(a.start), e: Math.max(endOf(a), tm(a.start) + 15)})));
  const now = nowMin(), showNow = date === t && now >= from && now < to;
  const html = AK.html`${!h.open ? AK.html`<div class="note warn closed-note">${icon('info')}<div>${'Closed on ' + weekday(date, 'long') + 's. You can still book; it will be flagged.'}</div></div>` : ''}
  <div class="tl" style="--h:${Math.round((to - from) * PX)}px">
    <div class="tl-gutter" aria-hidden="true">${hours.map(m => AK.html`<span class="tl-hour" style="top:${Math.round(y(m))}px">${hourLabel(m / 60)}</span>`)}</div>
    <div class="tl-grid">
      ${hours.map(m => AK.html`<span class="tl-line" style="top:${Math.round(y(m))}px" aria-hidden="true"></span>${m + 30 < to ? AK.html`<span class="tl-line half" style="top:${Math.round(y(m + 30))}px" aria-hidden="true"></span>` : ''}`)}
      ${shade.map(s => AK.html`<span class="tl-closed" style="top:${Math.round(y(s[0]))}px;height:${Math.round((s[1] - s[0]) * PX)}px" aria-hidden="true"></span>`)}
      ${slots.map(m => AK.html`<button type="button" class="slot" style="top:${Math.round(y(m))}px;height:${Math.round(step * PX)}px" data-act="new-at" data-date="${date}" data-time="${hm(m)}" aria-label="${'Book at ' + fmtT(m)}"><span>${'+ ' + fmtT(m)}</span></button>`)}
      ${blocks.map(x => AK.html`<span class="tl-block" style="top:${Math.round(y(Math.max(x.from, from)))}px;height:${Math.round((Math.min(x.to, to) - Math.max(x.from, from)) * PX)}px"><span>${x.b.label + ' · ' + fmtT(x.from) + ' to ' + fmtT(x.to)}</span></span>`)}
      ${items.map(it => { const a = it.a, hgt = Math.max(44, Math.round((it.e - it.s) * PX) - 3), w = 100 / it.n;
        return AK.html`<button type="button" class="appt s-${a.status}${hgt < 66 ? ' short' : ''}" style="top:${Math.round(y(it.s)) + 1}px;height:${hgt}px;left:${(it.lane * w).toFixed(3)}%;width:${w.toFixed(3)}%;--svc:${svcColor(a)}" data-act="open-appt" data-id="${a.id}">
          <span class="appt-time">${fmtT(it.s) + ' to ' + fmtT(endOf(a))}</span><span class="appt-name">${a.name}</span><span class="appt-svc">${svcName(a)}</span>${a.status !== 'booked' ? AK.html`<span class="appt-st">${ST[a.status].label}</span>` : ''}</button>`; })}
      ${showNow ? AK.html`<span class="tl-now" style="top:${Math.round(y(now))}px" aria-hidden="true"><span>${fmtT(now)}</span></span>` : ''}
    </div>
  </div>
  <p class="muted small tl-help">Tap an empty time to book it. Tap a booking to change, move or remind.</p>`;
  const free = freeSlots(date, defaultDur()), act = list.filter(ACTIVE);
  const side = AK.html`<aside class="day-side card" aria-label="This day at a glance"><div class="ds-nums"><div><span class="ds-v">${act.length}</span><span class="ds-l">${act.length === 1 ? 'booking' : 'bookings'}</span></div><div><span class="ds-v">${durShort(act.reduce((m, a) => m + (+a.dur || 0), 0))}</span><span class="ds-l">booked</span></div><div><span class="ds-v">${money0(sum(act, a => a.price))}</span><span class="ds-l">expected</span></div></div>
    <h2 class="ds-h">Free times</h2>${free.length ? AK.html`<p class="muted small">${'For a ' + durText(defaultDur()) + ' visit. Tap one to book it.'}</p><div class="slot-chips">${free.slice(0, 18).map(m => slotChip(date, m))}</div>` : AK.html`<p class="muted small">${h.open ? 'Nothing free for a ' + durText(defaultDur()) + ' visit.' : 'Closed this day.'}</p>`}</aside>`;
  AK.render(out, AK.html`<div class="day-layout"><div class="day-main">${html}</div>${side}</div>`);
  if (ui.scroll) {
    ui.scroll = false;
    if (!AK.still()) requestAnimationFrame(() => { const n = $('.tl-now', out) || $('.appt', out); if (n) window.scrollTo({top: Math.max(0, n.getBoundingClientRect().top + window.scrollY - 180)}); });
  }
}

/* ===== 7. CALENDAR: week and month ===== */
function drawWeek(out, date) {
  const ws = weekOf(date)[0], t = today();
  AK.render(out, AK.html`<div class="week">${[0, 1, 2, 3, 4, 5, 6].map(i => {
    const d = addDays(ws, i), list = dayAppts(d), act = list.filter(ACTIVE), h = hoursOn(d), booked = act.reduce((m, a) => m + (+a.dur || 0), 0), open = h.open ? h.to - h.from : 0;
    return AK.html`<section class="wday${d === t ? ' is-today' : ''}${h.open ? '' : ' is-closed'}" aria-label="${toD(d).toLocaleDateString(LANG, {weekday: 'long', month: 'long', day: 'numeric'})}">
      <button type="button" class="wday-head" data-act="open-day" data-date="${d}" aria-label="${'Open ' + toD(d).toLocaleDateString(LANG, {weekday: 'long', month: 'long', day: 'numeric'})}"><span class="wd">${weekday(d)}</span><span class="dn">${toD(d).getDate()}</span><span class="wn">${h.open ? plural(act.length, 'booking') : 'Closed'}</span></button>
      <div class="wbody">${open && booked ? AK.html`<div class="progress" style="--value:${Math.min(100, Math.round(booked / open * 100))}" title="${durText(booked) + ' of ' + durText(open)}"></div>` : ''}
        ${list.length ? AK.html`<ul class="wlist">${list.map(a => AK.html`<li><button type="button" class="wchip s-${a.status}" style="--svc:${svcColor(a)}" data-act="open-appt" data-id="${a.id}"><span class="t">${fmtT(tm(a.start))}</span><span class="n">${a.name}</span><span class="s">${svcName(a)}</span></button></li>`)}</ul>` : AK.html`<p class="wempty">${h.open ? 'Nothing booked' : 'Closed'}</p>`}
        <button type="button" class="btn ghost sm wadd" data-act="new-on" data-date="${d}">${icon('plus')}Book</button></div></section>`; })}</div>`);
}
function drawMonth(out, date) {
  const first = date.slice(0, 8) + '01', start = weekOf(first)[0], t = today(), m = date.slice(0, 7), ws = weekStart();
  const heads = [0, 1, 2, 3, 4, 5, 6].map(i => weekday(addDays(start, i), 'narrow'));
  const cells = [];
  for (let i = 0; i < 42; i++) cells.push(addDays(start, i));
  const rows = cells[35].slice(0, 7) === m ? 6 : 5, sel = dayAppts(date);
  AK.render(out, AK.html`<div class="month"><div class="mhead" aria-hidden="true">${heads.map(x => AK.html`<span>${x}</span>`)}</div>
    <div class="mgrid">${cells.slice(0, rows * 7).map(d => { const list = dayAppts(d).filter(ACTIVE), o = hoursOn(d).open;
      return AK.html`<button type="button" class="mday${d.slice(0, 7) !== m ? ' out' : ''}${d === t ? ' is-today' : ''}${o ? '' : ' is-closed'}" data-act="pick-day" data-date="${d}" aria-pressed="${String(d === date)}" aria-label="${toD(d).toLocaleDateString(LANG, {weekday: 'long', month: 'long', day: 'numeric'}) + ', ' + plural(list.length, 'booking')}">
        <span class="dn">${toD(d).getDate()}</span>${list.length ? AK.html`<span class="mcount">${list.length}</span>` : ''}
        <span class="mnames">${list.slice(0, 3).map(a => AK.html`<span style="--svc:${svcColor(a)}">${fmtT(tm(a.start)) + ' ' + a.name}</span>`)}${list.length > 3 ? AK.html`<span class="more">${'+' + (list.length - 3) + ' more'}</span>` : ''}</span></button>`; })}</div></div>
    <section class="section month-day"><div class="sec-head"><h2>${toD(date).toLocaleDateString(LANG, {weekday: 'long', month: 'long', day: 'numeric'})}</h2><button type="button" class="btn ghost sm" data-act="open-day" data-date="${date}">Day view${icon('chevron-right')}</button></div>
      ${sel.length ? AK.html`<ul class="list agenda">${sel.map(a => apptRow(a))}</ul>` : AK.html`<div class="card small-empty">${icon('calendar')}<span>${hoursOn(date).open ? 'Nothing booked this day.' : 'Closed this day.'}</span><button type="button" class="btn ghost sm" data-act="new-on" data-date="${date}">Book</button></div>`}</section>`);
}

/* ===== 8. SERVICES ===== */
function renderServices(page) {
  page.dataset.view = 'services';
  page.append(tpl('view-services'));
  const F = fills(page), list = services();
  if (!list.length) {
    F.help.remove();
    AK.render(F.out, emptyState({art: 'list', title: 'No services yet', text: 'Add what customers book, with how long it takes. Bookings fill in the time and price for you.', btns: [{label: 'New service', icon: 'plus', cls: 'primary', act: 'new-service'}, {label: 'Try it with sample data', icon: 'zap', cls: 'soft', act: 'load-sample'}]}));
    return;
  }
  const m = today().slice(0, 7), count = id => appts().filter(a => a.serviceId === id && ACTIVE(a) && a.date.slice(0, 7) === m).length;
  F.sub.textContent = plural(list.length, 'service') + ' · tap one to change it';
  AK.render(F.out, AK.html`<ul class="list svc-list">${list.map((s, i) => AK.html`<li class="item" data-id="${s.id}" style="${'--svc:var(--ak-c' + (i % 5 + 1) + ')'}"><button type="button" class="handle" aria-label="${'Move ' + s.name + ' (arrow keys)'}">${icon('grip')}</button>
    <button type="button" class="svc-open" data-act="open-service" data-id="${s.id}"><span class="svc-dot" aria-hidden="true"></span><span class="body"><span class="title">${s.name}</span><span class="sub">${durText(+s.dur) + (count(s.id) ? ' · ' + plural(count(s.id), 'booking') + ' this month' : '')}</span></span>
    <span class="trail"><span class="value">${s.price != null && s.price !== '' ? money(s.price) : 'Varies'}</span></span>${icon('chevron-right', 'chev')}</button></li>`)}</ul>`);
  AK.drag($('.svc-list', F.out), {handle: '.handle', onSort: ids => db.set({services: ids.map(id => services().find(s => s.id === id)).filter(Boolean)})});
}

/* ===== 9. SETTINGS ===== */
function renderSettings(page) {
  page.dataset.view = 'settings';
  page.append(tpl('view-settings'));
  const F = fills(page), c = cfg(), H = hoursAll(), ws = weekStart();
  const order = [0, 1, 2, 3, 4, 5, 6].map(i => (i + ws) % 7), ref = weekOf(today())[0];
  F.hours.innerHTML = String(AK.html`${order.map((d, i) => { const h = H[d] || {open: false, from: '09:00', to: '17:00'}, name = weekday(addDays(ref, i), 'long');
    return AK.html`<div class="hours-row"><label class="check between day-name"><span>${name}</span><input type="checkbox" class="switch" name="${'open' + d}" ${h.open ? 'checked' : ''} aria-label="${'Open on ' + name}"></label>
      <div class="times"><input type="time" name="${'from' + d}" value="${h.from || '09:00'}" aria-label="${name + ' opens at'}" step="300"><span class="to">to</span><input type="time" name="${'to' + d}" value="${h.to || '17:00'}" aria-label="${name + ' closes at'}" step="300"></div></div>`; })}`);
  const syncRows = () => $$('.hours-row', F.hours).forEach(r => { r.classList.toggle('is-closed', !$('input.switch', r).checked); });
  F.hoursform.addEventListener('change', syncRows); syncRows();
  F.hoursform.addEventListener('submit', e => {
    e.preventDefault();
    const v = AK.values(F.hoursform), out = {};
    let bad = null;
    for (let d = 0; d < 7; d++) { out[d] = {open: !!v['open' + d], from: v['from' + d] || '09:00', to: v['to' + d] || '17:00'}; if (out[d].open && tm(out[d].to) <= tm(out[d].from)) bad = d; }
    if (bad != null) { AK.toast('Closing time must be after opening time.', 'bad'); const n = F.hoursform.elements['to' + bad]; if (n) n.focus(); return; }
    saveSettings({hours: out});
    AK.toast('Opening hours saved', {type: 'ok', undo: () => db.undo()});
  });
  const blocks = (c.blocks || []).slice().sort((a, b) => (a.date || '0').localeCompare(b.date || '0') || tm(a.from) - tm(b.from));
  F.blocks.innerHTML = String(blocks.length ? AK.html`<ul class="list">${blocks.map(b => AK.html`<li><button type="button" class="item" data-act="open-block" data-id="${b.id}"><span class="lead">${icon(b.date ? 'calendar' : 'refresh')}</span>
    <span class="body"><span class="title">${b.label}</span><span class="sub">${(b.date ? AK.fmt.date(b.date, 'long') : 'Every ' + daysText(b.days)) + ' · ' + fmtT(tm(b.from)) + ' to ' + fmtT(tm(b.to))}</span></span>${icon('chevron-right', 'chev')}</button></li>`)}</ul>`
    : AK.html`<p class="muted small">Nothing blocked.</p>`);
  AK.fill(F.rulesform, {step: String(c.step), buffer: String(c.buffer), capacity: c.capacity, currency: c.currency || '', weekStart: String(c.weekStart)});
  F.rulesform.addEventListener('change', () => {
    const v = AK.values(F.rulesform);
    saveSettings({step: +v.step || 30, buffer: +v.buffer || 0, capacity: Math.min(20, Math.max(1, Math.round(+v.capacity || 1))), currency: v.currency, weekStart: v.weekStart});
    applyCurrency(); AK.toast('Saved', 'ok');
  });
  const tplIn = F.tplform.elements.template;
  tplIn.value = c.template || TEMPLATE;
  const preview = () => {
    const nx = appts().filter(a => ACTIVE(a) && a.status !== 'done' && (a.date > today() || (a.date === today() && tm(a.start) > nowMin()))).sort(byTime)[0];
    F.preview.textContent = reminderText(nx || {name: '', service: services()[0] ? services()[0].name : 'appointment', date: addDays(today(), 1), start: '10:30', dur: 30}, tplIn.value);
  };
  tplIn.addEventListener('input', preview); preview();
  F.tplreset.addEventListener('click', () => { tplIn.value = TEMPLATE; preview(); });
  F.tplform.addEventListener('submit', e => { e.preventDefault(); saveSettings({template: tplIn.value.trim() === TEMPLATE ? '' : tplIn.value.trim().slice(0, 600)}); AK.toast('Reminder text saved', 'ok'); });
  AK.fill(F.bizform, {business: biz('business'), phone: biz('phone'), email: biz('email'), address: biz('address')});
  F.bizform.addEventListener('submit', e => {
    e.preventDefault();
    const r = AK.validate(F.bizform);
    if (!r.ok) return;
    saveSettings({business: r.values.business.trim(), phone: r.values.phone.trim(), email: r.values.email.trim(), address: r.values.address.trim()});
    applyBrand(); AK.toast('Details saved', {type: 'ok', undo: () => { db.undo(); applyBrand(); }});
  });
  const kb = Math.max(1, Math.round(JSON.stringify(S()).length / 1024));
  F.storage.textContent = (db.persistent ? 'Saved on this device only. ' : 'Private mode: nothing is kept after this page closes. ') + plural(appts().length, 'booking') + ', ' + plural(services().length, 'service') + ', about ' + kb + ' KB.';
  const sample = hasSample();
  F.loadsample.hidden = sample; F.clearsample.hidden = !sample;
  if (sample) F.sampletext.textContent = 'Sample data is on. Clearing it removes only the sample services, bookings and blocked time.';
}
function saveSettings(patch) { db.set({settings: Object.assign({}, S().settings || {}, patch)}); }
function daysText(days) {
  const ds = (days || []).slice().sort((a, b) => ((a - weekStart() + 7) % 7) - ((b - weekStart() + 7) % 7)), ref = weekOf(today())[0];
  const name = d => weekday(addDays(ref, (d - weekStart() + 7) % 7));
  if (ds.length === 7) return 'day';
  if (ds.join() === '1,2,3,4,5') return name(1) + ' to ' + name(5);
  return ds.map(name).join(', ') || 'never';
}

/* ===== 10. BOOKING SHEET (new + edit): live clash check and free times ===== */
function openAppt(id, preset) {
  const old = id ? apptById(id) : null;
  if (id && !old) { AK.toast('That booking is gone. It may have been deleted.', 'warn'); return; }
  const svcs = services(), s0 = svcs[0];
  const a = old ? JSON.parse(JSON.stringify(old)) : Object.assign({name: '', phone: '', serviceId: s0 ? s0.id : '__other', service: '', date: ui.date >= today() ? ui.date : today(), start: '', dur: s0 ? +s0.dur : 30, price: s0 ? s0.price : null, status: 'booked', notes: ''}, preset || {});
  if (!old && !a.start) { const nf = nextFree(a.date, 0, +a.dur); if (nf) { a.date = nf.date; a.start = hm(nf.start); } else a.start = '09:00'; }
  if (a.serviceId !== '__other' && !svcs.some(s => s.id === a.serviceId)) a.serviceId = '__other';
  const body = tpl('sheet-appt'), F = fills(body), form = $('form', body);
  const people = {};
  appts().slice().sort((x, y) => String(y.updated || y.created || '').localeCompare(String(x.updated || x.created || ''))).forEach(x => { if (x.name && !(norm(x.name) in people)) people[norm(x.name)] = x; });
  F.names.innerHTML = String(AK.html`${Object.keys(people).slice(0, 200).map(k => AK.html`<option value="${people[k].name}"></option>`)}`);
  F.svc.innerHTML = String(AK.html`${svcs.map(s => AK.html`<option value="${s.id}">${s.name + ' · ' + durText(+s.dur)}</option>`)}<option value="__other">${svcs.length ? 'Something else' : 'Type what it is for'}</option>`);
  const durs = DURS.indexOf(+a.dur) < 0 ? DURS.concat([+a.dur]).sort((x, y) => x - y) : DURS;
  F.dur.innerHTML = String(AK.html`${durs.map(m => AK.html`<option value="${m}">${durText(m)}</option>`)}`);
  F.status.innerHTML = String(AK.html`${STATUS.map(s => AK.html`<label><input type="radio" name="status" value="${s.id}"><span><span class="sdot s-${s.id}" aria-hidden="true"></span>${s.label}</span></label>`)}`);
  F.cur.textContent = curSymbol();
  const L = AK.sheet({
    title: old ? old.name : 'New booking', body, focus: old ? false : '#a-name',
    actions: [
      old ? {label: 'Delete', icon: 'trash', ghost: true, onClick: l => { deleteAppt(old.id, l); return false; }} : null,
      {label: old ? 'Save' : 'Book it', primary: true, onClick: l => saveAppt(old, $('form', l.body), l)}
    ].filter(Boolean)
  });
  const B = L.body, fm = $('form', B), G = fills(B);
  AK.fill(fm, {name: a.name, phone: a.phone, serviceId: a.serviceId, service: a.service, date: a.date, start: a.start, dur: String(a.dur), price: a.price == null ? '' : a.price, status: a.status, notes: a.notes});
  let lastSvcPrice = s0 && a.serviceId === s0.id ? s0.price : (svcs.find(s => s.id === a.serviceId) || {}).price;
  const showOther = () => { G.otherf.hidden = fm.elements.serviceId.value !== '__other'; };
  showOther();
  fm.elements.serviceId.addEventListener('change', () => {
    const s = svcs.find(x => x.id === fm.elements.serviceId.value);
    showOther();
    if (s) {
      if (DURS.indexOf(+s.dur) < 0 && !$('option[value="' + s.dur + '"]', G.dur)) G.dur.append(new Option(durText(+s.dur), s.dur));
      fm.elements.dur.value = String(s.dur);
      const cur = fm.elements.price.value.trim();
      if (!cur || (lastSvcPrice != null && AK.parseMoney(cur) === +lastSvcPrice)) fm.elements.price.value = s.price != null && s.price !== '' ? (+s.price).toFixed(2) : '';
      lastSvcPrice = s.price;
    } else setTimeout(() => fm.elements.service.focus(), 30);
    check();
  });
  fm.elements.name.addEventListener('change', () => { const p = people[norm(fm.elements.name.value)]; if (p && p.phone && !fm.elements.phone.value.trim()) fm.elements.phone.value = p.phone; });
  ['date', 'start', 'dur'].forEach(n => { fm.elements[n].addEventListener('change', check); fm.elements[n].addEventListener('input', check); });
  B.addEventListener('click', e => {
    const c = e.target.closest('[data-pick-time]');
    if (!c) return;
    e.preventDefault();
    if (c.dataset.pickDate) fm.elements.date.value = c.dataset.pickDate;
    fm.elements.start.value = c.dataset.pickTime;
    check();
  });
  function check() {
    const date = fm.elements.date.value, start = fm.elements.start.value, dur = +fm.elements.dur.value || 30, ign = old ? old.id : null;
    if (!isDate(date) || !isTime(start)) { G.clash.innerHTML = ''; G.slots.innerHTML = ''; return; }
    const free = freeSlots(date, dur, ign), st = tm(start);
    G.slots.innerHTML = String(free.length ? AK.html`${free.slice(0, 16).map(m => AK.html`<button type="button" class="chip slot-chip" data-pick-time="${hm(m)}" aria-pressed="${String(m === st)}">${fmtT(m)}</button>`)}` : AK.html`<span class="muted small">${hoursOn(date).open ? 'No free times left that day.' : 'Closed that day.'}</span>`);
    const cl = clashes(date, st, dur, ign), past = !old && (date < today() || (date === today() && st < nowMin()));
    const pastNote = past ? AK.html`<div class="note warn">${icon('clock')}<div>That time has already passed. Fine for writing down a walk-in.</div></div>` : '';
    if (!cl.length) { G.clash.innerHTML = String(AK.html`<div class="stack" style="--gap:8px">${pastNote}<div class="note ok">${icon('check-circle')}<div>${'Free: ' + fmtT(st) + ' to ' + fmtT(st + dur) + ', ' + dayWord(date) + '.'}</div></div></div>`); return; }
    const nf = nextFree(date, st, dur, ign);
    G.clash.innerHTML = String(AK.html`<div class="stack" style="--gap:8px">${pastNote}<div class="note ${cl.some(c => c.type === 'overlap') ? 'bad' : 'warn'}">${icon('alert')}<div><ul class="clash-list">${clashText(cl).map(x => AK.html`<li>${x}</li>`)}</ul>
      ${nf ? AK.html`<button type="button" class="btn sm soft next-free-btn" data-pick-time="${hm(nf.start)}" data-pick-date="${nf.date}">${icon('arrow-right')}${'Next free: ' + (nf.date === date ? '' : AK.fmt.day(nf.date) + ' ') + fmtT(nf.start)}</button>` : ''}</div></div></div>`);
  }
  check();
  if (old) {
    G.quick.hidden = false;
    if (old.phone) G.call.href = AK.tel(old.phone); else G.call.remove();
    const upcoming = ACTIVE(old) && old.status !== 'done' && (old.date > today() || (old.date === today() && tm(old.start) > nowMin()));
    if (upcoming) { G.remind.href = smsHref(old); G.remind.dataset.id = old.id; } else G.remind.remove();
    if (old.remindedAt) { G.reminded.hidden = false; G.reminded.textContent = 'Reminder text opened ' + AK.fmt.date(old.remindedAt) + ', ' + AK.fmt.time(new Date(old.remindedAt)); }
    G.move.addEventListener('click', () => reschedule(old.id));
    G.again.addEventListener('click', () => { const nx = Object.assign({}, old, {date: addDays(old.date >= today() ? old.date : today(), 7), status: 'booked', notes: ''}); delete nx.id; AK.closeAll(); setTimeout(() => openAppt(null, nx), 60); });
    G.meta.textContent = [old.created ? 'Booked ' + AK.fmt.date(old.created) : '', old.updated ? 'changed ' + AK.fmt.ago(old.updated) : ''].filter(Boolean).join(' · ');
  } else { G.quick.remove(); G.meta.remove(); }
}
function saveAppt(old, form, layer) {
  const r = AK.validate(form, {price: {min: 0, max: 9999999, message: 'Enter a price, or leave it empty'}, start: {test: v => isTime(v) || 'Pick a start time'}});
  const v = r.values;
  if (v.serviceId === '__other' && !String(v.service || '').trim() && !services().length) { AK.setError(form.elements.service, 'Say what the visit is for'); r.ok = false; }
  if (!r.ok) return false;
  const svc = services().find(s => s.id === v.serviceId);
  const data = {name: v.name.trim().slice(0, 80), phone: String(v.phone || '').trim().slice(0, 40), serviceId: svc ? svc.id : '', service: svc ? svc.name : String(v.service || '').trim().slice(0, 80),
    date: v.date, start: hm(tm(v.start)), dur: Math.max(5, +v.dur || 30), price: v.price == null ? null : cents(v.price), status: ST[v.status] ? v.status : 'booked', notes: String(v.notes || '').trim().slice(0, 2000)};
  const commit = () => {
    if (old) db.update('appts', old.id, Object.assign(data, {updated: nowIso()}, old.date !== data.date || old.start !== data.start ? {remindedAt: null} : {}));
    else db.add('appts', Object.assign({created: nowIso(), updated: nowIso(), remindedAt: null}, data));
    layer.close();
    AK.toast((old ? 'Saved: ' : 'Booked: ') + data.name + ', ' + dayWord(data.date) + ' at ' + fmtT(tm(data.start)), 'ok');
  };
  const cl = ACTIVE(data) ? clashes(data.date, tm(data.start), data.dur, old ? old.id : null) : [];
  if (!cl.length) { commit(); return false; }
  AK.confirm('Book it anyway?', {text: clashText(cl).join('. ') + '.', ok: 'Book anyway', icon: 'alert'}).then(ok => { if (ok) commit(); });
  return false;
}
function deleteAppt(id, layer) {
  const a = apptById(id);
  if (!a) return;
  AK.confirm('Delete this booking?', {text: a.name + ', ' + dayWord(a.date) + ' at ' + fmtT(tm(a.start)) + '. Tip: Cancelled keeps a record instead. You can undo right after.', ok: 'Delete', danger: true}).then(ok => {
    if (!ok) return;
    db.remove('appts', id);
    if (layer) layer.close();
    AK.toast('Booking deleted', {undo: () => db.undo()});
  });
}

/* ===== 11. RESCHEDULE, STATUS, SEARCH ===== */
function reschedule(id) {
  const a = apptById(id);
  if (!a) return;
  let day = a.date >= today() ? a.date : today();
  const L = AK.sheet({title: 'Reschedule', body: AK.html`<p class="pick-title">${a.name + ' · ' + svcName(a) + ' · ' + durText(+a.dur)}</p><p class="label small">Pick a day</p><div class="day-chips" data-r="days"></div>
    <div class="field pick-date"><label for="r-date">Or any day</label><input id="r-date" type="date" min="${today()}"></div><p class="label small" data-r="label"></p><div class="slot-chips big" data-r="slots"></div>`});
  const B = L.body, inp = $('#r-date', B);
  const draw = () => {
    inp.value = day;
    $('[data-r="days"]', B).innerHTML = String(AK.html`${Array.from({length: 14}, (x, i) => addDays(today(), i)).map(d => AK.html`<button type="button" class="chip day-chip${hoursOn(d).open ? '' : ' is-closed'}" data-day="${d}" aria-pressed="${String(d === day)}"><span>${i18nDay(d)}</span><small>${String(toD(d).getDate())}</small></button>`)}`);
    const free = freeSlots(day, +a.dur, a.id);
    $('[data-r="label"]', B).textContent = (free.length ? 'Free times ' : 'No free times ') + dayWord(day) + (hoursOn(day).open ? '' : ' (closed)');
    const nf = free.length ? null : nextFree(day, 0, +a.dur, a.id);
    $('[data-r="slots"]', B).innerHTML = String(free.length ? AK.html`${free.map(m => AK.html`<button type="button" class="chip slot-chip" data-move="${hm(m)}">${fmtT(m)}</button>`)}`
      : nf ? AK.html`<button type="button" class="btn soft" data-move="${hm(nf.start)}" data-move-date="${nf.date}">${icon('arrow-right')}${'Next free: ' + AK.fmt.day(nf.date) + ' ' + fmtT(nf.start)}</button>` : '');
  };
  B.addEventListener('click', e => {
    const dc = e.target.closest('[data-day]');
    if (dc) { day = dc.dataset.day; draw(); return; }
    const mv = e.target.closest('[data-move]');
    if (!mv) return;
    const nd = mv.dataset.moveDate || day, ns = mv.dataset.move;
    db.update('appts', id, {date: nd, start: ns, status: ACTIVE(a) ? a.status : 'booked', remindedAt: null, updated: nowIso()});
    AK.closeAll();
    AK.toast('Moved to ' + dayWord(nd) + ' at ' + fmtT(tm(ns)), {undo: () => db.undo()});
  });
  inp.addEventListener('change', () => { if (isDate(inp.value)) { day = inp.value; draw(); } });
  draw();
}
const i18nDay = d => (d === today() ? 'Today' : weekday(d));
function pickStatus(id) {
  const a = apptById(id);
  if (!a) return;
  const L = AK.sheet({title: 'Status', body: AK.html`<p class="pick-title">${a.name + ' · ' + dayWord(a.date) + ' ' + fmtT(tm(a.start))}</p><ul class="list status-list">${STATUS.map(s => AK.html`<li><button type="button" class="item${s.id === a.status ? ' is-current' : ''}" data-status="${s.id}" aria-pressed="${String(s.id === a.status)}">
    <span class="lead"><span class="sdot big s-${s.id}"></span></span><span class="body"><span class="title">${s.label}</span><span class="sub">${s.hint}</span></span>${s.id === a.status ? icon('check', 'chev') : ''}</button></li>`)}</ul>`});
  L.body.addEventListener('click', e => { const b = e.target.closest('[data-status]'); if (!b) return; L.close(); setStatus(id, b.dataset.status); });
}
function setStatus(id, to) {
  const a = apptById(id);
  if (!a || a.status === to || !ST[to]) return;
  db.update('appts', id, {status: to, updated: nowIso()});
  AK.toast(a.name + ': ' + ST[to].label, {undo: () => db.undo()});
}
function search() {
  const L = AK.sheet({title: 'Find a booking', focus: '#q-in', body: AK.html`<div class="search"><input type="search" id="q-in" placeholder="Name, phone, service or note" aria-label="Search bookings" autocomplete="off"></div><div class="search-out" data-q="out"></div>`});
  const B = L.body, inp = $('#q-in', B), out = $('[data-q="out"]', B);
  const draw = () => {
    const q = norm(inp.value), qd = digits(inp.value), t = today();
    if (!q) { out.innerHTML = String(AK.html`<p class="muted small search-hint">Type a few letters of a name, a phone number or a service.</p>`); return; }
    const hits = appts().filter(a => (qd.length >= 3 && digits(a.phone).indexOf(qd) > -1) || [a.name, svcName(a), a.notes].join(' ').toLowerCase().indexOf(q) > -1);
    const up = hits.filter(a => a.date >= t).sort(byTime).slice(0, 40), past = hits.filter(a => a.date < t).sort(byTime).reverse().slice(0, 40);
    out.innerHTML = String(!hits.length ? AK.html`<div class="empty">${AK.illus('search')}<p class="title">No bookings match</p><p class="text">Try fewer letters, or search by phone number.</p></div>`
      : AK.html`${up.length ? AK.html`<h3 class="mini-head">${'Coming up (' + up.length + ')'}</h3><ul class="list agenda">${up.map(a => apptRow(a, true))}</ul>` : ''}${past.length ? AK.html`<h3 class="mini-head">${'Earlier (' + past.length + ')'}</h3><ul class="list agenda">${past.map(a => apptRow(a, true))}</ul>` : ''}`);
  };
  inp.addEventListener('input', draw);
  draw();
}

/* ===== 12. SERVICE + BLOCKED TIME SHEETS ===== */
function openService(id) {
  const old = id ? services().find(s => s.id === id) : null;
  if (id && !old) return;
  const body = tpl('sheet-service'), F = fills(body);
  F.dur.innerHTML = String(AK.html`${DURS.map(m => AK.html`<option value="${m}">${durText(m)}</option>`)}`);
  F.cur.textContent = curSymbol();
  const L = AK.sheet({title: old ? 'Edit service' : 'New service', body, focus: old ? false : '#v-name', actions: [
    old ? {label: 'Delete', icon: 'trash', ghost: true, onClick: l => {
      AK.confirm('Delete ' + old.name + '?', {text: 'Bookings keep the name. You can undo right after.', ok: 'Delete', danger: true}).then(ok => { if (ok) { db.remove('services', old.id); l.close(); AK.toast('Service deleted', {undo: () => db.undo()}); } });
      return false;
    }} : null,
    {label: old ? 'Save' : 'Add service', primary: true, onClick: l => {
      const r = AK.validate($('form', l.body), {price: {min: 0, max: 9999999}});
      if (!r.ok) return false;
      const data = {name: r.values.name.trim().slice(0, 80), dur: +r.values.dur || 30, price: r.values.price == null ? null : cents(r.values.price)};
      if (old) db.update('services', old.id, data); else db.add('services', data);
      AK.toast(old ? 'Service saved' : 'Service added', 'ok');
    }}].filter(Boolean)});
  AK.fill($('form', L.body), old ? {name: old.name, dur: String(old.dur), price: old.price == null ? '' : old.price} : {dur: '30'});
}
function openBlock(id) {
  const old = id ? (cfg().blocks || []).find(b => b.id === id) : null;
  if (id && !old) return;
  const body = tpl('sheet-block'), F = fills(body), ws = weekStart(), ref = weekOf(today())[0];
  const days = old && old.days ? old.days.slice() : [1, 2, 3, 4, 5];
  F.daypick.innerHTML = String(AK.html`${[0, 1, 2, 3, 4, 5, 6].map(i => { const d = (i + ws) % 7; return AK.html`<button type="button" class="chip" data-day="${d}" aria-pressed="${String(days.indexOf(d) > -1)}">${weekday(addDays(ref, i))}</button>`; })}`);
  const L = AK.sheet({title: old ? 'Blocked time' : 'Block some time', body, focus: old ? false : '#b-label', actions: [
    old ? {label: 'Delete', icon: 'trash', ghost: true, onClick: l => { saveSettings({blocks: (cfg().blocks || []).filter(b => b.id !== old.id)}); l.close(); AK.toast('Blocked time removed', {undo: () => db.undo()}); return false; }} : null,
    {label: old ? 'Save' : 'Block it', primary: true, onClick: l => {
      const fm = $('form', l.body), r = AK.validate(fm, {to: {test: (v, all) => tm(v) > tm(all.from) || 'Must be after the start'}});
      const v = r.values, picked = $$('[data-day][aria-pressed="true"]', l.body).map(b => +b.dataset.day);
      if (v.repeat === 'once' && !isDate(v.date)) { AK.setError(fm.elements.date, 'Pick the day'); r.ok = false; }
      if (v.repeat !== 'once' && !picked.length) { AK.toast('Pick at least one day', 'warn'); r.ok = false; }
      if (!r.ok) return false;
      const b = {id: old ? old.id : AK.id(), label: v.label.trim().slice(0, 60), from: v.from, to: v.to, date: v.repeat === 'once' ? v.date : null, days: v.repeat === 'once' ? null : picked.sort(), sample: old ? !!old.sample : false};
      saveSettings({blocks: (cfg().blocks || []).filter(x => x.id !== b.id).concat([b])});
      AK.toast(old ? 'Blocked time saved' : 'Time blocked', 'ok');
    }}].filter(Boolean)});
  const fm = $('form', L.body), G = fills(L.body);
  AK.fill(fm, old ? {label: old.label, repeat: old.date ? 'once' : 'weekly', date: old.date || ui.date, from: old.from, to: old.to} : {repeat: 'weekly', date: ui.date, from: '12:00', to: '13:00'});
  const sync = () => { const once = fm.elements.repeat.value === 'once'; G.datef.hidden = !once; G.days.hidden = once; };
  fm.addEventListener('change', sync); sync();
}

/* ===== 13. REMINDER TEXT ===== */
function reminderText(a, template) {
  const b = bizName();
  let t = String(template || cfg().template || TEMPLATE);
  if (!b) t = t.replace(/[^.!?]*\{business\}[^.!?]*[.!?]\s*/g, '');
  const n = diffDays(today(), a.date);
  const day = n === 0 ? 'today' : n === 1 ? 'tomorrow' : 'on ' + toD(a.date).toLocaleDateString(LANG, {weekday: 'long', month: 'long', day: 'numeric'});
  return t.replace(/\{name\}/g, firstName(a.name)).replace(/\{service\}/g, svcName(a)).replace(/\{day\}/g, day).replace(/\{time\}/g, fmtT(tm(a.start)))
    .replace(/\{date\}/g, AK.fmt.date(a.date)).replace(/\{business\}/g, b).replace(/\s+/g, ' ').trim();
}
const smsHref = a => AK.sms(a.phone || '', reminderText(a));

/* ===== 14. PRINTED DAY SHEET ===== */
function printDay(date) {
  const doc = tpl('doc-day'), F = fills(doc), list = dayAppts(date), act = list.filter(ACTIVE), h = hoursOn(date);
  if (bizName()) F.biz.textContent = bizName();
  [['addr', 'address'], ['phone', 'phone'], ['email', 'email']].forEach(p => { const v = biz(p[1]); if (v) F[p[0]].textContent = v; else if (BAD[p[1]](F[p[0]].textContent.trim())) F[p[0]].remove(); });
  if (!$('[data-f="phone"],[data-f="email"]', F.contact)) F.contact.remove();
  F.day.textContent = toD(date).toLocaleDateString(LANG, {weekday: 'long', month: 'long', day: 'numeric', year: 'numeric'});
  F.hours.textContent = h.open ? 'Open ' + fmtT(h.from) + ' to ' + fmtT(h.to) : 'Closed';
  F.rows.innerHTML = String(list.length ? AK.html`${list.map(a => AK.html`<tr class="${ACTIVE(a) ? '' : 'off'}"><td class="nowrap">${fmtT(tm(a.start)) + ' to ' + fmtT(endOf(a))}</td><td>${a.name}${a.phone ? AK.html`<br><span class="muted">${a.phone}</span>` : ''}</td>
    <td>${svcName(a)}${a.notes ? AK.html`<br><span class="muted">${a.notes}</span>` : ''}</td><td class="num">${a.price != null && a.price !== '' ? money(a.price) : ''}</td><td>${ST[a.status].label}</td></tr>`)}` : AK.html`<tr><td colspan="5" class="muted">Nothing booked.</td></tr>`);
  F.count.textContent = act.length;
  F.time.textContent = durText(act.reduce((m, a) => m + (+a.dur || 0), 0));
  F.total.textContent = money(sum(act, a => a.price));
  if (bizName()) F.foot.textContent = 'Printed ' + AK.fmt.date(new Date()) + ' from ' + bizName() + ' Bookings.';
  AK.print('Day sheet ' + date, doc.firstElementChild);
}

/* ===== 15. FILES: CSV export, JSON backup and restore ===== */
const fileName = (what, ext) => SLUG + '-' + what + '-' + today() + '.' + (ext || 'csv');
const offerCopy = (text, what) => AK.toast(what + ' saved as a file', {action: {label: 'Copy instead', onClick: () => AK.copy(text, {done: 'Copied. Paste it where you need it.'})}});
function exportCsv() {
  const list = appts().slice().sort(byTime);
  if (!list.length) { AK.toast('No bookings to export yet.', 'warn'); return; }
  const rows = list.map(a => ({date: a.date, start: a.start, end: hm(Math.min(endOf(a), 1439)), name: a.name, phone: a.phone, service: svcName(a), minutes: a.dur, price: a.price == null ? '' : (+a.price).toFixed(2), status: ST[a.status].label, reminded: a.remindedAt ? String(a.remindedAt).slice(0, 10) : '', notes: a.notes}));
  const text = AK.csv.export(rows, fileName('bookings'), [{key: 'date', label: 'Date'}, {key: 'start', label: 'Start'}, {key: 'end', label: 'End'}, {key: 'name', label: 'Customer'}, {key: 'phone', label: 'Phone'}, {key: 'service', label: 'Service'},
    {key: 'minutes', label: 'Minutes'}, {key: 'price', label: 'Price'}, {key: 'status', label: 'Status'}, {key: 'reminded', label: 'Reminder sent'}, {key: 'notes', label: 'Notes'}]);
  offerCopy(text, 'Bookings');
}
function pickText(accept) {
  return new Promise(res => {
    const inp = AK.el('input', {type: 'file', accept, class: 'sr-only', tabindex: '-1', 'aria-hidden': 'true'});
    ($('dialog[open]') || D.body).append(inp);
    inp.addEventListener('change', () => { const f = inp.files[0]; inp.remove(); if (!f) { res(null); return; } f.text().then(res, () => res(null)); }, {once: true});
    inp.addEventListener('cancel', () => { inp.remove(); res(null); }, {once: true});
    inp.click();
  });
}
const backupText = () => JSON.stringify({app: SLUG, store: 'booking-manager', saved: nowIso(), data: S()}, null, 2);
function normalize(raw) {
  const d = raw && raw.data && raw.store ? raw.data : raw;
  if (!d || typeof d !== 'object' || !Array.isArray(d.appts)) return null;
  const str = v => (v == null ? '' : String(v)), mins = (v, def) => { const n = Math.round(+v); return n >= 5 && n <= 1440 ? n : def; };
  const out = {v: 1, appts: [], services: [], settings: Object.assign({}, d.settings && typeof d.settings === 'object' ? d.settings : {})};
  if (!Array.isArray(out.settings.blocks)) out.settings.blocks = [];
  out.settings.blocks = out.settings.blocks.filter(b => b && b.label && isTime(b.from) && isTime(b.to));
  (d.services || []).forEach(s => { if (s && s.name) out.services.push({id: str(s.id) || AK.id(), name: str(s.name).slice(0, 80), dur: mins(s.dur, 30), price: s.price == null || s.price === '' ? null : cents(s.price), sample: !!s.sample}); });
  d.appts.forEach(a => { if (!a || !a.name || !isDate(a.date) || !isTime(a.start)) return;
    out.appts.push({id: str(a.id) || AK.id(), name: str(a.name).slice(0, 80), phone: str(a.phone), serviceId: str(a.serviceId), service: str(a.service), date: a.date, start: hm(tm(a.start)), dur: mins(a.dur, 30),
      price: a.price == null || a.price === '' ? null : cents(a.price), status: ST[a.status] ? a.status : 'booked', notes: str(a.notes), created: str(a.created) || nowIso(), updated: str(a.updated), remindedAt: a.remindedAt || null, sample: !!a.sample}); });
  return out;
}
function restoreText(text) {
  let raw = null;
  try { raw = JSON.parse(text); } catch (e) { raw = null; }
  const data = normalize(raw);
  if (!data) { AK.toast('That is not a Bookings backup.', 'bad'); return Promise.resolve(false); }
  return AK.confirm('Restore this backup?', {text: 'It has ' + plural(data.appts.length, 'booking') + ' and ' + plural(data.services.length, 'service') + '. It replaces everything here now. You can undo right after.', ok: 'Restore'}).then(ok => {
    if (!ok) return false;
    db.reset(data); applyCurrency(); applyBrand();
    AK.toast('Backup restored', {type: 'ok', undo: () => { db.undo(); applyBrand(); }});
    return true;
  });
}
function pasteBackup() {
  AK.modal({title: 'Paste a backup', body: AK.html`<form class="form" novalidate><div class="field"><label for="pb-t">Backup text</label><textarea id="pb-t" name="text" class="paste-area" required placeholder="Paste the copied backup here"></textarea><p class="help">It starts with { and was made with Copy the backup.</p></div></form>`,
    actions: [{label: 'Cancel'}, {label: 'Restore', primary: true, onClick: l => { const r = AK.validate($('form', l.body)); if (!r.ok) return false; restoreText(r.values.text).then(ok => { if (ok) l.close(); }); return false; }}]});
}

/* ===== 16. SAMPLE DATA (clearly marked; "Clear sample data" removes only these) ===== */
function sampleData() {
  const t = today(), now = nowMin(), note = 'Sample booking. Clear it in Settings.';
  const sv = [['Sample service: quick visit', 30, 40], ['Sample service: standard visit', 60, 90], ['Sample service: long visit', 120, 220]].map(x => ({id: AK.id(), name: x[0], dur: x[1], price: x[2], sample: true}));
  let n = 0;
  const A = (date, start, si, status) => { n++; return {id: AK.id(), name: 'Sample customer ' + n, phone: '', serviceId: sv[si].id, service: sv[si].name, date, start, dur: sv[si].dur, price: sv[si].price, status, notes: note, created: nowIso(), updated: nowIso(), remindedAt: null, sample: true}; };
  const when = (date, start, dur) => (date < t || (date === t && tm(start) + dur <= now) ? 'done' : date === t && tm(start) <= now ? 'arrived' : 'booked');
  const list = [];
  const y = addDays(t, -1);
  list.push(A(y, '10:00', 1, 'done'), A(y, '15:00', 0, 'noshow'));
  if (hoursOn(t).open) [['09:00', 0], ['10:00', 1], ['13:00', 1], ['14:30', 2]].forEach(x => list.push(A(t, x[0], x[1], when(t, x[0], sv[x[1]].dur))));
  const opens = [];
  for (let i = 1; i < 15 && opens.length < 3; i++) if (hoursOn(addDays(t, i)).open) opens.push(addDays(t, i));
  const plan = [[['09:30', 1], ['11:00', 0]], [['10:00', 2]], [['14:00', 1]]];
  opens.forEach((d, i) => plan[i].forEach(x => list.push(A(d, x[0], x[1], 'booked'))));
  const firstUp = list.filter(a => a.status === 'booked').sort(byTime)[0];
  if (firstUp) firstUp.status = 'confirmed';
  return {services: sv, appts: list, block: {id: AK.id(), label: 'Sample block: lunch', from: '12:00', to: '12:30', days: [1, 2, 3, 4, 5], date: null, sample: true}};
}
function loadSample() {
  if (hasSample()) { AK.toast('Sample data is already here.', 'info'); return; }
  const s = sampleData();
  db.update(d => {
    d.services = (d.services || []).concat(s.services);
    d.appts = (d.appts || []).concat(s.appts);
    d.settings = Object.assign({}, d.settings || {});
    d.settings.blocks = (d.settings.blocks || []).concat([s.block]);
  });
  AK.toast('Sample data loaded', {type: 'ok', undo: () => db.undo()});
}
function clearSample() {
  AK.confirm('Clear the sample data?', {text: 'Removes only the sample services, bookings and blocked time. Anything you added stays.', ok: 'Clear it', danger: true}).then(ok => {
    if (!ok) return;
    db.update(d => {
      d.services = (d.services || []).filter(s => !s.sample);
      d.appts = (d.appts || []).filter(a => !a.sample);
      d.settings = Object.assign({}, d.settings || {});
      d.settings.blocks = (d.settings.blocks || []).filter(b => !b.sample);
    });
    AK.toast('Sample data cleared', {undo: () => db.undo()});
  });
}

/* ===== 17. ACTIONS (data-act="name" anywhere runs these) + keyboard shortcuts ===== */
function moveCal(dir) {
  ui.date = ui.view === 'day' ? addDays(ui.date, dir) : ui.view === 'week' ? addDays(ui.date, 7 * dir) : addMonths(ui.date, dir);
  ui.scroll = true;
  app.refresh();
}
AK.act({
  'new-appt': () => openAppt(),
  'new-at': el => openAppt(null, {date: el.dataset.date, start: el.dataset.time}),
  'new-on': el => openAppt(null, {date: el.dataset.date}),
  'open-appt': el => { if ($('dialog[open] .search-out') && el.closest('dialog')) { AK.closeAll(); setTimeout(() => openAppt(el.dataset.id), 60); } else openAppt(el.dataset.id); },
  'pick-status': el => pickStatus(el.dataset.id),
  'set-status': el => setStatus(el.dataset.id, el.dataset.status),
  reminded: el => { const id = el.dataset.id; if (id && apptById(id)) setTimeout(() => db.update('appts', id, {remindedAt: nowIso()}), 0); },
  'go-day': () => { ui.date = today(); ui.view = 'day'; prefs.set('view', 'day'); ui.scroll = true; app.go('/calendar'); },
  'open-day': el => { ui.date = el.dataset.date; ui.view = 'day'; prefs.set('view', 'day'); ui.scroll = true; app.refresh(); },
  'pick-day': el => { ui.date = el.dataset.date; ui.scroll = true; app.refresh(); },
  'cal-prev': () => moveCal(-1),
  'cal-next': () => moveCal(1),
  'cal-today': () => { ui.date = today(); ui.scroll = true; app.refresh(); },
  'print-day': () => printDay(ui.date),
  search: () => search(),
  'new-service': () => openService(),
  'open-service': el => openService(el.dataset.id),
  'new-block': () => openBlock(),
  'open-block': el => openBlock(el.dataset.id),
  'export-csv': () => exportCsv(),
  backup: () => { const t = backupText(); AK.download(fileName('backup', 'json'), t); offerCopy(t, 'Backup'); },
  'copy-backup': () => AK.copy(backupText(), {done: 'Backup copied. Paste it somewhere safe.'}),
  restore: () => pickText('.json,application/json,text/plain').then(t => { if (t != null) restoreText(t); }),
  'restore-paste': () => pasteBackup(),
  'load-sample': () => loadSample(),
  'clear-sample': () => clearSample(),
  erase: () => AK.confirm('Erase all bookings?', {text: 'Every booking is removed from this device. Services, hours and settings stay. You can undo right after.', ok: 'Erase', danger: true}).then(ok => {
    if (ok) { db.update(d => { d.appts = []; }); AK.toast('All bookings erased', {undo: () => db.undo()}); }
  })
});
D.addEventListener('keydown', e => {
  if (e.ctrlKey || e.metaKey || e.altKey || $('dialog[open]') || /^(INPUT|TEXTAREA|SELECT)$/.test(e.target.tagName || '') || e.target.isContentEditable) return;
  const k = e.key, onCal = app.route().tab === 'calendar';
  if (k === 'n' || k === 'N') { e.preventDefault(); openAppt(); }
  else if (k === '/') { e.preventDefault(); search(); }
  else if (onCal && (k === 'ArrowLeft' || k === 'ArrowRight') && !e.target.closest('[role=radiogroup],.strip')) { e.preventDefault(); moveCal(k === 'ArrowLeft' ? -1 : 1); }
  else if (onCal && (k === 't' || k === 'T')) { ui.date = today(); app.refresh(); }
});

/* read-only hooks for tests, and for a bot that needs to look at the data */
window.Bookings = Object.freeze({store: db, time: {today, addDays, addMonths, diffDays, weekOf, tm, hm, parseHours}, rules: {clashes, freeSlots, nextFree, hoursOn, lanes}, reminderText});

/* ===== 18. START ===== */
function applyBrand() {
  const name = bizName() || 'Bookings';
  $$('.brand .name').forEach(n => { n.textContent = name; });
  if (bizName()) $$('.brand .mark').forEach(m => { m.textContent = name.trim().charAt(0).toUpperCase(); });
}
applyCurrency();
const app = AK.app({
  title: bizName() || 'Bookings', icon: bizName() ? null : 'calendar', mark: bizName() || null,
  actions: [{icon: 'search', label: 'Find a booking', act: 'search'}],
  fab: {icon: 'plus', label: 'Book', act: 'new-appt'},
  tabs: [
    {id: 'today', label: 'Today', icon: 'home', render: renderToday},
    {id: 'calendar', label: 'Calendar', icon: 'calendar', render: renderCalendar},
    {id: 'services', label: 'Services', icon: 'tag', render: renderServices, fab: {icon: 'plus', label: 'New service', act: 'new-service'}},
    {id: 'settings', label: 'Settings', icon: 'settings', render: renderSettings, fab: false}
  ],
  onChange: tab => { if (tab === 'calendar') ui.scroll = true; }
});
db.subscribe(() => { applyCurrency(); app.refresh(); });
/* home-screen name and icon once the app is published (no offline cache, nothing leaves the device) */
AK.pwa({name: (bizName() ? bizName() + ' ' : '') + 'Bookings', short_name: 'Bookings'});
})();
