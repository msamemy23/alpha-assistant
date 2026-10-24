/* profile-card | app.js
   Small, dependency-free extras. Every link already works without this file.
   1. Text + Directions links built from the phone and address on the page
   2. Save contact: builds a .vcf from what the page shows (name, role, phone, email, address, hours, photo)
   3. Share (phone share sheet, or copy the link)   4. Copy address   5. Hours as neat rows
   6. Photo strip arrows + dots   7. The small Save bar that appears after scrolling */
(function () {
  'use strict';
  var d = document, W = window;
  var $ = function (s, r) { return (r || d).querySelector(s); };
  var $$ = function (s, r) { return [].slice.call((r || d).querySelectorAll(s)); };
  var txt = function (s) { var e = $(s); return e ? e.textContent.replace(/\s+/g, ' ').trim() : ''; };
  var calm = /[?&]shot(=|&|$)/.test(location.search) || (!!W.matchMedia && W.matchMedia('(prefers-reduced-motion: reduce)').matches);

  /* toast */
  var toast = $('.toast'), tt;
  function say(msg) {
    if (!toast) return;
    toast.textContent = msg;
    toast.classList.add('is-on');
    clearTimeout(tt);
    tt = setTimeout(function () { toast.classList.remove('is-on'); }, 2800);
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

  /* 1. Links from the page's own facts */
  var callA = $('[data-call]');
  var tel = callA ? callA.getAttribute('href') : '';
  if (/^tel:/i.test(tel)) $$('[data-sms]').forEach(function (a) { a.setAttribute('href', tel.replace(/^tel:/i, 'sms:')); });
  var address = txt('[data-address]');
  if (address) {
    var maps = 'https://www.google.com/maps/search/?api=1&query=' + encodeURIComponent(address);
    $$('[data-maps]').forEach(function (a) { a.setAttribute('href', maps); });
  }

  /* 5. Hours: "Mon-Fri 8:00-18:00, Sat 9:00-15:00" becomes one row per day group */
  var hoursEl = $('[data-hours]'), hoursRaw = hoursEl ? hoursEl.textContent.trim() : '';
  if (hoursEl && hoursRaw) {
    var parts = hoursRaw.split(hoursRaw.indexOf(';') > -1 ? /\s*;\s*/ : /\s*,\s*/).filter(Boolean), rows = [];
    parts.forEach(function (p) {
      // a part with no time in it ("Mon") joins the next one ("Wed 9-5" -> "Mon, Wed 9-5")
      if (rows.length && !/\d|closed|appointment|open/i.test(rows[rows.length - 1])) rows[rows.length - 1] += ', ' + p;
      else rows.push(p);
    });
    var split = rows.map(function (r) { return r.match(/^(.+?)\s+((?:\d|by\b|closed\b|open\b|24).*)$/i); });
    if (rows.length > 1 || split[0]) {
      var ul = d.createElement('ul');
      ul.className = 'hours';
      rows.forEach(function (r, i) {
        var li = d.createElement('li'), m = split[i];
        if (m) {
          var a = d.createElement('span'), b = d.createElement('span');
          a.className = 'hours__d'; a.textContent = m[1];
          b.className = 'hours__t'; b.textContent = m[2];
          li.appendChild(a); li.appendChild(b);
        } else li.textContent = r;
        ul.appendChild(li);
      });
      hoursEl.parentNode.replaceChild(ul, hoursEl);
    }
  }

  /* 2. Save contact (.vcf) */
  function esc(v) { return String(v || '').replace(/\\/g, '\\\\').replace(/\r?\n/g, '\\n').replace(/([,;])/g, '\\$1'); }
  function fold(line) {
    var out = line.slice(0, 75), i = 75;
    while (i < line.length) { out += '\r\n ' + line.slice(i, i + 74); i += 74; }
    return out;
  }
  function photo() {
    try {
      var img = $('.avatar__img');
      if (!img || !img.complete || !img.naturalWidth) return '';
      var c = d.createElement('canvas'), s = 256, w = img.naturalWidth, h = img.naturalHeight, m = Math.min(w, h);
      c.width = c.height = s;
      c.getContext('2d').drawImage(img, (w - m) / 2, (h - m) / 3, m, m, 0, 0, s, s);
      return c.toDataURL('image/jpeg', 0.82).split(',')[1] || '';
    } catch (e) { return ''; }   // a picture from another origin cannot be read; save without it
  }
  function vcard() {
    var name = txt('.profile__name'), role = txt('.profile__tagline');
    var em = $('[data-email]');
    var mail = (em ? em.getAttribute('href') : '').replace(/^mailto:/i, '').split('?')[0];
    var phone = (tel || '').replace(/^tel:/i, '');
    var L = ['BEGIN:VCARD', 'VERSION:3.0', 'N:' + esc(name) + ';;;;', 'FN:' + esc(name), 'ORG:' + esc(name)];
    if (role) L.push('TITLE:' + esc(role));
    if (phone) L.push('TEL;TYPE=WORK,VOICE:' + esc(phone));
    if (mail) L.push('EMAIL;TYPE=INTERNET,WORK:' + esc(mail));
    if (address) L.push('ADR;TYPE=WORK:;;' + esc(address) + ';;;;');
    if (/^https?:/i.test(location.href)) L.push('URL:' + esc(location.href.split('#')[0].split('?')[0]));
    if (hoursRaw) L.push('NOTE:' + esc('Hours: ' + hoursRaw));
    var p = photo();
    if (p) L.push('PHOTO;ENCODING=b;TYPE=JPEG:' + p);
    L.push('END:VCARD');
    return L.map(fold).join('\r\n') + '\r\n';
  }
  $$('[data-vcard]').forEach(function (b) {
    b.addEventListener('click', function () {
      try {
        var slug = (d.body.getAttribute('data-slug') || 'contact').replace(/[^a-z0-9-]/gi, '') || 'contact';
        var url = URL.createObjectURL(new Blob([vcard()], { type: 'text/vcard;charset=utf-8' }));
        var a = d.createElement('a');
        a.href = url; a.download = slug + '.vcf'; a.rel = 'noopener';
        d.body.appendChild(a); a.click();
        setTimeout(function () { URL.revokeObjectURL(url); a.parentNode.removeChild(a); }, 2000);
        say('Contact card saved. Open it to add it to your contacts.');
      } catch (e) { say('Could not save the card on this device'); }
    });
  });

  /* 3. Share */
  $$('[data-share]').forEach(function (b) {
    b.addEventListener('click', function () {
      var data = { title: txt('.profile__name'), text: txt('.profile__tagline'), url: location.href.split('#')[0] };
      if (navigator.share) { navigator.share(data).catch(function () {}); return; }
      copyText(data.url).then(function () { say('Link copied. Paste it anywhere to share.'); }, function () { say('Copy the address bar to share this card'); });
    });
  });

  /* 4. Copy address */
  $$('[data-copy-address]').forEach(function (b) {
    b.addEventListener('click', function () {
      copyText(address).then(function () { say('Address copied'); }, function () { say('Press and hold the address to copy it'); });
    });
  });

  /* 6. Photo strip */
  var car = $('.carousel');
  if (car) {
    var slides = $$('.slide', car), dots = $$('.dots span'), prev = $('[data-slide="-1"]'), next = $('[data-slide="1"]');
    var stepW = function () { return slides.length > 1 ? slides[1].offsetLeft - slides[0].offsetLeft : car.clientWidth; };
    var index = function () {
      var max = car.scrollWidth - car.clientWidth;
      if (car.scrollLeft >= max - 4) return slides.length - 1;
      return Math.max(0, Math.min(slides.length - 1, Math.round(car.scrollLeft / stepW())));
    };
    var update = function () {
      var i = index();
      dots.forEach(function (dt, k) { dt.classList.toggle('is-on', k === i); });
      if (prev) prev.disabled = car.scrollLeft < 4;
      if (next) next.disabled = car.scrollLeft >= car.scrollWidth - car.clientWidth - 4;
    };
    var raf = 0;
    car.addEventListener('scroll', function () { if (!raf) raf = W.requestAnimationFrame(function () { raf = 0; update(); }); }, { passive: true });
    $$('[data-slide]').forEach(function (b) {
      b.addEventListener('click', function () {
        car.scrollBy({ left: +b.getAttribute('data-slide') * stepW(), behavior: calm ? 'auto' : 'smooth' });
      });
    });
    car.addEventListener('keydown', function (e) {
      if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') {
        e.preventDefault();
        car.scrollBy({ left: (e.key === 'ArrowRight' ? 1 : -1) * stepW(), behavior: calm ? 'auto' : 'smooth' });
      }
    });
    update();
    W.addEventListener('resize', update);
  }

  /* 7. Save bar: shows once the main Save contact button has scrolled off the top */
  var dock = $('[data-dock]'), main = $('.save-btn');
  if (dock && main && 'IntersectionObserver' in W) {
    new IntersectionObserver(function (es) {
      es.forEach(function (e) { dock.classList.toggle('is-on', !e.isIntersecting && e.boundingClientRect.top < 0); });
    }).observe(main);
  }
})();
