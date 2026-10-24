# Game templates - how a bot builds and ships a game

Ten finished, themed (auto repair shop) games. Each is **one HTML file** that loads `juice.js` (and, for the 3D one, three.js). Copy one, change the `CONFIG` object at the top, check it, publish it. No build step, no network at run time, no copyrighted art or sound (everything is drawn in code or made with WebAudio).

```
games/
  juice.js  juice.src.js  JUICE.md      shared kit (minified build / readable source / API docs)
  lib/                                  pinned libraries + LIBS.md (three r186, Phaser 3.90, Howler 2.2.4)
  templates/                            the ten games (+ they reference ../juice.js and ../lib/)
  qa/sim.js                             touch simulator for the verify loop
  assets-guide.md                       how to fetch CC0 art/audio and the credits rule
```

## 1. Pick a template

| The owner wants... | Template | File | Size | Controls |
|---|---|---|---:|---|
| A quick fun game people can replay (default choice) | **Arcade** - Lug Nut Breaker (breakout) | `arcade.html` | 18 KB | drag to move the paddle |
| An action game with upgrades, longer sessions | **Survivor** - Grease Monkey Survivor (twin-stick) | `shooter.html` | 20 KB | left thumb moves, right thumb aims (auto-aim if idle) |
| A classic jump-and-run with levels | **Platformer** - Garage Dash (tile maps) | `platformer.html` | 22 KB | run buttons + jump button |
| A relaxing puzzle | **Puzzle** - Part Swap (match-3) | `puzzle.html` | 19 KB | swipe or tap two tiles |
| A "wow" visual, bragging rights | **3D runner** - Tire Roll Rush (three.js) | `runner3d.html` | 21 KB (+three) | swipe left/right/up |
| Customer knowledge, events, trivia night, lead magnet | **Quiz / party** - Garage Quiz Night | `quiz.html` | 18 KB | tap answers; solo or 2-4 teams |
| Something creative and calming | **Music toy** - Engine Beats (16-step sequencer) | `musictoy.html` | 14 KB | tap pads |
| A story that builds trust or explains a service | **Branching story** - The Mystery Rattle | `story.html` | 20 KB | tap choices |
| User-generated content people post on social media | **Creative studio** - Sign Studio (draw, stickers, meme text, save PNG) | `studio.html` | 23 KB | draw/drag/tap |
| Promotions, giveaways, a waiting-room screen | **Shop promo** - Spin & Win wheel + scratch card + lobby slideshow | `promo.html` | 26 KB | tap / scratch |

Rules of thumb: a promotion or coupon -> promo. "Something to keep people busy in the waiting room" -> promo (`?mode=lobby&chrome=0`) or arcade. Social reach -> studio. A brand story -> story. Teach/screen customers -> quiz. When unsure -> arcade.

All templates: portrait-first, big touch targets, any screen from phone to desktop, pause when the app is hidden, sound unlocks on the first tap, mute button, saved best scores, `window.__errors` / `window.__qa` QA hooks, <= 30 KB.

## 2. Package a game

Templates sit in `templates/` and point at `../juice.js` (and `../lib/` for 3D). To make a standalone site folder:

1. Create the site folder and copy the template to `index.html`.
2. Copy `juice.js` next to it.
3. In `index.html` replace the text `../juice.js` with `juice.js`.
4. 3D template only: copy `lib/three.module.min.js` and `lib/three.core.js` into `lib/` beside `index.html`, and replace `../lib/` with `lib/` (twice, in the import map).
5. Add the PWA files (section 7) and publish. The 3D game needs `http(s)://` hosting (ES modules do not run from `file://`); the others run from anywhere.

## 3. Retheme with CONFIG

Every template starts with a `const CONFIG = {...}` and `// CUSTOMIZE:` comments. Safe edits, in order of effect:

| Field | Meaning | Where |
|---|---|---|
| `id` | short unique key; saved scores/endings/beat are stored under it | all |
| `title`, `tagline` | big title and one-line subtitle (also change `<title>` in the head) | all |
| `shop` | business name: shown on title and end screens (3D: billboards use `billboard`) | all |
| `cta` | message on the game-over / end screen ("Free tire rotation with any oil change!") | all except musictoy, studio, promo |
| `player` | name used on the high-score board | scoring games |
| `text.*` | every on-screen word (buttons, labels). Keep them short; text auto-shrinks to fit | all |
| `colors.*` | any CSS colors. Change the palette to the business brand | all |
| numbers | `lives`, `hp`, `ballSpeed`, `paddleWidth`, `speed`, `fireInterval`, `damage`, `runSpeed`, `jumpPower`, `startSpeed`, `moves`, `timeLimit`, `playsPerDay`... smaller/bigger = easier/harder | per game |
| `music` | song data (see JUICE.md "Chiptune sequencer") | games with music |

Per-template extras:

* **arcade**: `levels` (rows of bricks), `powerupChance`, `ballSpeedMax`.
* **shooter**: `enemies.{rust,sludge,boss}` (hp, speed, size, xp), `spawn.{start,min,ramp,bossEvery}`, `autoAim`, `upgrades[]` (names/descriptions - keep the `id`s: rate, dmg, multi, pierce, speed, magnet, hp).
* **platformer**: `levels[{name, map[]}]`.
* **puzzle**: `parts[{name,color}]` (4-6 entries), `goals[]`, `moves`, `shuffles`.
* **runner3d**: `colors.sky/ground/road/buildings[]`, `billboard`, `maxSpeed`.
* **quiz**: `questions[]`, `teams[]`, `roundSize`, `partyRounds`.
* **musictoy**: `rows[]` (names, colors, sounds), `presets[]`, `bpm`.
* **story**: `customer`, `stats[]`, `story` (the whole script), `textSpeed`.
* **studio**: `captions.top/bottom[]`, `inks[]`, `badge`, `watermark`, `backgrounds[]`.
* **promo**: `prizes[]`, `rulesExtra[]`, `playsPerDay`, `validDays`, `codePrefix`, `slides[]`, `slideSeconds`, `idleToLobby`.

**Art is code.** Tires, wrenches, oil drops, pistons etc. are vector shapes in small named functions (arcade: `tire`, `nut`, `drop`, `wrenchPaddle`; shooter: `tire`, `wrench`, `drawEnemy`, `drawPlayer`; platformer: `drawTile`, `drawTire`, `drawPlayer`; puzzle: `icon`; runner3d: the `make()` function and the player/scenery blocks; promo: `art`; studio: the `ART` table; story: the `SC` scene table). For a **non-auto business** change the words and colors in `CONFIG`, then replace those few functions (about 10 lines each) with the new shapes or with an image (section 6). Changing words and colors alone already re-brands the game; the shapes stay the shop's tools.

## 4. Add a level, question, prize...

**Arcade level** - add an array of 8-character rows to `CONFIG.levels`. `1`-`5` = brick color, `9` = tough brick (3 hits), `.` = empty.
```js
['9......9', '19....91', '.123321.', '..4444..']
```

**Platformer level** - add `{ name, map }` to `CONFIG.levels`. Rows can be any length (short rows are padded). Legend: `#` solid, `.` empty, `o` bolt, `^` spikes, `E` rolling-tire enemy, `S` spring, `P` player start (exactly one), `F` finish flag (exactly one). Design rules: ground at the bottom two rows, gaps at most 3 tiles wide, steps at most 3 tiles high, spikes at most 2 wide, flag reachable without springs. Tile size is 32 units; jump height is about 3.5 tiles.

**Quiz question** - one line in `CONFIG.questions`: `{ q: 'Question?', a: ['Right', 'Wrong', 'Wrong', 'Wrong'], c: 0, fact: 'Optional fun fact' }`. `c` is the index of the right answer (answers are shuffled for the player). Keep questions under ~90 characters and answers under ~40.

**Story node** - add a key to `CONFIG.story.nodes`:
```js
cheap: { scene: 'counter', text: '{customer} asks about price.', choices: [
  { t: 'Quote fairly', to: 'good', add: { trust: 1 } },
  { t: 'Offer a loyalty discount', to: 'loyal', req: { trust: 2 } } ] },
fin:   { scene: 'sunset', end: 'good', badge: 'Happy Customer', text: 'The end.' }
```
`scene`: garage, road, wheel, counter, sunset, night. `end`: good / bad / neutral (the node then needs no choices). `add` changes a stat (stats are declared in `CONFIG.stats`), `req` hides a choice until the stat is high enough. `{customer}`, `{shop}` and `{stat}` are replaced in text. Every `to` must name an existing node (a missing node logs a console error that the QA hook reports).

**Puzzle goals** - `CONFIG.goals: [500, 900, ...]`; levels past the end add +800 each. **Music toy preset** - `{ name:'PARTY', bpm:120, rows:[6 strings of 16 chars] }`, `.` = off, `1`/`2`/`3` = which note of that row. **Promo prize** - `{ label:'$10 OFF', detail:'$10 off any service over $50', color:'#ff7a1a', weight:1 }`; **keep all weights equal for equal odds** (the rules screen prints the real odds from the weights, and wheel slices are drawn in proportion). **Lobby slide** - `{ title, body, art:'oil'|'tire'|'brake'|'engine'|'wrench'|'car', color }`.

**Promo rules (important):** the promo is free for everyone with equal odds by default and shows a RULES & ODDS screen. Do not hide odds, do not change `weight`s without telling the owner, and edit `rulesExtra`, `playsPerDay`, `validDays` and the prize list to match what the shop really honors. Codes are display-only (no server), so staff must be able to honor "show this screen".

## 5. Sounds

* Built in (nothing to download): `J.play('coin' | 'hit' | 'boom' | 'jump' | 'laser' | 'power' | 'lose' | 'tick' | 'pop' | 'click')`.
* New effect: design one at https://killedbyapixel.github.io/ZzFX/ , then `J.S.mySound = [ ...numbers ];` and `J.play('mySound')`.
* Music: edit `CONFIG.music` (data, no files). A track is a string of notes: `'C4 . E4 . G4 . E4 .'`.
* Real sound files (mp3/ogg) are only needed for recorded effects: use CC0 files (assets-guide.md), embed small ones as data URLs and play with Howler (`lib/howler.min.js`) or `new Audio(dataUrl)` after the first tap.

## 6. Images as data URLs

Keep the game one file by embedding small images. Convert a PNG/JPG/SVG to a data URL and draw it:
```js
const LOGO = new Image();
LOGO.src = 'data:image/png;base64,iVBORw0KGgo...';     // keep each image under ~60 KB; prefer SVG or 128-256 px PNG
// in draw(c):
if (LOGO.complete) c.drawImage(LOGO, x - 48, y - 48, 96, 96);
```
SVG logos work the same: `'data:image/svg+xml;utf8,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" ...>...</svg>')`. Total file budget stays 30 KB for the template plus images; if a game needs more images than that, ship them as separate files next to `index.html` and list them in the service worker cache. Always load images inside the page (no cross-site URLs at run time) and keep credits (assets-guide.md).

## 7. Export as an installable PWA

Publish the site folder (index.html, juice.js, [lib/...]) over https (Vercel/Netlify/GitHub Pages). Add these files:

`manifest.webmanifest`
```json
{
  "name": "Lug Nut Breaker",
  "short_name": "Lug Nut",
  "description": "A quick arcade game from Mike's Auto Repair",
  "start_url": "./index.html",
  "scope": "./",
  "display": "standalone",
  "orientation": "portrait",
  "background_color": "#12151c",
  "theme_color": "#12151c",
  "icons": [
    { "src": "icon-192.png", "sizes": "192x192", "type": "image/png", "purpose": "any" },
    { "src": "icon-512.png", "sizes": "512x512", "type": "image/png", "purpose": "any" },
    { "src": "icon-512.png", "sizes": "512x512", "type": "image/png", "purpose": "maskable" }
  ]
}
```
Use the template's `bg` color for `background_color`/`theme_color`. Use `"orientation": "any"` for the studio, promo/lobby and 3D games if landscape should be allowed.

In `<head>` of `index.html` add:
```html
<link rel="manifest" href="manifest.webmanifest">
<link rel="apple-touch-icon" href="icon-192.png">
<meta name="apple-mobile-web-app-capable" content="yes">
```
**Icons:** PNG, exactly 192x192 and 512x512, square, opaque background, the logo inside the central 80% (the "safe zone", so round/maskable crops do not cut it). Make them with code if you have no art: draw on a 512x512 canvas (brand color fill, a big tire/wrench shape or the shop's initials with `J.txt`), call `canvas.toDataURL('image/png')`, strip the `data:image/png;base64,` prefix and save the decoded bytes as `icon-512.png`; repeat at 192 (draw the same thing scaled). Check that both files open.

`sw.js` (offline cache; list every file the game needs):
```js
const CACHE = 'game-v1';
const FILES = ['./', './index.html', './juice.js', './manifest.webmanifest', './icon-192.png', './icon-512.png'];   // 3D: add ./lib/three.module.min.js and ./lib/three.core.js
self.addEventListener('install', e => e.waitUntil(caches.open(CACHE).then(c => c.addAll(FILES)).then(() => self.skipWaiting())));
self.addEventListener('activate', e => e.waitUntil(caches.keys().then(k => Promise.all(k.filter(x => x !== CACHE).map(x => caches.delete(x)))).then(() => self.clients.claim())));
self.addEventListener('fetch', e => { if (e.request.method !== 'GET') return;
  e.respondWith(caches.match(e.request).then(r => r || fetch(e.request).then(res => { const copy = res.clone(); caches.open(CACHE).then(c => c.put(e.request, copy)); return res; }).catch(() => caches.match('./index.html')))); });
```
Register it at the end of `index.html`:
```html
<script>if ('serviceWorker' in navigator && location.protocol.indexOf('http') === 0) navigator.serviceWorker.register('sw.js').catch(function () {});</script>
```
Bump `CACHE` (`game-v2`) whenever the game changes so phones fetch the new version. Installable checks: manifest + 192/512 icons + service worker + https. Unverified in this staging pass: installing the PWA on the phone.

## 8. Verify loop (run after every change; fix, then run again)

Serve the folder over http and use the browser tool in **its own tab at the front** (background tabs pause the game loop; `SIM.info().hidden` must be `false`). Inject the touch helper: `eval(await (await fetch('/qa/sim.js')).text())`.

1. **Load** the page at **390x844**. Wait ~1 s. `SIM.info()` should show `frames > 0`, `fps >= 55`.
2. **Console errors:** `window.__errors` must be `[]` (juice records script errors, promise rejections, `console.error`, and files that failed to load). Also glance at the console for 404s.
3. **Tap test:** press the start button (`await SIM.tap(Juice.W/2, Juice.H*0.62)`; the title-screen start button of most templates sits at 62-68% of the height; Engine Beats, Sign Studio and Spin & Win start directly on their main screen) and confirm `__qa.info()` changed (state `play`, moves/score appear). Play 5-10 seconds with real gestures (`SIM.drag` for swipes and sticks, two pointer ids for two thumbs), then check `__errors` and `fps` again. Reach the end screen at least once (let it die, or finish) and press the replay button.
4. **Screenshot** at 390x844 and again at **1280x800**: nothing clipped or overlapping, text readable, buttons at least ~44 px, the shop name and CTA spelled right, no leftover "Mike's" or tire words if the theme changed.
5. **Pause:** `Juice.pause()` shows "PAUSED"; one tap resumes; no errors.
6. **Persistence:** reload and confirm the best score/ending/beat is still there; check the game still runs with storage blocked (`localStorage` is guarded).
7. **Package check:** file list matches section 2, `juice.js` path is right, manifest name/colors updated, icons open, the page works from the published URL (not only locally).
8. **Size check:** the HTML is at most ~30 KB (plus images), `juice.js` 16 KB.

A scripted regression like this was run on all ten templates at 390x844 and 1280x800 in a desktop Chromium pane: each loaded with zero console errors, started, played with simulated taps, swipes and two-thumb sticks, state advanced, and pause/resume worked. Frame rate was 56-61 fps (3D runner 50 fps at phone size in that software-rendered test browser). It has **not** been run on the real phone WebView yet.

## 9. Gotchas

* Never read the canvas size; use `Juice.W`/`Juice.H` (logical units). The screen can be any shape from 0.5 to 1.8 aspect.
* Always draw the background over `-30..W+30` so screen shake never shows edges.
* No keyboard on the device: avoid text input. The studio template uses a small DOM text box for custom captions (the system keyboard appears); everything else is tap/drag.
* Sound only starts after a tap; do not call `J.music.start` before the first button press (the templates start music on the Play button).
* `localStorage` can be blocked (private mode): always use `J.save` / `J.load`.
* Do not add network calls (fonts, CDNs, analytics) to a template: the games must work offline and pass the QA check.
* Keep the `// CUSTOMIZE:` comments when editing so the next bot can find them.
