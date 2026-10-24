## renderToneCode(jsString, opts)

Runs **trusted** bot-written Tone.js code (never run untrusted text: it can do anything the page can) in an offline context and returns the same result object.

```js
// Body of an async function. Variables in scope: Tone, context (the offline context), duration, sampleRate, helpers
// @duration 6                         <- optional first-lines directive, default 10 s, max 120 s (or opts.durationSec)
const synth = new Tone.PolySynth(Tone.Synth).toDestination();           // connect to the destination = the output
Tone.getTransport().bpm.value = 110;
new Tone.Sequence((time, n) => synth.triggerAttackRelease(n, "8n", time), ["C4","E4","G4","B4"], "8n").start(0);
Tone.getTransport().start(0);                                           // Transport events fire during the offline render
```
* Build the graph and schedule events (absolute `time` in seconds, or Transport); the harness renders it, then normalises to -14 LUFS (`opts.normalize:false` or `"peak"`, `opts.targetLufs`, `opts.gain`, `opts.fadeOut`).
* You may also call `await Tone.Offline(...)` yourself and `return` the resulting buffer.
* `opts.timeLimitMs` (default 60 000). `helpers.checkTime()` throws when the limit is passed - call it inside long loops. Promise-based waits that never finish are cut off by the limit; **synchronous** infinite loops cannot be (see watchdog).
* Tested working in the offline context (http://127.0.0.1, a secure context): Synth, PolySynth, FMSynth, AMSynth, MonoSynth, DuoSynth, MembraneSynth, NoiseSynth, Noise, LFO, Filter, `Tone.Reverb` (`await reverb.ready` first), Freeverb, PluckSynth, Chorus, Convolver, MetalSynth, Sequence/Transport. `PluckSynth`, `Freeverb`, `JCReverb` use AudioWorklet, which needs a secure context (https through WebViewAssetLoader, file://, localhost) - not a plain-http page on a real host name.
* Cost in offline rendering (desktop, 10 s of audio, 20 idle instances): MetalSynth ~0.9 s *each*, Chorus ~0.33 s, EQ3 ~0.2 s, MonoSynth/FMSynth ~0.1 s, Synth ~0.03 s; every note start creates new oscillator nodes (~4-5 ms of render time per note). Keep bot code lean (few instruments, few hundred notes) or use `renderScore` with the built-in synth presets, which is 10x faster.
* Errors come back as `Tone code failed: <message>` (rejected promise + `postError`).

