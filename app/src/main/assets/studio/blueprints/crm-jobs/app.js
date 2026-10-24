/* Job Board | blueprint crm-jobs | customers, jobs on a board, follow-up reminders, totals.
   Built on appkit (window.AK, see APPKIT.md). Every screen is a <template> in index.html; this file fills it in and wires it up.
   Data stays in this browser (AK.store 'crm-jobs'): {customers:[], jobs:[], followups:[], settings:{}}.
   MAP  1 setup  2 dates + money  3 data helpers  4 small renderers  5 Board  6 Today  7 Customers  8 Customer page
        9 Settings  10 job sheet  11 status sheet + quick add  12 customer sheet  13 follow-ups  14 printed job sheet
        15 files: CSV + backup  16 sample data  17 actions + shortcuts  18 start */
(() => {
'use strict';

/* ===== 1. SETUP ===== */
const D = document;
const $ = (s, r) => (r || D).querySelector(s);
const $$ = (s, r) => Array.from((r || D).querySelectorAll(s));
const FACTS = ($('#facts') || {dataset: {}}).dataset;
const SLUG = D.documentElement.dataset.app || 'job-board';
const STATUS = [
  {id: 'new', label: 'New', hint: 'Just came in'},
  {id: 'scheduled', label: 'Scheduled', hint: 'Has a day booked'},
  {id: 'progress', label: 'In progress', hint: 'Being worked on'},
  {id: 'waiting', label: 'Waiting', hint: 'On parts, a reply or a payment'},
  {id: 'done', label: 'Done', hint: 'Finished'}
];
const ST = {};
STATUS.forEach(s => { ST[s.id] = s; });
/* the checklist every new job starts with (the owner edits it in Settings) */
const STEPS = ['Confirm the details with the customer', 'Parts and materials ready', 'Do the work', 'Check the work', 'Tell the customer it is ready'];
const SETTINGS = {business: '', phone: '', email: '', address: '', currency: '', weekStart: '', doneDays: 30, steps: null};
const db = AK.store('crm-jobs', {v: 1, customers: [], jobs: [], followups: [], settings: {}});
const S = () => db.get();
const cfg = () => Object.assign({}, SETTINGS, S().settings || {});
const steps = () => Array.isArray(cfg().steps) ? cfg().steps : STEPS.map((text, i) => ({id: 's' + i, text}));

/* view choices that are not business data (board or list, sort order) live in their own small key */
const PREF_KEY = 'ak:' + SLUG + ':crm-jobs-view';
const prefs = (() => {
  let p = {};
  try { p = JSON.parse(localStorage.getItem(PREF_KEY)) || {}; } catch (e) { p = {}; }
  return {get: (k, d) => (k in p ? p[k] : d), set: (k, v) => { p[k] = v; try { localStorage.setItem(PREF_KEY, JSON.stringify(p)); } catch (e) { /* private mode */ } }};
})();
const ui = {view: prefs.get('view', 'board'), filter: 'all', q: '', custQ: '', custFilter: 'all', custSort: prefs.get('custSort', 'name'), boardLeft: 0, doneAll: false};

/* ===== 2. DATES + MONEY (dates are local 'YYYY-MM-DD' strings, money is rounded to cents) ===== */
const pad = n => String(n).padStart(2, '0');
const iso = d => d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
const toD = s => { const p = String(s).split('-'); return new Date(+p[0], p[1] - 1, +p[2]); };
const isDate = s => /^\d{4}-\d{2}-\d{2}$/.test(s || '') && !isNaN(toD(s));
const today = () => AK.today();
const addDays = (s, n) => { const d = toD(s); d.setDate(d.getDate() + n); return iso(d); };
const diffDays = (a, b) => Math.round((toD(b) - toD(a)) / 864e5);
const nowIso = () => new Date().toISOString();
function weekStart() {
  const w = String(cfg().weekStart);
  if (w === '0' || w === '1') return +w;
  const region = (navigator.language || 'en-US').split('-')[1] || 'US';
  return /^(US|CA|MX|BR|JP|IL|PH|KR|TW)$/i.test(region) ? 0 : 1;
}
const weekOf = s => { const d = toD(s); d.setDate(d.getDate() - (d.getDay() - weekStart() + 7) % 7); const a = iso(d); return [a, addDays(a, 6)]; };
const dayWord = s => { const t = AK.fmt.day(s); return /^(Today|Tomorrow|Yesterday)$/.test(t) ? t.toLowerCase() : t; };
const lateText = n => (n === 1 ? '1 day late' : n + ' days late');
const cents = v => Math.round((typeof v === 'number' ? v : AK.parseMoney(v)) * 100) / 100;
const sum = (arr, f) => arr.reduce((t, x) => t + Math.round((+f(x) || 0) * 100), 0) / 100;
const money = n => AK.fmt.money(n);
const money0 = n => AK.fmt.money(n, Math.abs(n) >= 1000 || Math.round(n) === n ? 0 : 2);
const plural = (n, one, many) => n + ' ' + (n === 1 ? one : many || one + 's');
function guessCurrency() {
  const r = ((navigator.language || 'en-US').split('-')[1] || 'US').toUpperCase();
  return {US: 'USD', CA: 'CAD', GB: 'GBP', AU: 'AUD', NZ: 'NZD', IN: 'INR', ZA: 'ZAR', MX: 'MXN', IE: 'EUR', DE: 'EUR', FR: 'EUR', ES: 'EUR', IT: 'EUR', NL: 'EUR', BE: 'EUR', AT: 'EUR', PT: 'EUR', FI: 'EUR'}[r] || 'USD';
}
const applyCurrency = () => { AK.currency = cfg().currency || guessCurrency(); };
function curSymbol() {
  try { return new Intl.NumberFormat(navigator.language || 'en-US', {style: 'currency', currency: AK.currency}).formatToParts(0).find(p => p.type === 'currency').value; } catch (e) { return '$'; }
}

/* ===== 3. DATA HELPERS ===== */
const list = k => S()[k] || [];
const cust = id => (id ? list('customers').find(c => c.id === id) || null : null);
const job = id => list('jobs').find(j => j.id === id) || null;
const fuOf = id => list('followups').find(f => f.id === id) || null;
const jobsOf = cid => (cid ? list('jobs').filter(j => j.customerId === cid) : []);
const digits = s => String(s || '').replace(/\D/g, '');
const norm = s => String(s || '').trim().toLowerCase();
const firstName = n => String(n || '').trim().split(/\s+/)[0] || '';
const isLate = j => j.status !== 'done' && isDate(j.due) && j.due < today();
const pendingFu = () => list('followups').filter(f => !f.done);
const hasSample = () => ['customers', 'jobs', 'followups'].some(k => list(k).some(x => x.sample));
const isEmpty = () => !list('customers').length && !list('jobs').length && !list('followups').length;
function allTags() {
  const m = {};
  list('customers').forEach(c => (c.tags || []).forEach(t => { m[t] = (m[t] || 0) + 1; }));
  return Object.keys(m).sort((a, b) => a.localeCompare(b)).map(t => ({t, n: m[t]}));
}
/* business facts: what the owner typed in Settings, else the facts the app filled in (fallback text like "Call us" is ignored) */
const BAD = {business: v => !v || /^\[/.test(v), phone: v => digits(v).length < 7, email: v => !/@/.test(v), address: v => !v || /^\[/.test(v)};
const biz = k => {
  const own = String(cfg()[k] || '').trim();
  if (own) return own;
  const f = String(FACTS[k] || '').trim();
  return BAD[k](f) ? '' : f;
};
const bizName = () => biz('business');
const hello = c => 'Hi ' + firstName(c.name) + ', ' + (bizName() ? 'this is ' + bizName() + '. ' : '');

function setStatus(j, to) {
  j.status = ST[to] ? to : 'new';
  j.doneAt = j.status === 'done' ? (j.doneAt || today()) : null;
  j.updated = nowIso();
}
const topRank = (d, col) => { const r = d.jobs.filter(x => x.status === col).map(x => +x.rank || 0); return r.length ? Math.min.apply(null, r) - 1 : 0; };
const nextNo = d => d.jobs.reduce((m, x) => Math.max(m, +x.no || 0), 0) + 1;
const byRank = (a, b) => (+a.rank || 0) - (+b.rank || 0) || String(a.due || '9999').localeCompare(String(b.due || '9999')) || String(b.created || '').localeCompare(String(a.created || ''));
const sortJobs = arr => arr.slice().sort((a, b) => (a.status === 'done' && b.status === 'done' ? String(b.doneAt || '').localeCompare(String(a.doneAt || '')) || byRank(a, b) : byRank(a, b)));
const recentDone = j => { const n = +cfg().doneDays; return !n || !j.doneAt || diffDays(j.doneAt, today()) <= n; };
function setJobStatus(id, to) {
  db.update(d => { const j = d.jobs.find(x => x.id === id); if (j && j.status !== to) { j.rank = topRank(d, to); setStatus(j, to); } });
}
function jobMatches(j, q, f) {
  const c = cust(j.customerId) || {};
  if (f === 'late' && !isLate(j)) return false;
  if (f === 'week') { const w = weekOf(today()); if (!isDate(j.due) || j.due < w[0] || j.due > w[1]) return false; }
  if (f === 'nodate' && (isDate(j.due) || j.status === 'done')) return false;
  if (f.indexOf('tag:') === 0 && (c.tags || []).indexOf(f.slice(4)) < 0) return false;
  if (!q) return true;
  const qd = digits(q);
  if (qd.length >= 3 && digits(c.phone).indexOf(qd) > -1) return true;
  return [j.title, j.notes, c.name, c.email, '#' + j.no].join(' ').toLowerCase().indexOf(q.toLowerCase()) > -1;
}
function stats() {
  const t = today(), w = weekOf(t), m = t.slice(0, 7), jobs = list('jobs');
  const open = jobs.filter(j => j.status !== 'done');
  const week = jobs.filter(j => isDate(j.due) && j.due >= w[0] && j.due <= w[1]);
  const doneM = jobs.filter(j => j.status === 'done' && String(j.doneAt || '').slice(0, 7) === m);
  const fu = pendingFu().filter(f => f.date <= t);
  return {open, openValue: sum(open, j => j.price), week, weekDone: week.filter(j => j.status === 'done').length,
    doneM, doneValue: sum(doneM, j => j.price), fu, fuLate: fu.filter(f => f.date < t), late: open.filter(isLate)};
}
function custInfo() {
  const m = {};
  list('customers').forEach(c => { m[c.id] = {open: 0, openValue: 0, done: 0, doneValue: 0, jobs: 0, fu: null, last: ''}; });
  list('jobs').forEach(j => {
    const x = m[j.customerId]; if (!x) return;
    x.jobs++; if (String(j.created || '') > x.last) x.last = String(j.created || '');
    if (j.status === 'done') { x.done++; x.doneValue += Math.round((+j.price || 0) * 100); } else { x.open++; x.openValue += Math.round((+j.price || 0) * 100); }
  });
  pendingFu().forEach(f => { const x = m[f.customerId]; if (x && (!x.fu || f.date < x.fu)) x.fu = f.date; });
  Object.keys(m).forEach(k => { m[k].openValue /= 100; m[k].doneValue /= 100; });
  return m;
}

/* ===== 4. SMALL RENDERERS ===== */
const icon = (n, c) => AK.icon(n, c);
const esc = AK.esc;
const frag = h => { const t = D.createElement('template'); t.innerHTML = String(h); return t.content; };
const fills = root => { const o = {}; $$('[data-f]', root).forEach(e => { o[e.dataset.f] = e; }); return o; };
const ST_SVG = {
  new: '<circle cx="8" cy="8" r="6" class="ring" stroke-dasharray="2.4 2.3"/>',
  scheduled: '<circle cx="8" cy="8" r="6" class="ring"/><path class="fill" d="M8 8V4.6A3.4 3.4 0 0 1 11.4 8z"/>',
  progress: '<circle cx="8" cy="8" r="6" class="ring"/><path class="fill" d="M8 4.6A3.4 3.4 0 0 1 8 11.4z"/>',
  waiting: '<circle cx="8" cy="8" r="6" class="ring"/><path class="bars" d="M6.4 5.9v4.2M9.6 5.9v4.2"/>',
  done: '<circle cx="8" cy="8" r="7.2" class="fill"/><path class="tick" d="M5 8.2l2 2 4-4.3"/>'
};
const stIcon = id => AK.raw('<svg class="st st-' + id + '" viewBox="0 0 16 16" aria-hidden="true">' + (ST_SVG[id] || ST_SVG.new) + '</svg>');
function hydrate(root) {
  $$('i[data-icon]', root).forEach(i => i.replaceWith(frag(icon(i.dataset.icon))));
  $$('i[data-st]', root).forEach(i => i.replaceWith(frag(stIcon(i.dataset.st))));
  return root;
}
const tpl = id => hydrate(D.getElementById(id).content.cloneNode(true));
const emptyState = o => AK.html`<div class="card"><div class="empty">${AK.illus(o.art || 'list')}<p class="title">${o.title}</p>${o.text ? AK.html`<p class="text">${o.text}</p>` : ''}
  ${o.btns ? AK.html`<div class="btns center-btns">${o.btns.map(b => AK.html`<button type="button" class="btn ${b.cls || ''}" data-act="${b.act}" data-to="${b.to || ''}">${b.icon ? icon(b.icon) : ''}${b.label}</button>`)}</div>` : ''}</div></div>`;
const smallEmpty = (text, btn) => AK.html`<div class="card small-empty">${icon('check-circle')}<span>${text}</span>${btn ? AK.html`<button type="button" class="btn ghost sm" data-act="${btn.act}">${btn.label}</button>` : ''}</div>`;
function dueChip(j) {
  if (j.status === 'done') return j.doneAt ? AK.html`<span class="due">${'Done ' + dayWord(j.doneAt)}</span>` : '';
  if (!isDate(j.due)) return '';
  const n = diffDays(today(), j.due);
  if (n < 0) return AK.html`<span class="due late">${icon('alert')}${lateText(-n)}</span>`;
  return AK.html`<span class="due${n === 0 ? ' today' : ''}">${icon('calendar')}${AK.fmt.day(j.due)}</span>`;
}
function jobCard(j) {
  const c = cust(j.customerId), ck = j.checklist || [], n = ck.filter(x => x.done).length;
  return AK.html`<p class="jc-cust">${c ? c.name : 'No customer'}</p><p class="kt">${j.title}</p>
    <div class="kmeta">${dueChip(j)}${ck.length ? AK.html`<span class="jc-ck">${icon('check-circle')}${n}/${ck.length}</span>` : ''}${j.price != null && j.price !== '' ? AK.html`<strong class="num jc-price">${money(j.price)}</strong>` : ''}</div>
    <button type="button" class="jc-move" data-act="pick-status" data-id="${j.id}" aria-label="${ST[j.status].label + '. Change status of ' + j.title}">${stIcon(j.status)}</button>`;
}
function jobRow(j, showCust) {
  const c = cust(j.customerId), ck = j.checklist || [], n = ck.filter(x => x.done).length, late = isLate(j);
  const when = j.status === 'done' ? (j.doneAt ? 'Done ' + dayWord(j.doneAt) : 'Done') : isDate(j.due) ? (late ? lateText(diffDays(j.due, today())) : AK.fmt.day(j.due)) : '';
  const sub = [showCust === false ? ST[j.status].label : (c ? c.name : 'No customer'), ck.length ? n + '/' + ck.length + ' steps' : ''].filter(Boolean).join(' · ');
  return AK.html`<li class="job-row${j.status === 'done' ? ' is-done' : ''}"><button type="button" class="st-btn" data-act="pick-status" data-id="${j.id}" aria-label="${ST[j.status].label + '. Change status of ' + j.title}">${stIcon(j.status)}</button>
    <button type="button" class="item" data-act="open-job" data-id="${j.id}"><span class="body"><span class="title">${j.title}</span><span class="sub">${sub}</span></span>
    <span class="trail"><span class="value">${j.price != null && j.price !== '' ? money(j.price) : ''}</span><span class="sub${late ? ' late' : ''}">${when}</span></span></button></li>`;
}
function fuCard(f, own) {
  const c = cust(f.customerId), j = f.jobId ? job(f.jobId) : null, n = diffDays(today(), f.date);
  const when = n < 0 ? AK.html`<span class="pill bad">${lateText(-n)}</span>` : n === 0 ? AK.html`<span class="pill warn">Today</span>` : AK.html`<span class="pill">${AK.fmt.day(f.date)}</span>`;
  const name = c ? c.name : 'Customer removed', what = f.note || (j ? 'About: ' + j.title : 'Check in');
  /* own = shown on the customer's own page, so lead with what to do instead of repeating their name */
  const head = own === true ? AK.html`<span class="lead">${icon('bell')}</span><span class="body"><span class="title">${what}</span><span class="sub">${j && f.note ? 'About: ' + j.title : AK.fmt.date(f.date, 'long')}</span></span>`
    : AK.html`${AK.avatar(name)}<span class="body"><span class="title">${name}</span><span class="sub">${what}</span></span>`;
  return AK.html`<li class="fu-card${n < 0 ? ' late' : ''}"><button type="button" class="fu-main" data-act="open-followup" data-id="${f.id}">${head}${when}</button>
    <div class="fu-acts">${c && c.phone ? AK.html`<a class="btn icon sm" href="${AK.tel(c.phone)}" aria-label="${'Call ' + name}">${icon('phone')}</a><a class="btn icon sm" href="${AK.sms(c.phone, hello(c))}" aria-label="${'Text ' + name}">${icon('message')}</a>` : ''}${c && c.email ? AK.html`<a class="btn icon sm" href="${AK.mail(c.email, bizName() || '', hello(c))}" aria-label="${'Email ' + name}">${icon('mail')}</a>` : ''}
      <span class="spacer"></span><button type="button" class="btn sm ghost" data-act="snooze" data-id="${f.id}">${icon('clock')}Later</button><button type="button" class="btn sm soft" data-act="fu-done" data-id="${f.id}">${icon('check')}Done</button></div></li>`;
}
function fuRow(f) {
  const c = cust(f.customerId);
  return AK.html`<li><button type="button" class="item" data-act="open-followup" data-id="${f.id}">${AK.avatar(c ? c.name : '?')}<span class="body"><span class="title">${c ? c.name : 'Customer removed'}</span><span class="sub">${f.note || 'Check in'}</span></span><span class="trail"><span class="sub">${AK.fmt.day(f.date)}</span></span></button></li>`;
}
function custRow(c, x) {
  const t = today(), fuDue = x.fu && x.fu <= t;
  const sub = [c.phone || c.email || 'No contact info', x.open ? plural(x.open, 'open job') : ''].filter(Boolean).join(' · ');
  const flag = fuDue ? AK.html`<span class="fu-flag${x.fu < t ? ' late' : ''}">${icon('bell')}${x.fu < t ? 'Late' : 'Due'}<span class="sr-only"> follow-up</span></span>` : '';
  const trail = AK.html`${x.openValue ? AK.html`<span class="value">${money0(x.openValue)}</span>` : ''}${flag || (x.openValue ? AK.html`<span class="sub">open</span>` : '')}`;
  return AK.html`<li><button type="button" class="item" data-act="open-customer" data-id="${c.id}">${AK.avatar(c.name)}<span class="body"><span class="title">${c.name}</span><span class="sub">${sub}</span>
    ${(c.tags || []).length ? AK.html`<span class="tags">${c.tags.slice(0, 4).map(g => AK.html`<span class="tag">${g}</span>`)}</span>` : ''}</span><span class="trail">${trail}</span>${icon('chevron-right', 'chev')}</button></li>`;
}

/* ===== 5. BOARD (kanban or list) ===== */
function renderBoard(page) {
  page.dataset.view = 'board';
  page.append(tpl('view-board'));
  const F = fills(page), st = stats();
  F.date.textContent = AK.fmt.date(new Date(), 'long');
  const pick = F.view.querySelector('input[value="' + ui.view + '"]');
  if (pick) pick.checked = true;
  F.view.addEventListener('change', e => { ui.view = e.target.value; prefs.set('view', ui.view); draw(); });
  if (isEmpty()) {
    F.welcome.hidden = false; F.tools.hidden = true; F.colnav.hidden = true; F.view.hidden = true;
    F.sub.textContent = 'Nothing here yet. Add your first job to get started.';
    return;
  }
  F.welcome.remove();
  F.samplenote.hidden = !hasSample();
  F.sub.textContent = st.open.length ? [plural(st.open.length, 'open job'), money0(st.openValue) + ' open value', st.late.length ? st.late.length + ' late' : ''].filter(Boolean).join(' · ') : 'No open jobs. Add one with New job.';
  F.search.value = ui.q;
  F.search.addEventListener('input', () => { ui.q = F.search.value; draw(); });
  AK.on(F.chips, 'click', '.chip', (e, b) => { ui.filter = b.dataset.f; draw(); });
  AK.on(F.colnav, 'click', 'button', (e, b) => {
    const k = $('.kanban', F.out), col = k && $('.kcol[data-col="' + b.dataset.col + '"]', k);
    if (!col) return;
    k.scrollTo({left: k.scrollLeft + col.getBoundingClientRect().left - k.getBoundingClientRect().left - (parseFloat(getComputedStyle(k).paddingLeft) || 0), behavior: AK.still() ? 'auto' : 'smooth'});
    $$('button', F.colnav).forEach(x => x.setAttribute('aria-current', String(x === b)));
  });
  draw();

  function drawChips() {
    const jobs = list('jobs'), n = f => jobs.filter(j => jobMatches(j, '', f)).length;
    const opts = [['all', 'All'], ['late', 'Late'], ['week', 'Due this week'], ['nodate', 'No date']].concat(allTags().map(t => ['tag:' + t.t, t.t]));
    if (!opts.some(o => o[0] === ui.filter)) ui.filter = 'all';
    F.chips.innerHTML = String(AK.html`${opts.map(o => AK.html`<button type="button" class="chip" data-f="${o[0]}" aria-pressed="${String(ui.filter === o[0])}">${o[0].indexOf('tag:') === 0 ? icon('tag') : ''}${o[1]}${o[0] === 'all' ? '' : AK.html` <span class="n">${n(o[0])}</span>`}</button>`)}`);
  }
  function draw() {
    drawChips();
    const q = ui.q.trim(), jobs = list('jobs').filter(j => jobMatches(j, q, ui.filter));
    F.colnav.hidden = ui.view !== 'board';
    if (!jobs.length && (q || ui.filter !== 'all')) {
      F.colnav.hidden = true;
      AK.render(F.out, emptyState({art: 'search', title: 'No jobs match', text: q ? 'Nothing matches "' + q + '". Try fewer letters or another filter.' : 'No jobs fit this filter right now.', btns: [{label: 'Show all jobs', act: 'clear-filter'}]}));
      return;
    }
    if (ui.view === 'list') drawList(F.out, jobs); else drawKanban(F.out, jobs, F.colnav);
  }
}
function drawKanban(out, jobs, nav) {
  let k = $('.kanban', out);
  if (!k) { out.replaceChildren(); k = D.createElement('div'); k.className = 'board'; out.append(k); }
  const shown = sortJobs(jobs.filter(j => j.status !== 'done' || recentDone(j)));
  k._jobs = jobs;
  AK.kanban(k, {
    columns: STATUS.map(s => ({id: s.id, title: s.label})),
    cards: shown.map(j => ({id: j.id, col: j.status, title: j.title, j})),
    card: c => jobCard(c.j), emptyText: 'Drop a job here',
    onMove: (c, to, idx, from) => moveCard(k, c.id, to, from),
    onOpen: c => { if (c) openJob(c.id); }
  });
  decorateBoard(k);
  if (!k._wired) {
    k._wired = 1;
    /* appkit redraws the board when a drag is cancelled, so add the extras back right after */
    ['pointerup', 'pointercancel', 'keyup'].forEach(t => k.addEventListener(t, () => setTimeout(() => decorateBoard(k), 0)));
    k.addEventListener('scroll', () => { ui.boardLeft = k.scrollLeft; syncNav(k, nav); }, {passive: true});
  }
  nav.innerHTML = String(AK.html`${STATUS.map(s => { const n = shown.filter(j => j.status === s.id).length; return AK.html`<button type="button" data-col="${s.id}" aria-label="${s.label + ': ' + plural(n, 'job')}"><span class="cn-top">${stIcon(s.id)}${n}</span><span class="cn-lbl">${s.label}</span></button>`; })}`);
  k.scrollLeft = ui.boardLeft;
  syncNav(k, nav);
}
function decorateBoard(k) {
  if (!k.isConnected) return;
  const jobs = k._jobs || [];
  $$('.kcol', k).forEach(col => {
    const id = col.dataset.col, head = $('.kcol-head', col);
    if (!head) return;
    if (!$('.st', head)) head.prepend(frag(stIcon(id)));
    let v = $('.kcol-val', head);
    if (!v) { v = D.createElement('span'); v.className = 'kcol-val num'; head.insertBefore(v, $('.n', head)); }
    const val = sum(jobs.filter(j => j.status === id && (id !== 'done' || recentDone(j))), j => j.price);
    v.textContent = val ? money0(val) : '';
    if (id === 'new' && !$('.kcol-add', col)) head.after(frag(AK.html`<button type="button" class="btn ghost sm kcol-add" data-act="quick-job">${icon('plus')}Add a job</button>`));
    if (id === 'done') {
      const older = jobs.filter(j => j.status === 'done' && !recentDone(j)).length;
      let m = $('.kcol-more', col);
      if (older && !m) { m = D.createElement('p'); m.className = 'kcol-more'; col.append(m); }
      if (m) m.textContent = older ? plural(older, 'older done job') + ' in the List view' : '';
    }
  });
}
function syncNav(k, nav) {
  const kl = k.getBoundingClientRect().left + (parseFloat(getComputedStyle(k).paddingLeft) || 0);
  let best = null, dist = 1e9;
  $$('.kcol', k).forEach(c => { const d = Math.abs(c.getBoundingClientRect().left - kl); if (d < dist) { dist = d; best = c.dataset.col; } });
  $$('button', nav).forEach(b => b.setAttribute('aria-current', String(b.dataset.col === best)));
}
function moveCard(k, id, to, from) {
  const ids = $$('.kcol[data-col="' + to + '"] .kcard', k).map(n => n.dataset.id);
  db.update(d => {
    const j = d.jobs.find(x => x.id === id);
    if (!j) return;
    if (j.status !== to) setStatus(j, to);
    ids.forEach((x, i) => { const y = d.jobs.find(z => z.id === x); if (y) y.rank = i; });
  });
  if (to !== from) AK.toast('Moved to ' + ST[to].label, {undo: () => db.undo()});
}
function drawList(out, jobs) {
  out.replaceChildren();
  if (!jobs.length) { AK.render(out, emptyState({art: 'list', title: 'No jobs yet', text: 'Add a job and it shows up here.', btns: [{label: 'New job', icon: 'plus', cls: 'primary', act: 'new-job'}]})); return; }
  STATUS.forEach(s => {
    let rows = sortJobs(jobs.filter(j => j.status === s.id));
    const all = rows.length, value = sum(rows, j => j.price);
    if (!all) return;
    let more = 0;
    if (s.id === 'done' && !ui.doneAll && all > 8) { more = all - 8; rows = rows.slice(0, 8); }
    out.append(frag(AK.html`<section class="section list-group"><div class="sec-head"><h2>${stIcon(s.id)}${s.label}<span class="count">${all}</span></h2><span class="muted small num">${value ? money0(value) : ''}</span></div>
      <ul class="list job-list">${rows.map(j => jobRow(j))}</ul>${more ? AK.html`<button type="button" class="btn ghost sm show-more" data-act="done-all">${'Show ' + more + ' more done'}</button>` : ''}</section>`));
  });
}

/* ===== 6. TODAY: follow-ups due, jobs due, the numbers ===== */
function renderToday(page) {
  page.dataset.view = 'today';
  page.append(tpl('view-today'));
  const F = fills(page), t = today(), st = stats(), w = weekOf(t);
  F.date.textContent = AK.fmt.date(new Date(), 'long');
  if (isEmpty()) {
    F.sub.textContent = 'Your day at a glance.';
    page.querySelector('.stats').remove(); page.querySelector('.today-grid').remove();
    page.append(frag(emptyState({art: 'inbox', title: 'Nothing due yet', text: 'Follow-ups and jobs that are due show up here, late ones in red.', btns: [{label: 'New job', icon: 'plus', cls: 'primary', act: 'new-job'}, {label: 'Try it with sample data', icon: 'zap', cls: 'soft', act: 'load-sample'}]})));
    return;
  }
  F.samplenote.hidden = !hasSample();
  const jobsDue = sortByDue(st.open.filter(j => isDate(j.due) && j.due <= t));
  const dueToday = jobsDue.filter(j => j.due === t).length;
  F.sub.textContent = [st.fuLate.length ? plural(st.fuLate.length, 'follow-up') + ' late' : '', st.late.length ? plural(st.late.length, 'job') + ' late' : '', dueToday ? plural(dueToday, 'job') + ' due today' : ''].filter(Boolean).join(' · ') || 'Nothing late. Nice and calm.';
  F['s-fu'].textContent = st.fu.length;
  F['s-fu-m'].textContent = st.fuLate.length ? st.fuLate.length + ' late' : st.fu.length ? 'All due today' : 'All caught up';
  F['s-week'].textContent = st.week.length;
  F['s-week-m'].textContent = st.week.length ? st.weekDone + ' done · ' + (st.week.length - st.weekDone) + ' to go' : AK.fmt.date(w[0], 'day') + ' to ' + AK.fmt.date(w[1], 'day');
  F['s-open'].textContent = money0(st.openValue);
  F['s-open-m'].textContent = plural(st.open.length, 'open job');
  F['s-done'].textContent = st.doneM.length;
  F['s-done-m'].textContent = st.doneValue ? money0(st.doneValue) + ' of work' : new Date().toLocaleDateString(navigator.language || 'en-US', {month: 'long'});
  const due = st.fu.slice().sort((a, b) => a.date.localeCompare(b.date));
  F.fu.innerHTML = String(due.length ? AK.html`<ul class="fu-list">${due.map(f => fuCard(f))}</ul>` : smallEmpty('No follow-ups due. You are all caught up.', {act: 'new-followup', label: 'Add one'}));
  const soon = pendingFu().filter(f => f.date > t && f.date <= addDays(t, 7)).sort((a, b) => a.date.localeCompare(b.date));
  F.soonwrap.hidden = !soon.length;
  F.soon.innerHTML = String(AK.html`${soon.map(fuRow)}`);
  F.due.innerHTML = String(jobsDue.length ? AK.html`<ul class="list job-list">${jobsDue.map(j => jobRow(j))}</ul>` : smallEmpty('No jobs due today.'));
  const later = sortByDue(st.open.filter(j => isDate(j.due) && j.due > t && j.due <= w[1]));
  F.weeksec.hidden = !later.length;
  F.week.innerHTML = String(AK.html`${later.map(j => jobRow(j))}`);
}
const sortByDue = arr => arr.slice().sort((a, b) => a.due.localeCompare(b.due) || byRank(a, b));

/* ===== 7. CUSTOMERS ===== */
function renderCustomers(page, ctx) {
  if (ctx.param) return renderCustomer(page, ctx);
  page.dataset.view = 'customers';
  page.append(tpl('view-customers'));
  const F = fills(page), cs = list('customers'), info = custInfo();
  const withOpen = cs.filter(c => info[c.id].open).length;
  F.sub.textContent = cs.length ? plural(cs.length, 'customer') + (withOpen ? ' · ' + withOpen + ' with open jobs' : '') : 'Nobody here yet';
  if (!cs.length) {
    F.search.closest('.toolbar').remove(); F.chips.remove();
    AK.render(F.out, emptyState({art: 'people', title: 'No customers yet', text: 'Add people as they call or walk in, or bring in a list from a spreadsheet.', btns: [{label: 'New customer', icon: 'plus', cls: 'primary', act: 'new-customer'}, {label: 'Try it with sample data', icon: 'zap', cls: 'soft', act: 'load-sample'}]}));
    return;
  }
  F.search.value = ui.custQ;
  F.sort.value = ui.custSort;
  F.search.addEventListener('input', () => { ui.custQ = F.search.value; draw(); });
  F.sort.addEventListener('change', () => { ui.custSort = F.sort.value; prefs.set('custSort', ui.custSort); draw(); });
  AK.on(F.chips, 'click', '.chip', (e, b) => { ui.custFilter = b.dataset.f; draw(); });
  const t = today();
  const test = (c, f) => f === 'all' || (f === 'open' && info[c.id].open > 0) || (f === 'fu' && info[c.id].fu && info[c.id].fu <= t) || (f.indexOf('tag:') === 0 && (c.tags || []).indexOf(f.slice(4)) > -1);
  const sorters = {
    name: (a, b) => a.name.localeCompare(b.name),
    recent: (a, b) => String(b.created || '').localeCompare(String(a.created || '')),
    open: (a, b) => info[b.id].openValue - info[a.id].openValue || info[b.id].open - info[a.id].open || a.name.localeCompare(b.name),
    fu: (a, b) => String(info[a.id].fu || '9999').localeCompare(String(info[b.id].fu || '9999')) || a.name.localeCompare(b.name)
  };
  draw();
  function draw() {
    const opts = [['all', 'All'], ['open', 'Open jobs'], ['fu', 'Follow-up due']].concat(allTags().map(x => ['tag:' + x.t, x.t]));
    if (!opts.some(o => o[0] === ui.custFilter)) ui.custFilter = 'all';
    F.chips.innerHTML = String(AK.html`${opts.map(o => AK.html`<button type="button" class="chip" data-f="${o[0]}" aria-pressed="${String(ui.custFilter === o[0])}">${o[0].indexOf('tag:') === 0 ? icon('tag') : ''}${o[1]}${o[0] === 'all' ? '' : AK.html` <span class="n">${cs.filter(c => test(c, o[0])).length}</span>`}</button>`)}`);
    const q = norm(ui.custQ), qd = digits(ui.custQ);
    const rows = cs.filter(c => test(c, ui.custFilter) && (!q || (qd.length >= 3 && digits(c.phone).indexOf(qd) > -1) ||
      [c.name, c.email, c.notes, (c.tags || []).join(' ')].join(' ').toLowerCase().indexOf(q) > -1)).sort(sorters[ui.custSort] || sorters.name);
    if (!rows.length) { AK.render(F.out, emptyState({art: 'search', title: 'Nobody matches', text: q ? 'Check the spelling, or search by phone number.' : 'No customers fit this filter.', btns: [{label: 'Show everyone', act: 'clear-cust-filter'}]})); return; }
    let ul = $('ul.list', F.out);
    if (!ul) { F.out.replaceChildren(); ul = D.createElement('ul'); ul.className = 'list'; ul.setAttribute('aria-label', 'Customers'); F.out.append(ul); }
    AK.list(ul, rows, c => custRow(c, info[c.id]));
  }
}

/* ===== 8. CUSTOMER PAGE (#/customers/ID) ===== */
function renderCustomer(page, ctx) {
  page.dataset.view = 'customer';
  const c = cust(ctx.param);
  if (!c) {
    AK.render(page, AK.html`<header class="page-head"><div><h1>Customer not found</h1><p class="sub">They may have been deleted.</p></div></header>${emptyState({art: 'people', title: 'Nobody here', text: 'This customer is not on this device any more.', btns: [{label: 'All customers', act: 'go', to: '/customers'}]})}`);
    return;
  }
  page.append(tpl('view-customer'));
  const F = fills(page), x = custInfo()[c.id], jobs = sortJobs(jobsOf(c.id)).sort((a, b) => (a.status === 'done') - (b.status === 'done'));
  ctx.title = c.name;
  F.avatar.innerHTML = String(AK.avatar(c.name, 'lg'));
  F.name.textContent = c.name;
  F.since.textContent = 'Customer since ' + AK.fmt.date(c.created || nowIso()) + ' · ' + plural(x.jobs, 'job');
  ['edit', 'newjob', 'addfu', 'del'].forEach(k => { F[k].dataset.id = c.id; });
  $$('[data-act="edit-customer"]', page).forEach(b => { b.dataset.id = c.id; });
  F.tags.innerHTML = String(AK.html`${(c.tags || []).map(g => AK.html`<span class="tag">${g}</span>`)}`);
  F.contact.innerHTML = String(AK.html`${c.phone ? AK.html`<a class="btn" href="${AK.tel(c.phone)}">${icon('phone')}<span>Call</span></a><a class="btn" href="${AK.sms(c.phone, hello(c))}">${icon('message')}<span>Text</span></a>`
    : AK.html`<button type="button" class="btn wide" data-act="edit-customer" data-id="${c.id}">${icon('phone')}<span>Add a phone number</span></button>`}
    ${c.email ? AK.html`<a class="btn" href="${AK.mail(c.email, bizName() || '', hello(c))}">${icon('mail')}<span>Email</span></a>` : AK.html`<button type="button" class="btn" data-act="edit-customer" data-id="${c.id}">${icon('mail')}<span>Add email</span></button>`}`);
  F['s-open'].textContent = money0(x.openValue);
  F['s-open-m'].textContent = plural(x.open, 'open job');
  F['s-done'].textContent = money0(x.doneValue);
  F['s-done-m'].textContent = plural(x.done, 'job') + ' done';
  const fus = pendingFu().filter(f => f.customerId === c.id).sort((a, b) => a.date.localeCompare(b.date));
  const doneFu = list('followups').filter(f => f.customerId === c.id && f.done).length;
  F.fu.innerHTML = String(fus.length ? AK.html`<ul class="fu-list">${fus.map(f => fuCard(f, true))}</ul>` : smallEmpty('No follow-ups planned' + (doneFu ? ' (' + doneFu + ' done before).' : '.'), {act: 'new-followup-for', label: 'Add one'}));
  const fb = $('.small-empty [data-act]', F.fu); if (fb) fb.dataset.id = c.id;
  F.jobcount.textContent = jobs.length ? plural(jobs.length, 'job') : '';
  F.jobs.innerHTML = String(jobs.length ? AK.html`<ul class="list job-list">${jobs.map(j => jobRow(j, false))}</ul>` : smallEmpty('No jobs yet.', {act: 'new-job-for', label: 'New job'}));
  const jb = $('.small-empty [data-act]', F.jobs); if (jb) jb.dataset.id = c.id;
  F.notes.textContent = c.notes || 'No notes yet. Tap Edit to add some.';
  F.notes.classList.toggle('none', !c.notes);
}

/* ===== 9. SETTINGS ===== */
function renderSettings(page) {
  page.dataset.view = 'settings';
  page.append(tpl('view-settings'));
  const F = fills(page), c = cfg();
  AK.fill(F.bizform, {business: biz('business'), phone: biz('phone'), email: biz('email'), address: biz('address')});
  F.bizform.addEventListener('submit', e => {
    e.preventDefault();
    const r = AK.validate(F.bizform);
    if (!r.ok) return;
    const v = r.values;
    saveSettings({business: v.business.trim(), phone: v.phone.trim(), email: v.email.trim(), address: v.address.trim()});
    applyBrand();
    AK.toast('Details saved', {type: 'ok', undo: () => { db.undo(); applyBrand(); }});
  });
  AK.fill(F.prefform, {currency: c.currency || '', weekStart: String(c.weekStart === undefined ? '' : c.weekStart), doneDays: String(c.doneDays)});
  F.prefform.addEventListener('change', () => {
    const v = AK.values(F.prefform);
    saveSettings({currency: v.currency, weekStart: v.weekStart, doneDays: +v.doneDays || 0});
    applyCurrency();
    AK.toast('Saved', 'ok');
  });
  const st = steps();
  F.stepcount.textContent = plural(st.length, 'step');
  F.steps.innerHTML = String(st.length ? AK.html`${st.map(x => AK.html`<li class="item" data-id="${x.id}"><button type="button" class="handle" aria-label="${'Move step: ' + x.text + ' (arrow keys)'}">${icon('grip')}</button><span class="body"><span class="title wrap">${x.text}</span></span>
    <button type="button" class="btn icon ghost sm" data-act="rename-step" data-id="${x.id}" aria-label="${'Rename step: ' + x.text}">${icon('edit')}</button><button type="button" class="btn icon ghost sm" data-act="del-step" data-id="${x.id}" aria-label="${'Remove step: ' + x.text}">${icon('trash')}</button></li>`)}`
    : AK.html`<li class="item"><span class="body"><span class="sub wrap">No steps. New jobs start with an empty checklist.</span></span></li>`);
  AK.drag(F.steps, {handle: '.handle', onSort: ids => saveSettings({steps: ids.map(id => steps().find(s => s.id === id)).filter(Boolean)})});
  F.stepform.addEventListener('submit', e => {
    e.preventDefault();
    const inp = F.stepform.elements.text, t = inp.value.trim();
    if (!t) { AK.setError(inp, 'Type a step first'); return; }
    saveSettings({steps: steps().concat([{id: AK.id(), text: t}])});
    setTimeout(() => { const n = $('[data-f="stepform"] input'); if (n) n.focus(); }, 0);
  });
  const kb = Math.max(1, Math.round(JSON.stringify(S()).length / 1024));
  F.storage.textContent = (db.persistent ? 'Saved on this device only. ' : 'Private mode: nothing is kept after this page closes. ') +
    [plural(list('customers').length, 'customer'), plural(list('jobs').length, 'job'), plural(list('followups').length, 'follow-up')].join(', ') + ', about ' + kb + ' KB.';
  const sample = hasSample();
  F.loadsample.hidden = sample; F.clearsample.hidden = !sample;
  if (sample) F.sampletext.textContent = 'Sample data is on. Clearing it removes only the sample customers, jobs and follow-ups. Anything you added stays.';
}
function saveSettings(patch) { db.set({settings: Object.assign({}, S().settings || {}, patch)}); }

/* ===== 10. JOB SHEET (new + edit) ===== */
function openJob(id, preset) {
  const old = id ? job(id) : null;
  if (id && !old) { AK.toast('That job is gone. It may have been deleted.', 'warn'); return; }
  const j = old ? JSON.parse(JSON.stringify(old)) : Object.assign({title: '', customerId: '', status: 'new', price: null, due: '', notes: '', checklist: steps().map(s => ({id: AK.id(), text: s.text, done: false}))}, preset || {});
  const ck = (j.checklist || []).map(x => Object.assign({}, x));
  const body = tpl('sheet-job'), F = fills(body), form = $('form', body);
  const cs = list('customers').slice().sort((a, b) => a.name.localeCompare(b.name));
  F.cust.innerHTML = String(AK.html`<option value="">No customer yet</option>${cs.map(c => AK.html`<option value="${c.id}">${c.name + (c.phone ? ' · ' + c.phone : '')}</option>`)}<option value="__new">+ New customer</option>`);
  F.cur.textContent = curSymbol();
  const L = AK.sheet({
    title: old ? 'Job' + (old.no ? ' #' + old.no : '') : 'New job', body, focus: old ? false : '#j-title',
    actions: [
      old ? {label: 'Delete', icon: 'trash', ghost: true, onClick: l => { deleteJob(id, l); return false; }} : null,
      {label: old ? 'Save' : 'Add job', primary: true, onClick: () => saveJob(old, form, ck)}
    ].filter(Boolean)
  });
  const B = L.body;
  AK.fill(form, {title: j.title, customerId: cust(j.customerId) ? j.customerId : '', status: j.status, price: j.price == null ? '' : j.price, due: j.due || '', notes: j.notes || ''});
  const F2 = fills(B);
  const toggleNew = () => {
    const on = form.elements.customerId.value === '__new';
    F2.newcust.hidden = !on; F2.newcust.disabled = !on;
    if (on) setTimeout(() => $('#j-ncname', B).focus(), 30);
  };
  form.elements.customerId.addEventListener('change', toggleNew);
  wireQuickDates(B);
  const drawCk = () => {
    const n = ck.filter(x => x.done).length;
    F2.cklist.innerHTML = String(AK.html`${ck.map((x, i) => AK.html`<li><label class="check"><input type="checkbox" data-ck="${i}" ${x.done ? 'checked' : ''}><span>${x.text}</span></label><button type="button" class="btn icon ghost sm" data-ckdel="${i}" aria-label="${'Remove step: ' + x.text}">${icon('x')}</button></li>`)}`);
    F2.ckcount.textContent = ck.length ? '· ' + n + ' of ' + ck.length + ' done' : '· none yet';
    F2.ckbar.hidden = !ck.length;
    F2.ckbar.style.setProperty('--value', ck.length ? n / ck.length * 100 : 0);
  };
  const addCk = () => { const t = F2.ckin.value.trim(); if (!t) { F2.ckin.focus(); return; } ck.push({id: AK.id(), text: t, done: false}); F2.ckin.value = ''; drawCk(); F2.ckin.focus(); };
  F2.cklist.addEventListener('change', e => { const i = e.target.dataset.ck; if (i != null) { ck[+i].done = e.target.checked; drawCk(); } });
  F2.cklist.addEventListener('click', e => { const b = e.target.closest('[data-ckdel]'); if (b) { ck.splice(+b.dataset.ckdel, 1); drawCk(); } });
  F2.ckbtn.addEventListener('click', addCk);
  F2.ckin.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); addCk(); } });
  drawCk();
  const oc = old ? cust(old.customerId) : null;
  if (!oc || !oc.phone) { F2.call.remove(); F2.text.remove(); }
  if (old) {
    const c = oc;
    F2.tools.hidden = false;
    F2.meta.textContent = [old.no ? 'Job #' + old.no : '', old.created ? 'Added ' + AK.fmt.date(old.created) : '', old.updated ? 'Changed ' + AK.fmt.ago(old.updated) : ''].filter(Boolean).join(' · ');
    F2.fu.addEventListener('click', () => { if (!c) { AK.toast('Pick a customer for this job first.', 'warn'); return; } openFollowup(null, {customerId: c.id, jobId: old.id}); });
    F2.print.addEventListener('click', () => printJob(old.id));
    if (c && c.phone) { F2.call.hidden = false; F2.call.href = AK.tel(c.phone); F2.text.hidden = false; F2.text.href = AK.sms(c.phone, hello(c)); }
    if (c) F2.open.addEventListener('click', () => app.go('/customers/' + c.id)); else F2.open.remove();
  }
}
function saveJob(old, form, ck) {
  const r = AK.validate(form, {price: {min: 0, max: 99999999, message: 'Enter a price, or leave it empty'}});
  const v = r.values, isNew = v.customerId === '__new';
  if (isNew && !String(v.ncName || '').trim()) { AK.setError($('#j-ncname', form), 'Type the customer name'); r.ok = false; }
  if (!r.ok) return false;
  let cid = isNew ? AK.id() : v.customerId || '';
  db.update(d => {
    if (isNew) d.customers.push({id: cid, name: v.ncName.trim(), phone: String(v.ncPhone || '').trim(), email: '', notes: '', tags: [], created: nowIso()});
    const data = {title: v.title.trim().slice(0, 140), customerId: cid, price: v.price == null ? null : cents(v.price), due: isDate(v.due) ? v.due : '', notes: v.notes.trim().slice(0, 4000), checklist: ck};
    if (old) {
      const x = d.jobs.find(y => y.id === old.id);
      if (!x) return;
      Object.assign(x, data, {updated: nowIso()});
      if (x.status !== v.status) { x.rank = topRank(d, v.status); setStatus(x, v.status); }
    } else {
      const x = Object.assign({id: AK.id(), no: nextNo(d), created: nowIso(), rank: topRank(d, v.status), doneAt: null}, data);
      setStatus(x, v.status);
      d.jobs.push(x);
    }
  });
  AK.toast(old ? 'Job saved' : 'Job added' + (v.status !== 'new' ? ' to ' + ST[v.status].label : ''), 'ok');
}
function deleteJob(id, layer) {
  const j = job(id);
  AK.confirm('Delete this job?', {text: j ? '"' + j.title + '" is removed. You can undo right after.' : '', ok: 'Delete', danger: true}).then(ok => {
    if (!ok) return;
    db.update(d => { d.jobs = d.jobs.filter(x => x.id !== id); d.followups.forEach(f => { if (f.jobId === id) f.jobId = ''; }); });
    if (layer) layer.close();
    AK.toast('Job deleted', {undo: () => db.undo()});
  });
}
function wireQuickDates(root) {
  AK.on(root, 'click', '.quick-dates [data-days]', (e, b) => {
    const inp = D.getElementById(b.closest('.quick-dates').dataset.for);
    if (!inp) return;
    inp.value = b.dataset.days === '' ? '' : addDays(today(), +b.dataset.days);
    inp.dispatchEvent(new Event('change', {bubbles: true}));
  });
}
function quickJob() {
  AK.prompt('Add a job', {label: 'What needs doing?', placeholder: 'A short name for the job', ok: 'Add'}).then(t => {
    if (!t) return;
    let id = '';
    db.update(d => { const x = {id: AK.id(), no: nextNo(d), title: String(t).slice(0, 140), customerId: '', price: null, due: '', notes: '', created: nowIso(), rank: topRank(d, 'new'), checklist: steps().map(s => ({id: AK.id(), text: s.text, done: false}))}; setStatus(x, 'new'); id = x.id; d.jobs.push(x); });
    AK.toast('Added to New', {action: {label: 'Add details', onClick: () => openJob(id)}});
  });
}

/* ===== 11. STATUS SHEET + QUICK ADD ===== */
function pickStatus(id) {
  const j = job(id);
  if (!j) return;
  const L = AK.sheet({title: 'Move job', body: AK.html`<p class="pick-title">${j.title}</p><ul class="list status-list">${STATUS.map(s => AK.html`<li><button type="button" class="item${s.id === j.status ? ' is-current' : ''}" data-status="${s.id}" aria-pressed="${String(s.id === j.status)}">
    <span class="lead">${stIcon(s.id)}</span><span class="body"><span class="title">${s.label}</span><span class="sub">${s.hint}</span></span>${s.id === j.status ? icon('check', 'chev') : ''}</button></li>`)}</ul>`});
  L.body.addEventListener('click', e => {
    const b = e.target.closest('[data-status]');
    if (!b) return;
    const to = b.dataset.status;
    L.close();
    if (to === j.status) return;
    setJobStatus(id, to);
    AK.toast('Moved to ' + ST[to].label, {undo: () => db.undo()});
  });
}
function quickAdd() {
  const opts = [['clipboard', 'A job', 'Something to do for a customer', 'new-job'], ['user', 'A customer', 'Name, phone, email and tags', 'new-customer'], ['bell', 'A follow-up', 'A reminder to get back to someone', 'new-followup']];
  AK.sheet({title: 'Quick add', body: AK.html`<ul class="list">${opts.map(o => AK.html`<li><button type="button" class="item" data-close data-act="${o[3]}"><span class="lead">${icon(o[0])}</span><span class="body"><span class="title">${o[1]}</span><span class="sub">${o[2]}</span></span>${icon('chevron-right', 'chev')}</button></li>`)}</ul>`});
}

/* ===== 12. CUSTOMER SHEET (new + edit) ===== */
function openCustomer(id) {
  const old = id ? cust(id) : null;
  if (id && !old) return;
  const body = tpl('sheet-customer'), form = $('form', body);
  const tags = (old ? old.tags || [] : []).slice(), known = allTags().map(t => t.t);
  const L = AK.sheet({
    title: old ? 'Edit customer' : 'New customer', body, focus: old ? false : '#c-name',
    actions: [
      old ? {label: 'Delete', icon: 'trash', ghost: true, onClick: l => { deleteCustomer(id, l); return false; }} : null,
      {label: old ? 'Save' : 'Add customer', primary: true, onClick: l => saveCustomer(old, form, tags, l)}
    ].filter(Boolean)
  });
  const F = fills(L.body);
  AK.fill(form, old || {});
  const drawTags = () => {
    const all = known.concat(tags.filter(t => known.indexOf(t) < 0));
    F.tags.innerHTML = String(AK.html`${all.map(t => AK.html`<button type="button" class="chip" data-tag="${t}" aria-pressed="${String(tags.indexOf(t) > -1)}">${t}</button>`)}`);
  };
  F.tags.addEventListener('click', e => {
    const b = e.target.closest('[data-tag]');
    if (!b) return;
    const t = b.dataset.tag, i = tags.indexOf(t);
    if (i > -1) tags.splice(i, 1); else tags.push(t);
  });
  const addTag = () => {
    const t = F.tagin.value.trim().replace(/\s+/g, ' ').slice(0, 24);
    if (!t) { F.tagin.focus(); return; }
    const same = known.concat(tags).find(x => norm(x) === norm(t)) || t;
    if (tags.indexOf(same) < 0) tags.push(same);
    if (known.indexOf(same) < 0) known.push(same);
    F.tagin.value = ''; drawTags();
  };
  F.tagbtn.addEventListener('click', addTag);
  F.tagin.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); addTag(); } });
  drawTags();
}
function saveCustomer(old, form, tags, layer) {
  const r = AK.validate(form);
  if (!r.ok) return false;
  const v = r.values, data = {name: v.name.trim().slice(0, 80), phone: v.phone.trim().slice(0, 40), email: v.email.trim().slice(0, 120), notes: v.notes.trim().slice(0, 4000), tags: tags.slice()};
  const twin = list('customers').find(c => (!old || c.id !== old.id) && ((digits(data.phone).length >= 7 && digits(c.phone) === digits(data.phone)) || (data.email && norm(c.email) === norm(data.email)) || norm(c.name) === norm(data.name)));
  const commit = () => {
    let nid = '';
    if (old) db.update('customers', old.id, data);
    else { nid = db.add('customers', Object.assign({created: nowIso()}, data)).id; }
    layer.close();
    AK.toast(old ? 'Customer saved' : 'Customer added', old ? 'ok' : {type: 'ok', action: {label: 'Open', onClick: () => app.go('/customers/' + nid)}});
  };
  if (!twin) { commit(); return false; }
  AK.confirm('Already a customer like this', {text: twin.name + (twin.phone ? ' · ' + twin.phone : '') + ' is already here. Save anyway?', ok: 'Save anyway'}).then(ok => { if (ok) commit(); });
  return false;
}
function deleteCustomer(id, layer) {
  const c = cust(id);
  if (!c) return;
  const n = jobsOf(id).length;
  AK.confirm('Delete ' + c.name + '?', {text: (n ? 'Their ' + plural(n, 'job') + ' stay on the board without a customer. ' : '') + 'Their follow-ups are removed. You can undo right after.', ok: 'Delete', danger: true}).then(ok => {
    if (!ok) return;
    db.update(d => {
      d.customers = d.customers.filter(x => x.id !== id);
      d.followups = d.followups.filter(f => f.customerId !== id);
      d.jobs.forEach(j => { if (j.customerId === id) j.customerId = ''; });
    });
    if (layer) layer.close();
    if (app.route().param === id) app.go('/customers', true);
    AK.toast(c.name + ' deleted', {undo: () => db.undo()});
  });
}

/* ===== 13. FOLLOW-UPS ===== */
function openFollowup(id, preset) {
  const old = id ? fuOf(id) : null;
  if (id && !old) return;
  const cs = list('customers').slice().sort((a, b) => a.name.localeCompare(b.name));
  if (!cs.length) { AK.toast('Add a customer first, then plan a follow-up.', 'warn'); openCustomer(); return; }
  const f = old || Object.assign({customerId: '', jobId: '', date: addDays(today(), 1), note: ''}, preset || {});
  const body = tpl('sheet-followup'), form = $('form', body), F = fills(body);
  F.cust.innerHTML = String(AK.html`<option value="">Choose a customer</option>${cs.map(c => AK.html`<option value="${c.id}">${c.name}</option>`)}`);
  const L = AK.sheet({
    title: old ? 'Follow-up' : 'New follow-up', body, focus: old ? false : (f.customerId ? '#f-note' : '#f-cust'),
    actions: [
      old ? {label: 'Delete', icon: 'trash', ghost: true, onClick: l => {
        AK.confirm('Delete this follow-up?', {ok: 'Delete', danger: true}).then(ok => { if (ok) { db.remove('followups', old.id); l.close(); AK.toast('Follow-up deleted', {undo: () => db.undo()}); } });
        return false;
      }} : null,
      old ? {label: 'Mark done', icon: 'check', onClick: () => { fuDone(old.id); }} : null,
      {label: old ? 'Save' : 'Add follow-up', primary: true, onClick: l => {
        const r = AK.validate(l.body.querySelector('form'));
        if (!r.ok) return false;
        const v = {customerId: r.values.customerId, jobId: r.values.jobId || '', date: r.values.date, note: r.values.note.trim()};
        if (old) db.update('followups', old.id, v); else db.add('followups', Object.assign({done: false, created: nowIso()}, v));
        AK.toast(old ? 'Follow-up saved' : 'Follow-up set for ' + dayWord(v.date), 'ok');
      }}
    ].filter(Boolean)
  });
  const B = L.body, fm = $('form', B), F2 = fills(B);
  const jobsFor = () => {
    const js = sortJobs(jobsOf(fm.elements.customerId.value));
    F2.job.innerHTML = String(AK.html`<option value="">Not about a job</option>${js.map(x => AK.html`<option value="${x.id}">${x.title + ' (' + ST[x.status].label + ')'}</option>`)}`);
    F2.jobfield.hidden = !js.length;
  };
  AK.fill(fm, {customerId: f.customerId, note: f.note, date: f.date});
  jobsFor();
  fm.elements.jobId.value = f.jobId || '';
  fm.elements.customerId.addEventListener('change', jobsFor);
  wireQuickDates(B);
}
function fuDone(id) {
  db.update('followups', id, {done: true, doneAt: today()});
  AK.toast('Follow-up done', {type: 'ok', undo: () => db.undo()});
}
function snooze(id) {
  const f = fuOf(id);
  if (!f) return;
  const opts = [[1, 'Tomorrow'], [3, 'In 3 days'], [7, 'Next week'], [14, 'In 2 weeks'], [30, 'In a month']];
  const L = AK.sheet({title: 'Follow up later', body: AK.html`<ul class="list">${opts.map(o => AK.html`<li><button type="button" class="item" data-days="${o[0]}"><span class="lead">${icon('clock')}</span><span class="body"><span class="title">${o[1]}</span><span class="sub">${AK.fmt.date(addDays(today(), o[0]), 'long')}</span></span></button></li>`)}</ul>
    <div class="field pick-date"><label for="snz-date">Or pick a date</label><div class="row"><input id="snz-date" type="date" min="${today()}"><button type="button" class="btn primary" data-pick>Move</button></div></div>`});
  L.body.addEventListener('click', e => {
    const b = e.target.closest('[data-days],[data-pick]');
    if (!b) return;
    const inp = $('#snz-date', L.body), d = b.dataset.days ? addDays(today(), +b.dataset.days) : inp.value;
    if (!isDate(d)) { AK.setError(inp, 'Pick a date first'); return; }
    L.close();
    db.update('followups', id, {date: d});
    AK.toast('Moved to ' + dayWord(d), {undo: () => db.undo()});
  });
}

/* ===== 14. PRINTED JOB SHEET (letterhead from Settings, else the business facts) ===== */
function printJob(id) {
  const j = job(id);
  if (!j) return;
  const doc = tpl('doc-job'), F = fills(doc), c = cust(j.customerId);
  if (bizName()) F.biz.textContent = bizName();
  [['addr', 'address'], ['phone', 'phone'], ['email', 'email']].forEach(p => {
    const v = biz(p[1]);
    if (v) F[p[0]].textContent = v; else if (BAD[p[1]](F[p[0]].textContent.trim())) F[p[0]].remove();
  });
  if (!$('[data-f="phone"],[data-f="email"]', F.contact)) F.contact.remove();
  F.no.textContent = j.no ? 'No. ' + j.no : '';
  F.printed.textContent = AK.fmt.date(new Date());
  F.cust.innerHTML = c ? String(AK.html`${c.name}${c.phone ? AK.html`<br>${c.phone}` : ''}${c.email ? AK.html`<br>${c.email}` : ''}`) : 'Not set';
  F.status.textContent = ST[j.status].label;
  F.due.textContent = isDate(j.due) ? AK.fmt.date(j.due) : 'Not set';
  F.price.textContent = j.price != null && j.price !== '' ? money(j.price) : 'Not set';
  F.title.textContent = j.title;
  F.steps.innerHTML = String(AK.html`${(j.checklist || []).map(s => AK.html`<div class="check-row"><span class="box${s.done ? ' on' : ''}"></span><span>${s.text}</span></div>`)}`);
  if (j.notes) F.notes.textContent = j.notes; else F.noteswrap.remove();
  if (bizName()) F.foot.textContent = 'Thank you for choosing ' + bizName() + '.';
  AK.print('Job sheet ' + (j.no ? '#' + j.no + ' ' : '') + j.title, doc.firstElementChild);
}

/* ===== 15. FILES: CSV in and out, JSON backup and restore ===== */
const fileName = (what, ext) => SLUG + '-' + what + '-' + today() + '.' + (ext || 'csv');
const offerCopy = (text, what) => AK.toast(what + ' saved as a file', {action: {label: 'Copy instead', onClick: () => AK.copy(text, {done: 'Copied. Paste it where you need it.'})}});
function pickText(accept) {
  return new Promise(res => {
    const inp = AK.el('input', {type: 'file', accept, class: 'sr-only', tabindex: '-1', 'aria-hidden': 'true'});
    ($('dialog[open]') || D.body).append(inp);
    inp.addEventListener('change', () => { const f = inp.files[0]; inp.remove(); if (!f) { res(null); return; } f.text().then(res, () => res(null)); }, {once: true});
    inp.addEventListener('cancel', () => { inp.remove(); res(null); }, {once: true});
    inp.click();
  });
}
const hk = s => norm(s).replace(/[^a-z0-9]/g, '');
function col(row, keys) {
  const ks = Object.keys(row);
  for (let i = 0; i < keys.length; i++) {
    const f = ks.find(x => hk(x) === keys[i]) || ks.find(x => hk(x).indexOf(keys[i]) === 0);
    if (f && String(row[f]).trim()) return String(row[f]).trim();
  }
  return '';
}
function looseDate(s) {
  s = String(s || '').trim();
  let m = s.match(/^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})/);
  if (m) return okDate(+m[1], +m[2], +m[3]);
  m = s.match(/^(\d{1,2})[-/.](\d{1,2})[-/.](\d{2,4})$/);
  if (!m) return '';
  const y = +m[3] < 100 ? 2000 + +m[3] : +m[3], a = +m[1], b = +m[2];
  const us = /-(US|CA|PH)$/i.test(navigator.language || 'en-US');
  return a > 12 ? okDate(y, b, a) : b > 12 ? okDate(y, a, b) : us ? okDate(y, a, b) : okDate(y, b, a);
}
const okDate = (y, mo, d) => { const x = new Date(y, mo - 1, d); return x.getMonth() === mo - 1 && x.getDate() === d ? iso(x) : ''; };
function statusFrom(s) {
  const t = norm(s);
  if (!t) return 'new';
  const hit = STATUS.find(x => x.id === t || norm(x.label) === t);
  if (hit) return hit.id;
  if (/progress|doing|working|started|active/.test(t)) return 'progress';
  if (/done|complete|closed|finish|paid/.test(t)) return 'done';
  if (/wait|hold|parts|pending/.test(t)) return 'waiting';
  if (/sched|booked|planned|date/.test(t)) return 'scheduled';
  return 'new';
}
function exportCustomers() {
  const info = custInfo(), cs = list('customers');
  if (!cs.length) { AK.toast('No customers to export yet.', 'warn'); return; }
  const rows = cs.slice().sort((a, b) => a.name.localeCompare(b.name)).map(c => ({name: c.name, phone: c.phone, email: c.email, tags: (c.tags || []).join('; '), notes: c.notes,
    open: info[c.id].open, openValue: info[c.id].openValue.toFixed(2), done: info[c.id].done, doneValue: info[c.id].doneValue.toFixed(2), nextFollowUp: info[c.id].fu || '', added: String(c.created || '').slice(0, 10)}));
  const text = AK.csv.export(rows, fileName('customers'), [{key: 'name', label: 'Name'}, {key: 'phone', label: 'Phone'}, {key: 'email', label: 'Email'}, {key: 'tags', label: 'Tags'}, {key: 'notes', label: 'Notes'},
    {key: 'open', label: 'Open jobs'}, {key: 'openValue', label: 'Open value'}, {key: 'done', label: 'Done jobs'}, {key: 'doneValue', label: 'Done value'}, {key: 'nextFollowUp', label: 'Next follow-up'}, {key: 'added', label: 'Added'}]);
  offerCopy(text, 'Customers');
}
function exportJobs() {
  const js = list('jobs');
  if (!js.length) { AK.toast('No jobs to export yet.', 'warn'); return; }
  const rows = js.slice().sort((a, b) => (+a.no || 0) - (+b.no || 0)).map(j => { const c = cust(j.customerId) || {}, ck = j.checklist || [];
    return {no: j.no || '', title: j.title, customer: c.name || '', phone: c.phone || '', status: ST[j.status].label, price: j.price == null ? '' : (+j.price).toFixed(2), due: j.due || '', doneOn: j.doneAt || '', steps: ck.length ? ck.filter(x => x.done).length + '/' + ck.length : '', notes: j.notes || '', added: String(j.created || '').slice(0, 10)}; });
  const text = AK.csv.export(rows, fileName('jobs'), [{key: 'no', label: 'Job number'}, {key: 'title', label: 'Title'}, {key: 'customer', label: 'Customer'}, {key: 'phone', label: 'Phone'}, {key: 'status', label: 'Status'},
    {key: 'price', label: 'Price'}, {key: 'due', label: 'Due'}, {key: 'doneOn', label: 'Done on'}, {key: 'steps', label: 'Checklist'}, {key: 'notes', label: 'Notes'}, {key: 'added', label: 'Added'}]);
  offerCopy(text, 'Jobs');
}
function importCustomers(rows) {
  if (!rows || !rows.length) { AK.toast('That file has no rows to bring in.', 'warn'); return 0; }
  const have = list('customers'), phones = new Set(have.map(c => digits(c.phone)).filter(x => x.length >= 7)), mails = new Set(have.map(c => norm(c.email)).filter(Boolean)), names = new Set(have.map(c => norm(c.name)));
  const add = [];
  let skipped = 0;
  rows.forEach(r => {
    let name = col(r, ['name', 'fullname', 'customer', 'customername', 'contact', 'client', 'company']);
    if (!name) name = [col(r, ['firstname', 'first', 'givenname']), col(r, ['lastname', 'last', 'surname', 'familyname'])].filter(Boolean).join(' ');
    if (!name) { skipped++; return; }
    const phone = col(r, ['phone', 'mobile', 'cell', 'tel', 'telephone']), email = col(r, ['email', 'mail', 'emailaddress']);
    if ((digits(phone).length >= 7 && phones.has(digits(phone))) || (email && mails.has(norm(email))) || (!phone && !email && names.has(norm(name)))) { skipped++; return; }
    const tags = col(r, ['tags', 'tag', 'labels', 'group']).split(/[;,|]/).map(t => t.trim().slice(0, 24)).filter(Boolean);
    add.push({id: AK.id(), name: name.slice(0, 80), phone, email, notes: col(r, ['notes', 'note', 'comments', 'memo']), tags, created: nowIso()});
    if (digits(phone).length >= 7) phones.add(digits(phone));
    if (email) mails.add(norm(email));
    names.add(norm(name));
  });
  if (!add.length) { AK.toast(skipped ? 'Everyone in that file is already here.' : 'No names found. The file needs a Name column.', 'warn'); return 0; }
  db.update(d => { d.customers = d.customers.concat(add); });
  AK.toast('Added ' + plural(add.length, 'customer') + (skipped ? ', skipped ' + skipped : ''), {type: 'ok', undo: () => db.undo()});
  return add.length;
}
function importJobs(rows) {
  if (!rows || !rows.length) { AK.toast('That file has no rows to bring in.', 'warn'); return 0; }
  let added = 0, skipped = 0, newCust = 0;
  db.update(d => {
    rows.forEach(r => {
      const title = col(r, ['title', 'job', 'jobtitle', 'description', 'work', 'service', 'task']);
      if (!title) { skipped++; return; }
      const cname = col(r, ['customer', 'customername', 'client', 'name', 'contact']), phone = col(r, ['phone', 'mobile', 'cell', 'tel']);
      let c = cname ? d.customers.find(x => norm(x.name) === norm(cname)) : null;
      if (!c && digits(phone).length >= 7) c = d.customers.find(x => digits(x.phone) === digits(phone));
      if (!c && cname) { c = {id: AK.id(), name: cname.slice(0, 80), phone, email: '', notes: '', tags: [], created: nowIso()}; d.customers.push(c); newCust++; }
      const priceText = col(r, ['price', 'amount', 'total', 'value', 'cost']);
      const x = {id: AK.id(), no: nextNo(d), title: title.slice(0, 140), customerId: c ? c.id : '', price: priceText ? cents(priceText) : null, due: looseDate(col(r, ['due', 'duedate', 'date', 'when'])),
        notes: col(r, ['notes', 'note', 'comments']), created: nowIso(), checklist: [], rank: 0, doneAt: null};
      setStatus(x, statusFrom(col(r, ['status', 'stage', 'state'])));
      if (x.status === 'done') x.doneAt = looseDate(col(r, ['doneon', 'done', 'completed', 'finished'])) || x.due || today();
      x.rank = topRank(d, x.status);
      d.jobs.push(x);
      added++;
    });
  });
  if (!added) { db.undo(); AK.toast('No jobs found. The file needs a Title column.', 'warn'); return 0; }
  AK.toast('Added ' + plural(added, 'job') + (newCust ? ' and ' + plural(newCust, 'new customer') : '') + (skipped ? ', skipped ' + skipped : ''), {type: 'ok', undo: () => db.undo()});
  return added;
}
function pasteCsv() {
  AK.modal({title: 'Paste CSV text', body: AK.html`<form class="form" novalidate><div class="field"><span class="label" id="pc-k">What is in it?</span>
    <div class="seg block" role="radiogroup" aria-labelledby="pc-k"><label><input type="radio" name="kind" value="customers" checked><span>Customers</span></label><label><input type="radio" name="kind" value="jobs"><span>Jobs</span></label></div></div>
    <div class="field"><label for="pc-t">CSV text, with the column names on the first line</label><textarea id="pc-t" name="text" class="paste-area" required placeholder="Name,Phone,Email"></textarea></div></form>`,
  actions: [{label: 'Cancel'}, {label: 'Bring it in', primary: true, onClick: l => {
    const r = AK.validate($('form', l.body));
    if (!r.ok) return false;
    const rows = AK.csv.parse(r.values.text);
    const n = r.values.kind === 'jobs' ? importJobs(rows) : importCustomers(rows);
    return n ? undefined : false;
  }}]});
}
const backupText = () => JSON.stringify({app: SLUG, store: 'crm-jobs', saved: nowIso(), data: S()}, null, 2);
function normalize(raw) {
  const d = raw && raw.data && raw.store ? raw.data : raw;
  if (!d || typeof d !== 'object' || (!Array.isArray(d.customers) && !Array.isArray(d.jobs))) return null;
  const str = v => (v == null ? '' : String(v));
  const out = {v: 1, customers: [], jobs: [], followups: [], settings: Object.assign({}, d.settings && typeof d.settings === 'object' ? d.settings : {})};
  (d.customers || []).forEach(c => { if (c && c.name) out.customers.push({id: str(c.id) || AK.id(), name: str(c.name).slice(0, 120), phone: str(c.phone), email: str(c.email), notes: str(c.notes), tags: Array.isArray(c.tags) ? c.tags.map(str).filter(Boolean) : [], created: str(c.created) || nowIso(), sample: !!c.sample}); });
  (d.jobs || []).forEach((j, i) => { if (!j || !j.title) return;
    const x = {id: str(j.id) || AK.id(), no: +j.no || i + 1, title: str(j.title).slice(0, 200), customerId: str(j.customerId), price: j.price == null || j.price === '' ? null : cents(j.price), due: isDate(j.due) ? j.due : '', notes: str(j.notes),
      checklist: Array.isArray(j.checklist) ? j.checklist.filter(s => s && s.text).map(s => ({id: str(s.id) || AK.id(), text: str(s.text), done: !!s.done})) : [], rank: +j.rank || 0, created: str(j.created) || nowIso(), updated: str(j.updated), doneAt: isDate(j.doneAt) ? j.doneAt : null, sample: !!j.sample};
    x.status = ST[j.status] ? j.status : 'new'; if (x.status === 'done' && !x.doneAt) x.doneAt = x.due || today(); if (x.status !== 'done') x.doneAt = null;
    out.jobs.push(x); });
  (d.followups || []).forEach(f => { if (f && f.customerId && isDate(f.date)) out.followups.push({id: str(f.id) || AK.id(), customerId: str(f.customerId), jobId: str(f.jobId), date: f.date, note: str(f.note), done: !!f.done, doneAt: f.doneAt || null, created: str(f.created) || nowIso(), sample: !!f.sample}); });
  return out;
}
function restoreText(text) {
  let raw = null;
  try { raw = JSON.parse(text); } catch (e) { raw = null; }
  const data = normalize(raw);
  if (!data) { AK.toast('That is not a Job Board backup.', 'bad'); return Promise.resolve(false); }
  return AK.confirm('Restore this backup?', {text: 'It has ' + [plural(data.customers.length, 'customer'), plural(data.jobs.length, 'job'), plural(data.followups.length, 'follow-up')].join(', ') + '. It replaces everything here now. You can undo right after.', ok: 'Restore'}).then(ok => {
    if (!ok) return false;
    db.reset(data);
    applyCurrency(); applyBrand();
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
  const t = today(), note = 'Sample data. Clear it in Settings.';
  const C = (n, tags) => ({id: AK.id(), name: 'Sample customer ' + n, phone: '', email: '', notes: note, tags: tags || [], created: nowIso(), sample: true});
  const cs = [C('A', ['Regular']), C('B'), C('C', ['Regular', 'Fleet']), C('D')];
  const ck = done => steps().map((s, i) => ({id: AK.id(), text: s.text, done: i < done}));
  const J = (title, ci, status, price, due, done, doneAt) => ({id: AK.id(), title: 'Sample job: ' + title, customerId: cs[ci].id, status, price, due: due == null ? '' : addDays(t, due), notes: note, checklist: ck(done), created: nowIso(), updated: nowIso(), doneAt: doneAt == null ? null : addDays(t, doneAt), rank: 0, sample: true});
  const jobs = [J('first visit and quote', 0, 'new', null, 3, 0), J('quote to write up', 3, 'new', 60, null, 0), J('booked repair', 1, 'scheduled', 240, 2, 1),
    J('work in progress', 2, 'progress', 180, 0, 3), J('waiting on parts', 3, 'waiting', 320, -1, 2), J('finished this week', 1, 'done', 150, -2, 5, -1), J('finished today', 2, 'done', 95, 0, 5, 0)];
  const F = (ci, due, text, ji) => ({id: AK.id(), customerId: cs[ci].id, jobId: ji == null ? '' : jobs[ji].id, date: addDays(t, due), note: 'Sample follow-up: ' + text, done: false, created: nowIso(), sample: true});
  return {customers: cs, jobs, followups: [F(3, -2, 'ask how the last job went', 6), F(0, 0, 'send the quote', 0), F(2, 4, 'check in next week')]};
}
function loadSample() {
  if (hasSample()) { AK.toast('Sample data is already here.', 'info'); return; }
  const s = sampleData();
  db.update(d => {
    let no = nextNo(d);
    s.jobs.forEach((j, i) => { j.no = no++; j.rank = i - 50; });
    d.customers = d.customers.concat(s.customers); d.jobs = d.jobs.concat(s.jobs); d.followups = d.followups.concat(s.followups);
  });
  AK.toast('Sample data loaded', {type: 'ok', undo: () => db.undo()});
}
function clearSample() {
  AK.confirm('Clear the sample data?', {text: 'Removes only the sample customers, jobs and follow-ups. Anything you added stays.', ok: 'Clear it', danger: true}).then(ok => {
    if (!ok) return;
    db.update(d => {
      const gone = new Set(d.customers.filter(c => c.sample).map(c => c.id));
      d.customers = d.customers.filter(c => !c.sample);
      d.jobs = d.jobs.filter(j => !j.sample);
      d.jobs.forEach(j => { if (gone.has(j.customerId)) j.customerId = ''; });
      d.followups = d.followups.filter(f => !f.sample && !gone.has(f.customerId));
    });
    AK.toast('Sample data cleared', {undo: () => db.undo()});
  });
}

/* ===== 17. ACTIONS (data-act="name" anywhere runs these) + keyboard shortcuts ===== */
AK.act({
  go: el => app.go(el.dataset.to),
  jump: el => { const t = D.getElementById(el.dataset.to); if (t) t.scrollIntoView({behavior: AK.still() ? 'auto' : 'smooth', block: 'start'}); },
  'quick-add': () => quickAdd(),
  'quick-job': () => quickJob(),
  'new-job': () => openJob(),
  'new-job-for': el => openJob(null, {customerId: el.dataset.id}),
  'open-job': el => openJob(el.dataset.id),
  'pick-status': el => pickStatus(el.dataset.id),
  'clear-filter': () => { ui.q = ''; ui.filter = 'all'; app.refresh(); },
  'clear-cust-filter': () => { ui.custQ = ''; ui.custFilter = 'all'; app.refresh(); },
  'done-all': () => { ui.doneAll = true; app.refresh(); },
  'new-customer': () => openCustomer(),
  'open-customer': el => app.go('/customers/' + el.dataset.id),
  'edit-customer': el => openCustomer(el.dataset.id || app.route().param),
  'delete-customer': el => deleteCustomer(el.dataset.id || app.route().param),
  'new-followup': () => openFollowup(null, app.route().tab === 'customers' && app.route().param ? {customerId: app.route().param} : null),
  'new-followup-for': el => openFollowup(null, {customerId: el.dataset.id || app.route().param}),
  'open-followup': el => openFollowup(el.dataset.id),
  'fu-done': el => fuDone(el.dataset.id),
  snooze: el => snooze(el.dataset.id),
  'export-customers': () => exportCustomers(),
  'export-jobs': () => exportJobs(),
  'import-customers': () => pickText('.csv,text/csv,text/plain').then(t => { if (t != null) importCustomers(AK.csv.parse(t)); }),
  'import-jobs': () => pickText('.csv,text/csv,text/plain').then(t => { if (t != null) importJobs(AK.csv.parse(t)); }),
  'paste-csv': () => pasteCsv(),
  backup: () => { const t = backupText(); AK.download(fileName('backup', 'json'), t); offerCopy(t, 'Backup'); },
  'copy-backup': () => AK.copy(backupText(), {done: 'Backup copied. Paste it somewhere safe.'}),
  restore: () => pickText('.json,application/json,text/plain').then(t => { if (t != null) restoreText(t); }),
  'restore-paste': () => pasteBackup(),
  'load-sample': () => loadSample(),
  'clear-sample': () => clearSample(),
  erase: () => AK.confirm('Erase everything?', {text: 'Every customer, job and follow-up is removed from this device. Your details and checklist stay. You can undo right after.', ok: 'Erase', danger: true}).then(ok => {
    if (ok) { db.update(d => { d.customers = []; d.jobs = []; d.followups = []; }); AK.toast('Everything erased', {undo: () => db.undo()}); }
  }),
  'rename-step': el => { const s = steps().find(x => x.id === el.dataset.id); if (s) AK.prompt('Rename step', {value: s.text, label: 'Step'}).then(t => { if (t) saveSettings({steps: steps().map(x => (x.id === s.id ? {id: x.id, text: String(t).slice(0, 80)} : x))}); }); },
  'del-step': el => { saveSettings({steps: steps().filter(x => x.id !== el.dataset.id)}); AK.toast('Step removed', {undo: () => db.undo()}); }
});
D.addEventListener('keydown', e => {
  if (e.ctrlKey || e.metaKey || e.altKey || $('dialog[open]') || /^(INPUT|TEXTAREA|SELECT)$/.test((e.target.tagName || '')) || e.target.isContentEditable) return;
  if (e.key === 'n' || e.key === 'N') { e.preventDefault(); openJob(); }
  else if (e.key === '/') { const s = $('.page input[type=search]'); if (s) { e.preventDefault(); s.focus(); } }
});

/* read-only hooks for tests, and for a bot that needs to look at the data */
window.JobBoard = Object.freeze({store: db, dates: {today, addDays, diffDays, weekOf, looseDate}, money: {cents, sum}});

/* ===== 18. START ===== */
function applyBrand() {
  const name = bizName() || 'Job Board';
  $$('.brand .name').forEach(n => { n.textContent = name; });
  if (bizName()) $$('.brand .mark').forEach(m => { m.textContent = name.trim().charAt(0).toUpperCase(); });
}
applyCurrency();
const app = AK.app({
  title: bizName() || 'Job Board', icon: bizName() ? null : 'clipboard', mark: bizName() || null,
  actions: [{icon: 'zap', label: 'Quick add', act: 'quick-add'}],
  fab: {icon: 'plus', label: 'New job', act: 'new-job'},
  tabs: [
    {id: 'board', label: 'Board', icon: 'grid', render: renderBoard},
    {id: 'today', label: 'Today', icon: 'bell', render: renderToday, fab: {icon: 'plus', label: 'Follow-up', act: 'new-followup'}},
    {id: 'customers', label: 'Customers', icon: 'users', render: renderCustomers, fab: {icon: 'plus', label: 'New customer', act: 'new-customer'}},
    {id: 'settings', label: 'Settings', icon: 'settings', render: renderSettings, fab: false}
  ]
});
const badge = () => app.badge('today', stats().fu.length);
db.subscribe(() => { applyCurrency(); app.refresh(); badge(); });
badge();
/* home-screen name and icon once the app is published (no offline cache, nothing leaves the device) */
AK.pwa({name: (bizName() ? bizName() + ' ' : '') + 'Job Board', short_name: 'Job Board'});
})();
