/* product-launch | app.js
   Small, optional extras. The page reads and works without this file.
   1 headline word-by-word reveal   2 bento spotlight   3 "how it works" steps
   4 FAQ accordion                   5 pause hero loops when the hero is off screen */
(function () {
  'use strict';
  var d = document, h = d.documentElement;
  h.classList.add('pl-js');
  var mq = function (q) { return !!(window.matchMedia && window.matchMedia(q).matches); };
  var reduce = mq('(prefers-reduced-motion: reduce)');
  var shot = /[?&]shot(=|&|$)/.test(location.search);
  var fine = mq('(hover: hover) and (pointer: fine)');
  var hasIO = 'IntersectionObserver' in window;
  function all(s, r) { return [].slice.call((r || d).querySelectorAll(s)); }

  /* 1. Headline: wrap each word in a span so CSS can bring them in one by one. */
  var title = d.querySelector('[data-words]');
  if (title && !reduce && !shot) {
    title.setAttribute('aria-label', title.textContent.replace(/\s+/g, ' ').trim());
    var parts = [].slice.call(title.childNodes), n = 0;
    var word = function (child) {
      var s = d.createElement('span');
      s.className = 'w';
      s.setAttribute('aria-hidden', 'true');
      s.style.setProperty('--i', n++);
      s.appendChild(child);
      title.appendChild(s);
    };
    title.textContent = '';
    parts.forEach(function (node) {
      if (node.nodeType === 3) {
        node.nodeValue.split(/(\s+)/).forEach(function (bit) {
          if (!bit) return;
          if (/^\s+$/.test(bit)) title.appendChild(d.createTextNode(' '));
          else word(d.createTextNode(bit));
        });
      } else if (node.nodeType === 1) {
        word(node);
      }
    });
  }

  /* 2. Spotlight: every tile lights up where the pointer is (mouse only). */
  var bento = d.querySelector('[data-spotlight]');
  if (bento && fine && !reduce) {
    var tiles = all('.tile', bento), px = 0, py = 0, raf = 0;
    bento.addEventListener('pointermove', function (e) {
      px = e.clientX; py = e.clientY;
      if (raf) return;
      raf = requestAnimationFrame(function () {
        raf = 0;
        tiles.forEach(function (t) {
          var r = t.getBoundingClientRect();
          t.style.setProperty('--mx', (px - r.left).toFixed(0) + 'px');
          t.style.setProperty('--my', (py - r.top).toFixed(0) + 'px');
        });
      });
    }, { passive: true });
  }

  /* 3. How it works: the step in the middle of the screen lights up and the phone shows its screen. */
  var steps = all('.how-step'), screens = all('.how-screen'), list = d.querySelector('.how__steps');
  function showStep(id) {
    steps.forEach(function (s) { s.classList.toggle('is-active', s.getAttribute('data-step') === id); });
    screens.forEach(function (s) { s.classList.toggle('is-active', s.getAttribute('data-screen') === id); });
    if (list && steps.length > 1) list.style.setProperty('--p', String(Math.max(0, +id) / (steps.length - 1)));
  }
  steps.forEach(function (s) { s.addEventListener('click', function () { showStep(s.getAttribute('data-step')); }); });
  if (steps.length && hasIO && !shot) {
    var stepIO = new IntersectionObserver(function (entries) {
      entries.forEach(function (e) { if (e.isIntersecting) showStep(e.target.getAttribute('data-step')); });
    }, { rootMargin: '-55% 0px -35% 0px' });
    steps.forEach(function (s) { stepIO.observe(s); });
  }

  /* 4. FAQ accordion (without JavaScript every answer is simply open). */
  all('.faq__q button').forEach(function (btn) {
    btn.addEventListener('click', function () {
      var item = btn.closest('.faq__item');
      var open = !item.classList.contains('is-open');
      item.classList.toggle('is-open', open);
      btn.setAttribute('aria-expanded', open ? 'true' : 'false');
    });
  });

  /* 5. Save battery: the aurora and the phone loop rest while the hero is off screen. */
  var hero = d.querySelector('.hero');
  if (hero && hasIO) {
    new IntersectionObserver(function (entries) {
      hero.classList.toggle('is-paused', !entries[0].isIntersecting);
    }).observe(hero);
  }
})();
