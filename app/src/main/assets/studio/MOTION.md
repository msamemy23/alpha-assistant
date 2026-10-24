# MOTION.md: the Alpha Studio motion kit

Two small files, no dependencies, work offline: `motion.css` (1.9 KB gzipped) and `motion.js` (2.9 KB gzipped).
You add attributes to your HTML; the kit does the rest. Only `transform` and `opacity` ever move.
Open `demo.html` to see every effect on one page.

## Install (every page)

```html
<link rel="stylesheet" href="motion.css">
<script src="motion.js"></script>   <!-- in <head>, no defer: it hides reveal targets before first paint -->
```

Content is only hidden once `motion.js` is running, so a visitor without JavaScript sees everything.
If `motion.js` ever throws, it switches itself off and shows everything.

## Quick reference

| Attribute / class | Goes on | Does |
|---|---|---|
| `data-reveal="up\|down\|left\|right\|scale\|fade"` | any element | fades and moves it in when it scrolls into view (empty value = `up`) |
| `data-delay="200"` | a reveal element | waits 200 ms first |
| `data-duration="900"` | a reveal element | animation length in ms (default 700) |
| `data-stagger="90"` | a parent | its children reveal one after another, 90 ms apart |
| `data-count-to="1200"` | a `span` | counts up to the number when it scrolls into view |
| `.marquee` + `.marquee__track` | a wrapper and its inner row | endless scrolling ticker |
| `data-tilt="8"` | any element | tilts toward the mouse pointer (max 8 degrees) |
| `data-magnetic="0.3"` | a button or link | leans toward the mouse pointer |
| `data-parallax="0.15"` | an image or shape | drifts slower than the page as you scroll |
| `data-progress` | `<body>` | thin scroll-progress bar at the top |
| `data-sticky` | `<header>` | sticks to the top; gains a background and shadow after 24 px of scroll |
| `data-shrink` | something inside the sticky header | scales down when the header is stuck |
| `data-lift` | a card | rises 4 px on mouse hover |
| `data-press` | a button | presses to 97% while tapped |
| `?shot` or `html.shot` | URL / root element | everything is already in its final state (for screenshots and lint) |

## data-reveal

```html
<h1 data-reveal="up">We fix brakes the same day</h1>
<p  data-reveal="up" data-delay="150">Call before 10 and we will fit you in.</p>
<img data-reveal="scale" data-delay="250" src="shop.jpg" alt="The front of Dave's Garage" width="800" height="600">
<aside data-reveal="left">Slides in from the left</aside>
<aside data-reveal="right">Slides in from the right</aside>
<footer data-reveal="fade">Just fades</footer>
<div data-reveal="down">Drops in from above</div>
<div data-reveal="up" data-duration="1000">A slower one (1 s)</div>
```

Each element animates once. Distances are 28 px (up, down) and 36 px (left, right); scale starts at 0.92.
Change the speed and curve for the whole page:

```css
:root { --m-dur: .5s; --m-ease: cubic-bezier(.2,.8,.2,1); }
```

## data-stagger

Put it on the parent. Children without their own `data-reveal` inherit the parent's value (default `up`).
Children that enter the screen together are spaced by the number of milliseconds; a row scrolled into view later starts from zero again.

```html
<ul class="cards" data-reveal="up" data-stagger="90">
  <li class="card">Oil change</li>
  <li class="card">Brakes</li>
  <li class="card">Tires</li>
</ul>

<!-- children can still choose their own direction -->
<div data-stagger="120">
  <div data-reveal="left">First</div>
  <div data-reveal="fade">Second</div>
  <div data-reveal="right">Third</div>
</div>
```

## data-count-to

Write the **final** text inside the element (people without JavaScript see it). Only count up real numbers the owner gave you.

```html
<span data-count-to="1200" data-count-suffix="+">1,200+</span>
<span data-count-to="4.9" data-count-decimals="1">4.9</span>
<span data-count-to="25" data-count-duration="2200">25</span>
<span data-count-to="98" data-count-suffix="%">98%</span>
<span data-count-to="350" data-count-prefix="$" data-count-from="100">$350</span>
```

Options: `data-count-from` (default 0), `data-count-duration` (ms, default 1600), `data-count-decimals` (default: as many as the target has), `data-count-prefix`, `data-count-suffix`.
Digits use a fixed width and the box keeps its final width, so nothing jiggles. Inside a `data-stagger` card the count starts together with the card's reveal.
Use a `span`, `strong` or `b`: the element becomes `inline-block`.

## .marquee

Put one set of items in the track; `motion.js` clones it (clones are `aria-hidden`) until the loop is seamless.

```html
<div class="marquee" data-speed="60" aria-label="What we fix">
  <div class="marquee__track" style="--g: 2.5rem">
    <span>Brakes</span><span>Oil changes</span><span>Tires</span><span>Batteries</span><span>Diagnostics</span>
  </div>
</div>
```

`data-speed` is pixels per second (default 50). Add `data-reverse` to run left to right. `--g` sets the gap.
It pauses on hover and while off-screen. The edges fade out with a mask.

## data-tilt and data-magnetic (mouse only)

```html
<article class="card" data-tilt="7">...</article>
<a class="btn" href="tel:+15555550100" data-magnetic>Call now</a>
<a class="btn" href="#quote" data-magnetic="0.45">Get a quote</a>
```

`data-tilt` is the maximum angle in degrees (default 8). `data-magnetic` is how far it follows the pointer, 0 to 1 (default 0.3); it starts reacting 60 px before the pointer touches the element.
Both are ignored on touch screens and when the visitor prefers reduced motion.

## data-parallax

Wrap the moving thing in a clipping box (`.m-clip`) and make it a little bigger than the box so no edge shows.

```html
<div class="m-clip" style="height: 360px">
  <img data-parallax="0.15" style="scale: 1.3; width: 100%; height: 100%; object-fit: cover"
       src="shop.jpg" alt="Inside the shop" width="1200" height="800">
</div>
```

The number is the strength: it drifts up to ±(number x 100) px while crossing the screen. Negative values move the other way. Browsers with scroll-driven animations run it on the compositor; others use a throttled scroll handler.

## data-progress

```html
<body data-progress>
```

```css
:root { --m-progress: #ff6a13; --m-progress-h: 4px; }   /* colour and thickness */
```

You can also place `<div class="m-progress" aria-hidden="true"></div>` yourself.

## data-sticky and data-shrink

```html
<header data-sticky>
  <div class="bar">
    <a class="logo" href="#top" data-shrink>Dave's Garage</a>
    <a class="btn" href="tel:+15555550100">Call</a>
  </div>
</header>
```

```css
:root { --m-header-bg: #14171a; --m-shrink-scale: .85; }  /* background when stuck; logo scale when stuck */
```

After 24 px of scroll the header gets the class `is-stuck`: a background layer fades in and `[data-shrink]` scales down (`transform-origin: left center`).
Optional: set `--m-shrink: 8px` on the header and give it at least 8 px top padding to also tuck it up by 8 px.
Anchor links automatically stop below the sticky header (the kit sets `--m-offset` to its height).

## Smooth anchor scroll

Nothing to add. `<a href="#services">` scrolls smoothly (not for visitors who prefer reduced motion), lands below the sticky header, and moves keyboard focus to the target.

## data-lift and data-press

```html
<article class="card" data-lift>Rises 4 px on hover</article>
<button class="btn" data-press>Presses in when tapped</button>
```

## Screenshot mode

Add `?shot` to the page URL, or call `Motion.shot()` after load. Everything shows in its final state: no hidden elements, counters at their final numbers, no animation, no progress bar.
Use it for screenshots and for `lint-rules.md` checks.

## JavaScript API

```js
Motion.scan(document.querySelector('#reviews'));  // after you add elements dynamically
Motion.shot();                                    // jump to the final state
```

## Rules of thumb

- One element, one transform effect: `data-tilt`, `data-magnetic`, `data-parallax` and `data-lift` all use `transform`, so do not combine them on one element. `data-reveal` combines with any of them (it uses `translate`, `scale` and `opacity`).
- Reveal blocks (headings, cards, images), not page-height wrappers; very tall elements may reveal late.
- At most two extras (marquee, tilt, magnetic, parallax) per page. Motion is seasoning.
- Counters: only numbers the owner supplied.

## Reduced motion

If the visitor's device asks for reduced motion, `motion.css` and `motion.js` switch to a still page: everything is visible at once; reveals, counters (final number shown), tilt, magnetic, parallax, lift, press, the progress bar and header shrink are off; the marquee stops and its items wrap into a normal centered row; smooth scrolling is off. You do not need to do anything.

## How it works and browser support

- Reveal, counters and marquee use `IntersectionObserver` (Android WebView 51+). Without it, everything simply shows.
- Reveals are CSS keyframes on `opacity`, `translate` and `scale`. Individual transform properties need Chrome/WebView 104+ or Safari 14.1+; on older engines only the fade runs.
- The progress bar and parallax use CSS scroll-driven animations inside `@supports (animation-timeline: scroll())`; elsewhere `motion.js` does the same work on a throttled scroll handler.
- Nothing is loaded from the internet.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| An element stays invisible | it has `data-reveal` but is inside `display: none`, or the script is missing; add `?shot` to check, and make sure `motion.js` is linked |
| Reveal does nothing | `motion.css` is not linked |
| Counter shows 0 | you wrote `0` inside the span; write the final number there |
| Marquee has a gap or jumps | give the items `flex: none` (default) and put no margin on the track; use `--g` for spacing |
| Tilt and hover lift fight | both use `transform`; keep one |
