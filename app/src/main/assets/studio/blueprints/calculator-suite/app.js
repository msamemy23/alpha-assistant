/* Calculators | app.js (built on appkit: window.AK, see APPKIT.md)
   Every calculator's markup is a <template id="calc-NAME"> in index.html; its maths is a small pure function below.
   Sections:  1 helpers  2 icons  3 store + settings  4 number formatting  5 MATHS (pure functions, no screen code)
   6 calculator list (CALCS: name, group, defaults, run, extras)  7 engine (read inputs, paint answers, remember, copy, save)
   8 Home  9 History  10 Settings  11 printing  12 actions + boot */
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
function tpl(id) {
  const f = document.getElementById(id).content.cloneNode(true);
  $$('i[data-icon]', f).forEach(i => i.replaceWith(node(icon(i.dataset.icon, i.className))));
  return f;
}
const fire = el => {el.dispatchEvent(new Event('input', {bubbles: true})); el.dispatchEvent(new Event('change', {bubbles: true}));};

/* ===== 2. ICONS (extra ones on top of AK.icons) ===== */
const XI = {
  bank: 'M3 21h18M5 21V11M19 21V11M9.5 21V11M14.5 21V11M2 11 12 3l10 8z',
  percent: 'M19 5 5 19M6.5 4a2.5 2.5 0 1 0 0 5 2.5 2.5 0 1 0 0-5zM17.5 15a2.5 2.5 0 1 0 0 5 2.5 2.5 0 1 0 0-5z',
  ruler: 'M21.3 15.3a2.4 2.4 0 0 1 0 3.4l-2.6 2.6a2.4 2.4 0 0 1-3.4 0L2.7 8.7a2.4 2.4 0 0 1 0-3.4l2.6-2.6a2.4 2.4 0 0 1 3.4 0zM14.5 12.5l2-2M11.5 9.5l2-2M8.5 6.5l2-2M17.5 15.5l2-2',
  tire: 'M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20zM12 7.5a4.5 4.5 0 1 0 0 9a4.5 4.5 0 1 0 0-9zM12 11a1 1 0 1 0 0 2a1 1 0 1 0 0-2zM4.9 4.9l3.3 3.3M19.1 4.9l-3.3 3.3M4.9 19.1l3.3-3.3M19.1 19.1l-3.3-3.3',
  fuel: 'M3 22h12M4 9h10M14 22V4a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v18M14 13h2a2 2 0 0 1 2 2v2a2 2 0 0 0 4 0V9.8a2 2 0 0 0-.6-1.4L18 5',
  swap: 'M16 3l4 4-4 4M20 7H4M8 21l-4-4 4-4M4 17h16'
};
const icon = (n, cls) => XI[n] ? AK.raw('<svg class="i' + (cls ? ' ' + AK.esc(cls) : '') + '" viewBox="0 0 24 24" aria-hidden="true"><path d="' + XI[n] + '"/></svg>') : AK.icon(n, cls);

/* ===== 3. STORE + SETTINGS (browser storage, namespaced by the app) =====
   settings: {symbol, cents, decimals, units}  pins: [calc ids]  inputs: {calcId: last inputs}  history: [{id, calc, at, main, sub, inputs}] */
const DEF_SET = {symbol: '$', cents: true, decimals: 2, units: 'us'};
const DEFAULT_PINS = ['pricing', 'percent']; /* which calculators start pinned; change for the trade (for example 'tire') */
const db = AK.store('calculator-suite', {v: 1, settings: Object.assign({}, DEF_SET), pins: DEFAULT_PINS.slice(), inputs: {}, history: []});
const SET = () => Object.assign({}, DEF_SET, db.get('settings') || {});

/* ===== 4. NUMBER FORMATTING (follows Settings: symbol, cents, decimal places) ===== */
const roundTo = (n, d) => {const f = Math.pow(10, d); return Math.round(n * f + (n >= 0 ? 1 : -1) * 1e-6) / f;}; /* half away from zero; 1.005 -> 1.01 */
const r2 = n => roundTo(n, 2);
const toC = n => Math.round((+n || 0) * 100 + ((+n || 0) >= 0 ? 1 : -1) * 1e-6);
const group = (a, maxD, minD) => a.toLocaleString(LOC(), {minimumFractionDigits: minD || 0, maximumFractionDigits: maxD});
const MINUS = '\u2212';
function money(n, dec) {
  if (n == null || !isFinite(n)) return '\u2014';
  const s = SET(), d = dec != null ? dec : (s.cents ? 2 : 0), r = roundTo(n, d);
  return (r < 0 ? MINUS : '') + s.symbol + group(Math.abs(r), d, d);
}
function num(n, dec, keep) {
  if (n == null || !isFinite(n)) return '\u2014';
  const d = dec != null ? dec : SET().decimals, a = Math.abs(n);
  if (a >= 1e15) return n.toExponential(4);
  if (a > 0 && a < 0.5 * Math.pow(10, -d)) return (n < 0 ? MINUS : '') + group(a, 20).replace(/(\.\d*?[1-9]\d{3})\d+/, '$1'); /* tiny: 4 significant digits */
  const r = roundTo(n, d);
  return (r < 0 ? MINUS : '') + group(Math.abs(r), d, keep ? d : 0);
}
const sig = (n, k) => {if (!isFinite(n)) return '\u2014'; if (n === 0) return '0'; const a = Math.abs(n); return a >= 1 ? num(n) : (n < 0 ? MINUS : '') + Number(a.toPrecision(k || 4)).toLocaleString(LOC(), {maximumSignificantDigits: k || 4});};
const pct = (n, dec) => n == null || !isFinite(n) ? '\u2014' : num(n, dec) + '%';
const hm = mins => {mins = Math.round(mins); const h = Math.floor(mins / 60), m = mins % 60; return h + 'h' + (m ? ' ' + String(m).padStart(2, '0') + 'm' : '');};
const plural = (n, w) => n + ' ' + w + (n === 1 ? '' : 's');
/* reading what was typed: "1,250", "$1250.00", "6.5%" -> number; empty or no digits -> null */
const N = s => {if (s == null) return null; s = String(s).trim(); if (!/\d/.test(s)) return null; const n = AK.parseMoney(s); return isFinite(n) ? n : null;};

/* ===== 5. MATHS: pure functions, easy to check (no screen code here) ===== */
const MATH = {
  /* Loan: fixed monthly payment M = P*r / (1 - (1+r)^-n), r = APR/12. Schedule in whole cents; the last payment clears what is left. */
  loan(P, apr, n, extra) {
    const r = apr / 100 / 12, M = r ? P * r / (1 - Math.pow(1 + r, -n)) : P / n, payC = toC(M);
    const run = exC => {
      let bal = toC(P), k = 0, intC = 0; const rows = [];
      while (bal > 0 && k < 1200) {
        k++;
        const i = Math.round(bal * r);
        let pr = payC + exC - i;
        if (pr >= bal || (!exC && k >= n)) pr = bal;
        if (pr <= 0) break;
        bal -= pr; intC += i;
        rows.push({k, pay: (pr + i) / 100, int: i / 100, prin: pr / 100, bal: bal / 100});
      }
      return {rows, interest: intC / 100, months: rows.length};
    };
    const main = run(toC(extra || 0)), base = extra > 0 ? run(0) : main;
    return {payment: payC / 100, interest: main.interest, total: r2(P + main.interest), months: main.months, rows: main.rows,
      baseInterest: base.interest, baseMonths: base.months, formulaPayment: M};
  },
  /* Margin mode: any two of cost, price, markup %, margin %, profit. markup = profit/cost, margin = profit/price */
  margin(k) {
    let c = k.m_cost, p = k.m_price;
    const mk = k.m_markup, mg = k.m_margin, pf = k.m_profit;
    if (c == null && p == null) {
      if (pf != null && mk != null) {if (!mk) return {error: 'With 0% markup there is no profit. Enter the cost or the price.'}; c = pf / (mk / 100); p = c + pf;}
      else if (pf != null && mg != null) {if (!mg) return {error: 'With 0% margin there is no profit. Enter the cost or the price.'}; p = pf / (mg / 100); c = p - pf;}
      else return {error: 'Markup and margin say the same thing two ways. Add the cost or the price.'};
    } else if (c == null) {
      if (mk != null) {if (mk <= -100) return {error: 'Markup has to be above -100%.'}; c = p / (1 + mk / 100);}
      else if (mg != null) c = p * (1 - mg / 100);
      else if (pf != null) c = p - pf;
    } else if (p == null) {
      if (mk != null) p = c * (1 + mk / 100);
      else if (mg != null) {if (mg >= 100) return {error: 'Margin has to be under 100%.'}; p = c / (1 - mg / 100);}
      else if (pf != null) p = c + pf;
    }
    return {m_cost: c, m_price: p, m_profit: p - c, m_markup: c ? (p - c) / Math.abs(c) * 100 : null, m_margin: p ? (p - c) / Math.abs(p) * 100 : null};
  },
  /* Discount mode: any two of original, % off, sale price, amount saved */
  discount(k) {
    let o = k.d_orig, s = k.d_sale;
    const d = k.d_pct, v = k.d_save;
    if (o == null) {
      if (s != null && d != null) {if (d >= 100) return {error: 'At 100% off or more there is no price to work back from.'}; o = s / (1 - d / 100);}
      else if (s != null && v != null) o = s + v;
      else if (d != null && v != null) {if (!d) return {error: 'At 0% off nothing is saved. Enter a price.'}; o = v / (d / 100);}
    }
    if (s == null) s = d != null ? o * (1 - d / 100) : o - v;
    return {d_orig: o, d_sale: s, d_save: o - s, d_pct: o ? (o - s) / o * 100 : null};
  },
  /* Sales tax mode: any two of price before tax, rate %, tax, total. Tax is rounded to the cent like a receipt. */
  tax(k) {
    let b = k.t_base, t = k.t_tax, x = k.t_total;
    const r = k.t_rate;
    if (b == null) {
      if (x != null && r != null) {if (r <= -100) return {error: 'Check the tax rate.'}; b = r2(x / (1 + r / 100));}
      else if (x != null && t != null) b = x - t;
      else if (r != null && t != null) {if (!r) return {error: 'A 0% rate has no tax. Enter a price.'}; b = r2(t / (r / 100));}
    }
    if (t == null) t = x != null ? x - b : r2(b * r / 100);
    if (x == null) x = b + t;
    return {t_base: r2(b), t_tax: r2(t), t_total: r2(x), t_rate: b ? t / b * 100 : null};
  },
  /* Tip: on the bill before tax; split exactly to the cent (some people pay 1 cent more), or round each share up */
  tip(bill, p, people, tax, roundUp) {
    people = Math.max(1, Math.floor(people || 1));
    const base = Math.max(0, bill - (tax || 0));
    let tip = r2(base * p / 100), total = r2(bill + tip);
    if (roundUp) {const each = Math.ceil(total / people - 1e-9); total = each * people; tip = r2(total - bill); return {each, tip, total, rate: base ? tip / base * 100 : 0, rem: 0, people};}
    const tc = toC(total), lo = Math.floor(tc / people), rem = tc - lo * people;
    return {each: (lo + (rem ? 1 : 0)) / 100, low: lo / 100, rem, tip, total, rate: p, people};
  },
  percentOf: (x, y) => x / 100 * y,
  whatPercent: (x, y) => y ? x / y * 100 : null,
  change: (a, b) => a ? (b - a) / Math.abs(a) * 100 : null,
  /* Units: factor to a base unit; temperature and fuel economy are special */
  convert(cat, v, from, to) {
    if (cat === 'temperature') {
      const c = from === 'c' ? v : from === 'f' ? (v - 32) * 5 / 9 : v - 273.15;
      return to === 'c' ? c : to === 'f' ? c * 9 / 5 + 32 : c + 273.15;
    }
    if (cat === 'fuel') {
      const KPM = 1.609344, LG = 3.785411784, LUK = 4.54609;
      const kml = from === 'mpg' ? v * KPM / LG : from === 'mpguk' ? v * KPM / LUK : from === 'kml' ? v : (v ? 100 / v : Infinity);
      if (to === 'l100') return kml ? 100 / kml : Infinity;
      return to === 'mpg' ? kml * LG / KPM : to === 'mpguk' ? kml * LUK / KPM : kml;
    }
    const f = UNITS[cat].units, a = f.find(u => u[0] === from), b = f.find(u => u[0] === to);
    return v * a[2] / b[2];
  },
  /* Time card: minutes worked in a day; an end time before the start means the shift ran past midnight */
  dayMinutes(start, end, brk) {
    const t = s => {const m = /^(\d{1,2}):(\d{2})/.exec(s || ''); return m ? +m[1] * 60 + +m[2] : null;};
    const a = t(start), z = t(end);
    if (a == null || z == null) return null;
    let d = z - a; if (d < 0) d += 1440;
    return d - (brk || 0);
  },
  /* weekly (and optional daily) overtime; pay rounded to the cent */
  timecard(dayMins, rate, mult, weekly, daily) {
    let total = 0, dailyOT = 0, cand = 0;
    dayMins.forEach(m => {
      if (!(m > 0)) return;
      total += m;
      if (daily > 0) {const lim = daily * 60; dailyOT += Math.max(0, m - lim); cand += Math.min(m, lim);} else cand += m;
    });
    const wl = weekly > 0 ? weekly * 60 : Infinity, weeklyOT = Math.max(0, cand - wl), reg = cand - weeklyOT, ot = dailyOT + weeklyOT;
    const regPay = r2(reg / 60 * (rate || 0)), otPay = r2(ot / 60 * (rate || 0) * (mult || 1));
    return {total, reg, ot, regPay, otPay, pay: r2(regPay + otPay)};
  },
  /* Tire size: metric 225/45R17 (also P, LT, ZR, 225/45-17, 225 45 17) or flotation 33x12.50R15 */
  tire(str) {
    const s = String(str || '').toUpperCase().replace(/\s+/g, ' ').trim();
    let m = /^(\d{2}(?:\.\d+)?)\s*[X*]\s*(\d{1,2}(?:\.\d+)?)\s*(?:[A-Z]{0,2}R|-|\/|\s)\s*(\d{2}(?:\.\d)?)/.exec(s);
    if (m) {
      const D = +m[1], W = +m[2], rim = +m[3];
      if (D < 20 || D > 60 || W < 5 || W > 25 || rim < 10 || rim > 30 || D <= rim) return null;
      const side = (D - rim) / 2;
      return finishTire({label: m[1] + 'x' + m[2] + 'R' + m[3], w_mm: W * 25.4, s_mm: side * 25.4, rim, d_in: D, aspect: side / W * 100});
    }
    m = /^(?:P|LT|ST|T)?\s*(\d{3})\s*[/ ]\s*(\d{2})\s*(?:[A-Z]{1,2})?\s*[-/ ]?\s*(\d{2}(?:\.\d)?)(?=$|[\s A-Z])/.exec(s);
    if (!m) return null;
    const W = +m[1], A = +m[2], rim = +m[3];
    if (W < 100 || W > 455 || A < 20 || A > 95 || rim < 10 || rim > 30) return null;
    const side = W * A / 100;
    return finishTire({label: W + '/' + A + 'R' + m[3], w_mm: W, s_mm: side, rim, d_in: (rim * 25.4 + 2 * side) / 25.4, aspect: A});
  },
  /* Fuel: trip cost and fill-up economy. 235.2146 = 100 * 3.785411784 / 1.609344 (mpg <-> L/100 km) */
  trip(us, dist, eco, price, roundTrip, people) {
    const d = dist * (roundTrip ? 2 : 1);
    const fuel = us ? d / eco : d * eco / 100, cost = fuel * (price || 0);
    return {dist: d, fuel, cost: r2(cost), each: r2(cost / Math.max(1, people || 1)), perDist: us ? (price || 0) / eco : eco / 100 * (price || 0)};
  },
  fill(us, dist, fuel, price) {
    const eco = us ? dist / fuel : fuel / dist * 100;
    const other = us ? 235.214583 / eco : 235.214583 / eco;
    return {eco, other, cost: price ? r2(fuel * price) : null, perDist: price ? fuel * price / dist : null};
  }
};
function finishTire(t) {
  t.d_mm = t.d_in * 25.4; t.c_in = Math.PI * t.d_in; t.rpm = 63360 / t.c_in; t.rpk = 1e6 / (Math.PI * t.d_mm);
  t.w_in = t.w_mm / 25.4; t.s_in = t.s_mm / 25.4;
  return t;
}
const UNITS = {
  length: {name: 'Length', units: [['mm', 'mm', 0.001, 'millimetres'], ['cm', 'cm', 0.01, 'centimetres'], ['m', 'm', 1, 'metres'], ['km', 'km', 1000, 'kilometres'],
    ['in', 'in', 0.0254, 'inches'], ['ft', 'ft', 0.3048, 'feet'], ['yd', 'yd', 0.9144, 'yards'], ['mi', 'mi', 1609.344, 'miles'], ['nmi', 'nmi', 1852, 'nautical miles']]},
  weight: {name: 'Weight', units: [['mg', 'mg', 1e-6, 'milligrams'], ['g', 'g', 0.001, 'grams'], ['kg', 'kg', 1, 'kilograms'], ['t', 't', 1000, 'tonnes'],
    ['oz', 'oz', 0.028349523125, 'ounces'], ['lb', 'lb', 0.45359237, 'pounds'], ['st', 'st', 6.35029318, 'stone'], ['ton', 'US ton', 907.18474, 'US tons']]},
  volume: {name: 'Volume', units: [['ml', 'mL', 0.001, 'millilitres'], ['l', 'L', 1, 'litres'], ['m3', 'm\u00b3', 1000, 'cubic metres'], ['tsp', 'tsp', 0.00492892159375, 'teaspoons (US)'],
    ['tbsp', 'tbsp', 0.01478676478125, 'tablespoons (US)'], ['floz', 'fl oz', 0.0295735295625, 'fluid ounces (US)'], ['cup', 'cup', 0.2365882365, 'cups (US)'],
    ['pt', 'pt', 0.473176473, 'pints (US)'], ['qt', 'qt', 0.946352946, 'quarts (US)'], ['gal', 'gal', 3.785411784, 'gallons (US)'], ['galuk', 'UK gal', 4.54609, 'gallons (UK)'],
    ['in3', 'in\u00b3', 0.016387064, 'cubic inches'], ['ft3', 'ft\u00b3', 28.316846592, 'cubic feet']]},
  temperature: {name: 'Temperature', units: [['c', '\u00b0C', 0, 'Celsius'], ['f', '\u00b0F', 0, 'Fahrenheit'], ['k', 'K', 0, 'Kelvin']]},
  speed: {name: 'Speed', units: [['kmh', 'km/h', 1 / 3.6, 'kilometres per hour'], ['mph', 'mph', 0.44704, 'miles per hour'], ['ms', 'm/s', 1, 'metres per second'],
    ['kn', 'kn', 1852 / 3600, 'knots'], ['fts', 'ft/s', 0.3048, 'feet per second']]},
  area: {name: 'Area', units: [['cm2', 'cm\u00b2', 1e-4, 'square centimetres'], ['m2', 'm\u00b2', 1, 'square metres'], ['ha', 'ha', 1e4, 'hectares'], ['km2', 'km\u00b2', 1e6, 'square kilometres'],
    ['in2', 'in\u00b2', 0.00064516, 'square inches'], ['ft2', 'ft\u00b2', 0.09290304, 'square feet'], ['yd2', 'yd\u00b2', 0.83612736, 'square yards'], ['ac', 'ac', 4046.8564224, 'acres'],
    ['mi2', 'mi\u00b2', 2589988.110336, 'square miles']]},
  fuel: {name: 'Fuel economy', units: [['mpg', 'mpg', 0, 'miles per gallon (US)'], ['mpguk', 'UK mpg', 0, 'miles per gallon (UK)'], ['kml', 'km/L', 0, 'kilometres per litre'], ['l100', 'L/100 km', 0, 'litres per 100 km']]}
};
const UNIT_START = {length: ['ft', 'm'], weight: ['lb', 'kg'], volume: ['gal', 'l'], temperature: ['f', 'c'], speed: ['mph', 'kmh'], area: ['ft2', 'm2'], fuel: ['mpg', 'l100']};
const uLabel = (cat, id) => (UNITS[cat].units.find(u => u[0] === id) || [id, id])[1];
const uName = (cat, id) => (UNITS[cat].units.find(u => u[0] === id) || [id, id, 0, id])[3];

/* ===== 6. THE CALCULATORS =====
   Each: id, name, group, icon, blurb, keys (search words), defaults (first-time inputs, a worked example),
   run(v) -> {out: {data-out key: number or text}, main, sub, copy, msg}; optional setup(root) and after(root, res, v). */
const GROUPS = ['Money', 'Work', 'Convert', 'Auto'];
const LOAN_EX = {amount: '25000', rate: '6.5', term: '5', unit: 'years', extra: '', sched: 'year'};
const CALCS = [
  {id: 'loan', name: 'Loan payment', group: 'Money', icon: 'bank', blurb: 'Monthly payment, total interest and a payoff schedule.', keys: 'loan mortgage car finance interest apr payment amortization schedule',
    defaults: LOAN_EX, run: runLoan, after: afterLoan},
  {id: 'pricing', name: 'Markup, margin & tax', group: 'Money', icon: 'tag', blurb: 'Price from cost, margin from price, discounts and sales tax.', keys: 'markup margin profit price cost discount sale tax vat gst percent off',
    defaults: {mode: 'm', m_cost: '60', m_markup: '50', known: {m: ['m_markup', 'm_cost'], d: [], t: []}}, run: runPricing, setup: setupPricing, after: afterPricing},
  {id: 'tip', name: 'Tip & split', group: 'Money', icon: 'receipt', blurb: 'Tip on the bill, split between people, round up if you like.', keys: 'tip split bill gratuity restaurant share people',
    defaults: {bill: '86.40', pct: '18', people: '3', tax: '', round: false}, run: runTip, setup: setupTip},
  {id: 'percent', name: 'Percentages', group: 'Money', icon: 'percent', blurb: 'X% of Y, what percent, percent change, plus or minus.', keys: 'percent percentage change increase decrease of what',
    defaults: {of_x: '15', of_y: '240', is_x: '45', is_y: '180', ch_x: '80', ch_y: '100', pm_y: '250', pm_x: '10'}, run: runPercent},
  {id: 'timecard', name: 'Time card & pay', group: 'Work', icon: 'clock', blurb: 'Hours between times, breaks, weekly total, overtime and pay.', keys: 'hours time card timesheet pay wage overtime payroll shift break',
    defaults: Object.assign({rate: '25', mult: '1.5', weekly: '40', daily: ''}, ...[0, 1, 2, 3, 4].map(i => ({['s' + i]: '08:00', ['e' + i]: '16:30', ['b' + i]: '30'}))), run: runTime, setup: setupTime},
  {id: 'units', name: 'Unit converter', group: 'Convert', icon: 'ruler', blurb: 'Length, weight, volume, temperature, speed, area, fuel economy.', keys: 'unit convert conversion length weight volume temperature speed area fuel inch mm kg lb gallon litre celsius fahrenheit',
    defaults: {cat: 'length', val: '10', from: 'ft', to: 'm'}, run: runUnits, setup: setupUnits, after: afterUnits},
  {id: 'tire', name: 'Tire size', group: 'Auto', icon: 'tire', blurb: 'Diameter, sidewall, revs per mile, compare two sizes and the speedometer.', keys: 'tire tyre size wheel diameter sidewall speedometer rim revolutions plus size',
    defaults: {a: '225/45R17', b: '245/40R18', speed: '60'}, run: runTire, after: afterTire},
  {id: 'fuel', name: 'Fuel & trip cost', group: 'Auto', icon: 'fuel', blurb: 'What a trip costs in fuel, and MPG from a fill-up.', keys: 'fuel gas petrol diesel trip cost mpg mileage economy litres gallons km',
    defaults: {mode: 'trip', units: '', dist: '120', eco: '24', price: '3.49', round: true, people: '1', odo1: '', odo2: '', driven: '312', fuel: '11.6', fprice: '3.49'}, run: runFuel, setup: setupFuel, after: afterFuel}
];
const CALC = {}; CALCS.forEach(c => CALC[c.id] = c);

/* --- loan --- */
function runLoan(v) {
  const P = N(v.amount), apr = N(v.rate), t = N(v.term), n = Math.round((t || 0) * (v.unit === 'months' ? 1 : 12)), ex = N(v.extra) || 0;
  if (P == null || apr == null || t == null) return {msg: 'Enter the amount, the rate and the length of the loan.'};
  if (!(P > 0)) return {msg: 'The amount has to be more than zero.'};
  if (apr < 0 || apr > 100) return {msg: 'Check the rate: it should be between 0% and 100% a year.'};
  if (n < 1) return {msg: 'The loan has to last at least one month.'};
  if (n > 600) return {msg: 'Use 50 years (600 months) or less.'};
  if (ex < 0) return {msg: 'The extra payment cannot be negative.'};
  const L = MATH.loan(P, apr, n, ex), yrs = Math.floor(L.months / 12), mos = L.months % 12;
  const payoff = (yrs ? plural(yrs, 'year') : '') + (yrs && mos ? ' ' : '') + (mos ? plural(mos, 'month') : '');
  const termTxt = v.unit === 'months' ? plural(n, 'month') : plural(t, 'year');
  const sub = money(P) + ' at ' + num(apr, 3) + '% for ' + termTxt + (ex ? ', ' + money(ex) + ' extra' : '');
  const saved = r2(L.baseInterest - L.interest);
  return {L, out: {payment: L.payment, interest: L.interest, total: L.total, payoff, saved: ex ? saved : null, extra: ex > 0,
      note: ex ? 'Paying ' + money(ex) + ' extra each month saves ' + money(saved) + ' and ' + plural(L.baseMonths - L.months, 'month') + '.' : ''},
    main: money(L.payment) + ' a month', sub,
    copy: 'Loan: ' + sub + '\nMonthly payment: ' + money(L.payment) + '\nTotal interest: ' + money(L.interest) + '\nTotal paid: ' + money(L.total) + '\nPaid off in: ' + payoff};
}
function loanRows(L, byMonth) {
  if (byMonth) return L.rows.map(r => ({label: String(r.k), pay: r.pay, prin: r.prin, int: r.int, bal: r.bal}));
  const out = [];
  L.rows.forEach(r => {
    const y = Math.ceil(r.k / 12), row = out[y - 1] || (out[y - 1] = {label: 'Year ' + y, pay: 0, prin: 0, int: 0, bal: 0});
    row.pay += toC(r.pay); row.prin += toC(r.prin); row.int += toC(r.int); row.bal = r.bal;
  });
  return out.map(r => Object.assign(r, {pay: r.pay / 100, prin: r.prin / 100, int: r.int / 100}));
}
function afterLoan(root, res, v) {
  const card = slot(root, 'schedule');
  card.hidden = !res.L;
  if (!res.L) return;
  const years = loanRows(res.L, false), byMonth = v.sched === 'month', rows = byMonth ? loanRows(res.L, true) : years;
  AK.chart.bar(slot(root, 'loan-chart'), {labels: years.map(r => r.label.replace('Year ', '')), stacked: true, format: 'money', title: 'Principal and interest paid each year',
    series: [{name: 'Principal', values: years.map(r => r.prin), color: 'var(--ak-c1)'}, {name: 'Interest', values: years.map(r => r.int), color: 'var(--ak-c4)'}]});
  setText(root, 'col-1', byMonth ? 'Month' : 'Year');
  setHTML(root, 'loan-rows', rows.map(r => AK.html`<tr><td>${byMonth ? r.label : r.label.replace('Year ', '')}</td><td class="num">${money(r.prin)}</td><td class="num">${money(r.int)}</td><td class="num">${money(r.bal)}</td></tr>`).join(''));
}

/* --- markup, margin, discount, sales tax: type any two, the rest is solved --- */
const MODES = {m: {fields: ['m_cost', 'm_price', 'm_markup', 'm_margin', 'm_profit'], solve: MATH.margin, order: ['m_price', 'm_cost', 'm_margin', 'm_markup', 'm_profit']},
  d: {fields: ['d_orig', 'd_pct', 'd_sale', 'd_save'], solve: MATH.discount, order: ['d_sale', 'd_orig', 'd_pct', 'd_save']},
  t: {fields: ['t_base', 't_rate', 't_tax', 't_total'], solve: MATH.tax, order: ['t_total', 't_base', 't_tax', 't_rate']}};
const FIELD = {m_cost: 'Cost', m_price: 'Selling price', m_markup: 'Markup', m_margin: 'Margin', m_profit: 'Profit', d_orig: 'Original price', d_pct: 'Discount',
  d_sale: 'Sale price', d_save: 'Amount saved', t_base: 'Price before tax', t_rate: 'Tax rate', t_tax: 'Tax', t_total: 'Total with tax'};
const isPct = f => /markup|margin|pct|rate/.test(f);
const fieldTxt = (f, x) => isPct(f) ? pct(x) : money(x);
function knownOf(v) {const k = v.known || {}; return (k[v.mode || 'm'] || []).filter(f => N(v[f]) != null).slice(0, 2);}
function runPricing(v) {
  const mode = MODES[v.mode] ? v.mode : 'm', M = MODES[mode], known = knownOf(Object.assign({}, v, {mode}));
  if (known.length < 2) return {mode, known, msg: known.length ? 'Now fill in one more box.' : 'Fill in any two boxes.', out: {label: FIELD[M.order[0]]}};
  const k = {}; known.forEach(f => k[f] = N(v[f]));
  const s = M.solve(k);
  if (s.error) return {mode, known, msg: s.error, out: {label: FIELD[M.order[0]]}};
  const mainF = M.order.find(f => known.indexOf(f) < 0), rest = M.fields.filter(f => f !== mainF);
  const line = rest.map(f => FIELD[f] + ' ' + fieldTxt(f, s[f])).join(' \u00b7 ');
  return {mode, known, solved: s, out: {label: FIELD[mainF], main: isPct(mainF) ? pct(s[mainF]) : s[mainF], line},
    main: FIELD[mainF] + ' ' + fieldTxt(mainF, s[mainF]), sub: known.map(f => FIELD[f] + ' ' + fieldTxt(f, k[f])).join(', '),
    copy: M.fields.map(f => FIELD[f] + ': ' + fieldTxt(f, s[f])).join('\n')};
}
function setupPricing(root, st) {
  /* remember which boxes were typed (newest first); the others are worked out */
  root.addEventListener('input', e => {
    const f = e.target.name; if (!f || !FIELD[f]) return;
    const mode = f[0], k = st.v.known = Object.assign({m: [], d: [], t: []}, st.v.known);
    k[mode] = [f].concat((k[mode] || []).filter(x => x !== f));
    if (N(e.target.value) == null) k[mode] = k[mode].filter(x => x !== f);
  }, true);
}
function afterPricing(root, res, v) {
  $$('.mode-fields', root).forEach(g => g.hidden = g.dataset.mode !== res.mode);
  MODES[res.mode].fields.forEach(f => {
    const el = root.querySelector('[name=' + f + ']'), known = res.known.indexOf(f) > -1;
    el.closest('.field').classList.toggle('is-known', known);
    el.closest('.field').classList.toggle('is-solved', !known && !!res.solved);
    if (!known && document.activeElement !== el) {
      const x = res.solved ? res.solved[f] : null;
      el.value = x == null || !isFinite(x) ? '' : isPct(f) ? String(roundTo(x, Math.max(2, SET().decimals))) : (r2(x)).toFixed(2);
    }
  });
}

/* --- tip & split --- */
function runTip(v) {
  const bill = N(v.bill), p = N(v.pct), people = Math.max(1, Math.floor(N(v.people) || 1)), tax = N(v.tax) || 0;
  if (bill == null || p == null) return {msg: 'Enter the bill and a tip.'};
  if (bill < 0 || p < 0 || tax < 0) return {msg: 'Use amounts of zero or more.'};
  if (tax > bill) return {msg: 'The tax cannot be more than the bill.'};
  const t = MATH.tip(bill, p, people, tax, !!v.round), money0 = n => money(n, 2), who = n => n === 1 ? '1 person pays ' : n + ' people pay ';
  const note = people > 1 && t.rem ? who(t.rem) + money0(t.each) + ' and ' + who(people - t.rem) + money0(t.low) + ', so it adds up to the cent.' :
    v.round ? 'Rounded up, so the tip becomes ' + money0(t.tip) + '.' : '';
  return {t, out: {label: people > 1 ? 'Each of ' + people + ' pays' : 'You pay', each: t.each, tip: t.tip, total: t.total, rate: t.rate, note},
    main: money0(t.each) + (people > 1 ? ' each' : ''), sub: money0(bill) + ' + ' + num(t.rate, 1) + '% tip, ' + (people === 1 ? '1 person' : people + ' people'),
    copy: 'Bill ' + money0(bill) + ', tip ' + money0(t.tip) + ' (' + num(t.rate, 1) + '%), total ' + money0(t.total) + (people > 1 ? ', ' + money0(t.each) + ' each for ' + people : '')};
}
function setupTip(root) {
  const inp = root.querySelector('[name=pct]');
  const mark = () => $$('.tip-chips .chip', root).forEach(c => c.classList.toggle('on', N(inp.value) === +c.dataset.tip));
  root.addEventListener('click', e => {const c = e.target.closest('[data-tip]'); if (c) {inp.value = c.dataset.tip; fire(inp);}});
  root.addEventListener('input', mark); mark();
}

/* --- percentages --- */
function runPercent(v) {
  const n = k => N(v[k]), parts = {}, out = {};
  const of = n('of_x') != null && n('of_y') != null ? MATH.percentOf(n('of_x'), n('of_y')) : null;
  out.of = of; parts.of = {main: num(n('of_x')) + '% of ' + num(n('of_y')) + ' = ' + num(of), sub: 'X% of Y', copy: num(of)};
  const is = n('is_x') != null && n('is_y') != null ? MATH.whatPercent(n('is_x'), n('is_y')) : null;
  out.is = is; parts.is = {main: num(n('is_x')) + ' is ' + pct(is) + ' of ' + num(n('is_y')), sub: 'What percent', copy: pct(is)};
  const ch = n('ch_x') != null && n('ch_y') != null ? MATH.change(n('ch_x'), n('ch_y')) : null, chTxt = (ch > 0 ? '+' : ch < 0 ? MINUS : '') + pct(ch == null ? null : Math.abs(ch));
  out.ch = ch == null ? null : Math.abs(ch);
  out.chWord = ch == null ? (n('ch_x') === 0 ? 'cannot start from zero' : '') : ch > 0 ? 'increase' : ch < 0 ? 'decrease' : 'no change';
  parts.ch = {main: chTxt + ' from ' + num(n('ch_x')) + ' to ' + num(n('ch_y')), sub: 'Percent change', copy: chTxt.replace(MINUS, '-')};
  const y = n('pm_y'), x = n('pm_x');
  out.plus = y != null && x != null ? y * (1 + x / 100) : null; out.minus = y != null && x != null ? y * (1 - x / 100) : null;
  parts.pm = {main: num(y) + ' \u00b1 ' + num(x) + '% = ' + num(out.plus) + ' / ' + num(out.minus), sub: 'Plus or minus', copy: num(out.plus) + ' / ' + num(out.minus)};
  return {out, parts, main: parts.of.main, sub: parts.of.sub, copy: parts.of.copy};
}

/* --- unit converter --- */
function runUnits(v) {
  const cat = UNITS[v.cat] ? v.cat : 'length', x = N(v.val);
  const ok = id => UNITS[cat].units.some(u => u[0] === id), from = ok(v.from) ? v.from : UNIT_START[cat][0], to = ok(v.to) ? v.to : UNIT_START[cat][1];
  if (x == null) return {cat, from, to, out: {from: 'Enter a value', value: null, unit: ''}};
  const y = MATH.convert(cat, x, from, to);
  let msg = '';
  if (cat === 'temperature' && MATH.convert(cat, x, from, 'k') < 0) msg = 'That is colder than absolute zero.';
  if (cat === 'fuel' && !isFinite(y)) msg = 'Zero cannot be turned into this unit.';
  const txt = unitTxt(y);
  return {cat, from, to, x, y, out: {from: num(x, 6) + ' ' + uName(cat, from) + ' is', value: txt, unit: uLabel(cat, to), msg},
    main: txt + ' ' + uLabel(cat, to), sub: num(x, 6) + ' ' + uLabel(cat, from), copy: txt.replace(MINUS, '-') + ' ' + uLabel(cat, to)};
}
const unitTxt = y => !isFinite(y) ? '\u2014' : Math.abs(y) >= 1 || y === 0 ? num(y, Math.max(SET().decimals, Math.abs(y) < 100 ? 3 : SET().decimals)) : sig(y, 4);
function setupUnits(root, st) {
  const cats = slot(root, 'cats'), from = root.querySelector('[name=from]'), to = root.querySelector('[name=to]'), catIn = root.querySelector('[name=cat]');
  cats.innerHTML = Object.keys(UNITS).map(k => String(AK.html`<button type="button" class="chip" role="radio" data-cat="${k}" aria-checked="false">${UNITS[k].name}</button>`)).join('');
  const fillSel = (cat, f, t) => {
    const opts = UNITS[cat].units.map(u => String(AK.html`<option value="${u[0]}">${u[3]} \u2013 ${u[1]}</option>`)).join('');
    from.innerHTML = opts; to.innerHTML = opts; from.value = f; to.value = t;
    $$('.chip', cats).forEach(c => {c.setAttribute('aria-checked', String(c.dataset.cat === cat)); c.classList.toggle('on', c.dataset.cat === cat);});
  };
  const v = st.v, cat = UNITS[v.cat] ? v.cat : 'length';
  catIn.value = cat;
  fillSel(cat, v.from || UNIT_START[cat][0], v.to || UNIT_START[cat][1]);
  cats.addEventListener('click', e => {
    const c = e.target.closest('[data-cat]'); if (!c) return;
    const k = c.dataset.cat, s = SET().units === 'metric', pair = UNIT_START[k];
    catIn.value = k; fillSel(k, s ? pair[1] : pair[0], s ? pair[0] : pair[1]); fire(catIn);
  });
}
function afterUnits(root, res) {
  setText(root, 'all-title', res.x == null ? 'Every unit' : num(res.x, 6) + ' ' + uLabel(res.cat, res.from) + ' in every unit');
  setHTML(root, 'all', res.x == null ? '' : UNITS[res.cat].units.filter(u => u[0] !== res.from).map(u => {
    const y = MATH.convert(res.cat, res.x, res.from, u[0]);
    return String(AK.html`<li><button type="button" class="item${u[0] === res.to ? ' is-to' : ''}" data-act="unit-to" data-unit="${u[0]}"><span class="body"><span class="title">${u[3]}</span></span><span class="trail"><span class="value">${unitTxt(y)} ${u[1]}</span></span></button></li>`);
  }).join(''));
}

/* --- time card --- */
const DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
function runTime(v) {
  const mins = DAYS.map((d, i) => MATH.dayMinutes(v['s' + i], v['e' + i], N(v['b' + i]) || 0));
  const bad = mins.map((m, i) => m != null && m < 0 ? DAYS[i] : null).filter(Boolean);
  const rate = N(v.rate), mult = N(v.mult) || 1, T = MATH.timecard(mins, rate || 0, mult, N(v.weekly), N(v.daily));
  const out = {hm: hm(T.total), hours: T.total / 60, reg: hm(T.reg), ot: hm(T.ot), regPay: rate != null ? T.regPay : null, otPay: rate != null ? T.otPay : null, pay: rate != null ? T.pay : null,
    rateLine: rate != null ? 'at ' + money(rate, 2) + ' an hour' + (T.ot ? ', overtime ' + num(mult, 2) + 'x' : '') : 'Add a pay rate to see pay',
    msg: bad.length ? 'Check ' + bad.join(', ') + ': the break is longer than the shift.' : ''};
  mins.forEach((m, i) => out['d' + i] = m == null ? '' : m < 0 ? '!' : hm(m));
  const lines = DAYS.map((d, i) => mins[i] > 0 ? d + ' ' + v['s' + i] + '\u2013' + v['e' + i] + (N(v['b' + i]) ? ' (' + N(v['b' + i]) + ' min break)' : '') + ': ' + hm(mins[i]) : null).filter(Boolean);
  return {T, out, main: hm(T.total) + (rate != null ? ', ' + money(T.pay) : ''), sub: num(T.total / 60, 2) + ' h' + (T.ot ? ' incl. ' + hm(T.ot) + ' overtime' : '') + (rate != null ? ' at ' + money(rate, 2) + '/h' : ''),
    copy: lines.concat(['Total: ' + hm(T.total) + ' (' + num(T.total / 60, 2) + ' h)', 'Regular: ' + hm(T.reg) + ', overtime: ' + hm(T.ot)], rate != null ? ['Pay: ' + money(T.regPay) + ' + ' + money(T.otPay) + ' = ' + money(T.pay)] : []).join('\n')};
}
function setupTime(root) {
  root.addEventListener('click', e => {
    if (e.target.closest('[data-act=copy-monday]')) {
      const s = root.querySelector('[name=s0]').value, en = root.querySelector('[name=e0]').value, b = root.querySelector('[name=b0]').value;
      [1, 2, 3, 4].forEach(i => {root.querySelector('[name=s' + i + ']').value = s; root.querySelector('[name=e' + i + ']').value = en; root.querySelector('[name=b' + i + ']').value = b;});
      fire(root.querySelector('[name=s4]'));
    } else if (e.target.closest('[data-act=clear-week]')) {
      DAYS.forEach((d, i) => ['s', 'e', 'b'].forEach(p => {root.querySelector('[name=' + p + i + ']').value = '';}));
      fire(root.querySelector('[name=s0]'));
    }
  });
}

/* --- tire size --- */
function runTire(v) {
  const A = MATH.tire(v.a), B = String(v.b || '').trim() ? MATH.tire(v.b) : null, metric = SET().units === 'metric', su = metric ? 'km/h' : 'mph';
  const speed = N(v.speed) || (metric ? 100 : 60), out = {mainUnit: '', hA: A ? A.label : 'Current', hB: B ? B.label : 'New'};
  if (!A) return {out: Object.assign(out, {label: 'Tire size', main: '\u2014', msg: String(v.a || '').trim() ? 'Could not read \u201c' + v.a + '\u201d. Try a size like 225/45R17 or 33x12.50R15.' : 'Type the size printed on the tire, like 225/45R17.'})};
  const len = (mm, inch) => metric ? num(mm, 0) + ' mm' : num(inch, 2) + ' in';
  /* one unit per cell keeps the table readable on a phone: inches (US) or millimetres (metric); width is always mm, as on the tire */
  const fill = (t, k) => {out['w' + k] = num(t.w_mm, 0) + ' mm'; out['s' + k] = metric ? num(t.s_mm, 1) + ' mm' : num(t.s_in, 2) + ' in';
    out['d' + k] = len(t.d_mm, t.d_in); out['c' + k] = metric ? num(t.c_in * 25.4, 0) + ' mm' : num(t.c_in, 2) + ' in'; out['r' + k] = metric ? num(t.rpk, 1) + ' /km' : num(t.rpm, 1);};
  fill(A, 'A');
  out.vLabel = 'at ' + num(speed, 1) + ' ' + su + ' on the dial'; out.vA = num(speed, 1) + ' ' + su;
  if (String(v.b || '').trim() && !B) out.msg = 'Could not read the new size \u201c' + v.b + '\u201d.';
  if (!B) {
    ['wB', 'sB', 'dB', 'cB', 'rB', 'vB', 'wD', 'sD', 'dD', 'cD', 'rD', 'vD'].forEach(k => out[k] = '\u2014');
    Object.assign(out, {label: A.label + ' overall diameter', main: metric ? num(A.d_mm, 0) : num(A.d_in, 2), mainUnit: metric ? 'mm' : 'in',
      line: 'Sidewall ' + len(A.s_mm, A.s_in) + ' \u00b7 ' + (metric ? num(A.rpk, 1) + ' revs per km' : num(A.rpm, 1) + ' revs per mile')});
    return {A, out, main: A.label + ': ' + num(A.d_in, 2) + ' in tall', sub: num(A.rpm, 1) + ' revs per mile',
      copy: A.label + '\nOverall diameter: ' + num(A.d_in, 2) + ' in (' + num(A.d_mm, 0) + ' mm)\nSidewall: ' + num(A.s_mm, 1) + ' mm (' + num(A.s_in, 2) + ' in)\nCircumference: ' + num(A.c_in, 2) + ' in\nRevolutions per mile: ' + num(A.rpm, 1)};
  }
  fill(B, 'B');
  const dp = (x, y) => (y - x) / x * 100, sgn = x => (x > 0 ? '+' : x < 0 ? MINUS : '') + num(Math.abs(x), 2) + '%';
  const diff = dp(A.d_in, B.d_in), actual = speed * B.d_in / A.d_in;
  out.wD = sgn(dp(A.w_mm, B.w_mm)); out.sD = sgn(dp(A.s_mm, B.s_mm)); out.dD = sgn(diff); out.cD = sgn(dp(A.c_in, B.c_in)); out.rD = sgn(dp(A.rpm, B.rpm));
  out.vB = num(actual, 1) + ' ' + su; out.vD = sgn(dp(speed, actual)); /* speedometer difference = diameter difference */
  const off = Math.abs(actual - speed);
  Object.assign(out, {label: 'Real speed when it shows ' + num(speed, 1) + ' ' + su, main: num(actual, 1), mainUnit: su,
    line: Math.abs(diff) < 0.005 ? 'Same overall size. The speedometer stays right.' : 'New tires are ' + num(Math.abs(diff), 2) + '% ' + (diff > 0 ? 'taller' : 'shorter') + '. The speedometer reads ' + num(off, 1) + ' ' + su + (diff > 0 ? ' slow.' : ' fast.'),
    msg: Math.abs(diff) > 3 ? 'More than 3% difference: usually too much without recalibrating.' : out.msg || ''});
  return {A, B, diff, actual, speed, out, main: A.label + ' \u2192 ' + B.label + ': ' + sgn(diff), sub: 'At ' + num(speed, 1) + ' ' + su + ' you really do ' + num(actual, 1),
    copy: A.label + ' vs ' + B.label + '\nDiameter: ' + num(A.d_in, 2) + ' in vs ' + num(B.d_in, 2) + ' in (' + sgn(diff) + ')\nRevs per mile: ' + num(A.rpm, 1) + ' vs ' + num(B.rpm, 1) +
      '\nSpeedometer ' + num(speed, 1) + ' ' + su + ' = real ' + num(actual, 1) + ' ' + su};
}
function afterTire(root, res) {
  setText(root, 'speed-unit', SET().units === 'metric' ? 'km/h' : 'mph');
  const box = slot(root, 'tire-art');
  if (!res.A) {box.innerHTML = ''; return;}
  const list = [res.A].concat(res.B ? [res.B] : []), maxD = Math.max.apply(null, list.map(t => t.d_in)), W = 320, H = 150, sc = 132 / maxD;
  const one = (t, cx, cls) => {
    const R = t.d_in / 2 * sc, rr = t.rim / 2 * sc, cy = H - 6 - R;
    return `<g class="${cls}"><circle class="t-tire" cx="${cx}" cy="${cy.toFixed(1)}" r="${R.toFixed(1)}"/><circle class="t-tread" cx="${cx}" cy="${cy.toFixed(1)}" r="${(R - 4).toFixed(1)}"/>` +
      `<circle class="t-rim" cx="${cx}" cy="${cy.toFixed(1)}" r="${rr.toFixed(1)}"/><circle class="t-hub" cx="${cx}" cy="${cy.toFixed(1)}" r="${(rr * 0.28).toFixed(1)}"/></g>`;
  };
  const xs = res.B ? [88, 232] : [160];
  const topA = H - 6 - res.A.d_in * sc;
  box.innerHTML = `<svg viewBox="0 0 ${W} ${H}" class="tire-svg"><line class="t-ground" x1="0" x2="${W}" y1="${H - 5}" y2="${H - 5}"/>` +
    (res.B ? `<line class="t-guide" x1="20" x2="${W - 20}" y1="${topA.toFixed(1)}" y2="${topA.toFixed(1)}"/>` : '') +
    list.map((t, i) => one(t, xs[i], i ? 't-b' : 't-a')).join('') + '</svg>' +
    '<div class="tire-caps">' + list.map((t, i) => '<span class="' + (i ? 'cap-b' : 'cap-a') + '">' + AK.esc(t.label) + '</span>').join('') + '</div>';
}

/* --- fuel --- */
function fuelUnits(v) {return (v.units || SET().units) === 'metric' ? 'metric' : 'us';}
function runFuel(v) {
  const us = fuelUnits(v) === 'us', U = us ? {d: 'mi', v: 'gal', e: 'mpg', per: 'mile'} : {d: 'km', v: 'L', e: 'L/100 km', per: 'km'};
  if (v.mode === 'fill') {
    const o1 = N(v.odo1), o2 = N(v.odo2), dist = o1 != null && o2 != null ? o2 - o1 : N(v.driven), fuel = N(v.fuel), price = N(v.fprice);
    if (dist == null || fuel == null) return {U, out: {label: us ? 'Miles per gallon' : 'Litres per 100 km', main: '\u2014', msg: 'Enter the distance (or both odometer readings) and the fuel you added.'}};
    if (!(dist > 0) || !(fuel > 0)) return {U, out: {label: us ? 'Miles per gallon' : 'Litres per 100 km', main: '\u2014', msg: o1 != null && o2 != null && o2 <= o1 ? 'The odometer now should be higher than at the last fill.' : 'Distance and fuel must be above zero.'}};
    const F = MATH.fill(us, dist, fuel, price);
    return {F, U, out: {label: us ? 'Miles per gallon' : 'Litres per 100 km', main: num(F.eco, 1), mainUnit: U.e,
        k1: us ? 'Same as' : 'Same as', v1: us ? num(F.other, 1) + ' L/100 km' : num(F.other, 1) + ' mpg (US)', k2: 'Fuel cost', v2: F.cost != null ? money(F.cost) : '\u2014',
        k3: 'Cost per ' + U.per, v3: F.perDist != null ? money(F.perDist, 3) : '\u2014', msg: ''},
      main: num(F.eco, 1) + ' ' + U.e, sub: num(dist, 1) + ' ' + U.d + ' on ' + num(fuel, 2) + ' ' + U.v,
      copy: num(dist, 1) + ' ' + U.d + ' on ' + num(fuel, 2) + ' ' + U.v + ' = ' + num(F.eco, 1) + ' ' + U.e + (F.cost != null ? '\nFuel cost ' + money(F.cost) : '')};
  }
  const dist = N(v.dist), eco = N(v.eco), price = N(v.price), people = Math.max(1, Math.floor(N(v.people) || 1));
  if (dist == null || eco == null || price == null) return {U, out: {label: 'Trip fuel cost', main: '\u2014', msg: 'Enter the distance, fuel economy and fuel price.'}};
  if (!(dist >= 0) || !(eco > 0) || !(price >= 0)) return {U, out: {label: 'Trip fuel cost', main: '\u2014', msg: 'Fuel economy must be above zero.'}};
  const T = MATH.trip(us, dist, eco, price, !!v.round, people);
  return {T, U, out: {label: 'Trip fuel cost' + (v.round ? ' (round trip)' : ''), main: money(T.cost), mainUnit: '',
      k1: 'Fuel needed', v1: num(T.fuel, 2) + ' ' + U.v, k2: people > 1 ? 'Each of ' + people + ' pays' : 'Cost per ' + U.per, v2: people > 1 ? money(T.each) : money(T.perDist, 3),
      k3: 'Distance', v3: num(T.dist, 1) + ' ' + U.d, msg: ''},
    main: money(T.cost) + ' for ' + num(T.dist, 1) + ' ' + U.d, sub: num(eco, 1) + ' ' + U.e + ' at ' + money(price, 2) + '/' + U.v,
    copy: 'Trip ' + num(T.dist, 1) + ' ' + U.d + ': ' + num(T.fuel, 2) + ' ' + U.v + ' = ' + money(T.cost) + (people > 1 ? ' (' + money(T.each) + ' each)' : '')};
}
function setupFuel(root, st) {if (!st.v.units) {const u = root.querySelector('[name=units][value=' + SET().units + ']'); if (u) u.checked = true;}}
function afterFuel(root, res, v) {
  $$('.mode-fields', root).forEach(g => g.hidden = g.dataset.mode !== (v.mode === 'fill' ? 'fill' : 'trip'));
  const U = res.U || {d: 'mi', v: 'gal', e: 'mpg'};
  $$('[data-u=dist]', root).forEach(e => e.textContent = U.d);
  $$('[data-u=vol]', root).forEach(e => e.textContent = U.v);
  $$('[data-u=eco]', root).forEach(e => e.textContent = U.e);
  $$('[data-u=per]', root).forEach(e => e.textContent = '/ ' + U.v);
  const g = slot(root, 'fuel-grid'); if (g) g.hidden = !res.T && !res.F;
}

/* ===== 7. ENGINE: inputs in, answers out, remembered, copied, saved ===== */
let cur = null; /* the calculator on screen: {id, root, v, res} */
function readInputs(root) {
  const v = {};
  $$('input[name],select[name],textarea[name]', root).forEach(e => {
    if (e.type === 'radio') {if (e.checked) v[e.name] = e.value;} else if (e.type === 'checkbox') v[e.name] = e.checked; else if (e.type !== 'file') v[e.name] = e.value;
  });
  return v;
}
function fillInputs(root, v) {
  $$('input[name],select[name],textarea[name]', root).forEach(e => {
    if (!(e.name in v)) return;
    if (e.type === 'radio') e.checked = e.value === String(v[e.name]); else if (e.type === 'checkbox') e.checked = !!v[e.name]; else if (e.type !== 'file') e.value = v[e.name] == null ? '' : v[e.name];
  });
}
const FMT = {money: x => money(x), pct: x => pct(x), num: x => num(x), num2: x => num(x, 2, true), int: x => num(x, 0), text: x => x};
function paint(root, out) {
  out = out || {};
  $$('[data-out]', root).forEach(el => {
    const k = el.dataset.out, x = out[k], f = FMT[el.dataset.fmt] || FMT.text;
    let t = x == null || x === '' ? (el.dataset.fmt === 'text' ? '' : '\u2014') : typeof x === 'number' ? (isFinite(x) ? f(x) : '\u2014') : String(x);
    el.textContent = t;
    if (typeof x === 'number') el.dataset.raw = String(x); else delete el.dataset.raw;
    if (el.classList.contains('r-num')) el.style.setProperty('--len', Math.max(3, t.length));
    if (k === 'msg' || k === 'note') el.hidden = !t;
  });
  $$('[data-show]', root).forEach(el => el.hidden = !out[el.dataset.show]);
}
/* inputs are saved a moment after typing stops (and straight away before any screen change) */
let saveTimer = 0, pending = null;
function remember(st, v) {
  const j = JSON.stringify(v);
  if (j === st.saved) return;
  st.saved = j; clearTimeout(saveTimer);
  pending = {id: st.id, v};
  saveTimer = setTimeout(flushSave, 300);
}
function flushSave() {clearTimeout(saveTimer); if (pending) {const p = pending; pending = null; db.set('inputs.' + p.id, p.v);}}
function safeRun(c, v) {try {return c.run(v) || {};} catch (e) {console.warn(e); return {msg: 'Something does not add up. Check the numbers.'};}}
function startValues(c) {
  const saved = (db.get('inputs') || {})[c.id];
  return Object.assign(JSON.parse(JSON.stringify(c.defaults)), saved || {});
}
function renderCalc(page, id) {
  flushSave();
  const c = CALC[id];
  if (!c) {page.innerHTML = String(AK.empty({art: 'search', title: 'No such calculator', text: 'It may have been renamed.', action: {label: 'All calculators', act: 'home'}})); return;}
  page.append(tpl('view-calc'));
  setText(page, 'group', c.group); setText(page, 'name', c.name); setText(page, 'blurb', c.blurb);
  const pinned = db.get('pins').indexOf(id) > -1, pb = $('.pin-btn', page);
  pb.setAttribute('aria-pressed', String(pinned)); pb.setAttribute('aria-label', pinned ? 'Unpin from Tools' : 'Pin to the top of Tools');
  const root = slot(page, 'body');
  root.append(tpl('calc-' + id));
  $$('[data-sym]', root).forEach(e => e.textContent = SET().symbol);
  const st = cur = {id, root, v: startValues(c), res: null, saved: null};
  fillInputs(root, st.v);
  if (c.setup) c.setup(root, st);
  const update = first => {
    const v = Object.assign(readInputs(root), c.id === 'pricing' ? {known: st.v.known} : {});
    st.v = v;
    const res = st.res = safeRun(c, v);
    paint(root, Object.assign({}, res.out, res.msg != null && !(res.out && res.out.msg) ? {msg: res.msg} : {}));
    if (c.after) c.after(root, res, v);
    if (first === true) st.saved = JSON.stringify(Object.assign(readInputs(root), c.id === 'pricing' ? {known: st.v.known} : {}));
    else remember(st, Object.assign(readInputs(root), c.id === 'pricing' ? {known: st.v.known} : {}));
  };
  root.addEventListener('input', update);
  root.addEventListener('change', update);
  /* big amounts get thousands separators when you leave the box: 25000 -> 25,000 */
  root.addEventListener('focusout', e => {
    const el = e.target;
    if (!el.matches || !el.matches('input[data-num]') || el.closest('.solver')) return;
    const n = N(el.value);
    if (n != null && Math.abs(n) >= 1000) el.value = n.toLocaleString(LOC(), {maximumFractionDigits: 6});
  });
  update(true);
  drawRecent(page, id);
}
function drawRecent(page, id) {
  const list = db.get('history').filter(h => h.calc === id).slice(0, 4);
  slot(page, 'recent-wrap').hidden = !list.length;
  setHTML(page, 'recent', list.map(histRow).join(''));
}
function histRow(h) {
  const c = CALC[h.calc] || {name: h.calc, icon: 'clock'};
  return String(AK.html`<li class="hist-li"><button type="button" class="item" data-act="open-hist" data-id="${h.id}"><span class="lead">${icon(c.icon)}</span>
    <span class="body"><span class="title wrap">${h.main}</span>${h.sub ? AK.html`<span class="sub wrap">${h.sub}</span>` : ''}<span class="sub when">${c.name} \u00b7 ${AK.fmt.ago(new Date(h.at))}</span></span></button>
    <button type="button" class="btn icon ghost sm del-hist" data-act="del-hist" data-id="${h.id}" aria-label="Delete this answer">${icon('x')}</button></li>`);
}
function addHistory(part) {
  if (!cur || !cur.res) return false;
  const r = part && cur.res.parts ? cur.res.parts[part] : cur.res;
  if (!r || !r.main || /\u2014/.test(r.main)) return false;
  const h = db.get('history'), last = h.find(x => x.calc === cur.id);
  if (last && last.main === r.main && last.sub === r.sub) return 'same';
  db.set('history', [{id: AK.id(), calc: cur.id, at: Date.now(), main: r.main, sub: r.sub || '', inputs: cur.v}].concat(h).slice(0, 200));
  return true;
}
function copyAnswer(part) {
  if (!cur || !cur.res) return;
  const r = part && cur.res.parts ? cur.res.parts[part] : cur.res;
  if (!r || !r.copy || /\u2014/.test(r.copy)) {AK.toast('Nothing to copy yet. Fill in the boxes first.', 'warn'); return;}
  addHistory(part);
  AK.copy(r.copy, {done: 'Copied and saved to History'});
  const page = document.querySelector('.page'); if (page) drawRecent(page, cur.id);
}

/* ===== 8. HOME: pinned tiles + every calculator ===== */
function renderHome(page, ctx) {
  if (ctx.param) return renderCalc(page, ctx.param);
  flushSave(); cur = null;
  page.append(tpl('view-home'));
  const pins = db.get('pins').filter(id => CALC[id]);
  const last = c => {const r = safeRun(c, startValues(c)); return r.main && !/\u2014/.test(r.main) ? r.main : c.blurb;};
  setHTML(page, 'pinned', pins.length ? pins.map(id => {const c = CALC[id]; return String(AK.html`<button type="button" class="tile" data-act="go" data-to="/tools/${id}">
      <span class="t-ico">${icon(c.icon)}</span><span class="t-name">${c.name}</span><span class="t-last">${last(c)}</span></button>`);}).join('') :
    String(AK.html`<p class="pin-hint">${icon('star')} Tap the star on any calculator to pin it here.</p>`));
  setHTML(page, 'groups', GROUPS.map(g => String(AK.html`<section class="section calc-group"><div class="sec-head"><h2>${g}</h2></div><ul class="list">${CALCS.filter(c => c.group === g).map(c => {
    const on = pins.indexOf(c.id) > -1;
    return AK.html`<li class="calc-li" data-keys="${c.name} ${c.keys}"><button type="button" class="item" data-act="go" data-to="/tools/${c.id}"><span class="lead">${icon(c.icon)}</span><span class="body"><span class="title">${c.name}</span><span class="sub">${c.blurb}</span></span></button>
      <button type="button" class="btn icon ghost pin-mini" data-act="pin" data-id="${c.id}" aria-pressed="${on}" aria-label="${on ? 'Unpin ' : 'Pin '}${c.name}">${icon('star')}</button></li>`;
  })}</ul></section>`)).join(''));
  const q = $('#find', page);
  const filter = () => {
    const s = q.value.trim().toLowerCase(), words = s.split(/\s+/).filter(Boolean);
    const hit = el => words.every(w => el.dataset.keys.toLowerCase().indexOf(w) > -1);
    let n = 0;
    $$('.calc-li', page).forEach(li => {const h = !s || hit(li); li.hidden = !h; if (h) n++;});
    $$('.calc-group', page).forEach(g => g.hidden = !$$('.calc-li', g).some(li => !li.hidden));
    slot(page, 'pinned-wrap').hidden = !!s;
    slot(page, 'nomatch').hidden = n > 0;
  };
  q.addEventListener('input', filter);
  q.addEventListener('keydown', e => {if (e.key === 'Enter') {const li = $$('.calc-li', page).find(x => !x.hidden); if (li) app.go(li.querySelector('.item').dataset.to);}});
}

/* ===== 9. HISTORY ===== */
function renderHistory(page) {
  flushSave(); cur = null;
  page.append(tpl('view-history'));
  const all = db.get('history'), q = $('#hist-q', page);
  slot(page, 'clear-btn').hidden = !all.length;
  q.closest('.search').hidden = all.length < 6;
  const draw = () => {
    const s = q.value.trim().toLowerCase(), list = all.filter(h => !s || (h.main + ' ' + h.sub + ' ' + (CALC[h.calc] || {}).name).toLowerCase().indexOf(s) > -1);
    setHTML(page, 'list', list.length ? '<ul class="list">' + list.map(histRow).join('') + '</ul>' :
      String(AK.html`<div class="card">${AK.empty({art: 'inbox', title: all.length ? 'Nothing matches' : 'No saved answers yet', text: all.length ? 'Try other words.' : 'Tap Copy or Save on any calculator and the answer is kept here, with its numbers.', action: all.length ? null : {label: 'Open a calculator', act: 'home'}})}</div>`));
  };
  q.addEventListener('input', draw); draw();
}

/* ===== 10. SETTINGS ===== */
function renderSettings(page) {
  flushSave(); cur = null;
  page.append(tpl('view-settings'));
  const form = $('#set-form', page), s = SET();
  fillInputs(form, s);
  const example = () => setText(page, 'dec-example', '1234.56789 shows as ' + num(1234.56789) + ', money as ' + money(1234.5));
  const save = () => {
    const v = readInputs(form), d = Math.max(0, Math.min(6, Math.round(N(v.decimals) == null ? 2 : N(v.decimals))));
    db.set('settings', {symbol: String(v.symbol || '').trim().slice(0, 4) || '$', cents: !!v.cents, decimals: d, units: v.units === 'metric' ? 'metric' : 'us'});
    example();
  };
  form.addEventListener('input', save); form.addEventListener('change', save);
  form.addEventListener('click', e => {const b = e.target.closest('[data-sym-pick]'); if (b) {form.elements.symbol.value = b.dataset.symPick.trim(); save();}});
  example();
  const h = db.get('history').length, n = Object.keys(db.get('inputs') || {}).length;
  setText(page, 'data-line', plural(h, 'saved answer') + ' and the last numbers of ' + plural(n, 'calculator') + ' are kept on this phone.');
  $('#restore-in', page).addEventListener('change', e => {const f = e.target.files[0]; e.target.value = ''; if (f) restore(f);});
}
function restore(file) {
  return file.text().then(t => {
    let j; try {j = JSON.parse(t);} catch (e) {AK.toast('That file is not a backup.', 'bad'); return;}
    const d = j && j.data && j.store ? j.data : j;
    if (!d || !Array.isArray(d.history) || typeof d.inputs !== 'object') {AK.toast('That file is not a backup from these calculators.', 'bad'); return;}
    AK.confirm('Restore this backup?', {text: plural(d.history.length, 'saved answer') + '. Your settings and last numbers are replaced.', ok: 'Restore'}).then(ok => {
      if (!ok) return;
      db.set({settings: Object.assign({}, DEF_SET, d.settings || {}), pins: Array.isArray(d.pins) ? d.pins : DEFAULT_PINS.slice(), inputs: d.inputs || {}, history: d.history.slice(0, 200)});
      app.refresh(); AK.toast('Backup restored', {type: 'ok', undo: () => {db.undo(); app.refresh();}});
    });
  });
}

/* ===== 11. PRINTING (loan schedule, tire comparison) with the owner's letterhead ===== */
function docFrom(id) {
  const f = tpl(id), el = f.firstElementChild;
  $$('h2', el).forEach(h => {if (/\{\{/.test(h.textContent)) h.textContent = BUSINESS;});
  setText(el, 'date', AK.fmt.date(new Date(), 'long'));
  return el;
}
function printLoan() {
  const res = cur && cur.res; if (!res || !res.L) {AK.toast('Fill in the loan first.', 'warn'); return;}
  const el = docFrom('doc-loan'), byMonth = cur.v.sched === 'month', rows = loanRows(res.L, byMonth);
  setHTML(el, 'meta', [['Loan', res.sub], ['Monthly payment', money(res.L.payment)], ['Total interest', money(res.L.interest)], ['Total paid', money(res.L.total)]]
    .map(m => String(AK.html`<div><dt>${m[0]}</dt><dd>${m[1]}</dd></div>`)).join(''));
  setText(el, 'col-1', byMonth ? 'Month' : 'Year');
  setHTML(el, 'rows', rows.map(r => String(AK.html`<tr><td>${r.label.replace('Year ', '')}</td><td class="num">${money(r.pay)}</td><td class="num">${money(r.prin)}</td><td class="num">${money(r.int)}</td><td class="num">${money(r.bal)}</td></tr>`)).join(''));
  AK.print(BUSINESS + ' loan schedule', el);
}
function printTire() {
  const res = cur && cur.res; if (!res || !res.A) {AK.toast('Type a tire size first.', 'warn'); return;}
  const el = docFrom('doc-tire'), t = cur.root.querySelector('.tire-table').cloneNode(true);
  setHTML(el, 'body', String(AK.html`<h3 class="doc-h3">${res.out.label}: ${res.out.main} ${res.out.mainUnit}</h3><p>${res.out.line || ''}</p>`));
  slot(el, 'body').append(t);
  AK.print(BUSINESS + ' tire sizes', el);
}

/* ===== 12. ACTIONS + BOOT ===== */
AK.act({
  home: () => app.go('/tools'),
  go: el => app.go(el.dataset.to),
  copy: el => copyAnswer(el.dataset.part),
  save: () => {const r = addHistory(); AK.toast(r === 'same' ? 'Already saved' : r ? 'Saved to History' : 'Nothing to save yet', r ? 'ok' : 'warn'); if (r === true) drawRecent(document.querySelector('.page'), cur.id);},
  pin: el => {
    const id = el.dataset.id || (cur && cur.id); if (!id) return;
    const p = db.get('pins'), on = p.indexOf(id) > -1;
    db.set('pins', on ? p.filter(x => x !== id) : p.concat([id]));
    AK.toast(on ? 'Unpinned' : 'Pinned to the top of Tools', 'ok');
    app.refresh();
  },
  reset: () => {
    if (!cur) return;
    const id = cur.id; clearTimeout(saveTimer); pending = null;
    db.update(s => {delete s.inputs[id];});
    app.refresh();
    AK.toast('Back to the example numbers', {undo: () => {db.undo(); app.refresh();}});
  },
  'clear-mode': () => {
    if (!cur || cur.id !== 'pricing') return;
    const mode = cur.v.mode || 'm';
    MODES[mode].fields.forEach(f => {cur.root.querySelector('[name=' + f + ']').value = '';});
    cur.v.known = Object.assign({}, cur.v.known, {[mode]: []});
    fire(cur.root.querySelector('[name=' + MODES[mode].fields[0] + ']'));
  },
  swap: () => {
    if (!cur) return;
    const f = cur.root.querySelector('[name=from]'), t = cur.root.querySelector('[name=to]'), a = f.value;
    f.value = t.value; t.value = a; fire(t);
  },
  'unit-to': el => {if (!cur) return; const t = cur.root.querySelector('[name=to]'); t.value = el.dataset.unit; fire(t); window.scrollTo({top: 0, behavior: AK.still() ? 'auto' : 'smooth'});},
  'open-hist': el => {
    const h = db.get('history').find(x => x.id === el.dataset.id); if (!h || !CALC[h.calc]) return;
    db.set('inputs.' + h.calc, h.inputs);
    app.go('/tools/' + h.calc);
  },
  'del-hist': el => {
    db.set('history', db.get('history').filter(x => x.id !== el.dataset.id));
    const page = document.querySelector('.page');
    if (cur) drawRecent(page, cur.id); else app.refresh();
    AK.toast('Answer deleted', {undo: () => {db.undo(); if (cur) drawRecent(document.querySelector('.page'), cur.id); else app.refresh();}});
  },
  'clear-history': async () => {
    if (!await AK.confirm('Clear all saved answers?', {ok: 'Clear', danger: true})) return;
    db.set('history', []); app.refresh();
    AK.toast('History cleared', {undo: () => {db.undo(); app.refresh();}});
  },
  'reset-all': async () => {
    if (!await AK.confirm('Reset every calculator?', {text: 'They go back to the example numbers. Saved answers stay.', ok: 'Reset', danger: true})) return;
    db.set('inputs', {}); app.refresh();
    AK.toast('Every calculator reset', {undo: () => {db.undo(); app.refresh();}});
  },
  backup: () => {db.export(BUSINESS.replace(/[^\w-]+/g, '-').toLowerCase() + '-calculators-backup-' + AK.today() + '.json'); AK.toast('Backup saved to your downloads', 'ok');},
  'print-loan': () => printLoan(),
  'print-tire': () => printTire()
});

/* BOOT: the app shell (bottom tabs on phones, sidebar on computers), created last so everything above exists. */
const app = AK.app({
  title: BUSINESS, icon: 'grid',
  tabs: [
    {id: 'tools', label: 'Tools', icon: 'grid', render: renderHome},
    {id: 'history', label: 'History', icon: 'clock', render: renderHistory},
    {id: 'prefs', label: 'Settings', icon: 'settings', render: renderSettings} /* id 'prefs' so it never clashes with another app's remembered tab on the same site */
  ]
});
AK.pwa({name: BUSINESS + ' Calculators', short_name: 'Calculators'});
window.addEventListener('pagehide', flushSave);
document.addEventListener('visibilitychange', () => {if (document.hidden) flushSave();});
