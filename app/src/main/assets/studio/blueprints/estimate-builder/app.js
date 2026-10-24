/* Estimate Builder | estimates and invoices for a small business, on a phone. Built on appkit (window.AK, see APPKIT.md).
   Screens are <template id="view-..."> blocks in index.html; this file copies them into the page and fills them.
   Sections: 1 facts + saved data  2 money maths  3 small helpers  4 app shell  5 documents list  6 document page
   7 printable document  8 editor  9 customers  10 insights  11 settings  12 sheets (send, payment, signature, customer)
   13 creating and changing documents  14 actions  15 sample data  16 boot */
(() => {
'use strict';

/* ===== 1. FACTS + SAVED DATA ===== */
// The owner's facts arrive as tokens inside <template id="facts"> in index.html; blanks come as visible fallbacks ("Call us", "[...]").
const FACTS = (() => {
  const o = {}, t = document.getElementById('facts');
  if (t) t.content.querySelectorAll('[data-k]').forEach(e => { o[e.dataset.k] = e.textContent.trim(); });
  return o;
})();
const REAL = {
  business: v => !!v && !/^\[.*\]$/.test(v), phone: v => /\d{3}/.test(v || ''),
  email: v => /\S@\S/.test(v || ''), address: v => !!v && !/^\[.*\]$/.test(v)
};
const DEF_SET = {
  business: '', phone: '', email: '', address: '', extra: '',
  currency: 'USD', taxName: 'Tax', taxRate: 0, taxLabour: true, taxParts: true, taxOther: true, labourRate: null,
  estPrefix: 'EST-', estNext: 1001, invPrefix: 'INV-', invNext: 1001, validDays: 30, dueDays: 14,
  estTerms: 'This estimate is valid until the date shown.', invTerms: 'Payment is due by the date shown. Thank you!'
};
const db = AK.store('estimate-builder', { v: 1, docs: [], settings: {} });
// Every document has the same shape (older backups or hand-edited data get the missing parts filled in).
const normDoc = d => Object.assign({ id: AK.id(), status: 'draft', date: '', due: '', log: [], terms: '',
  discount: { mode: 'amount', value: null }, tax: { name: 'Tax', rate: 0, on: { labour: true, parts: true, other: true } } }, d, {
  type: d.type === 'invoice' ? 'invoice' : 'estimate', number: String(d.number || ''),
  customer: Object.assign({ name: '', phone: '', email: '', address: '' }, d.customer || {}), job: Object.assign({ what: '', notes: '' }, d.job || {}),
  lines: Array.isArray(d.lines) ? d.lines : [], payments: Array.isArray(d.payments) ? d.payments : [] });
const set = () => Object.assign({}, DEF_SET, db.get('settings') || {});
const docs = () => db.get('docs') || [];
// Business details for documents: what the owner typed in Settings, else the token (when it is a real value).
const factOf = k => (REAL[k](FACTS[k]) ? FACTS[k] : '');
const biz = () => {
  const s = set();
  return { name: s.business || FACTS.business || '', phone: s.phone || factOf('phone'), email: s.email || factOf('email'), address: s.address || factOf('address'), extra: s.extra || '' };
};
// Changes made by the editor and settings forms are saved "quietly" so the screen is not redrawn under the owner's fingers.
let quiet = 0;
const silently = fn => { quiet++; try { return fn(); } finally { quiet--; } };

/* ===== 2. MONEY MATHS (whole cents, half-up rounding) ===== */
const TYPES = ['labour', 'parts', 'other'];
const TYPE_NAME = { labour: 'Labour', parts: 'Parts', other: 'Other' };
const num = v => {
  if (typeof v === 'number') return isFinite(v) ? v : 0;
  const n = parseFloat(String(v == null ? '' : v).replace(',', '.').replace(/[^\d.-]/g, ''));
  return isFinite(n) ? n : 0;
};
const cents = v => Math.round(AK.parseMoney(v == null ? 0 : v) * 100);
const half = x => Math.sign(x) * Math.round(Math.abs(x) + 1e-7);
const lineCents = l => half(num(l.qty) * cents(l.price));
function totals(d) {
  const by = { labour: 0, parts: 0, other: 0 };
  let sub = 0;
  (d.lines || []).forEach(l => { const a = lineCents(l); by[TYPES.indexOf(l.type) > -1 ? l.type : 'other'] += a; sub += a; });
  const dis = d.discount || {}, dv = num(dis.value);
  let disc = 0;
  if (dv > 0 && sub > 0) disc = dis.mode === 'percent' ? half(sub * Math.min(dv, 100) / 100) : Math.min(cents(dv), sub);
  const tx = d.tax || {}, on = tx.on || {}, rate = Math.max(0, num(tx.rate));
  const base = TYPES.reduce((a, k) => a + (on[k] ? by[k] : 0), 0);
  const taxable = Math.max(0, sub > 0 ? base - half(disc * base / sub) : base);   // the discount is shared out before tax
  const tax = rate > 0 ? half(taxable * rate / 100) : 0;
  const total = sub - disc + tax;
  const paid = cents(d.deposit) + (d.payments || []).reduce((a, p) => a + cents(p.amount), 0);
  const D = c => c / 100;
  return { labour: D(by.labour), parts: D(by.parts), other: D(by.other), sub: D(sub), disc: D(disc), taxable: D(taxable), tax: D(tax), rate,
    total: D(total), paid: D(paid), balance: D(total - paid), cTotal: total, cPaid: paid, cBalance: total - paid, cSub: sub };
}
const taxDesc = d => {
  const tx = d.tax || {}, on = tx.on || {}, list = TYPES.filter(k => on[k]);
  const what = list.length === 3 ? 'everything' : list.length ? list.join(' and ') : 'nothing';
  return (tx.name || 'Tax') + ' ' + AK.fmt.number(num(tx.rate)) + '% on ' + what;
};

/* ===== 3. SMALL HELPERS ===== */
const $ = (s, r) => (r || document).querySelector(s);
const $$ = (s, r) => Array.from((r || document).querySelectorAll(s));
const money = n => AK.fmt.money(n, set().currency);
const today = () => AK.today();
const cur = () => set().currency;
const symbol = () => {
  try { return (0).toLocaleString(AK.locale || navigator.language || 'en-US', { style: 'currency', currency: cur(), maximumFractionDigits: 0 }).replace(/[\d\s., ]/g, '') || '$'; }
  catch (e) { return '$'; }
};
// Copy a <template> from index.html; data-i="name" becomes an icon (data-cls adds a class to it).
function tpl(id) {
  const f = document.getElementById(id).content.cloneNode(true);
  $$('[data-i]', f).forEach(e => { const t = document.createElement('template'); t.innerHTML = String(AK.icon(e.dataset.i, e.dataset.cls)); e.replaceWith(t.content); });
  return f;
}
const pick = (o, p) => p.split('.').reduce((a, k) => (a == null ? a : a[k]), o);
// data-t="path" sets text, data-if="path" (or "!path") shows or hides.
function bind(root, data) {
  $$('[data-t]', root).forEach(e => { const v = pick(data, e.dataset.t); e.textContent = v == null ? '' : v; });
  $$('[data-if]', root).forEach(e => { const k = e.dataset.if, no = k[0] === '!', v = pick(data, no ? k.slice(1) : k); e.hidden = no ? !!v : !v; });
}
const slot = (root, name) => root.querySelector('[data-slot="' + name + '"]');
const sumC = (list, f) => list.reduce((a, x) => a + f(x), 0);
const debounce = (fn, ms) => { let t = 0; const d = (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; d.flush = () => { clearTimeout(t); fn(); }; return d; };
const kindName = d => (d.type === 'invoice' ? 'Invoice' : 'Estimate');
const titleOf = d => (d.customer && d.customer.name) || (d.job && d.job.what) || 'No customer yet';
const addLog = (d, what) => (d.log || []).concat([{ at: new Date().toISOString(), what }]).slice(-40);
const plural = (n, one, many) => n + ' ' + (n === 1 ? one : many || one + 's');
const daysBetween = (a, b) => Math.round((new Date(b + 'T12:00') - new Date(a + 'T12:00')) / 864e5);
const setSymbols = root => $$('.cur-sym', root).forEach(e => { e.textContent = symbol(); });

/* Status: drafts, sent, approved (estimates), paid / part paid / overdue (invoices, worked out from payments), void */
const PILL = { draft: ['Draft', ''], sent: ['Sent', 'info'], approved: ['Approved', 'ok'], invoiced: ['Invoiced', 'ok'],
  paid: ['Paid', 'ok'], partpaid: ['Part paid', 'warn'], overdue: ['Overdue', 'bad'], void: ['Void', 'plain void'] };
const CHIP_OF = { draft: 'draft', sent: 'sent', partpaid: 'sent', overdue: 'sent', approved: 'approved', invoiced: 'approved', paid: 'paid', void: 'void' };
function state(d, t) {
  if (d.status === 'void') return 'void';
  if (d.type === 'invoice') {
    t = t || totals(d);
    if (d.status === 'draft') return 'draft';
    if (t.cTotal > 0 && t.cBalance <= 0) return 'paid';
    if (d.due && d.due < today()) return 'overdue';
    return t.cPaid > 0 ? 'partpaid' : 'sent';
  }
  if (d.convertedTo && docs().some(x => x.id === d.convertedTo)) return 'invoiced';
  return d.status === 'approved' || d.status === 'sent' ? d.status : 'draft';
}
const pillHtml = st => AK.html`<span class="pill ${PILL[st][1]}">${PILL[st][0]}</span>`;
// Every payment with a date (a deposit counts on the document's date).
const paymentsOf = d => (cents(d.deposit) > 0 ? [{ amount: AK.parseMoney(d.deposit), date: d.date, method: 'Deposit', deposit: true }] : [])
  .concat((d.payments || []).map(p => ({ id: p.id, amount: AK.parseMoney(p.amount), date: p.date || d.date, method: p.method || '' })));

/* ===== 4. APP SHELL (the AK.app call itself is in 16. BOOT, after every helper exists) ===== */
let app = null;
let q = '', kindF = 'all', chipF = 'all', custQ = '';
function startApp() {
  app = AK.app({
    title: biz().name || 'Estimates', icon: 'receipt',
    fab: { icon: 'plus', label: 'New estimate', act: 'new-estimate' },
    tabs: [
      { id: 'docs', label: 'Documents', icon: 'file-text', render: renderDocs },
      { id: 'customers', label: 'Customers', icon: 'users', render: renderCustomers },
      { id: 'insights', label: 'Insights', icon: 'chart', render: renderInsights, fab: false },
      { id: 'settings', label: 'Settings', icon: 'settings', render: renderSettings, fab: false }
    ]
  });
  db.subscribe(() => { badge(); if (!quiet) app.refresh(); });
}
function badge() { app.badge('docs', docs().filter(d => d.type === 'invoice' && state(d) === 'overdue').length); }
function brand() { const n = biz().name || 'Estimates'; $$('.app .brand .name').forEach(e => { e.textContent = n; }); }

/* ===== 5. DOCUMENTS LIST ===== */
function renderDocs(page, ctx) {
  if (ctx.param) {
    const p = ctx.param.split('/');
    return p[1] === 'edit' ? renderEditor(page, ctx, p[0]) : renderDoc(page, ctx, p[0]);
  }
  const all = docs(), s = moneySummary(all);
  page.append(tpl('view-docs'));
  bind(page, {
    empty: !all.length, hasSample: all.some(d => d.sample), todayText: AK.fmt.date(new Date(), 'long'),
    summary: all.length ? plural(all.filter(d => d.type === 'estimate' && d.status !== 'void').length, 'estimate') + ' · ' + plural(all.filter(d => d.type === 'invoice' && d.status !== 'void').length, 'invoice') : 'Nothing here yet',
    owed: money(s.owed), owedMeta: s.owedN ? plural(s.owedN, 'invoice') + (s.overdueN ? ' · ' + s.overdueN + ' overdue' : '') : 'All paid up',
    paidMonth: money(s.paidM), paidMeta: s.paidMN ? plural(s.paidMN, 'payment') : 'No payments yet',
    openEst: money(s.openEst), openEstMeta: s.openEstN ? plural(s.openEstN, 'estimate') + ' sent or approved' : 'None waiting'
  });
  if (!all.length) return;   // the welcome card has the buttons (style.css hides the floating one meanwhile)
  const search = $('#doc-q', page), seg = $('.kind-seg', page), chips = slot(page, 'chips'), out = slot(page, 'list');
  search.value = q;
  $$('input[name=kind]', seg).forEach(r => { r.checked = r.value === kindF; });
  const draw = () => {
    const base = all.filter(d => kindF === 'all' || d.type === kindF);
    const counts = { all: base.length };
    base.forEach(d => { const c = CHIP_OF[state(d)]; counts[c] = (counts[c] || 0) + 1; });
    const list = [['all', 'All'], ['draft', 'Draft'], ['sent', 'Sent'], ['approved', 'Approved'], ['paid', 'Paid'], ['void', 'Void']]
      .filter(c => c[0] === 'all' || counts[c[0]] || chipF === c[0]);
    AK.render(chips, list.map(c => AK.html`<button type="button" class="chip" data-chip="${c[0]}" aria-pressed="${chipF === c[0]}">${c[1]} <span class="n">${counts[c[0]] || 0}</span></button>`));
    const words = q.toLowerCase().split(/\s+/).filter(Boolean);
    const shown = base.filter(d => (chipF === 'all' || CHIP_OF[state(d)] === chipF) && (!words.length || words.every(w => hay(d).indexOf(w) > -1)))
      .sort((a, b) => (b.date || '').localeCompare(a.date || '') || (b.created || 0) - (a.created || 0));
    if (!shown.length) {
      AK.render(out, AK.html`<div class="card">${AK.empty({ art: 'search', title: words.length ? 'No matches' : 'Nothing with this status', text: words.length ? 'Check the spelling, or search for a number, a phone or an item instead.' : 'Pick another filter to see more.' })}</div>`);
      return;
    }
    const groups = [];
    shown.forEach(d => { const m = (d.date || '').slice(0, 7), g = groups[groups.length - 1]; if (g && g.m === m) g.items.push(d); else groups.push({ m, items: [d] }); });
    AK.render(out, groups.map(g => AK.html`<section class="month"><h2 class="month-h"><span>${monthName(g.m)}</span><span class="num">${money(sumC(g.items.filter(d => d.status !== 'void'), d => totals(d).cTotal) / 100)}</span></h2>
      <ul class="list">${g.items.map(docRow)}</ul></section>`));
  };
  search.addEventListener('input', () => { q = search.value; draw(); });
  seg.addEventListener('change', e => { kindF = e.target.value; draw(); });
  AK.on(chips, 'click', '.chip', (e, c) => { chipF = c.dataset.chip; draw(); });
  draw();
}
const monthName = m => { if (!m) return 'No date'; const p = m.split('-'); const d = new Date(+p[0], p[1] - 1, 1); return d.toLocaleDateString(AK.locale || navigator.language || 'en-US', d.getFullYear() === new Date().getFullYear() ? { month: 'long' } : { month: 'long', year: 'numeric' }); };
const hay = d => [d.number, d.customer.name, d.customer.phone, d.customer.email, d.job.what, d.job.notes].concat((d.lines || []).map(l => l.desc)).join(' ').toLowerCase();
function docRow(d) {
  const t = totals(d), st = state(d);
  return AK.html`<li><button type="button" class="item doc-row" data-act="open" data-id="${d.id}">
    <span class="lead kind-tag kind-${d.type}" aria-hidden="true">${d.type === 'invoice' ? 'INV' : 'EST'}</span>
    <span class="body"><span class="title">${titleOf(d)}</span><span class="sub">${[d.number, d.job.what, AK.fmt.day(d.date)].filter(Boolean).join(' · ')}</span></span>
    <span class="trail"><span class="value num${st === 'void' ? ' struck' : ''}">${money(st === 'partpaid' || st === 'overdue' ? t.balance : t.total)}</span>${pillHtml(st)}</span></button></li>`;
}
function moneySummary(all) {
  const m = today().slice(0, 7), r = { owed: 0, owedN: 0, overdueN: 0, paidM: 0, paidMN: 0, openEst: 0, openEstN: 0 };
  all.forEach(d => {
    if (d.status === 'void') return;
    const t = totals(d), st = state(d, t);
    if (d.type === 'invoice') {
      if (d.status !== 'draft' && t.cBalance > 0) { r.owed += t.cBalance; r.owedN++; if (st === 'overdue') r.overdueN++; }
      if (d.status !== 'draft') paymentsOf(d).forEach(p => { if ((p.date || '').slice(0, 7) === m) { r.paidM += cents(p.amount); r.paidMN++; } });
    } else if (st === 'sent' || st === 'approved') { r.openEst += t.cTotal; r.openEstN++; }
  });
  r.owed /= 100; r.paidM /= 100; r.openEst /= 100;
  return r;
}

/* ===== 6. DOCUMENT PAGE ===== */
function notFound(page, what) {
  page.append(AK.el('header', { class: 'page-head' }, AK.el('div', {}, AK.el('h1', { text: what + ' not found' }))));
  AK.render(page.appendChild(AK.el('div', { class: 'card' })), AK.empty({ art: 'search', title: 'It may have been deleted', text: 'Go back to your list to find it.', action: { label: 'All documents', act: 'go-docs' } }));
}
function renderDoc(page, ctx, id) {
  const d = db.find('docs', id);
  if (!d) return notFound(page, 'Document');
  const t = totals(d), st = state(d, t), est = d.type === 'estimate';
  page.append(tpl('view-doc'));
  ctx.title = d.number;
  const due = d.due ? AK.fmt.date(d.due) : '';
  let hint = '';
  if (st === 'overdue') hint = 'Overdue by ' + plural(daysBetween(d.due, today()), 'day');
  else if (st === 'paid') hint = 'Paid in full';
  else if (st === 'void') hint = 'Void: not counted in totals';
  else if (st === 'invoiced') hint = 'Turned into an invoice';
  else if (due) hint = (est ? 'Valid until ' : 'Due ') + due;
  bind(page, {
    kindTitle: kindName(d), number: d.number, title: titleOf(d),
    dateLine: [d.job.what, AK.fmt.date(d.date)].filter(Boolean).join(' · '),
    totalLabel: est ? 'Estimate total' : 'Invoice total', totalText: money(t.total), dueHint: hint,
    showPaid: t.cPaid > 0 && st !== 'void', paidText: money(t.paid), balanceText: money(t.balance)
  });
  slot(page, 'pill').innerHTML = String(pillHtml(st));
  const prog = slot(page, 'progress');
  prog.style.setProperty('--value', t.cTotal > 0 ? Math.min(100, Math.round(t.cPaid / t.cTotal * 100)) : 0);
  AK.render(slot(page, 'next'), nextActions(d, st));
  const pays = paymentsOf(d);
  AK.render(slot(page, 'payments'), pays.map(p => AK.html`<li><span class="pay-dot" aria-hidden="true"></span><span class="pay-what">${p.method || 'Payment'} · ${AK.fmt.date(p.date)}</span><span class="num">${money(p.amount)}</span>${p.id ? AK.html`<button type="button" class="btn icon ghost sm" data-act="del-payment" data-id="${d.id}" data-pay="${p.id}" aria-label="Remove this payment">${AK.icon('x')}</button>` : ''}</li>`));
  slot(page, 'paper').append(paper(d));
  AK.render(slot(page, 'customer'), customerCard(d));
  AK.render(slot(page, 'more'), moreList(d, st));
  AK.render(slot(page, 'log'), (d.log || []).slice().reverse().map(l => AK.html`<li><span class="tl-dot" aria-hidden="true"></span><span class="tl-what">${l.what}</span><span class="tl-when">${AK.fmt.date(l.at)} · ${AK.fmt.time(l.at)}</span></li>`));
}
function nextActions(d, st) {
  const b = (act, icon, label, primary) => AK.html`<button type="button" class="btn${primary ? ' primary' : ''}" data-act="${act}" data-id="${d.id}">${AK.icon(icon)}${label}</button>`;
  if (st === 'void') return b('restore', 'undo', 'Restore', true);
  if (st === 'draft') return [b('send', 'send', 'Send to customer', true), b('edit', 'edit', 'Edit')];
  if (d.type === 'estimate') {
    if (st === 'sent') return [b('approve', 'check', 'Mark approved', true), b('sign', 'edit', 'Customer signs')];
    if (st === 'approved') return [b('convert', 'receipt', 'Turn into invoice', true), b('edit', 'edit', 'Edit')];
    return AK.html`<button type="button" class="btn" data-act="open" data-id="${d.convertedTo}">${AK.icon('receipt')}Open the invoice</button>`;
  }
  if (st === 'paid') return [b('duplicate', 'copy', 'Duplicate'), b('send', 'send', 'Send receipt')];
  return [b('pay', 'dollar', 'Record payment', true), b('send', 'send', st === 'overdue' ? 'Send a reminder' : 'Send again')];
}
function moreList(d, st) {
  const row = (act, icon, title, sub, danger) => AK.html`<li><button type="button" class="item${danger ? ' danger' : ''}" data-act="${act}" data-id="${d.id}"><span class="lead">${AK.icon(icon)}</span><span class="body"><span class="title">${title}</span><span class="sub">${sub}</span></span></button></li>`;
  const out = [row('edit', 'edit', 'Edit', 'Change lines, customer, dates or terms')];
  if (d.type === 'estimate' && st !== 'void' && st !== 'invoiced') {
    out.push(row('sign', 'edit', 'Customer signs', d.signature ? 'Signed. Sign again to replace it' : 'Sign on this screen to approve'));
    if (st !== 'approved') out.push(row('convert', 'receipt', 'Turn into invoice', 'Copies everything to a new invoice'));
  }
  if (d.type === 'invoice' && st !== 'void' && st !== 'paid') out.push(row('mark-paid', 'check-circle', 'Mark paid in full', 'Records a payment for the balance, today'));
  if (d.from && docs().some(x => x.id === d.from)) out.push(row('open-from', 'file-text', 'Open the estimate', 'The estimate this invoice came from'));
  out.push(row('duplicate', 'copy', 'Duplicate', 'A new draft with the same lines'));
  if (st !== 'void') out.push(row('void', 'x-circle', 'Void', 'Keep it, but stop counting it'));
  out.push(row('delete', 'trash', 'Delete', 'Remove it from this phone', true));
  return out;
}
function customerCard(d) {
  const c = d.customer || {};
  if (!c.name && !c.phone && !c.email) return AK.html`<p class="muted">No customer details yet.</p><div class="btns"><button type="button" class="btn sm" data-act="edit" data-id="${d.id}">${AK.icon('edit')}Add a customer</button></div>`;
  return AK.html`<div class="cust-mini">${AK.avatar(c.name || '?')}<div><p class="cust-name">${c.name || 'No name'}</p>${c.phone ? AK.html`<p class="muted small">${c.phone}</p>` : ''}${c.email ? AK.html`<p class="muted small">${c.email}</p>` : ''}${c.address ? AK.html`<p class="muted small pre">${c.address}</p>` : ''}</div></div>
    <div class="btns fill contact-btns">${contactButtons(c)}</div>`;
}
function contactButtons(c) {
  const first = (c.name || '').split(' ')[0];
  return [
    c.phone ? AK.html`<a class="btn sm" href="${AK.tel(c.phone)}">${AK.icon('phone')}Call</a>` : AK.html`<button type="button" class="btn sm" disabled>${AK.icon('phone')}Call</button>`,
    c.phone ? AK.html`<a class="btn sm" href="${AK.sms(c.phone, first ? 'Hi ' + first + ', ' : '')}">${AK.icon('message')}Text</a>` : AK.html`<button type="button" class="btn sm" disabled>${AK.icon('message')}Text</button>`,
    c.email ? AK.html`<a class="btn sm" href="${AK.mail(c.email, '', '')}">${AK.icon('mail')}Email</a>` : AK.html`<button type="button" class="btn sm" disabled>${AK.icon('mail')}Email</button>`
  ];
}

/* ===== 7. PRINTABLE DOCUMENT ===== */
function paper(d) {
  const f = tpl('doc-paper'), t = totals(d), st = state(d, t), est = d.type === 'estimate', b = biz(), c = d.customer || {};
  const dis = d.discount || {};
  bind(f, {
    stamp: st === 'paid' ? 'Paid' : st === 'void' ? 'Void' : '',
    biz: Object.assign({}, b, { both: b.phone && b.email }), kindTitle: kindName(d), number: d.number,
    toLabel: est ? 'Prepared for' : 'Bill to', cust: { name: c.name || '-', phone: c.phone, email: c.email, address: c.address },
    what: d.job.what, dateText: AK.fmt.date(d.date) || '-', dueLabel: est ? 'Valid until' : 'Due date', dueText: d.due ? AK.fmt.date(d.due) : '',
    t, subText: money(t.sub), discLabel: dis.mode === 'percent' ? 'Discount (' + AK.fmt.number(num(dis.value)) + '%)' : 'Discount', discText: '-' + money(t.disc),
    taxLabel: taxDesc(d), taxText: money(t.tax), totalText: money(t.total),
    paidLabel: d.payments && d.payments.length ? 'Paid' : 'Deposit received', paidText: '-' + money(t.paid), balanceText: money(t.balance),
    notes: d.job.notes, terms: d.terms, signLabel: est ? 'Customer approval' : 'Customer signature',
    signDate: d.signature ? AK.fmt.date(d.signedAt) : '',
    footer: [b.name, b.phone, b.email].filter(Boolean).join(' · ')
  });
  const stamp = f.querySelector('.stamp');
  if (stamp) stamp.classList.add(st);
  const body = slot(f, 'lines'), lines = (d.lines || []).filter(l => l.desc || lineCents(l));
  const used = TYPES.filter(k => lines.some(l => (l.type || 'other') === k));
  const row = l => AK.html`<tr><td>${l.desc || TYPE_NAME[l.type] || 'Item'}<span class="ln-calc">${AK.fmt.number(num(l.qty))} × ${money(cents(l.price) / 100)}</span></td><td class="num c-qty">${AK.fmt.number(num(l.qty))}</td><td class="num c-price">${money(cents(l.price) / 100)}</td><td class="num">${money(lineCents(l) / 100)}</td></tr>`;
  if (!lines.length) AK.render(body, AK.html`<tr><td colspan="4" class="muted">No lines yet</td></tr>`);
  else if (used.length < 2) AK.render(body, lines.map(row));
  else AK.render(body, used.map(k => AK.html`<tr class="grp"><th scope="rowgroup">${TYPE_NAME[k]}</th><td class="c-qty"></td><td class="c-price"></td><td class="num">${lines.filter(l => (l.type || 'other') === k).length > 1 ? money(t[k]) : ''}</td></tr>${lines.filter(l => (l.type || 'other') === k).map(row)}`));
  if (d.signature) slot(f, 'sig').innerHTML = String(AK.html`<img class="sig-img" src="${d.signature}" alt="Signature of ${d.signedBy || 'the customer'}" width="300" height="95">${d.signedBy ? AK.html`<span class="sig-name">${d.signedBy}</span>` : ''}`);
  return f;
}

/* ===== 8. EDITOR (saves as you type) ===== */
function renderEditor(page, ctx, id) {
  const d0 = db.find('docs', id);
  if (!d0) return notFound(page, 'Document');
  page.append(tpl('view-editor'));
  ctx.title = 'Edit ' + d0.number;
  const form = $('#ed-form', page), el = n => form.elements[n], linesEl = $('.ed-lines', form);
  const saveState = $('.save-state', page), titleEl = $('.ed-title', page), dueLabel = $('.due-label', page);
  setSymbols(form);
  // fill the form
  el('type').value = d0.type; el('number').value = d0.number; el('date').value = d0.date || ''; el('due').value = d0.due || '';
  el('cname').value = d0.customer.name || ''; el('cphone').value = d0.customer.phone || ''; el('cemail').value = d0.customer.email || ''; el('caddress').value = d0.customer.address || '';
  el('what').value = d0.job.what || ''; el('notes').value = d0.job.notes || ''; el('terms').value = d0.terms || '';
  el('discMode').value = (d0.discount && d0.discount.mode) || 'amount'; el('discValue').value = d0.discount && d0.discount.value != null ? d0.discount.value : '';
  el('taxRate').value = AK.fmt.number(num(d0.tax && d0.tax.rate)).replace(/,/g, '');
  TYPES.forEach(k => { el('tax' + TYPE_NAME[k]).checked = !!(d0.tax && d0.tax.on && d0.tax.on[k]); });
  el('deposit').value = cents(d0.deposit) ? (cents(d0.deposit) / 100).toFixed(2) : '';
  (d0.lines || []).forEach(l => linesEl.append(lineRow(l)));
  let lastType = d0.type;

  const read = () => {
    const cur0 = db.find('docs', id) || d0;
    return Object.assign({}, cur0, {
      type: el('type').value || 'estimate', number: el('number').value.trim(), date: el('date').value, due: el('due').value,
      customer: { name: el('cname').value.trim(), phone: el('cphone').value.trim(), email: el('cemail').value.trim(), address: el('caddress').value.trim() },
      job: { what: el('what').value.trim(), notes: el('notes').value.trim() },
      lines: $$('.ln', linesEl).map(li => ({ id: li.dataset.id, type: $('[name=ltype]', li).value, desc: $('[name=ldesc]', li).value.trim(),
        qty: $('[name=lqty]', li).value.trim() === '' ? null : num($('[name=lqty]', li).value), price: $('[name=lprice]', li).value.trim() === '' ? null : AK.parseMoney($('[name=lprice]', li).value) })),
      discount: { mode: el('discMode').value || 'amount', value: el('discValue').value.trim() === '' ? null : num(el('discValue').value) },
      tax: { name: (cur0.tax && cur0.tax.name) || set().taxName || 'Tax', rate: num(el('taxRate').value), on: { labour: el('taxLabour').checked, parts: el('taxParts').checked, other: el('taxOther').checked } },
      deposit: el('deposit').value.trim() === '' ? null : AK.parseMoney(el('deposit').value),
      terms: el('terms').value, updated: Date.now()
    });
  };
  const show = d => {
    const t = totals(d), m = n => money(n);
    titleEl.textContent = kindName(d) + ' ' + (d.number || '');
    dueLabel.textContent = d.type === 'invoice' ? 'Due date' : 'Valid until';
    TYPES.forEach(k => { const r = $('.by-type[data-type="' + k + '"]', form); r.hidden = !t[k]; r.lastElementChild.textContent = m(t[k]); });
    $('.t-sub', form).textContent = m(t.sub); $('.t-disc', form).textContent = t.disc ? '-' + m(t.disc) : m(0);
    $('.t-taxname', form).textContent = (d.tax.name || 'Tax') + ' ' + AK.fmt.number(t.rate) + '%';
    $('.t-tax', form).textContent = m(t.tax); $('.t-taxdesc', form).textContent = taxDesc(d);
    $$('.t-total', form).forEach(e => { e.textContent = m(t.total); });
    const payC = sumC(d.payments || [], p => cents(p.amount));
    $('.t-payrow', form).hidden = !payC; $('.t-pays', form).textContent = '-' + m(payC / 100);
    $('.t-bal', form).textContent = m(t.balance);
    $$('.ln', linesEl).forEach((li, i) => { li.querySelector('output').textContent = m(lineCents(d.lines[i] || {}) / 100); });
    const n = d.lines.length;
    $('.lines-count', form).textContent = n ? plural(n, 'line') : '';
    $('.lines-empty', form).hidden = n > 0;
    const dup = d.number && docs().some(x => x.id !== id && x.number.toLowerCase() === d.number.toLowerCase());
    $('.warn-help', form).hidden = !dup;
  };
  // saved quietly while the editor is open; an Undo tapped after leaving saves normally so the screen on show is redrawn
  const save = debounce(() => {
    const d = read();
    if (form.isConnected) silently(() => db.update('docs', id, d)); else db.update('docs', id, d);
    saveState.textContent = 'All changes saved';
  }, 350);
  const changed = () => {
    const d = read();
    if (d.type !== lastType) { switchType(d, lastType); lastType = d.type; el('number').value = d.number; el('due').value = d.due; el('terms').value = d.terms; }
    saveState.textContent = 'Saving...';
    show(d); save();
  };
  form.addEventListener('input', changed);
  form.addEventListener('change', changed);
  form.addEventListener('submit', e => e.preventDefault());
  // lines: add, remove (with undo), reorder
  AK.on(form, 'click', '[data-add]', (e, b) => {
    const type = b.dataset.add, rate = type === 'labour' ? set().labourRate : null;
    const li = lineRow({ id: AK.id(), type, desc: '', qty: 1, price: rate ? AK.parseMoney(rate) : null });
    linesEl.append(li); changed();
    $('[name=ldesc]', li).focus();
  });
  AK.on(linesEl, 'click', '.ln-del', (e, b) => {
    const li = b.closest('.ln'), next = li.nextElementSibling;
    li.remove(); changed();
    AK.toast('Line removed', { undo: () => { if (next && next.parentNode === linesEl) linesEl.insertBefore(li, next); else linesEl.append(li); changed(); } });
  });
  AK.drag(linesEl, { handle: '.handle', onSort: changed });
  // suggestions: past customers on the name, past lines on each description
  suggest(el('cname'), qq => customerList().filter(c => match(c.name + ' ' + c.phone + ' ' + c.email, qq)).slice(0, 5)
    .map(c => ({ label: c.name, sub: [c.phone, c.what].filter(Boolean).join(' · ') || plural(c.docs.length, 'document'), value: c })), c => {
    el('cname').value = c.name; el('cphone').value = c.phone || ''; el('cemail').value = c.email || ''; el('caddress').value = c.address || '';
    if (!el('what').value.trim() && c.what) el('what').value = c.what;
    changed();
  });
  AK.on(linesEl, 'focusin', '[name=ldesc]', (e, inp) => {
    if (inp._sg) return;
    suggest(inp, qq => pastLines().filter(l => match(l.desc, qq)).slice(0, 6).map(l => ({ label: l.desc, sub: TYPE_NAME[l.type] + ' · ' + money(cents(l.price) / 100), value: l })), l => {
      const li = inp.closest('.ln');
      inp.value = l.desc; $('[name=ltype]', li).value = l.type;
      const pr = $('[name=lprice]', li);
      if (!pr.value.trim() || !AK.parseMoney(pr.value)) pr.value = (cents(l.price) / 100).toFixed(2);
      changed();
    });
  });
  show(read());
  if (!d0.customer.name && !(d0.lines || []).length) setTimeout(() => { if (el('cname').isConnected && !AK.still()) el('cname').focus({ preventScroll: true }); }, 60);
  const hide = () => { if (document.visibilityState === 'hidden') save.flush(); };
  document.addEventListener('visibilitychange', hide);
  // leaving the editor: save now; a brand-new document that is still empty is thrown away (and its number given back)
  return () => {
    if (location.hash.replace(/^#\/?/, '') === 'docs/' + id + '/edit') return;
    document.removeEventListener('visibilitychange', hide);
    save.flush();
    const d = db.find('docs', id);
    if (d && isBlank(d) && d.status === 'draft' && !(d.payments || []).length) silently(() => { releaseNumber(d.type, d.number); db.remove('docs', id); });
  };
}
function lineRow(l) {
  const f = tpl('row-line'), li = f.firstElementChild;
  li.dataset.id = l.id || AK.id();
  $('[name=ltype]', li).value = TYPES.indexOf(l.type) > -1 ? l.type : 'other';
  $('[name=ldesc]', li).value = l.desc || '';
  $('[name=lqty]', li).value = l.qty == null ? '' : AK.fmt.number(num(l.qty)).replace(/,/g, '');
  $('[name=lprice]', li).value = l.price == null || l.price === '' ? '' : (cents(l.price) / 100).toFixed(2);
  setSymbols(li);
  return li;
}
const isBlank = d => !d.customer.name && !d.customer.phone && !d.customer.email && !d.job.what && !d.job.notes && !(d.lines || []).some(l => l.desc || cents(l.price)) && !cents(d.deposit);
// When an estimate becomes an invoice in the editor (or back), it takes a number, dates and terms of the new kind.
function switchType(d, from) {
  const s = set(), oldP = from === 'invoice' ? s.invPrefix : s.estPrefix;
  if (!d.number || d.number.indexOf(oldP) === 0) { releaseNumber(from, d.number); d.number = takeNumber(d.type); }
  d.due = AK.today(d.type === 'invoice' ? +s.dueDays || 0 : +s.validDays || 0);
  if (!d.terms.trim() || d.terms === (from === 'invoice' ? s.invTerms : s.estTerms)) d.terms = d.type === 'invoice' ? s.invTerms : s.estTerms;
}
const match = (text, qq) => { const ws = qq.toLowerCase().split(/\s+/).filter(Boolean); const h = String(text || '').toLowerCase(); return ws.length > 0 && ws.every(w => h.indexOf(w) > -1); };
function pastLines() {
  const seen = new Map();
  docs().slice().sort((a, b) => (a.updated || 0) - (b.updated || 0)).forEach(d => (d.lines || []).forEach(l => { if (l.desc) seen.set(l.desc.toLowerCase(), l); }));
  return Array.from(seen.values()).reverse();
}
// A small suggestion list under an input; tap (or Enter on a highlighted row) picks one.
function suggest(input, source, onPick) {
  input._sg = true;
  const wrap = input.closest('.suggest-wrap') || input.parentElement;
  const box = AK.el('ul', { class: 'suggest', role: 'listbox', hidden: true });
  wrap.append(box);
  input.setAttribute('aria-autocomplete', 'list');
  let items = [], hi = -1;
  const close = () => { box.hidden = true; hi = -1; input.removeAttribute('aria-expanded'); };
  const draw = () => {
    items = input.value.trim() ? source(input.value.trim()) : [];
    if (!items.length || (items.length === 1 && items[0].label.toLowerCase() === input.value.trim().toLowerCase())) return close();
    AK.render(box, items.map((it, i) => AK.html`<li role="option" aria-selected="${i === hi}"><button type="button" tabindex="-1" data-i="${i}"><span class="sg-title">${it.label}</span><span class="sg-sub">${it.sub}</span></button></li>`));
    box.hidden = false; input.setAttribute('aria-expanded', 'true');
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
  box.addEventListener('click', e => { const b = e.target.closest('[data-i]'); if (!b) return; const it = items[+b.dataset.i]; close(); onPick(it.value); input.focus(); });
}

/* ===== 9. CUSTOMERS (built from the documents) ===== */
function customerList() {
  const map = new Map();
  docs().slice().sort((a, b) => (a.date || '').localeCompare(b.date || '') || (a.updated || 0) - (b.updated || 0)).forEach(d => {
    const n = (d.customer.name || '').trim();
    if (!n) return;
    const k = n.toLowerCase().replace(/\s+/g, ' ');
    const c = map.get(k) || { key: k, name: n, phone: '', email: '', address: '', what: '', docs: [], billed: 0, owed: 0, last: '' };
    c.name = n; ['phone', 'email', 'address'].forEach(f => { if (d.customer[f]) c[f] = d.customer[f]; });
    if (d.job.what) c.what = d.job.what;
    c.docs.push(d); c.last = d.date || c.last;
    if (d.type === 'invoice' && d.status !== 'void' && d.status !== 'draft') { const t = totals(d); c.billed += t.cTotal; if (t.cBalance > 0) c.owed += t.cBalance; }
    if (d.type === 'estimate' && d.status !== 'void') c.quoted = (c.quoted || 0) + totals(d).cTotal;
    map.set(k, c);
  });
  return Array.from(map.values());
}
function renderCustomers(page) {
  const list = customerList();
  page.append(tpl('view-customers'));
  bind(page, { any: list.length > 0, summary: list.length ? plural(list.length, 'customer') + ' · from your estimates and invoices' : 'Customers appear here as you make documents' });
  const out = slot(page, 'list'), search = $('#cust-q', page);
  const draw = () => {
    const shown = list.filter(c => !custQ.trim() || match([c.name, c.phone, c.email, c.what].join(' '), custQ)).sort((a, b) => b.owed - a.owed || a.name.localeCompare(b.name));
    if (!list.length) { AK.render(out, AK.html`<div class="card">${AK.empty({ art: 'people', title: 'No customers yet', text: 'Each estimate or invoice you make adds its customer here, with their phone, email and what they owe.', action: { label: 'New estimate', act: 'new-estimate' } })}</div>`); return; }
    if (!shown.length) { AK.render(out, AK.html`<div class="card">${AK.empty({ art: 'search', title: 'Nobody matches', text: 'Try part of a name, a phone number or an email.' })}</div>`); return; }
    AK.render(out, AK.html`<ul class="list">${shown.map(c => AK.html`<li><button type="button" class="item" data-act="open-customer" data-key="${c.key}">${AK.avatar(c.name)}
      <span class="body"><span class="title">${c.name}</span><span class="sub">${[c.phone, plural(c.docs.length, 'document')].filter(Boolean).join(' · ')}</span></span>
      <span class="trail">${c.owed > 0 ? AK.html`<span class="value num">${money(c.owed / 100)}</span><span class="sub owes">owes</span>`
        : c.billed > 0 || !c.quoted ? AK.html`<span class="value num">${money(c.billed / 100)}</span><span class="sub">billed</span>` : AK.html`<span class="value num">${money(c.quoted / 100)}</span><span class="sub">quoted</span>`}</span>${AK.icon('chevron-right', 'chev')}</button></li>`)}</ul>`);
  };
  if (search) { search.value = custQ; search.addEventListener('input', () => { custQ = search.value; draw(); }); }
  draw();
}

/* ===== 10. INSIGHTS ===== */
function renderInsights(page) {
  const all = docs().filter(d => d.status !== 'void'), inv = all.filter(d => d.type === 'invoice' && d.status !== 'draft');
  page.append(tpl('view-insights'));
  const m = today().slice(0, 7), y = today().slice(0, 4);
  const billedM = sumC(inv.filter(d => (d.date || '').slice(0, 7) === m), d => totals(d).cTotal);
  const paysM = [].concat(...inv.map(paymentsOf)).filter(p => (p.date || '').slice(0, 7) === m);
  const est = all.filter(d => d.type === 'estimate' && d.status !== 'draft'), won = est.filter(d => ['approved', 'invoiced'].indexOf(state(d)) > -1);
  const avg = inv.length ? sumC(inv, d => totals(d).cTotal) / inv.length / 100 : 0;
  bind(page, {
    empty: !docs().length, monthText: AK.fmt.date(new Date(), 'full'),
    billedM: money(billedM / 100), billedMeta: plural(inv.filter(d => (d.date || '').slice(0, 7) === m).length, 'invoice'),
    paidM: money(sumC(paysM, p => cents(p.amount)) / 100), paidMeta: plural(paysM.length, 'payment'),
    avg: money(avg), avgMeta: inv.length ? 'Across ' + plural(inv.length, 'invoice') : 'No invoices sent yet',
    winRate: est.length ? AK.fmt.percent(won.length / est.length) : '-', winMeta: est.length ? won.length + ' of ' + plural(est.length, 'sent estimate') : 'No estimates sent yet'
  });
  if (!docs().length) { AK.render(slot(page, 'empty'), AK.empty({ art: 'chart', title: 'Numbers show up here', text: 'Once you send invoices, this page shows what you billed, what came in, and where the money comes from.', action: { label: 'Try it with sample data', act: 'load-sample' } })); return; }
  // last 6 months, billed vs collected
  const months = [];
  for (let i = 5; i >= 0; i--) { const d = new Date(); d.setDate(1); d.setMonth(d.getMonth() - i); months.push(d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0')); }
  const label = k => new Date(+k.slice(0, 4), +k.slice(5) - 1, 1).toLocaleDateString(AK.locale || navigator.language || 'en-US', { month: 'short' });
  const allPays = [].concat(...inv.map(paymentsOf));
  AK.chart.bar(slot(page, 'chart'), { labels: months.map(label), format: 'money', title: 'Billed and collected by month',
    series: [{ name: 'Billed', values: months.map(k => sumC(inv.filter(d => (d.date || '').slice(0, 7) === k), d => totals(d).cTotal) / 100) },
      { name: 'Collected', values: months.map(k => sumC(allPays.filter(p => (p.date || '').slice(0, 7) === k), p => cents(p.amount)) / 100) }] });
  const yearInv = inv.filter(d => (d.date || '').slice(0, 4) === y), mix = TYPES.map(k => sumC(yearInv, d => Math.round(totals(d)[k] * 100)) / 100);
  const keep = TYPES.map((k, i) => i).filter(i => mix[i] > 0);
  if (keep.length) AK.chart.donut(slot(page, 'mix'), { labels: keep.map(i => TYPE_NAME[TYPES[i]]), values: keep.map(i => mix[i]), format: 'money', center: 'this year', title: 'Invoice lines by type' });
  else AK.render(slot(page, 'mix'), AK.html`<p class="muted">No invoices sent this year yet.</p>`);
  const top = customerList().filter(c => c.billed > 0).sort((a, b) => b.billed - a.billed).slice(0, 5);
  AK.render(slot(page, 'top'), top.length ? top.map((c, i) => AK.html`<li><button type="button" class="item" data-act="open-customer" data-key="${c.key}"><span class="lead rank">${i + 1}</span><span class="body"><span class="title">${c.name}</span><span class="sub">${plural(c.docs.length, 'document')}</span></span><span class="trail"><span class="value num">${money(c.billed / 100)}</span></span></button></li>`)
    : AK.html`<li class="item"><span class="body"><span class="sub">Customers you invoice show up here.</span></span></li>`);
}

/* ===== 11. SETTINGS (saves as you type) ===== */
function renderSettings(page) {
  page.append(tpl('view-settings'));
  const form = $('#set-form', page), s = set(), b = biz(), saveState = $('.save-state', page);
  setSymbols(page);
  AK.fill(form, Object.assign({}, s, { business: REAL.business(b.name) ? b.name : s.business, phone: b.phone, email: b.email, address: b.address, labourRate: s.labourRate == null || s.labourRate === '' ? '' : s.labourRate }));
  const used = (() => { try { return (localStorage.getItem(db.key) || '').length * 2; } catch (e) { return 0; } })();
  bind(page, { bizName: b.name, storeText: (db.persistent ? 'Everything is saved on this phone only. ' : 'Private mode: nothing is kept after this page closes. ') + 'Using about ' + Math.max(1, Math.round(used / 1024)) + ' KB.' });
  const taxPill = () => { const v = set(); $('.tax-pill', page).textContent = AK.fmt.number(num(v.taxRate)) + '%'; };
  taxPill();
  const save = debounce(() => {
    const v = AK.values(form), next = Object.assign({}, set());
    ['extra', 'taxName', 'estPrefix', 'invPrefix', 'estTerms', 'invTerms', 'currency'].forEach(k => { next[k] = typeof v[k] === 'string' ? v[k] : next[k]; });
    // business facts: keep an override only when it differs from the token
    ['phone', 'email', 'address'].forEach(k => { const val = (v[k] || '').trim(); next[k] = val === factOf(k) ? '' : val; });
    const nm = (v.business || '').trim(); next.business = nm === FACTS.business ? '' : nm;
    next.taxRate = Math.min(100, Math.max(0, num(v.taxRate)));
    next.taxLabour = !!v.taxLabour; next.taxParts = !!v.taxParts; next.taxOther = !!v.taxOther;
    next.labourRate = v.labourRate == null ? null : v.labourRate;
    ['estNext', 'invNext'].forEach(k => { next[k] = Math.max(1, Math.round(num(v[k])) || 1); });
    ['validDays', 'dueDays'].forEach(k => { next[k] = Math.max(0, Math.min(365, Math.round(num(v[k])))); });
    next.taxName = (next.taxName || '').trim() || 'Tax';
    silently(() => db.set({ settings: next }));
    AK.currency = next.currency; brand(); taxPill(); setSymbols(page);
    saveState.textContent = 'Saved';
  }, 450);
  form.addEventListener('input', () => { saveState.textContent = 'Saving...'; save(); });
  form.addEventListener('change', () => { saveState.textContent = 'Saving...'; save(); });
  form.addEventListener('submit', e => e.preventDefault());
  $('#restore-input', page).addEventListener('change', e => { const f = e.target.files[0]; e.target.value = ''; if (f) f.text().then(restoreText); });
  return () => save.flush();
}
function backupText() { return JSON.stringify({ app: 'estimate-builder', saved: new Date().toISOString(), data: db.get() }, null, 1); }
function restoreText(text) {
  let j = null;
  try { j = JSON.parse(String(text || '').trim()); } catch (e) { j = null; }
  const data = j && j.data ? j.data : j;
  if (!data || !Array.isArray(data.docs)) { AK.toast('That is not a backup from this app.', 'bad'); return false; }
  AK.confirm('Replace everything with this backup?', { text: plural(data.docs.length, 'document') + ' in the backup. What is on this phone now is replaced.', ok: 'Restore', danger: true }).then(ok => {
    if (!ok) return;
    db.reset({ v: 1, docs: data.docs.filter(x => x && typeof x === 'object').map(normDoc), settings: data.settings || {} });
    AK.currency = set().currency; brand();
    AK.toast('Backup restored', { type: 'ok', undo: () => db.undo() });
  });
  return true;
}

/* ===== 12. SHEETS: send, payment, signature, customer ===== */
function messageFor(d) {
  const t = totals(d), b = biz(), st = state(d, t), who = (d.customer.name || '').trim();
  const lines = [(who ? 'Hi ' + who + ', ' : 'Hello, ') + (d.type === 'invoice' ? (st === 'paid' ? 'thank you, invoice ' + d.number + ' is paid in full.' : 'here is invoice ' + d.number + '.') : 'here is estimate ' + d.number + '.')];
  if (d.job.what) lines.push('For: ' + d.job.what);
  lines.push('Total: ' + money(t.total));
  if (t.cPaid > 0 && st !== 'paid') lines.push('Paid so far: ' + money(t.paid), 'Balance due: ' + money(t.balance));
  if (d.due && st !== 'paid') lines.push((d.type === 'invoice' ? 'Due: ' : 'Valid until: ') + AK.fmt.date(d.due));
  if (st === 'overdue') lines.push('This invoice is now past its due date.');
  lines.push('', 'Thank you,', [b.name, b.phone].filter(Boolean).join(' · '));
  return lines.join('\n');
}
function openSend(d) {
  const body = tpl('sheet-send'), c = d.customer;
  const L = AK.sheet({ title: 'Send ' + d.number, body });
  const B = L.body, msg = $('#send-msg', B), sms = $('.send-sms', B), mail = $('.send-mail', B), mark = $('.send-mark', B);
  msg.value = messageFor(d);
  $('.send-sms-to', B).textContent = c.phone ? 'To ' + c.phone : 'Opens your messages app';
  $('.send-mail-to', B).textContent = c.email ? 'To ' + c.email : 'Opens your email app';
  mark.hidden = d.status !== 'draft';
  const links = () => { sms.href = AK.sms(c.phone, msg.value); mail.href = AK.mail(c.email, kindName(d) + ' ' + d.number + (biz().name ? ' from ' + biz().name : ''), msg.value); };
  links(); msg.addEventListener('input', links);
  const sent = how => { if (d.status === 'draft' && $('input', mark).checked) markSent(d.id, how); };
  sms.addEventListener('click', () => { sent('Sent by text message'); setTimeout(() => L.close(), 300); });
  mail.addEventListener('click', () => { sent('Sent by email'); setTimeout(() => L.close(), 300); });
  $('.send-copy', B).addEventListener('click', () => { AK.copy(msg.value, { done: 'Message copied' }); sent('Message copied to send'); });
  $('.send-print', B).addEventListener('click', () => { sent('Printed'); L.close(); setTimeout(() => printDoc(d), 250); });
}
function markSent(id, how) {
  const d = db.find('docs', id);
  if (!d || d.status !== 'draft') return;
  db.update('docs', id, { status: 'sent', sentAt: today(), log: addLog(d, how || 'Marked as sent') });
}
function printDoc(d) {
  const el = $('.dv-paper .doc');
  if (el) AK.print(kindName(d) + ' ' + d.number, el);
  else { const host = AK.el('div', { class: 'print-host' }); host.append(paper(d)); document.body.append(host); AK.print(kindName(d) + ' ' + d.number, host.firstElementChild); setTimeout(() => host.remove(), 1500); }
}
function openPayment(d) {
  const t = totals(d);
  AK.modal({ title: 'Record a payment', body: tpl('modal-payment'),
    onOpen: L => {
      setSymbols(L.body);
      AK.fill($('form', L.body), { amount: t.balance > 0 ? t.balance : '', date: today() });
      $('.pay-help', L.body).textContent = 'Balance due ' + money(t.balance) + ' of ' + money(t.total);
    },
    actions: [{ label: 'Cancel' }, { label: 'Save payment', primary: true, onClick: L => {
      const r = AK.validate($('form', L.body), { amount: { min: 0.01, message: 'Enter the amount paid' } });
      if (!r.ok) return false;
      addPayment(d.id, r.values.amount, r.values.date, r.values.method);
    } }] });
}
function addPayment(id, amount, date, method) {
  const d = db.find('docs', id);
  if (!d) return;
  const p = { id: AK.id(), amount: Math.round(AK.parseMoney(amount) * 100) / 100, date: date || today(), method: method || 'Payment' };
  const next = Object.assign({}, d, { payments: (d.payments || []).concat([p]), status: d.status === 'draft' ? 'sent' : d.status });
  const after = totals(next);
  next.log = addLog(d, 'Payment of ' + money(p.amount) + ' (' + p.method + ')' + (after.cBalance <= 0 ? ', paid in full' : ''));
  db.update('docs', id, next);
  AK.toast(after.cBalance <= 0 ? 'Paid in full' : 'Payment saved, ' + money(after.balance) + ' left', { type: 'ok', undo: () => db.undo() });
}
function openSign(d) {
  const L = AK.sheet({ title: 'Customer signs', body: tpl('sheet-sign'),
    actions: [{ label: 'Clear', ghost: true, onClick: () => { pad.clear(); return false; } }, { label: 'Save signature', primary: true, onClick: () => {
      if (pad.isEmpty()) { AK.toast('Ask the customer to sign in the box first', 'warn'); return false; }
      const cur0 = db.find('docs', d.id);
      const name = $('#sign-name', L.body).value.trim() || cur0.customer.name || '';
      db.update('docs', d.id, { signature: pad.toDataURL({ color: '#000', scale: 1.5 }), signedBy: name, signedAt: today(),
        status: cur0.type === 'estimate' && cur0.status !== 'void' ? 'approved' : cur0.status, log: addLog(cur0, 'Signed by ' + (name || 'the customer') + (cur0.type === 'estimate' ? ', approved' : '')) });
      AK.toast(d.type === 'estimate' ? 'Signed and approved' : 'Signature saved', 'ok');
    } }] });
  $('.sign-lead', L.body).textContent = 'By signing, the customer approves ' + kindName(d).toLowerCase() + ' ' + d.number + ' for ' + money(totals(d).total) + '.';
  $('#sign-name', L.body).value = d.customer.name || '';
  const pad = AK.signature($('.sign-pad', L.body));
}
function openCustomer(key) {
  const c = customerList().find(x => x.key === key);
  if (!c) return;
  const body = tpl('sheet-customer');
  bind(body, { name: c.name, address: c.address, billedText: money(c.billed / 100), owedText: money(c.owed / 100), count: c.docs.length });
  slot(body, 'avatar').outerHTML = String(AK.avatar(c.name, 'lg'));
  AK.render(slot(body, 'contact'), contactButtons(c));
  AK.render(slot(body, 'docs'), c.docs.slice().reverse().map(docRow));
  AK.sheet({ title: c.name, body, actions: [
    { label: 'New invoice', onClick: () => { newDoc('invoice', { customer: pickCust(c), job: { what: c.what, notes: '' } }); } },
    { label: 'New estimate', primary: true, onClick: () => { newDoc('estimate', { customer: pickCust(c), job: { what: c.what, notes: '' } }); } }] });
}
const pickCust = c => ({ name: c.name, phone: c.phone, email: c.email, address: c.address });

/* ===== 13. CREATING AND CHANGING DOCUMENTS ===== */
function takeNumber(type) {
  const s = set(), inv = type === 'invoice', p = inv ? s.invPrefix : s.estPrefix, used = new Set(docs().map(d => (d.number || '').toLowerCase()));
  let n = Math.max(1, Math.round(num(inv ? s.invNext : s.estNext)) || 1);
  while (used.has((p + n).toLowerCase())) n++;
  silently(() => db.set({ settings: Object.assign({}, db.get('settings'), { [inv ? 'invNext' : 'estNext']: n + 1 }) }));
  return p + n;
}
function releaseNumber(type, number) {
  const s = set(), inv = type === 'invoice', k = inv ? 'invNext' : 'estNext';
  if (number && number === (inv ? s.invPrefix : s.estPrefix) + (s[k] - 1)) silently(() => db.set({ settings: Object.assign({}, db.get('settings'), { [k]: s[k] - 1 }) }));
}
function blankDoc(type, preset) {
  const s = set(), inv = type === 'invoice';
  return Object.assign({
    id: AK.id(), type, number: '', status: 'draft', date: today(), due: AK.today(inv ? +s.dueDays || 0 : +s.validDays || 0),
    customer: { name: '', phone: '', email: '', address: '' }, job: { what: '', notes: '' }, lines: [],
    discount: { mode: 'amount', value: null }, tax: { name: s.taxName || 'Tax', rate: num(s.taxRate), on: { labour: !!s.taxLabour, parts: !!s.taxParts, other: !!s.taxOther } },
    deposit: null, payments: [], terms: inv ? s.invTerms : s.estTerms, signature: null, created: Date.now(), updated: Date.now(), log: []
  }, preset || {});
}
function newDoc(type, preset) {
  const d = blankDoc(type, preset);
  d.number = takeNumber(type);
  d.log = addLog(d, 'Created');
  silently(() => db.add('docs', d, { first: true }));
  app.go('/docs/' + d.id + '/edit');
}
function convert(id) {
  const e = db.find('docs', id);
  if (!e) return;
  AK.confirm('Turn ' + e.number + ' into an invoice?', { text: 'A new invoice gets every line, the customer and any deposit. The estimate stays as it is, marked approved.', ok: 'Make the invoice', icon: 'receipt' }).then(ok => {
    if (!ok) return;
    const s = set();
    const inv = blankDoc('invoice', { customer: Object.assign({}, e.customer), job: Object.assign({}, e.job), lines: e.lines.map(l => Object.assign({}, l, { id: AK.id() })),
      discount: Object.assign({}, e.discount), tax: JSON.parse(JSON.stringify(e.tax)), deposit: e.deposit, from: e.id, terms: s.invTerms, sample: e.sample });
    inv.number = takeNumber('invoice');
    inv.log = addLog(inv, 'Made from estimate ' + e.number);
    db.update(st => {
      st.docs = [inv].concat(st.docs.map(x => x.id === e.id ? Object.assign({}, x, { status: 'approved', convertedTo: inv.id, log: addLog(x, 'Turned into invoice ' + inv.number) }) : x));
      return st;
    });
    AK.toast('Invoice ' + inv.number + ' is ready', { type: 'ok', undo: () => { db.undo(); releaseNumber('invoice', inv.number); app.go('/docs/' + e.id, true); } });
    app.go('/docs/' + inv.id, true);
  });
}
function duplicate(id) {
  const o = db.find('docs', id);
  if (!o) return;
  const d = blankDoc(o.type, { customer: Object.assign({}, o.customer), job: Object.assign({}, o.job), lines: o.lines.map(l => Object.assign({}, l, { id: AK.id() })),
    discount: Object.assign({}, o.discount), tax: JSON.parse(JSON.stringify(o.tax)), terms: o.terms });
  d.number = takeNumber(o.type);
  d.log = addLog(d, 'Copied from ' + o.number);
  db.add('docs', d, { first: true });
  AK.toast('Copied to ' + d.number, 'ok');
  app.go('/docs/' + d.id + '/edit');
}

/* ===== 14. ACTIONS (any element with data-act="name") ===== */
const docOf = el => db.find('docs', el.dataset.id || (app.route().param || '').split('/')[0]);
AK.act({
  'new-estimate': () => newDoc('estimate'),
  'new-invoice': () => newDoc('invoice'),
  'go-docs': () => app.go('/docs'),
  open: el => app.go('/docs/' + el.dataset.id),
  'open-from': el => { const d = docOf(el); if (d) app.go('/docs/' + d.from); },
  edit: el => { const d = docOf(el); if (d) app.go('/docs/' + d.id + '/edit'); },
  'ed-done': () => { const id = (app.route().param || '').split('/')[0]; app.go('/docs/' + id, true); },
  'ed-delete': () => { const id = (app.route().param || '').split('/')[0]; const d = db.find('docs', id); if (d) removeDoc(d); },
  'show-owed': () => { kindF = 'invoice'; chipF = 'sent'; q = ''; app.refresh(); },
  send: el => { const d = docOf(el); if (d) openSend(d); },
  print: el => { const d = docOf(el); if (d) printDoc(d); },
  pay: el => { const d = docOf(el); if (d) openPayment(d); },
  'mark-paid': el => { const d = docOf(el); if (!d) return; const t = totals(d); if (t.cBalance > 0) addPayment(d.id, t.balance, today(), 'Payment'); },
  'del-payment': el => {
    const d = db.find('docs', el.dataset.id); if (!d) return;
    const p = (d.payments || []).find(x => x.id === el.dataset.pay);
    db.update('docs', d.id, { payments: d.payments.filter(x => x.id !== el.dataset.pay), log: addLog(d, 'Removed a payment of ' + money(p ? p.amount : 0)) });
    AK.toast('Payment removed', { undo: () => db.undo() });
  },
  approve: el => { const d = docOf(el); if (d) { db.update('docs', d.id, { status: 'approved', log: addLog(d, 'Marked approved') }); AK.toast('Approved. Turn it into an invoice when the work is done.', 'ok'); } },
  sign: el => { const d = docOf(el); if (d) openSign(d); },
  convert: el => { const d = docOf(el); if (d) convert(d.id); },
  duplicate: el => { const d = docOf(el); if (d) duplicate(d.id); },
  void: el => {
    const d = docOf(el); if (!d) return;
    AK.confirm('Void ' + d.number + '?', { text: 'It stays in your list marked Void, and stops counting in your totals. You can restore it.', ok: 'Void it', danger: true, icon: 'x-circle' }).then(ok => {
      if (ok) { db.update('docs', d.id, { status: 'void', before: d.status, log: addLog(d, 'Voided') }); AK.toast(d.number + ' is void', { undo: () => db.undo() }); }
    });
  },
  restore: el => { const d = docOf(el); if (d) db.update('docs', d.id, { status: d.before && d.before !== 'void' ? d.before : 'draft', log: addLog(d, 'Restored') }); },
  delete: el => { const d = docOf(el); if (d) removeDoc(d); },
  'open-customer': el => { AK.closeAll(); setTimeout(() => openCustomer(el.dataset.key), 30); },
  'load-sample': () => loadSample(),
  'clear-sample': () => {
    const n = docs().filter(d => d.sample).length;
    if (!n) { AK.toast('There is no sample data to clear', 'info'); return; }
    db.set({ docs: docs().filter(d => !d.sample) });
    AK.toast('Sample data cleared', { undo: () => db.undo() });
  },
  backup: () => { AK.download('estimates-backup-' + today() + '.json', backupText()); AK.toast('Backup file saved', 'ok'); },
  'copy-backup': () => AK.copy(backupText(), { done: 'Backup copied. Paste it somewhere safe.' }),
  'restore-file': () => { const i = $('#restore-input'); if (i) i.click(); },
  'restore-text': () => {
    AK.modal({ title: 'Restore from copied text', body: AK.html`<div class="form"><div class="field"><label for="rt-text">Paste the backup here</label><textarea id="rt-text" rows="6"></textarea></div></div>`,
      actions: [{ label: 'Cancel' }, { label: 'Restore', primary: true, onClick: L => restoreText($('#rt-text', L.body).value) ? undefined : false }] });
  },
  'export-csv': () => {
    const rows = docs().map(d => { const t = totals(d); return { Type: kindName(d), Number: d.number, Status: PILL[state(d, t)][0], Date: d.date, Due: d.due, Customer: d.customer.name, Phone: d.customer.phone, Email: d.customer.email, For: d.job.what,
      Labour: t.labour.toFixed(2), Parts: t.parts.toFixed(2), Other: t.other.toFixed(2), Discount: t.disc.toFixed(2), Tax: t.tax.toFixed(2), Total: t.total.toFixed(2), Paid: t.paid.toFixed(2), Balance: t.balance.toFixed(2) }; });
    if (!rows.length) { AK.toast('There are no documents to export yet', 'info'); return; }
    AK.csv.export(rows, 'estimates-and-invoices-' + today() + '.csv');
    AK.toast('Spreadsheet file saved', 'ok');
  },
  erase: () => {
    AK.confirm('Erase every document?', { text: 'All estimates and invoices on this phone are removed. Settings stay. Back up first if you might need them.', ok: 'Erase', danger: true }).then(ok => {
      if (ok) { db.set({ docs: [] }); AK.toast('Everything erased', { undo: () => db.undo() }); }
    });
  }
});
function removeDoc(d) {
  AK.confirm('Delete ' + d.number + '?', { text: 'This removes it from this phone. You can undo straight after.', ok: 'Delete', danger: true }).then(ok => {
    if (!ok) return;
    app.go('/docs', true);
    setTimeout(() => {
      if (!db.find('docs', d.id)) { AK.toast(d.number + ' deleted'); return; }   // an empty new draft is already gone
      db.remove('docs', d.id);
      AK.toast(d.number + ' deleted', { undo: () => db.undo() });
    }, 30);
  });
}

/* ===== 15. SAMPLE DATA (clearly marked; "Clear sample data" removes only these) ===== */
function loadSample() {
  if (docs().some(d => d.sample)) { AK.toast('Sample data is already loaded', 'info'); return; }
  const s = set(), tax = { name: s.taxName || 'Tax', rate: 8, on: { labour: false, parts: true, other: true } };
  const L = (type, desc, qty, price) => ({ id: AK.id(), type, desc, qty, price });
  const thisMonth = n => { const d = new Date(); return AK.today(-Math.min(n, d.getDate() - 1)); };   // n days ago, but not before the 1st
  const mk = (o) => Object.assign(blankDoc(o.type), { sample: true, tax }, o, { log: [{ at: new Date().toISOString(), what: 'Sample document' }] });
  const list = [
    mk({ type: 'estimate', number: 'SAMPLE-E1', status: 'sent', date: AK.today(-2), due: AK.today(28), customer: { name: 'Sample customer A', phone: '', email: '', address: '' }, job: { what: 'Sample vehicle or item', notes: 'Sample note: what was found and what is recommended.' },
      lines: [L('labour', 'Sample labour', 2, 100), L('parts', 'Sample part', 2, 25), L('other', 'Sample shop supplies', 1, 10)], discount: { mode: 'percent', value: 10 }, deposit: 50 }),
    mk({ type: 'invoice', number: 'SAMPLE-I1', status: 'sent', date: AK.today(-36), due: AK.today(-22), customer: { name: 'Sample customer B', phone: '', email: '', address: '' }, job: { what: 'Sample job site', notes: '' },
      lines: [L('labour', 'Sample labour', 3.5, 100), L('parts', 'Sample materials', 1, 120)], payments: [{ id: AK.id(), amount: 100, date: AK.today(-30), method: 'Card' }] }),
    mk({ type: 'invoice', number: 'SAMPLE-I2', status: 'sent', date: thisMonth(4), due: AK.today(10), customer: { name: 'Sample customer A', phone: '', email: '', address: '' }, job: { what: 'Sample vehicle or item', notes: '' },
      lines: [L('labour', 'Sample labour', 1, 100), L('parts', 'Sample part', 4, 12.5)], payments: [{ id: AK.id(), amount: 154, date: thisMonth(1), method: 'Cash' }] }),
    mk({ type: 'estimate', number: 'SAMPLE-E2', status: 'draft', date: AK.today(), due: AK.today(30), customer: { name: 'Sample customer C', phone: '', email: '', address: '' }, job: { what: 'Sample item', notes: '' },
      lines: [L('labour', 'Sample labour', 1.5, 100), L('parts', 'Sample part', 1, 45)] })
  ];
  db.set({ docs: list.concat(docs()) });
  q = ''; chipF = 'all'; kindF = 'all';
  AK.toast('Sample data loaded. Clear it any time.', { type: 'ok', undo: () => db.undo() });
  if (app.route().tab !== 'docs' || app.route().param) app.go('/docs');
}

/* ===== 16. BOOT ===== */
if (docs().some(d => !d || !d.customer || !d.job || !Array.isArray(d.lines))) silently(() => db.set({ docs: docs().filter(x => x && typeof x === 'object').map(normDoc) }));
AK.currency = set().currency;
startApp();
brand(); badge();
window.EB = { db, totals, state, set };
})();
