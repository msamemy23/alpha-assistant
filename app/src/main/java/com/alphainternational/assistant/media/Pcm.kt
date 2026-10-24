package com.alphainternational.assistant.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Interleaved 16-bit PCM. */
class PcmClip(val sampleRate: Int, val channels: Int, val samples: ShortArray) {
    val frames get() = samples.size / channels
    val durationMs get() = frames * 1000L / sampleRate
}

object PcmDecoder {
    /** Decodes the first audio track of any file Android can open. [startUs]/[maxDurationUs] cut a window out of it. */
    fun decode(context: Context, uri: Uri, startUs: Long = 0, maxDurationUs: Long = Long.MAX_VALUE): PcmClip {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        try {
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("no audio track in $uri")
            extractor.selectTrack(track)
            val inFormat = extractor.getTrackFormat(track)
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val endUs = if (maxDurationUs == Long.MAX_VALUE) Long.MAX_VALUE else startUs + maxDurationUs

            val codec = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(inFormat, null, null, 0)
            codec.start()
            var rate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var out = ShortArray(rate * channels * 30)
            var n = 0
            fun ensure(extra: Int) { if (n + extra > out.size) out = out.copyOf(max(out.size * 2, n + extra)) }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            try {
                while (!outputDone) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            val size = extractor.readSampleData(buf, 0)
                            val t = extractor.sampleTime
                            if (size < 0 || t > endUs) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, size, t, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        o >= 0 -> {
                            val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                val f = buf.asFloatBuffer()
                                ensure(f.remaining())
                                while (f.hasRemaining()) out[n++] = (f.get().coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                            } else {
                                val s = buf.asShortBuffer()
                                ensure(s.remaining())
                                val len = s.remaining(); s.get(out, n, len); n += len
                            }
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        }
                    }
                }
            } finally { codec.stop(); codec.release() }
            return PcmClip(rate, channels, out.copyOf(n))
        } finally { extractor.release() }
    }
}

// ------------------------------------------------------------------ conversions

/** Average of all channels, scaled to -1..1. */
fun PcmClip.toMonoFloat(): FloatArray {
    val mono = FloatArray(frames)
    for (i in 0 until frames) {
        var sum = 0
        for (c in 0 until channels) sum += samples[i * channels + c]
        mono[i] = sum / (channels * 32768f)
    }
    return mono
}

fun PcmClip.channelFloats(): List<FloatArray> = List(channels) { c -> FloatArray(frames) { samples[it * channels + c] / 32768f } }

/** Windowed-sinc resampler (Hann window, 8 lobes each side). Good enough for speech models and loudness metering. */
fun resample(x: FloatArray, fromRate: Int, toRate: Int): FloatArray {
    if (fromRate == toRate) return x
    val ratio = fromRate.toDouble() / toRate
    val cutoff = min(1.0, toRate.toDouble() / fromRate)       // low-pass at the lower Nyquist
    val half = 8.0 / cutoff
    val outLen = floor(x.size / ratio).toInt()
    val y = FloatArray(outLen)
    for (n in 0 until outLen) {
        val center = n * ratio
        val from = max(0, ceil(center - half).toInt())
        val to = min(x.size - 1, floor(center + half).toInt())
        var acc = 0.0
        for (k in from..to) {
            val d = k - center
            val arg = PI * d * cutoff
            val sinc = if (abs(arg) < 1e-9) 1.0 else sin(arg) / arg
            val window = 0.5 + 0.5 * cos(PI * d / half)
            acc += x[k] * sinc * window * cutoff
        }
        y[n] = acc.toFloat()
    }
    return y
}

/** What speech models want: 16 kHz, mono, float32 in -1..1. */
fun PcmClip.toMono16k(): FloatArray = resample(toMonoFloat(), sampleRate, 16000)

// ------------------------------------------------------------------ analysis

data class Span(val startMs: Long, val endMs: Long)

object AudioAnalysis {
    /** RMS per window in dBFS (-120 floor). */
    fun rmsDb(x: FloatArray, rate: Int, windowMs: Int = 20): FloatArray {
        val w = rate * windowMs / 1000
        return FloatArray(x.size / w) { i ->
            var s = 0.0
            for (k in i * w until (i + 1) * w) s += x[k] * x[k]
            max(-120f, (10 * log10(s / w + 1e-12)).toFloat())
        }
    }

    /** Stretches quieter than [thresholdDb] for at least [minMs] (end of a clip counts as silence too). */
    fun silences(x: FloatArray, rate: Int, thresholdDb: Float = -45f, minMs: Int = 400, windowMs: Int = 20): List<Span> {
        val db = rmsDb(x, rate, windowMs)
        val out = ArrayList<Span>()
        var start = -1
        for (i in 0..db.size) {
            val quiet = i < db.size && db[i] < thresholdDb
            if (quiet && start < 0) start = i
            if (!quiet && start >= 0) {
                if ((i - start) * windowMs >= minMs) out += Span(start * windowMs.toLong(), i * windowMs.toLong())
                start = -1
            }
        }
        return out
    }

    fun peakDb(x: FloatArray): Float = (20 * log10(max(1e-6f, x.maxOfOrNull { abs(it) } ?: 0f).toDouble())).toFloat()

    /** Min/max envelope image the bot can look at ("is there sound where I think there is?"). */
    fun waveformBitmap(x: FloatArray, rate: Int, width: Int = 1200, height: Int = 240): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(18, 18, 24))
        val line = Paint().apply { color = Color.rgb(90, 200, 160); strokeWidth = 1f }
        val tick = Paint().apply { color = Color.GRAY; textSize = 18f }
        val mid = height / 2f
        val per = max(1, x.size / width)
        for (px in 0 until width) {
            var lo = 0f; var hi = 0f
            for (k in px * per until min(x.size, (px + 1) * per)) { lo = min(lo, x[k]); hi = max(hi, x[k]) }
            c.drawLine(px.toFloat(), mid - hi * mid, px.toFloat(), mid - lo * mid + 1f, line)
        }
        val totalSec = x.size.toFloat() / rate
        var t = 0
        while (t < totalSec) { c.drawText("${t}s", t / totalSec * width, height - 4f, tick); t += max(1, (totalSec / 10).toInt()) }
        return bmp
    }
}

/** ITU-R BS.1770-4 integrated loudness (LUFS) with the 48 kHz K-weighting filters and both gates. */
object LoudnessMeter {
    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
    }

    private fun kWeight(x: FloatArray): DoubleArray {
        val shelf = Biquad(1.53512485958697, -2.69169618940638, 1.19839281085285, -1.69065929318241, 0.73248077421585)
        val highPass = Biquad(1.0, -2.0, 1.0, -1.99004745483398, 0.99007225036621)
        return DoubleArray(x.size) { highPass.process(shelf.process(x[it].toDouble())) }
    }

    /** [channels] are equal-length float arrays (mono or L/R). Returns LUFS, or -70 if the audio is silent. */
    fun integratedLufs(channels: List<FloatArray>, rate: Int): Float {
        val ch = if (rate == 48000) channels else channels.map { resample(it, rate, 48000) }
        val weighted = ch.map { kWeight(it) }
        val block = 19200; val hop = 4800                                 // 400 ms blocks, 75 % overlap
        val n = weighted[0].size
        if (n < block) return -70f
        val energies = ArrayList<Double>()
        var start = 0
        while (start + block <= n) {
            var z = 0.0
            for (w in weighted) { var s = 0.0; for (i in start until start + block) s += w[i] * w[i]; z += s / block }
            energies += z
            start += hop
        }
        fun lufs(z: Double) = -0.691 + 10 * log10(z + 1e-20)
        val abs = energies.filter { lufs(it) > -70.0 }
        if (abs.isEmpty()) return -70f
        val relGate = lufs(abs.average()) - 10.0
        val gated = abs.filter { lufs(it) > relGate }
        return lufs(gated.average()).toFloat()
    }

    /** Gain to hit [targetLufs] without pushing the sample peak above [ceilingDb]. */
    fun gainDbFor(measuredLufs: Float, peakDb: Float, targetLufs: Float = -14f, ceilingDb: Float = -1f): Float =
        min(targetLufs - measuredLufs, ceilingDb - peakDb)
}

// ------------------------------------------------------------------ mixing + wav

/** Sums tracks (each with a linear gain) into one buffer with a soft knee above 0.9 so nothing wraps around. */
fun mixTracks(tracks: List<Pair<FloatArray, Float>>): FloatArray {
    val len = tracks.maxOf { it.first.size }
    val out = FloatArray(len)
    for ((x, g) in tracks) for (i in x.indices) out[i] += x[i] * g
    for (i in out.indices) {
        val v = out[i]
        val a = abs(v)
        out[i] = if (a <= 0.9f) v else Math.signum(v) * (0.9f + 0.1f * Math.tanh(((a - 0.9f) / 0.1f).toDouble()).toFloat())
    }
    return out
}

/** Mono 16-bit WAV, readable by MediaItem / MediaExtractor. */
fun writeWavMono16(file: File, x: FloatArray, rate: Int) {
    val bytes = ByteBuffer.allocate(x.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    for (v in x) bytes.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
    RandomAccessFile(file, "rw").use { f ->
        f.setLength(0)
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + bytes.capacity()).put("WAVE".toByteArray()).put("fmt ".toByteArray())
        h.putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(bytes.capacity())
        f.write(h.array()); f.write(bytes.array())
    }
}

fun rms(x: FloatArray): Float = sqrt(x.fold(0.0) { a, v -> a + v * v } / max(1, x.size)).toFloat()

