/* app.js | Service Pro blueprint | small, dependency-free behaviours (ES2019).
   The page works without this file; everything here only adds polish.
   1. Text and directions links   2. Tidy opening hours   3. Smooth accordions
   4. Timeline line that draws on scroll   5. Photo lightbox   6. Copy address */
(function () {
  'use strict';
  var d = document, h = d.documentElement;
  var $$ = function (s, r) { return Array.prototype.slice.call((r || d).querySelectorAll(s)); };
  var shot = h.classList.contains('shot') || /[?&]shot(=|&|$)/.test(location.search);
  var reduce = !!(window.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches);
  var calm = shot || reduce;
  var icon = function (id) { return '<svg class="icon" aria-hidden="true"><use href="#' + id + '"/></svg>'; };

  /* 1. sms: links reuse the phone number; directions links search the address on Google Maps. */
  $$('a[data-sms]').forEach(function (a) {
    var n = (a.getAttribute('href') || '').replace(/^tel:/i, '');
    if (n) a.setAttribute('href', 'sms:' + n);
  });
  $$('a[data-maps]').forEach(function (a) {
    var addr = (a.getAttribute('data-address') || '').trim();
    if (addr && !/\{\{/.test(addr)) a.setAttribute('href', 'https://www.google.com/maps/search/?api=1&query=' + encodeURIComponent(addr));
  });

  /* 1b. A long headline gets a smaller size so it never towers over the page. */
  var h1 = d.querySelector('.hero__title');
  if (h1 && h1.textContent.trim().length > 40) h1.classList.add('is-long');

  /* 2. Opening hours: "Mon-Fri 8:00-18:00, Sat 9:00-15:00" becomes one tidy row per line. */
  var dayOnly = /^(mon|tue|wed|thu|fri|sat|sun)[a-z]*\.?$/i;
  function hoursRows(raw) {
    var segs = raw.split(/\s*(?:[;\n|]|,(?=\s*[A-Za-z]))\s*/).filter(Boolean), out = [], carry = '';
    segs.forEach(function (s, i) {
      if (dayOnly.test(s) && i < segs.length - 1) { carry += s + ', '; return; }
      out.push(carry + s); carry = '';
    });
    return out.map(function (s) {
      var m = s.match(/^([^0-9]*?)[\s:]*(\d.*)$/);
      if (m) return [m[1].replace(/\s*-\s*/g, '–'), m[2].replace(/\s*-\s*/g, '–')];
      var c = s.match(/^(.*?)\s+(closed)$/i);
      return c ? [c[1].replace(/\s*-\s*/g, '–'), 'Closed'] : [s, ''];
    });
  }
  $$('[data-hours]').forEach(function (el) {
    var raw = el.textContent.trim();
    if (!raw || /\{\{/.test(raw)) return;
    var ul = d.createElement('ul');
    ul.className = 'hours-list';
    hoursRows(raw).forEach(function (r) {
      var li = d.createElement('li'), a = d.createElement('span');
      a.textContent = r[0] || r[1];
      li.appendChild(a);
      if (r[0] && r[1]) { var b = d.createElement('span'); b.textContent = r[1]; li.appendChild(b); }
      ul.appendChild(li);
    });
    el.textContent = '';
    el.appendChild(ul);
  });

  /* 3. Accordions: native <details>, with a smooth height change on top. */
  $$('details').forEach(function (det) {
    var sum = det.querySelector('summary'), body = det.querySelector('.acc__body');
    if (!sum || !body) return;
    var timer;
    function finish() { clearTimeout(timer); det.classList.remove('is-closing'); det.open = false; }
    sum.addEventListener('click', function (e) {
      if (calm) return;
      e.preventDefault();
      if (det.open && !det.classList.contains('is-closing')) {
        det.classList.add('is-closing');
        timer = setTimeout(finish, 520);
      } else {
        clearTimeout(timer);
        det.classList.remove('is-closing');
        det.classList.add('is-opening');
        det.open = true;
        requestAnimationFrame(function () { requestAnimationFrame(function () { det.classList.remove('is-opening'); }); });
      }
    });
  });

  /* 4. "How it works": the rail fills as the visitor scrolls, and each step lights up as it is reached. */
  var steps = d.querySelector('[data-steps]');
  if (steps) {
    var items = $$('.step', steps), lineH = 0, ticking = 0;
    var measure = function () {
      var last = items[items.length - 1], first = items[0];
      if (!last || !first) return;
      lineH = Math.max(0, last.offsetTop - first.offsetTop);
      steps.style.setProperty('--line', lineH + 'px');
      steps.classList.add('is-ready');
    };
    var paint = function (p) {
      steps.style.setProperty('--p', p.toFixed(3));
      items.forEach(function (li, i) {
        li.classList.toggle('is-reached', i === 0 ? p > 0 || calm : p * lineH >= li.offsetTop - items[0].offsetTop - 4);
      });
    };
    var update = function () {
      ticking = 0;
      var r = steps.getBoundingClientRect(), start = innerHeight * .62;
      paint(Math.max(0, Math.min(1, (start - r.top - 20) / Math.max(1, lineH))));
    };
    measure();
    if (calm) paint(1);
    else {
      update();
      addEventListener('scroll', function () { if (!ticking) ticking = requestAnimationFrame(update); }, { passive: true });
      addEventListener('resize', function () { measure(); update(); });
      addEventListener('load', function () { measure(); update(); });
    }
    if (calm) addEventListener('load', measure);
  }

  /* 5. Lightbox for the photo strip: arrows, swipe, Esc, focus goes back to the photo. */
  var pics = $$('[data-gallery] img');
  if (pics.length) {
    var dlg, big, txt, num, at = 0, opener = null;
    var show = function (i) {
      at = (i + pics.length) % pics.length;
      var p = pics[at];
      big.src = p.currentSrc || p.src;
      big.alt = p.alt || '';
      txt.textContent = p.alt || '';
      num.textContent = (at + 1) + ' / ' + pics.length;
      big.style.animation = 'none'; void big.offsetWidth; big.style.animation = '';
    };
    var onClose = function () {
      h.classList.remove('sp-lock');
      if (opener && d.activeElement !== opener) opener.focus();
    };
    var close = function () {
      if (dlg.open) { if (dlg.close) dlg.close(); else dlg.removeAttribute('open'); }
      onClose();
    };
    var build = function () {
      dlg = d.createElement('dialog');
      dlg.className = 'lightbox';
      dlg.setAttribute('aria-label', 'Photo viewer');
      dlg.innerHTML = '<figure class="lightbox__figure"><img class="lightbox__img" src="' + (pics[0].currentSrc || pics[0].src) + '" alt=""><figcaption class="lightbox__cap"><span class="lightbox__count"></span><span class="lightbox__text"></span></figcaption></figure>' +
        '<button class="lightbox__btn lightbox__close" type="button" aria-label="Close photo">' + icon('i-close') + '</button>' +
        (pics.length > 1 ? '<button class="lightbox__btn lightbox__prev" type="button" aria-label="Previous photo">' + icon('i-left') + '</button>' +
        '<button class="lightbox__btn lightbox__next" type="button" aria-label="Next photo">' + icon('i-right') + '</button>' : '');
      d.body.appendChild(dlg);
      big = dlg.querySelector('.lightbox__img');
      txt = dlg.querySelector('.lightbox__text');
      num = dlg.querySelector('.lightbox__count');
      dlg.querySelector('.lightbox__close').addEventListener('click', close);
      if (pics.length > 1) {
        dlg.querySelector('.lightbox__prev').addEventListener('click', function () { show(at - 1); });
        dlg.querySelector('.lightbox__next').addEventListener('click', function () { show(at + 1); });
      }
      dlg.addEventListener('close', onClose);
      dlg.addEventListener('cancel', onClose);
      dlg.addEventListener('click', function (e) { if (e.target === dlg || e.target.classList.contains('lightbox__figure')) close(); });
      dlg.addEventListener('keydown', function (e) {
        if (e.key === 'ArrowRight') show(at + 1);
        else if (e.key === 'ArrowLeft') show(at - 1);
      });
      var x0 = null;
      dlg.addEventListener('pointerdown', function (e) { x0 = e.clientX; });
      dlg.addEventListener('pointerup', function (e) {
        if (x0 === null) return;
        var dx = e.clientX - x0; x0 = null;
        if (Math.abs(dx) > 48) show(at + (dx < 0 ? 1 : -1));
      });
    };
    pics.forEach(function (p, i) {
      var btn = p.closest('button');
      if (!btn) return;
      btn.addEventListener('click', function () {
        if (!dlg) build();
        opener = btn;
        show(i);
        h.classList.add('sp-lock');
        if (dlg.showModal) dlg.showModal(); else dlg.setAttribute('open', '');
        dlg.querySelector('.lightbox__close').focus();
      });
    });
  }

  /* 5b. Swipe progress under the photo strip on phones. */
  var strip = d.querySelector('[data-gallery]'), bar = d.querySelector('.work__progress');
  if (strip && bar) {
    var sx = function () {
      var max = strip.scrollWidth - strip.clientWidth, vis = strip.clientWidth / Math.max(1, strip.scrollWidth);
      bar.style.setProperty('--sx', max > 0 ? (vis + (1 - vis) * (strip.scrollLeft / max)).toFixed(3) : 1);
    };
    strip.addEventListener('scroll', sx, { passive: true });
    addEventListener('resize', sx);
    addEventListener('load', sx);
    sx();
  }

  /* 6. Copy the address to the clipboard, with a short "Copied" confirmation. */
  $$('[data-copy]').forEach(function (b) {
    var label = b.querySelector('.btn__label') || b, orig = label.textContent;
    var live = d.createElement('span');
    live.className = 'sr-only';
    live.setAttribute('aria-live', 'polite');
    b.appendChild(live);
    var done = function () {
      label.textContent = 'Copied';
      live.textContent = 'Address copied';
      setTimeout(function () { label.textContent = orig; live.textContent = ''; }, 1800);
    };
    var fallback = function (t) {
      var ta = d.createElement('textarea');
      ta.value = t; ta.setAttribute('readonly', ''); ta.style.position = 'fixed'; ta.style.opacity = '0';
      d.body.appendChild(ta); ta.select();
      try { if (d.execCommand('copy')) done(); } catch (e) { /* nothing to do */ }
      d.body.removeChild(ta);
    };
    b.addEventListener('click', function () {
      var t = b.getAttribute('data-copy') || '';
      if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(t).then(done, function () { fallback(t); });
      else fallback(t);
    });
  });
})();
