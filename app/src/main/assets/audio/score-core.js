/* Alpha Audio - score-core.js
 * Pure JavaScript (no DOM, no audio): score parsing, validation and compilation to a timed event plan.
 * Works in the WebView harness, in a browser tab and in Node.  Exposes  AlphaScore.{validateScore, compileScore, ...}
 * Everything between the VALIDATOR BEGIN / END markers is also printed at the end of SCORE-SCHEMA.md.
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.AlphaScore = factory();
})(typeof self !== 'undefined' ? self : this, function () {
'use strict';

/* ==== VALIDATOR BEGIN ==== */
const SCORE_VERSION = 1;
const LIMITS = { tracks: 24, notes: 12000, durationSec: 120, tempoMin: 20, tempoMax: 300 };

const GM_NAMES = ['Acoustic Grand Piano','Bright Acoustic Piano','Electric Grand Piano','Honky-tonk Piano','Electric Piano 1','Electric Piano 2','Harpsichord','Clavinet',
'Celesta','Glockenspiel','Music Box','Vibraphone','Marimba','Xylophone','Tubular Bells','Dulcimer','Drawbar Organ','Percussive Organ','Rock Organ','Church Organ',
'Reed Organ','Accordion','Harmonica','Tango Accordion','Acoustic Guitar (nylon)','Acoustic Guitar (steel)','Electric Guitar (jazz)','Electric Guitar (clean)',
'Electric Guitar (muted)','Overdriven Guitar','Distortion Guitar','Guitar Harmonics','Acoustic Bass','Electric Bass (finger)','Electric Bass (pick)','Fretless Bass',
'Slap Bass 1','Slap Bass 2','Synth Bass 1','Synth Bass 2','Violin','Viola','Cello','Contrabass','Tremolo Strings','Pizzicato Strings','Orchestral Harp','Timpani',
'String Ensemble 1','String Ensemble 2','Synth Strings 1','Synth Strings 2','Choir Aahs','Voice Oohs','Synth Voice','Orchestra Hit','Trumpet','Trombone','Tuba',
'Muted Trumpet','French Horn','Brass Section','Synth Brass 1','Synth Brass 2','Soprano Sax','Alto Sax','Tenor Sax','Baritone Sax','Oboe','English Horn','Bassoon',
'Clarinet','Piccolo','Flute','Recorder','Pan Flute','Blown Bottle','Shakuhachi','Whistle','Ocarina','Lead 1 (square)','Lead 2 (sawtooth)','Lead 3 (calliope)',
'Lead 4 (chiff)','Lead 5 (charang)','Lead 6 (voice)','Lead 7 (fifths)','Lead 8 (bass + lead)','Pad 1 (new age)','Pad 2 (warm)','Pad 3 (polysynth)','Pad 4 (choir)',
'Pad 5 (bowed)','Pad 6 (metallic)','Pad 7 (halo)','Pad 8 (sweep)','FX 1 (rain)','FX 2 (soundtrack)','FX 3 (crystal)','FX 4 (atmosphere)','FX 5 (brightness)',
'FX 6 (goblins)','FX 7 (echoes)','FX 8 (sci-fi)','Sitar','Banjo','Shamisen','Koto','Kalimba','Bagpipe','Fiddle','Shanai','Tinkle Bell','Agogo','Steel Drums',
'Woodblock','Taiko Drum','Melodic Tom','Synth Drum','Reverse Cymbal','Guitar Fret Noise','Breath Noise','Seashore','Bird Tweet','Telephone Ring','Helicopter',
'Applause','Gunshot'];

const GM_ALIASES = { 'piano':0,'grand piano':0,'bright piano':1,'electric piano':4,'e piano':4,'epiano':4,'rhodes':4,'wurlitzer':5,'organ':16,'church organ':19,'pipe organ':19,
'guitar':25,'acoustic guitar':25,'nylon guitar':24,'classical guitar':24,'steel guitar':25,'steel string guitar':25,'electric guitar':27,'clean guitar':27,'jazz guitar':26,
'muted guitar':28,'overdrive guitar':29,'overdriven guitar':29,'distortion guitar':30,'distorted guitar':30,'bass':33,'electric bass':33,'finger bass':33,'pick bass':34,
'fretless':35,'acoustic bass':32,'upright bass':32,'double bass':32,'slap bass':36,'synth bass':38,'violin':40,'viola':41,'cello':42,'contrabass':43,'strings':48,
'string ensemble':48,'string section':48,'pizzicato':45,'harp':46,'timpani':47,'choir':52,'choir aahs':52,'voice':53,'oohs':53,'trumpet':56,'trombone':57,'tuba':58,
'muted trumpet':59,'horn':60,'french horn':60,'brass':61,'brass section':61,'sax':65,'saxophone':65,'soprano sax':64,'alto sax':65,'tenor sax':66,'baritone sax':67,
'oboe':68,'bassoon':70,'clarinet':71,'piccolo':72,'flute':73,'recorder':74,'pan flute':75,'panflute':75,'whistle':78,'ocarina':79,'lead':80,'square lead':80,
'saw lead':81,'sawtooth lead':81,'synth lead':81,'pad':89,'synth pad':89,'warm pad':89,'new age pad':88,'polysynth':90,'choir pad':91,'halo pad':94,'sweep pad':95,
'bell':14,'bells':14,'tubular bell':14,'tubular bells':14,'church bells':14,'glockenspiel':9,'glock':9,'celesta':8,'music box':10,'vibraphone':11,'vibes':11,'marimba':12,
'xylophone':13,'dulcimer':15,'harpsichord':6,'clavinet':7,'clav':7,'accordion':21,'harmonica':22,'sitar':104,'banjo':105,'shamisen':106,'koto':107,'kalimba':108,
'thumb piano':108,'bagpipe':109,'bagpipes':109,'fiddle':110,'tinkle bell':112,'agogo':113,'steel drum':114,'steel drums':114,'steeldrum':114,'woodblock':115,'taiko':116,
'melodic tom':117,'synth drum':118,'reverse cymbal':119,'telephone':124,'phone':124,'ringtone':124,'helicopter':125,'applause':126,'clap':126,'seashore':122,'ocean':122,'bird':123,'birds':123 };

const DRUM_WORDS = { 'drums':0,'drum':0,'drumkit':0,'drum kit':0,'standard kit':0,'standard drums':0,'gm drums':0,'percussion':0,'channel 10':0,'ch10':0,'kit':0,
'room kit':8,'room drums':8,'power kit':16,'power drums':16,'rock drums':16,'electronic kit':24,'electronic drums':24,'808':25,'tr808':25,'808 kit':25,'tr 808':25,
'jazz kit':32,'jazz drums':32,'brush kit':40,'brushes':40,'orchestra kit':48,'orchestral drums':48 };

const DRUM_MAP = { kick:36,bass_drum:36,bassdrum:36,bd:36,bass_drum_1:36,kick2:35,acoustic_bass_drum:35,
snare:38,acoustic_snare:38,sd:38,snare2:40,electric_snare:40,rim:37,rimshot:37,side_stick:37,sidestick:37,stick:37,clap:39,hand_clap:39,handclap:39,
hat:42,hihat:42,hi_hat:42,hh:42,chh:42,closed_hat:42,closed_hihat:42,closed_hi_hat:42,closed_hh:42,pedal_hat:44,pedal_hihat:44,pedal_hi_hat:44,
open_hat:46,openhat:46,open_hihat:46,open_hi_hat:46,ohh:46,open_hh:46,
low_floor_tom:41,floor_tom_low:41,floor_tom:43,high_floor_tom:43,tom_floor:43,low_tom:45,tom_low:45,tom:47,mid_tom:47,tom_mid:48,low_mid_tom:47,hi_mid_tom:48,
high_mid_tom:48,high_tom:50,hi_tom:50,tom_high:50,crash:49,crash_cymbal:49,crash_cymbal_1:49,crash1:49,crash2:57,crash_cymbal_2:57,
ride:51,ride_cymbal:51,ride_cymbal_1:51,ride2:59,ride_cymbal_2:59,china:52,chinese_cymbal:52,ride_bell:53,tambourine:54,splash:55,splash_cymbal:55,cowbell:56,vibraslap:58,
hi_bongo:60,high_bongo:60,bongo:60,low_bongo:61,mute_hi_conga:62,open_hi_conga:63,conga:63,low_conga:64,high_timbale:65,timbale:65,low_timbale:66,
high_agogo:67,agogo:67,low_agogo:68,cabasa:69,maracas:70,shaker:70,short_whistle:71,long_whistle:72,short_guiro:73,guiro:73,long_guiro:74,claves:75,clave:75,
hi_wood_block:76,high_wood_block:76,woodblock:76,wood_block:76,low_wood_block:77,mute_cuica:78,open_cuica:79,mute_triangle:80,open_triangle:81,triangle:81 };

/* Tone.js based synth presets ("synth:<name>").  Single-note melodic voices unless marked (drum). */
const SYNTH_PRESETS = { pluck:'plucky saw, fast filter decay', pad:'slow warm detuned-saw pad', bass:'filtered saw bass', lead:'bright detuned-saw lead',
chiptune:'25% pulse wave (NES style)', 'chiptune-tri':'triangle wave (NES bass)', 'chiptune-noise':'8-bit noise hit (drum)', bell:'FM bell / chime', keys:'FM electric piano',
organ:'drawbar-style organ', strings:'synth string section', sub:'pure sine sub bass', kick:'kick drum (drum)', snare:'snare drum (drum)', hat:'closed hi-hat (drum)',
openhat:'open hi-hat (drum)', clap:'hand clap (drum)', tom:'tom, pitch follows the note (drum)', crash:'crash cymbal (drum)', drums:'full synth drum kit using GM drum names (drum)' };
const SYNTH_ALIASES = { chip:'chiptune', square:'chiptune', '8bit':'chiptune', nes:'chiptune', triangle:'chiptune-tri', 'chip-tri':'chiptune-tri', 'chip-noise':'chiptune-noise',
noise:'chiptune-noise', hihat:'hat', hh:'hat', closedhat:'hat', 'open-hat':'openhat', open_hat:'openhat', saw:'lead', epiano:'keys', ep:'keys', chime:'bell', 'synth-bass':'bass', 'sub-bass':'sub',
drumkit:'drums', 'drum-kit':'drums', cymbal:'crash' };
const SFX_PRESET_NAMES = ['beep','ding','bell','chime','coin','success','error','click','pop','whoosh','swoosh','laser','powerup','alert','ring','notify','tick','buzz','explosion','blip'];

const CHORD_TYPES = { '':[0,4,7], maj:[0,4,7], M:[0,4,7], m:[0,3,7], min:[0,3,7], '7':[0,4,7,10], maj7:[0,4,7,11], M7:[0,4,7,11], m7:[0,3,7,10], min7:[0,3,7,10], dim:[0,3,6],
dim7:[0,3,6,9], m7b5:[0,3,6,10], aug:[0,4,8], '+':[0,4,8], sus2:[0,2,7], sus4:[0,5,7], '7sus4':[0,5,7,10], '6':[0,4,7,9], m6:[0,3,7,9], '9':[0,4,7,10,14], maj9:[0,4,7,11,14],
m9:[0,3,7,10,14], min9:[0,3,7,10,14], add9:[0,4,7,14], '5':[0,7], '11':[0,4,7,10,14,17], '13':[0,4,7,10,14,21] };
const CHORD_RE = /^([A-Ga-g])([#b♯♭]?)(maj9|maj7|min9|min7|m7b5|dim7|7sus4|add9|sus2|sus4|maj|min|dim|aug|M7|m9|m7|m6|13|11|9|7|6|5|m|M|\+|)(?:\/([A-Ga-g])([#b♯♭]?))?$/;
const NOTE_RE = /^([A-Ga-g])(##|bb|[#b♯♭])?(-?\d{1,2})$/;
const PC = { c:0, d:2, e:4, f:5, g:7, a:9, b:11 };

const isObj = v => v !== null && typeof v === 'object' && !Array.isArray(v);
const isNum = v => typeof v === 'number' && isFinite(v);
const normName = s => String(s).toLowerCase().replace(/[()\-_\/+&,.:]/g, ' ').replace(/\s+/g, ' ').trim();
const GM_INDEX = (() => { const m = new Map(); GM_NAMES.forEach((n, i) => m.set(normName(n), i)); return m; })();

function accidental(a) { return !a ? 0 : (a === '#' || a === '♯') ? 1 : (a === 'b' || a === '♭') ? -1 : a === '##' ? 2 : a === 'bb' ? -2 : 0; }

/** "C4" | "F#3" | "Bb2" | 60 | "60"  ->  integer MIDI number (C4 = 60) or null */
function parsePitch(p) {
  if (typeof p === 'number') return isFinite(p) && p >= 0 && p <= 127 ? Math.round(p) : null;
  if (typeof p !== 'string') return null;
  const s = p.trim();
  if (/^\d+$/.test(s)) { const n = +s; return n <= 127 ? n : null; }
  const m = NOTE_RE.exec(s);
  if (!m) return null;
  const v = PC[m[1].toLowerCase()] + accidental(m[2]) + 12 * (parseInt(m[3], 10) + 1);
  return v >= 0 && v <= 127 ? v : null;
}
/** drum track pitch: GM drum name ("kick", "closed_hat", "Open Hi-Hat") or MIDI number -> GM drum note, or null */
function parseDrum(p) {
  if (typeof p === 'number') return isFinite(p) && p >= 0 && p <= 127 ? Math.round(p) : null;
  if (typeof p !== 'string') return null;
  const k = p.trim().toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '');
  if (/^\d+$/.test(k)) return +k <= 127 ? +k : null;
  return DRUM_MAP[k] !== undefined ? DRUM_MAP[k] : null;
}
/** "Am7" | "C" | "F#m7b5" | "Dsus4" | "C/E" -> sorted MIDI note array (root in `octave`, default 4), or null */
function parseChord(sym, octave, inversion) {
  const m = CHORD_RE.exec(String(sym).trim());
  if (!m) return null;
  const iv = CHORD_TYPES[m[3]];
  if (!iv) return null;
  const oct = isNum(octave) ? Math.round(octave) : 4;
  const root = PC[m[1].toLowerCase()] + accidental(m[2]) + 12 * (oct + 1);
  let notes = iv.map(i => root + i);
  const inv = isNum(inversion) ? Math.max(0, Math.min(notes.length - 1, Math.round(inversion))) : 0;
  for (let i = 0; i < inv; i++) notes.push(notes.shift() + 12);
  if (m[4]) { let b = PC[m[4].toLowerCase()] + accidental(m[5]) + 12 * (oct + 1); while (b >= notes[0]) b -= 12; notes.unshift(b); }
  notes = notes.filter(n => n >= 0 && n <= 127);
  return notes.length ? notes : null;
}
function lev(a, b) { const d = []; for (let i = 0; i <= a.length; i++) { d[i] = [i]; } for (let j = 1; j <= b.length; j++) d[0][j] = j;
  for (let i = 1; i <= a.length; i++) for (let j = 1; j <= b.length; j++) d[i][j] = Math.min(d[i-1][j] + 1, d[i][j-1] + 1, d[i-1][j-1] + (a[i-1] === b[j-1] ? 0 : 1));
  return d[a.length][b.length]; }
function closest(word, list, n) { return list.map(x => [x, lev(word, normName(x))]).sort((p, q) => p[1] - q[1]).slice(0, n || 3).map(p => p[0]); }

/** Resolve a track "instrument" value.  Returns {kind:'gm',program,name} | {kind:'drums',kit} | {kind:'synth',preset} | {error} */
function resolveInstrument(inst, track) {
  track = track || {};
  const kitOf = k => { if (k === undefined || k === null) return 0; if (isNum(k)) return Math.max(0, Math.min(127, Math.round(k)));
    const n = normName(k); if (DRUM_WORDS[n] !== undefined) return DRUM_WORDS[n]; if (DRUM_WORDS[n + ' kit'] !== undefined) return DRUM_WORDS[n + ' kit']; return 0; };
  if (track.drums === true) return { kind: 'drums', kit: kitOf(track.kit !== undefined ? track.kit : inst), name: 'GM drum kit' };
  if (isNum(inst)) return Number.isInteger(inst) && inst >= 0 && inst <= 127 ? { kind: 'gm', program: inst, name: GM_NAMES[inst] }
    : { error: 'instrument number ' + inst + ' is not a General MIDI program (integer 0-127, 0 = Acoustic Grand Piano)' };
  if (typeof inst !== 'string' || !inst.trim()) return { error: 'instrument is missing - use a GM name like "Acoustic Grand Piano", a GM program number 0-127, "drums" or "synth:pluck"' };
  const s = inst.trim();
  let m = /^synth\s*:\s*(.+)$/i.exec(s);
  if (m) { let p = m[1].trim().toLowerCase(); p = SYNTH_ALIASES[p] || p;
    return SYNTH_PRESETS[p] ? { kind: 'synth', preset: p } : { error: 'unknown synth preset "' + m[1] + '" - choose one of: ' + Object.keys(SYNTH_PRESETS).join(', ') }; }
  m = /^(?:gm|program)\s*:?\s*(\d+)$/i.exec(s) || /^(\d+)$/.exec(s);
  if (m) { const n = +m[1]; return n <= 127 ? { kind: 'gm', program: n, name: GM_NAMES[n] } : { error: 'GM program ' + n + ' is out of range 0-127' }; }
  const n = normName(s);
  if (DRUM_WORDS[n] !== undefined) return { kind: 'drums', kit: track.kit !== undefined ? kitOf(track.kit) : DRUM_WORDS[n], name: 'GM drum kit' };
  if (GM_INDEX.has(n)) { const i = GM_INDEX.get(n); return { kind: 'gm', program: i, name: GM_NAMES[i] }; }
  if (GM_ALIASES[n] !== undefined) return { kind: 'gm', program: GM_ALIASES[n], name: GM_NAMES[GM_ALIASES[n]] };
  for (const [k, i] of GM_INDEX) if (k.startsWith(n)) return { kind: 'gm', program: i, name: GM_NAMES[i] };
  const toks = n.split(' ');
  for (const [k, i] of GM_INDEX) if (toks.every(t => k.indexOf(t) >= 0)) return { kind: 'gm', program: i, name: GM_NAMES[i] };
  const cands = GM_NAMES.map(x => [x, normName(x)]).concat(Object.keys(GM_ALIASES).map(a => [GM_NAMES[GM_ALIASES[a]], a]));
  const best = []; cands.map(c => [c[0], lev(n, c[1])]).sort((p, q) => p[1] - q[1]).forEach(c => { if (best.indexOf(c[0]) < 0 && best.length < 3) best.push(c[0]); });
  return { error: 'unknown instrument "' + inst + '" - did you mean ' + best.map(x => '"' + x + '"').join(', ') + '? (or use a number 0-127, "drums", "synth:pluck")' };
}

/** How pitches are read for a resolved instrument: {isDrum, parse}.  Named-kit drums need drum names/numbers; one-shot synth drums (kick, snare, hat ...) accept anything. */
const ONE_SHOT = ['kick','snare','hat','openhat','clap','tom','crash','chiptune-noise'];
function instProfile(inst) {
  if (inst.kind === 'drums' || (inst.kind === 'synth' && inst.preset === 'drums')) return { isDrum: true, parse: parseDrum };
  if (inst.kind === 'synth' && ONE_SHOT.indexOf(inst.preset) >= 0) return { isDrum: true, parse: p => { const a = parsePitch(p); return a !== null ? a : (typeof p === 'string' ? parseDrum(p) : null); } };
  return { isDrum: false, parse: parsePitch };
}

/** Velocity: 0..1 is a fraction, values above 1 are MIDI velocities (1..127).  Returns 0..1 or null. */
function parseVelocity(v) {
  if (v === undefined || v === null) return 0.8;
  if (!isNum(v) || v < 0 || v > 127) return null;
  return v <= 1 ? v : v / 127;
}
function stepVelocity(ch) {
  if (ch === 'x') return 0.8; if (ch === 'X') return 1; if (ch === 'o') return 0.55; if (ch === 'g') return 0.3;
  if (ch >= '1' && ch <= '9') return (+ch) / 9;
  return 0;
}
/** "8n" "4n." "16n" "2n" "1n" "8t" or a plain number of beats -> beats (quarter note = 1) or null */
function parseBeats(v) {
  if (isNum(v)) return v;
  if (typeof v !== 'string') return null;
  const m = /^(\d+)([nt])(\.?)$/.exec(v.trim()); if (!m) return null;
  let b = 4 / +m[1]; if (m[2] === 't') b *= 2 / 3; if (m[3]) b *= 1.5; return b;
}

const TOP_KEYS = ['version','title','description','mode','tempo','bpm','timeSignature','swing','swingDivision','humanize','seed','lengthBeats','lengthBars','durationSec','tailSec','fadeIn','fadeOut','loop','master','tracks','sfx','midi'];
const TRACK_KEYS = ['name','instrument','kit','drums','volume','pan','mute','solo','transpose','offset','fx','notes','steps','stepBeats','repeat','env','osc','engine'];
const NOTE_KEYS = ['pitch','chord','octave','inversion','strum','start','dur','duration','vel','velocity'];
const FX_KEYS = ['reverb','delay','chorus','distortion','lowpass','highpass','eq','compressor'];
const MASTER_KEYS = ['gain','reverb','delay','compressor','eq','normalize','targetLufs','ceiling','peak'];
const SFX_KEYS = ['name','preset','zzfx','start','volume','pan','pitch','repeat','every','reverb'];

function unknownKeys(obj, allowed, path, warn) {
  for (const k of Object.keys(obj)) if (allowed.indexOf(k) < 0) {
    const c = closest(k.toLowerCase(), allowed, 1)[0];
    warn(path, 'unknown field "' + k + '" is ignored' + (c && lev(k.toLowerCase(), c.toLowerCase()) <= 3 ? ' (did you mean "' + c + '"?)' : ''));
  }
}

/** Validate a score (object or JSON string).  Never throws.  Returns {ok, errors[], warnings[], summary, score}. */
function validateScore(input) {
  const errors = [], warnings = [];
  const E = (p, m) => errors.push(p ? p + ': ' + m : m);
  const W = (p, m) => warnings.push(p ? p + ': ' + m : m);
  let s = input;
  if (typeof s === 'string') {
    try { s = JSON.parse(s); } catch (e) { return { ok: false, errors: ['Score is not valid JSON (' + e.message + '). Use double quotes, no comments, no trailing commas.'], warnings, summary: null, score: null }; }
  }
  if (!isObj(s)) return { ok: false, errors: ['Score must be a JSON object like {"tempo":120,"tracks":[...]}'], warnings, summary: null, score: null };
  s = Object.assign({}, s);
  if (s.bpm !== undefined && s.tempo === undefined) { s.tempo = s.bpm; delete s.bpm; }
  unknownKeys(s, TOP_KEYS, '', W);
  const range = (p, v, lo, hi, what) => { if (v === undefined) return; if (!isNum(v) || v < lo || v > hi) E(p, what + ' must be a number from ' + lo + ' to ' + hi + ' (got ' + JSON.stringify(v) + ')'); };
  if (s.version !== undefined && s.version !== SCORE_VERSION) W('version', 'only version ' + SCORE_VERSION + ' exists; got ' + JSON.stringify(s.version));
  if (s.mode !== undefined && s.mode !== 'music' && s.mode !== 'sfx') E('mode', 'must be "music" or "sfx"');
  range('tempo', s.tempo, LIMITS.tempoMin, LIMITS.tempoMax, 'tempo (BPM)');
  if (s.timeSignature !== undefined) {
    const t = s.timeSignature;
    if (!Array.isArray(t) || t.length !== 2 || !Number.isInteger(t[0]) || t[0] < 1 || t[0] > 32 || [1,2,4,8,16].indexOf(t[1]) < 0) E('timeSignature', 'must look like [4,4] or [3,4] or [6,8] (numerator 1-32, denominator 1,2,4,8 or 16)');
  }
  range('swing', s.swing, 0, 1, 'swing'); range('humanize', s.humanize, 0, 1, 'humanize');
  if (s.swingDivision !== undefined && s.swingDivision !== '8n' && s.swingDivision !== '16n') E('swingDivision', 'must be "8n" or "16n"');
  range('lengthBeats', s.lengthBeats, 0.5, 4800, 'lengthBeats'); range('lengthBars', s.lengthBars, 0.25, 1200, 'lengthBars');
  range('durationSec', s.durationSec, 0.05, LIMITS.durationSec, 'durationSec'); range('tailSec', s.tailSec, 0, 20, 'tailSec');
  range('fadeIn', s.fadeIn, 0, 30, 'fadeIn'); range('fadeOut', s.fadeOut, 0, 30, 'fadeOut');
  const loopOn = s.loop === true || isObj(s.loop);
  if (s.loop !== undefined && s.loop !== true && s.loop !== false && !isObj(s.loop)) E('loop', 'must be true, false or an object like {"times":4} or {"toSeconds":60}');
  if (isObj(s.loop)) {
    unknownKeys(s.loop, ['times', 'toSeconds', 'seamless'], 'loop', W);
    if (s.loop.times !== undefined && (!Number.isInteger(s.loop.times) || s.loop.times < 1 || s.loop.times > 200)) E('loop.times', 'must be a whole number 1-200');
    range('loop.toSeconds', s.loop.toSeconds, 0.1, LIMITS.durationSec, 'loop.toSeconds');
  }
  if (loopOn && s.lengthBeats === undefined && s.lengthBars === undefined) W('loop', 'set lengthBars (or lengthBeats) so the loop length is exact; otherwise it is rounded up from the last note');

  if (s.master !== undefined) {
    const m = s.master, P = 'master';
    if (!isObj(m)) E(P, 'must be an object'); else {
      unknownKeys(m, MASTER_KEYS, P, W);
      range(P + '.gain', m.gain, 0, 4, 'gain'); range(P + '.targetLufs', m.targetLufs, -40, -3, 'targetLufs'); range(P + '.ceiling', m.ceiling, 0.1, 0.98, 'ceiling');
      range(P + '.peak', m.peak, 0.1, 0.98, 'peak');
      if (m.normalize !== undefined && m.normalize !== true && m.normalize !== false && m.normalize !== 'lufs' && m.normalize !== 'peak') E(P + '.normalize', 'must be true, false, "lufs" or "peak"');
      if (m.reverb !== undefined) { if (!isObj(m.reverb)) E(P + '.reverb', 'must be an object like {"decay":2.5,"preDelay":0.02,"damping":0.5}'); else {
        range(P + '.reverb.decay', m.reverb.decay, 0.1, 10, 'decay seconds'); range(P + '.reverb.preDelay', m.reverb.preDelay, 0, 0.5, 'preDelay seconds'); range(P + '.reverb.damping', m.reverb.damping, 0, 1, 'damping'); } }
      if (m.delay !== undefined) { if (!isObj(m.delay)) E(P + '.delay', 'must be an object like {"beats":0.75,"feedback":0.35}'); else {
        const b = m.delay.beats !== undefined ? parseBeats(m.delay.beats) : null;
        if (m.delay.beats !== undefined && (b === null || b <= 0 || b > 8)) E(P + '.delay.beats', 'must be beats (0.25-8) or a note length like "8n." / "4n"');
        range(P + '.delay.time', m.delay.time, 0.01, 2, 'delay time seconds'); range(P + '.delay.feedback', m.delay.feedback, 0, 0.9, 'feedback'); } }
      if (m.compressor !== undefined && typeof m.compressor !== 'boolean' && !isObj(m.compressor)) E(P + '.compressor', 'must be true, false or {"threshold":-18,"ratio":3}');
      if (isObj(m.compressor)) { range(P + '.compressor.threshold', m.compressor.threshold, -60, 0, 'threshold dB'); range(P + '.compressor.ratio', m.compressor.ratio, 1, 20, 'ratio'); }
      if (m.eq !== undefined) { if (!isObj(m.eq)) E(P + '.eq', 'must be {"low":0,"mid":0,"high":0} in dB'); else for (const b of ['low','mid','high']) range(P + '.eq.' + b, m.eq[b], -24, 24, 'EQ ' + b + ' dB'); }
    }
  }

  let noteTotal = 0, endBeat = 0, usable = 0;
  const tracks = s.tracks;
  if (tracks !== undefined && !Array.isArray(tracks)) E('tracks', 'must be an array of track objects');
  if (Array.isArray(tracks)) {
    if (tracks.length > LIMITS.tracks) E('tracks', 'at most ' + LIMITS.tracks + ' tracks (got ' + tracks.length + ')');
    tracks.forEach((t, ti) => {
      const P = 'tracks[' + ti + ']';
      if (!isObj(t)) { E(P, 'must be an object'); return; }
      unknownKeys(t, TRACK_KEYS, P, W);
      const inst = resolveInstrument(t.instrument, t);
      if (inst.error) E(P + '.instrument', inst.error);
      const prof = inst.kind ? instProfile(inst) : { isDrum: false, parse: parsePitch }, isDrum = prof.isDrum;
      range(P + '.volume', t.volume, 0, 2, 'volume'); range(P + '.pan', t.pan, -1, 1, 'pan');
      if ((inst.kind === 'gm' || inst.kind === 'drums') && isNum(t.volume) && t.volume > 1) W(P + '.volume', 'SoundFont tracks cannot go above 1.0 (clamped); lower the other tracks instead');
      if (t.transpose !== undefined && (!Number.isInteger(t.transpose) || t.transpose < -48 || t.transpose > 48)) E(P + '.transpose', 'must be a whole number of semitones -48..48');
      range(P + '.offset', t.offset, 0, 4800, 'offset (beats)'); range(P + '.stepBeats', t.stepBeats, 0.0625, 8, 'stepBeats');
      if (t.fx !== undefined) { if (!isObj(t.fx)) E(P + '.fx', 'must be an object'); else {
        const f = t.fx; unknownKeys(f, FX_KEYS, P + '.fx', W);
        for (const k of ['reverb','delay','chorus','distortion']) range(P + '.fx.' + k, f[k], 0, 1, k + ' amount');
        range(P + '.fx.lowpass', f.lowpass, 40, 20000, 'lowpass Hz'); range(P + '.fx.highpass', f.highpass, 10, 18000, 'highpass Hz');
        if (f.eq !== undefined) { if (!isObj(f.eq)) E(P + '.fx.eq', 'must be {"low":0,"mid":0,"high":0} in dB'); else for (const b of ['low','mid','high']) range(P + '.fx.eq.' + b, f.eq[b], -24, 24, 'EQ ' + b + ' dB'); }
        if (f.compressor !== undefined && typeof f.compressor !== 'boolean' && !isObj(f.compressor)) E(P + '.fx.compressor', 'must be true, false or an object');
        if (inst.kind === 'gm' || inst.kind === 'drums') { if (f.eq || f.lowpass || f.highpass || f.distortion || f.compressor) W(P + '.fx', 'SoundFont tracks support only reverb, delay and chorus per track; eq/lowpass/highpass/distortion/compressor are ignored here (use master.eq or a synth: track)'); }
      } }
      if (t.engine !== undefined && t.engine !== 'js' && t.engine !== 'tone') E(P + '.engine', 'must be "js" (default, fast built-in synth) or "tone" (Tone.js voices, slower)');
      if (t.engine !== undefined && inst.kind && inst.kind !== 'synth') W(P + '.engine', 'engine only applies to synth: instruments');
      if (t.env !== undefined && !isObj(t.env)) E(P + '.env', 'must be {"attack":0.01,"decay":0.2,"sustain":0.7,"release":0.3} (seconds, sustain 0-1)');
      if (t.env !== undefined && inst.kind && inst.kind !== 'synth') W(P + '.env', 'env/osc only apply to synth: instruments');
      let hasMaterial = false, trackEnd = 0, trackNotes = 0;
      const pitchOf = prof.parse;
      const checkNote = (n, np) => {
        let pitch, start, dur, vel, chord, octave, inversion, strum;
        if (Array.isArray(n)) { [pitch, start, dur, vel] = n; if (n.length < 2 || n.length > 4) E(np, 'array notes look like [pitch, startBeat, durBeats, velocity] (velocity optional)'); }
        else if (isObj(n)) { unknownKeys(n, NOTE_KEYS, np, W); ({ pitch, chord, octave, inversion, strum } = n); start = n.start; dur = n.dur !== undefined ? n.dur : n.duration; vel = n.vel !== undefined ? n.vel : n.velocity; }
        else { E(np, 'a note must be an object {"pitch":"C4","start":0,"dur":1} or an array ["C4",0,1]'); return; }
        if (!isNum(start) || start < 0 || start > 4800) E(np + '.start', 'start (in beats from the beginning, 0 = first beat) must be a number >= 0 (got ' + JSON.stringify(start) + ')');
        if (dur === undefined) dur = 1;
        if (!isNum(dur) || dur <= 0 || dur > 4800) E(np + '.dur', 'dur (length in beats) must be a number > 0 (got ' + JSON.stringify(dur) + ')');
        if (parseVelocity(vel) === null) E(np + '.vel', 'vel must be 0-1 (or MIDI 2-127) (got ' + JSON.stringify(vel) + ')');
        if (strum !== undefined) range(np + '.strum', strum, 0, 1, 'strum beats');
        let count = 1;
        if (chord !== undefined) {
          if (isDrum) E(np + '.chord', 'chords make no sense on a drum track');
          else { const c = parseChord(chord, octave, inversion); if (!c) E(np + '.chord', '"' + chord + '" is not a chord symbol - use e.g. "C", "Am", "G7", "Fmaj7", "Dm7", "Bdim", "Csus4", "C/E"'); else count = c.length; }
          if (pitch !== undefined) W(np, 'both pitch and chord given; pitch is ignored');
        } else if (pitch === undefined) E(np + '.pitch', 'pitch is required (e.g. "C4", 60' + (isDrum ? ', "kick"' : '') + ' or an array of pitches for a chord)');
        else {
          const arr = Array.isArray(pitch) ? pitch : [pitch];
          if (!arr.length) E(np + '.pitch', 'empty pitch array'); count = arr.length;
          arr.forEach(p => { if (pitchOf(p) === null) E(np + '.pitch', JSON.stringify(p) + ' is not ' + (isDrum ? 'a drum name (kick, snare, closed_hat, open_hat, clap, crash, ride, tom_low, ...) or GM drum number 27-87' : 'a note name like C4, F#3, Bb2 or a MIDI number 0-127 (middle C = C4 = 60)')); });
        }
        trackNotes += count; if (isNum(start) && isNum(dur)) trackEnd = Math.max(trackEnd, start + dur);
      };
      if (t.notes !== undefined) {
        if (!Array.isArray(t.notes)) E(P + '.notes', 'must be an array'); else { hasMaterial = hasMaterial || t.notes.length > 0; t.notes.forEach((n, ni) => checkNote(n, P + '.notes[' + ni + ']')); }
      }
      let stepsLen = 0;
      if (t.steps !== undefined) {
        if (!isObj(t.steps)) E(P + '.steps', 'must be an object like {"kick":"x...x...","snare":"....x..."}'); else {
          const sb = t.stepBeats !== undefined ? t.stepBeats : 0.25;
          for (const [k, str] of Object.entries(t.steps)) {
            const sp = P + '.steps.' + k;
            if (typeof str !== 'string') { E(sp, 'must be a string of step characters like "x...x..."'); continue; }
            if (pitchOf(k) === null) E(sp, '"' + k + '" is not ' + (isDrum ? 'a drum name' : 'a note name'));
            const clean = str.replace(/[\s|]/g, '');
            const bad = clean.replace(/[xXog1-9.\-_]/g, '');
            if (bad) E(sp, 'invalid characters "' + bad + '" - use x (hit), X (accent), o (soft), g (ghost), 1-9 (velocity), . (rest)');
            stepsLen = Math.max(stepsLen, clean.length); hasMaterial = true;
            for (const ch of clean) if (stepVelocity(ch) > 0) trackNotes++;
          }
          trackEnd = Math.max(trackEnd, stepsLen * sb);
        }
      }
      if (t.repeat !== undefined) {
        const r = t.repeat;
        if (!isObj(r)) E(P + '.repeat', 'must be {"times":4,"every":16} (every = distance in beats between copies)');
        else {
          if (!Number.isInteger(r.times) || r.times < 1 || r.times > 200) E(P + '.repeat.times', 'must be a whole number 1-200');
          const every = r.every !== undefined ? r.every : (stepsLen ? stepsLen * (t.stepBeats !== undefined ? t.stepBeats : 0.25) : undefined);
          if (!isNum(every) || every <= 0) E(P + '.repeat.every', 'required: beats between copies (e.g. 16 for 4 bars of 4/4)');
          else if (Number.isInteger(r.times)) { trackNotes *= r.times; trackEnd += every * (r.times - 1); }
        }
      }
      if (!hasMaterial && !(Array.isArray(t.notes) && t.notes.length)) W(P, 'track has no notes or steps and will be silent');
      noteTotal += trackNotes; endBeat = Math.max(endBeat, trackEnd + (isNum(t.offset) ? t.offset : 0));
      if (!t.mute) usable++;
    });
  }
  if (noteTotal > LIMITS.notes) E('tracks', 'too many notes (' + noteTotal + ', max ' + LIMITS.notes + ') - use repeat/loop instead of writing every repetition');

  let sfxCount = 0;
  if (s.sfx !== undefined) {
    if (!Array.isArray(s.sfx)) E('sfx', 'must be an array of sound-effect objects');
    else s.sfx.forEach((e, i) => {
      const P = 'sfx[' + i + ']'; sfxCount++;
      if (!isObj(e)) { E(P, 'must be an object like {"preset":"ding","start":0} or {"zzfx":[1,0,440,...],"start":0}'); return; }
      unknownKeys(e, SFX_KEYS, P, W);
      if (e.preset === undefined && e.zzfx === undefined) E(P, 'needs "preset" (' + SFX_PRESET_NAMES.join('/') + ') or "zzfx" (an array of up to 23 numbers)');
      if (e.preset !== undefined && SFX_PRESET_NAMES.indexOf(e.preset) < 0) E(P + '.preset', '"' + e.preset + '" unknown - choose: ' + SFX_PRESET_NAMES.join(', '));
      if (e.zzfx !== undefined) {
        const layers = Array.isArray(e.zzfx) && Array.isArray(e.zzfx[0]) ? e.zzfx : [e.zzfx];
        layers.forEach((a, li) => { if (!Array.isArray(a) || a.length > 23 || a.some(x => x !== null && !isNum(x))) E(P + '.zzfx', 'layer ' + li + ' must be an array of at most 23 numbers (use null or 0 for defaults): [volume,randomness,frequency,attack,sustain,release,shape,shapeCurve,slide,deltaSlide,pitchJump,pitchJumpTime,repeatTime,noise,modulation,bitCrush,delay,sustainVolume,decay,tremolo,filter,filterEnd,filterQ]'); });
      }
      range(P + '.start', e.start, 0, LIMITS.durationSec, 'start (seconds)'); range(P + '.volume', e.volume, 0, 2, 'volume'); range(P + '.pan', e.pan, -1, 1, 'pan');
      range(P + '.pitch', e.pitch, -36, 36, 'pitch (semitones)'); range(P + '.reverb', e.reverb, 0, 1, 'reverb'); range(P + '.every', e.every, 0.02, 60, 'every (seconds)');
      if (e.repeat !== undefined && (!Number.isInteger(e.repeat) || e.repeat < 1 || e.repeat > 64)) E(P + '.repeat', 'must be a whole number 1-64');
    });
  }
  if (s.midi !== undefined) {
    const arr = Array.isArray(s.midi) ? s.midi : [s.midi];
    arr.forEach((m, i) => { const P = 'midi' + (Array.isArray(s.midi) ? '[' + i + ']' : '');
      if (!isObj(m) || typeof m.base64 !== 'string' || m.base64.length < 20) E(P, 'must be {"base64":"<standard MIDI file as base64>","volume":1,"startSec":0}');
      else { range(P + '.volume', m.volume, 0, 2, 'volume'); range(P + '.startSec', m.startSec, 0, LIMITS.durationSec, 'startSec'); } });
  }
  if (Array.isArray(tracks) && tracks.length === 0 && !sfxCount && s.midi === undefined) E('tracks', 'the score has no tracks, sfx or midi - nothing to render');
  if (tracks === undefined && !sfxCount && s.midi === undefined) E('tracks', 'missing: add "tracks":[{"instrument":"Acoustic Grand Piano","notes":[["C4",0,1]]}] (or "sfx":[...])');
  if (Array.isArray(tracks) && tracks.length && !usable && !sfxCount && s.midi === undefined) W('tracks', 'every track is muted');
  if (isObj(s.master) && s.master.normalize === false) W('master.normalize', 'normalisation is off; the level of the result is not controlled (peak is still limited)');
  const bpm = isNum(s.tempo) ? s.tempo : 120;
  const summary = { tracks: Array.isArray(tracks) ? tracks.length : 0, notes: noteTotal, sfx: sfxCount, tempo: bpm, estimatedMusicSec: Math.round(endBeat * 60 / bpm * 100) / 100 };
  return { ok: errors.length === 0, errors, warnings, summary, score: errors.length ? null : s };
}

/** Turn a validation result into one readable text block (handy for sending back to the AI that wrote the score). */
function formatValidation(r) {
  const out = [];
  out.push(r.ok ? 'Score OK' + (r.summary ? ' (' + r.summary.tracks + ' tracks, ' + r.summary.notes + ' notes, ~' + r.summary.estimatedMusicSec + ' s of music at ' + r.summary.tempo + ' BPM)' : '') : 'Score has ' + r.errors.length + ' error(s):');
  r.errors.forEach(e => out.push('  ERROR   ' + e)); r.warnings.forEach(w => out.push('  warning ' + w));
  return out.join('\n');
}
/* ==== VALIDATOR END ==== */

/* ------------------------------------------------------------------------------------------------------------------ */
/* compileScore: validated score  ->  plan with every note placed in seconds (swing, humanize, repeat, loop already applied) */
/* ------------------------------------------------------------------------------------------------------------------ */

function mulberry32(a) { return function () { a |= 0; a = a + 0x6D2B79F5 | 0; let t = Math.imul(a ^ a >>> 15, 1 | a); t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; }; }

function zzfxLengthSec(p) { // attack + decay + sustain + release + delay (indices 3,18,4,5,16), as in the ZzFX generator
  return ((p[3] || 0) + (p[18] || 0) + (p[4] || 0) + (p[5] === undefined || p[5] === null ? 0.1 : p[5]) + (p[16] || 0)) + 9 / 44100;
}

function compileScore(input, SFX_LIB) {
  const v = validateScore(input);
  if (!v.ok) { const e = new Error('Invalid score:\n  - ' + v.errors.join('\n  - ')); e.validation = v; throw e; }
  const s = v.score, warnings = v.warnings.slice();
  const tempo = isNum(s.tempo) ? s.tempo : 120, spb = 60 / tempo;
  const ts = s.timeSignature || [4, 4], beatsPerBar = ts[0] * 4 / ts[1];
  const isSfxMode = s.mode === 'sfx' || (!(s.tracks && s.tracks.length) && s.midi === undefined && (s.sfx || []).length > 0);
  const rnd = mulberry32(isNum(s.seed) ? s.seed | 0 : 12345);
  const human = s.humanize || 0, swing = s.swing || 0, swingP = s.swingDivision === '16n' ? 0.5 : 1;

  const warp = b => { // swing: delay the off-beat half of every swing cell
    if (!swing) return b;
    const P = swingP, h = P / 2, m = h + swing * P / 6, k = Math.floor(b / P + 1e-9), f = b - k * P;
    return k * P + (f < h ? f * (m / h) : m + (f - h) * ((P - m) / (P - h)));
  };

  // 1. expand every track into pattern-level events in beats
  const anySolo = (s.tracks || []).some(t => t && t.solo);
  const trs = [];
  let naturalEnd = 0;
  (s.tracks || []).forEach((t, ti) => {
    if (t.mute || (anySolo && !t.solo)) return;
    const inst = resolveInstrument(t.instrument, t);
    const prof = instProfile(inst), drum = prof.isDrum, pitchOf = prof.parse;
    const tr = t.transpose || 0;
    const shift = (n) => (drum ? n : Math.max(0, Math.min(127, n + tr)));
    const evs = [];
    const pushN = (pitch, start, dur, vel) => evs.push({ pitch: pitch, b: start, d: dur, vel: vel });
    (t.notes || []).forEach(n => {
      let pitch, start, dur, vel, chord, octave, inversion, strum;
      if (Array.isArray(n)) { [pitch, start, dur, vel] = n; } else { ({ pitch, chord, octave, inversion, strum } = n); start = n.start; dur = n.dur !== undefined ? n.dur : n.duration; vel = n.vel !== undefined ? n.vel : n.velocity; }
      if (dur === undefined) dur = 1;
      const velo = parseVelocity(vel);
      const list = chord !== undefined ? parseChord(chord, octave, inversion) : (Array.isArray(pitch) ? pitch : [pitch]).map(pitchOf);
      list.forEach((p, i) => pushN(shift(p), start + (strum ? strum * i : 0), dur, velo));
    });
    let stepsLen = 0;
    const sb = t.stepBeats !== undefined ? t.stepBeats : 0.25;
    if (t.steps) {
      for (const [k, str] of Object.entries(t.steps)) {
        const clean = str.replace(/[\s|]/g, ''); stepsLen = Math.max(stepsLen, clean.length);
        const p = pitchOf(k);
        for (let i = 0; i < clean.length; i++) { const sv = stepVelocity(clean[i]); if (sv > 0) pushN(shift(p), i * sb, drum ? Math.min(sb, 0.5) : sb * 0.95, sv); }
      }
    }
    let all = evs;
    if (t.repeat) {
      const every = t.repeat.every !== undefined ? t.repeat.every : stepsLen * sb;
      all = [];
      for (let r = 0; r < t.repeat.times; r++) evs.forEach(e => all.push({ pitch: e.pitch, b: e.b + r * every, d: e.d, vel: e.vel }));
    }
    const off = t.offset || 0;
    all.forEach(e => { e.b += off; naturalEnd = Math.max(naturalEnd, e.b + e.d); });
    trs.push({ index: ti, name: t.name || ('track' + ti), inst: inst, drum: drum, t: t, events: all });
  });

  // sfx events (seconds)
  const sfxEv = [];
  (s.sfx || []).forEach(e => {
    let layers;
    if (e.zzfx !== undefined) layers = Array.isArray(e.zzfx[0]) ? e.zzfx : [e.zzfx];
    else layers = SFX_LIB && SFX_LIB[e.preset] ? SFX_LIB[e.preset] : [[1, 0, 440, .01, .1, .2]];
    const reps = e.repeat || 1, every = e.every || 0.25;
    for (let r = 0; r < reps; r++) sfxEv.push({ t: (e.start || 0) + r * every, layers: layers, volume: e.volume === undefined ? 1 : e.volume, pan: e.pan || 0, pitch: e.pitch || 0, reverb: e.reverb || 0, name: e.name || e.preset || 'zzfx' });
  });
  let sfxEnd = 0;
  sfxEv.forEach(e => e.layers.forEach(l => { sfxEnd = Math.max(sfxEnd, e.t + zzfxLengthSec(l) * (e.pitch ? Math.pow(2, -e.pitch / 12) : 1)); }));
  const midiList = s.midi === undefined ? [] : (Array.isArray(s.midi) ? s.midi : [s.midi]);

  // 2. lengths: pattern, loops, output
  const ceilBar = b => Math.max(beatsPerBar, Math.ceil(b / beatsPerBar - 1e-6) * beatsPerBar);
  let patBeats;
  if (s.lengthBeats !== undefined) patBeats = s.lengthBeats;
  else if (s.lengthBars !== undefined) patBeats = s.lengthBars * beatsPerBar;
  else patBeats = trs.length ? (isSfxMode ? naturalEnd : ceilBar(naturalEnd)) : 0;
  let patSec = patBeats * spb;
  const musicEndSec = trs.length ? Math.max(naturalEnd * spb, patSec) : 0;
  const contentSec = Math.max(patSec, sfxEnd, trs.length ? 0 : 0);
  const loopObj = isObj(s.loop) ? s.loop : (s.loop === true ? {} : null);
  const tail = s.tailSec !== undefined ? s.tailSec : (isSfxMode ? 0.3 : 1.5);
  let loops = 1, outSec, seamless = false;
  const unitSec = patSec > 0 ? patSec : sfxEnd; // one loop unit
  if (loopObj) {
    if (!(unitSec > 0)) { warnings.push('loop: nothing to loop (no musical content)'); loops = 1; }
    else if (loopObj.times) loops = loopObj.times;
    else if (loopObj.toSeconds || s.durationSec) loops = Math.max(1, Math.ceil(((loopObj.toSeconds || s.durationSec) - 1e-6) / unitSec));
    else loops = 2;
    const full = loops * unitSec;
    outSec = loopObj.toSeconds || s.durationSec || full;
    seamless = loopObj.seamless !== false && Math.abs(outSec - full) < 0.01;
    if (seamless) outSec = full;
  } else {
    let natural = Math.max(trs.length ? Math.max(patSec, musicEndSec) : 0, sfxEnd);
    outSec = s.durationSec || (natural + tail);
  }
  midiList.forEach(m => { /* length unknown until parsed; the engine extends outSec if no explicit length */ });
  if (outSec > LIMITS.durationSec) { warnings.push('duration ' + outSec.toFixed(1) + ' s clipped to ' + LIMITS.durationSec + ' s'); outSec = LIMITS.durationSec; }
  const renderSec = outSec + (seamless ? tail : 0);

  // 3. place notes in seconds for every loop copy
  let totalNotes = 0;
  trs.forEach(tr => {
    const out = [];
    for (let l = 0; l < loops; l++) {
      const base = l * patBeats;
      tr.events.forEach(e => {
        if (loopObj && e.b >= patBeats - 1e-9 && patBeats > 0) return; // would collide with the next loop copy
        let b0 = base + e.b, b1 = base + e.b + e.d;
        let t0 = warp(b0) * spb, t1 = warp(b1) * spb;
        let vel = e.vel;
        if (human) { t0 += (rnd() - 0.5) * 0.024 * human; if (t0 < 0) t0 = 0; vel = Math.max(0.05, Math.min(1, vel + (rnd() - 0.5) * 0.2 * human)); }
        if (t0 >= renderSec) return;
        out.push({ note: e.pitch, t: t0, d: Math.max(0.03, Math.min(t1 - t0, renderSec - t0)), vel: vel });
      });
    }
    out.sort((a, b) => a.t - b.t || a.note - b.note);
    tr.out = out; totalNotes += out.length;
  });
  const sfxOut = [];
  for (let l = 0; l < loops; l++) sfxEv.forEach(e => { const t0 = e.t + l * unitSec; if (t0 < renderSec) sfxOut.push(Object.assign({}, e, { t: t0 })); });
  sfxOut.sort((a, b) => a.t - b.t);

  // 4. channel assignment for SoundFont tracks, effect defaults
  const sfTracks = [], toneTracks = [], synthTracks = [];
  let melodicCh = 0, drumTaken = false;
  const nextMelodic = () => { if (melodicCh === 9) melodicCh++; return melodicCh++; };
  trs.forEach(tr => {
    const fx = (tr.t.fx || {});
    const common = { index: tr.index, name: tr.name, volume: tr.t.volume === undefined ? 1 : tr.t.volume, pan: tr.t.pan || 0,
      fx: { reverb: fx.reverb === undefined ? 0.15 : fx.reverb, delay: fx.delay || 0, chorus: fx.chorus || 0, distortion: fx.distortion || 0, lowpass: fx.lowpass, highpass: fx.highpass,
        eq: fx.eq, compressor: fx.compressor }, events: tr.out };
    if (tr.inst.kind === 'gm' || tr.inst.kind === 'drums') {
      let ch;
      if (tr.inst.kind === 'drums' && !drumTaken) { ch = 9; drumTaken = true; } else ch = nextMelodic();
      sfTracks.push(Object.assign(common, { channel: ch, drums: tr.inst.kind === 'drums', program: tr.inst.kind === 'gm' ? tr.inst.program : tr.inst.kit, label: tr.inst.name }));
    } else {
      (tr.t.engine === 'tone' ? toneTracks : synthTracks).push(Object.assign(common, { preset: tr.inst.preset, drum: tr.drum, env: tr.t.env, osc: tr.t.osc, label: 'synth:' + tr.inst.preset, engine: tr.t.engine === 'tone' ? 'tone' : 'js' }));
    }
  });

  const m = s.master || {};
  const master = {
    gain: m.gain === undefined ? 1 : m.gain,
    normalize: m.normalize === undefined ? (isSfxMode ? 'peak' : 'lufs') : (m.normalize === true ? 'lufs' : m.normalize),
    targetLufs: m.targetLufs === undefined ? -14 : m.targetLufs,
    ceiling: m.ceiling === undefined ? 0.95 : m.ceiling,
    peak: m.peak === undefined ? 0.9 : m.peak,
    reverb: Object.assign({ decay: 2.2, preDelay: 0.02, damping: 0.5 }, m.reverb || {}),
    delay: m.delay ? { time: m.delay.time !== undefined ? m.delay.time : (parseBeats(m.delay.beats !== undefined ? m.delay.beats : 0.75)) * spb, feedback: m.delay.feedback === undefined ? 0.35 : m.delay.feedback } : { time: 0.75 * spb, feedback: 0.35 },
    compressor: m.compressor === undefined ? true : m.compressor,
    eq: m.eq || null
  };
  // track-level delay bus uses the master delay settings (default: dotted eighth at the tempo)
  const fadeDefault = seamless ? 0 : 0.01;
  return {
    version: SCORE_VERSION, title: s.title || '', mode: isSfxMode ? 'sfx' : 'music', tempo: tempo, spb: spb, beatsPerBar: beatsPerBar,
    patternBeats: patBeats, patternSec: patSec, loops: loops, seamless: seamless, tailSec: tail,
    explicitDuration: s.durationSec !== undefined || !!(loopObj && loopObj.toSeconds), outSec: outSec, renderSec: renderSec, fadeIn: s.fadeIn === undefined ? fadeDefault : s.fadeIn, fadeOut: s.fadeOut === undefined ? fadeDefault : s.fadeOut,
    sf: sfTracks, synth: synthTracks, tone: toneTracks, sfx: sfxOut, midi: midiList, master: master, noteCount: totalNotes, warnings: warnings
  };
}

return { SCORE_VERSION, LIMITS, GM_NAMES, GM_ALIASES, DRUM_MAP, DRUM_WORDS, SYNTH_PRESETS, SYNTH_ALIASES, SFX_PRESET_NAMES, CHORD_TYPES,
  parsePitch, parseDrum, parseChord, parseBeats, parseVelocity, resolveInstrument, instProfile, validateScore, formatValidation, compileScore, zzfxLengthSec };
});
