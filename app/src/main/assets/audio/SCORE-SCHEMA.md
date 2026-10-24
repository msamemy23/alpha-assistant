# Alpha Audio score format (v1)

You write ONE JSON object (a "score"). The app renders it offline to a WAV file (44.1 kHz, 16-bit, stereo) and normalises the loudness to about -14 LUFS, so you only decide **what is played and how the parts balance**, not absolute volume. Output JSON only: double quotes, no comments, no trailing commas.

```json
{ "title": "Door chime", "tempo": 120, "timeSignature": [4,4],
  "tracks": [
    { "name": "piano", "instrument": "Acoustic Grand Piano", "volume": 0.9,
      "notes": [ ["C5",0,1], ["E5",1,1], ["G5",2,2,0.9] ] },
    { "name": "bell",  "instrument": "synth:bell", "notes": [ { "pitch": "C6", "start": 2, "dur": 2, "vel": 0.7 } ] }
  ] }
```

## 1. Time, pitch, loudness

| thing | rule |
|---|---|
| time | **beats** (1 beat = 1 quarter note). `start` counts from 0 (first beat of bar 1 = 0). Bar *n* starts at `(n-1) * beatsPerBar` (4/4: bar 1 = 0, bar 2 = 4, bar 3 = 8 ...). Seconds = beats x 60 / tempo. |
| pitch | note name `"C4"` `"F#3"` `"Bb2"` (middle C = C4 = MIDI 60, octave changes at C) or a MIDI number 0-127. A pitch **array** is a chord: `["C4","E4","G4"]`. |
| chord symbol | instead of `pitch`: `{"chord":"Am7","octave":3,"start":0,"dur":4}` (root sits in `octave`, default 4). Types: (none) m 7 maj7 m7 dim dim7 m7b5 aug sus2 sus4 6 m6 9 maj9 m9 add9 5 7sus4 11 13, slash bass `"C/E"`. Optional `inversion` 0-3, `strum` (beats between chord notes, e.g. 0.03 for a guitar strum). |
| velocity | `vel` 0-1 (default 0.8). Values above 1 are read as MIDI velocity 2-127. |
| array note | `[pitch, start, dur, vel]` (vel optional) - the shortest form, use it for long lists. |

## 2. Top-level fields

| field | meaning (default) |
|---|---|
| `tempo` | BPM 20-300 (120). `bpm` is accepted as an alias. |
| `timeSignature` | `[4,4]`, `[3,4]`, `[6,8]` ... bar = `n * 4 / d` beats. Only used for bar maths (lengthBars, rounding up to full bars). |
| `swing` | 0-1 (0). Delays the off-beat 8ths: 0.3 light, 0.5 medium, 1 triplet shuffle. `swingDivision`: `"8n"` (default) or `"16n"`. |
| `humanize`, `seed` | 0-1 random timing/velocity looseness (0), repeatable with `seed`. Good for guitars, pianos; keep 0 for chiptune/drum machines. |
| `lengthBars` / `lengthBeats` | length of the musical pattern. **Set it for loops** and for pieces that end with silence/rests. Default: last note end rounded up to a whole bar. |
| `durationSec` | exact length of the output file (max 120). Anything later is cut. Default: pattern length + `tailSec`. |
| `tailSec` | extra seconds after the pattern so reverb can ring out (1.5 music, 0.3 sfx). |
| `fadeIn`, `fadeOut` | seconds (default: only a 10 ms de-click; none for seamless loops). |
| `loop` | `true` (2 copies) or `{"times":4}` or `{"toSeconds":60}` (repeat the pattern until it fills 60 s, then cut) ; `"seamless":false` disables the tail wrap. With `times`, or when the length is an exact multiple, the reverb tail is folded onto the start so the WAV loops without a click. Always pair with `lengthBars`. |
| `master` | see section 6. |
| `tracks` | array, max 24 tracks, max 12 000 notes after repeats. |
| `sfx` | array of sound effects (section 7). A score with only `sfx` is a sound-effect score (`"mode":"sfx"` is implied). |
| `midi` | `{"base64":"...","volume":1,"startSec":0}` renders a standard MIDI file with the same SoundFont and mixes it in (see MIDI-IMPORT.md). |

## 3. Tracks

```json
{ "name": "bass", "instrument": "Electric Bass (finger)", "volume": 0.9, "pan": -0.1,
  "transpose": 0, "offset": 0, "mute": false, "solo": false,
  "fx": { "reverb": 0.15, "delay": 0, "chorus": 0 },
  "notes": [ ... ]  }
```

* `instrument` (required): a **General MIDI name** ("Violin", "Acoustic Guitar (nylon)", close names and aliases such as "piano", "guitar", "strings", "flute", "organ" also work), a **GM program number 0-127** (0 = Acoustic Grand Piano; numbers are 0-based, names are safer), `"drums"` (GM drum kit = MIDI channel 10), a kit name (`"power drums"`, `"electronic drums"`, `"808"`, `"jazz drums"`, `"brushes"`, `"orchestra kit"`, `"room kit"`) or `"synth:<preset>"` (section 4).
* `volume` 0-2 (1). SoundFont tracks (GM names/numbers/drums) are capped at 1.0 - lower the others instead. `pan` -1 (left) .. 1 (right).
* `transpose` (whole semitones, melodic tracks), `offset` (beats, shifts the whole track), `mute`, `solo`.
* `fx` (all 0-1 amounts): `reverb` (default 0.15), `delay`, `chorus`. Synth tracks additionally support `distortion`, `lowpass`/`highpass` (Hz), `eq` `{low,mid,high}` in dB, `compressor` (true or `{threshold,ratio}`).
* **Drum tracks** use drum names as `pitch`: `kick snare rim clap closed_hat open_hat pedal_hat tom_low tom tom_high crash ride ride_bell splash tambourine cowbell shaker clave woodblock conga bongo triangle` (or GM drum numbers 35-81: 36 kick, 38 snare, 42 closed hat, 46 open hat, 49 crash, 51 ride).
* **Step sequencer** (best way to write drums and repeating patterns): `"steps": {"kick":"x...x...x...x...", "snare":"....x.......x..."}`. One character = `stepBeats` (default 0.25 = a 16th note); `x` hit, `X` accent, `o` soft, `g` ghost, `1`-`9` velocity (9 = loudest), `.` rest; spaces and `|` are ignored. 16 characters = 1 bar of 4/4. Keys are drum names on drum tracks and note names on melodic tracks (`"C2":"x.x.x.x."`).
* **Repeat**: `"repeat": {"times": 8, "every": 4}` copies everything in the track `times` times, `every` beats apart (for `steps`, `every` defaults to the pattern length, so `"repeat":{"times":8}` gives 8 bars).
* Drum kit can also be chosen with `"kit"`: `{"instrument":"drums","kit":"808", ...}` (standard, room, power, electronic, 808, jazz, brush, orchestra).
* Synth-only extras: `"env": {"attack":0.01,"decay":0.2,"sustain":0.7,"release":0.3}` (seconds, sustain 0-1; used by pad, bass, sub, lead, chiptune, chiptune-tri, strings, organ), `"osc": "sine|triangle|saw|square|pulse"` (oscillator shape of pad, bass, lead, chiptune, strings), `"engine": "tone"` (use the Tone.js voices instead of the fast built-in ones - 5-10x slower, rarely needed).

## 4. Instruments

**GM programs** (0-based): 0 Acoustic Grand Piano, 1 Bright Acoustic Piano, 2 Electric Grand Piano, 3 Honky-tonk Piano, 4 Electric Piano 1, 5 Electric Piano 2, 6 Harpsichord, 7 Clavinet - 8 Celesta, 9 Glockenspiel, 10 Music Box, 11 Vibraphone, 12 Marimba, 13 Xylophone, 14 Tubular Bells, 15 Dulcimer - 16 Drawbar Organ, 17 Percussive Organ, 18 Rock Organ, 19 Church Organ, 20 Reed Organ, 21 Accordion, 22 Harmonica, 23 Tango Accordion - 24 Acoustic Guitar (nylon), 25 Acoustic Guitar (steel), 26 Electric Guitar (jazz), 27 Electric Guitar (clean), 28 Electric Guitar (muted), 29 Overdriven Guitar, 30 Distortion Guitar, 31 Guitar Harmonics - 32 Acoustic Bass, 33 Electric Bass (finger), 34 Electric Bass (pick), 35 Fretless Bass, 36 Slap Bass 1, 37 Slap Bass 2, 38 Synth Bass 1, 39 Synth Bass 2 - 40 Violin, 41 Viola, 42 Cello, 43 Contrabass, 44 Tremolo Strings, 45 Pizzicato Strings, 46 Orchestral Harp, 47 Timpani - 48 String Ensemble 1, 49 String Ensemble 2, 50 Synth Strings 1, 51 Synth Strings 2, 52 Choir Aahs, 53 Voice Oohs, 54 Synth Voice, 55 Orchestra Hit - 56 Trumpet, 57 Trombone, 58 Tuba, 59 Muted Trumpet, 60 French Horn, 61 Brass Section, 62 Synth Brass 1, 63 Synth Brass 2 - 64 Soprano Sax, 65 Alto Sax, 66 Tenor Sax, 67 Baritone Sax, 68 Oboe, 69 English Horn, 70 Bassoon, 71 Clarinet - 72 Piccolo, 73 Flute, 74 Recorder, 75 Pan Flute, 76 Blown Bottle, 77 Shakuhachi, 78 Whistle, 79 Ocarina - 80 Lead 1 (square), 81 Lead 2 (sawtooth), 82 Lead 3 (calliope), 83 Lead 4 (chiff), 84 Lead 5 (charang), 85 Lead 6 (voice), 86 Lead 7 (fifths), 87 Lead 8 (bass + lead) - 88 Pad 1 (new age), 89 Pad 2 (warm), 90 Pad 3 (polysynth), 91 Pad 4 (choir), 92 Pad 5 (bowed), 93 Pad 6 (metallic), 94 Pad 7 (halo), 95 Pad 8 (sweep) - 96 FX 1 (rain), 97 FX 2 (soundtrack), 98 FX 3 (crystal), 99 FX 4 (atmosphere), 100 FX 5 (brightness), 101 FX 6 (goblins), 102 FX 7 (echoes), 103 FX 8 (sci-fi) - 104 Sitar, 105 Banjo, 106 Shamisen, 107 Koto, 108 Kalimba, 109 Bagpipe, 110 Fiddle, 111 Shanai - 112 Tinkle Bell, 113 Agogo, 114 Steel Drums, 115 Woodblock, 116 Taiko Drum, 117 Melodic Tom, 118 Synth Drum, 119 Reverse Cymbal - 120 Guitar Fret Noise, 121 Breath Noise, 122 Seashore, 123 Bird Tweet, 124 Telephone Ring, 125 Helicopter, 126 Applause, 127 Gunshot.

**Synth presets** (`"instrument":"synth:<name>"`, generated, no SoundFont; good for retro/electronic sounds, work at any pitch): `pluck` (Karplus-Strong string), `pad`, `bass`, `sub`, `lead`, `chiptune` (25% pulse), `chiptune-tri`, `chiptune-noise` (8-bit noise hit), `bell`, `keys` (FM e-piano), `organ`, `strings`; drums: `kick snare hat openhat clap tom crash` (one sound, any pitch value works, e.g. `[60,0,0.5]` or `"kick"`) and `drums` (a full kit addressed by GM drum names).

## 5. Music cheat sheet

**Keys.** Pick one key and stay in it. C major notes: `C D E F G A B`. Scales as semitone offsets from the root: major `0 2 4 5 7 9 11`; natural minor `0 2 3 5 7 8 10`; major pentatonic `0 2 4 7 9` (always sounds happy and safe); minor pentatonic `0 3 5 7 10`; blues `0 3 5 6 7 10`; dorian `0 2 3 5 7 9 10`; mixolydian `0 2 4 5 7 9 10`.

| key | scale notes (one octave) |
|---|---|
| C major | C D E F G A B |
| G major | G A B C D E F# |
| D major | D E F# G A B C# |
| F major | F G A Bb C D E |
| A minor | A B C D E F G |
| E minor | E F# G A B C D |
| D minor | D E F G A Bb C |

**Chords in C major** (triads as note arrays): I `["C4","E4","G4"]`, ii `["D4","F4","A4"]`, iii `["E4","G4","B4"]`, IV `["F4","A4","C5"]`, V `["G4","B4","D5"]`, vi `["A4","C5","E5"]`, vii° `["B3","D4","F4"]`. Seventh versions: Cmaj7, Dm7, Em7, Fmaj7, G7, Am7, Bm7b5. In another key use the same Roman-numeral shape (key of G: G C D Em; key of A minor: Am F C G).

**Progressions (one chord per bar unless noted)** - upbeat/pop: I-V-vi-IV (C G Am F); sunny: I-IV-V-I (C F G C); sentimental: vi-IV-I-V (Am F C G); calm/lounge: Imaj7-vi7-IVmaj7-V (Cmaj7 Am7 Fmaj7 G); jazzy: ii7-V7-Imaj7 (Dm7 G7 Cmaj7); dramatic minor: i-VI-III-VII (Am F C G); game/adventure minor: i-VII-VI-V (Am G F E).

**Rhythm patterns (16 steps = 1 bar, 4/4)**
| style | steps |
|---|---|
| four-on-the-floor kick | `x...x...x...x...` |
| backbeat snare | `....x.......x...` |
| rock kick | `x.......x.x.....` |
| 8th hats | `x.x.x.x.x.x.x.x.` |
| 16th hats (accents) | `X.xxX.xxX.xxX.xx` |
| disco open hat on offbeats | `..x...x...x...x.` (open_hat) |
| boom-bap kick / snare | `x..x......x.x...` / `....x.......x...` |
| shaker | `xxxxxxxxxxxxxxxx` with `swing: 0.2` |
| bossa-ish clave | `x..x..x...x..x..` |

Bass: play the chord root on beat 1, root again on the "and" of 2, fifth on 3 (`[root,0,1] [root,1.5,0.5] [fifth,2,1]`), in octave 1-2 (`C2`=36). Chords: whole notes (`dur` 4) for pads, strums on beats for guitars/keys. Melody: mostly chord tones on strong beats (1 and 3), steps of a scale in between, end the phrase on the tonic, repeat the 2-bar hook once with a small change.

**Recipes**
* **Jingle (8-20 s):** 120-140 BPM, major key, I-V-vi-IV, 4-8 bars. Tracks: drums (kick/snare/hat steps with `repeat`), bass, strummed guitar or keys, a bright hook on vibraphone/marimba/synth:pluck, one `synth:bell` sparkle. Last bar: one long tonic chord + crash, `durationSec` = the exact length wanted, `fadeOut` 0.3-0.8.
* **Ringtone (3-8 s, loops):** 140-160 BPM, 2-3 bars, `lengthBars` + `"loop":{"times":1}` (seamless), loud mid-high lead (Marimba, Music Box, synth:chiptune, Electric Piano) in C5-C7, a simple repeating 8th-note hook, little reverb.
* **Hold music (20-120 s):** 70-95 BPM, major 7th chords, no heavy drums (light `brushes` hats or none), warm pad + soft electric piano arpeggio + sparse flute/vibes melody, bass on beats 1 and 3, `fx.reverb` 0.3, `master.targetLufs` -16, `lengthBars` 4-8 + `loop.toSeconds`.
* **Notification / "service ready" chime (0.3-2 s):** use `sfx` presets (`chime`, `ding`, `notify`, `success`, `coin`); rising notes = positive, falling = negative; or 2-3 notes of `synth:bell`/Glockenspiel in a major triad.
* **Game theme:** chiptune lead + `chiptune` arpeggio (`osc:"square"`, 16th notes, pan 0.3) + `chiptune-tri` bass + `kick`/`snare`/`chiptune-noise` steps, 140-170 BPM, minor key, loop 8 bars.
* **Transition / UI sounds:** `sfx` with `whoosh`, `swoosh`, `pop`, `click`, `ding`, `laser`, `powerup`, `explosion`.

**Mix guide** (volume per track, since the final level is normalised): melody 0.85-1, chords 0.6-0.8, pad/strings 0.4-0.6, bass 0.8-1, drums 0.7-0.9, hats/percussion 0.4-0.6. Use `pan` -0.4..0.4 to separate parts. Prefer 3-6 tracks; more tracks blur the sound. Reverb `fx.reverb`: 0.05 drums/bass, 0.15-0.3 melody, 0.3-0.5 pads/bells.

## 6. master

```json
"master": { "gain": 1, "normalize": true, "targetLufs": -14, "ceiling": 0.95,
            "reverb": { "decay": 2.2, "preDelay": 0.02, "damping": 0.5 },
            "delay": { "beats": 0.75, "feedback": 0.35 },
            "compressor": true, "eq": { "low": 0, "mid": 0, "high": 0 } }
```
`normalize`: `true`/`"lufs"` (music default, about -14 LUFS), `"peak"` (sfx default, peak 0.9), `false`. `gain` multiplies after normalising (0.5 = 6 dB quieter). A look-ahead limiter always keeps the peak at or below `ceiling` (<= 0.98). `reverb` shapes the shared reverb used by synth tracks and sfx (`fx.reverb` is the send amount); SoundFont tracks have their own built-in reverb/chorus controlled by the same `fx` numbers. `delay.beats` accepts a number or `"8n"`, `"8n."`, `"4n"`, `"16n"`.

## 7. Sound effects (`sfx`)

```json
"sfx": [ { "preset": "whoosh", "start": 0 }, { "preset": "ding", "start": 0.6, "volume": 0.8, "reverb": 0.3 },
         { "zzfx": [1,0,1568,0.002,0.02,1.1,0,1,0,0,0,0,0,0,0,0,0,0.35,0.02,0,0], "start": 2, "pitch": -5, "pan": 0.3 } ]
```
Fields: `preset` or `zzfx`, `start` (seconds), `volume` 0-2, `pan`, `pitch` (semitones), `reverb` 0-1, `repeat` + `every` (seconds). Presets: beep, blip, tick, click, pop, ding, bell, chime, coin, success, error, buzz, laser, powerup, alert, ring, notify, whoosh, swoosh, explosion.
`zzfx` is a ZzFX parameter array (or an array of such arrays = layers mixed together): `[volume, randomness, frequency, attack, sustain, release, shape, shapeCurve, slide, deltaSlide, pitchJump, pitchJumpTime, repeatTime, noise, modulation, bitCrush, delay, sustainVolume, decay, tremolo, filter]` - frequency in Hz, times in seconds, shape 0 sine / 1 triangle / 2 saw / 3 tan / 4 noise / 5 square, slide: 1 = +500 Hz per second, filter: positive = high-pass, negative = low-pass at 2 x |value| Hz. Alpha extension: item 22 `filterEnd` sweeps the filter cutoff over the sound, item 23 is the filter Q (a whoosh is noise `shape 4` with `filter -60` and `filterEnd -2800`). Use `0`/`null` for defaults. Set `randomness` 0 for repeatable sounds. Bells and chimes: stack 2-4 sine layers at frequency ratios 1 : 2.76 : 5.4 with decreasing volume and long `release`.

## 8. Common mistakes

1. Using **seconds** for `start`/`dur`: they are **beats**. At 120 BPM one beat is 0.5 s.
2. Octave confusion: middle C is `C4` (MIDI 60). Bass lives in octave 1-2, melodies in 4-6, bells/sparkle in 6-7.
3. GM program numbers are **0-based**; "Violin" is 40, not 41. Prefer names.
4. Drum names on a melodic track (or note names on a drum track). Use `"instrument":"drums"` for kits.
5. Writing every repeated bar by hand: use `steps` + `repeat`, or `loop`. Over 12 000 notes is rejected.
6. Looping without `lengthBars`: the loop length then comes from the last note and the loop point may be off-beat.
7. Notes starting at or after `lengthBars` of a looped score are dropped (they would overlap the next copy).
8. `volume` above 1 on SoundFont tracks (clamped); raise nothing - lower the others. The level is normalised anyway.
9. Overlapping notes of the same pitch on one track: make each `dur` end before the next identical pitch starts.
10. Very long scores: max 120 s output and 24 tracks; render a short pattern and loop it.
11. `vel` of 100 means MIDI 100 (0.79). `vel` of 1 means full. 0 is silent.
12. Putting reverb 1.0 everywhere: it turns to mud; keep most tracks at 0.05-0.3.
13. Adding `"engine":"tone"` for no reason: it is 5-10x slower and not needed.

## 9. Validating before you render

`validateScore(json)` (in the harness page as `validateScore`, also `AlphaScore.validateScore`) never throws. It accepts a JSON string or object and returns `{ok, errors[], warnings[], summary, score}`; `AlphaScore.formatValidation(result)` turns it into text such as:

```
Score has 2 error(s):
  ERROR   tracks[0].notes[3].pitch: "H4" is not a note name like C4, F#3, Bb2 or a MIDI number 0-127 (middle C = C4 = 60)
  ERROR   tracks[1].instrument: unknown instrument "pianoo" - did you mean "Acoustic Grand Piano", "Bright Acoustic Piano", "Piccolo"? ...
  warning loop: set lengthBars (or lengthBeats) so the loop length is exact; otherwise it is rounded up from the last note
```
Paths such as `tracks[1].notes[3].pitch` count from 0. Fix every ERROR and send the corrected full JSON again; warnings are optional. The renderer runs the same check and refuses invalid scores with the same text.

<!-- APPENDIX: validator source (the harness loads it from score-core.js; prompt builders may cut this file here) -->

