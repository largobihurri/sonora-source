package io.sonora.tools.calibration

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.GridBeat
import org.junit.Assert.assertThrows
import org.junit.Test

class BeatMetricsTest {

    private fun times(vararg ms: Double) = doubleArrayOf(*ms)

    @Test
    fun `hand computed F-measure`() {
        val reference = times(0.0, 500.0, 1000.0, 1500.0, 2000.0)
        val ours = times(10.0, 520.0, 1100.0, 1490.0, 2500.0)

        val score = BeatMetrics.score(ours, reference)

        // 0-10, 500-520 and 1500-1490 match; 1100 and 2500 are too far from 1000 and 2000.
        assertThat(score.matched).isEqualTo(3)
        assertThat(score.oursCount).isEqualTo(5)
        assertThat(score.referenceCount).isEqualTo(5)
        assertThat(score.precision).isWithin(1e-12).of(0.6)
        assertThat(score.recall).isWithin(1e-12).of(0.6)
        assertThat(score.fMeasure).isWithin(1e-12).of(0.6)
        assertThat(score.offsetsMs).usingTolerance(1e-9).containsExactly(10.0, 20.0, -10.0).inOrder()
    }

    @Test
    fun `the window is inclusive at 70 ms`() {
        val onEdge = BeatMetrics.score(times(1070.0), times(1000.0))
        assertThat(onEdge.matched).isEqualTo(1)
        assertThat(onEdge.offsetsMs[0]).isEqualTo(70.0)

        val earlyEdge = BeatMetrics.score(times(930.0), times(1000.0))
        assertThat(earlyEdge.matched).isEqualTo(1)
        assertThat(earlyEdge.offsetsMs[0]).isEqualTo(-70.0)

        val outside = BeatMetrics.score(times(1071.0), times(1000.0))
        assertThat(outside.matched).isEqualTo(0)
        assertThat(outside.fMeasure).isEqualTo(0.0)
    }

    @Test
    fun `a custom window is honoured`() {
        val score = BeatMetrics.score(times(1050.0), times(1000.0), toleranceMs = 40.0)
        assertThat(score.matched).isEqualTo(0)
    }

    @Test
    fun `matching is one to one`() {
        // Two estimates around one reference: only one may match, so precision drops to 1/2.
        val score = BeatMetrics.score(times(100.0, 110.0), times(105.0))

        assertThat(score.matched).isEqualTo(1)
        assertThat(score.precision).isEqualTo(0.5)
        assertThat(score.recall).isEqualTo(1.0)
        assertThat(score.fMeasure).isWithin(1e-12).of(2.0 / 3.0)
        // The earliest estimate in the window is taken.
        assertThat(score.offsetsMs.toList()).containsExactly(-5.0)
    }

    @Test
    fun `matching has maximum cardinality`() {
        // A nearest-first pairing would give 100-120 and leave 160 unmatched; the maximum matching has two pairs.
        val score = BeatMetrics.score(times(40.0, 120.0), times(100.0, 160.0))

        assertThat(score.matched).isEqualTo(2)
        assertThat(score.fMeasure).isEqualTo(1.0)
        assertThat(score.offsetsMs.toList()).containsExactly(-60.0, -40.0).inOrder()
    }

    @Test
    fun `unsorted input is sorted first`() {
        val score = BeatMetrics.score(times(520.0, 10.0), times(500.0, 0.0))
        assertThat(score.matched).isEqualTo(2)
        assertThat(score.offsetsMs.toList()).containsExactly(10.0, 20.0).inOrder()
    }

    @Test
    fun `empty sequences score zero`() {
        assertThat(BeatMetrics.score(DoubleArray(0), times(1.0)).fMeasure).isEqualTo(0.0)
        assertThat(BeatMetrics.score(times(1.0), DoubleArray(0)).fMeasure).isEqualTo(0.0)
        val both = BeatMetrics.score(DoubleArray(0), DoubleArray(0))
        assertThat(both.fMeasure).isEqualTo(0.0)
        assertThat(both.precision).isEqualTo(0.0)
        assertThat(both.recall).isEqualTo(0.0)
        assertThat(both.offsetsMs).isEmpty()
    }

    @Test
    fun `identical sequences score one with zero offsets`() {
        val beats = times(0.0, 468.75, 937.5, 1406.25)
        val score = BeatMetrics.score(beats, beats)
        assertThat(score.fMeasure).isEqualTo(1.0)
        assertThat(score.offsets.median).isEqualTo(0.0)
        assertThat(score.offsets.p95).isEqualTo(0.0)
    }

    @Test
    fun `beats before the minimum time are ignored on both sides`() {
        val reference = times(0.0, 4000.0, 5000.0, 6000.0)
        val ours = times(100.0, 300.0, 5010.0, 6020.0)

        val score = BeatMetrics.score(ours, reference, minBeatTimeMs = 5000.0)

        assertThat(score.referenceCount).isEqualTo(2)
        assertThat(score.oursCount).isEqualTo(2)
        assertThat(score.matched).isEqualTo(2)
        assertThat(score.fMeasure).isEqualTo(1.0)
    }

    @Test
    fun `median and p95 of the signed offsets`() {
        // Our beats are late by 10, 20, 30, 40 and 50 ms.
        val reference = times(0.0, 1000.0, 2000.0, 3000.0, 4000.0)
        val ours = times(10.0, 1020.0, 2030.0, 3040.0, 4050.0)

        val score = BeatMetrics.score(ours, reference)

        assertThat(score.offsets.count).isEqualTo(5)
        assertThat(score.offsets.median).isEqualTo(30.0)
        assertThat(score.offsets.mean).isEqualTo(30.0)
        // rank 0.95 * 4 = 3.8 -> 40 + 0.8 * 10
        assertThat(score.offsets.p95).isWithin(1e-9).of(48.0)
    }

    @Test
    fun `the sign is ours minus reference`() {
        val early = BeatMetrics.score(times(990.0), times(1000.0))
        assertThat(early.offsets.median).isEqualTo(-10.0)
    }

    @Test
    fun `offsets are pooled per format`() {
        val mp3a = BeatMetrics.score(times(25.0, 525.0), times(0.0, 500.0)) // +25, +25
        val mp3b = BeatMetrics.score(times(45.0), times(0.0)) // +45
        val flac = BeatMetrics.score(times(-5.0, 495.0), times(0.0, 500.0)) // -5, -5

        val byFormat = BeatMetrics.offsetSummaryByFormat(
            listOf("mp3" to mp3a, "flac" to flac, "mp3" to mp3b),
        )

        assertThat(byFormat.keys).containsExactly("flac", "mp3").inOrder()
        assertThat(byFormat.getValue("mp3").count).isEqualTo(3)
        assertThat(byFormat.getValue("mp3").median).isEqualTo(25.0)
        assertThat(byFormat.getValue("mp3").mean).isWithin(1e-9).of(95.0 / 3.0)
        assertThat(byFormat.getValue("flac").median).isEqualTo(-5.0)
    }

    @Test
    fun `a format with no matched beats still gets a row`() {
        val none = BeatMetrics.score(times(5000.0), times(0.0))
        val byFormat = BeatMetrics.offsetSummaryByFormat(listOf("ogg" to none))
        assertThat(byFormat.getValue("ogg").count).isEqualTo(0)
        assertThat(byFormat.getValue("ogg").median).isNaN()
    }

    // --- downbeats ---

    private fun beats(vararg timeAndPosition: Pair<Double, Int>) = timeAndPosition.map { BarBeat(it.first, it.second) }

    @Test
    fun `downbeat accuracy is the fraction of reference downbeats matched`() {
        val reference = beats(0.0 to 1, 500.0 to 2, 1000.0 to 3, 1500.0 to 4, 2000.0 to 1, 4000.0 to 1)
        val ours = beats(30.0 to 1, 530.0 to 2, 2100.0 to 1, 4050.0 to 1)

        val score = BeatMetrics.downbeats(ours, reference)

        // 0-30 and 4000-4050 match; 2000-2100 is 100 ms away.
        assertThat(score.referenceCount).isEqualTo(3)
        assertThat(score.oursCount).isEqualTo(3)
        assertThat(score.matched).isEqualTo(2)
        assertThat(score.accuracy).isWithin(1e-12).of(2.0 / 3.0)
    }

    @Test
    fun `downbeat accuracy without reference downbeats is zero`() {
        val score = BeatMetrics.downbeats(beats(0.0 to 1), beats(0.0 to 2))
        assertThat(score.referenceCount).isEqualTo(0)
        assertThat(score.accuracy).isEqualTo(0.0)
    }

    @Test
    fun `bar phase agrees when the beat positions of matched beats are equal`() {
        val reference = beats(0.0 to 1, 500.0 to 2, 1000.0 to 3, 1500.0 to 4, 2000.0 to 1)
        val same = BeatMetrics.barPhase(reference.map { BarBeat(it.timeMs + 10, it.beatInBar) }, reference)

        assertThat(same.matchedBeats).isEqualTo(5)
        assertThat(same.agreement).isEqualTo(1.0)
        assertThat(same.bestShift).isEqualTo(0)
        assertThat(same.shiftedAgreement).isEqualTo(1.0)
    }

    @Test
    fun `a constant bar phase error is found as a shift`() {
        val reference = beats(0.0 to 1, 500.0 to 2, 1000.0 to 3, 1500.0 to 4, 2000.0 to 1, 2500.0 to 2)
        // Ours counts the same beats one position late: 2, 3, 4, 1, 2, 3.
        val ours = reference.map { BarBeat(it.timeMs, it.beatInBar % 4 + 1) }

        val phase = BeatMetrics.barPhase(ours, reference)

        assertThat(phase.agreement).isEqualTo(0.0)
        // Adding 3 to our position (mod 4) gives the reference position.
        assertThat(phase.bestShift).isEqualTo(3)
        assertThat(phase.shiftedAgreement).isEqualTo(1.0)
    }

    @Test
    fun `bar phase with no matched beats is empty`() {
        val phase = BeatMetrics.barPhase(beats(9000.0 to 1), beats(0.0 to 1))
        assertThat(phase.matchedBeats).isEqualTo(0)
        assertThat(phase.agreement).isEqualTo(0.0)
        assertThat(phase.bestShift).isEqualTo(0)
    }

    @Test
    fun `grid beats convert to bar beats`() {
        val converted = listOf(GridBeat(0.0, 1, 120.0), GridBeat(500.0, 2, 120.0)).toBarBeats()
        assertThat(converted).containsExactly(BarBeat(0.0, 1), BarBeat(500.0, 2)).inOrder()
    }

    @Test
    fun `a bar position outside 1 to 4 is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { BarBeat(0.0, 0) }
        assertThrows(IllegalArgumentException::class.java) { BarBeat(0.0, 5) }
    }
}
