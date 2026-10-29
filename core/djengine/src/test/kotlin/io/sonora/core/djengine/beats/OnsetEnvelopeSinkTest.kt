package io.sonora.core.djengine.beats

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djengine.fixtures.SyntheticAudio
import io.sonora.core.djmodel.OnsetEnvelope
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.sin

class OnsetEnvelopeSinkTest {

    private val rate = OnsetEnvelopeSink.DEFAULT_SAMPLE_RATE_HZ

    /** Feeds [audio] to a fresh sink in blocks of [blockSize], read from a padded array so the offset is exercised. */
    private fun run(
        audio: FloatArray,
        blockSize: Int = 4_096,
        sink: OnsetEnvelopeSink = OnsetEnvelopeSink(),
    ): OnsetEnvelope {
        val padding = 3
        val padded = FloatArray(audio.size + 2 * padding) { Float.NaN }
        audio.copyInto(padded, padding)
        val instance = sink.create()
        var at = 0
        while (at < audio.size) {
            val count = minOf(blockSize, audio.size - at)
            instance.accept(padded, padding + at, count)
            at += count
        }
        return instance.finish()
    }

    /** Time in ms of the parabola-refined local maximum of [env] nearest [aroundMs] (within [windowMs]). */
    private fun peakMs(env: OnsetEnvelope, aroundMs: Double, windowMs: Double = 40.0): Double {
        val v = env.values()
        val fps = env.framesPerSecond
        val from = ((aroundMs - windowMs) * fps / 1000).toInt().coerceAtLeast(1)
        val to = ((aroundMs + windowMs) * fps / 1000).toInt().coerceAtMost(v.size - 2)
        var best = from
        for (f in from..to) if (v[f] > v[best]) best = f
        val a = v[best - 1].toDouble()
        val b = v[best].toDouble()
        val c = v[best + 1].toDouble()
        val denominator = a - 2 * b + c
        val offset = if (denominator < 0) 0.5 * (a - c) / denominator else 0.0
        return (best + offset) * 1000 / fps
    }

    @Test
    fun `frames follow the hop and cover the whole input`() {
        val sink = OnsetEnvelopeSink(sampleRateHz = 16_000, hopMs = 2.0)
        val env = run(SyntheticAudio.sine(80.0, 1_000.0, 16_000), sink = sink)

        assertThat(env.framesPerSecond).isEqualTo(500.0)
        assertThat(env.frameCount).isEqualTo(500)
        // A hop that is not a whole number of samples is rounded: 2 ms at 22.05 kHz is 44 samples.
        val odd = OnsetEnvelopeSink(sampleRateHz = 22_050, hopMs = 2.0)
        val oddEnv = run(SyntheticAudio.sine(80.0, 1_000.0, 22_050), sink = odd)
        assertThat(oddEnv.framesPerSecond).isWithin(1e-9).of(22_050.0 / 44)
        assertThat(oddEnv.frameCount).isEqualTo(ceil(22_050.0 / 44).toInt())
    }

    @Test
    fun `an empty input gives an empty envelope`() {
        val env = OnsetEnvelopeSink().create().finish()
        assertThat(env.frameCount).isEqualTo(0)
    }

    @Test
    fun `silence gives an all-zero envelope`() {
        val env = run(FloatArray(rate * 2))
        assertThat(env.values().all { it == 0f }).isTrue()
    }

    @Test
    fun `values are normalised to 0 to 1 with a maximum of 1`() {
        val env = run(SyntheticAudio.clickTrack(120.0, 5_000.0, rate))
        val v = env.values()
        assertThat(v.all { it in 0f..1f }).isTrue()
        assertThat(v.max()).isEqualTo(1f)
    }

    @Test
    fun `each click of a track makes an envelope peak on its onset`() {
        val env = run(SyntheticAudio.clickTrack(120.0, 12_000.0, rate, firstBeatMs = 500.0))
        for (k in 0 until 20) {
            val click = 500.0 + 500.0 * k
            if (click > 11_000) break
            assertThat(abs(peakMs(env, click) - click)).isLessThan(0.5)
        }
    }

    @Test
    fun `the envelope is low-band, a high tone burst barely registers`() {
        val seconds = 3.0
        val kick = SyntheticAudio.sine(80.0, 1_000.0, rate, 0.4)
        val hat = SyntheticAudio.sine(5_000.0, 1_000.0, rate, 0.9)
        val audio = FloatArray((seconds * rate).toInt())
        // 80 Hz burst from 0.5 s, 5 kHz burst from 1.8 s, each with a short fade-in so the onset is the burst start.
        for (n in 0 until 6_000) audio[8_000 + n] = (kick[n] * (n / 160f).coerceAtMost(1f))
        for (n in 0 until 6_000) audio[28_800 + n] = (hat[n] * (n / 160f).coerceAtMost(1f))
        val v = run(audio).values()

        val fps = 500
        val kickPeak = (0.4 * fps).toInt().let { from -> v.slice(from..(0.8 * fps).toInt()).max() }
        val hatPeak = (1.7 * fps).toInt().let { from -> v.slice(from..(2.1 * fps).toInt()).max() }
        assertThat(kickPeak).isEqualTo(1f)
        assertThat(hatPeak).isLessThan(0.02f)
    }

    @Test
    fun `block size does not change the envelope`() {
        val audio = SyntheticAudio.clickTrack(128.0, 6_000.0, rate, firstBeatMs = 123.0, accentEvery = 4)
        val reference = run(audio, blockSize = audio.size).values()

        for (block in intArrayOf(1, 7, 64, 100, 999, 4_096)) {
            assertThat(run(audio, blockSize = block).values()).isEqualTo(reference)
        }
    }

    @Test
    fun `non-finite samples count as silence`() {
        val audio = SyntheticAudio.clickTrack(120.0, 3_000.0, rate)
        val dirty = audio.copyOf()
        dirty[5_000] = Float.NaN
        dirty[5_001] = Float.POSITIVE_INFINITY
        val clean = audio.copyOf()
        clean[5_000] = 0f
        clean[5_001] = 0f

        assertThat(run(dirty).values()).isEqualTo(run(clean).values())
    }

    @Test
    fun `a tail of silence after loud audio decays to exact zeros`() {
        val loud = SyntheticAudio.sine(80.0, 500.0, rate, 0.8)
        val audio = loud + FloatArray(rate * 20)
        val v = run(audio).values()
        assertThat(v.takeLast(500).all { it == 0f }).isTrue()
    }

    @Test
    fun `the sink is not used after finish`() {
        val sink = OnsetEnvelopeSink().create()
        sink.accept(FloatArray(10), 0, 10)
        sink.finish()
        assertThrows(IllegalStateException::class.java) { sink.finish() }
        assertThrows(IllegalStateException::class.java) { sink.accept(FloatArray(10), 0, 10) }
    }

    @Test
    fun `bad ranges and bad parameters are rejected`() {
        val sink = OnsetEnvelopeSink().create()
        assertThrows(IllegalArgumentException::class.java) { sink.accept(FloatArray(10), 5, 10) }
        assertThrows(IllegalArgumentException::class.java) { OnsetEnvelopeSink(sampleRateHz = 100) }
        assertThrows(IllegalArgumentException::class.java) { OnsetEnvelopeSink(lowHz = 200.0, highHz = 100.0) }
        assertThrows(IllegalArgumentException::class.java) { OnsetEnvelopeSink(lowHz = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { OnsetEnvelopeSink(highHz = 9_000.0) }
        assertThrows(IllegalArgumentException::class.java) { OnsetEnvelopeSink(hopMs = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { OnsetEnvelopeSink(smoothingMs = -1.0) }
    }

    @Test
    fun `a 60 Hz kick-like burst peaks within its first half cycle`() {
        // A 60 Hz sine with a 3 ms attack and an exponential decay, starting at 750 ms.
        val audio = FloatArray(rate * 2)
        val start = (0.75 * rate).toInt()
        for (n in 0 until rate / 2) {
            val t = n.toDouble() / rate
            val attack = (t / 0.003).coerceAtMost(1.0)
            audio[start + n] = (0.8 * attack * exp(-t / 0.12) * sin(2 * PI * 60.0 * t)).toFloat()
        }
        val env = run(audio)
        // Unlike a click, a 60 Hz burst has no sharp edge for a 40..200 Hz band to see: its envelope climbs over the
        // first half cycle (8.3 ms), so that is the most the peak may lag the start (measured: 8.3 ms). Real kicks
        // start higher and sweep down, so they land closer; the calibration against them belongs to the DJ tools.
        val lag = peakMs(env, 750.0, 60.0) - 750.0
        assertThat(lag).isAtLeast(0.0)
        assertThat(lag).isAtMost(1000.0 / 60 / 2 + 1.0)
    }
}
