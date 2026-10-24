/* shop-showcase | app.js
   Small, optional extras. Without this file every product still shows and every link works.
   1 "Ask about this" links (text on phones, email on computers) prefilled with the product name
   2 "Send a text" links built from the phone number
   3 collection filter chips with a smooth layout animation
   4 pause the turning badge while the hero is off screen */
(function () {
  'use strict';
  var d = document;
  d.documentElement.classList.add('sh-js');
  var mq = function (q) { return !!(window.matchMedia && window.matchMedia(q).matches); };
  var reduce = mq('(prefers-reduced-motion: reduce)');
  var phoneLike = mq('(pointer: coarse)') || /Android|iPhone|iPad|Mobile/i.test(navigator.userAgent || '');
  function all(s, r) { return [].slice.call((r || d).querySelectorAll(s)); }
  function first(s) { return d.querySelector(s); }

  var telLink = first('a[href^="tel:"]'), mailLink = first('a[href^="mailto:"]');
  var number = telLink ? telLink.getAttribute('href').slice(4) : '';
  var email = mailLink ? mailLink.getAttribute('href').slice(7).split('?')[0] : '';
  var brand = first('.brand__name');
  var shopName = brand ? brand.textContent.trim() : '';
  function smsHref(body) { return 'sms:' + number + (body ? '?body=' + encodeURIComponent(body) : ''); }
  function mailHref(subject, body) {
    return 'mailto:' + email + '?subject=' + encodeURIComponent(subject) + '&body=' + encodeURIComponent(body);
  }

  /* 1. Ask about this */
  var grid = first('.products');
  var via = grid ? (grid.getAttribute('data-ask-via') || 'auto') : 'auto';
  var useSms = number && (via === 'sms' || (via === 'auto' && phoneLike));
  if (via === 'email' || !number) useSms = false;
  all('[data-ask]').forEach(function (a) {
    var card = a.closest('.product');
    var nameEl = card && card.querySelector('.product__name');
    var name = nameEl ? nameEl.textContent.trim() : 'one of your pieces';
    var msg = 'Hi' + (shopName ? ' ' + shopName : '') + ', I would like to ask about: ' + name + '.';
    if (useSms) a.setAttribute('href', smsHref(msg));
    else if (email) a.setAttribute('href', mailHref('Question about ' + name, msg));
    a.setAttribute('aria-label', 'Ask about ' + name + (useSms ? ' by text message' : ' by email'));
  });

  /* 2. Send a text */
  all('[data-sms]').forEach(function (a) { if (number) a.setAttribute('href', smsHref('')); });

  /* 3. Filter chips */
  var chips = all('.chip');
  var cards = grid ? all('.product', grid) : [];
  var status = first('.shop__status');
  function has(card, cat) { return cat === 'all' || (' ' + (card.getAttribute('data-cat') || '') + ' ').indexOf(' ' + cat + ' ') > -1; }
  chips.forEach(function (chip) {
    var cat = chip.getAttribute('data-filter');
    var count = cards.filter(function (c) { return has(c, cat); }).length;
    var badge = chip.querySelector('.chip__count');
    if (badge) badge.textContent = String(count);
    if (!count && cat !== 'all') chip.hidden = true;
  });
  function say(n) {
    if (!status) return;
    status.textContent = n === cards.length ? n + ' pieces' : 'Showing ' + n + ' of ' + cards.length;
  }
  function filter(cat) {
    var before = new Map();
    cards.forEach(function (c) { if (!c.hidden) before.set(c, c.getBoundingClientRect()); });
    var shown = 0;
    cards.forEach(function (c) { var on = has(c, cat); c.hidden = !on; if (on) shown++; });
    say(shown);
    if (reduce || !cards.length || !cards[0].animate) return;
    cards.forEach(function (c) {
      if (c.hidden) return;
      var now = c.getBoundingClientRect(), was = before.get(c);
      if (was) {
        var dx = was.left - now.left, dy = was.top - now.top;
        var sx = now.width ? was.width / now.width : 1, sy = now.height ? was.height / now.height : 1;
        if (Math.abs(dx) < 1 && Math.abs(dy) < 1 && Math.abs(sx - 1) < .01 && Math.abs(sy - 1) < .01) return;
        c.animate([
          { transformOrigin: 'top left', transform: 'translate(' + dx + 'px,' + dy + 'px) scale(' + sx + ',' + sy + ')' },
          { transformOrigin: 'top left', transform: 'none' }
        ], { duration: 520, easing: 'cubic-bezier(.2,.8,.2,1)' });
      } else {
        c.animate([{ opacity: 0, transform: 'scale(.94)' }, { opacity: 1, transform: 'none' }], { duration: 420, easing: 'cubic-bezier(.2,.8,.2,1)' });
      }
    });
  }
  chips.forEach(function (chip) {
    chip.addEventListener('click', function () {
      if (chip.getAttribute('aria-pressed') === 'true') return;
      chips.forEach(function (c) { c.setAttribute('aria-pressed', c === chip ? 'true' : 'false'); });
      filter(chip.getAttribute('data-filter'));
    });
  });
  say(cards.length);

  /* 4. Rest the turning badge when the hero is off screen */
  var hero = first('.hero');
  if (hero && 'IntersectionObserver' in window) {
    new IntersectionObserver(function (e) { hero.classList.toggle('is-paused', !e[0].isIntersecting); }).observe(hero);
  }
})();
