/* app.js | Pro Firm blueprint | plain JavaScript, no libraries.
   Everything is an enhancement: without JavaScript every practice area is open, the hours read as written,
   and the consultation form opens a plain email. Sections:
   1. practice areas (accordion on phones, tabs on wide screens)   2. "Ask about this" links
   3. consultation form (builds an email or text message)          4. opening hours rows
   5. directions link                                               6. phone menu */
(function () {
  'use strict';
  var d = document;
  var $ = function (s, r) { return [].slice.call((r || d).querySelectorAll(s)); };
  var pad = function (n) { return (n < 10 ? '0' : '') + n; };

  /* ---------- 1. practice areas ---------- */
  function areas(root) {
    var items = $('.area', root);
    if (!items.length) return;
    var wide = window.matchMedia ? window.matchMedia('(min-width: 1000px)') : { matches: false };
    var list = d.createElement('div');
    list.className = 'areas__tabs';
    list.setAttribute('role', 'tablist');
    list.setAttribute('aria-orientation', 'vertical');
    list.setAttribute('aria-label', 'Practice areas');
    var tabs = items.map(function (item, i) {
      var toggle = item.querySelector('.area__toggle');
      var panel = item.querySelector('.area__panel');
      var no = item.querySelector('.area__no');
      var label = item.querySelector('.area__label');
      panel.id = 'area-' + (i + 1) + '-panel';
      toggle.id = 'area-' + (i + 1) + '-head';
      toggle.setAttribute('aria-controls', panel.id);
      toggle.addEventListener('click', function () { setOpen(item, !item.classList.contains('is-open')); });
      var tab = d.createElement('button');
      tab.type = 'button';
      tab.className = 'tab';
      tab.id = 'area-' + (i + 1) + '-tab';
      tab.setAttribute('role', 'tab');
      tab.setAttribute('aria-controls', panel.id);
      tab.innerHTML = '<span class="tab__no"></span><span class="tab__label"></span>' +
        '<svg class="icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12h14M13 6l6 6-6 6"/></svg>';
      tab.querySelector('.tab__no').textContent = no ? no.textContent : pad(i + 1);
      tab.querySelector('.tab__label').textContent = (label || toggle).textContent.trim();
      tab.addEventListener('click', function () { select(i); });
      list.appendChild(tab);
      return tab;
    });
    root.insertBefore(list, root.firstChild);

    list.addEventListener('keydown', function (e) {
      var i = tabs.indexOf(d.activeElement), n = tabs.length, j = -1;
      if (i < 0) return;
      if (e.key === 'ArrowDown' || e.key === 'ArrowRight') j = (i + 1) % n;
      else if (e.key === 'ArrowUp' || e.key === 'ArrowLeft') j = (i - 1 + n) % n;
      else if (e.key === 'Home') j = 0;
      else if (e.key === 'End') j = n - 1;
      if (j < 0) return;
      e.preventDefault();
      select(j);
      tabs[j].focus();
    });

    function setOpen(item, open) {
      item.classList.toggle('is-open', open);
      item.querySelector('.area__toggle').setAttribute('aria-expanded', String(open));
    }
    function select(i) {
      items.forEach(function (item, k) {
        setOpen(item, k === i);
        tabs[k].setAttribute('aria-selected', String(k === i));
        tabs[k].tabIndex = k === i ? 0 : -1;
      });
    }
    function mode() {
      var on = !!wide.matches;
      root.classList.toggle('is-tabs', on);
      items.forEach(function (item, k) {
        var panel = item.querySelector('.area__panel');
        panel.setAttribute('role', on ? 'tabpanel' : 'region');
        panel.setAttribute('aria-labelledby', on ? tabs[k].id : item.querySelector('.area__toggle').id);
      });
      if (on) {
        var first = 0;
        items.some(function (item, k) { if (item.classList.contains('is-open')) { first = k; return true; } return false; });
        select(first);
      }
    }
    if (wide.addEventListener) wide.addEventListener('change', mode);
    else if (wide.addListener) wide.addListener(mode);
    mode();
  }

  /* ---------- 2. "Ask about this" fills in the consultation form ---------- */
  function topics() {
    var msg = d.getElementById('c-msg');
    var name = d.getElementById('c-name');
    if (!msg) return;
    $('[data-topic]').forEach(function (a) {
      a.addEventListener('click', function () {
        var t = a.getAttribute('data-topic');
        if (t && msg.value.indexOf(t) < 0) msg.value = 'I would like to ask about: ' + t + '.\n' + msg.value;
        setTimeout(function () { (name && !name.value ? name : msg).focus({ preventScroll: true }); }, 90);
      });
    });
  }

  /* ---------- 3. consultation form: opens the visitor's email or messages app with the request written out ---------- */
  function consult(form) {
    var mail = form.getAttribute('action') || '';
    var tel = (form.getAttribute('data-phone') || '').replace(/^tel:/i, '');
    var hasMail = /^mailto:/i.test(mail), hasTel = /^\+?[0-9][0-9 ().-]{5,}$/.test(tel);
    var smsBtn = form.querySelector('[data-send="sms"]');
    var mailBtn = form.querySelector('[data-send="email"]');
    var status = form.querySelector('.consult__status');
    var via = hasMail ? 'email' : 'sms';
    if (smsBtn) smsBtn.hidden = !hasTel;
    if (mailBtn && !hasMail && hasTel) mailBtn.hidden = true;
    [smsBtn, mailBtn].forEach(function (b) {
      if (b) b.addEventListener('click', function () { via = b.getAttribute('data-send'); });
    });
    function say(t) { if (status) status.textContent = t; }

    form.addEventListener('submit', function (e) {
      e.preventDefault();
      if (e.submitter && e.submitter.getAttribute('data-send')) via = e.submitter.getAttribute('data-send');
      var f = form.elements;
      var name = (f.name && f.name.value || '').trim();
      var reach = (f.contact && f.contact.value || '').trim();
      var text = (f.message && f.message.value || '').trim();
      if (!name) {
        f.name.setAttribute('aria-invalid', 'true');
        f.name.focus();
        say('Please add your name so we know who to reply to.');
        return;
      }
      f.name.removeAttribute('aria-invalid');
      var biz = form.getAttribute('data-business') || '';
      var lines = ['Hello' + (biz ? ' ' + biz : '') + ',', '', 'I would like to request a consultation.', '', 'Name: ' + name];
      if (reach) lines.push('Best way to reach me: ' + reach);
      if (text) lines.push('', text);
      var body = lines.join('\r\n');
      var url = '';
      if (via === 'sms' && hasTel) url = 'sms:' + tel.replace(/[^0-9+]/g, '') + '?&body=' + encodeURIComponent(body);
      else if (hasMail) url = mail.split('?')[0] + '?subject=' + encodeURIComponent('Consultation request from ' + name) + '&body=' + encodeURIComponent(body);
      else if (hasTel) url = 'sms:' + tel.replace(/[^0-9+]/g, '') + '?&body=' + encodeURIComponent(body);
      if (!url) { say('Please call or visit us to arrange a consultation.'); return; }
      say(url.indexOf('sms:') === 0 ? 'Your messages app should open with the request written out. Just press send.' : 'Your email app should open with the request written out. Just press send.');
      window.location.href = url;
    });
  }

  /* ---------- 4. opening hours: "Mon-Fri 8:00-18:00, Sat 9:00-15:00" becomes tidy rows ---------- */
  function hours(el) {
    var raw = (el.textContent || '').replace(/\s+/g, ' ').trim();
    if (!raw || /^add /i.test(raw)) return;
    var chunks = [];
    raw.split(/\s*[;|•·]\s*/).forEach(function (part) {
      var buf = '';
      part.split(/\s*,\s*/).forEach(function (piece) {
        buf = buf ? buf + ', ' + piece : piece;
        if (/\d|closed|appointment/i.test(piece)) { chunks.push(buf); buf = ''; }
      });
      if (buf) chunks.push(buf);
    });
    var rows = chunks.map(function (c) {
      var m = c.match(/^(.*?[A-Za-z.)])\s*:?\s+((?:\d|closed|by appointment|24).*)$/i);
      var dash = function (s) { return s.replace(/(\w)\s*-\s*(\w)/g, '$1–$2'); };
      return m ? { day: dash(m[1]), time: dash(m[2]) } : { day: dash(c), time: '' };
    });
    if (!rows.some(function (r) { return r.time; })) return;
    var ul = d.createElement('ul');
    ul.className = 'hours__list';
    rows.forEach(function (r) {
      var li = d.createElement('li');
      li.className = 'hours__row';
      var a = d.createElement('span'); a.className = 'hours__day'; a.textContent = r.day;
      var b = d.createElement('span'); b.className = 'hours__time'; b.textContent = r.time;
      li.appendChild(a); li.appendChild(b);
      ul.appendChild(li);
    });
    el.textContent = '';
    el.appendChild(ul);
  }

  /* ---------- 5. directions: a maps search for the address ---------- */
  function maps(a) {
    var q = (a.getAttribute('data-maps') || '').trim();
    if (q) a.href = 'https://www.google.com/maps/search/?api=1&query=' + encodeURIComponent(q);
  }

  /* ---------- 6. phone menu: close after a choice, on Escape or a tap outside ---------- */
  function menu(m) {
    m.addEventListener('click', function (e) { if (e.target.closest && e.target.closest('a')) m.open = false; });
    d.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && m.open) { m.open = false; m.querySelector('summary').focus(); }
    });
    d.addEventListener('click', function (e) { if (m.open && !m.contains(e.target)) m.open = false; });
  }

  function init() {
    try {
      $('[data-areas]').forEach(areas);
      topics();
      $('[data-consult]').forEach(consult);
      $('[data-hours]').forEach(hours);
      $('[data-maps]').forEach(maps);
      $('.menu').forEach(menu);
    } catch (err) {
      if (window.console) console.warn('app.js:', err);
    }
  }
  if (d.readyState === 'loading') d.addEventListener('DOMContentLoaded', init); else init();
})();
