package com.inn.lampdemo.lamp

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

enum class Sfx { Squeak, Stretch, On, Off, Twang, Clank, Tap, Pop }

/**
 * Procedural sound effects, a port of the Web Audio graph used by the original page:
 * filtered noise bursts plus pitch-swept oscillators, all with exponential decay envelopes.
 * Effects are synthesized and mixed on a dedicated audio thread.
 */
class LampSound {
    @Volatile
    var muted = false

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private val requests = LinkedBlockingQueue<Pair<Sfx, Float>>()
    private val noise = FloatArray(SR) { Random.nextFloat() * 2 - 1 }

    fun play(sfx: Sfx, amt: Float = 1f) {
        if (!muted && running) requests.offer(sfx to amt)
    }

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "lamp-audio").apply { start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private class Voice(val data: FloatArray, var pos: Int = 0)

    private fun loop() {
        val format = AudioFormat.Builder()
            .setSampleRate(SR)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(format)
            .setBufferSizeInBytes(
                AudioTrack.getMinBufferSize(
                    SR,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT
                )
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        val chunk = FloatArray(256)
        val voices = ArrayList<Voice>()
        var idle = Int.MAX_VALUE
        try {
            while (running) {
                var req = if (voices.isEmpty() && idle > SR / 2) {
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
                    requests.take()
                } else {
                    requests.poll()
                }
                while (req != null) {
                    voices += Voice(synth(req.first, req.second))
                    req = requests.poll()
                }
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()

                chunk.fill(0f)
                val it = voices.iterator()
                while (it.hasNext()) {
                    val v = it.next()
                    val n = minOf(chunk.size, v.data.size - v.pos)
                    for (i in 0 until n) chunk[i] += v.data[v.pos + i]
                    v.pos += n
                    if (v.pos >= v.data.size) it.remove()
                }
                for (i in chunk.indices) chunk[i] = chunk[i].coerceIn(-1f, 1f)
                idle =
                    if (voices.isEmpty()) idle.coerceAtMost(Int.MAX_VALUE - chunk.size) + chunk.size else 0
                track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
            }
        } catch (_: InterruptedException) {
        } finally {
            track.release()
        }
    }

    // ---------- synthesis ----------

    private fun synth(sfx: Sfx, amt: Float): FloatArray = when (sfx) {
        Sfx.Squeak -> {
            val g = amt.coerceIn(0f, 1f)
            val dur = 0.14f + g * 0.16f
            val out = buffer(dur + 0.02f)
            squeak(out, dur, g)
            out
        }

        Sfx.Stretch -> {
            val out = buffer(0.08f)
            val f0 = 80 + amt * 240
            val filter = Biquad(FilterType.BandPass, f0 * 4, 3f)
            val peak = 0.05f + amt * 0.05f
            val n = (0.07f * SR).toInt()
            var phase = 0.0
            for (i in 0 until n) {
                val t = i.toFloat() / SR
                val f = if (t < 0.05f) f0 * (1 + 0.1f * t / 0.05f) else f0 * 1.1f
                phase += f / SR
                out[i] += filter.process(saw(phase)) * expEnv(peak, t, 0.05f)
            }
            burst(out, 0f, 0.02f, FilterType.BandPass, 1200 + amt * 1600, 0.05f, 2f)
            out
        }

        Sfx.On, Sfx.Off -> {
            val out = buffer(0.08f)
            burst(out, 0f, 0.012f, FilterType.HighPass, 2600f, 0.5f)
            burst(
                out,
                0.025f,
                0.014f,
                FilterType.BandPass,
                if (sfx == Sfx.On) 1900f else 1300f,
                0.4f,
                2f
            )
            tone(out, 0f, 150f, 60f, 0.06f, 0.22f, Wave.Sine)
            out
        }

        Sfx.Twang -> {
            val out = buffer(0.18f)
            burst(out, 0f, 0.03f, FilterType.BandPass, 900f, 0.35f, 3f)
            tone(out, 0f, 190f, 110f, 0.16f, 0.18f, Wave.Triangle)
            out
        }

        Sfx.Clank -> {
            val out = buffer(0.37f)
            val g = amt.coerceIn(0.15f, 1f)
            floatArrayOf(1320f, 2210f, 3470f).forEachIndexed { i, f ->
                tone(out, 0f, f, f * 0.98f, 0.35f - i * 0.08f, 0.09f * g, Wave.Triangle)
            }
            burst(out, 0f, 0.02f, FilterType.HighPass, 3000f, 0.4f * g)
            out
        }

        Sfx.Tap -> {
            val out = buffer(0.04f)
            burst(out, 0f, 0.03f, FilterType.LowPass, 500f, amt.coerceIn(0.05f, 0.5f))
            out
        }

        Sfx.Pop -> {
            val out = buffer(0.85f)
            burst(out, 0f, 0.14f, FilterType.LowPass, 900f, 0.9f)
            tone(out, 0f, 220f, 40f, 0.12f, 0.3f, Wave.Sine)
            repeat(16) {
                burst(
                    out,
                    0.02f + Random.nextFloat() * 0.7f,
                    0.02f + Random.nextFloat() * 0.06f,
                    FilterType.BandPass,
                    3000 + Random.nextFloat() * 5000,
                    0.12f + Random.nextFloat() * 0.2f,
                    9f,
                )
            }
            out
        }
    }

    private fun squeak(
        out: FloatArray,
        dur: Float,
        g: Float,
    ) {
        val base = 1400 + Random.nextFloat() * 350
        val lfoF = 26 + Random.nextFloat() * 12
        val depth = base * 0.07f
        val filter = Biquad(FilterType.BandPass, base * 1.2f, 4f)
        val peak = 0.04f + 0.09f * g
        val n = (dur * SR).toInt()
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toFloat() / SR
            val sweep = if (t < dur * 0.4f) {
                lerp(base * 0.8f, base * 1.15f, t / (dur * 0.4f))
            } else {
                lerp(base * 1.15f, base * 0.9f, (t - dur * 0.4f) / (dur * 0.6f))
            }
            val f = sweep + depth * sin(2 * PI * lfoF * t).toFloat()
            phase += f / SR
            val env = if (t < 0.03f) {
                0.0001f * (peak / 0.0001f).pow(t / 0.03f)
            } else {
                peak * (0.0001f / peak).pow((t - 0.03f) / (dur - 0.03f))
            }
            out[i] += filter.process(saw(phase)) * env
        }
    }

    private fun buffer(seconds: Float) = FloatArray((seconds * SR).toInt() + 1)

    private fun burst(
        out: FloatArray,
        start: Float,
        dur: Float,
        type: FilterType,
        freq: Float,
        gain: Float,
        q: Float = 1f,
    ) {
        val filter = Biquad(type, freq, if (type == FilterType.BandPass) q else 0.707f)
        val s0 = (start * SR).toInt()
        val n = (dur * SR).toInt()
        val offset = Random.nextInt(SR / 2)
        for (i in 0 until n) {
            val idx = s0 + i
            if (idx >= out.size) break
            val y = filter.process(noise[(offset + i) % SR])
            out[idx] += y * expEnv(gain, i.toFloat() / SR, dur)
        }
    }

    private fun tone(
        out: FloatArray,
        start: Float,
        f0: Float,
        f1: Float,
        dur: Float,
        gain: Float,
        wave: Wave
    ) {
        val s0 = (start * SR).toInt()
        val n = (dur * SR).toInt()
        var phase = 0.0
        for (i in 0 until n) {
            val idx = s0 + i
            if (idx >= out.size) break
            val t = i.toFloat() / SR
            phase += f0 * (f1 / f0).pow(t / dur) / SR
            val v = when (wave) {
                Wave.Sine -> sin(2 * PI * phase).toFloat()
                Wave.Triangle -> (2 / PI * asin(sin(2 * PI * phase))).toFloat()
            }
            out[idx] += v * expEnv(gain, t, dur)
        }
    }

    private fun expEnv(peak: Float, t: Float, dur: Float) =
        peak * (0.0001f / peak).pow((t / dur).coerceAtMost(1f))

    private fun saw(phase: Double) = (2 * (phase - floor(phase + 0.5))).toFloat()

    private enum class Wave { Sine, Triangle }
    private enum class FilterType { LowPass, HighPass, BandPass }

    private class Biquad(
        type: FilterType,
        freq: Float,
        q: Float,
    ) {
        private val b0: Float
        private val b1: Float
        private val b2: Float
        private val a1: Float
        private val a2: Float
        private var x1 = 0f
        private var x2 = 0f
        private var y1 = 0f
        private var y2 = 0f

        init {
            val w0 = 2 * PI * freq.coerceAtMost(SR * 0.45f) / SR
            val c = cos(w0).toFloat()
            val alpha = (sin(w0) / (2 * q)).toFloat()
            val a0 = 1 + alpha
            val (nb0, nb1, nb2) = when (type) {
                FilterType.LowPass -> Triple((1 - c) / 2, 1 - c, (1 - c) / 2)
                FilterType.HighPass -> Triple((1 + c) / 2, -(1 + c), (1 + c) / 2)
                FilterType.BandPass -> Triple(alpha, 0f, -alpha)
            }
            b0 = nb0 / a0
            b1 = nb1 / a0
            b2 = nb2 / a0
            a1 = -2 * c / a0
            a2 = (1 - alpha) / a0
        }

        fun process(x: Float): Float {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }
    }

    private companion object {
        const val SR = 44100
    }
}
