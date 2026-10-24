/* app.js | Restaurant & Cafe blueprint | small, dependency-free behaviours (ES2019).
   The page works without this file; everything here only adds polish.
   1. Text and directions links   2. Opening hours rows + today   3. Menu tabs
   4. Gallery lightbox   5. Floating call button on phones */
(function () {
  'use strict';
  var d = document, h = d.documentElement;
  var $$ = function (s, r) { return Array.prototype.slice.call((r || d).querySelectorAll(s)); };
  var shot = h.classList.contains('shot') || /[?&]shot(=|&|$)/.test(location.search);
  var reduce = !!(window.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches);
  var calm = shot || reduce;
  var icon = function (id) { return '<svg class="icon" aria-hidden="true"><use href="#' + id + '"/></svg>'; };
  var token = /\{\{/;

  /* 1. sms: links reuse the phone number; directions links search the address on Google Maps. */
  $$('a[data-sms]').forEach(function (a) {
    var n = (a.getAttribute('href') || '').replace(/^tel:/i, '');
    if (n) a.setAttribute('href', 'sms:' + n);
  });
  $$('a[data-maps]').forEach(function (a) {
    var addr = (a.getAttribute('data-address') || '').trim();
    if (addr && !token.test(addr)) a.setAttribute('href', 'https://www.google.com/maps/search/?api=1&query=' + encodeURIComponent(addr));
  });

  /* 1b. A long business name gets a smaller headline size. */
  var h1 = d.querySelector('.hero__title');
  if (h1 && h1.textContent.trim().length > 22) h1.classList.add('is-long');

  /* 2. Opening hours: "Mon-Fri 8:00-18:00, Sat 9:00-15:00" becomes one row per line, and today's row is marked. */
  var DAYS = ['sun', 'mon', 'tue', 'wed', 'thu', 'fri', 'sat'];
  var NAMES = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
  var today = new Date().getDay();
  var dayOnly = /^(mon|tue|wed|thu|fri|sat|sun)[a-z]*\.?$/i;
  function dayIndex(word) { return DAYS.indexOf(String(word).slice(0, 3).toLowerCase()); }
  function coversToday(label) {
    var l = label.toLowerCase();
    if (/daily|every ?day|7 days/.test(l)) return true;
    var parts = l.split(/\s*[,&]\s*|\s+and\s+/);
    return parts.some(function (p) {
      var r = p.match(/([a-z]{3})[a-z]*\.?\s*(?:-|–|to)\s*([a-z]{3})/);
      if (r) {
        var a = dayIndex(r[1]), b = dayIndex(r[2]);
        if (a < 0 || b < 0) return false;
        return a <= b ? today >= a && today <= b : today >= a || today <= b;
      }
      var s = p.match(/^([a-z]{3})/);
      return !!s && dayIndex(s[1]) === today;
    });
  }
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
    if (!raw || token.test(raw)) return;
    var ul = d.createElement('ul');
    ul.className = 'hours-list';
    hoursRows(raw).forEach(function (r) {
      var li = d.createElement('li'), a = d.createElement('span');
      a.textContent = r[0] || r[1];
      li.appendChild(a);
      if (r[0] && r[1]) { var b = d.createElement('span'); b.textContent = r[1]; li.appendChild(b); }
      if (r[0] && coversToday(r[0])) li.className = 'is-today';
      ul.appendChild(li);
    });
    el.textContent = '';
    el.appendChild(ul);
  });
  $$('[data-today]').forEach(function (el) { el.textContent = NAMES[today]; });

  /* 3. Menu tabs: arrow keys, Home and End move between tabs (WAI-ARIA tabs pattern). */
  var tablist = d.querySelector('.menu__tabs');
  if (tablist) {
    var tabs = $$('[role="tab"]', tablist);
    var panels = tabs.map(function (t) { return d.getElementById(t.getAttribute('aria-controls')); });
    var ink = tablist.querySelector('.menu__ink');
    if (tabs.length && panels.every(Boolean)) {
      tablist.hidden = false;
      d.querySelector('.menu').classList.add('js-tabs');
      var current = 0;
      var moveInk = function () {
        var t = tabs[current];
        if (!ink || !t) return;
        ink.style.transform = 'translateX(' + t.offsetLeft + 'px) scaleX(' + (t.offsetWidth / 100).toFixed(4) + ')';
      };
      var select = function (i, focus) {
        current = (i + tabs.length) % tabs.length;
        tabs.forEach(function (t, j) {
          var on = j === current;
          t.setAttribute('aria-selected', on ? 'true' : 'false');
          t.tabIndex = on ? 0 : -1;
          panels[j].hidden = !on;
        });
        var p = panels[current];
        if (!calm) {
          $$('.dish', p).forEach(function (li, k) { li.style.setProperty('--i', k); });
          p.classList.remove('is-entering'); void p.offsetWidth; p.classList.add('is-entering');
        }
        if (focus) tabs[current].focus();
        var t = tabs[current];
        if (tablist.scrollWidth > tablist.clientWidth) tablist.scrollTo({ left: t.offsetLeft - (tablist.clientWidth - t.offsetWidth) / 2, behavior: calm ? 'auto' : 'smooth' });
        moveInk();
      };
      tabs.forEach(function (t, i) {
        t.addEventListener('click', function () { select(i, false); });
        t.addEventListener('keydown', function (e) {
          var k = e.key, n = null;
          if (k === 'ArrowRight' || k === 'ArrowDown') n = current + 1;
          else if (k === 'ArrowLeft' || k === 'ArrowUp') n = current - 1;
          else if (k === 'Home') n = 0;
          else if (k === 'End') n = tabs.length - 1;
          if (n !== null) { e.preventDefault(); select(n, true); }
        });
      });
      select(0, false);
      panels[0].classList.remove('is-entering');
      addEventListener('resize', moveInk);
      addEventListener('load', moveInk);
      if (d.fonts && d.fonts.ready) d.fonts.ready.then(moveInk);
    }
  }

  /* 4. Lightbox for the gallery: arrows, swipe, Esc, focus goes back to the photo. */
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
      h.classList.remove('rc-lock');
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
        h.classList.add('rc-lock');
        if (dlg.showModal) dlg.showModal(); else dlg.setAttribute('open', '');
        dlg.querySelector('.lightbox__close').focus();
      });
    });
  }

  /* 5. Floating call button: appears on phones once the hero has scrolled away. */
  var fab = d.querySelector('.fab'), hero = d.querySelector('.hero');
  if (fab && hero && 'IntersectionObserver' in window && !shot) {
    new IntersectionObserver(function (es) {
      var away = !es[0].isIntersecting;
      fab.classList.toggle('is-shown', away);
      fab.tabIndex = away ? 0 : -1;
    }, { threshold: 0 }).observe(hero);
  }
})();
