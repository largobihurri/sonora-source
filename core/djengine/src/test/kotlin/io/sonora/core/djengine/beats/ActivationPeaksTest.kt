package io.sonora.core.djengine.beats

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.BeatActivations
import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import org.junit.Assert.assertThrows
import org.junit.Test

class ActivationPeaksTest {

    private val fps = 50.0
    private val frameMs = 1000.0 / fps

    /** Logits at [baseline] with a Gaussian bump of [height] and [sigmaFrames] centred at each time of [centresMs]. */
    private fun bumps(
        frames: Int,
        centresMs: DoubleArray,
        height: Double = 12.0,
        sigmaFrames: Double = 1.5,
        baseline: Double = -6.0,
    ): FloatArray = FloatArray(frames) { f ->
        var v = baseline
        for (c in centresMs) {
            val d = f - c / frameMs
            v += height * exp(-d * d / (2 * sigmaFrames * sigmaFrames))
        }
        v.toFloat()
    }

    private fun flat(frames: Int, value: Float = -6f) = FloatArray(frames) { value }

    private fun activations(beat: FloatArray, downbeat: FloatArray = flat(beat.size)) = BeatActivations(beat, downbeat, fps)

    // --- sub-frame refinement -------------------------------------------------------------------------------------

    @Test
    fun `gaussian peaks at known sub-frame times are recovered within one millisecond`() {
        val times = DoubleArray(12) { 513.7 + it * 467.3 + (it % 5) * 3.1 }
        val a = activations(bumps(frames = 400, centresMs = times))

        val found = ActivationPeaks.candidates(a).beatsMs()

        assertThat(found.size).isEqualTo(times.size)
        for (i in times.indices) assertThat(abs(found[i] - times[i])).isLessThan(1.0)
    }

    @Test
    fun `sub-frame offsets across a whole frame are all recovered`() {
        for (offsetTenths in 0..10) {
            val t = 1000.0 + offsetTenths * frameMs / 10
            val found = ActivationPeaks.candidates(activations(bumps(100, doubleArrayOf(t)))).beatsMs()

            assertThat(found.size).isEqualTo(1)
            assertThat(abs(found[0] - t)).isLessThan(1.0)
        }
    }

    @Test
    fun `a peak on the first or last frame is kept without refinement`() {
        val beat = flat(30)
        beat[0] = 3f
        beat[29] = 2f

        val found = ActivationPeaks.candidates(activations(beat)).beatsMs()

        assertThat(found.toList()).containsExactly(0.0, 29 * frameMs).inOrder()
    }

    // --- threshold and max-pool -----------------------------------------------------------------------------------

    @Test
    fun `peaks at or below the threshold are dropped`() {
        val beat = flat(60)
        beat[10] = 0.5f
        beat[25] = 0f
        beat[40] = -0.5f

        val found = ActivationPeaks.candidates(activations(beat)).beatsMs()

        assertThat(found.size).isEqualTo(1)
        assertThat(abs(found[0] - 10 * frameMs)).isLessThan(frameMs / 2)
    }

    @Test
    fun `a custom threshold is honoured`() {
        val beat = flat(40)
        beat[10] = 1f
        beat[30] = 3f

        val found = ActivationPeaks.candidates(activations(beat), PeakParams(threshold = 2f)).beatsMs()

        assertThat(found.size).isEqualTo(1)
        assertThat(abs(found[0] - 30 * frameMs)).isLessThan(frameMs / 2)
    }

    @Test
    fun `peaks closer than the pool radius collapse into the highest one`() {
        val beat = flat(60)
        beat[20] = 5f
        beat[23] = 4f // 3 frames away: inside the 7-frame pool of frame 20

        val found = ActivationPeaks.candidates(activations(beat)).beatsMs()

        assertThat(found.size).isEqualTo(1)
        assertThat(abs(found[0] - 20 * frameMs)).isLessThan(frameMs / 2)
    }

    @Test
    fun `peaks four frames apart are both kept`() {
        val beat = flat(60)
        beat[20] = 5f
        beat[24] = 4f

        assertThat(ActivationPeaks.candidates(activations(beat)).beatsMs().size).isEqualTo(2)
    }

    @Test
    fun `a wider pool suppresses more neighbours`() {
        val beat = flat(60)
        beat[20] = 5f
        beat[24] = 4f

        val found = ActivationPeaks.candidates(activations(beat), PeakParams(poolFrames = 11)).beatsMs()

        assertThat(found.size).isEqualTo(1)
    }

    @Test
    fun `invalid parameters are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { PeakParams(poolFrames = 6) }
        assertThrows(IllegalArgumentException::class.java) { PeakParams(poolFrames = 0) }
        assertThrows(IllegalArgumentException::class.java) { PeakParams(downbeatWindowFrames = -1) }
        assertThrows(IllegalArgumentException::class.java) { PeakParams(threshold = Float.NaN) }
    }

    // --- merging --------------------------------------------------------------------------------------------------

    @Test
    fun `a plateau of equal frames is merged into its centre`() {
        val beat = flat(80)
        for (f in 30..33) beat[f] = 4f // centre 31.5
        for (f in 50..51) beat[f] = 3f // centre 50.5

        val found = ActivationPeaks.candidates(activations(beat)).beatsMs()

        assertThat(found.size).isEqualTo(2)
        assertThat(found[0]).isWithin(1e-6).of(31.5 * frameMs)
        assertThat(found[1]).isWithin(1e-6).of(50.5 * frameMs)
    }

    @Test
    fun `peaks two frames apart with a dip between are not merged`() {
        val beat = flat(60)
        beat[20] = 4f
        beat[21] = 3f
        beat[22] = 4f

        val found = ActivationPeaks.candidates(activations(beat)).beatsMs()

        assertThat(found.size).isEqualTo(2)
    }

    // --- downbeat probability -------------------------------------------------------------------------------------

    @Test
    fun `the downbeat probability is the sigmoid of the strongest downbeat logit near the beat`() {
        val beat = flat(100)
        beat[20] = 4f
        beat[50] = 4f
        beat[80] = 4f
        val down = flat(100, -9f)
        down[21] = 2f // one frame after the first beat
        down[52] = 1f
        down[53] = 30f // the strongest value inside the +-3 window of beat 50 (frames 47..53)
        down[90] = 8f // more than 3 frames from every beat: must not count

        val p = ActivationPeaks.candidates(activations(beat, down)).downbeatProbabilities()

        assertThat(p.size).isEqualTo(3)
        assertThat(p[0]).isWithin(1e-6).of(1.0 / (1.0 + exp(-2.0)))
        assertThat(p[1]).isWithin(1e-6).of(1.0 / (1.0 + exp(-30.0)))
        assertThat(p[2]).isWithin(1e-6).of(1.0 / (1.0 + exp(9.0)))
    }

    @Test
    fun `a window of zero frames reads only the beat frame`() {
        val beat = flat(60)
        beat[20] = 4f
        val down = flat(60, -5f)
        down[21] = 5f

        val p = ActivationPeaks.candidates(activations(beat, down), PeakParams(downbeatWindowFrames = 0))
            .downbeatProbabilities()

        assertThat(p[0]).isWithin(1e-6).of(1.0 / (1.0 + exp(5.0)))
    }

    @Test
    fun `downbeat times are the beats that carry a downbeat peak`() {
        val beats = DoubleArray(10) { 400.0 + it * 480.0 }
        val downs = doubleArrayOf(beats[1] + 8.0, beats[5] - 6.0, beats[9])
        val a = activations(bumps(300, beats), bumps(300, downs))

        val c = ActivationPeaks.candidates(a)

        val found = c.beatsMs()
        assertThat(c.downbeatsMs().toList()).containsExactly(found[1], found[5], found[9]).inOrder()
    }

    @Test
    fun `beat candidates default to unknown downbeat probabilities`() {
        val c = io.sonora.core.djmodel.BeatCandidates(doubleArrayOf(0.0, 500.0), doubleArrayOf(0.0))

        assertThat(c.downbeatProbabilities().all { it.isNaN() }).isTrue()
        assertThat(c.beatCount).isEqualTo(2)
        assertThrows(IllegalArgumentException::class.java) {
            io.sonora.core.djmodel.BeatCandidates(doubleArrayOf(0.0, 500.0), doubleArrayOf(), doubleArrayOf(0.5))
        }
        assertThrows(IllegalArgumentException::class.java) {
            io.sonora.core.djmodel.BeatCandidates(doubleArrayOf(0.0), doubleArrayOf(), doubleArrayOf(1.5))
        }
    }

    // --- edge cases -----------------------------------------------------------------------------------------------

    @Test
    fun `no frames and silence give no candidates`() {
        val none = ActivationPeaks.candidates(activations(FloatArray(0)))
        val quiet = ActivationPeaks.candidates(activations(flat(200, -9f)))

        assertThat(none.beatsMs()).isEmpty()
        assertThat(quiet.beatsMs()).isEmpty()
        assertThat(quiet.downbeatsMs()).isEmpty()
    }

    // --- bar phase ------------------------------------------------------------------------------------------------

    /** 128 BPM beats with downbeat bumps on every 4th beat starting at beat [firstDownbeat], plus seeded noise. */
    private fun noisyBarActivations(beatCount: Int, firstDownbeat: Int, seed: Long): BeatActivations {
        val beatMs = 60_000.0 / 128
        val beats = DoubleArray(beatCount) { 300.0 + it * beatMs }
        val downs = DoubleArray(beatCount) { beats[it] }.filterIndexed { i, _ -> i >= firstDownbeat && (i - firstDownbeat) % 4 == 0 }
        val frames = ((beats.last() + 1000) / frameMs).toInt()
        val rng = Random(seed)
        val beat = bumps(frames, beats, baseline = -6.0)
        val down = bumps(frames, downs.toDoubleArray(), height = 8.0, baseline = -5.0)
        for (i in 0 until frames) {
            beat[i] += ((rng.nextDouble() - 0.5) * 3).toFloat()
            down[i] += ((rng.nextDouble() - 0.5) * 3).toFloat()
        }
        return activations(beat, down)
    }

    @Test
    fun `bar phase is recovered for every position of the first downbeat in noisy activations`() {
        for (phase in 0..3) {
            val c = ActivationPeaks.candidates(noisyBarActivations(beatCount = 34, firstDownbeat = phase, seed = 7L + phase))

            assertThat(c.beatCount).isEqualTo(34)
            assertThat(ActivationPeaks.barPhase(c, beatsPerBar = 4)).isEqualTo(phase)
        }
    }

    @Test
    fun `bar phase supports other metres`() {
        val beatMs = 500.0
        val beats = DoubleArray(21) { 300.0 + it * beatMs }
        val downs = beats.filterIndexed { i, _ -> i % 3 == 2 }.toDoubleArray()
        val frames = 600
        val c = ActivationPeaks.candidates(activations(bumps(frames, beats), bumps(frames, downs, baseline = -5.0)))

        assertThat(ActivationPeaks.barPhase(c, beatsPerBar = 3)).isEqualTo(2)
    }

    @Test
    fun `bar phase ignores the uneven number of beats per phase`() {
        // 9 beats: phase 0 owns beats 0, 4, 8; phase 1 owns 1 and 5; the true downbeats are 1 and 5 and 9 would be next.
        val probabilities = doubleArrayOf(0.3, 0.9, 0.1, 0.1, 0.3, 0.9, 0.1, 0.1, 0.3)
        val c = io.sonora.core.djmodel.BeatCandidates(DoubleArray(9) { it * 500.0 }, doubleArrayOf(), probabilities)

        assertThat(ActivationPeaks.barPhase(c, 4)).isEqualTo(1)
    }

    @Test
    fun `bar phase skips unknown probabilities and defaults to zero when none is known`() {
        val unknown = io.sonora.core.djmodel.BeatCandidates(DoubleArray(8) { it * 500.0 }, doubleArrayOf())
        val partial = io.sonora.core.djmodel.BeatCandidates(
            DoubleArray(8) { it * 500.0 },
            doubleArrayOf(),
            doubleArrayOf(Double.NaN, Double.NaN, 0.8, 0.1, Double.NaN, Double.NaN, 0.7, 0.2),
        )

        assertThat(ActivationPeaks.barPhase(unknown, 4)).isEqualTo(0)
        assertThat(ActivationPeaks.barPhase(partial, 4)).isEqualTo(2)
        assertThat(ActivationPeaks.barPhase(io.sonora.core.djmodel.BeatCandidates(DoubleArray(0), DoubleArray(0)), 4)).isEqualTo(0)
        assertThrows(IllegalArgumentException::class.java) { ActivationPeaks.barPhase(unknown, 0) }
    }

    // --- minimal postprocessor ------------------------------------------------------------------------------------

    @Test
    fun `minimal keeps whole frame positions averages adjacent peaks and snaps downbeats to the nearest beat`() {
        val beat = flat(100)
        for (f in 10..11) beat[f] = 3f // merged: 10.5
        beat[40] = 3f
        beat[70] = 3f
        val down = flat(100)
        down[42] = 2f // nearest beat: frame 40
        down[72] = 2f // nearest beat: frame 70
        down[73] = 2f // adjacent to 72: averaged to 72.5, still nearest beat 70

        val m = ActivationPeaks.minimal(activations(beat, down))

        assertThat(m.beatsMs().toList()).containsExactly(10.5 * frameMs, 40 * frameMs, 70 * frameMs).inOrder()
        assertThat(m.downbeatsMs().toList()).containsExactly(40 * frameMs, 70 * frameMs).inOrder()
    }

    // --- golden parity --------------------------------------------------------------------------------------------

    @Test
    fun `minimal postprocessor reproduces the reference beat and downbeat times of every golden`() {
        for (name in BeatThisGoldens.CASES) {
            val golden = BeatThisGoldens.load(name)

            val m = ActivationPeaks.minimal(golden.activations)

            assertWithin(name, "beats", golden.referenceBeatsMs, m.beatsMs(), frameMs)
            assertWithin(name, "downbeats", golden.referenceDownbeatsMs, m.downbeatsMs(), frameMs)
            assertWithin(name, "beats exact", golden.referenceBeatsMs, m.beatsMs(), 0.5)
        }
    }

    @Test
    fun `candidates from the golden logits find every click within the usual 70 ms beat window`() {
        for (name in BeatThisGoldens.CASES) {
            val golden = BeatThisGoldens.load(name)

            val c = ActivationPeaks.candidates(golden.activations)
            val found = c.beatsMs()

            for (click in golden.clickTimesMs) {
                val nearest = found.minOfOrNull { abs(it - click) } ?: Double.POSITIVE_INFINITY
                assertThat(nearest).isLessThan(70.0)
            }
            assertThat(c.beatCount).isEqualTo(c.downbeatProbabilities().size)
        }
    }

    @Test
    fun `candidates of the constant tempo goldens sit a few milliseconds after the click like the model does`() {
        for (name in listOf("click_090", "click_128", "click_174")) {
            val golden = BeatThisGoldens.load(name)
            val found = ActivationPeaks.candidates(golden.activations).beatsMs()

            val offsets = golden.clickTimesMs.map { click -> found.minBy { abs(it - click) } - click }.sorted()

            assertThat(offsets[offsets.size / 2]).isIn(com.google.common.collect.Range.closed(0.0, 25.0))
        }
    }

    @Test
    fun `refined candidates stay within one frame of the minimal postprocessor and never outnumber it`() {
        for (name in BeatThisGoldens.CASES) {
            val golden = BeatThisGoldens.load(name)

            val refined = ActivationPeaks.candidates(golden.activations).beatsMs()

            // The reference splits a plateau of three or more equal frames in several beats (a plateau of four frames
            // gives 0.5 and 2.5 where the centre is 1.5, exactly one frame from each); candidates() keeps one.
            assertThat(refined.size).isAtMost(golden.referenceBeatsMs.size)
            for (t in refined) {
                assertThat(golden.referenceBeatsMs.minOf { abs(it - t) }).isAtMost(frameMs + 1e-6)
            }
        }
    }

    @Test
    fun `candidate downbeats are beats and the silent golden has none`() {
        for (name in BeatThisGoldens.CASES) {
            val c = ActivationPeaks.candidates(BeatThisGoldens.load(name).activations)

            val beats = c.beatsMs().toSet()
            assertThat(c.downbeatsMs().all { it in beats }).isTrue()
        }
        assertThat(ActivationPeaks.candidates(BeatThisGoldens.load("silence").activations).beatCount).isEqualTo(0)
    }

    private fun assertWithin(case: String, what: String, expected: DoubleArray, actual: DoubleArray, toleranceMs: Double) {
        assertThat(actual.size).isEqualTo(expected.size)
        for (i in expected.indices) {
            assertThat(abs(actual[i] - expected[i])).isLessThan(toleranceMs)
        }
    }
}
