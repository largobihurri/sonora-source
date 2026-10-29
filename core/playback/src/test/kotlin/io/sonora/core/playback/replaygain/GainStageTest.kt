package io.sonora.core.playback.replaygain

import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import org.junit.Test

/** The ReplayGain gain stage on interleaved float PCM. */
class GainStageTest {

    private val stage = GainStage().apply { configure(RATE) }

    private fun ones(frames: Int, channels: Int = CHANNELS) = FloatArray(frames * channels) { 1f }

    @Test
    fun `at unity the samples are left untouched`() {
        val samples = FloatArray(64) { it / 64f }
        val copy = samples.copyOf()

        stage.process(samples, 32, CHANNELS)

        assertThat(stage.isUnity).isTrue()
        assertThat(samples).isEqualTo(copy)
    }

    @Test
    fun `an immediate gain scales every sample from the first`() {
        stage.setTarget(0.5, immediate = true)
        val samples = FloatArray(8) { if (it % 2 == 0) 0.8f else -0.4f }

        stage.process(samples, 4, CHANNELS)

        for (i in samples.indices) assertThat(samples[i]).isWithin(1e-6f).of(if (i % 2 == 0) 0.4f else -0.2f)
        assertThat(stage.isUnity).isFalse()
    }

    @Test
    fun `a change ramps over about 30 ms instead of stepping`() {
        stage.setTarget(0.25)
        val frames = RATE / 10
        val samples = ones(frames)

        stage.process(samples, frames, CHANNELS)

        val rampFrames = (GainStage.RAMP_SECONDS * RATE).toInt()
        assertThat(samples[0].toDouble()).isGreaterThan(0.99)
        var largestStep = 0.0
        for (f in 1 until frames) largestStep = maxOf(largestStep, abs(samples[f * CHANNELS] - samples[(f - 1) * CHANNELS]).toDouble())
        assertThat(largestStep).isAtMost(0.75 / rampFrames + 1e-6)
        assertThat(samples[rampFrames * CHANNELS].toDouble()).isWithin(1e-6).of(0.25)
        assertThat(samples[(frames - 1) * CHANNELS].toDouble()).isWithin(1e-6).of(0.25)
        // Both channels of a frame get the same gain.
        for (f in 0 until frames) assertThat(samples[f * CHANNELS + 1]).isEqualTo(samples[f * CHANNELS])
    }

    @Test
    fun `the ramp carries on across buffers exactly as in one`() {
        val whole = GainStage().apply { configure(RATE); setTarget(2.0) }
        val split = GainStage().apply { configure(RATE); setTarget(2.0) }
        val frames = 3_000
        val one = ones(frames)
        val pieces = ones(frames)

        whole.process(one, frames, CHANNELS)
        var frame = 0
        while (frame < frames) {
            val n = minOf(37, frames - frame)
            val chunk = pieces.copyOfRange(frame * CHANNELS, (frame + n) * CHANNELS)
            split.process(chunk, n, CHANNELS)
            chunk.copyInto(pieces, frame * CHANNELS)
            frame += n
        }

        for (i in one.indices) assertThat(pieces[i]).isWithin(1e-6f).of(one[i])
    }

    @Test
    fun `a new target during a ramp starts from where the gain is`() {
        stage.setTarget(0.0)
        val first = ones(100)
        stage.process(first, 100, CHANNELS)
        val reached = first[99 * CHANNELS]

        stage.setTarget(1.0)
        val second = ones(1)
        stage.process(second, 1, CHANNELS)

        assertThat(abs(second[0] - reached).toDouble()).isLessThan(0.01)
    }

    @Test
    fun `back at unity after its ramp the stage is unity again`() {
        stage.setTarget(0.5, immediate = true)
        stage.setTarget(1.0)
        assertThat(stage.isUnity).isFalse()

        stage.process(ones(RATE / 10), RATE / 10, CHANNELS)

        assertThat(stage.isUnity).isTrue()
    }

    @Test
    fun `reset goes back to unity`() {
        stage.setTarget(0.5, immediate = true)

        stage.reset()

        assertThat(stage.isUnity).isTrue()
    }

    private companion object {
        const val RATE = 48_000
        const val CHANNELS = 2
    }
}
