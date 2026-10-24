/* portfolio-studio | app.js
   Small, dependency-free extras. The page works without this file.
   1. big type that fits its box   2. copy the email   3. index picture that follows the pointer
   4. lightbox for the work grid   5. a quiet custom cursor (desktop mouse only) */
(function () {
  'use strict';
  var d = document, W = window;
  var $ = function (s, r) { return (r || d).querySelector(s); };
  var $$ = function (s, r) { return [].slice.call((r || d).querySelectorAll(s)); };
  var mq = function (q) { return !!W.matchMedia && W.matchMedia(q).matches; };
  var shot = /[?&]shot(=|&|$)/.test(location.search);
  var calm = shot || mq('(prefers-reduced-motion: reduce)');
  var fine = mq('(hover: hover) and (pointer: fine)');
  var pad = function (n) { return (n < 10 ? '0' : '') + n; };

  /* 1. Email: allow a line break before the @ so a long address can wrap on a phone. */
  $$('[data-email]').forEach(function (a) {
    var t = a.textContent.trim(), i = t.indexOf('@');
    if (i < 1) return;
    a.textContent = '';
    a.appendChild(d.createTextNode(t.slice(0, i)));
    a.appendChild(d.createElement('wbr'));
    a.appendChild(d.createTextNode(t.slice(i)));
  });

  /* Big type: if one word is wider than its box (a long name or address), shrink the font until it fits.
     data-fit="line" on a wide screen: scale the text so it fills exactly one line (between 72px and 240px). */
  var fits = $$('[data-fit]');
  var wide = W.matchMedia ? W.matchMedia('(min-width: 900px)') : { matches: false };
  function shrink(el) {
    for (var i = 0; i < 6 && el.scrollWidth > el.clientWidth + 1; i++) {
      var fs = parseFloat(W.getComputedStyle(el).fontSize);
      el.style.fontSize = (fs * (el.clientWidth / el.scrollWidth) * 0.98).toFixed(2) + 'px';
    }
  }
  function fillLine(el) {
    el.classList.add('is-line');
    var r = d.createRange(); r.selectNodeContents(el);
    var w = r.getBoundingClientRect().width, box = el.parentElement.clientWidth;
    var min = +el.getAttribute('data-fit-min') || 72, max = +el.getAttribute('data-fit-max') || 240;
    if (!w || !box) { el.classList.remove('is-line'); return false; }
    var fs = parseFloat(W.getComputedStyle(el).fontSize), want = fs * box / w * 0.97;
    if (want < min) { el.classList.remove('is-line'); return false; }
    el.style.fontSize = Math.min(want, max).toFixed(2) + 'px';
    return true;
  }
  function fit() {
    fits.forEach(function (el) {
      el.style.fontSize = '';
      el.classList.remove('is-line');
      if (el.getAttribute('data-fit') === 'line' && wide.matches && fillLine(el)) return;
      shrink(el);
    });
  }
  fit();
  if (d.fonts && d.fonts.ready) d.fonts.ready.then(fit);
  W.addEventListener('load', fit);
  var rt;
  W.addEventListener('resize', function () { clearTimeout(rt); rt = setTimeout(fit, 120); });

  /* 2. Copy the email address. */
  var toast = $('.toast'), tt;
  function say(msg) {
    if (!toast) return;
    toast.textContent = msg;
    toast.classList.add('is-on');
    clearTimeout(tt);
    tt = setTimeout(function () { toast.classList.remove('is-on'); }, 2600);
  }
  function copyText(text) {
    if (navigator.clipboard && W.isSecureContext) return navigator.clipboard.writeText(text);
    return new Promise(function (ok, no) {
      var ta = d.createElement('textarea');
      ta.value = text; ta.setAttribute('readonly', '');
      ta.style.cssText = 'position:fixed;top:0;left:0;opacity:0';
      d.body.appendChild(ta); ta.select();
      try { if (d.execCommand('copy')) ok(); else no(); } catch (e) { no(e); }
      d.body.removeChild(ta);
    });
  }
  $$('[data-copy-from]').forEach(function (b) {
    var label = $('.copy-btn__label', b), orig = label ? label.textContent : '';
    b.addEventListener('click', function () {
      var src = $(b.getAttribute('data-copy-from'));
      if (!src) return;
      copyText(src.textContent.replace(/\s+/g, '')).then(function () {
        b.classList.add('is-done');
        if (label) label.textContent = 'Copied';
        say('Email address copied');
        setTimeout(function () { b.classList.remove('is-done'); if (label) label.textContent = orig; }, 2400);
      }, function () { say('Press and hold the address to copy it'); });
    });
  });

  /* 3. Index: on a desktop with a mouse, the hovered row's picture follows the pointer (CSS does the showing). */
  var list = $('.index__list');
  var floatMQ = W.matchMedia ? W.matchMedia('(hover: hover) and (pointer: fine) and (min-width: 900px)') : null;
  if (list && floatMQ) {
    var tx = 0, ty = 0, cx = 0, cy = 0, rot = 0, raf = 0, inside = false;
    var step = function () {
      var k = calm ? 1 : 0.16, dx = tx - cx;
      cx += dx * k; cy += (ty - cy) * k;
      rot += ((calm ? 0 : Math.max(-8, Math.min(8, dx * 0.1))) - rot) * 0.14;
      list.style.setProperty('--px', cx.toFixed(1) + 'px');
      list.style.setProperty('--py', cy.toFixed(1) + 'px');
      list.style.setProperty('--pr', rot.toFixed(2) + 'deg');
      raf = (Math.abs(tx - cx) > 0.3 || Math.abs(ty - cy) > 0.3 || Math.abs(rot) > 0.05) ? W.requestAnimationFrame(step) : 0;
    };
    var kick = function () { if (!raf) raf = W.requestAnimationFrame(step); };
    var thumb = $('.index__thumb', list);
    list.addEventListener('pointermove', function (e) {
      if (!floatMQ.matches) return;
      var r = list.getBoundingClientRect(), half = (thumb ? thumb.offsetWidth : 280) / 2 + 12;
      // keep the whole picture inside the list so it never makes the page scroll sideways
      tx = Math.max(half, Math.min(r.width - half, e.clientX - r.left)); ty = e.clientY - r.top;
      if (!inside) { cx = tx; cy = ty; inside = true; }
      kick();
    });
    list.addEventListener('pointerleave', function () { inside = false; });
    list.addEventListener('focusin', function (e) {
      var row = e.target.closest && e.target.closest('.index__row');
      if (!row || !floatMQ.matches) return;
      var r = list.getBoundingClientRect(), rr = row.getBoundingClientRect();
      tx = cx = rr.left - r.left + rr.width * 0.7;
      ty = cy = rr.top - r.top + rr.height / 2;
      kick();
    });
  }

  /* 4. Lightbox for the work grid (arrow keys, swipe, Esc). */
  var items = $$('.work__item');
  var icon = function (p) { return '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="' + p + '"/></svg>'; };
  items.forEach(function (it) {
    var b = $('.work__open', it), n = $('.work__name', it);
    if (b && n) b.setAttribute('aria-label', 'View larger: ' + n.textContent.trim());
  });
  if (items.length && W.HTMLDialogElement) {
    var dlg = d.createElement('dialog');
    dlg.className = 'lightbox';
    dlg.setAttribute('aria-label', 'Picture viewer');
    dlg.innerHTML =
      '<div class="lightbox__bar"><p class="lightbox__count"></p>' +
      '<button class="lb-btn" type="button" data-lb="close" aria-label="Close">' + icon('M6 6l12 12M18 6 6 18') + '</button></div>' +
      '<div class="lightbox__stage"></div>' +
      '<div class="lightbox__foot"><p class="lightbox__caption"></p><div class="lightbox__nav">' +
      '<button class="lb-btn" type="button" data-lb="prev" aria-label="Previous picture">' + icon('M15 6l-6 6 6 6') + '</button>' +
      '<button class="lb-btn" type="button" data-lb="next" aria-label="Next picture">' + icon('M9 6l6 6-6 6') + '</button></div></div>';
    d.body.appendChild(dlg);
    var stage = $('.lightbox__stage', dlg), cap = $('.lightbox__caption', dlg), cnt = $('.lightbox__count', dlg), at = 0, opener = null;
    var show = function (i) {
      at = (i + items.length) % items.length;
      var src = $('img', items[at]), name = $('.work__name', items[at]);
      if (!src) return;
      var img = d.createElement('img');
      img.src = src.currentSrc || src.src;
      img.alt = src.alt;
      img.width = src.naturalWidth || +src.getAttribute('width') || 1200;
      img.height = src.naturalHeight || +src.getAttribute('height') || 1200;
      stage.innerHTML = '';
      stage.appendChild(img);
      cap.textContent = name ? name.textContent.trim() : '';
      cnt.textContent = pad(at + 1) + ' / ' + pad(items.length);
    };
    items.forEach(function (it, i) {
      var b = $('.work__open', it);
      if (b) b.addEventListener('click', function () { opener = b; show(i); dlg.showModal(); });
    });
    dlg.addEventListener('click', function (e) {
      var b = e.target.closest && e.target.closest('[data-lb]');
      if (b) {
        var a = b.getAttribute('data-lb');
        if (a === 'close') dlg.close(); else show(at + (a === 'next' ? 1 : -1));
      } else if (e.target === stage || e.target === dlg) dlg.close();
    });
    dlg.addEventListener('keydown', function (e) {
      if (e.key === 'ArrowRight') show(at + 1);
      else if (e.key === 'ArrowLeft') show(at - 1);
    });
    dlg.addEventListener('close', function () { stage.innerHTML = ''; d.documentElement.classList.remove('lb-open'); if (opener) opener.focus(); });
    dlg.addEventListener('cancel', function () { d.documentElement.classList.remove('lb-open'); });
    items.forEach(function (it) { var b = $('.work__open', it); if (b) b.addEventListener('click', function () { d.documentElement.classList.add('lb-open'); }); });
    var sx = null;
    stage.addEventListener('touchstart', function (e) { sx = e.touches[0].clientX; }, { passive: true });
    stage.addEventListener('touchend', function (e) {
      if (sx === null) return;
      var dx = e.changedTouches[0].clientX - sx;
      if (Math.abs(dx) > 50) show(at + (dx < 0 ? 1 : -1));
      sx = null;
    });
  }

  /* 5. Custom cursor: a ring that trails the mouse, grows on links and says "View" on pictures.
        The normal pointer stays visible. Off on touch screens, in screenshots and for reduced motion. */
  if (fine && !calm) {
    var cur = d.createElement('div');
    cur.className = 'cursor';
    cur.setAttribute('aria-hidden', 'true');
    cur.innerHTML = '<span class="cursor__ring"><span class="cursor__label">View</span></span>';
    d.body.appendChild(cur);
    var mx = 0, my = 0, ox = 0, oy = 0, on = false, craf = 0;
    var cstep = function () {
      ox += (mx - ox) * 0.2; oy += (my - oy) * 0.2;
      cur.style.transform = 'translate3d(' + ox.toFixed(1) + 'px,' + oy.toFixed(1) + 'px,0)';
      craf = (Math.abs(mx - ox) + Math.abs(my - oy) > 0.2) ? W.requestAnimationFrame(cstep) : 0;
    };
    d.addEventListener('pointermove', function (e) {
      if (e.pointerType && e.pointerType !== 'mouse') return;
      mx = e.clientX; my = e.clientY;
      if (!on) { ox = mx; oy = my; on = true; cur.classList.add('is-on'); }
      var t = e.target.closest ? e.target : null;
      var view = !!(t && t.closest('[data-cursor="view"]'));
      cur.classList.toggle('is-view', view);
      cur.classList.toggle('is-link', !view && !!(t && t.closest('a,button')));
      cur.classList.toggle('is-invert', !!(t && t.closest('.contact,.site-footer')));
      if (!craf) craf = W.requestAnimationFrame(cstep);
    }, { passive: true });
    d.documentElement.addEventListener('mouseleave', function () { cur.classList.remove('is-on'); on = false; });
    d.addEventListener('pointerdown', function () { cur.classList.add('is-down'); });
    d.addEventListener('pointerup', function () { cur.classList.remove('is-down'); });
  }
})();
