/* Inspection Checklist | fast one-handed inspections with photos and a customer-ready report. Built on appkit (window.AK, see APPKIT.md).
   Screens are <template id="view-..."> blocks in index.html; this file copies them into the page and fills them.
   Sections: 1 facts + saved data  2 checklists that ship  3 status maths  4 small helpers  5 app shell  6 inspections list
   7 starting an inspection  8 running an inspection (tap, swipe, notes, photos, signature)  9 report  10 sharing
   11 checklist editor  12 settings + backup  13 actions  14 sample data  15 boot */
(() => {
'use strict';

/* ===== 1. FACTS + SAVED DATA ===== */
// The owner's facts arrive as tokens inside <template id="facts"> in index.html; blanks come as visible fallbacks ("Call us", "[...]").
const FACTS = (() => {
  const o = {}, t = document.getElementById('facts');
  if (t) t.content.querySelectorAll('[data-k]').forEach(e => { o[e.dataset.k] = e.textContent.trim(); });
  return o;
})();
const REAL = { business: v => !!v && !/^\[.*\]$/.test(v), phone: v => /\d{3}/.test(v || ''), email: v => /\S@\S/.test(v || ''), address: v => !!v && !/^\[.*\]$/.test(v) };
const factOf = k => (REAL[k](FACTS[k]) ? FACTS[k] : '');
const DEF_SET = { business: '', phone: '', email: '', address: '', inspector: '', showOk: true, autoNote: true };
const db = AK.store('inspection-checklist', { v: 1, inspections: [], templates: starterTemplates(), settings: {} });
// Photos and signatures live in their own store, so a tap on a check never re-saves megabytes of pictures.
const pics = AK.store('inspection-photos', { p: {} });
const set = () => Object.assign({}, DEF_SET, db.get('settings') || {});
const all = () => db.get('inspections') || [];
const templates = () => db.get('templates') || [];
const photo = id => (pics.get('p') || {})[id] || '';
const biz = () => {
  const s = set();
  return { name: s.business || FACTS.business || '', phone: s.phone || factOf('phone'), email: s.email || factOf('email'), address: s.address || factOf('address') };
};
let quiet = 0;
const silently = fn => { quiet++; try { return fn(); } finally { quiet--; } };

/* ===== 2. CHECKLISTS THAT SHIP (generic; the owner edits them in Checklists) ===== */
function starterTemplates() {
  const T = (name, subjectLabel, refLabel, secs) => ({ id: AK.id(), name, subjectLabel, refLabel,
    sections: secs.map(s => ({ id: AK.id(), name: s[0], items: s[1].map(n => ({ id: AK.id(), name: n })) })) });
  return [
    T('Vehicle inspection', 'Vehicle', 'Mileage', [
      ['Brakes', ['Front brake pads', 'Rear brake pads or shoes', 'Rotors and drums', 'Brake fluid level and condition', 'Brake lines and hoses', 'Parking brake']],
      ['Tires and wheels', ['Tread depth', 'Tire pressure', 'Sidewalls and wear pattern', 'Spare tire and jack', 'Wheel nuts']],
      ['Fluids', ['Engine oil level and condition', 'Coolant level', 'Transmission fluid', 'Power steering fluid', 'Washer fluid', 'Leaks under the vehicle']],
      ['Lights', ['Headlights, low and high beam', 'Brake lights', 'Turn signals and hazards', 'Tail and license plate lights', 'Dashboard warning lights']],
      ['Belts and hoses', ['Drive belts', 'Radiator hoses', 'Heater hoses']],
      ['Battery', ['Battery test', 'Terminals and cables', 'Battery hold-down']],
      ['Suspension and steering', ['Shocks and struts', 'Ball joints and tie rod ends', 'Bushings', 'Steering play', 'CV axles and boots']],
      ['Exhaust', ['Leaks and noise', 'Hangers and heat shields', 'Muffler and pipes']],
      ['Wipers and glass', ['Wiper blades', 'Windshield', 'Washer jets']]
    ]),
    T('Home or equipment inspection', 'Item or site', 'Reference or serial', [
      ['General condition', ['Overall condition', 'Damage, wear or corrosion', 'Labels and markings readable']],
      ['Safety', ['Guards and covers in place', 'Shut-off or emergency stop works', 'Fire extinguisher present and charged', 'Trip and fall hazards']],
      ['Electrical', ['Cords, plugs and outlets', 'Switches and controls', 'Lighting']],
      ['Water and fluids', ['Leaks or stains', 'Fluid levels', 'Filters']],
      ['Moving parts', ['Belts and chains', 'Bearings and noise', 'Lubrication']],
      ['Operation', ['Starts and runs normally', 'Unusual noise or vibration', 'Gauges and displays']]
    ])
  ];
}

/* ===== 3. STATUS MATHS ===== */
const ST = { ok: 'OK', attn: 'Needs attention', urgent: 'Urgent', na: 'Not applicable' };
function counts(x) {
  const n = { ok: 0, attn: 0, urgent: 0, na: 0, none: 0, total: 0 };
  (x.sections || []).forEach(s => s.items.forEach(it => { n.total++; if (ST[it.status]) n[it.status]++; else n.none++; }));
  n.done = n.total - n.none;
  n.pct = n.total ? Math.floor(n.done / n.total * 100) : 0;
  return n;
}
const flagged = (x, st) => [].concat(...x.sections.map(s => s.items.filter(it => it.status === st).map(it => ({ sec: s.name, it }))));

/* ===== 4. SMALL HELPERS ===== */
const $ = (s, r) => (r || document).querySelector(s);
const $$ = (s, r) => Array.from((r || document).querySelectorAll(s));
function tpl(id) {
  const f = document.getElementById(id).content.cloneNode(true);
  $$('[data-i]', f).forEach(e => { const t = document.createElement('template'); t.innerHTML = String(AK.icon(e.dataset.i, e.dataset.cls)); e.replaceWith(t.content); });
  return f;
}
const pick = (o, p) => p.split('.').reduce((a, k) => (a == null ? a : a[k]), o);
function bind(root, data) {
  // keys this call does not mention are left alone, so several small binds can share one screen
  $$('[data-t]', root).forEach(e => { const v = pick(data, e.dataset.t); if (v !== undefined) e.textContent = v == null ? '' : v; });
  $$('[data-if]', root).forEach(e => { const k = e.dataset.if, no = k[0] === '!', v = pick(data, no ? k.slice(1) : k); if (v !== undefined) e.hidden = no ? !!v : !v; });
}
const slot = (root, name) => root.querySelector('[data-slot="' + name + '"]');
const plural = (n, one, many) => n + ' ' + (n === 1 ? one : many || one + 's');
const debounce = (fn, ms) => { let t = 0; const d = (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; d.flush = () => { clearTimeout(t); fn(); }; return d; };
const match = (text, qq) => { const ws = String(qq || '').toLowerCase().split(/\s+/).filter(Boolean); const h = String(text || '').toLowerCase(); return ws.length > 0 && ws.every(w => h.indexOf(w) > -1); };
const titleOf = x => x.subject || x.customer.name || 'New inspection';
const buzz = () => { try { if (navigator.vibrate) navigator.vibrate(8); } catch (e) {} };
const copyOf = o => JSON.parse(JSON.stringify(o));
const isVehicle = x => /vehicle|car|truck|van/i.test((x.subjectLabel || '') + ' ' + (x.templateName || x.name || ''));

/* ===== 5. APP SHELL (the AK.app call itself is in 15. BOOT, after every helper exists) ===== */
let app = null;
let q = '', chipF = 'all';
function startApp() {
  app = AK.app({
    title: biz().name || 'Inspections', icon: 'clipboard',
    fab: { icon: 'plus', label: 'New inspection', act: 'new-insp' },
    tabs: [
      { id: 'list', label: 'Inspections', icon: 'clipboard', render: renderList },
      { id: 'templates', label: 'Checklists', icon: 'list', render: renderTemplates, fab: false },
      { id: 'settings', label: 'Settings', icon: 'settings', render: renderSettings, fab: false }
    ]
  });
  db.subscribe(() => { badge(); if (!quiet) app.refresh(); });
}
function badge() { app.badge('list', all().filter(x => x.status !== 'done').length); }
function brand() { const n = biz().name || 'Inspections'; $$('.app .brand .name').forEach(e => { e.textContent = n; }); }

/* ===== 6. INSPECTIONS LIST (history) ===== */
function renderList(page, ctx) {
  if (ctx.param) { const p = ctx.param.split('/'); return p[1] === 'report' ? renderReport(page, ctx, p[0]) : renderRun(page, ctx, p[0]); }
  const xs = all(), m = AK.today().slice(0, 7);
  const open = xs.filter(x => x.status !== 'done'), doneM = xs.filter(x => x.status === 'done' && (x.date || '').slice(0, 7) === m);
  const left = open.reduce((a, x) => a + counts(x).none, 0);
  page.append(tpl('view-list'));
  bind(page, {
    empty: !xs.length, hasSample: xs.some(x => x.sample), todayText: AK.fmt.date(new Date(), 'long'),
    summary: xs.length ? plural(open.length, 'in progress', 'in progress') + ' · ' + plural(xs.length - open.length, 'finished', 'finished') : 'Nothing here yet',
    openN: String(open.length), openMeta: open.length ? plural(left, 'check') + ' left' : 'All finished',
    doneN: String(doneM.length), doneMeta: plural(doneM.length, 'report') + ' ready',
    urgentN: String(xs.filter(x => (x.date || '').slice(0, 7) === m).reduce((a, x) => a + counts(x).urgent, 0))
  });
  if (!xs.length) return;
  const search = $('#insp-q', page), chips = slot(page, 'chips'), out = slot(page, 'list');
  search.value = q;
  const draw = () => {
    const c = { all: xs.length, open: open.length, done: xs.length - open.length, urgent: xs.filter(x => counts(x).urgent).length };
    AK.render(chips, [['all', 'All'], ['open', 'In progress'], ['done', 'Finished'], ['urgent', 'With urgent']].map(f => AK.html`<button type="button" class="chip" data-chip="${f[0]}" aria-pressed="${chipF === f[0]}">${f[1]} <span class="n">${c[f[0]]}</span></button>`));
    const shown = xs.filter(x => (chipF === 'all' || (chipF === 'open' ? x.status !== 'done' : chipF === 'done' ? x.status === 'done' : counts(x).urgent > 0)) &&
      (!q.trim() || match([x.customer.name, x.customer.phone, x.subject, x.ref, x.inspector, x.templateName].join(' '), q)))
      .sort((a, b) => (b.date || '').localeCompare(a.date || '') || (b.created || 0) - (a.created || 0));
    if (!shown.length) { AK.render(out, AK.html`<div class="card">${AK.empty({ art: 'search', title: q.trim() ? 'No matches' : 'Nothing here', text: q.trim() ? 'Try part of a name, a phone number or the vehicle.' : 'Pick another filter to see more.' })}</div>`); return; }
    const groups = [['In progress', shown.filter(x => x.status !== 'done')], ['Finished', shown.filter(x => x.status === 'done')]].filter(g => g[1].length);
    AK.render(out, groups.map(g => AK.html`<section class="grp"><h2 class="grp-h">${g[0]} <span class="num">${g[1].length}</span></h2><ul class="list">${g[1].map(row)}</ul></section>`));
  };
  search.addEventListener('input', () => { q = search.value; draw(); });
  AK.on(chips, 'click', '.chip', (e, b) => { chipF = b.dataset.chip; draw(); });
  draw();
}
function row(x) {
  const n = counts(x), done = x.status === 'done';
  const tags = [n.urgent ? AK.html`<span class="pill bad">${n.urgent} urgent</span>` : '', n.attn ? AK.html`<span class="pill warn">${n.attn} attention</span>` : '',
    !n.urgent && !n.attn && n.done ? AK.html`<span class="pill ok">All OK</span>` : ''];
  return AK.html`<li><button type="button" class="item insp-row" data-act="open" data-id="${x.id}">
    <span class="ring row-ring${done ? ' is-done' : ''}" style="--value:${n.pct}" aria-hidden="true"><span>${done ? AK.icon('check') : n.pct + '%'}</span></span>
    <span class="body"><span class="title">${titleOf(x)}</span><span class="sub">${[x.subject ? x.customer.name : '', x.ref, AK.fmt.day(x.date)].filter(Boolean).join(' · ') || x.templateName}</span>
      <span class="row-tags">${tags}${!done && !n.done ? AK.html`<span class="pill plain">Not started</span>` : ''}</span></span>
    ${AK.icon('chevron-right', 'chev')}</button></li>`;
}

/* ===== 7. STARTING AN INSPECTION ===== */
function startNew(preset) {
  const ts = templates();
  if (!ts.length) { AK.toast('Make a checklist first', 'warn'); app.go('/templates'); return; }
  if (ts.length === 1) { create(ts[0], preset); return; }
  const body = tpl('sheet-new');
  AK.render(slot(body, 'templates'), ts.map(t => AK.html`<li><button type="button" class="tpl-card" data-tpl="${t.id}">
    <span class="tpl-ico">${AK.icon(isVehicle(t) ? 'car' : 'clipboard')}</span>
    <span class="body"><span class="title">${t.name}</span><span class="sub">${plural(t.sections.length, 'section')} · ${plural(t.sections.reduce((a, s) => a + s.items.length, 0), 'check')}</span></span>
    ${AK.icon('chevron-right', 'chev')}</button></li>`));
  const L = AK.sheet({ title: 'New inspection', body });
  L.body.addEventListener('click', e => { const b = e.target.closest('[data-tpl]'); if (b) create(ts.find(t => t.id === b.dataset.tpl), preset); });
}
function fromTemplate(t, preset) {
  return Object.assign({
    id: AK.id(), templateId: t.id, templateName: t.name, subjectLabel: t.subjectLabel || 'Item', refLabel: t.refLabel || 'Reference',
    customer: { name: '', phone: '', email: '' }, subject: '', ref: '', date: AK.today(), inspector: set().inspector || '', notes: '', status: 'open',
    signature: '', signedAt: '', created: Date.now(), updated: Date.now(),
    sections: t.sections.map(s => ({ id: AK.id(), name: s.name, items: s.items.map(i => ({ id: AK.id(), name: i.name, status: '', note: '', photos: [] })) }))
  }, preset || {});
}
function create(t, preset) {
  const x = fromTemplate(t, preset);
  silently(() => db.add('inspections', x, { first: true }));
  app.go('/list/' + x.id);
}

/* ===== 8. RUNNING AN INSPECTION ===== */
function notFound(page) {
  page.append(AK.el('header', { class: 'page-head' }, AK.el('div', {}, AK.el('h1', { text: 'Inspection not found' }))));
  AK.render(page.appendChild(AK.el('div', { class: 'card' })), AK.empty({ art: 'search', title: 'It may have been deleted', text: 'Go back to the list to find it.', action: { label: 'All inspections', act: 'go-list' } }));
}
function renderRun(page, ctx, id) {
  const x0 = db.find('inspections', id);
  if (!x0) return notFound(page);
  const x = copyOf(x0), s = set();
  page.append(tpl('view-run'));
  ctx.title = titleOf(x);
  const hf = $('.head-form', page), hc = $('.head-card', page), secsEl = slot(page, 'sections'), notes = $('#f-notes', page);
  let root = null;
  const live = () => !!root && root.isConnected;
  // saved quietly while this screen is open; an Undo tapped after leaving saves normally so the screen on show is redrawn
  const save = debounce(() => { x.updated = Date.now(); if (live()) silently(() => db.update('inspections', id, copyOf(x))); else db.update('inspections', id, copyOf(x)); }, 250);
  // details (customer, vehicle or item, reference, date, inspector)
  $('.subject-label', page).textContent = x.subjectLabel; $('.ref-label', page).textContent = x.refLabel;
  hf.subject.placeholder = isVehicle(x) ? 'Year, make and model' : 'What is being inspected';
  hf.customer.value = x.customer.name; hf.phone.value = x.customer.phone; hf.email.value = x.customer.email || '';
  hf.subject.value = x.subject; hf.ref.value = x.ref; hf.date.value = x.date; hf.inspector.value = x.inspector;
  hc.open = !x.customer.name && !x.subject;
  notes.value = x.notes || '';
  const heads = () => {
    if (root && !live()) return;
    bind(page, { eyebrow: x.templateName + (x.status === 'done' ? ' · Finished' : ''), title: titleOf(x), sub: [x.subject ? x.customer.name : '', x.ref, AK.fmt.date(x.date)].filter(Boolean).join(' · '),
      headTitle: [x.customer.name, x.subject].filter(Boolean).join(' · ') || 'Add the details', headSub: [x.subjectLabel + (x.ref ? ': ' + x.ref : ''), AK.fmt.date(x.date), x.inspector].filter(Boolean).join(' · ') });
  };
  hf.addEventListener('input', () => {
    x.customer = { name: hf.customer.value.trim(), phone: hf.phone.value.trim(), email: hf.email.value.trim() };
    x.subject = hf.subject.value.trim(); x.ref = hf.ref.value.trim(); x.date = hf.date.value || x.date; x.inspector = hf.inspector.value.trim();
    heads(); save();
  });
  suggest(hf.customer, qq => pastCustomers().filter(c => match(c.name + ' ' + c.phone + ' ' + c.subject, qq)).slice(0, 5)
    .map(c => ({ label: c.name, sub: [c.phone, c.subject].filter(Boolean).join(' · '), value: c })), c => {
    hf.customer.value = c.name; hf.phone.value = c.phone || ''; hf.email.value = c.email || '';
    if (!hf.subject.value.trim() && c.subject && c.label === x.subjectLabel) hf.subject.value = c.subject;
    hf.dispatchEvent(new Event('input'));
  });
  notes.addEventListener('input', () => { x.notes = notes.value; save(); });
  // sections and checks
  x.sections.forEach(sec => {
    const f = tpl('row-section'), el = f.firstElementChild, ul = $('.items', el);
    el.dataset.sec = sec.id;
    $('.sec-name', el).textContent = sec.name;
    sec.items.forEach(it => ul.append(itemEl(it)));
    const doneAll = sec.items.every(it => it.status), bad = sec.items.some(it => it.status === 'attn' || it.status === 'urgent');
    if (doneAll && !bad && sec.items.length) collapse(el, true);
    secsEl.append(el);
  });
  const find = li => { const sec = x.sections.find(s => s.id === li.closest('.sec').dataset.sec); return sec && sec.items.find(it => it.id === li.dataset.id); };
  const progress = () => {
    if (!live()) return;
    const n = counts(x), left = n.none;
    bind(page, { pctText: n.done + ' of ' + n.total + ' checked', leftText: left ? plural(left, 'check') + ' left' : 'All checked', pctShort: n.pct + '%',
      sideSub: n.urgent || n.attn ? [n.urgent ? n.urgent + ' urgent' : '', n.attn ? n.attn + ' need attention' : ''].filter(Boolean).join(' · ') : plural(n.total, 'check') + ' in this list' });
    $('.run-prog', page).style.setProperty('--value', n.pct);
    const ring = slot(page, 'ring'); ring.style.setProperty('--value', n.pct);
    ['ok', 'attn', 'urgent'].forEach(k => { $('.n-' + k, page).textContent = n[k]; });
    $$('.sec', secsEl).forEach(el => {
      const sec = x.sections.find(q2 => q2.id === el.dataset.sec), d = sec.items.filter(it => it.status).length;
      $('.sec-count', el).textContent = d + '/' + sec.items.length;
      el.classList.toggle('complete', d === sec.items.length);
      $('.sec-ok', el).disabled = d === sec.items.length;
    });
    const stEl = slot(page, 'state');
    stEl.textContent = x.status === 'done' ? 'Finished' : left ? 'In progress' : 'Ready to finish';
    stEl.className = 'pill ' + (x.status === 'done' ? 'ok' : left ? 'plain' : 'info');
    $('.finish-label', page).textContent = x.status === 'done' ? 'See the report' : 'Finish and see the report';
  };
  const setStatus = (li, st, force) => {
    const it = find(li); if (!it) return;
    it.status = force ? st : it.status === st ? '' : st;
    paint(li, it); buzz();
    if ((it.status === 'attn' || it.status === 'urgent') && set().autoNote && !it.note && !force) openDetail(li, true);
    progress(); save();
  };
  // taps (listeners go on this screen's own elements: appkit keeps the .page element between screens)
  root = $('.run', page);
  root.addEventListener('click', e => {
    if (swiped && Date.now() - swiped < 400) { e.stopPropagation(); e.preventDefault(); return; }
    const t = e.target, li = t.closest('.it');
    if (t.closest('.st-btn')) { setStatus(li, t.closest('.st-btn').dataset.st); return; }
    if (t.closest('.it-head')) { openDetail(li, $('.it-detail', li).hidden); return; }
    if (t.closest('.it-na')) { setStatus(li, 'na'); return; }
    if (t.closest('.add-photo')) { addPhoto(li); return; }
    const th = t.closest('.thumb'); if (th) { viewPhoto(li, th.dataset.pid); return; }
    if (t.closest('.sec-toggle')) { const el = t.closest('.sec'); collapse(el, !el.classList.contains('collapsed')); return; }
    if (t.closest('.sec-ok')) { restOk(t.closest('.sec')); return; }
  }, true);
  root.addEventListener('input', e => { if (e.target.classList.contains('it-note')) { const li = e.target.closest('.it'), it = find(li); it.note = e.target.value; paint(li, it); save(); } });
  const restOk = el => {
    const sec = x.sections.find(q2 => q2.id === el.dataset.sec), changed = sec.items.filter(it => !it.status);
    if (!changed.length) return;
    changed.forEach(it => { it.status = 'ok'; });
    $$('.it', el).forEach(li => paint(li, find(li)));
    progress(); save(); buzz();
    AK.toast(plural(changed.length, 'check') + ' marked OK', { undo: () => { changed.forEach(it => { it.status = ''; }); $$('.it', el).forEach(li => paint(li, find(li))); progress(); save(); } });
  };
  // swipe right = OK, left = Needs attention (touch only; buttons work everywhere)
  let swiped = 0;
  swipe(secsEl, (li, dir) => { swiped = Date.now(); setStatus(li, dir > 0 ? 'ok' : 'attn', true); });
  // photos
  const addPhoto = async li => {
    const it = find(li);
    if (it.photos.length >= 3) { AK.toast('Up to 3 photos per check', 'info'); return; }
    if (spaceLeft() < 250000) { AK.toast('This phone is nearly out of space for photos. Back up, then delete old inspections.', { type: 'bad', duration: 7000 }); return; }
    const url = await AK.photo(undefined, { max: 900, quality: 0.62 });
    if (!url) return;
    const pid = 'ph' + AK.id();
    pics.set('p.' + pid, url);
    it.photos.push(pid); paint(li, it); save();
    AK.toast('Photo added', 'ok');
  };
  const viewPhoto = (li, pid) => {
    const it = find(li);
    AK.modal({ title: it.name, class: 'photo-modal', body: AK.html`<img class="big-photo" src="${photo(pid)}" alt="Photo of ${it.name}" width="900" height="675">`,
      actions: [{ label: 'Remove photo', danger: true, icon: 'trash', onClick: () => {
        const i = it.photos.indexOf(pid); it.photos = it.photos.filter(p => p !== pid); paint(li, it); save();
        AK.toast('Photo removed', { undo: () => { it.photos.splice(i, 0, pid); paint(li, it); save(); } });
      } }, { label: 'Close', primary: true }] });
  };
  // signature
  const pad = AK.signature($('.sign-pad', page));
  const sigShow = () => {
    const has = x.signature && photo(x.signature);
    $('.sig-saved', page).hidden = !has; $('.sig-new', page).hidden = !!has;
    AK.render($('.sig-holder', page), has ? AK.html`<img class="sig-img" src="${photo(x.signature)}" alt="Customer signature" width="300" height="95">` : '');
  };
  page._sig = {
    clear: () => pad.clear(),
    save: () => {
      if (pad.isEmpty()) { AK.toast('Ask the customer to sign in the box first', 'warn'); return; }
      const pid = 'sig' + AK.id();
      pics.set('p.' + pid, pad.toDataURL({ color: '#000', scale: 1.5 }));
      x.signature = pid; x.signedAt = AK.today(); save(); sigShow();
      AK.toast('Signature saved', 'ok');
    },
    redo: () => { x.signature = ''; save(); pad.clear(); sigShow(); }
  };
  page._x = x; page._save = save;
  sigShow(); heads(); progress();
  const hide = () => { if (document.visibilityState === 'hidden') save.flush(); };
  document.addEventListener('visibilitychange', hide);
  return () => { document.removeEventListener('visibilitychange', hide); save.flush(); page._x = page._save = page._sig = null; };
}
function itemEl(it) {
  const li = tpl('row-item').firstElementChild;
  li.dataset.id = it.id;
  $('.it-name', li).textContent = it.name;
  $('.it-seg', li).setAttribute('aria-label', 'Status of ' + it.name);
  $('.it-head', li).setAttribute('aria-label', it.name + ': notes and photos');
  $('.it-note', li).value = it.note || '';
  paint(li, it);
  return li;
}
// Draws one check from its data: status colour, pressed button, note and photo flags, thumbnails.
function paint(li, it) {
  li.dataset.st = it.status || '';
  $$('.st-btn', li).forEach(b => b.setAttribute('aria-pressed', String(b.dataset.st === it.status)));
  $('.flag-note', li).hidden = !it.note;
  const fp = $('.flag-photo', li); fp.hidden = !it.photos.length; $('b', fp).textContent = it.photos.length || '';
  $('.na-label', li).textContent = it.status === 'na' ? 'Applies after all' : 'Not applicable';
  const note = $('.it-note', li);
  if (document.activeElement !== note && note.value !== (it.note || '')) note.value = it.note || '';
  AK.render($('.thumbs-row', li), it.photos.map((p, i) => AK.html`<button type="button" class="thumb" data-pid="${p}" aria-label="Open photo ${i + 1}">${photo(p) ? AK.html`<img src="${photo(p)}" alt="" width="72" height="72">` : AK.html`<span class="missing">Missing</span>`}</button>`));
  $('.add-photo', li).hidden = it.photos.length >= 3;
}
function openDetail(li, open) {
  $('.it-detail', li).hidden = !open;
  $('.it-head', li).setAttribute('aria-expanded', String(open));
  li.classList.toggle('open', open);
}
function collapse(el, yes) {
  el.classList.toggle('collapsed', yes);
  $('.sec-toggle', el).setAttribute('aria-expanded', String(!yes));
}
// Horizontal swipe on a check (touch and pen); vertical movement scrolls as usual.
function swipe(root, done) {
  let st = null;
  root.addEventListener('pointerdown', e => {
    if (e.pointerType === 'mouse' || e.button > 0) return;
    const body = e.target.closest('.it-body');
    if (!body || e.target.closest('.it-detail')) return;
    st = { body, li: body.parentElement, x: e.clientX, y: e.clientY, dx: 0, on: false, pid: e.pointerId };
  });
  root.addEventListener('pointermove', e => {
    if (!st || e.pointerId !== st.pid) return;
    const dx = e.clientX - st.x, dy = e.clientY - st.y;
    if (!st.on) {
      if (Math.abs(dy) > 10 && Math.abs(dy) > Math.abs(dx)) { st = null; return; }
      if (Math.abs(dx) < 14) return;
      st.on = true; st.li.classList.add('swiping');
      try { st.body.setPointerCapture(e.pointerId); } catch (x) {}
    }
    st.dx = Math.max(-150, Math.min(150, dx));
    st.body.style.transform = 'translateX(' + st.dx + 'px)';
    st.li.dataset.swipe = st.dx > 0 ? 'ok' : 'attn';
    st.li.classList.toggle('armed', Math.abs(st.dx) > 84);
  });
  const end = e => {
    if (!st) return;
    const s = st; st = null;
    if (!s.on) return;
    s.body.style.transform = ''; s.li.classList.remove('swiping', 'armed'); delete s.li.dataset.swipe;
    if (e.type === 'pointerup' && Math.abs(s.dx) > 84) done(s.li, s.dx);
  };
  root.addEventListener('pointerup', end); root.addEventListener('pointercancel', end);
}
function pastCustomers() {
  const map = new Map();
  all().slice().sort((a, b) => (a.date || '').localeCompare(b.date || '')).forEach(x => {
    const n = (x.customer.name || '').trim(); if (!n) return;
    const k = n.toLowerCase(), c = map.get(k) || { name: n, phone: '', email: '', subject: '', label: '' };
    if (x.customer.phone) c.phone = x.customer.phone; if (x.customer.email) c.email = x.customer.email;
    if (x.subject) { c.subject = x.subject; c.label = x.subjectLabel; }
    map.set(k, c);
  });
  return Array.from(map.values()).reverse();
}
// A small suggestion list under an input; tap (or Enter on a highlighted row) picks one.
function suggest(input, source, onPick) {
  const wrap = input.closest('.suggest-wrap') || input.parentElement;
  const box = AK.el('ul', { class: 'suggest', role: 'listbox', hidden: true });
  wrap.append(box);
  let items = [], hi = -1;
  const close = () => { box.hidden = true; hi = -1; };
  const draw = () => {
    items = input.value.trim() ? source(input.value.trim()) : [];
    if (!items.length || (items.length === 1 && items[0].label.toLowerCase() === input.value.trim().toLowerCase())) return close();
    AK.render(box, items.map((it, i) => AK.html`<li role="option" aria-selected="${i === hi}"><button type="button" tabindex="-1" data-i="${i}"><span class="sg-title">${it.label}</span><span class="sg-sub">${it.sub}</span></button></li>`));
    box.hidden = false;
  };
  input.addEventListener('input', () => { hi = -1; draw(); });
  input.addEventListener('blur', () => setTimeout(close, 180));
  input.addEventListener('keydown', e => {
    if (box.hidden) return;
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') { e.preventDefault(); hi = (hi + (e.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length; draw(); }
    else if (e.key === 'Enter' && hi > -1) { e.preventDefault(); const it = items[hi]; close(); onPick(it.value); }
    else if (e.key === 'Escape') close();
  });
  box.addEventListener('pointerdown', e => e.preventDefault());
  box.addEventListener('click', e => { const b = e.target.closest('[data-i]'); if (!b) return; const it = items[+b.dataset.i]; close(); onPick(it.value); });
}
function nextUnchecked() {
  const li = $$('.page .it').find(l => !l.dataset.st);
  if (!li) { AK.toast('Every check is done. Finish when you are ready.', 'ok'); const f = $('#finish'); if (f) f.scrollIntoView({ block: 'start', behavior: AK.still() ? 'auto' : 'smooth' }); return; }
  const sec = li.closest('.sec'); if (sec.classList.contains('collapsed')) collapse(sec, false);
  li.scrollIntoView({ block: 'center', behavior: AK.still() ? 'auto' : 'smooth' });
  li.classList.remove('flash'); void li.offsetWidth; li.classList.add('flash');
  const b = $('.st-btn', li); if (b) b.focus({ preventScroll: true });
}
function finish(id) {
  const page = $('.page'), x = page._x;
  if (!x || x.id !== id) return;
  const go = () => { x.status = 'done'; x.finishedAt = x.finishedAt || AK.today(); page._save(); page._save.flush(); app.go('/list/' + id + '/report'); };
  if (x.status === 'done') { page._save.flush(); app.go('/list/' + id + '/report'); return; }
  const n = counts(x);
  if (n.none) AK.confirm(plural(n.none, 'check is', 'checks are') + ' not done yet', { text: 'Finish anyway? They show as Not checked on the report.', ok: 'Finish anyway', icon: 'alert' }).then(ok => { if (ok) go(); });
  else go();
}

/* ===== 9. REPORT ===== */
function renderReport(page, ctx, id) {
  const x = db.find('inspections', id);
  if (!x) return notFound(page);
  page.append(tpl('view-report'));
  ctx.title = 'Report';
  const n = counts(x);
  bind(page, { title: titleOf(x), sub: [n.urgent ? plural(n.urgent, 'urgent problem') : '', n.attn ? n.attn + ' to watch' : '', n.ok + ' OK'].filter(Boolean).join(' · ') });
  slot(page, 'doc').append(reportDoc(x));
}
function reportDoc(x) {
  const f = tpl('doc-report'), n = counts(x), b = biz(), s = set();
  const names = st => [].concat(...x.sections.map(sec => sec.items.filter(it => (st ? it.status === st : !it.status)).map(it => sec.name + ': ' + it.name)));
  bind(f, {
    biz: b, dateText: AK.fmt.date(x.date, 'full'), tplName: x.templateName,
    customer: x.customer.name, phone: x.customer.phone, subjectLabel: x.subjectLabel, subject: x.subject, refLabel: x.refLabel, ref: x.ref, inspector: x.inspector,
    n, showOk: s.showOk && n.ok > 0, noneText: names('').join(' · '), naText: names('na').join(' · '), notes: x.notes,
    signLine: x.signature ? 'Customer: ' + (x.customer.name || 'signed') + (x.signedAt ? ', ' + AK.fmt.date(x.signedAt) : '') : 'Customer signature',
    footer: [b.name, b.phone, b.email].filter(Boolean).join(' · ')
  });
  const item = f2 => AK.html`<li class="rep-item"><div class="rep-text"><p class="rep-sec">${f2.sec}</p><p class="rep-name">${f2.it.name}</p>${f2.it.note ? AK.html`<p class="rep-note pre">${f2.it.note}</p>` : ''}</div>
    ${f2.it.photos.length ? AK.html`<div class="rep-photos">${f2.it.photos.filter(photo).map((p, i) => AK.html`<img src="${photo(p)}" alt="Photo ${i + 1} of ${f2.it.name}" width="320" height="240">`)}</div>` : ''}</li>`;
  AK.render(slot(f, 'urgent'), flagged(x, 'urgent').map(item));
  AK.render(slot(f, 'attn'), flagged(x, 'attn').map(item));
  AK.render(slot(f, 'ok'), x.sections.filter(sec => sec.items.some(it => it.status === 'ok')).map(sec => AK.html`<div class="ok-sec"><p class="ok-h">${sec.name}</p><ul>${sec.items.filter(it => it.status === 'ok').map(it => AK.html`<li>${it.name}</li>`)}</ul></div>`));
  if (x.signature && photo(x.signature)) slot(f, 'sig').innerHTML = String(AK.html`<img class="sig-img" src="${photo(x.signature)}" alt="Customer signature" width="300" height="95">`);
  return f;
}

/* ===== 10. SHARING ===== */
function messageFor(x) {
  const n = counts(x), b = biz(), out = [];
  out.push('Inspection report' + (x.subject ? ' for ' + x.subject : '') + (x.ref ? ' (' + x.refLabel + ': ' + x.ref + ')' : ''));
  out.push('Date: ' + AK.fmt.date(x.date));
  out.push('Result: ' + [plural(n.urgent, 'urgent'), n.attn + ' need attention', n.ok + ' OK'].join(', '));
  const list = (st, head) => { const fl = flagged(x, st); if (fl.length) { out.push('', head); fl.forEach(f => out.push('- ' + f.sec + ', ' + f.it.name + (f.it.note ? ': ' + f.it.note.replace(/\s+/g, ' ') : ''))); } };
  list('urgent', 'URGENT, needs action now:');
  list('attn', 'Needs attention soon:');
  if (x.notes) out.push('', x.notes);
  out.push('', 'Thank you,', [b.name, b.phone].filter(Boolean).join(' · '));
  return out.join('\n');
}
function openShare(x) {
  const L = AK.sheet({ title: 'Send the report', body: tpl('sheet-share') });
  const B = L.body, msg = $('#share-msg', B), sms = $('.share-sms', B), mail = $('.share-mail', B);
  msg.value = messageFor(x);
  $('.share-sms-to', B).textContent = x.customer.phone ? 'To ' + x.customer.phone : 'Opens your messages app';
  $('.share-mail-to', B).textContent = x.customer.email ? 'To ' + x.customer.email : 'Opens your email app';
  const links = () => { sms.href = AK.sms(x.customer.phone, msg.value); mail.href = AK.mail(x.customer.email, 'Inspection report' + (x.subject ? ': ' + x.subject : '') + (biz().name ? ' from ' + biz().name : ''), msg.value); };
  links(); msg.addEventListener('input', links);
  $('.share-copy', B).addEventListener('click', () => AK.copy(msg.value, { done: 'Message copied' }));
  $('.share-print', B).addEventListener('click', () => { L.close(); setTimeout(() => printReport(x), 250); });
}
function printReport(x) {
  const el = $('.page .doc.report');
  if (el) { AK.print('Inspection ' + titleOf(x), el); return; }
  const host = AK.el('div', { class: 'print-host' }); host.append(reportDoc(x)); document.body.append(host);
  AK.print('Inspection ' + titleOf(x), host.firstElementChild); setTimeout(() => host.remove(), 1500);
}

/* ===== 11. CHECKLIST EDITOR ===== */
function renderTemplates(page, ctx) {
  if (ctx.param) return renderTemplate(page, ctx, ctx.param);
  page.append(tpl('view-templates'));
  const ts = templates();
  AK.render(slot(page, 'list'), ts.length ? AK.html`<ul class="list">${ts.map(t => AK.html`<li><button type="button" class="item" data-act="open-template" data-id="${t.id}">
      <span class="lead">${AK.icon(isVehicle(t) ? 'car' : 'clipboard')}</span><span class="body"><span class="title">${t.name}</span><span class="sub">${plural(t.sections.length, 'section')} · ${plural(t.sections.reduce((a, s) => a + s.items.length, 0), 'check')}</span></span>${AK.icon('chevron-right', 'chev')}</button></li>`)}</ul>`
    : AK.html`<div class="card">${AK.empty({ art: 'list', title: 'No checklists', text: 'Make one, or load the ones that came with the app.', action: { label: 'Bring back the starter lists', act: 'restore-starters' } })}</div>`);
}
function renderTemplate(page, ctx, id) {
  const t = db.find('templates', id);
  if (!t) { app.go('/templates', true); return; }
  page.append(tpl('view-template'));
  ctx.title = t.name;
  const form = $('.tpl-form', page), secs = slot(page, 'sections'), stateEl = $('.save-state', page);
  form.querySelector('[name=name]').value = t.name; form.querySelector('[name=subjectLabel]').value = t.subjectLabel || ''; form.querySelector('[name=refLabel]').value = t.refLabel || '';
  $('.tpl-title', page).textContent = t.name;
  const itemLi = it => {
    const li = AK.el('li', { class: 'item titem', 'data-id': it.id });
    AK.render(li, AK.html`<button type="button" class="handle" aria-label="Move this check (arrow keys)">${AK.icon('grip')}</button><input class="titem-name" aria-label="Check name" value="${it.name}"><button type="button" class="btn icon ghost sm titem-del" aria-label="Delete this check">${AK.icon('trash')}</button>`);
    return li;
  };
  const secEl = sec => {
    const el = tpl('row-tsec').firstElementChild;
    el.dataset.id = sec.id;
    $('.handle', el).classList.add('sec-handle');
    $('.tsec-name', el).value = sec.name;
    const ul = $('.titems', el);
    sec.items.forEach(it => ul.append(itemLi(it)));
    AK.drag(ul, { handle: '.handle', onSort: () => save() });
    return el;
  };
  t.sections.forEach(sec => secs.append(secEl(sec)));
  AK.drag(secs, { handle: '.sec-handle', onSort: () => save() });
  const read = () => ({
    name: form.querySelector('[name=name]').value.trim() || 'Untitled checklist', subjectLabel: form.querySelector('[name=subjectLabel]').value.trim() || 'Item', refLabel: form.querySelector('[name=refLabel]').value.trim() || 'Reference',
    sections: $$('.tsec', secs).map(el => ({ id: el.dataset.id, name: $('.tsec-name', el).value.trim() || 'Section', items: $$('.titem', el).map(li => ({ id: li.dataset.id, name: $('.titem-name', li).value.trim() })).filter(it => it.name) }))
  });
  const box = $('.tpl-edit', page), titleEl = $('.tpl-title', page);
  // an Undo tapped after leaving this screen still saves (and then the screen on show is redrawn)
  const save = debounce(() => { const v = read(); if (box.isConnected) silently(() => db.update('templates', id, v)); else db.update('templates', id, v); titleEl.textContent = v.name; stateEl.textContent = 'Saved'; }, 300);
  box.addEventListener('input', () => { stateEl.textContent = 'Saving...'; save(); });
  box.addEventListener('submit', e => {
    e.preventDefault();
    const fm = e.target;
    if (fm.classList.contains('add-item')) {
      const v = fm.item.value.trim(); if (!v) { fm.item.focus(); return; }
      $('.titems', fm.closest('.tsec')).append(itemLi({ id: AK.id(), name: v })); fm.item.value = ''; fm.item.focus(); save();
    } else if (fm.classList.contains('add-sec')) {
      const v = fm.sec.value.trim(); if (!v) { fm.sec.focus(); return; }
      const el = secEl({ id: AK.id(), name: v, items: [] }); secs.append(el); fm.sec.value = ''; save();
      $('.add-item input', el).focus();
    }
  });
  box.addEventListener('click', e => {
    const del = e.target.closest('.titem-del');
    if (del) {
      const li = del.closest('.titem'), ul = li.parentElement, next = li.nextElementSibling;
      li.remove(); save();
      AK.toast('Check deleted', { undo: () => { if (next && next.parentNode === ul) ul.insertBefore(li, next); else ul.append(li); save(); } });
      return;
    }
    const sd = e.target.closest('.tsec-del');
    if (sd) {
      const el = sd.closest('.tsec'), next = el.nextElementSibling, n = $$('.titem', el).length;
      const go = () => { el.remove(); save(); AK.toast('Section deleted', { undo: () => { if (next && next.parentNode === secs) secs.insertBefore(el, next); else secs.append(el); save(); } }); };
      if (n) AK.confirm('Delete "' + $('.tsec-name', el).value + '"?', { text: 'Its ' + plural(n, 'check') + ' go too. Past inspections keep theirs.', ok: 'Delete', danger: true }).then(ok => { if (ok) go(); });
      else go();
    }
  });
  return () => save.flush();
}

/* ===== 12. SETTINGS + BACKUP ===== */
function storageUsed() { try { return (localStorage.getItem(db.key) || '').length + (localStorage.getItem(pics.key) || '').length; } catch (e) { return 0; } }
function spaceLeft() { let used = 0; try { for (let i = 0; i < localStorage.length; i++) { const k = localStorage.key(i); used += k.length + (localStorage.getItem(k) || '').length; } } catch (e) { return 5e6; } return 5e6 - used; }
function renderSettings(page) {
  page.append(tpl('view-settings'));
  const form = $('#set-form', page), s = set(), b = biz(), state = $('.save-state', page);
  AK.fill(form, Object.assign({}, s, { business: REAL.business(b.name) ? b.name : s.business, phone: b.phone, email: b.email, address: b.address }));
  const used = storageUsed(), mb = n => (n / 1e6).toFixed(n < 1e5 ? 2 : 1);
  bind(page, { bizName: b.name, storeText: (db.persistent ? 'Saved on this phone only. ' : 'Private mode: nothing is kept after this page closes. ') + 'Inspections and photos use about ' + mb(used) + ' MB of the roughly 5 MB a browser gives one site.' });
  const bar = $('.store-bar', page);
  bar.style.setProperty('--value', Math.min(100, Math.round(used / 5e6 * 100)));
  bar.classList.toggle('warn', used > 3.5e6);
  const save = debounce(() => {
    const v = AK.values(form), next = Object.assign({}, set());
    ['phone', 'email', 'address'].forEach(k => { const val = (v[k] || '').trim(); next[k] = val === factOf(k) ? '' : val; });
    const nm = (v.business || '').trim(); next.business = nm === FACTS.business ? '' : nm;
    next.inspector = (v.inspector || '').trim(); next.showOk = !!v.showOk; next.autoNote = !!v.autoNote;
    silently(() => db.set({ settings: next }));
    brand(); state.textContent = 'Saved';
  }, 400);
  form.addEventListener('input', () => { state.textContent = 'Saving...'; save(); });
  form.addEventListener('change', () => { state.textContent = 'Saving...'; save(); });
  form.addEventListener('submit', e => e.preventDefault());
  $('#restore-input', page).addEventListener('change', e => { const f = e.target.files[0]; e.target.value = ''; if (f) f.text().then(restoreText); });
  return () => save.flush();
}
function backupText() { return JSON.stringify({ app: 'inspection-checklist', saved: new Date().toISOString(), data: db.get(), photos: pics.get('p') || {} }); }
function restoreText(text) {
  let j = null;
  try { j = JSON.parse(String(text || '').trim()); } catch (e) { j = null; }
  const data = j && j.data ? j.data : j;
  if (!data || !Array.isArray(data.inspections) || !Array.isArray(data.templates)) { AK.toast('That is not a backup from this app.', 'bad'); return false; }
  AK.confirm('Replace everything with this backup?', { text: plural(data.inspections.length, 'inspection') + ' and ' + plural(data.templates.length, 'checklist') + ' in the backup. What is on this phone now is replaced.', ok: 'Restore', danger: true }).then(ok => {
    if (!ok) return;
    pics.reset({ p: (j && j.photos) || {} });
    db.reset({ v: 1, inspections: data.inspections.filter(x => x && Array.isArray(x.sections)).map(normInsp), templates: data.templates.filter(t => t && Array.isArray(t.sections)), settings: data.settings || {} });
    brand();
    AK.toast('Backup restored', 'ok');
  });
  return true;
}
const normInsp = x => Object.assign({ id: AK.id(), status: 'open', date: AK.today(), notes: '', subject: '', ref: '', inspector: '', templateName: 'Inspection', subjectLabel: 'Item', refLabel: 'Reference', signature: '' }, x, {
  customer: Object.assign({ name: '', phone: '', email: '' }, x.customer || {}),
  sections: x.sections.map(s => Object.assign({ id: AK.id(), name: '' }, s, { items: (s.items || []).map(it => Object.assign({ id: AK.id(), name: '', status: '', note: '' }, it, { photos: Array.isArray(it.photos) ? it.photos : [] })) }))
});

/* ===== 13. ACTIONS (any element with data-act="name") ===== */
const curId = () => (app.route().param || '').split('/')[0];
AK.act({
  'new-insp': () => startNew(),
  'go-list': () => app.go('/list'),
  'go-templates': () => app.go('/templates'),
  open: el => app.go('/list/' + el.dataset.id),
  next: () => nextUnchecked(),
  report: () => { const p = $('.page'); if (p._save) p._save.flush(); app.go('/list/' + curId() + '/report'); },
  finish: () => finish(curId()),
  'back-run': () => app.go('/list/' + curId()),
  share: () => { const x = db.find('inspections', curId()); if (x) openShare(x); },
  print: () => { const x = db.find('inspections', curId()); if (x) printReport(x); },
  'sig-clear': () => { const p = $('.page'); if (p._sig) p._sig.clear(); },
  'sig-save': () => { const p = $('.page'); if (p._sig) p._sig.save(); },
  'sig-redo': () => { const p = $('.page'); if (p._sig) p._sig.redo(); },
  'open-template': el => app.go('/templates/' + el.dataset.id),
  'new-template': async () => {
    const name = await AK.prompt('Name the new checklist', { label: 'Name', placeholder: 'For example: Pre-delivery check' });
    if (!name) return;
    const t = { id: AK.id(), name, subjectLabel: 'Item', refLabel: 'Reference', sections: [{ id: AK.id(), name: 'First section', items: [] }] };
    silently(() => db.add('templates', t));
    app.go('/templates/' + t.id);
  },
  'dup-template': () => {
    const t = db.find('templates', curId()); if (!t) return;
    const c = { id: AK.id(), name: t.name + ' (copy)', subjectLabel: t.subjectLabel, refLabel: t.refLabel, sections: t.sections.map(s => ({ id: AK.id(), name: s.name, items: s.items.map(i => ({ id: AK.id(), name: i.name })) })) };
    silently(() => db.add('templates', c));
    AK.toast('Copied', 'ok'); app.go('/templates/' + c.id);
  },
  'del-template': () => {
    const t = db.find('templates', curId()); if (!t) return;
    AK.confirm('Delete "' + t.name + '"?', { text: 'Past inspections made with it are kept.', ok: 'Delete', danger: true }).then(ok => {
      if (!ok) return;
      app.go('/templates', true);
      setTimeout(() => { db.remove('templates', t.id); AK.toast('Checklist deleted', { undo: () => db.undo() }); }, 30);
    });
  },
  'restore-starters': () => { db.set({ templates: templates().concat(starterTemplates()) }); AK.toast('Starter checklists added', 'ok'); },
  'load-sample': () => loadSample(),
  'clear-sample': () => {
    if (!all().some(x => x.sample)) { AK.toast('There is no sample data to clear', 'info'); return; }
    db.set({ inspections: all().filter(x => !x.sample) });
    AK.toast('Sample data cleared', { undo: () => db.undo() });
  },
  backup: () => { AK.download('inspections-backup-' + AK.today() + '.json', backupText()); AK.toast('Backup file saved', 'ok'); },
  'copy-backup': () => AK.copy(backupText(), { done: 'Backup copied. Paste it somewhere safe.' }),
  'restore-file': () => { const i = $('#restore-input'); if (i) i.click(); },
  'restore-text': () => {
    AK.modal({ title: 'Restore from copied text', body: AK.html`<div class="form"><div class="field"><label for="rt-text">Paste the backup here</label><textarea id="rt-text" rows="6"></textarea></div></div>`,
      actions: [{ label: 'Cancel' }, { label: 'Restore', primary: true, onClick: L => (restoreText($('#rt-text', L.body).value) ? undefined : false) }] });
  },
  erase: () => {
    AK.confirm('Erase every inspection?', { text: 'All inspections, their photos and signatures are removed from this phone. Checklists and settings stay.', ok: 'Erase', danger: true }).then(ok => {
      if (ok) { db.set({ inspections: [] }); AK.toast('Everything erased', { undo: () => db.undo() }); }
    });
  }
});
// Delete an inspection (button at the bottom of its Finish card)
AK.act('delete-insp', () => {
  const x = db.find('inspections', curId()); if (!x) return;
  AK.confirm('Delete this inspection?', { text: 'Its photos and signature go too. You can undo straight after.', ok: 'Delete', danger: true }).then(ok => {
    if (!ok) return;
    app.go('/list', true);
    setTimeout(() => { db.remove('inspections', x.id); AK.toast('Inspection deleted', { undo: () => db.undo() }); }, 30);
  });
});

/* ===== 14. SAMPLE DATA (clearly marked; "Clear sample data" removes only these) ===== */
function samplePhoto(label) {
  const c = document.createElement('canvas'); c.width = 640; c.height = 480;
  const g = c.getContext('2d'), gr = g.createLinearGradient(0, 0, 640, 480);
  gr.addColorStop(0, '#9aa3ad'); gr.addColorStop(1, '#4b535c');
  g.fillStyle = gr; g.fillRect(0, 0, 640, 480);
  g.strokeStyle = 'rgba(255,255,255,.35)'; g.lineWidth = 3; g.strokeRect(40, 40, 560, 400);
  g.fillStyle = '#ffffff'; g.font = '600 40px sans-serif'; g.textAlign = 'center'; g.fillText('Sample photo', 320, 230);
  g.font = '24px sans-serif'; g.fillText(label, 320, 280);
  return c.toDataURL('image/jpeg', 0.7);
}
function loadSample() {
  if (all().some(x => x.sample)) { AK.toast('Sample data is already loaded', 'info'); return; }
  const t = templates().find(x => isVehicle(x)) || starterTemplates()[0];
  const a = fromTemplate(t, { sample: true, customer: { name: 'Sample customer A', phone: '', email: '' }, subject: 'Sample vehicle', ref: 'Sample reading', date: AK.today(-1), inspector: 'Sample inspector', status: 'done', finishedAt: AK.today(-1),
    notes: 'Sample overall note: what to do now and what can wait.' });
  const note = 'Sample note: describe what you found and what you recommend.';
  a.sections.forEach((s, si) => s.items.forEach((it, ii) => { it.status = 'ok'; if (si === 0 && ii === 0) { it.status = 'urgent'; it.note = note; } if ((si === 1 && ii === 0) || (si === 8 && ii === 0)) { it.status = 'attn'; it.note = note; } if (si === 1 && ii === 3) it.status = 'na'; }));
  if (a.sections[0] && a.sections[0].items[0]) {
    const pid = 'ph' + AK.id();
    pics.set('p.' + pid, samplePhoto('Attach up to 3 per check'));
    a.sections[0].items[0].photos = [pid];
  }
  const b = fromTemplate(t, { sample: true, customer: { name: 'Sample customer B', phone: '', email: '' }, subject: 'Sample vehicle', ref: 'Sample reading', date: AK.today(), inspector: 'Sample inspector' });
  b.sections.forEach((s, si) => s.items.forEach((it, ii) => { if (si < 4) it.status = 'ok'; if (si === 2 && ii === 1) { it.status = 'attn'; it.note = note; } }));
  db.set({ inspections: [b, a].concat(all()) });
  q = ''; chipF = 'all';
  AK.toast('Sample data loaded. Clear it any time.', { type: 'ok', undo: () => db.undo() });
  if (app.route().tab !== 'list' || app.route().param) app.go('/list');
}

/* ===== 15. BOOT ===== */
(() => {
  // keep the starter checklists' ids stable from the first visit
  let stored = null; try { stored = localStorage.getItem(db.key); } catch (e) { stored = null; }
  if (!stored && db.persistent) silently(() => db.set({ templates: templates() }));
  // forget photos that no inspection uses any more (deleted inspections, removed photos)
  const used = new Set();
  all().forEach(x => { if (x.signature) used.add(x.signature); x.sections.forEach(s => s.items.forEach(it => (it.photos || []).forEach(p => used.add(p)))); });
  const p = pics.get('p') || {}, gone = Object.keys(p).filter(k => !used.has(k));
  if (gone.length) { const keep = {}; Object.keys(p).forEach(k => { if (used.has(k)) keep[k] = p[k]; }); pics.set({ p: keep }); }
  if (all().some(x => !x.customer || !Array.isArray(x.sections))) silently(() => db.set({ inspections: all().filter(x => x && Array.isArray(x.sections)).map(normInsp) }));
  startApp();
  brand(); badge();
})();
window.IC = { db, pics, counts };
})();
