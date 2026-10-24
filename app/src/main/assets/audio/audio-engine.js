/* Alpha Audio engine - loaded by audio-render.html (and by test pages).  Classic script, no modules, no network. See HARNESS-API.md. */
(function () {
'use strict';
const VERSION = '1.0.0';
const SR = 44100;
const SF_URL = 'lib/soundfont/FluidR3Mono_GM.sf3';
const SF_B64_URL = 'lib/soundfont/FluidR3Mono_GM.sf3.b64.js';   // optional fallback (defines window.ALPHA_SF3_B64), not shipped by default
const Core = self.SpessaSynthCore;
const S = self.AlphaScore;
const now = () => (self.performance && performance.now) ? performance.now() : Date.now();

/* ----------------------------------------------------------------------------------------------- host bridge */
function host(fn) { try { const h = self.AlphaAudio; if (h && typeof h[fn] !== 'undefined') return h[fn].apply(h, Array.prototype.slice.call(arguments, 1)); } catch (e) { /* host errors must never break rendering */ } }
function progress(pct, stage) { host('postProgress', Math.round(pct), stage); }
let yieldChan = null;
function yieldNow() { // yields to the event loop without timer throttling (MessageChannel is not throttled in hidden WebViews)
  return new Promise(res => { try { if (!yieldChan) yieldChan = new MessageChannel(); yieldChan.port1.onmessage = () => res(); yieldChan.port2.postMessage(0); } catch (e) { setTimeout(res, 0); } });
}

/* ----------------------------------------------------------------------------------------------- base64 / WAV */
function bytesToBase64(u8) {   // native btoa on 24 KB binary-string chunks (multiple of 3 bytes, so no padding inside the stream)
  const CH = 24576, parts = [];
  for (let i = 0; i < u8.length; i += CH) parts.push(btoa(String.fromCharCode.apply(null, u8.subarray(i, Math.min(u8.length, i + CH)))));
  return parts.join('');
}
function base64ToBytes(b64) {
  const clean = String(b64).replace(/^data:[^,]*,/, '').replace(/[\s]/g, '').replace(/-/g, '+').replace(/_/g, '/');
  let bin; try { bin = atob(clean); } catch (e) { throw new Error('the base64 data is not valid base64'); }
  const u8 = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i);
  return u8;
}
function encodeWav16(L, R, sampleRate) {
  const n = L.length, dataBytes = n * 4, buf = new ArrayBuffer(44 + dataBytes), dv = new DataView(buf);
  const str = (o, s) => { for (let i = 0; i < s.length; i++) dv.setUint8(o + i, s.charCodeAt(i)); };
  str(0, 'RIFF'); dv.setUint32(4, 36 + dataBytes, true); str(8, 'WAVE'); str(12, 'fmt '); dv.setUint32(16, 16, true); dv.setUint16(20, 1, true); dv.setUint16(22, 2, true);
  dv.setUint32(24, sampleRate, true); dv.setUint32(28, sampleRate * 4, true); dv.setUint16(32, 4, true); dv.setUint16(34, 16, true); str(36, 'data'); dv.setUint32(40, dataBytes, true);
  const pcm = new Int16Array(buf, 44, n * 2), LE = new Uint8Array(new Uint16Array([1]).buffer)[0] === 1;
  if (LE) { for (let i = 0, j = 0; i < n; i++, j += 2) { let l = L[i], r = R[i]; l = l > 1 ? 1 : l < -1 ? -1 : l; r = r > 1 ? 1 : r < -1 ? -1 : r; pcm[j] = Math.round(l < 0 ? l * 32768 : l * 32767); pcm[j + 1] = Math.round(r < 0 ? r * 32768 : r * 32767); } }
  else { let o = 44; for (let i = 0; i < n; i++) { let l = L[i], r = R[i]; l = l > 1 ? 1 : l < -1 ? -1 : l; r = r > 1 ? 1 : r < -1 ? -1 : r; dv.setInt16(o, Math.round(l < 0 ? l * 32768 : l * 32767), true); dv.setInt16(o + 2, Math.round(r < 0 ? r * 32768 : r * 32767), true); o += 4; } }
  return new Uint8Array(buf);
}

/* ----------------------------------------------------------------------------------------------- ZzFX (MIT, Frank Force 2019) */
/* Compact generator derived from ZzFXMicro v1.4.0 (lib/zzfx/ZzFXMicro.upstream.js, LICENSE in lib/zzfx/LICENSE).
   Differences: returns a Float32Array instead of playing it, uses a seeded random function, no AudioContext. */
const ZZFX_VOLUME = 0.3;
function zzfxG(params, rand) {
  const a = params.map(x => (x === null ? undefined : x));
  let [volume = 1, randomness = .05, frequency = 220, attack = 0, sustain = 0, release = .1, shape = 0, shapeCurve = 1, slide = 0, deltaSlide = 0, pitchJump = 0, pitchJumpTime = 0,
    repeatTime = 0, noise = 0, modulation = 0, bitCrush = 0, delay = 0, sustainVolume = 1, decay = 0, tremolo = 0, filter = 0] = a;
  const sampleRate = SR, PI2 = Math.PI * 2, abs = Math.abs, sign = v => v < 0 ? -1 : 1;
  let startSlide = slide *= 500 * PI2 / sampleRate / sampleRate,
    startFrequency = frequency *= (1 + randomness * 2 * rand() - randomness) * PI2 / sampleRate,
    modOffset = 0, repeat = 0, crush = 0, jump = 1, length, t = 0, i = 0, s = 0, f;
  /* Alpha extension (not in upstream ZzFX): params[21] = filterEnd (Hz, same units as filter) sweeps the cutoff exponentially over the sound, params[22] = filter Q (default 2). */
  const filterEnd = a[21] || 0, quality = a[22] || 2, sweep = !!(filter && filterEnd), fSign = sign(filter), fStart = abs(filter), fStop = abs(filterEnd) || fStart;
  let a1, a2, b0, b1, b2;
  const setFilter = cut => { const w = PI2 * cut * 2 / sampleRate, cos = Math.cos(w), alpha = Math.sin(w) / 2 / quality, a0 = 1 + alpha;
    a1 = -2 * cos / a0; a2 = (1 - alpha) / a0; b0 = (1 + fSign * cos) / 2 / a0; b1 = -(fSign + cos) / a0; b2 = b0; };
  setFilter(fStart);
  let x2 = 0, x1 = 0, y2 = 0, y1 = 0;
  attack = attack * sampleRate || 9; decay *= sampleRate; sustain *= sampleRate; release *= sampleRate; delay *= sampleRate;
  deltaSlide *= 500 * PI2 / sampleRate ** 3; modulation *= PI2 / sampleRate; pitchJump *= PI2 / sampleRate; pitchJumpTime *= sampleRate;
  repeatTime = repeatTime * sampleRate | 0; volume *= ZZFX_VOLUME;
  length = attack + decay + sustain + release + delay | 0;
  const b = new Float32Array(Math.max(1, length));
  for (; i < length; b[i++] = s * volume) {
    if (sweep && !(i & 31)) setFilter(fStart * Math.pow(fStop / fStart, i / length));
    if (!(++crush % (bitCrush * 100 | 0))) {
      s = shape ? shape > 1 ? shape > 2 ? shape > 3 ? shape > 4 ? (t / PI2 % 1 < shapeCurve / 2) * 2 - 1 : Math.sin(t ** 3) : Math.max(Math.min(Math.tan(t), 1), -1) : 1 - (2 * t / PI2 % 2 + 2) % 2 : 1 - 4 * abs(Math.round(t / PI2) - t / PI2) : Math.sin(t);
      s = (repeatTime ? 1 - tremolo + tremolo * Math.sin(PI2 * i / repeatTime) : 1) * (shape > 4 ? s : sign(s) * abs(s) ** shapeCurve) *
        (i < attack ? i / attack : i < attack + decay ? 1 - ((i - attack) / decay) * (1 - sustainVolume) : i < attack + decay + sustain ? sustainVolume :
          i < length - delay ? (length - i - delay) / release * sustainVolume : 0);
      s = delay ? s / 2 + (delay > i ? 0 : (i < length - delay ? 1 : (length - i) / delay) * b[i - delay | 0] / 2 / volume) : s;
      if (filter) s = y1 = b2 * x2 + b1 * (x2 = x1) + b0 * (x1 = s) - a2 * y2 - a1 * (y2 = y1);
    }
    f = (frequency += slide += deltaSlide) * Math.cos(modulation * modOffset++);
    t += f + f * noise * (i * i * PI2 % 2 - 1);
    if (jump && ++jump > pitchJumpTime) { frequency += pitchJump; startFrequency += pitchJump; jump = 0; }
    if (repeatTime && !(++repeat % repeatTime)) { frequency = startFrequency; slide = startSlide; jump ||= 1; }
  }
  return b;
}
/* ZzFX parameter order: [volume, randomness, frequency, attack, sustain, release, shape(0 sin,1 tri,2 saw,3 tan,4 noise,5 square), shapeCurve, slide, deltaSlide,
   pitchJump, pitchJumpTime, repeatTime, noise, modulation, bitCrush, delay, sustainVolume, decay, tremolo, filter(+highpass Hz / -lowpass Hz)] */
const SFX_LIB = {
  /* units: frequency Hz, slide = 500 Hz/s per unit, deltaSlide = slide units per second, filter cutoff = 2 x |value| Hz (+ high-pass, - low-pass),
     optional Alpha extras: [21] filterEnd (sweep target, same units), [22] filter Q */
  beep:      [[1, 0, 880, .005, .09, .08, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, .9, .01, 0, 0]],
  blip:      [[.8, 0, 1320, .002, .03, .05, 5, .5, 0, 0, 0, 0, 0, 0, 0, 0, 0, .9, 0, 0, 0]],
  tick:      [[.7, 0, 2400, .001, .004, .02, 4, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 800]],
  click:     [[.8, 0, 1800, .001, .006, .03, 4, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 500]],
  pop:       [[1, 0, 350, .001, .01, .07, 0, 1, 24, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0]],
  ding:      [[1, 0, 1568, .002, .02, 1.1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, .35, .02, 0, 0],
              [.35, 0, 3136, .002, .01, .6, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, .3, .01, 0, 0],
              [.12, 0, 4218, .002, .01, .35, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, .3, .01, 0, 0]],
  bell:      [[1, 0, 659, .002, .03, 2.2, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, .35, .04, 0, 0],
              [.5, 0, 1768, .002, .02, 1.4, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, .3, .03, 0, 0],
              [.3, 0, 3262, .002, .01, .9, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, .3, .02, 0, 0],
              [.12, 0, 5100, .002, .01, .5, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, .3, .01, 0, 0]],
  chime:     [[.8, 0, 1046.5, .002, .02, 1.6, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, .35, .03, 0, 0],
              [.3, 0, 2093, .002, .01, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, .3, .02, 0, 0]],
  coin:      [[.9, 0, 988, .002, .06, .06, 5, .5, 0, 0, 0, 0, 0, 0, 0, 0, 0, .8, 0, 0, 0], [.9, 0, 1319, .002, .25, .15, 5, .5, 0, 0, 0, 0, 0, 0, 0, 0, .06, .8, 0, 0, 0]],
  success:   [[.8, 0, 523, .005, .1, .08, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, .9, 0, 0, 0], [.8, 0, 659, .005, .1, .08, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, .12, .9, 0, 0, 0],
              [.9, 0, 784, .005, .22, .3, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, .24, .9, 0, 0, 0]],
  error:     [[.9, 0, 196, .005, .22, .12, 2, 1, 0, 0, 0, 0, .09, 0, 0, 0, 0, .8, 0, .4, 0]],
  buzz:      [[.8, 0, 110, .01, .3, .1, 5, .8, 0, 0, 0, 0, .04, 0, 0, 0, 0, .9, 0, 0, 0]],
  laser:     [[.9, 0, 2200, .002, .05, .14, 2, 1, -16, 0, 0, 0, 0, 0, 0, 0, 0, .8, .02, 0, 0]],
  powerup:   [[.9, 0, 300, .01, .2, .2, 5, .5, 7, 0, 0, 0, 0, 0, 0, 0, 0, .8, .02, 0, 0]],
  alert:     [[.9, 0, 880, .005, .18, .06, 5, .5, 0, 0, 0, 0, 0, 0, 0, 0, 0, .8, 0, 0, 0], [.9, 0, 660, .005, .18, .06, 5, .5, 0, 0, 0, 0, 0, 0, 0, 0, .22, .8, 0, 0, 0]],
  ring:      [[.8, 0, 1568, .002, .55, .08, 0, 1, 0, 0, 0, 0, .08, 0, 0, 0, 0, .9, 0, .7, 0], [.8, 0, 1976, .002, .55, .08, 0, 1, 0, 0, 0, 0, .08, 0, 0, 0, 0, .9, 0, .7, 0]],
  notify:    [[.8, 0, 880, .004, .05, .22, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, .6, .02, 0, 0], [.8, 0, 1320, .004, .05, .5, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, .13, .5, .02, 0, 0]],
  whoosh:    [[.9, 0, 100, .38, .05, .55, 4, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, .85, 0, 0, -60, -2800, 1.4]],
  swoosh:    [[.9, 0, 100, .08, .05, .5, 4, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, .85, 0, 0, -2400, -70, 1.2]],
  explosion: [[1, 0, 40, .005, .1, 1.1, 4, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, .6, .15, 0, -400, -80, 1.2]]
};

/* ----------------------------------------------------------------------------------------------- loading assets */
let sfPromise = null, sfBank = null, sfBytes = null;
function xhrBinary(url) {
  return new Promise((resolve, reject) => {
    const x = new XMLHttpRequest(); x.open('GET', url, true); x.responseType = 'arraybuffer';
    x.onload = () => { if (x.status === 200 || x.status === 0) { if (x.response && x.response.byteLength > 0) resolve(x.response); else reject(new Error('empty response for ' + url)); } else reject(new Error('HTTP ' + x.status + ' for ' + url)); };
    x.onerror = () => reject(new Error('XHR blocked or failed for ' + url)); x.send();
  });
}
async function fetchBinary(url) {
  const errs = [];
  try { const r = await fetch(url); if (r.ok || r.status === 0) { const ab = await r.arrayBuffer(); if (ab.byteLength) return ab; } } catch (e) { errs.push('fetch: ' + e.message); }
  try { return await xhrBinary(url); } catch (e) { errs.push('xhr: ' + e.message); }
  // last resort: classic <script> that defines window.ALPHA_SF3_B64 (works from file:// where fetch/XHR are blocked)
  try {
    if (!self.ALPHA_SF3_B64) await new Promise((res, rej) => { const el = document.createElement('script'); el.src = SF_B64_URL; el.onload = res; el.onerror = () => rej(new Error('script ' + SF_B64_URL + ' not found')); document.head.appendChild(el); });
    if (self.ALPHA_SF3_B64) { const u8 = base64ToBytes(self.ALPHA_SF3_B64); self.ALPHA_SF3_B64 = null; return u8.buffer; }
  } catch (e) { errs.push('b64 script: ' + e.message); }
  throw new Error('Cannot load the SoundFont (' + url + '). Serve this folder with WebViewAssetLoader (https://appassets.androidplatform.net/...) or allow file access, or call AlphaAudioHarness.setSoundFont(arrayBuffer). Details: ' + errs.join(' | '));
}
function loadSoundFont() {
  if (sfPromise) return sfPromise;
  sfPromise = (async () => {
    const t0 = now();
    if (Core && Core.SpessaLog) { try { Core.SpessaLog.setLogLevel(false, false, false); } catch (e) {} }
    await self.StbVorbis.ready;
    const ab = sfBytes || await fetchBinary(SF_URL);
    sfBank = Core.SoundBankLoader.fromArrayBuffer(ab);
    loadSoundFont.ms = Math.round(now() - t0);
    return sfBank;
  })();
  sfPromise.catch(() => { sfPromise = null; });
  return sfPromise;
}

/* ----------------------------------------------------------------------------------------------- SoundFont rendering (SpessaSynth, pure JS, offline) */
function newSynth(effects) {
  const synth = new Core.SpessaSynthProcessor(SR, { effectsEnabled: effects !== false, eventsEnabled: false, maxBufferSize: 128 });
  synth.soundBankManager.addSoundBank(sfBank, 'main');
  synth.setSystemParameter('autoAllocateVoices', true);
  return synth;
}
async function renderSoundFont(plan, L, R) {   // adds into L,R (allocated here when not supplied)
  await loadSoundFont();
  const N = L ? L.length : Math.ceil(plan.renderSec * SR); L = L || new Float32Array(N); R = R || new Float32Array(N);
  if (plan.sf.length) {
    const synth = newSynth(true); await synth.processorInitialized;
    let maxCh = 0; plan.sf.forEach(t => { maxCh = Math.max(maxCh, t.channel); });
    while (synth.midiChannels.length <= maxCh) synth.createMIDIChannel();
    const cc = (ch, c, v) => synth.controllerChange(ch, c, Math.max(0, Math.min(127, Math.round(v))));
    plan.sf.forEach(t => {
      const ch = t.channel;
      if (t.drums) synth.midiChannels[ch].setDrums(true);
      cc(ch, 0, 0); synth.programChange(ch, t.program);
      cc(ch, 7, 127 * Math.sqrt(Math.min(1, t.volume)));   // GM channel volume follows a squared curve; sqrt makes `volume` linear
      cc(ch, 10, 64 + t.pan * 63); cc(ch, 11, 127);
      cc(ch, 91, 127 * t.fx.reverb); cc(ch, 93, 127 * t.fx.chorus); cc(ch, 94, 127 * t.fx.delay);
    });
    const ev = [];
    plan.sf.forEach(t => t.events.forEach(n => {
      const on = Math.round(n.t * SR), off = Math.max(on + 1, Math.round((n.t + n.d) * SR));
      ev.push([on, 1, t.channel, n.note, Math.max(1, Math.min(127, Math.round(n.vel * 127)))], [off, 0, t.channel, n.note, 0]);
    }));
    ev.sort((a, b) => a[0] - b[0] || a[1] - b[1]);
    let pos = 0, ei = 0, lastYield = now();
    while (pos < N) {
      while (ei < ev.length && ev[ei][0] <= pos) { const e = ev[ei++]; if (e[1]) synth.noteOn(e[2], e[3], e[4]); else synth.noteOff(e[2], e[3]); }
      const nextEv = ei < ev.length ? ev[ei][0] : N;
      const n = Math.min(128, N - pos, Math.max(1, nextEv - pos));
      synth.process(L, R, pos, n); pos += n;
      if ((pos & 0x3fff) < 128 && now() - lastYield > 150) { await yieldNow(); lastYield = now(); progress(10 + 40 * pos / N, 'soundfont'); }
    }
    /* NOTE: do not call synth.destroySynthProcessor(): it also tears down the shared SoundBank object */
  }
  for (const m of plan.midi) await mixMidi(m, L, R, plan);
  return { L, R, N };
}
function parseMidi(base64) {
  try { const u8 = base64ToBytes(base64); return Core.BasicMIDI.fromArrayBuffer(u8.buffer.slice(u8.byteOffset, u8.byteOffset + u8.byteLength)); }
  catch (e) { throw new Error('MIDI import failed: ' + (e && e.message ? e.message : e) + ' (expected a standard MIDI file, base64 encoded)'); }
}
async function mixMidi(m, L, R, plan) {
  const midi = parseMidi(m.base64);
  const synth = newSynth(true); await synth.processorInitialized;
  const seq = new Core.SpessaSynthSequencer(synth); seq.loadNewSongList([midi]); seq.play();
  const startS = Math.round((m.startSec || 0) * SR), vol = m.volume === undefined ? 1 : m.volume;
  const N = L.length, tmpL = new Float32Array(128), tmpR = new Float32Array(128);
  const endS = Math.min(N, startS + Math.ceil((midi.duration + Math.max(1.5, plan.tailSec)) * SR));
  let pos = startS, lastYield = now();
  while (pos < endS) {
    seq.processTick();
    const n = Math.min(128, endS - pos);
    tmpL.fill(0); tmpR.fill(0); synth.process(tmpL, tmpR, 0, n);
    for (let i = 0; i < n; i++) { L[pos + i] += tmpL[i] * vol; R[pos + i] += tmpR[i] * vol; }
    pos += n;
    if (now() - lastYield > 150) { await yieldNow(); lastYield = now(); }
  }
  /* see note above: processors are simply dropped for GC; the parsed SoundBank is shared between renders */
  return midi.duration;
}
function midiDurationSec(base64) { return parseMidi(base64).duration; }

/* ----------------------------------------------------------------------------------------------- ZzFX sfx mixing */
function renderSfx(plan, rand, dryL, dryR, revIn) {   // mixes ZzFX events into the dry bus (and the mono reverb send)
  const N = dryL.length;
  plan.sfx.forEach(e => {
    const pg = AlphaSynth.panGains(e.pan), gl = pg[0] * Math.SQRT2 * 0.7071, gr = pg[1] * Math.SQRT2 * 0.7071;
    const start = Math.round(e.t * SR), ratio = e.pitch ? Math.pow(2, e.pitch / 12) : 1;
    e.layers.forEach(l => {
      const p = l.slice(); while (p.length < 21) p.push(undefined);
      if (e.pitch) { p[2] = (p[2] === null || p[2] === undefined ? 220 : p[2]) * ratio; }
      const buf = zzfxG(p, rand);
      for (let i = 0; i < buf.length && start + i < N; i++) {
        const v = buf[i] * e.volume;
        dryL[start + i] += v * gl; dryR[start + i] += v * gr;
        if (e.reverb > 0 && revIn) revIn[start + i] += v * e.reverb;
      }
    });
  });
}

/* ----------------------------------------------------------------------------------------------- Tone.js synth presets */
const hz = m => 440 * Math.pow(2, (m - 69) / 12);
const dbToGain = d => Math.pow(10, d / 20);
function mergeDeep(a, b) { const o = Object.assign({}, a); if (b) for (const k of Object.keys(b)) o[k] = (b[k] && typeof b[k] === 'object' && !Array.isArray(b[k]) && o[k] && typeof o[k] === 'object') ? mergeDeep(o[k], b[k]) : b[k]; return o; }
const MAX_VOICES = 14;
/* melodic presets: a pool of monophonic Tone voices; every note is pre-scheduled with explicit times (no Transport / timer dependency) */
const POLY = {
  pluck:  { trim: -6,  mk: o => new Tone.MonoSynth(mergeDeep({ oscillator: { type: 'sawtooth' }, envelope: { attack: .002, decay: .32, sustain: 0, release: .22 }, filter: { Q: 2, type: 'lowpass', rolloff: -24 }, filterEnvelope: { attack: .001, decay: .24, sustain: 0, release: .2, baseFrequency: 380, octaves: 3.4 } }, o)) },
  pad:    { trim: -6, mk: o => new Tone.Synth(mergeDeep({ oscillator: { type: 'fatsawtooth', count: 3, spread: 26 }, envelope: { attack: .75, decay: .5, sustain: .85, release: 1.9 } }, o)), post: () => new Tone.Filter(2300, 'lowpass') },
  bass:   { trim: -7,  mk: o => new Tone.MonoSynth(mergeDeep({ oscillator: { type: 'sawtooth' }, envelope: { attack: .006, decay: .3, sustain: .6, release: .14 }, filter: { Q: 1.2, type: 'lowpass', rolloff: -24 }, filterEnvelope: { attack: .005, decay: .25, sustain: .25, release: .2, baseFrequency: 130, octaves: 2.6 } }, o)) },
  sub:    { trim: -17.5,  mk: o => new Tone.Synth(mergeDeep({ oscillator: { type: 'sine' }, envelope: { attack: .01, decay: .25, sustain: .85, release: .18 } }, o)) },
  lead:   { trim: -8, mk: o => new Tone.Synth(mergeDeep({ oscillator: { type: 'fatsawtooth', count: 2, spread: 14 }, envelope: { attack: .012, decay: .16, sustain: .65, release: .28 } }, o)), post: () => new Tone.Filter(5200, 'lowpass') },
  chiptune: { trim: -17, mk: o => new Tone.Synth(mergeDeep({ oscillator: { type: 'pulse', width: 0.25 }, envelope: { attack: .002, decay: .09, sustain: .55, release: .05 } }, o)) },
  'chiptune-tri': { trim: -16.5, mk: o => new Tone.Synth(mergeDeep({ oscillator: { type: 'triangle' }, envelope: { attack: .002, decay: .05, sustain: .95, release: .04 } }, o)) },
  bell:   { trim: -2,  mk: o => new Tone.FMSynth(mergeDeep({ harmonicity: 3.01, modulationIndex: 14, oscillator: { type: 'sine' }, envelope: { attack: .001, decay: 1.6, sustain: 0, release: 1.6 }, modulation: { type: 'sine' }, modulationEnvelope: { attack: .001, decay: .5, sustain: 0, release: .5 } }, o)) },
  keys:   { trim: -1,  mk: o => new Tone.FMSynth(mergeDeep({ harmonicity: 2, modulationIndex: 4.5, oscillator: { type: 'sine' }, envelope: { attack: .004, decay: 1.1, sustain: .12, release: .45 }, modulation: { type: 'sine' }, modulationEnvelope: { attack: .002, decay: .35, sustain: .08, release: .3 } }, o)) },
  organ:  { trim: -16, mk: o => new Tone.Synth(mergeDeep({ oscillator: { type: 'custom', partials: [1, .6, .45, .3, .12, .08] }, envelope: { attack: .012, decay: .05, sustain: 1, release: .09 } }, o)) },
  strings: { trim: -10, mk: o => new Tone.Synth(mergeDeep({ oscillator: { type: 'fatsawtooth', count: 3, spread: 18 }, envelope: { attack: .35, decay: .25, sustain: .85, release: .7 } }, o)), post: () => new Tone.Filter(3200, 'lowpass') }
};
/* drum presets: each returns {out, hit(midiNoteOrDrumNote, t, d, vel)} */
function noiseHit(Tn, decay, hp, lp, type) {
  const n = new Tn.NoiseSynth({ noise: { type: type || 'white' }, envelope: { attack: .001, decay: decay, sustain: 0, release: .02 } });
  let tail = n; if (hp) { const f = new Tn.Filter(hp, 'highpass'); tail.connect(f); tail = f; } if (lp) { const f = new Tn.Filter(lp, 'lowpass'); tail.connect(f); tail = f; }
  return { synth: n, out: tail };
}
function drumPreset(name) {
  const T = Tone, out = new T.Gain(1);
  const kick = () => { const s = new T.MembraneSynth({ pitchDecay: .045, octaves: 6.5, oscillator: { type: 'sine' }, envelope: { attack: .001, decay: .34, sustain: 0, release: .06 } }); s.connect(out); return (t, d, v) => s.triggerAttackRelease(52, .3, t, v); };
  const snare = () => { const nz = noiseHit(T, .17, 1100, 9000); nz.out.connect(out); const b = new T.MembraneSynth({ pitchDecay: .02, octaves: 2, envelope: { attack: .001, decay: .1, sustain: 0, release: .03 } }); b.volume.value = -8; b.connect(out);
    return (t, d, v) => { nz.synth.triggerAttackRelease(.15, t, v); b.triggerAttackRelease(190, .08, t, v); }; };
  const rim = () => { const b = new T.MembraneSynth({ pitchDecay: .008, octaves: 1.5, envelope: { attack: .001, decay: .04, sustain: 0, release: .01 } }); const nz = noiseHit(T, .02, 2500, 8000); b.connect(out); nz.out.connect(out);
    return (t, d, v) => { b.triggerAttackRelease(900, .03, t, v * .8); nz.synth.triggerAttackRelease(.02, t, v * .6); }; };
  const hat = (decay, vol) => { const m = new T.MetalSynth({ envelope: { attack: .001, decay: decay, release: .01 }, harmonicity: 5.1, modulationIndex: 32, resonance: 7000, octaves: 1.5 }); m.volume.value = vol; const hp = new T.Filter(6500, 'highpass'); m.connect(hp); hp.connect(out);
    return (t, d, v) => m.triggerAttackRelease(300, decay, t, v); };
  const clap = () => { const nz = noiseHit(T, .12, 800, 4200); const bp = nz.out; bp.connect(out); return (t, d, v) => { [0, .011, .023].forEach(o => nz.synth.triggerAttackRelease(.02, t + o, v * .8)); nz.synth.triggerAttackRelease(.14, t + .035, v); }; };
  const tom = () => { const s = new T.MembraneSynth({ pitchDecay: .06, octaves: 2.5, envelope: { attack: .001, decay: .3, sustain: 0, release: .08 } }); s.connect(out); return (t, d, v, note) => s.triggerAttackRelease(note || 100, .25, t, v); };
  const crash = (decay, vol, hpf) => { const m = new T.MetalSynth({ envelope: { attack: .001, decay: decay, release: .3 }, harmonicity: 5.1, modulationIndex: 40, resonance: 4200, octaves: 1.5 }); m.volume.value = vol; const hp = new T.Filter(hpf || 3000, 'highpass'); m.connect(hp); hp.connect(out);
    return (t, d, v) => m.triggerAttackRelease(300, decay, t, v); };
  const shaker = () => { const nz = noiseHit(T, .07, 5500, 12000); nz.out.connect(out); return (t, d, v) => nz.synth.triggerAttackRelease(.06, t, v * .6); };
  const cowbell = () => { const m = new T.MetalSynth({ envelope: { attack: .001, decay: .18, release: .05 }, harmonicity: 3, modulationIndex: 12, resonance: 800, octaves: .3 }); m.volume.value = -10; const bp = new T.Filter(900, 'bandpass'); m.connect(bp); bp.connect(out); return (t, d, v) => m.triggerAttackRelease(560, .15, t, v); };
  const click = () => { const b = new T.MembraneSynth({ pitchDecay: .004, octaves: 1, envelope: { attack: .001, decay: .05, sustain: 0, release: .01 } }); b.connect(out); return (t, d, v, note) => b.triggerAttackRelease(note || 800, .03, t, v); };
  const chipNoise = () => { const nz = noiseHit(T, .055, 1500, null); nz.out.connect(out); return (t, d, v) => nz.synth.triggerAttackRelease(.05, t, v); };
  if (name === 'kick') { const f = kick(); return { out, trim: -4, hit: (n, t, d, v) => f(t, d, v) }; }
  if (name === 'snare') { const f = snare(); return { out, trim: -6, hit: (n, t, d, v) => f(t, d, v) }; }
  if (name === 'hat') { const f = hat(.05, -14); return { out, trim: -2, hit: (n, t, d, v) => f(t, d, v) }; }
  if (name === 'openhat') { const f = hat(.32, -14); return { out, trim: -2, hit: (n, t, d, v) => f(t, d, v) }; }
  if (name === 'clap') { const f = clap(); return { out, trim: -8, hit: (n, t, d, v) => f(t, d, v) }; }
  if (name === 'tom') { const f = tom(); return { out, trim: -6, hit: (n, t, d, v) => f(t, d, v, hz(n - 12)) }; }
  if (name === 'crash') { const f = crash(1.4, -14); return { out, trim: -4, hit: (n, t, d, v) => f(t, d, v) }; }
  if (name === 'chiptune-noise') { const f = chipNoise(); return { out, trim: -8, hit: (n, t, d, v) => f(t, d, v) }; }
  /* 'drums': GM drum names -> synth voices (voices are created lazily, only the ones the track really uses) */
  const lazy = {}, mk = (k, f) => lazy[k] || (lazy[k] = f());
  return { out, trim: -5, hit: (n, t, d, v) => {
    if (n === 35 || n === 36) mk('K', kick)(t, d, v); else if (n === 38 || n === 40) mk('SN', snare)(t, d, v); else if (n === 37) mk('RM', rim)(t, d, v); else if (n === 39) mk('CL', clap)(t, d, v);
    else if (n === 42 || n === 44) mk('HC', () => hat(.045, -14))(t, d, v); else if (n === 46) mk('HO', () => hat(.32, -14))(t, d, v);
    else if (n === 41 || n === 43 || n === 45 || n === 47 || n === 48 || n === 50) mk('TM', tom)(t, d, v, 70 * Math.pow(2, (n - 41) / 12 * 1.1));
    else if (n === 49 || n === 52 || n === 55 || n === 57) mk('CR', () => crash(1.4, -14))(t, d, v); else if (n === 51 || n === 53 || n === 59) mk('RD', () => crash(.55, -20, 5000))(t, d, v);
    else if (n === 54 || n === 69 || n === 70) mk('SH', shaker)(t, d, v); else if (n === 56) mk('CB', cowbell)(t, d, v);
    else mk('CK', click)(t, d, v, 700 + (n % 12) * 60);
  } };
}
function buildToneTrack(tr) {
  const out = new Tone.Gain(1);
  const over = {}; if (tr.env) over.envelope = tr.env; if (tr.osc) over.oscillator = { type: tr.osc };
  if (POLY[tr.preset]) {
    const def = POLY[tr.preset];
    let head = out; if (def.post) { const p = def.post(); p.connect(out); head = p; }
    const bus = new Tone.Gain(dbToGain(def.trim)); bus.connect(head);
    const voices = [], busy = [];
    tr.events.forEach(n => {
      let vi = -1, best = Infinity;
      for (let i = 0; i < voices.length; i++) if (busy[i] <= n.t - 0.0005 && busy[i] < best) { best = busy[i]; vi = i; }
      if (vi < 0 && voices.length < MAX_VOICES) { const v = def.mk(over); v.connect(bus); voices.push(v); busy.push(-1); vi = voices.length - 1; }
      if (vi < 0) { vi = busy.indexOf(Math.min.apply(null, busy)); }
      voices[vi].triggerAttackRelease(hz(n.note), n.d, n.t, n.vel);
      busy[vi] = n.t + n.d + 0.02;
    });
  } else {
    const dr = drumPreset(tr.preset); const g = new Tone.Gain(dbToGain(dr.trim)); dr.out.connect(g); g.connect(out);
    tr.events.forEach(n => dr.hit(n.note, n.t, n.d, n.vel));
  }
  return out;
}

/* ----------------------------------------------------------------------------------------------- Tone offline graph */
function makeImpulse(ctx, decay, preDelay, damping) {
  const len = Math.max(2048, Math.ceil((preDelay + decay * 1.15) * SR)), buf = ctx.createBuffer(2, len, SR);
  let seed = 987654321;
  const r = () => { seed = (seed * 1664525 + 1013904223) >>> 0; return seed / 2147483648 - 1; };
  for (let c = 0; c < 2; c++) {
    const d = buf.getChannelData(c); let lp = 0; const pre = Math.floor(preDelay * SR);
    for (let i = pre; i < len; i++) {
      const t = (i - pre) / SR, env = Math.exp(-6.91 * t / decay);
      const a = Math.max(0.04, 1 - damping * Math.min(1, t / decay) * 0.97 - damping * 0.15);   // darker as the tail ages
      lp += a * (r() - lp); d[i] = lp * env * (i - pre < 64 ? (i - pre) / 64 : 1);
    }
  }
  return buf;
}
function chainForTrack(tr, trackOut, masterIn, reverbIn, delayIn) {
  let tail = trackOut; const f = tr.fx;
  const add = n => { tail.connect(n); tail = n; };
  if (f.highpass) add(new Tone.Filter(f.highpass, 'highpass'));
  if (f.lowpass) add(new Tone.Filter(f.lowpass, 'lowpass'));
  if (f.eq) add(new Tone.EQ3({ low: f.eq.low || 0, mid: f.eq.mid || 0, high: f.eq.high || 0 }));
  if (f.distortion) add(new Tone.Distortion({ distortion: Math.min(1, f.distortion), wet: 1 }));
  if (f.chorus) { const c = new Tone.Chorus({ frequency: 1.4, delayTime: 3.5, depth: .7, wet: Math.min(1, f.chorus) }); c.start(); add(c); }
  if (f.compressor) { const c = f.compressor === true ? {} : f.compressor; add(new Tone.Compressor({ threshold: c.threshold === undefined ? -18 : c.threshold, ratio: c.ratio || 3, attack: .01, release: .15 })); }
  const pan = new Tone.Panner(Math.max(-1, Math.min(1, tr.pan))); add(pan);
  const vol = new Tone.Gain(tr.volume); add(vol);
  vol.connect(masterIn);
  if (f.reverb > 0 && reverbIn) { const g = new Tone.Gain(f.reverb); vol.connect(g); g.connect(reverbIn); }
  if (f.delay > 0 && delayIn) { const g = new Tone.Gain(f.delay); vol.connect(g); g.connect(delayIn); }
}
async function renderToneGraph(plan, sf, sfx) {
  const prev = Tone.getContext();
  const ctx = new Tone.OfflineContext(2, plan.renderSec, SR);
  Tone.setContext(ctx);
  try {
    const m = plan.master;
    const masterIn = new Tone.Gain(1);
    let tail = masterIn;
    if (m.eq) { const e = new Tone.EQ3({ low: m.eq.low || 0, mid: m.eq.mid || 0, high: m.eq.high || 0 }); tail.connect(e); tail = e; }
    if (m.compressor) { const c = m.compressor === true ? {} : m.compressor; const comp = new Tone.Compressor({ threshold: c.threshold === undefined ? -16 : c.threshold, ratio: c.ratio || 2.5, attack: .02, release: .25, knee: 12 }); tail.connect(comp); tail = comp; }
    tail.toDestination();
    const needReverb = plan.tone.some(t => t.fx.reverb > 0) || (sfx && sfx.hasWet);
    const needDelay = plan.tone.some(t => t.fx.delay > 0);
    let reverbIn = null, delayIn = null;
    if (needReverb) {
      reverbIn = new Tone.Gain(1);
      const conv = new Tone.Convolver({ url: new Tone.ToneAudioBuffer(makeImpulse(ctx, m.reverb.decay, m.reverb.preDelay, m.reverb.damping)), normalize: true });
      const ret = new Tone.Gain(0.5); reverbIn.connect(conv); conv.connect(ret); ret.connect(masterIn);
    }
    if (needDelay) {
      delayIn = new Tone.Gain(1);
      const d = new Tone.FeedbackDelay({ delayTime: Math.min(2, m.delay.time), feedback: m.delay.feedback, wet: 1, maxDelay: 2 });
      const ret = new Tone.Gain(0.55); delayIn.connect(d); d.connect(ret); ret.connect(masterIn);
    }
    const bufSrc = (L, R, dest) => {
      const ab = ctx.createBuffer(2, L.length, SR); ab.copyToChannel(L, 0); ab.copyToChannel(R, 1);
      const src = new Tone.ToneBufferSource({ url: new Tone.ToneAudioBuffer(ab) }); src.connect(dest); src.start(0); return src;
    };
    if (sf) bufSrc(sf.L, sf.R, masterIn);
    if (sfx) { bufSrc(sfx.dry.L, sfx.dry.R, masterIn); if (sfx.hasWet && reverbIn) bufSrc(sfx.wet.L, sfx.wet.R, reverbIn); }
    const tb = now();
    plan.tone.forEach(tr => { const o = buildToneTrack(tr); chainForTrack(tr, o, masterIn, reverbIn, delayIn); });
    await Tone.loaded();
    renderToneGraph.buildMs = Math.round(now() - tb); const tr0 = now();
    const rendered = await ctx.render(false);      // false = no setTimeout yields (hidden-WebView safe)
    renderToneGraph.renderMs = Math.round(now() - tr0);
    const ab = rendered.get();
    return { L: Float32Array.from(ab.getChannelData(0)), R: Float32Array.from(ab.getChannelData(Math.min(1, ab.numberOfChannels - 1))) };
  } finally { Tone.setContext(prev); }
}

/* ----------------------------------------------------------------------------------------------- mastering: loudness, limiter, fades */
function biquad(b0, b1, b2, a1, a2) { let x1 = 0, x2 = 0, y1 = 0, y2 = 0; return x => { const y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2; x2 = x1; x1 = x; y2 = y1; y1 = y; return y; }; }
function kWeight(fs) {   // ITU-R BS.1770 pre-filter (high shelf) + RLB high-pass
  let f0 = 1681.974450955533, G = 3.999843853973347, Q = 0.7071752369554196;
  let A = Math.pow(10, G / 40), w0 = 2 * Math.PI * f0 / fs, al = Math.sin(w0) / (2 * Q), c = Math.cos(w0), sA = Math.sqrt(A);
  const a0 = (A + 1) - (A - 1) * c + 2 * sA * al;
  const s1 = [A * ((A + 1) + (A - 1) * c + 2 * sA * al) / a0, -2 * A * ((A - 1) + (A + 1) * c) / a0, A * ((A + 1) + (A - 1) * c - 2 * sA * al) / a0, 2 * ((A - 1) - (A + 1) * c) / a0, ((A + 1) - (A - 1) * c - 2 * sA * al) / a0];
  f0 = 38.13547087602444; Q = 0.5003270373238773;
  const K = Math.tan(Math.PI * f0 / fs), den = 1 + K / Q + K * K;
  const s2 = [1, -2, 1, 2 * (K * K - 1) / den, (1 - K / Q + K * K) / den];
  return [s1, s2];
}
function measureLoudness(L, R, fs) {   // integrated loudness, gated (absolute -70 LUFS, relative -10 LU); returns LUFS or -Infinity
  const n = L.length; if (!n) return -Infinity;
  const [s1, s2] = kWeight(fs), hop = Math.round(0.1 * fs), nh = Math.ceil(n / hop), he = new Float64Array(nh);
  const A0 = s1[0], A1 = s1[1], A2 = s1[2], A3 = s1[3], A4 = s1[4], B0 = s2[0], B1 = s2[1], B2 = s2[2], B3 = s2[3], B4 = s2[4];
  for (let c = 0; c < 2; c++) {                 // each channel: two cascaded biquads, energy summed per 100 ms hop (inlined for speed)
    const X = c ? R : L; let x1 = 0, x2 = 0, y1 = 0, y2 = 0, u1 = 0, u2 = 0, v1 = 0, v2 = 0;
    for (let h = 0; h < nh; h++) {
      const end = Math.min(n, (h + 1) * hop); let e = 0;
      for (let i = h * hop; i < end; i++) {
        const x = X[i], y = A0 * x + A1 * x1 + A2 * x2 - A3 * y1 - A4 * y2; x2 = x1; x1 = x; y2 = y1; y1 = y;
        const v = B0 * y + B1 * u1 + B2 * u2 - B3 * v1 - B4 * v2; u2 = u1; u1 = y; v2 = v1; v1 = v; e += v * v;
      }
      he[h] += e;
    }
  }
  const blocks = [];
  if (nh < 4) { let s = 0; for (let h = 0; h < nh; h++) s += he[h]; blocks.push(s / n); }
  else for (let h = 0; h + 4 <= nh; h++) blocks.push((he[h] + he[h + 1] + he[h + 2] + he[h + 3]) / (4 * hop));
  const lufs = z => -0.691 + 10 * Math.log10(z);
  let kept = blocks.filter(z => z > 0 && lufs(z) > -70); if (!kept.length) return -Infinity;
  const mean = a => a.reduce((p, q) => p + q, 0) / a.length;
  const rel = lufs(mean(kept)) - 10; kept = kept.filter(z => lufs(z) > rel);
  return kept.length ? lufs(mean(kept)) : -Infinity;
}
function peakOf(L, R) { let p = 0; for (let i = 0; i < L.length; i++) { const a = Math.abs(L[i]), b = Math.abs(R[i]); if (a > p) p = a; if (b > p) p = b; } return p; }
function rmsOf(L, R) { let s = 0; const n = L.length; for (let i = 0; i < n; i++) s += L[i] * L[i] + R[i] * R[i]; return n ? Math.sqrt(s / (2 * n)) : 0; }
function limitInPlace(L, R, gain, ceiling) {   // look-ahead peak limiter (5 ms), then applies `gain`; guarantees |x| <= ceiling
  const N = L.length, W = Math.max(2, Math.round(0.005 * SR)), g = new Float32Array(N);
  for (let i = 0; i < N; i++) { const p = Math.max(Math.abs(L[i]), Math.abs(R[i])) * gain; g[i] = p > ceiling ? ceiling / p : 1; }
  const cap = W + 2, qi = new Int32Array(cap), qv = new Float32Array(cap); let head = 0, size = 0;
  const m = new Float32Array(N);
  for (let i = N - 1; i >= 0; i--) {   // sliding minimum over [i, i+W-1]
    while (size && qv[(head + size - 1) % cap] >= g[i]) size--;
    const pos = (head + size) % cap; qi[pos] = i; qv[pos] = g[i]; size++;
    while (size && qi[head] > i + W - 1) { head = (head + 1) % cap; size--; }
    m[i] = qv[head];
  }
  let run = 0; for (let k = 0; k < W; k++) run += m[0];   // pre-roll uses m[0]
  const out = new Float32Array(N);
  for (let i = 0; i < N; i++) {                         // moving average of m over [i-W+1, i]
    run += m[i] - (i - W >= 0 ? m[i - W] : m[0]); out[i] = run / W;
  }
  let minG = 1;
  for (let i = 0; i < N; i++) {
    let a = L[i] * gain * out[i], b = R[i] * gain * out[i]; if (out[i] < minG) minG = out[i];
    L[i] = a > ceiling ? ceiling : a < -ceiling ? -ceiling : a; R[i] = b > ceiling ? ceiling : b < -ceiling ? -ceiling : b;
  }
  return -20 * Math.log10(minG);   // deepest gain reduction applied by the limiter, in dB (0 = never engaged)
}
function applyFades(L, R, fin, fout) {
  const n = L.length, a = Math.min(n, Math.round(fin * SR)), b = Math.min(n, Math.round(fout * SR));
  for (let i = 0; i < a; i++) { const g = 0.5 - 0.5 * Math.cos(Math.PI * i / a); L[i] *= g; R[i] *= g; }
  for (let i = 0; i < b; i++) { const g = 0.5 - 0.5 * Math.cos(Math.PI * i / b), j = n - 1 - i; L[j] *= g; R[j] *= g; }
}
function masterAndEncode(raw, o) {
  // raw {L,R}; o {outSec, tailWrap, fadeIn, fadeOut, normalize, targetLufs, ceiling, peak, gain}
  const t0 = now(); let { L, R } = raw; const warnings = [];
  const outN = Math.min(L.length, Math.round(o.outSec * SR));
  if (o.tailWrap && L.length > outN) { const tailN = L.length - outN; for (let i = 0; i < tailN && i < outN; i++) { L[i] += L[outN + i]; R[i] += R[outN + i]; } }
  if (L.length !== outN) { L = L.subarray(0, outN); R = R.subarray(0, outN); }
  for (let i = 0; i < outN; i++) { if (!(L[i] === L[i])) L[i] = 0; if (!(R[i] === R[i])) R[i] = 0; }   // NaN guard
  if (o.fadeIn > 0 || o.fadeOut > 0) applyFades(L, R, o.fadeIn, o.fadeOut);
  const rawPeak = peakOf(L, R), lufsIn = measureLoudness(L, R, SR);
  let g = 1;
  if (rawPeak < 1e-5) warnings.push('The rendered audio is silent (no notes reached the output).');
  else if (o.normalize === 'lufs' && isFinite(lufsIn)) {
    g = Math.pow(10, (o.targetLufs - lufsIn) / 20);
    const gPeak = o.ceiling / rawPeak;
    const cap = gPeak * Math.pow(10, 9 / 20);           // never need more than 9 dB of limiting to reach the target
    if (g > cap) { g = cap; warnings.push('Material is very peaky; loudness target not fully reached (limited to keep it clean).'); }
    g = Math.min(g, Math.pow(10, 30 / 20));
  } else if (o.normalize === 'peak' || (o.normalize === 'lufs' && !isFinite(lufsIn))) g = rawPeak > 0 ? o.peak / rawPeak : 1;
  g *= o.gain;
  const limiterDb = limitInPlace(L, R, g, o.ceiling);
  const peak = peakOf(L, R), rms = rmsOf(L, R), lufs = measureLoudness(L, R, SR);
  const wav = encodeWav16(L, R, SR), b64 = bytesToBase64(wav);
  return { wavBase64: b64, wavBytes: wav.length, durationSec: outN / SR, sampleRate: SR, channels: 2, peak: peak, rms: rms, peakDb: peak > 0 ? 20 * Math.log10(peak) : -Infinity,
    rmsDb: rms > 0 ? 20 * Math.log10(rms) : -Infinity, lufs: lufs, gainApplied: g, limiterDb: Math.round(limiterDb * 10) / 10, warnings: warnings, ms: Math.round(now() - t0), L: L, R: R };
}

/* ----------------------------------------------------------------------------------------------- public: renderScore / renderMidi / renderToneCode */
function deliver(res, meta, opts) {
  if (opts && opts.post === false) return;
  host('postMeta', JSON.stringify(meta));
  const cs = opts && opts.chunkSize;   // optional: deliver the base64 in pieces through AlphaAudio.postChunk(index, count, piece) instead of one big postResult string
  if (cs > 0 && self.AlphaAudio && typeof self.AlphaAudio.postChunk !== 'undefined') {
    const b = res.wavBase64, n = Math.ceil(b.length / cs);
    for (let i = 0; i < n; i++) host('postChunk', i, n, b.substr(i * cs, cs));
  } else host('postResult', res.wavBase64);
}
function publicResult(r, extra) {
  const o = { wavBase64: r.wavBase64, durationSec: r.durationSec, peak: r.peak, rms: r.rms };
  o.lufs = r.lufs; o.limiterDb = r.limiterDb; o.peakDb = r.peakDb; o.rmsDb = r.rmsDb; o.sampleRate = r.sampleRate; o.channels = r.channels; o.wavBytes = r.wavBytes; o.warnings = r.warnings.slice();
  return Object.assign(o, extra || {});
}
/* ---- JS synth tracks: render, apply per-track fx, pan/volume, mix into the dry bus and the mono send buses ---- */
async function mixSynthTracks(plan, bus, rand) {
  const S2 = self.AlphaSynth, N = bus.N, tL = new Float32Array(N), tR = new Float32Array(N);
  let lastY = now();
  for (const tr of plan.synth) {
    tL.fill(0); tR.fill(0);
    S2.renderTrack(tr, tL, tR, SR, rand);
    let end = 0; tr.events.forEach(n => { end = Math.max(end, Math.round((n.t + n.d + 5) * SR)); }); end = Math.min(N, end);
    const f = tr.fx, vL = tL.subarray(0, end), vR = tR.subarray(0, end);
    if (f.highpass) { const c = S2.biquadCoef('highpass', f.highpass, 0.707, 0, SR); S2.biquadRun(tL, c, 0, end); S2.biquadRun(tR, c, 0, end); }
    if (f.lowpass) { const c = S2.biquadCoef('lowpass', f.lowpass, 0.707, 0, SR); S2.biquadRun(tL, c, 0, end); S2.biquadRun(tR, c, 0, end); }
    if (f.eq) S2.eqRun(vL, vR, f.eq, SR, f.eq.low || 0, f.eq.mid || 0, f.eq.high || 0);
    if (f.distortion) S2.distortRun(vL, vR, Math.min(1, f.distortion));
    if (f.chorus) S2.chorusRun(vL, vR, Math.min(1, f.chorus), SR);
    if (f.compressor) { const c = f.compressor === true ? {} : f.compressor; S2.compressor(vL, vR, SR, c.threshold === undefined ? -18 : c.threshold, c.ratio || 3, 0.01, 0.15, 6); }
    const pg = S2.panGains(tr.pan), gl = pg[0] * Math.SQRT2 * tr.volume, gr = pg[1] * Math.SQRT2 * tr.volume, rv = f.reverb * tr.volume * 0.5, dl = f.delay * tr.volume * 0.5;
    const dL = bus.L, dR = bus.R;
    for (let i = 0; i < end; i++) { dL[i] += tL[i] * gl; dR[i] += tR[i] * gr; }
    if (f.reverb > 0) { const rb = bus.rev(); for (let i = 0; i < end; i++) rb[i] += (tL[i] + tR[i]) * rv; }
    if (f.delay > 0) { const db = bus.del(); for (let i = 0; i < end; i++) db[i] += (tL[i] + tR[i]) * dl; }
    if (now() - lastY > 150) { await yieldNow(); lastY = now(); }
  }
}
/* ---- master section in JS: reverb return, echo return, EQ, compressor ---- */
function masterSection(plan, bus) {
  const m = plan.master, L = bus.L, R = bus.R;
  if (bus.revIn) self.AlphaSynth.reverbRun(bus.revIn, L, R, SR, m.reverb.decay, m.reverb.preDelay, m.reverb.damping, REVERB_RETURN);
  if (bus.delIn) self.AlphaSynth.delayRun(bus.delIn, L, R, SR, Math.min(2, Math.max(0.02, m.delay.time)), m.delay.feedback, 0.7);
  if (m.eq) self.AlphaSynth.eqRun(L, R, m.eq, SR, m.eq.low || 0, m.eq.mid || 0, m.eq.high || 0);
  if (m.compressor) { const c = m.compressor === true ? {} : m.compressor; return self.AlphaSynth.compressor(L, R, SR, c.threshold === undefined ? -16 : c.threshold, c.ratio || 2.5, 0.02, 0.25, 12); }
  return 0;
}
const REVERB_RETURN = 0.6;

async function renderScore(scoreJson, opts) {
  opts = opts || {};
  const T0 = now(), tm = {};
  try {
    progress(1, 'compile');
    let plan = S.compileScore(scoreJson, SFX_LIB);
    // scores that only contain MIDI have no explicit length: extend to the MIDI length (+ tail) unless durationSec was given
    if (plan.midi.length && !plan.explicitDuration) {
      let longest = 0; plan.midi.forEach(m => { longest = Math.max(longest, (m.startSec || 0) + midiDurationSec(m.base64)); });
      if (longest + plan.tailSec > plan.outSec || (plan.sf.length + plan.synth.length + plan.tone.length + plan.sfx.length === 0)) { plan.outSec = Math.min(S.LIMITS.durationSec, longest + plan.tailSec); plan.renderSec = plan.outSec + (plan.seamless ? plan.tailSec : 0); }
    }
    tm.compileMs = Math.round(now() - T0);
    const N = Math.ceil(plan.renderSec * SR);
    const rand = (function (a) { return function () { a |= 0; a = a + 0x6D2B79F5 | 0; let t = Math.imul(a ^ a >>> 15, 1 | a); t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; }; })(2024);
    const bus = { N: N, L: new Float32Array(N), R: new Float32Array(N), revIn: null, delIn: null, rev() { return this.revIn || (this.revIn = new Float32Array(N)); }, del() { return this.delIn || (this.delIn = new Float32Array(N)); } };
    let t1 = now();
    if (plan.sf.length || plan.midi.length) { progress(8, 'soundfont'); await renderSoundFont(plan, bus.L, bus.R); tm.soundfontMs = Math.round(now() - t1); tm.soundfontLoadMs = loadSoundFont.ms || 0; }
    t1 = now(); if (plan.synth.length) { progress(45, "synth"); await mixSynthTracks(plan, bus, rand); tm.synthMs = Math.round(now() - t1); }
    t1 = now(); if (plan.sfx.length) { renderSfx(plan, rand, bus.L, bus.R, plan.sfx.some(e => e.reverb > 0) ? bus.rev() : null); tm.sfxMs = Math.round(now() - t1); }
    if (plan.tone.length) {
      progress(55, 'tone'); t1 = now();
      const stem = await renderToneGraph(plan, null, null); tm.toneMs = Math.round(now() - t1); tm.toneBuildMs = renderToneGraph.buildMs; tm.toneRenderMs = renderToneGraph.renderMs;
      for (let i = 0, M = Math.min(N, stem.L.length); i < M; i++) { bus.L[i] += stem.L[i]; bus.R[i] += stem.R[i]; }
    }
    progress(75, 'master'); t1 = now(); const gr = masterSection(plan, bus); tm.fxMs = Math.round(now() - t1);
    const r = masterAndEncode({ L: bus.L, R: bus.R }, { outSec: plan.outSec, tailWrap: plan.seamless, fadeIn: plan.fadeIn, fadeOut: plan.fadeOut, normalize: plan.master.normalize, targetLufs: plan.master.targetLufs,
      ceiling: plan.master.ceiling, peak: plan.master.peak, gain: plan.master.gain });
    tm.masterMs = r.ms; tm.totalMs = Math.round(now() - T0);
    const res = publicResult(r, { timings: tm, warnings: plan.warnings.concat(r.warnings), noteCount: plan.noteCount, title: plan.title, tempo: plan.tempo, loops: plan.loops,
      renderXRealtime: Math.round(r.durationSec / (tm.totalMs / 1000) * 10) / 10, masterCompressionDb: Math.round(gr * 10) / 10 });
    if (opts.keepPcm) { res.pcm = { L: r.L, R: r.R }; }
    progress(100, 'done');
    deliver(res, { durationSec: res.durationSec, peak: res.peak, rms: res.rms, lufs: res.lufs, timings: tm, warnings: res.warnings, title: plan.title }, opts);
    return res;
  } catch (e) {
    const msg = (e && e.message) ? e.message : String(e);
    if (!(opts && opts.post === false)) host('postError', msg);
    throw e;
  }
}
function midiScore(midiBase64, opts) {   // a standard MIDI file (base64) as a one-item score; opts: volume, startSec, durationSec, tailSec, title, master
  opts = opts || {};
  const score = { title: opts.title || 'MIDI import', midi: { base64: midiBase64, volume: opts.volume === undefined ? 1 : opts.volume, startSec: opts.startSec || 0 }, master: opts.master || {} };
  if (opts.durationSec) score.durationSec = opts.durationSec;
  if (opts.tailSec !== undefined) score.tailSec = opts.tailSec;
  return score;
}
const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
async function renderToneCode(code, opts) {
  opts = opts || {};
  const T0 = now();
  const dm = /^\s*\/\/\s*@?duration\s*[:=]?\s*([\d.]+)/im.exec(code);
  const dur = Math.max(0.1, Math.min(120, opts.durationSec || (dm ? parseFloat(dm[1]) : 10)));
  const limitMs = opts.timeLimitMs || 60000, deadline = T0 + limitMs;
  const prev = Tone.getContext();
  let timer = null;
  try {
    const ctx = new Tone.OfflineContext(2, dur, SR);
    Tone.setContext(ctx);
    const helpers = { checkTime: () => { if (now() > deadline) throw new Error('renderToneCode time limit of ' + limitMs + ' ms exceeded'); }, midiToHz: hz, dbToGain: dbToGain, sampleRate: SR };
    const fn = new AsyncFunction('Tone', 'context', 'duration', 'sampleRate', 'helpers', code);
    const timeout = new Promise((_, rej) => { timer = setTimeout(() => rej(new Error('renderToneCode time limit of ' + limitMs + ' ms exceeded')), limitMs); });
    const returned = await Promise.race([fn(Tone, ctx, dur, SR, helpers), timeout]);
    await Tone.loaded();
    let ab;
    if (returned && typeof returned.get === 'function' && returned.numberOfChannels) ab = returned.get();   // code used Tone.Offline itself and returned a ToneAudioBuffer
    else if (returned && returned.numberOfChannels && returned.getChannelData) ab = returned;
    else { const rendered = await Promise.race([ctx.render(false), timeout]); ab = rendered.get(); }
    clearTimeout(timer);
    const L = Float32Array.from(ab.getChannelData(0)), R = Float32Array.from(ab.getChannelData(Math.min(1, ab.numberOfChannels - 1)));
    const nm = opts.normalize === undefined ? 'lufs' : opts.normalize;
    const r = masterAndEncode({ L, R }, { outSec: L.length / ab.sampleRate, tailWrap: false, fadeIn: opts.fadeIn || 0.005, fadeOut: opts.fadeOut === undefined ? 0.02 : opts.fadeOut,
      normalize: nm === true ? 'lufs' : nm, targetLufs: opts.targetLufs || -14, ceiling: 0.95, peak: 0.9, gain: opts.gain || 1 });
    const res = publicResult(r, { timings: { totalMs: Math.round(now() - T0) } });
    deliver(res, { durationSec: res.durationSec, peak: res.peak, rms: res.rms, lufs: res.lufs, timings: res.timings, warnings: res.warnings }, opts);
    return res;
  } catch (e) {
    clearTimeout(timer);
    const msg = 'Tone code failed: ' + ((e && e.message) ? e.message : String(e));
    if (!(opts && opts.post === false)) host('postError', msg);
    throw new Error(msg);
  } finally { Tone.setContext(prev); }
}

/* ----------------------------------------------------------------------------------------------- exports */
/* only one render runs at a time (they share the SoundFont and the global Tone context); extra calls wait in line */
let queue = Promise.resolve();
const serial = fn => function () { const args = arguments, self_ = this; const run = () => fn.apply(self_, args); const p = queue.then(run, run); queue = p.catch(() => {}); return p; };
self.renderScore = serial(renderScore);
self.renderToneCode = serial(renderToneCode);
self.renderMidi = function (midiBase64, opts) { return self.renderScore(midiScore(midiBase64, opts), opts); };
self.validateScore = function (x) { return S.validateScore(x); };
self.AlphaAudioHarness = {
  version: VERSION, sampleRate: SR,
  ready: Promise.resolve(true),
  preloadSoundFont: () => loadSoundFont().then(() => ({ ok: true, loadMs: loadSoundFont.ms })),
  setSoundFont: ab => { sfBytes = ab instanceof ArrayBuffer ? ab : (ab && ab.buffer ? ab.buffer : ab); sfPromise = null; sfBank = null; },
  listInstruments: () => S.GM_NAMES.map((n, i) => ({ program: i, name: n })),
  listSynthPresets: () => Object.assign({}, S.SYNTH_PRESETS), listSfxPresets: () => Object.keys(SFX_LIB),
  formatValidation: S.formatValidation, zzfx: p => zzfxG(p, Math.random),
  encodeWavBase64: (L, R) => bytesToBase64(encodeWav16(L, R, SR)), measureLoudness: (L, R) => measureLoudness(L, R, SR),
  _internals: { renderSoundFont, renderToneGraph, renderSfx, masterAndEncode, zzfxG, SFX_LIB, limitInPlace, makeImpulse, mixSynthTracks, masterSection }   // for tests/debugging only
};
try { host('onReady', VERSION); } catch (e) {}
})();
