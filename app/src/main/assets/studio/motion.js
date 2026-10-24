/*! motion.js 1.0 | Alpha Studio motion kit | own work, CC0 | no dependencies | pairs with motion.css */
(function (W, d) {
  'use strict';
  if (W.Motion) return;
  var h = d.documentElement,
    $ = function (s, r) { return [].slice.call((r || d).querySelectorAll(s)); },
    mq = function (s) { return !!W.matchMedia && W.matchMedia(s).matches; },
    shot = /[?&]shot(=|&|$)/.test(location.search),
    calm = shot || mq('(prefers-reduced-motion: reduce)'),
    fine = mq('(hover: hover) and (pointer: fine)'),
    sd = !!(W.CSS && CSS.supports && CSS.supports('animation-timeline', 'scroll()')),
    io, par = [], sticky = [], bar, tick = 0;

  if (shot) h.classList.add('shot');
  h.classList.add('m-js');

  function fmt(e, v) {
    var D = e.dataset, k = D.countDecimals != null ? +D.countDecimals : (D.countTo.split('.')[1] || '').length;
    return (D.countPrefix || '') + v.toLocaleString(void 0, { minimumFractionDigits: k, maximumFractionDigits: k }) + (D.countSuffix || '');
  }

  function count(e, delay) {
    var D = e.dataset, to = +D.countTo, from = +D.countFrom || 0, dur = +D.countDuration || 1600;
    if (calm) { e.textContent = fmt(e, to); return; }
    setTimeout(function () {
      var t0;
      W.requestAnimationFrame(function f(t) {
        if (h.classList.contains('shot')) { e.textContent = fmt(e, to); return; }
        t0 = t0 || t;
        var p = Math.min((t - t0) / dur, 1);
        e.textContent = fmt(e, from + (to - from) * (1 - Math.pow(1 - p, 3)));
        if (p < 1) W.requestAnimationFrame(f);
      });
    }, delay);
  }

  function show(t, k, base) {
    var D = t.dataset, p = t._p, dl = (+D.delay || 0) + (base || 0) + (p ? k * (+p.dataset.stagger || 80) : 0);
    t.style.setProperty('--d', dl + 'ms');
    if (D.duration) t.style.setProperty('--dur', D.duration + 'ms');
    t.classList.add('is-in');
    if (D.countTo != null) count(t, dl);
    if (io) io.unobserve(t);
    $('[data-count-to]:not(.is-in)', t).forEach(function (c) { show(c, 0, dl); });
  }

  function sweep(all) {
    $('[data-reveal]:not([data-stagger]):not(.is-in),[data-count-to]:not(.is-in)').forEach(function (e) {
      if (all || e.getBoundingClientRect().top < W.innerHeight) show(e, 0);
    });
  }

  function scan(r) {
    $('[data-stagger]', r).forEach(function (p) {
      [].forEach.call(p.children, function (c) {
        c._p = p;
        if (!c.hasAttribute('data-reveal')) c.setAttribute('data-reveal', p.getAttribute('data-reveal') || 'up');
      });
    });
    $('[data-reveal]:not([data-stagger]),[data-count-to]', r).forEach(function (e) {
      if (e._m) return;
      e._m = 1;
      if (e.hasAttribute('data-count-to')) {
        var w = e.offsetWidth;
        if (w) e.style.setProperty('--cw', w + 'px');
        if (!calm) e.textContent = fmt(e, +e.dataset.countFrom || 0);
      }
      if (io && !calm) io.observe(e); else show(e, 0);
    });
  }

  function marq(m) {
    var t = m.querySelector('.marquee__track') || m;
    $('[data-c]', t).forEach(function (c) { t.removeChild(c); });
    var o = [].slice.call(t.children);
    if (!o.length) return;
    var last = o[o.length - 1],
      w = last.offsetLeft + last.offsetWidth - o[0].offsetLeft + (parseFloat(W.getComputedStyle(t).columnGap) || 0);
    if (!w) return;
    for (var n = Math.ceil(m.offsetWidth / w) || 1; n--;) {
      o.forEach(function (c) {
        var k = c.cloneNode(true);
        k.setAttribute('aria-hidden', 'true');
        k.setAttribute('data-c', '');
        t.appendChild(k);
      });
    }
    t.style.setProperty('--mw', w + 'px');
    t.style.setProperty('--md', w / (+m.dataset.speed || 50) + 's');
  }

  function plx() {
    par.forEach(function (e) {
      var r = e.getBoundingClientRect(), H = W.innerHeight, y = e._py || 0;
      if (r.bottom < -200 || r.top > H + 200) return;
      y = ((H - (r.top - y)) / (H + r.height) * 2 - 1) * (+e.dataset.parallax || .15) * 100;
      e._py = y;
      e.style.transform = 'translate3d(0,' + y + 'px,0)';
    });
  }

  function onScroll() {
    if (tick) return;
    tick = W.requestAnimationFrame(function () {
      tick = 0;
      var y = W.pageYOffset;
      if (!shot) sticky.forEach(function (s) { s.classList.toggle('is-stuck', y > 24); });
      if (bar && !sd) bar.style.setProperty('--p', y / Math.max(1, h.scrollHeight - W.innerHeight));
      if (par.length && !sd && !calm) plx();
      if (W.innerHeight + y >= h.scrollHeight - 4) sweep();
    });
  }

  function layout() {
    var s = sticky[0];
    h.style.setProperty('--m-offset', s ? s.offsetHeight + 'px' : '0px');
    if (!calm) $('.marquee').forEach(marq);
  }

  function init() {
    try {
      if (W.IntersectionObserver) {
        io = new IntersectionObserver(function (es) {
          var c = new Map();
          es.forEach(function (e) {
            if (!e.isIntersecting) return;
            var p = e.target._p, k = 0;
            if (p) { k = c.get(p) || 0; c.set(p, k + 1); }
            show(e.target, k);
          });
        }, { rootMargin: '0px 0px -10% 0px' });
      }
      scan();
      sticky = $('[data-sticky]');
      par = $('[data-parallax]');

      if (d.body.hasAttribute('data-progress') && !$('.m-progress')[0]) {
        bar = d.createElement('div');
        bar.className = 'm-progress';
        bar.setAttribute('aria-hidden', 'true');
        d.body.appendChild(bar);
      }
      bar = bar || $('.m-progress')[0];

      if (!calm) {
        par.forEach(function (e) { e.style.setProperty('--ps', +e.dataset.parallax || .15); });
        if (W.IntersectionObserver) {
          var mo = new IntersectionObserver(function (es) {
            es.forEach(function (e) { e.target.classList.toggle('m-off', !e.isIntersecting); });
          });
          $('.marquee').forEach(function (m) { mo.observe(m); });
        }
        if (fine) {
          $('[data-tilt]').forEach(function (e) {
            var m = +e.dataset.tilt || 8;
            e.addEventListener('pointermove', function (v) {
              var r = e.getBoundingClientRect();
              e.style.setProperty('--rx', -((v.clientY - r.top) / r.height - .5) * 2 * m + 'deg');
              e.style.setProperty('--ry', ((v.clientX - r.left) / r.width - .5) * 2 * m + 'deg');
            });
            e.addEventListener('pointerleave', function () {
              e.style.removeProperty('--rx');
              e.style.removeProperty('--ry');
            });
          });
          var mg = $('[data-magnetic]');
          if (mg.length) {
            W.addEventListener('pointermove', function (v) {
              mg.forEach(function (e) {
                var r = e.getBoundingClientRect(), s = +e.dataset.magnetic || .3,
                  dx = v.clientX - (r.left - (e._mx || 0) + r.width / 2),
                  dy = v.clientY - (r.top - (e._my || 0) + r.height / 2),
                  near = Math.abs(dx) < r.width / 2 + 60 && Math.abs(dy) < r.height / 2 + 60;
                e._mx = near ? dx * s : 0;
                e._my = near ? dy * s : 0;
                e.style.transform = near ? 'translate3d(' + e._mx + 'px,' + e._my + 'px,0)' : '';
              });
            }, { passive: true });
          }
        }
      }

      layout();
      W.addEventListener('scroll', onScroll, { passive: true });
      onScroll();
      var rt;
      W.addEventListener('resize', function () { clearTimeout(rt); rt = setTimeout(layout, 150); });
      var late = function () { layout(); setTimeout(sweep, 1500); };
      if (d.readyState === 'complete') late(); else W.addEventListener('load', late);
      if (d.fonts && d.fonts.ready) d.fonts.ready.then(layout);
      d.addEventListener('click', function (ev) {
        var a = ev.target.closest && ev.target.closest('a[href^="#"]'), t;
        if (!a || a.hash.length < 2) return;
        t = d.getElementById(decodeURIComponent(a.hash.slice(1)));
        if (t) setTimeout(function () {
          if (!/^(A|BUTTON|INPUT|SELECT|TEXTAREA)$/.test(t.tagName)) t.setAttribute('tabindex', '-1');
          t.focus({ preventScroll: true });
        }, 0);
      });
    } catch (err) {
      h.classList.remove('m-js');
      $('[data-reveal]').forEach(function (e) { e.classList.add('is-in'); });
      if (W.console) console.warn('motion.js disabled:', err);
    }
  }

  W.Motion = {
    scan: scan,
    shot: function () { calm = true; h.classList.add('shot'); sweep(true); }
  };

  if (d.readyState === 'loading') d.addEventListener('DOMContentLoaded', init); else init();
})(window, document);
