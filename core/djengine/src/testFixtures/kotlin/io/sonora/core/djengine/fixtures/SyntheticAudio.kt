package io.sonora.core.djengine.fixtures

import io.sonora.core.domain.analysis.MusicalKey
import java.util.Random
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Deterministic mono test signals for the analysis stages, all at a caller-chosen sample rate (default 44.1 kHz) and
 * with samples within -1..1. Nothing here reads a file or the clock, so a test always sees the same audio.
 *
 * Time arguments are milliseconds. A click placed at `t` ms starts exactly at the first sample at or after `t`
 * (its shape is evaluated at the true fractional offset), so beat positions are known to better than a sample.
 */
object SyntheticAudio {

    const val DEFAULT_SAMPLE_RATE_HZ: Int = 44_100

    private const val CLICK_HZ = 1_000.0
    private const val ACCENT_HZ = 1_500.0
    private const val CLICK_AMPLITUDE = 0.5
    private const val ACCENT_AMPLITUDE = 0.9
    private const val CLICK_DECAY_S = 0.002
    private const val CLICK_LENGTH_S = 0.03
    private const val FADE_MS = 5.0

    /** C3 in Hz: pitch class 0 of the lowest octave the triads use. */
    private const val C3_HZ = 130.8128

    /** Beat times of a constant [bpm] from [firstBeatMs] while they are before [durationMs]. */
    fun beatTimesMs(bpm: Double, firstBeatMs: Double, durationMs: Double): DoubleArray {
        require(bpm.isFinite() && bpm > 0) { "bpm must be positive: $bpm" }
        require(firstBeatMs >= 0 && durationMs >= 0) { "times must not be negative" }
        val beatMs = 60_000.0 / bpm
        val count = if (firstBeatMs >= durationMs) 0 else ceil((durationMs - firstBeatMs) / beatMs).toInt()
        return DoubleArray(count) { firstBeatMs + it * beatMs }
    }

    /**
     * Beat times of a tempo that changes linearly with time from [startBpm] at the first beat to [endBpm] at
     * [durationMs]: beat `k` is where the integral of the tempo reaches `k` beats.
     */
    fun rampBeatTimesMs(startBpm: Double, endBpm: Double, firstBeatMs: Double, durationMs: Double): DoubleArray {
        require(startBpm > 0 && endBpm > 0) { "bpm must be positive" }
        require(firstBeatMs >= 0 && durationMs >= firstBeatMs) { "durationMs must not precede firstBeatMs" }
        val span = durationMs - firstBeatMs
        val times = ArrayList<Double>()
        // Beats since the first one at time t (ms): (b0 t + (b1 - b0) t^2 / (2 span)) / 60000.
        val slope = if (span > 0) (endBpm - startBpm) / (2 * span) else 0.0
        var k = 0
        while (true) {
            val beats = k * 60_000.0
            val t = if (slope == 0.0) beats / startBpm else (-startBpm + sqrt(startBpm * startBpm + 4 * slope * beats)) / (2 * slope)
            if (firstBeatMs + t >= durationMs) break
            times += firstBeatMs + t
            k++
        }
        return times.toDoubleArray()
    }

    /**
     * Silence of [durationMs] with a decaying 1 kHz click at each of [timesMs] (an accented one, louder and at 1.5 kHz,
     * where [accented] says so). The first sample of a click is at full level, so its onset is sharp.
     */
    fun clicks(
        timesMs: DoubleArray,
        durationMs: Double,
        sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
        accented: BooleanArray? = null,
    ): FloatArray {
        val out = silence(durationMs, sampleRateHz)
        require(accented == null || accented.size == timesMs.size) { "accented needs one flag per click" }
        timesMs.forEachIndexed { i, timeMs ->
            val loud = accented?.get(i) == true
            val hz = if (loud) ACCENT_HZ else CLICK_HZ
            val amplitude = if (loud) ACCENT_AMPLITUDE else CLICK_AMPLITUDE
            val start = ceil(timeMs * sampleRateHz / 1000.0).toInt()
            var n = start
            while (n < out.size) {
                val tau = n.toDouble() / sampleRateHz - timeMs / 1000.0
                if (tau > CLICK_LENGTH_S) break
                out[n] += (amplitude * cos(2 * PI * hz * tau) * exp(-tau / CLICK_DECAY_S)).toFloat()
                n++
            }
        }
        return out
    }

    /** A click on every beat of a constant tempo; with [accentEvery] > 0 the first and every n-th beat after it are accented (a downbeat). */
    fun clickTrack(
        bpm: Double,
        durationMs: Double,
        sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
        firstBeatMs: Double = 0.0,
        accentEvery: Int = 0,
    ): FloatArray {
        val times = beatTimesMs(bpm, firstBeatMs, durationMs)
        val accents = if (accentEvery > 0) BooleanArray(times.size) { it % accentEvery == 0 } else null
        return clicks(times, durationMs, sampleRateHz, accents)
    }

    /** A click on every beat of the linear tempo ramp of [rampBeatTimesMs]. */
    fun tempoRamp(
        startBpm: Double,
        endBpm: Double,
        durationMs: Double,
        sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
        firstBeatMs: Double = 0.0,
    ): FloatArray = clicks(rampBeatTimesMs(startBpm, endBpm, firstBeatMs, durationMs), durationMs, sampleRateHz)

    /** A pure sine. */
    fun sine(
        frequencyHz: Double,
        durationMs: Double,
        sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
        amplitude: Double = 0.5,
    ): FloatArray {
        val out = silence(durationMs, sampleRateHz)
        for (n in out.indices) out[n] = (amplitude * sin(2 * PI * frequencyHz * n / sampleRateHz)).toFloat()
        return out
    }

    /** Frequency in Hz of pitch class [pitchClass] (0 = C .. 11 = B) in the octave from C3 shifted by [octaveOffset]. */
    fun pitchClassHz(pitchClass: Int, octaveOffset: Int = 0): Double =
        C3_HZ * 2.0.pow(octaveOffset + pitchClass / 12.0)

    /** The tonic triad of [key] (root, third, fifth, each with two harmonics), peak at most [amplitude]. */
    fun triad(key: MusicalKey, durationMs: Double, sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ, amplitude: Double = 0.5): FloatArray =
        chord(rootOf(key), thirdOf(key), 7, durationMs, sampleRateHz, amplitude)

    /** The tonic triad of each of [keys] in turn, [chordMs] each. */
    fun triads(keys: List<MusicalKey>, chordMs: Double, sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ, amplitude: Double = 0.5): FloatArray =
        join(keys.map { triad(it, chordMs, sampleRateHz, amplitude) })

    /** I, IV, V, I of [key] (natural minor for a minor key), [chordMs] each: a piece that stays inside the key. */
    fun diatonicProgression(key: MusicalKey, chordMs: Double, sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ, amplitude: Double = 0.5): FloatArray {
        val root = rootOf(key)
        val third = thirdOf(key)
        val chords = listOf(root, root + 5, root + 7, root).map { chord(it, third, 7, chordMs, sampleRateHz, amplitude) }
        return join(chords)
    }

    /**
     * Silence of [durationMs] with a burst of white noise ([burstMs] long, peak at most [amplitude]) at each of
     * [timesMs]; the same [seed] gives the same noise.
     */
    fun noiseBursts(
        timesMs: DoubleArray,
        burstMs: Double,
        durationMs: Double,
        sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
        seed: Long = 1L,
        amplitude: Double = 0.5,
    ): FloatArray {
        val out = silence(durationMs, sampleRateHz)
        val random = Random(seed)
        for (timeMs in timesMs) {
            val from = (timeMs * sampleRateHz / 1000.0).roundToInt()
            val to = minOf(out.size, from + (burstMs * sampleRateHz / 1000.0).roundToInt())
            for (n in from until to) out[n] = ((random.nextDouble() * 2 - 1) * amplitude).toFloat()
        }
        return out
    }

    private fun rootOf(key: MusicalKey): Int = (key.index - 1) % 12

    /** Semitones from the root to the third: 4 for major, 3 for minor. */
    private fun thirdOf(key: MusicalKey): Int = if (key.isMinor) 3 else 4

    private fun chord(rootPc: Int, thirdSemitones: Int, fifthSemitones: Int, durationMs: Double, rate: Int, amplitude: Double): FloatArray {
        val out = silence(durationMs, rate)
        val notes = listOf(rootPc, rootPc + thirdSemitones, rootPc + fifthSemitones)
        // Harmonics at 1, 2 and 3 times the note with weights 1, 1/2, 1/3: enough partials for pitch-class detectors.
        val weights = doubleArrayOf(1.0, 0.5, 1.0 / 3)
        val peak = notes.size * weights.sum()
        val fadeSamples = (FADE_MS * rate / 1000.0).roundToInt().coerceAtLeast(1)
        for (n in out.indices) {
            var v = 0.0
            for (note in notes) {
                val base = pitchClassHz(note)
                for (h in weights.indices) v += weights[h] * sin(2 * PI * base * (h + 1) * n / rate)
            }
            val fade = minOf(1.0, minOf(n, out.size - 1 - n).toDouble() / fadeSamples)
            out[n] = (amplitude * fade * v / peak).toFloat()
        }
        return out
    }

    private fun silence(durationMs: Double, sampleRateHz: Int): FloatArray {
        require(sampleRateHz > 0) { "sampleRateHz must be positive: $sampleRateHz" }
        require(durationMs.isFinite() && durationMs >= 0) { "durationMs must not be negative: $durationMs" }
        return FloatArray((durationMs * sampleRateHz / 1000.0).roundToInt())
    }

    private fun join(parts: List<FloatArray>): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var at = 0
        for (part in parts) {
            part.copyInto(out, at)
            at += part.size
        }
        return out
    }
}
