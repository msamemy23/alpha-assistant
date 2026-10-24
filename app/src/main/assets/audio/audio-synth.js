/* Alpha Audio - audio-synth.js
 * Small, fast, deterministic software synthesizer + effects written in plain JavaScript (no Web Audio graph, no AudioWorklet, no timers).
 * Voices render note-by-note straight into Float32Arrays, which is 10-50x faster than building a node graph of Tone.js synths on a phone.
 * Presets mirror the Tone.js preset names used in score files: synth:pluck/pad/bass/sub/lead/chiptune/chiptune-tri/chiptune-noise/bell/keys/organ/strings
 * and the drums kick/snare/hat/openhat/clap/tom/crash/drums.   (Tone.js versions of the same presets stay available with "engine":"tone".)
 * Exposes  AlphaSynth.{PRESETS, renderTrack, Reverb, Delay, compressor, eqRun, chorusRun, ...}.   Plain script, MIT-style licence same as the rest of the harness.
 */
(function (root) {
'use strict';
const TAU = Math.PI * 2;
const clamp = (v, lo, hi) => (v < lo ? lo : v > hi ? hi : v);
const midiHz = m => 440 * Math.pow(2, (m - 69) / 12);
const dbGain = d => Math.pow(10, d / 20);

/* ----------------------------------------------------------------------------------------------- biquads */
function biquadCoef(type, f, q, gDb, sr) {   // RBJ cookbook; returns [b0,b1,b2,a1,a2]
  const w0 = TAU * Math.min(f, sr * 0.45) / sr, cs = Math.cos(w0), sn = Math.sin(w0), alpha = sn / (2 * q), A = Math.pow(10, (gDb || 0) / 40);
  let b0, b1, b2, a0, a1, a2;
  if (type === 'lowpass') { b0 = (1 - cs) / 2; b1 = 1 - cs; b2 = b0; a0 = 1 + alpha; a1 = -2 * cs; a2 = 1 - alpha; }
  else if (type === 'highpass') { b0 = (1 + cs) / 2; b1 = -(1 + cs); b2 = b0; a0 = 1 + alpha; a1 = -2 * cs; a2 = 1 - alpha; }
  else if (type === 'bandpass') { b0 = alpha; b1 = 0; b2 = -alpha; a0 = 1 + alpha; a1 = -2 * cs; a2 = 1 - alpha; }
  else if (type === 'peaking') { b0 = 1 + alpha * A; b1 = -2 * cs; b2 = 1 - alpha * A; a0 = 1 + alpha / A; a1 = -2 * cs; a2 = 1 - alpha / A; }
  else if (type === 'lowshelf') { const sq = 2 * Math.sqrt(A) * alpha; b0 = A * ((A + 1) - (A - 1) * cs + sq); b1 = 2 * A * ((A - 1) - (A + 1) * cs); b2 = A * ((A + 1) - (A - 1) * cs - sq); a0 = (A + 1) + (A - 1) * cs + sq; a1 = -2 * ((A - 1) + (A + 1) * cs); a2 = (A + 1) + (A - 1) * cs - sq; }
  else { const sq = 2 * Math.sqrt(A) * alpha; b0 = A * ((A + 1) + (A - 1) * cs + sq); b1 = -2 * A * ((A - 1) + (A + 1) * cs); b2 = A * ((A + 1) + (A - 1) * cs - sq); a0 = (A + 1) - (A - 1) * cs + sq; a1 = 2 * ((A - 1) - (A + 1) * cs); a2 = (A + 1) - (A - 1) * cs - sq; }
  return [b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0];
}
function biquadRun(x, c, from, to) {   // in place on one channel
  const b0 = c[0], b1 = c[1], b2 = c[2], a1 = c[3], a2 = c[4]; let x1 = 0, x2 = 0, y1 = 0, y2 = 0;
  for (let i = from; i < to; i++) { const v = x[i], y = b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2; x2 = x1; x1 = v; y2 = y1; y1 = y; x[i] = y; }
}
function Filt(type, f, q, sr) { this.type = type; this.q = q || 0.707; this.sr = sr; this.x1 = this.x2 = this.y1 = this.y2 = 0; this.set(f); }
Filt.prototype.set = function (f) { const c = biquadCoef(this.type, f, this.q, 0, this.sr); this.b0 = c[0]; this.b1 = c[1]; this.b2 = c[2]; this.a1 = c[3]; this.a2 = c[4]; };
Filt.prototype.tick = function (v) { const y = this.b0 * v + this.b1 * this.x1 + this.b2 * this.x2 - this.a1 * this.y1 - this.a2 * this.y2; this.x2 = this.x1; this.x1 = v; this.y2 = this.y1; this.y1 = y; return y; };

/* ----------------------------------------------------------------------------------------------- envelope (attack linear, decay/release exponential) */
function Env(a, d, s, r, dur, sr, minTail) {
  this.A = Math.max(1, Math.round(a * sr)); this.s = s; this.kd = Math.exp(-4.6 / Math.max(1, d * sr)); this.kr = Math.exp(-4.6 / Math.max(1, r * sr));
  this.off = Math.max(this.A + 1, Math.round(dur * sr)); this.total = this.off + Math.round(Math.max(r, minTail || 0.02) * sr) + 256;
  this.i = 0; this.q = 1; this.lvl = 0; this.rel = 0;
}
Env.prototype.next = function () {
  const i = this.i++;
  if (i > this.total - 256) { return this.rel * this.kr * Math.max(0, (this.total - i)) / 256; }   // short fade so a cut tail never clicks
  if (i < this.A) return (this.lvl = i / this.A);
  if (i < this.off) { this.q *= this.kd; return (this.lvl = this.s + (1 - this.s) * this.q); }
  if (i === this.off) this.rel = this.lvl;
  this.rel *= this.kr; return this.rel;
};
function envParams(ov, a, d, s, r) { const e = (ov && ov.env) || {}; return [e.attack === undefined ? a : e.attack, e.decay === undefined ? d : e.decay, e.sustain === undefined ? s : e.sustain, e.release === undefined ? r : e.release]; }

/* ----------------------------------------------------------------------------------------------- oscillators */
function blep(t, dt) { if (t < dt) { t /= dt; return t + t - t * t - 1; } if (t > 1 - dt) { t = (t - 1) / dt; return t * t + t + t + 1; } return 0; }
function sawBL(p, dt) { return 2 * p - 1 - blep(p, dt); }
function pulseBL(p, dt, w) { let v = p < w ? 1 : -1; v += blep(p, dt); let q = p - w; if (q < 0) q += 1; v -= blep(q, dt); return v; }
function triangle(p) { return 4 * Math.abs(p - 0.5) - 1; }
function wave(kind, p, dt, w) {
  switch (kind) { case 'sine': return Math.sin(TAU * p); case 'triangle': return triangle(p); case 'saw': case 'sawtooth': return sawBL(p, dt); case 'pulse': return pulseBL(p, dt, w || 0.25); default: return pulseBL(p, dt, 0.5); }
}
const cents = c => Math.pow(2, c / 1200);
const panGains = p => { const a = (clamp(p, -1, 1) + 1) * Math.PI / 4; return [Math.cos(a), Math.sin(a)]; };   // constant power, centre = 0.707 each

/* A generic subtractive voice: N detuned oscillators -> optional low-pass (static or enveloped) -> amp envelope.  Used by pad/lead/strings/bass/chiptune. */
function subVoice(L, R, s, f, vel, dur, sr, rnd, o) {
  const n = o.osc.length, env = new Env(o.a, o.d, o.s, o.r, dur, sr), len = Math.min(L.length - s, env.total);
  const ph = new Float64Array(n), inc = new Float64Array(n), gl = new Float64Array(n), gr = new Float64Array(n);
  for (let k = 0; k < n; k++) { const sp = o.osc[k]; inc[k] = f * cents(sp.det || 0) / sr; ph[k] = (sp.ph === undefined ? rnd() : sp.ph); const pg = panGains(sp.pan || 0); gl[k] = pg[0] * (sp.g === undefined ? 1 : sp.g); gr[k] = pg[1] * (sp.g === undefined ? 1 : sp.g); }
  const lp = o.lp ? new Filt('lowpass', o.lp, o.q || 0.8, sr) : null;
  const amp = o.gain * (0.15 + 0.85 * vel) / Math.sqrt(n);
  let vibPh = 0; const vibInc = (o.vibHz || 5.5) / sr, vibStart = (o.vibDelay || 0) * sr;
  for (let i = 0; i < len; i++) {
    const e = env.next(); let vib = 1;
    if (o.vib && i > vibStart) { vibPh += vibInc; vib = 1 + o.vib * 0.000577 * Math.sin(TAU * vibPh) * Math.min(1, (i - vibStart) / (0.3 * sr)); }
    let sl = 0, sR = 0;
    for (let k = 0; k < n; k++) {
      let p = ph[k] + inc[k] * vib; if (p >= 1) p -= 1; ph[k] = p;
      const kind = o.osc[k].t || o.t, pw = o.osc[k].w || o.w;
      const w = wave(kind, p, inc[k], pw) - (kind === 'pulse' ? 2 * (pw || 0.25) - 1 : 0);   // pulse waves have a DC offset of (2*width-1)
      sl += w * gl[k]; sR += w * gr[k];
    }
    if (lp) {
      if (o.fenv && (i & 31) === 0) lp.set(o.lp + o.fenv * Math.exp(-i / (sr * (o.ftau || 0.12))));
      const m = lp.tick((sl + sR) * 0.5); const dif = (sl - sR) * 0.5; sl = m + dif * 0.6; sR = m - dif * 0.6;
    }
    L[s + i] += sl * e * amp; R[s + i] += sR * e * amp;
  }
}
const UNI = (t, dets, spread) => dets.map((d, k) => ({ t: t, det: d, pan: dets.length > 1 ? spread * (2 * k / (dets.length - 1) - 1) : 0 }));

/* Karplus-Strong plucked string with tuned fractional delay */
function pluckVoice(L, R, s, f, vel, dur, sr, rnd, ov, o) {
  o = o || {};
  const D = sr / f, a = o.bright || 0.5, lpDelay = (1 - a) / a, n0 = Math.max(2, Math.floor(D - lpDelay - 0.5)), frac = D - lpDelay - n0, c = (1 - frac) / (1 + frac);
  const t60 = clamp((o.t60 || 2.4) * Math.pow(261.6 / f, 0.45), 0.4, 5), g = Math.pow(10, -3 * D / (sr * t60));
  const buf = new Float32Array(n0); let lp = 0; const br = 0.3 + 0.6 * vel;
  for (let i = 0; i < n0; i++) { lp += br * ((rnd() * 2 - 1) - lp); buf[i] = lp; }
  const pk = Math.max(2, Math.round(n0 * (o.pick || 0.18))); for (let i = n0 - 1; i >= pk; i--) buf[i] -= buf[i - pk] * 0.7;
  let mx = 1e-6; for (let i = 0; i < n0; i++) mx = Math.max(mx, Math.abs(buf[i])); const sc = (o.gain || 1) * (0.2 + 0.8 * vel) / mx;
  const offI = Math.round(Math.max(dur, 0.04) * sr), total = Math.min(L.length - s, offI + Math.round(0.6 * sr));
  let idx = 0, y1 = 0, apx = 0, apy = 0, aa = a, gg = g;
  for (let i = 0; i < total; i++) {
    if (i === offI) { aa = 0.22; gg = 0.9; }
    const x = buf[idx]; y1 = aa * x + (1 - aa) * y1; const ap = c * (y1 - apy) + apx; apx = y1; apy = ap; buf[idx] = ap * gg; if (++idx === n0) idx = 0;
    const out = x * sc; L[s + i] += out; R[s + i] += out;
  }
}

/* FM bell / electric piano style voices (2-operator FM + one extra partial) */
function fmVoice(L, R, s, f, vel, dur, sr, rnd, ov, o) {
  const total = Math.min(L.length - s, Math.round(o.length * sr)), ka = Math.exp(-1 / (o.tauA * sr)), km = Math.exp(-1 / (o.tauM * sr)), k2 = Math.exp(-1 / ((o.tau2 || 0.5) * sr));
  const amp = o.gain * (0.15 + 0.85 * vel), ic = f / sr, im = f * o.ratio / sr, i2 = f * (o.r2 || 2.76) / sr, offI = Math.round(dur * sr), kd = o.damp ? Math.exp(-1 / (o.damp * sr)) : 1;
  let pc = 0, pm = 0, p2 = 0, a = 1, mi = o.index, a2 = o.w2 || 0, dm = 1;
  for (let i = 0; i < total; i++) {
    pc += ic; pm += im; p2 += i2; if (pc > 1) pc -= 1; if (pm > 1) pm -= 1; if (p2 > 1) p2 -= 1;
    let y = Math.sin(TAU * pc + (mi + (o.idx0 || 0)) * Math.sin(TAU * pm));
    if (a2) y += a2 * Math.sin(TAU * p2);
    if (i > offI) dm *= kd;
    const out = y * a * dm * amp * (i > total - 400 ? (total - i) / 400 : 1); L[s + i] += out; R[s + i] += out;
    a *= ka; mi *= km; a2 *= k2;
  }
}

/* additive organ */
function organVoice(L, R, s, f, vel, dur, sr, rnd, ov) {
  const e = envParams(ov, 0.012, 0.05, 1, 0.09), env = new Env(e[0], e[1], e[2], e[3], dur, sr), len = Math.min(L.length - s, env.total);
  const parts = [1, 2, 3, 4, 6, 8], amps = [1, 0.7, 0.5, 0.35, 0.2, 0.12], ph = [0, 0, 0, 0, 0, 0], inc = parts.map(p => f * p / sr);
  const amp = 0.28 * (0.2 + 0.8 * vel);
  for (let i = 0; i < len; i++) {
    let y = 0; for (let k = 0; k < 6; k++) { if (inc[k] < 0.45) { ph[k] += inc[k]; if (ph[k] > 1) ph[k] -= 1; y += amps[k] * Math.sin(TAU * ph[k]); } }
    const out = y * env.next() * amp; L[s + i] += out; R[s + i] += out;
  }
}

/* ----------------------------------------------------------------------------------------------- drums (mono, centred) */
let DRUM_G = 1;   // per-drum level trim used by synth:drums (set around each hit)
function addMono(L, R, s, i, v) { v *= DRUM_G; L[s + i] += v; R[s + i] += v; }
function kickVoice(L, R, s, f, vel, dur, sr, rnd, ov) {
  const len = Math.min(L.length - s, Math.round(0.5 * sr)), amp = 0.9 * (0.3 + 0.7 * vel); let ph = 0;
  for (let i = 0; i < len; i++) {
    const t = i / sr, fr = 46 + 120 * Math.exp(-t / 0.028); ph += fr / sr;
    let y = Math.sin(TAU * ph) * Math.exp(-t / 0.13); if (i < 120) y += (rnd() * 2 - 1) * 0.25 * (1 - i / 120);
    addMono(L, R, s, i, y * amp * (i > len - 200 ? (len - i) / 200 : 1));
  }
}
function snareVoice(L, R, s, f, vel, dur, sr, rnd, ov) {
  const len = Math.min(L.length - s, Math.round(0.35 * sr)), amp = 0.75 * (0.3 + 0.7 * vel), hp = new Filt('highpass', 1200, 0.7, sr), bp = new Filt('bandpass', 4500, 0.6, sr); let ph = 0;
  for (let i = 0; i < len; i++) {
    const t = i / sr, n = hp.tick(rnd() * 2 - 1), nz = n * 0.7 + bp.tick(n) * 0.5;
    ph += (185 + 60 * Math.exp(-t / 0.02)) / sr;
    const y = nz * Math.exp(-t / 0.07) + Math.sin(TAU * ph) * 0.55 * Math.exp(-t / 0.05);
    addMono(L, R, s, i, y * amp * (i > len - 200 ? (len - i) / 200 : 1));
  }
}
const METAL = [205.3, 304.4, 369.6, 522.7, 540.0, 800.0];
function metalVoice(L, R, s, vel, tau, hpf, sr, rnd, gain, base, total) {
  const len = Math.min(L.length - s, Math.round(total * sr)), hp = new Filt('highpass', hpf, 0.8, sr), hp2 = new Filt('highpass', hpf, 0.8, sr), amp = gain * (0.3 + 0.7 * vel), ph = new Float64Array(6);
  for (let i = 0; i < len; i++) {
    let y = 0; for (let k = 0; k < 6; k++) { ph[k] += METAL[k] * base / sr; if (ph[k] >= 1) ph[k] -= 1; y += ph[k] < 0.5 ? 1 : -1; }
    y = hp2.tick(hp.tick(y / 6 + (rnd() * 2 - 1) * 0.25));
    addMono(L, R, s, i, y * Math.exp(-i / (tau * sr)) * amp * (i > len - 200 ? (len - i) / 200 : 1));
  }
}
function clapVoice(L, R, s, f, vel, dur, sr, rnd, ov) {
  const len = Math.min(L.length - s, Math.round(0.3 * sr)), bp = new Filt('bandpass', 1300, 0.9, sr), hp = new Filt('highpass', 600, 0.7, sr), amp = 1.1 * (0.3 + 0.7 * vel);
  const bursts = [0, 0.011, 0.023], b = bursts.map(x => Math.round(x * sr));
  for (let i = 0; i < len; i++) {
    const n = bp.tick(hp.tick(rnd() * 2 - 1)); let e = Math.exp(-Math.max(0, i - b[2]) / (0.07 * sr));
    for (let k = 0; k < 2; k++) if (i >= b[k] && i < b[k + 1]) e = Math.exp(-(i - b[k]) / (0.006 * sr));
    if (i < b[0]) e = 0;
    addMono(L, R, s, i, n * e * amp * (i > len - 200 ? (len - i) / 200 : 1));
  }
}
function tomVoice(L, R, s, f, vel, dur, sr, rnd, ov, fr0) {
  const len = Math.min(L.length - s, Math.round(0.45 * sr)), amp = 0.85 * (0.3 + 0.7 * vel); let ph = 0; const f0 = fr0 || 110;
  for (let i = 0; i < len; i++) { const t = i / sr; ph += (f0 * (1 + 0.9 * Math.exp(-t / 0.04))) / sr; addMono(L, R, s, i, Math.sin(TAU * ph) * Math.exp(-t / 0.14) * amp * (i > len - 200 ? (len - i) / 200 : 1)); }
}
function clickVoice(L, R, s, f, vel, dur, sr, rnd, ov, fr0) {
  const len = Math.min(L.length - s, Math.round(0.08 * sr)), amp = 0.8 * (0.3 + 0.7 * vel); let ph = 0; const f0 = fr0 || 900;
  for (let i = 0; i < len; i++) { const t = i / sr; ph += f0 / sr; addMono(L, R, s, i, (Math.sin(TAU * ph) * 0.8 + (rnd() * 2 - 1) * 0.3) * Math.exp(-t / 0.012) * amp); }
}
function shakerVoice(L, R, s, f, vel, dur, sr, rnd, ov) {
  const len = Math.min(L.length - s, Math.round(0.12 * sr)), hp = new Filt('highpass', 5500, 0.7, sr), amp = 0.6 * (0.3 + 0.7 * vel);
  for (let i = 0; i < len; i++) { const t = i / sr, e = Math.min(1, t / 0.012) * Math.exp(-t / 0.035); addMono(L, R, s, i, hp.tick(rnd() * 2 - 1) * e * amp); }
}
function cowbellVoice(L, R, s, f, vel, dur, sr, rnd, ov) {
  const len = Math.min(L.length - s, Math.round(0.35 * sr)), bp = new Filt('bandpass', 1000, 1.5, sr), amp = 0.55 * (0.3 + 0.7 * vel); let p1 = 0, p2 = 0;
  for (let i = 0; i < len; i++) { p1 += 540 / sr; p2 += 800 / sr; if (p1 >= 1) p1 -= 1; if (p2 >= 1) p2 -= 1; const y = bp.tick((p1 < 0.5 ? 1 : -1) + (p2 < 0.5 ? 1 : -1)); addMono(L, R, s, i, y * (0.6 * Math.exp(-i / (0.012 * sr)) + 0.4 * Math.exp(-i / (0.12 * sr))) * amp); }
}
function chipNoiseVoice(L, R, s, f, vel, dur, sr, rnd, ov) {
  const len = Math.min(L.length - s, Math.round(0.12 * sr)), amp = 0.5 * (0.3 + 0.7 * vel), clock = Math.round(sr / 9000); let v = 0;
  for (let i = 0; i < len; i++) { if (i % clock === 0) v = rnd() < 0.5 ? 1 : -1; const e = i < 40 ? i / 40 : Math.exp(-(i - 40) / (0.035 * sr)); addMono(L, R, s, i, v * e * amp * (i > len - 100 ? (len - i) / 100 : 1)); }
}
/* GM drum note -> voice, used by synth:drums */
function drumKit(L, R, s, n, vel, dur, sr, rnd, ov) {
  const db = (n === 35 || n === 36) ? -9.1 : (n === 38 || n === 40) ? -11 : n === 37 ? -8 : n === 39 ? -4.1 : (n === 42 || n === 44) ? -5 : n === 46 ? -3.5 : (n >= 41 && n <= 50 && n !== 42 && n !== 44 && n !== 46 && n !== 49) ? -8.3
    : (n === 49 || n === 52 || n === 55 || n === 57) ? -3.7 : (n === 51 || n === 53 || n === 59) ? -3 : (n === 54 || n === 69 || n === 70) ? -3 : n === 56 ? -3 : -8;
  DRUM_G = dbGain(db); try { drumKitHit(L, R, s, n, vel, dur, sr, rnd, ov); } finally { DRUM_G = 1; }
}
function drumKitHit(L, R, s, n, vel, dur, sr, rnd, ov) {
  if (n === 35 || n === 36) kickVoice(L, R, s, 0, vel, dur, sr, rnd, ov);
  else if (n === 38 || n === 40) snareVoice(L, R, s, 0, vel, dur, sr, rnd, ov);
  else if (n === 37) clickVoice(L, R, s, 0, vel, dur, sr, rnd, ov, 1700);
  else if (n === 39) clapVoice(L, R, s, 0, vel, dur, sr, rnd, ov);
  else if (n === 42 || n === 44) metalVoice(L, R, s, vel, 0.025, 7000, sr, rnd, 0.55, 1, 0.12);
  else if (n === 46) metalVoice(L, R, s, vel, 0.16, 7000, sr, rnd, 0.5, 1, 0.7);
  else if (n === 41 || n === 43 || n === 45 || n === 47 || n === 48 || n === 50) tomVoice(L, R, s, 0, vel, dur, sr, rnd, ov, 70 * Math.pow(2, (n - 41) / 12 * 1.1));
  else if (n === 49 || n === 52 || n === 55 || n === 57) metalVoice(L, R, s, vel, 0.55, 5000, sr, rnd, 0.5, 1.15, 1.8);
  else if (n === 51 || n === 53 || n === 59) metalVoice(L, R, s, vel, 0.22, 6500, sr, rnd, 0.35, 1.3, 0.9);
  else if (n === 54 || n === 69 || n === 70) shakerVoice(L, R, s, 0, vel, dur, sr, rnd, ov);
  else if (n === 56) cowbellVoice(L, R, s, 0, vel, dur, sr, rnd, ov);
  else clickVoice(L, R, s, 0, vel, dur, sr, rnd, ov, 700 + (n % 12) * 60);
}

/* ----------------------------------------------------------------------------------------------- preset table
   trim = dB gain so every preset sits near the same loudness as a General MIDI piano at the same velocity. */
function oscOverride(ov, dflt) { return ov && ov.osc ? ov.osc : dflt; }
const PRESETS = {
  pluck:  { trim: -3.4,   v: (L, R, s, f, vel, dur, sr, rnd, ov) => pluckVoice(L, R, s, f, vel, dur, sr, rnd, ov, { gain: 0.9, bright: 0.55, t60: 2.4 }) },
  pad:    { trim: -15.3,  v: (L, R, s, f, vel, dur, sr, rnd, ov) => { const e = envParams(ov, 0.75, 0.5, 0.85, 1.9); subVoice(L, R, s, f, vel, dur, sr, rnd, { osc: UNI(oscOverride(ov, 'saw'), [-13, -4, 5, 14], 0.8), a: e[0], d: e[1], s: e[2], r: e[3], gain: 0.9, lp: 2000, q: 0.6 }); } },
  bass:   { trim: -15.2,  v: (L, R, s, f, vel, dur, sr, rnd, ov) => { const e = envParams(ov, 0.004, 0.3, 0.6, 0.12); subVoice(L, R, s, f, vel, dur, sr, rnd, { osc: [{ t: oscOverride(ov, 'saw'), det: 0, g: 0.8 }, { t: 'sine', det: 0, g: 0.9 }], a: e[0], d: e[1], s: e[2], r: e[3], gain: 1.5, lp: 260, fenv: 1300, ftau: 0.1, q: 1.6 }); } },
  sub:    { trim: -18.3,  v: (L, R, s, f, vel, dur, sr, rnd, ov) => { const e = envParams(ov, 0.01, 0.25, 0.85, 0.15); subVoice(L, R, s, f, vel, dur, sr, rnd, { osc: [{ t: 'sine', g: 1 }, { t: 'sine', det: 1200, g: 0.12 }], a: e[0], d: e[1], s: e[2], r: e[3], gain: 1.3 }); } },
  lead:   { trim: -14.5,  v: (L, R, s, f, vel, dur, sr, rnd, ov) => { const e = envParams(ov, 0.012, 0.16, 0.65, 0.28); subVoice(L, R, s, f, vel, dur, sr, rnd, { osc: UNI(oscOverride(ov, 'saw'), [-7, 7], 0.5), a: e[0], d: e[1], s: e[2], r: e[3], gain: 0.95, lp: 5200, q: 0.7, vib: 14, vibDelay: 0.15 }); } },
  chiptune: { trim: -15.6, v: (L, R, s, f, vel, dur, sr, rnd, ov) => { const e = envParams(ov, 0.002, 0.09, 0.55, 0.05), k = ov && ov.osc ? ov.osc : 'pulse'; subVoice(L, R, s, f, vel, dur, sr, rnd, { osc: [{ t: k, w: 0.25, ph: 0 }], a: e[0], d: e[1], s: e[2], r: e[3], gain: 0.8 }); } },
  'chiptune-tri': { trim: -20.5, v: (L, R, s, f, vel, dur, sr, rnd, ov) => {
      const e = envParams(ov, 0.002, 0.05, 0.95, 0.04), env = new Env(e[0], e[1], e[2], e[3], dur, sr), len = Math.min(L.length - s, env.total), amp = 0.9 * (0.3 + 0.7 * vel); let p = 0;
      for (let i = 0; i < len; i++) { p += f / sr; if (p >= 1) p -= 1; const q = Math.floor((triangle(p) + 1) * 8 + 0.5) / 8 - 1; addMono(L, R, s, i, q * env.next() * amp); } } },
  'chiptune-noise': { trim: -6.6, v: chipNoiseVoice },
  bell:   { trim: -17,   v: (L, R, s, f, vel, dur, sr, rnd, ov) => fmVoice(L, R, s, f, vel, dur, sr, rnd, ov, { gain: 0.55, ratio: 3.5, index: 2.6, idx0: 0.15, tauA: 0.9, tauM: 0.35, length: 2.6, w2: 0.35, r2: 2.76, tau2: 0.5 }) },
  keys:   { trim: -15.2,   v: (L, R, s, f, vel, dur, sr, rnd, ov) => fmVoice(L, R, s, f, vel, dur, sr, rnd, ov, { gain: 0.6, ratio: 1, index: 1.8, idx0: 0.25, tauA: 0.9, tauM: 0.22, length: Math.min(3.5, dur + 1.2), damp: 0.25, w2: 0.1, r2: 7, tau2: 0.08 }) },
  organ:  { trim: -15.4,  v: organVoice },
  strings: { trim: -14, v: (L, R, s, f, vel, dur, sr, rnd, ov) => { const e = envParams(ov, 0.35, 0.25, 0.85, 0.7); subVoice(L, R, s, f, vel, dur, sr, rnd, { osc: UNI(oscOverride(ov, 'saw'), [-11, -4, 4, 11], 0.85), a: e[0], d: e[1], s: e[2], r: e[3], gain: 0.95, lp: 3000, q: 0.6, vib: 8, vibDelay: 0.35 }); } },
  kick:   { trim: -9.1, drum: true, v: kickVoice },
  snare:  { trim: -11, drum: true, v: snareVoice },
  hat:    { trim: -5, drum: true, v: (L, R, s, f, vel, dur, sr, rnd, ov) => metalVoice(L, R, s, vel, 0.025, 7000, sr, rnd, 0.55, 1, 0.12) },
  openhat: { trim: -3.5, drum: true, v: (L, R, s, f, vel, dur, sr, rnd, ov) => metalVoice(L, R, s, vel, 0.16, 7000, sr, rnd, 0.5, 1, 0.7) },
  clap:   { trim: -4.1, drum: true, v: clapVoice },
  tom:    { trim: -8.3, drum: true, v: (L, R, s, f, vel, dur, sr, rnd, ov) => tomVoice(L, R, s, f, vel, dur, sr, rnd, ov, clamp(f * 0.5, 60, 400)) },
  crash:  { trim: -3.7, drum: true, v: (L, R, s, f, vel, dur, sr, rnd, ov) => metalVoice(L, R, s, vel, 0.55, 5000, sr, rnd, 0.5, 1.15, 1.8) },
  drums:  { trim: 0, drum: true, kit: true, v: drumKit }
};

/* render all notes of one track into stereo buffers L,R (already zeroed).  events: [{note,t,d,vel}]; the preset trim (dB) is applied to the whole track afterwards. */
function renderTrack(tr, L, R, sr, rnd) {
  const def = PRESETS[tr.preset]; if (!def) throw new Error('unknown synth preset ' + tr.preset);
  const ov = { env: tr.env, osc: tr.osc }; let last = 0;
  tr.events.forEach(n => {
    const s = Math.round(n.t * sr); if (s >= L.length) return;
    def.v(L, R, s, def.kit ? n.note : midiHz(n.note), n.vel, n.d, sr, rnd, ov);
    last = Math.max(last, s + Math.round((n.d + 4) * sr));
  });
  const g = dbGain(def.trim), end = Math.min(L.length, last);
  if (g !== 1) for (let i = 0; i < end; i++) { L[i] *= g; R[i] *= g; }
}

/* ----------------------------------------------------------------------------------------------- effects */
function eqRun(L, R, eq, sr, low, mid, high) {   // low/high shelves at 250 Hz / 4 kHz, peaking at 1 kHz
  const bands = [];
  if (low) bands.push(biquadCoef('lowshelf', 250, 0.707, low, sr)); if (mid) bands.push(biquadCoef('peaking', 1000, 0.8, mid, sr)); if (high) bands.push(biquadCoef('highshelf', 4000, 0.707, high, sr));
  bands.forEach(c => { biquadRun(L, c, 0, L.length); biquadRun(R, c, 0, R.length); });
}
function distortRun(L, R, amount) { const k = 1 + amount * 12, nrm = 1 / Math.tanh(k); for (let i = 0; i < L.length; i++) { L[i] = Math.tanh(L[i] * k) * nrm; R[i] = Math.tanh(R[i] * k) * nrm; } }
function chorusRun(L, R, amount, sr) {   // two modulated delays (stereo), mixed at `amount`
  const n = L.length, size = Math.ceil(0.05 * sr), bl = new Float32Array(size), br = new Float32Array(size); let w = 0, lfo = 0;
  const rate = 0.8 / sr, base = 0.016 * sr, depth = 0.004 * sr, mixW = clamp(amount, 0, 1) * 0.6, mixD = 1 - mixW * 0.5;
  for (let i = 0; i < n; i++) {
    bl[w] = L[i]; br[w] = R[i]; lfo += rate; if (lfo >= 1) lfo -= 1;
    const dl = base + depth * Math.sin(TAU * lfo), dr = base + depth * Math.sin(TAU * (lfo + 0.5));
    let rl = w - dl; if (rl < 0) rl += size; let rr = w - dr; if (rr < 0) rr += size;
    const il = rl | 0, fl = rl - il, ir = rr | 0, fr = rr - ir, jl = il + 1 === size ? 0 : il + 1, jr = ir + 1 === size ? 0 : ir + 1;
    const wl = bl[il] * (1 - fl) + bl[jl] * fl, wr = br[ir] * (1 - fr) + br[jr] * fr;
    L[i] = L[i] * mixD + wl * mixW; R[i] = R[i] * mixD + wr * mixW;
    if (++w === size) w = 0;
  }
}
/* feed-forward stereo-linked compressor.  threshold dB, ratio, attack/release seconds, knee dB.  Returns max gain reduction (dB). */
function compressor(L, R, sr, thr, ratio, att, rel, knee) {
  const n = L.length, ka = Math.exp(-1 / (Math.max(0.0005, att) * sr)), kr = Math.exp(-1 / (Math.max(0.005, rel) * sr)); let env = 0, gr = 0, maxGr = 0; knee = knee || 6;
  for (let i = 0; i < n; i++) {
    const x = Math.max(Math.abs(L[i]), Math.abs(R[i])); env = x > env ? ka * env + (1 - ka) * x : kr * env + (1 - kr) * x;
    const db = 20 * Math.log10(env + 1e-9), over = db - thr; let red = 0;
    if (2 * over < -knee) red = 0; else if (2 * Math.abs(over) <= knee) red = (1 - 1 / ratio) * Math.pow(over + knee / 2, 2) / (2 * knee); else red = (1 - 1 / ratio) * over;
    gr = red > gr ? ka * gr + (1 - ka) * red : kr * gr + (1 - kr) * red; if (gr > maxGr) maxGr = gr;
    const g = Math.pow(10, -gr / 20); L[i] *= g; R[i] *= g;
  }
  return maxGr;
}

/* 8-line feedback delay network reverb (Hadamard mixing, per-line damping), pre-delay, 3 input diffusers, stereo output.
   input: mono Float32Array; the wet signal is ADDED into outL/outR.  decay = RT60 seconds, damping 0..1 (1 = dark). */
function reverbRun(input, outL, outR, sr, decay, preDelay, damping, wetGain) {
  const N = 8, lens = [1087, 1283, 1481, 1699, 1907, 2131, 2371, 2609].map(x => Math.round(x * sr / 44100));
  const bufs = lens.map(l => new Float32Array(l)), idx = new Int32Array(N), lpv = new Float64Array(N), yk = new Float64Array(N), tmp = new Float64Array(N);
  const gain = lens.map(l => Math.pow(10, -3 * l / (sr * Math.max(0.1, decay)))), dampA = 1 - clamp(damping, 0, 1) * 0.8;
  const pre = Math.max(1, Math.round(preDelay * sr)), pbuf = new Float32Array(pre); let pi = 0;
  const apLen = [142, 107, 79].map(x => Math.round(x * sr / 44100)), apB = apLen.map(l => new Float32Array(l)), apI = new Int32Array(3);
  const n = input.length, sc = wetGain === undefined ? 1 : wetGain, H = 1 / Math.sqrt(8), inW = [0.5, -0.5, 0.5, -0.5, 0.5, -0.5, 0.5, -0.5];
  for (let i = 0; i < n; i++) {
    let x = pbuf[pi]; pbuf[pi] = input[i]; if (++pi === pre) pi = 0;
    for (let k = 0; k < 3; k++) { const b = apB[k], j = apI[k], d = b[j], v = x + 0.6 * d; b[j] = v; x = d - 0.6 * v; if (++apI[k] === apLen[k]) apI[k] = 0; }
    for (let k = 0; k < N; k++) { lpv[k] += dampA * (bufs[k][idx[k]] - lpv[k]); yk[k] = lpv[k] * gain[k]; tmp[k] = yk[k]; }
    for (let len = 1; len < N; len <<= 1) for (let a = 0; a < N; a += len << 1) for (let b = a; b < a + len; b++) { const u = tmp[b], v = tmp[b + len]; tmp[b] = u + v; tmp[b + len] = u - v; }
    for (let k = 0; k < N; k++) { bufs[k][idx[k]] = tmp[k] * H + x * inW[k]; if (++idx[k] === lens[k]) idx[k] = 0; }
    outL[i] += (yk[0] - yk[2] + yk[4] - yk[6]) * 0.5 * sc; outR[i] += (yk[1] - yk[3] + yk[5] - yk[7]) * 0.5 * sc;
  }
}
/* stereo ping-pong feedback delay.  input: mono; wet is ADDED into outL/outR. */
function delayRun(input, outL, outR, sr, time, feedback, wetGain) {
  const size = Math.max(2, Math.round(time * sr)), bl = new Float32Array(size), br = new Float32Array(size), n = input.length, fb = clamp(feedback, 0, 0.92), g = wetGain === undefined ? 1 : wetGain;
  let w = 0, lpl = 0, lpr = 0;
  for (let i = 0; i < n; i++) {
    const yl = bl[w], yr = br[w]; lpl += 0.55 * (yl - lpl); lpr += 0.55 * (yr - lpr);
    bl[w] = input[i] + fb * lpr; br[w] = fb * lpl; outL[i] += yl * g; outR[i] += yr * g; if (++w === size) w = 0;
  }
}

root.AlphaSynth = { PRESETS, renderTrack, biquadCoef, biquadRun, eqRun, distortRun, chorusRun, compressor, reverbRun, delayRun, midiHz, dbGain, panGains, Filt };
})(typeof self !== 'undefined' ? self : this);
