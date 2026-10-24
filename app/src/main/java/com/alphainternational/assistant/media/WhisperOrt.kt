package com.alphainternational.assistant.media

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Whisper log-mel front end exactly as OpenAI/HF compute it: 16 kHz, n_fft 400, hop 160, 80 slaney mel bands,
 * periodic Hann, reflect padding (center=True), log10, clamp to max-8, (x+4)/4. Output is [80 x 3000] row-major
 * (30 s window; shorter audio is zero padded first).
 */
object WhisperMel {
    const val SR = 16000; const val N_FFT = 400; const val HOP = 160; const val N_SAMPLES = 30 * SR; const val FRAMES = 3000
    private const val BINS = N_FFT / 2 + 1
    private val window = FloatArray(N_FFT) { (0.5 - 0.5 * cos(2 * PI * it / N_FFT)).toFloat() }
    private val cosT = Array(BINS) { k -> FloatArray(N_FFT) { n -> cos(2 * PI * k * n / N_FFT).toFloat() } }
    private val sinT = Array(BINS) { k -> FloatArray(N_FFT) { n -> sin(2 * PI * k * n / N_FFT).toFloat() } }
    private val filters: Array<FloatArray> = melFilters(80)

    private fun hzToMel(f: Double): Double { val fsp = 200.0 / 3; val minLogMel = 1000.0 / fsp; val logstep = ln(6.4) / 27.0; return if (f >= 1000.0) minLogMel + ln(f / 1000.0) / logstep else f / fsp }
    private fun melToHz(m: Double): Double { val fsp = 200.0 / 3; val minLogMel = 1000.0 / fsp; val logstep = ln(6.4) / 27.0; return if (m >= minLogMel) 1000.0 * exp(logstep * (m - minLogMel)) else fsp * m }

    private fun melFilters(nMels: Int): Array<FloatArray> {
        val fftFreqs = DoubleArray(BINS) { it * (SR / 2.0) / (BINS - 1) }
        val lo = hzToMel(0.0); val hi = hzToMel(SR / 2.0)
        val melF = DoubleArray(nMels + 2) { melToHz(lo + (hi - lo) * it / (nMels + 1)) }
        return Array(nMels) { i ->
            val fdiff0 = melF[i + 1] - melF[i]; val fdiff1 = melF[i + 2] - melF[i + 1]
            val enorm = 2.0 / (melF[i + 2] - melF[i])
            FloatArray(BINS) { k ->
                val lower = (fftFreqs[k] - melF[i]) / fdiff0
                val upper = (melF[i + 2] - fftFreqs[k]) / fdiff1
                (max(0.0, min(lower, upper)) * enorm).toFloat()
            }
        }
    }

    fun logMel(audio: FloatArray): FloatArray {
        val x = FloatArray(N_SAMPLES); System.arraycopy(audio, 0, x, 0, min(audio.size, N_SAMPLES))
        val pad = N_FFT / 2
        val p = FloatArray(N_SAMPLES + 2 * pad)                       // reflect padding like torch.stft(center=True)
        for (i in p.indices) { var j = i - pad; if (j < 0) j = -j; if (j >= N_SAMPLES) j = 2 * (N_SAMPLES - 1) - j; p[i] = x[j] }
        val mel = Array(80) { FloatArray(FRAMES) }
        val power = FloatArray(BINS)
        val frame = FloatArray(N_FFT)
        var maxLog = -1e30f
        for (t in 0 until FRAMES) {
            var any = false
            for (n in 0 until N_FFT) { val v = p[t * HOP + n] * window[n]; frame[n] = v; if (v != 0f) any = true }
            if (any) {
                for (k in 0 until BINS) {
                    var re = 0f; var im = 0f; val c = cosT[k]; val s = sinT[k]
                    for (n in 0 until N_FFT) { re += frame[n] * c[n]; im -= frame[n] * s[n] }
                    power[k] = re * re + im * im
                }
            } else java.util.Arrays.fill(power, 0f)
            for (m in 0 until 80) {
                var acc = 0f; val f = filters[m]
                for (k in 0 until BINS) acc += f[k] * power[k]
                val l = Math.log10(max(acc, 1e-10f).toDouble()).toFloat()
                mel[m][t] = l; if (l > maxLog) maxLog = l
            }
        }
        val out = FloatArray(80 * FRAMES)
        for (m in 0 until 80) for (t in 0 until FRAMES) out[m * FRAMES + t] = (max(mel[m][t], maxLog - 8f) + 4f) / 4f
        return out
    }
}

/** Reads GPT-2 byte-level BPE vocab.json for DECODING token ids back to bytes/text (no encoder needed). */
class WhisperVocab(vocabJson: File) {
    private val idToBytes = HashMap<Int, ByteArray>()
    init {
        val bs = ArrayList<Int>(); for (b in 33..126) bs += b; for (b in 161..172) bs += b; for (b in 174..255) bs += b
        val cs = ArrayList<Int>(bs); var n = 0
        for (b in 0..255) if (b !in bs) { bs += b; cs += 256 + n; n++ }
        val uni2byte = HashMap<Char, Int>(); for (i in bs.indices) uni2byte[cs[i].toChar()] = bs[i]
        val o = JSONObject(vocabJson.readText())
        for (key in o.keys()) idToBytes[o.getInt(key)] = ByteArray(key.length) { (uni2byte[key[it]] ?: 63).toByte() }
    }
    fun bytes(id: Int): ByteArray = idToBytes[id] ?: ByteArray(0)
}

/**
 * Whisper on the app's own onnxruntime-android: encoder + merged KV-cache decoder from onnx-community/whisper-*_timestamped
 * (they expose cross_attentions, so word times come from DTW over the model's alignment heads - no extra native library).
 */
class WhisperOrt(private val dir: File, private val encoderFile: String = "encoder_model_int8.onnx", private val decoderFile: String = "decoder_model_merged_int8.onnx", threads: Int = 4) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val gen = JSONObject(File(dir, "generation_config.json").readText())
    private val cfg = JSONObject(File(dir, "config.json").readText())
    private val layers = cfg.getInt("decoder_layers")
    private val heads = cfg.getInt("decoder_attention_heads")
    private val headDim = cfg.getInt("d_model") / heads
    private val vocab = WhisperVocab(File(dir, "vocab.json"))
    private val eos = gen.getInt("eos_token_id"); private val sot = gen.getInt("decoder_start_token_id")
    private val transcribe = gen.getJSONObject("task_to_id").getInt("transcribe"); private val noTimestamps = gen.getInt("no_timestamps_token_id")
    private val suppress = HashSet<Int>().apply { val a = gen.getJSONArray("suppress_tokens"); for (i in 0 until a.length()) add(a.getInt(i)) }
    private val beginSuppress = HashSet<Int>().apply { val a = gen.getJSONArray("begin_suppress_tokens"); for (i in 0 until a.length()) add(a.getInt(i)) }
    private val alignHeads: List<Pair<Int, Int>> = gen.getJSONArray("alignment_heads").let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getInt(0) to p.getInt(1) } } }
    private val langIds: Map<String, Int> = gen.getJSONObject("lang_to_id").let { o -> o.keys().asSequence().associate { it.trim('<', '>', '|') to o.getInt(it) } }
    private val enc: OrtSession; private val dec: OrtSession

    init {
        fun opts() = OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads); setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT) }
        enc = opts().use { env.createSession(File(dir, "onnx/$encoderFile").path, it) }
        dec = opts().use { env.createSession(File(dir, "onnx/$decoderFile").path, it) }
    }

    /** Timing of the last transcribe() call, for logging. */
    var lastTiming = ""

    class Result(val text: String, val language: String, val words: List<Word>)

    /** [audio16k] mono float in -1..1, at most 30 s. language = "en", "es", ... or null to choose between [candidates]. */
    fun transcribe(audio16k: FloatArray, language: String? = null, candidates: List<String> = listOf("en", "es")): Result {
        require(audio16k.size <= WhisperMel.N_SAMPLES) { "chunk audio to 30 s first" }
        val tMel = System.nanoTime()
        val mel = WhisperMel.logMel(audio16k)
        val melMs = (System.nanoTime() - tMel) / 1_000_000
        val audioSec = audio16k.size / 16000f
        OnnxTensor.createTensor(env, FloatBuffer.wrap(mel), longArrayOf(1, 80, 3000)).use { input ->
            val tEnc = System.nanoTime()
            enc.run(mapOf("input_features" to input)).use { encOut ->
                val encMs = (System.nanoTime() - tEnc) / 1_000_000
                val hidden = encOut.get("last_hidden_state").get() as OnnxTensor
                val lang = language ?: detectLanguage(hidden, candidates)
                val tDec = System.nanoTime()
                return decode(hidden, lang, audioSec).also { lastTiming = "mel $melMs ms, encoder $encMs ms, decoder ${(System.nanoTime() - tDec) / 1_000_000} ms for ${it.words.size} words" }
            }
        }
    }

    private fun emptyPast(): MutableMap<String, OnnxTensor> {
        val m = HashMap<String, OnnxTensor>()
        for (l in 0 until layers) for (kind in listOf("decoder", "encoder")) for (kv in listOf("key", "value"))
            m["past_key_values.$l.$kind.$kv"] = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1, heads.toLong(), 0, headDim.toLong()))
        return m
    }

    private fun step(hidden: OnnxTensor, ids: LongArray, useCache: Boolean, past: Map<String, OnnxTensor>): OrtSession.Result {
        val inputs = HashMap<String, OnnxTensor>(past)
        val idT = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong()))
        val cacheT = OnnxTensor.createTensor(env, booleanArrayOf(useCache))
        inputs["input_ids"] = idT; inputs["encoder_hidden_states"] = hidden; inputs["use_cache_branch"] = cacheT
        try { return dec.run(inputs) } finally { idT.close(); cacheT.close() }
    }

    private fun lastLogits(r: OrtSession.Result, rows: Int): FloatArray {
        val t = r.get("logits").get() as OnnxTensor
        val v = t.info.let { (it as ai.onnxruntime.TensorInfo).shape[2].toInt() }
        val fb = t.floatBuffer; val out = FloatArray(v); fb.position((rows - 1) * v); fb.get(out); return out
    }

    private fun detectLanguage(hidden: OnnxTensor, candidates: List<String>): String {
        val past = emptyPast()
        try {
            step(hidden, longArrayOf(sot.toLong()), false, past).use { r ->
                val logits = lastLogits(r, 1)
                return candidates.maxByOrNull { logits[langIds[it] ?: return@maxByOrNull Float.NEGATIVE_INFINITY] } ?: candidates.first()
            }
        } finally { past.values.forEach { it.close() } }
    }

    private fun decode(hidden: OnnxTensor, lang: String, audioSec: Float): Result {
        val frames = min(1500, kotlin.math.ceil(audioSec * 50f).toInt())
        val prompt = longArrayOf(sot.toLong(), langIds.getValue(lang).toLong(), transcribe.toLong(), noTimestamps.toLong())
        val tokens = ArrayList<Int>()                       // generated text tokens
        val attn = List(alignHeads.size) { ArrayList<FloatArray>() }   // [head][row] -> frames
        var past: MutableMap<String, OnnxTensor> = emptyPast()
        var first: OrtSession.Result? = null                // owns the encoder KV cache, kept to the end
        var prev: OrtSession.Result? = null                 // owns the decoder KV cache of the last step
        var ids = prompt
        var useCache = false
        var encoderPast: Map<String, OnnxTensor>? = null
        try {
            for (stepNo in 0 until 224) {
                val r = step(hidden, ids, useCache, past)
                // cross attention rows of the tokens fed in this step, for the alignment heads only
                for ((k, lh) in alignHeads.withIndex()) {
                    val t = r.get("cross_attentions.${lh.first}").get() as OnnxTensor
                    val shape = (t.info as ai.onnxruntime.TensorInfo).shape       // [1, heads, rows, 1500]
                    val rows = shape[2].toInt(); val full = shape[3].toInt(); val fb = t.floatBuffer
                    for (row in 0 until rows) { val a = FloatArray(frames); fb.position((lh.second * rows + row) * full); fb.get(a); attn[k] += a }
                }
                val logits = lastLogits(r, ids.size)
                for (i in suppress) if (i < logits.size) logits[i] = Float.NEGATIVE_INFINITY
                if (stepNo == 0) for (i in beginSuppress) logits[i] = Float.NEGATIVE_INFINITY
                for (i in eos + 1 until logits.size) logits[i] = Float.NEGATIVE_INFINITY        // no specials, no timestamp tokens
                var best = 0; for (i in logits.indices) if (logits[i] > logits[best]) best = i
                // new cache: decoder tensors from this step; encoder tensors only from step 0 (later outputs are empty)
                val next = HashMap<String, OnnxTensor>()
                for (l in 0 until layers) for (kv in listOf("key", "value")) {
                    next["past_key_values.$l.decoder.$kv"] = r.get("present.$l.decoder.$kv").get() as OnnxTensor
                    val ek = "past_key_values.$l.encoder.$kv"
                    next[ek] = if (stepNo == 0) r.get("present.$l.encoder.$kv").get() as OnnxTensor else encoderPast!!.getValue(ek)
                }
                if (stepNo == 0) { encoderPast = next.filterKeys { ".encoder." in it } }
                if (stepNo == 0) { past.values.forEach { it.close() }; first = r }      // the empty tensors we created
                else prev?.takeIf { it !== first }?.close()
                prev = r
                past = next
                if (best == eos) break
                tokens += best; ids = longArrayOf(best.toLong()); useCache = true
            }
        } finally { prev?.takeIf { it !== first }?.close(); first?.close() }

        val bytesPerToken = tokens.map { vocab.bytes(it) }
        val text = String(bytesPerToken.fold(ByteArray(0)) { a, b -> a + b }, Charsets.UTF_8).trim()
        val words = wordTimes(tokens, bytesPerToken, attn, frames)
        return Result(text, lang, words)
    }

    // ---- word timestamps: OpenAI's find_alignment (normalise -> median filter 7 -> mean over heads -> DTW)

    private fun wordTimes(tokens: List<Int>, tokenBytes: List<ByteArray>, attn: List<List<FloatArray>>, frames: Int): List<Word> {
        if (tokens.isEmpty()) return emptyList()
        val rowsTotal = attn[0].size                         // 4 prompt rows + one per generated token fed
        val n = tokens.size
        // rows 3 .. 3+n-1 line up with tokens 0..n-1 (row 3 = <|notimestamps|> predicting token 0); + n+? see below
        val first = 3; val rows = min(n + 1, rowsTotal - first)
        val cost = Array(rows) { FloatArray(frames) }
        for (h in attn.indices) {
            val w = Array(rows) { r -> attn[h][first + r] }
            // normalise each frame across the token axis
            for (f in 0 until frames) {
                var mean = 0f; for (r in 0 until rows) mean += w[r][f]; mean /= rows
                var v = 0f; for (r in 0 until rows) { val d = w[r][f] - mean; v += d * d }
                val std = sqrt(v / rows) + 1e-9f
                for (r in 0 until rows) w[r][f] = (w[r][f] - mean) / std
            }
            for (r in 0 until rows) { val m = medianFilter7(w[r]); for (f in 0 until frames) cost[r][f] -= m[f] / attn.size }
        }
        val (textIdx, timeIdx) = dtw(cost)
        val jumpTimes = ArrayList<Float>()
        for (i in textIdx.indices) if (i == 0 || textIdx[i] != textIdx[i - 1]) jumpTimes += timeIdx[i] / 50f
        // word boundaries: a token whose bytes start with a space starts a new word
        val starts = ArrayList<Int>()
        for (i in 0 until n) if (i == 0 || (tokenBytes[i].isNotEmpty() && tokenBytes[i][0] == 0x20.toByte())) starts += i
        val out = ArrayList<Word>()
        for ((wi, s) in starts.withIndex()) {
            val e = if (wi + 1 < starts.size) starts[wi + 1] else n
            val text = String(tokenBytes.subList(s, e).fold(ByteArray(0)) { a, b -> a + b }, Charsets.UTF_8).trim()
            if (text.isEmpty()) continue
            val t0 = jumpTimes.getOrElse(s) { jumpTimes.last() }
            val t1 = jumpTimes.getOrElse(e) { jumpTimes.last() }
            out += Word(text, t0, max(t1, t0 + 0.04f))
        }
        return out
    }

    private fun medianFilter7(x: FloatArray): FloatArray {
        val out = FloatArray(x.size); val win = FloatArray(7)
        for (i in x.indices) {
            for (k in -3..3) { var j = i + k; if (j < 0) j = -j; if (j >= x.size) j = 2 * (x.size - 1) - j; win[k + 3] = x[max(0, min(x.size - 1, j))] }
            win.sort(); out[i] = win[3]
        }
        return out
    }

    /** OpenAI whisper.timing.dtw: returns (textIndices, timeIndices) of the cheapest monotonic path. */
    private fun dtw(x: Array<FloatArray>): Pair<IntArray, IntArray> {
        val n = x.size; val m = x[0].size
        val cost = Array(n + 1) { FloatArray(m + 1) { Float.POSITIVE_INFINITY } }
        val trace = Array(n + 1) { IntArray(m + 1) { -1 } }
        cost[0][0] = 0f
        for (j in 1..m) for (i in 1..n) {
            val c0 = cost[i - 1][j - 1]; val c1 = cost[i - 1][j]; val c2 = cost[i][j - 1]
            val c: Float; val t: Int
            if (c0 < c1 && c0 < c2) { c = c0; t = 0 } else if (c1 < c0 && c1 < c2) { c = c1; t = 1 } else { c = c2; t = 2 }
            cost[i][j] = x[i - 1][j - 1] + c; trace[i][j] = t
        }
        for (j in 0..m) trace[0][j] = 2
        for (i in 0..n) trace[i][0] = 1
        var i = n; var j = m
        val ti = ArrayList<Int>(); val tj = ArrayList<Int>()
        while (i > 0 || j > 0) {
            ti += i - 1; tj += j - 1
            when (trace[i][j]) { 0 -> { i--; j-- }; 1 -> i--; else -> j-- }
        }
        ti.reverse(); tj.reverse()
        return ti.toIntArray() to tj.toIntArray()
    }

    override fun close() { enc.close(); dec.close() }
}

/**
 * Any length: cuts the audio into pieces of at most 28 s at the quietest spot near the end of each piece, skips pieces that
 * are silent (Whisper invents text such as "Thank you." on silence), keeps the language found in the first speech piece.
 * Word times are shifted back onto the full clip's timeline.
 */
fun WhisperOrt.transcribeLong(pcm16k: FloatArray, language: String? = null, onProgress: (Float) -> Unit = {}): List<Word> {
    val words = ArrayList<Word>()
    var lang = language
    var start = 0
    while (start < pcm16k.size) {
        var end = min(pcm16k.size, start + 28 * 16000)
        if (end < pcm16k.size) {                                   // cut at the quietest 200 ms of the last 6 s
            var bestAt = end; var bestE = Float.MAX_VALUE
            var i = max(start, end - 6 * 16000)
            while (i + 3200 <= end) {
                var e = 0f; for (k in i until i + 3200) e += pcm16k[k] * pcm16k[k]
                if (e < bestE) { bestE = e; bestAt = i + 1600 }
                i += 800
            }
            end = bestAt
        }
        val piece = pcm16k.copyOfRange(start, end)
        if (rms(piece) > 0.0015f) {                                // about -56 dBFS: below this it is silence
            val r = transcribe(piece, lang)
            lang = lang ?: r.language
            val off = start / 16000f
            for (w in r.words) words += Word(w.text, w.startSec + off, w.endSec + off)
        }
        start = end
        onProgress(start.toFloat() / pcm16k.size)
    }
    return words
}
