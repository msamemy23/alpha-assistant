/* app.js | Event Invite blueprint | plain JavaScript, no libraries.
   The event date lives in ONE place: data-date on #countdown (for example 2027-06-12T16:00, or 2027-06-12 for a whole day).
   From it this file fills the date lines, the schedule times (data-offset = minutes after the start), the countdown
   and the "Add to my calendar" file. With no date (or no JavaScript) the page shows friendly fallback text instead.
   Sections: 1 date helpers  2 countdown  3 date text, schedule, calendar  4 maps, copy  5 RSVP  6 photo viewer
             7 confetti  8 phone bar and dots */
(function () {
  'use strict';
  var d = document, W = window, root = d.documentElement;
  var $ = function (s, r) { return [].slice.call((r || d).querySelectorAll(s)); };
  var pad = function (n) { return (n < 10 ? '0' : '') + n; };
  var shot = /[?&]shot(=|&|$)/.test(location.search) || root.classList.contains('shot');
  var calm = shot || (W.matchMedia && W.matchMedia('(prefers-reduced-motion: reduce)').matches);
  var lang = root.getAttribute('lang') || undefined;

  /* ---------- 1. date helpers ---------- */
  function readDate() {
    var el = d.getElementById('countdown');
    var s = el ? (el.getAttribute('data-date') || '').trim() : '';
    if (!s) return null;
    var m = /^(\d{4})-(\d{1,2})-(\d{1,2})(?:[T ](\d{1,2}):(\d{2}))?/.exec(s);
    if (!m) return null;
    var dt = /([zZ]|[+-]\d{2}:?\d{2})$/.test(s) ? new Date(s) : new Date(+m[1], +m[2] - 1, +m[3], m[4] ? +m[4] : 0, m[5] ? +m[5] : 0);
    if (isNaN(dt.getTime())) return null;
    var hrs = parseFloat(el.getAttribute('data-hours-long')) || 5;
    return { start: dt, hasTime: !!m[4], end: new Date(dt.getTime() + (m[4] ? hrs : 24) * 3600e3) };
  }
  function fmtDate(dt) {
    try { return dt.toLocaleDateString(lang, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }); }
    catch (e) { return dt.toDateString(); }
  }
  function fmtTime(dt) {
    try { return dt.toLocaleTimeString(lang, { hour: 'numeric', minute: '2-digit' }); }
    catch (e) { return pad(dt.getHours()) + ':' + pad(dt.getMinutes()); }
  }

  /* ---------- 2. countdown ---------- */
  function countdown(ev) {
    var el = d.getElementById('countdown');
    if (!el) return;
    var section = el.closest('section');
    var state = el.querySelector('[data-state]');
    var note = el.querySelector('[data-note]');
    var units = {};
    $('[data-unit]', el).forEach(function (u) { units[u.getAttribute('data-unit')] = u; });
    if (!ev) return; // no date yet: the HTML already shows "Add the date"
    if (section) section.classList.add('has-date');
    var timer = 0;
    function set(u, v) {
      var n = units[u];
      if (!n || n.textContent === v) return;
      n.textContent = v;
      if (!calm) { n.classList.remove('is-tick'); void n.offsetWidth; n.classList.add('is-tick'); }
    }
    function tick() {
      var now = Date.now(), left = ev.start.getTime() - now;
      if (left > 0) {
        el.classList.add('is-live');
        var s = Math.floor(left / 1000);
        var days = Math.floor(s / 86400);
        set('days', days > 99 ? String(days) : pad(days));
        set('hours', pad(Math.floor(s % 86400 / 3600)));
        set('minutes', pad(Math.floor(s % 3600 / 60)));
        set('seconds', pad(s % 60));
        return;
      }
      el.classList.remove('is-live');
      if (now < ev.end.getTime()) {
        if (state) state.textContent = 'Happening now';
        if (note) note.textContent = 'If you are on your way, we will see you soon.';
      } else {
        if (state) state.textContent = 'What a day';
        if (note) note.textContent = 'Thank you to everyone who celebrated with us.';
      }
      clearInterval(timer);
    }
    tick();
    if (!shot) timer = setInterval(tick, 1000);
  }

  /* ---------- 3. date lines, schedule times and the calendar file ---------- */
  function dateText(ev) {
    if (!ev) return;
    $('[data-date-text]').forEach(function (n) {
      var kind = n.getAttribute('data-date-text');
      if (kind === 'long') n.textContent = fmtDate(ev.start);
      else if (kind === 'time') n.textContent = ev.hasTime ? fmtTime(ev.start) : '';
    });
    if (!ev.hasTime) return;
    $('[data-offset]').forEach(function (t) {
      var mins = parseFloat(t.getAttribute('data-offset'));
      if (isNaN(mins)) return;
      var at = new Date(ev.start.getTime() + mins * 60000);
      t.textContent = fmtTime(at);
      t.setAttribute('datetime', at.getFullYear() + '-' + pad(at.getMonth() + 1) + '-' + pad(at.getDate()) + 'T' + pad(at.getHours()) + ':' + pad(at.getMinutes()));
    });
  }
  function calendar(ev) {
    var btns = $('[data-ics]');
    if (!ev || ev.end.getTime() < Date.now()) return;
    var title = (d.getElementById('ev-title') || {}).textContent || d.title;
    var where = (d.querySelector('[data-maps]') || { getAttribute: function () { return ''; } }).getAttribute('data-maps') || '';
    var slug = d.body.getAttribute('data-slug') || 'event';
    function local(dt) { return dt.getFullYear() + pad(dt.getMonth() + 1) + pad(dt.getDate()) + 'T' + pad(dt.getHours()) + pad(dt.getMinutes()) + '00'; }
    function day(dt) { return dt.getFullYear() + pad(dt.getMonth() + 1) + pad(dt.getDate()); }
    function esc(s) { return String(s).trim().replace(/\\/g, '\\\\').replace(/([,;])/g, '\\$1').replace(/\n/g, '\\n'); }
    btns.forEach(function (b) {
      b.hidden = false;
      b.addEventListener('click', function () {
        var now = new Date();
        var stamp = now.getUTCFullYear() + pad(now.getUTCMonth() + 1) + pad(now.getUTCDate()) + 'T' + pad(now.getUTCHours()) + pad(now.getUTCMinutes()) + '00Z';
        var lines = ['BEGIN:VCALENDAR', 'VERSION:2.0', 'PRODID:-//Alpha Studio//Event invite//EN', 'BEGIN:VEVENT',
          'UID:' + slug + '-' + day(ev.start) + '@invite', 'DTSTAMP:' + stamp,
          ev.hasTime ? 'DTSTART:' + local(ev.start) : 'DTSTART;VALUE=DATE:' + day(ev.start),
          ev.hasTime ? 'DTEND:' + local(ev.end) : 'DTEND;VALUE=DATE:' + day(ev.end),
          'SUMMARY:' + esc(title), 'LOCATION:' + esc(where), 'END:VEVENT', 'END:VCALENDAR'];
        var url = URL.createObjectURL(new Blob([lines.join('\r\n')], { type: 'text/calendar' }));
        var a = d.createElement('a');
        a.href = url; a.download = slug + '.ics';
        d.body.appendChild(a); a.click(); a.remove();
        setTimeout(function () { URL.revokeObjectURL(url); }, 4000);
        toast('Calendar file ready: open it to add the date.');
      });
    });
  }

  /* ---------- 4. maps link, copy address, toast ---------- */
  var toastEl = d.querySelector('.toast'), toastT = 0;
  function toast(msg) {
    if (!toastEl) return;
    toastEl.textContent = msg;
    toastEl.classList.add('is-on');
    clearTimeout(toastT);
    toastT = setTimeout(function () { toastEl.classList.remove('is-on'); }, 2600);
  }
  function maps() {
    $('[data-maps]').forEach(function (a) {
      var q = (a.getAttribute('data-maps') || '').trim();
      if (q) a.href = 'https://www.google.com/maps/search/?api=1&query=' + encodeURIComponent(q);
    });
    var first = d.querySelector('[data-maps]');
    $('.rsvp-bar a[href="#venue"]').forEach(function (a) { if (first) { a.href = first.href; a.target = '_blank'; a.rel = 'noopener'; } });
    $('[data-copy]').forEach(function (b) {
      b.addEventListener('click', function () {
        var text = b.getAttribute('data-copy') || '';
        var done = function () { toast('Address copied'); };
        if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(text).then(done, fallback);
        else fallback();
        function fallback() {
          var t = d.createElement('textarea');
          t.value = text; t.setAttribute('readonly', ''); t.style.position = 'fixed'; t.style.opacity = '0';
          d.body.appendChild(t); t.select();
          try { d.execCommand('copy'); done(); } catch (e) { toast(text); }
          t.remove();
        }
      });
    });
    // fit the city label on the drawn map
    $('.map__label').forEach(function (g) {
      var t = g.querySelector('text'), r = g.querySelector('rect');
      try { var cx = parseFloat(t.getAttribute('x')) || 330, w = Math.min(Math.max(t.getComputedTextLength() + 40, 90), 300); r.setAttribute('width', w); r.setAttribute('x', Math.min(cx - w / 2, 470 - w)); } catch (e) { /* not rendered yet */ }
    });
  }

  /* ---------- 5. RSVP: builds a text or an email with the reply written out ---------- */
  function rsvp(form) {
    var mail = form.getAttribute('action') || '';
    var tel = (form.getAttribute('data-phone') || '').replace(/^tel:/i, '');
    var hasMail = /^mailto:/i.test(mail), hasTel = /^\+?[0-9][0-9 ().-]{5,}$/.test(tel);
    var sms = form.querySelector('[data-send="sms"]'), em = form.querySelector('[data-send="email"]');
    var status = form.querySelector('.rsvp__status');
    var via = hasTel ? 'sms' : 'email';
    if (sms) sms.hidden = !hasTel;
    if (em && !hasMail && hasTel) em.hidden = true;
    if (em && hasTel) { em.classList.remove('btn--solid'); em.classList.add('btn--line'); }
    [sms, em].forEach(function (b) { if (b) b.addEventListener('click', function () { via = b.getAttribute('data-send'); }); });

    var guests = form.querySelector('#r-guests');
    $('[data-step]', form).forEach(function (b) {
      b.addEventListener('click', function () {
        var v = (parseInt(guests.value, 10) || 1) + parseInt(b.getAttribute('data-step'), 10);
        guests.value = Math.max(+guests.min || 1, Math.min(+guests.max || 20, v));
      });
    });
    var guestRow = form.querySelector('[data-guests]');
    $('input[name="reply"]', form).forEach(function (r) {
      r.addEventListener('change', function () {
        var yes = form.elements.reply.value !== 'no';
        if (guestRow) guestRow.hidden = !yes;
        if (yes) burst(r.closest('label'));
      });
    });

    form.addEventListener('submit', function (e) {
      e.preventDefault();
      if (e.submitter && e.submitter.getAttribute('data-send')) via = e.submitter.getAttribute('data-send');
      var f = form.elements, name = (f.name.value || '').trim();
      if (!name) { f.name.focus(); status.textContent = 'Please add your name first.'; return; }
      var yes = f.reply.value !== 'no';
      var event = form.getAttribute('data-event') || d.title;
      var lines = ['RSVP for ' + event, '', 'Name: ' + name, 'Reply: ' + (yes ? 'Joyfully accepts' : "Sadly can't make it")];
      if (yes) lines.push('Guests, including me: ' + (parseInt(f.guests.value, 10) || 1));
      if ((f.note.value || '').trim()) lines.push('', f.note.value.trim());
      var body = lines.join('\r\n'), url = '';
      var number = tel.replace(/[^0-9+]/g, '');
      if (via === 'sms' && hasTel) url = 'sms:' + number + '?&body=' + encodeURIComponent(body);
      else if (hasMail) url = mail.split('?')[0] + '?subject=' + encodeURIComponent('RSVP: ' + name + (yes ? ' is coming' : " can't make it")) + '&body=' + encodeURIComponent(body);
      else if (hasTel) url = 'sms:' + number + '?&body=' + encodeURIComponent(body);
      if (!url) { status.textContent = 'Please reply using the contact details on this page.'; return; }
      status.textContent = url.indexOf('sms:') === 0 ? 'Your messages app should open with the reply written out. Just press send.' : 'Your email app should open with the reply written out. Just press send.';
      if (yes) burst(e.submitter || form);
      setTimeout(function () { W.location.href = url; }, yes && !calm ? 450 : 0);
    });
  }

  /* ---------- 6. photo viewer ---------- */
  function gallery(strip) {
    var dlg = d.querySelector('.lightbox');
    var items = $('.strip__item', strip);
    if (!dlg || !items.length) return;
    var img = dlg.querySelector('.lightbox__img'), cap = dlg.querySelector('.lightbox__cap'), at = 0, opener = null;
    function show(i) {
      at = (i + items.length) % items.length;
      var src = items[at].querySelector('img');
      img.src = src.currentSrc || src.src;
      img.alt = src.alt || '';
      cap.textContent = (src.alt ? src.alt + '  ' : '') + '(' + (at + 1) + ' of ' + items.length + ')';
      if (!calm) { img.classList.remove('is-in'); void img.offsetWidth; img.classList.add('is-in'); }
    }
    function open(i) {
      opener = items[i];
      show(i);
      if (dlg.showModal) dlg.showModal(); else dlg.setAttribute('open', '');
      dlg.querySelector('.lightbox__close').focus();
    }
    function close() { if (dlg.close) dlg.close(); else dlg.removeAttribute('open'); }
    items.forEach(function (b, i) { b.addEventListener('click', function () { open(i); }); });
    dlg.querySelector('.lightbox__close').addEventListener('click', close);
    dlg.querySelector('.lightbox__prev').addEventListener('click', function () { show(at - 1); });
    dlg.querySelector('.lightbox__next').addEventListener('click', function () { show(at + 1); });
    dlg.addEventListener('close', function () { if (opener) opener.focus(); });
    dlg.addEventListener('click', function (e) { if (e.target === dlg) close(); });
    dlg.addEventListener('keydown', function (e) {
      if (e.key === 'ArrowLeft') show(at - 1);
      else if (e.key === 'ArrowRight') show(at + 1);
      else if (e.key === 'Escape' && !dlg.close) close();
    });
    var x0 = null;
    dlg.addEventListener('pointerdown', function (e) { x0 = e.clientX; });
    dlg.addEventListener('pointerup', function (e) {
      if (x0 === null) return;
      var dx = e.clientX - x0; x0 = null;
      if (Math.abs(dx) > 50) show(at + (dx < 0 ? 1 : -1));
    });
  }

  /* ---------- 7. confetti: gentle and slow in the hero; a short burst when someone says yes ---------- */
  function palette() {
    var cs = getComputedStyle(root), out = [];
    ['--c-accent', '--c-accent', '--c-surface', '--c-bg', '--c-on-accent'].forEach(function (v) {
      var c = cs.getPropertyValue(v).trim();
      if (c) out.push(c);
    });
    return out.length ? out : ['#e8b04b', '#e8b04b', '#f4e7dc', '#ffffff'];
  }
  function rng(seed) { return function () { seed |= 0; seed = seed + 0x6D2B79F5 | 0; var t = Math.imul(seed ^ seed >>> 15, 1 | seed); t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; }; }
  function piece(r, w, h, colors, burst) {
    return {
      x: r() * w, y: burst ? h : r() * h, s: 5 + r() * 8, ratio: .4 + r() * .7,
      vx: burst ? (r() - .5) * 520 : (r() - .5) * 14, vy: burst ? -(380 + r() * 520) : 14 + r() * 26,
      rot: r() * 6.28, vr: (r() - .5) * (burst ? 12 : 2.4), sway: 10 + r() * 22, ph: r() * 6.28,
      shape: r() < .5 ? 0 : r() < .7 ? 1 : 2, c: colors[Math.floor(r() * colors.length)], life: 0
    };
  }
  function drawPiece(ctx, p) {
    ctx.save();
    ctx.translate(p.x, p.y);
    ctx.rotate(p.rot);
    ctx.fillStyle = p.c;
    ctx.strokeStyle = p.c;
    if (p.shape === 0) ctx.fillRect(-p.s / 2, -p.s * p.ratio / 2, p.s, p.s * p.ratio);
    else if (p.shape === 1) { ctx.beginPath(); ctx.arc(0, 0, p.s * .38, 0, 6.283); ctx.fill(); }
    else { ctx.lineWidth = 2; ctx.lineCap = 'round'; ctx.beginPath(); ctx.moveTo(-p.s * .7, 0); ctx.quadraticCurveTo(0, -p.s * .7, p.s * .7, 0); ctx.stroke(); }
    ctx.restore();
  }
  function confetti(canvas) {
    var ctx = canvas.getContext && canvas.getContext('2d');
    if (!ctx) return;
    var colors = palette(), r = rng(7), parts = [], w = 0, h = 0, raf = 0, last = 0, visible = true;
    function size() {
      var b = canvas.getBoundingClientRect(), dpr = Math.min(2, W.devicePixelRatio || 1);
      w = b.width; h = b.height;
      canvas.width = Math.round(w * dpr); canvas.height = Math.round(h * dpr);
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      var n = Math.round(Math.min(70, Math.max(26, w / 18)));
      while (parts.length < n) parts.push(piece(r, w, h, colors));
      parts.length = n;
    }
    function frame(t) {
      var dt = Math.min(.05, (t - (last || t)) / 1000);
      last = t;
      ctx.clearRect(0, 0, w, h);
      parts.forEach(function (p) {
        p.ph += dt * 1.4; p.rot += p.vr * dt;
        p.y += p.vy * dt; p.x += p.vx * dt + Math.sin(p.ph) * p.sway * dt;
        if (p.y > h + 20) { p.y = -20; p.x = r() * w; }
        drawPiece(ctx, p);
      });
      visible = heroOnScreen && !d.hidden;
      raf = visible ? W.requestAnimationFrame(frame) : 0;
    }
    function still() { ctx.clearRect(0, 0, w, h); parts.forEach(function (p) { drawPiece(ctx, p); }); }
    size();
    if (calm) { still(); W.addEventListener('resize', function () { size(); still(); }); return; }
    W.addEventListener('resize', size);
    function wake() { visible = heroOnScreen && !d.hidden; if (visible && !raf) { last = 0; raf = W.requestAnimationFrame(frame); } }
    W.addEventListener('scroll', function () {
      if (raf) return;
      var b = canvas.getBoundingClientRect();
      if (b.bottom > 0 && b.top < W.innerHeight) { heroOnScreen = true; wake(); }
    }, { passive: true });
    d.addEventListener('visibilitychange', wake);
    raf = W.requestAnimationFrame(frame);
  }
  var burstCanvas = null;
  function burst(from) {
    if (calm || !from || !from.getBoundingClientRect) return;
    if (!burstCanvas) { burstCanvas = d.createElement('canvas'); burstCanvas.className = 'burst'; burstCanvas.setAttribute('aria-hidden', 'true'); d.body.appendChild(burstCanvas); }
    var c = burstCanvas, ctx = c.getContext('2d'), dpr = Math.min(2, W.devicePixelRatio || 1);
    var w = W.innerWidth, h = W.innerHeight, b = from.getBoundingClientRect(), r = rng(Date.now() & 0xffff), colors = palette();
    c.width = w * dpr; c.height = h * dpr; ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    var parts = [];
    for (var i = 0; i < 90; i++) { var p = piece(r, w, h, colors, true); p.x = b.left + b.width / 2 + (r() - .5) * b.width * .6; p.y = b.top + b.height / 2; parts.push(p); }
    var last = 0, t0 = 0;
    W.requestAnimationFrame(function step(t) {
      t0 = t0 || t;
      var dt = Math.min(.04, (t - (last || t)) / 1000); last = t;
      ctx.clearRect(0, 0, w, h);
      parts.forEach(function (p) { p.vy += 900 * dt; p.vx *= .985; p.x += p.vx * dt; p.y += p.vy * dt; p.rot += p.vr * dt; drawPiece(ctx, p); });
      if (t - t0 < 2200) W.requestAnimationFrame(step); else ctx.clearRect(0, 0, w, h);
    });
  }

  /* ---------- 8. phone RSVP bar, desktop dots, hero visibility (one cheap scroll handler) ---------- */
  var heroOnScreen = true;
  function chrome() {
    var bar = d.querySelector('[data-rsvp-bar]');
    var hero = d.getElementById('top'), rs = d.getElementById('rsvp'), foot = d.querySelector('.ev-foot');
    var dots = $('.dots a'), targets = [];
    dots.forEach(function (a) { var t = d.getElementById(a.getAttribute('href').slice(1)); if (t) targets.push([t, a]); });
    var ticking = false;
    function inView(el, frac) {
      if (!el) return false;
      var r = el.getBoundingClientRect(), h = W.innerHeight;
      return r.bottom > h * frac && r.top < h * (1 - frac);
    }
    function update() {
      ticking = false;
      heroOnScreen = inView(hero, 0);
      if (bar && !shot) bar.classList.toggle('is-on', !inView(hero, 0.05) && !inView(rs, 0.2) && !inView(foot, 0.1));
      var mid = W.innerHeight / 2, active = null;
      targets.forEach(function (p) { var r = p[0].getBoundingClientRect(); if (r.top <= mid && r.bottom > mid) active = p[1]; });
      if (active && !active.classList.contains('is-active')) {
        dots.forEach(function (a) { a.classList.remove('is-active'); a.removeAttribute('aria-current'); });
        active.classList.add('is-active');
        active.setAttribute('aria-current', 'true');
      }
    }
    function onScroll() { if (!ticking) { ticking = true; setTimeout(update, 80); } }
    W.addEventListener('scroll', onScroll, { passive: true });
    W.addEventListener('resize', onScroll);
    update();
  }
  function init() {
    var ev = null;
    try { ev = readDate(); } catch (e) { ev = null; }
    [function () { countdown(ev); }, function () { dateText(ev); }, function () { calendar(ev); }, maps,
      function () { $('[data-rsvp]').forEach(rsvp); }, function () { $('[data-gallery]').forEach(gallery); },
      function () { $('[data-confetti]').forEach(confetti); }, chrome
    ].forEach(function (fn) { try { fn(); } catch (err) { if (W.console) console.warn('app.js:', err); } });
  }
  if (d.readyState === 'loading') d.addEventListener('DOMContentLoaded', init); else init();
})();
