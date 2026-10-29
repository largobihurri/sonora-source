package io.sonora.core.djengine.beats

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.BandWaveform
import io.sonora.core.djmodel.BeatCandidates
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.TempoSegment
import org.junit.Test

class BarPhaserTest {

    private val bpm = 120.0
    private val intervalMs = 500.0
    private val durationMs = 60_000.0

    private fun grid(startMs: Double = 0.0, beatInBarAtStart: Int = 1) = DjBeatGrid(listOf(TempoSegment(startMs, bpm, beatInBarAtStart)))

    /** One detection per grid beat from [startMs], with the downbeat probability of its position `k % 4`. */
    private fun candidates(startMs: Double, probabilityByPosition: DoubleArray): BeatCandidates {
        val count = ((durationMs - startMs) / intervalMs).toInt()
        val beats = DoubleArray(count) { startMs + it * intervalMs }
        return BeatCandidates(beats, DoubleArray(0), DoubleArray(count) { probabilityByPosition[it % 4] })
    }

    /** A track that is silent until [soundFromMs] and loud after it, as 150-column-per-second planes. */
    private fun waveform(soundFromMs: Double?, level: Int = 200): BandWaveform {
        val columns = (durationMs * BandWaveform.COLUMNS_PER_SECOND / 1000).toInt()
        val first = if (soundFromMs == null) columns else (soundFromMs * BandWaveform.COLUMNS_PER_SECOND / 1000).toInt()
        val peak = ByteArray(columns) { if (it >= first) level.toByte() else 0 }
        return BandWaveform(peak, peak, peak, peak, version = 1)
    }

    private fun BarPhaseResult.beatInBarAtStart() = grid.segments.single().beatInBarAtStart

    @Test
    fun `the bar starts where the mean downbeat probability is highest`() {
        // Position 2 of the beats (counted from the grid start) is the strongest, so the first beat is beat 3 of its bar.
        val result = BarPhaser.rephase(grid(), candidates(0.0, doubleArrayOf(0.1, 0.1, 0.8, 0.1)), waveform(null))

        assertThat(result.beatInBarAtStart()).isEqualTo(3)
    }

    @Test
    fun `evidence that agrees with the grid leaves it as it is`() {
        val before = grid(startMs = 250.0, beatInBarAtStart = 4)

        // The grid says the downbeat is beat k = 1 (beat 4 at k = 0 means beat 1 at k = 1).
        val result = BarPhaser.rephase(before, candidates(250.0, doubleArrayOf(0.1, 0.9, 0.1, 0.1)), waveform(null))

        assertThat(result.grid).isEqualTo(before)
    }

    @Test
    fun `only the bar position changes, never a beat time or the tempo`() {
        val before = grid(startMs = 100.0)
        val result = BarPhaser.rephase(before, candidates(100.0, doubleArrayOf(0.1, 0.1, 0.1, 0.9)), waveform(0.0))

        val a = before.beats(durationMs)
        val b = result.grid.beats(durationMs)
        assertThat(b.map { it.timeMs }).isEqualTo(a.map { it.timeMs })
        assertThat(result.grid.segments.single().startMs).isEqualTo(100.0)
        assertThat(result.grid.segments.single().bpm).isEqualTo(bpm)
        assertThat(result.beatInBarAtStart()).isNotEqualTo(before.segments.single().beatInBarAtStart)
    }

    @Test
    fun `a track that starts sounding on the first beat tips a close call towards that beat`() {
        // Positions 0 and 2 are nearly tied, 2 slightly ahead: the model alone says beat 3 of the bar at k = 0.
        val probabilities = doubleArrayOf(0.45, 0.1, 0.50, 0.1)
        val without = BarPhaser.rephase(grid(), candidates(0.0, probabilities), waveform(null))
        val with = BarPhaser.rephase(grid(), candidates(0.0, probabilities), waveform(0.0))

        assertThat(without.beatInBarAtStart()).isEqualTo(3)
        assertThat(with.beatInBarAtStart()).isEqualTo(1)
    }

    @Test
    fun `a confident model is not overruled by the start of the track`() {
        val result = BarPhaser.rephase(grid(), candidates(0.0, doubleArrayOf(0.1, 0.1, 0.9, 0.1)), waveform(0.0))

        assertThat(result.beatInBarAtStart()).isEqualTo(3)
    }

    @Test
    fun `sound that starts late in the track says nothing about the bars`() {
        val probabilities = doubleArrayOf(0.45, 0.1, 0.50, 0.1)

        val result = BarPhaser.rephase(grid(), candidates(0.0, probabilities), waveform(2_000.0))

        assertThat(result.beatInBarAtStart()).isEqualTo(3)
    }

    @Test
    fun `a silent track says nothing about the bars`() {
        val probabilities = doubleArrayOf(0.45, 0.1, 0.50, 0.1)

        val result = BarPhaser.rephase(grid(), candidates(0.0, probabilities), waveform(null))

        assertThat(result.beatInBarAtStart()).isEqualTo(3)
    }

    @Test
    fun `sound that begins between two beats is not a bar start`() {
        // The grid beat nearest to the first sound is 0.4 of a beat away (the sound starts at 0, the grid at 200 ms).
        val probabilities = doubleArrayOf(0.45, 0.1, 0.50, 0.1)

        val result = BarPhaser.rephase(grid(startMs = 200.0), candidates(200.0, probabilities), waveform(0.0))

        assertThat(result.beatInBarAtStart()).isEqualTo(3)
    }

    @Test
    fun `sound that starts just before the grid's first beat points at the beat before it`() {
        // The grid starts at 400 ms; the sound at 0 is 0.2 beats after the beat at -100 ms, which is k = -1: position 3.
        val probabilities = doubleArrayOf(0.1, 0.30, 0.1, 0.25)
        val without = BarPhaser.rephase(grid(startMs = 400.0), candidates(400.0, probabilities), waveform(null))
        val with = BarPhaser.rephase(grid(startMs = 400.0), candidates(400.0, probabilities), waveform(0.0))

        assertThat(without.beatInBarAtStart()).isEqualTo(4)
        // Beat k = 3 is the downbeat, so k = 0 is beat 2 of its bar.
        assertThat(with.beatInBarAtStart()).isEqualTo(2)
    }

    @Test
    fun `a grid with tempo changes is returned as it is`() {
        val changing = DjBeatGrid(listOf(TempoSegment(0.0, 120.0, 1), TempoSegment(30_000.0, 140.0, 1)))

        val result = BarPhaser.rephase(changing, candidates(0.0, doubleArrayOf(0.1, 0.1, 0.9, 0.1)), waveform(0.0))

        assertThat(result.grid).isEqualTo(changing)
        assertThat(result.confidence).isEqualTo(0.0)
    }

    @Test
    fun `detections without a downbeat probability leave the grid as it is`() {
        val beats = DoubleArray(100) { it * intervalMs }
        val unknown = BeatCandidates(beats, DoubleArray(0), DoubleArray(100) { Double.NaN })

        val result = BarPhaser.rephase(grid(beatInBarAtStart = 3), unknown, waveform(0.0))

        assertThat(result.grid).isEqualTo(grid(beatInBarAtStart = 3))
        assertThat(result.confidence).isEqualTo(0.0)
    }

    @Test
    fun `a phase without any detection is not guessed`() {
        // Only the beats at positions 0 and 1 were detected: no vote across the four positions is possible.
        val beats = DoubleArray(200) { (it / 2 * 4 + it % 2) * intervalMs }
        val sparse = BeatCandidates(beats, DoubleArray(0), DoubleArray(200) { 0.9 })

        val result = BarPhaser.rephase(grid(beatInBarAtStart = 2), sparse, waveform(0.0))

        assertThat(result.grid).isEqualTo(grid(beatInBarAtStart = 2))
    }

    @Test
    fun `confidence is the lead of the chosen position over the runner up`() {
        val decisive = BarPhaser.rephase(grid(), candidates(0.0, doubleArrayOf(0.9, 0.1, 0.1, 0.1)), waveform(null))
        val close = BarPhaser.rephase(grid(), candidates(0.0, doubleArrayOf(0.5, 0.1, 0.45, 0.1)), waveform(null))
        val tied = BarPhaser.rephase(grid(), candidates(0.0, doubleArrayOf(0.4, 0.4, 0.4, 0.4)), waveform(null))

        assertThat(decisive.confidence).isWithin(1e-9).of(0.8)
        assertThat(close.confidence).isWithin(1e-9).of(0.05)
        assertThat(tied.confidence).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `confidence stays between 0 and 1 when the start of the track adds to the lead`() {
        val result = BarPhaser.rephase(grid(), candidates(0.0, doubleArrayOf(0.95, 0.0, 0.0, 0.0)), waveform(0.0))

        assertThat(result.confidence).isAtMost(1.0)
        assertThat(result.confidence).isAtLeast(0.0)
    }

    @Test
    fun `a tie goes to the earlier position, as in the grid fit`() {
        val result = BarPhaser.rephase(grid(), candidates(0.0, doubleArrayOf(0.3, 0.3, 0.3, 0.3)), waveform(null))

        assertThat(result.beatInBarAtStart()).isEqualTo(1)
    }

    @Test
    fun `the weights can be changed or switched off`() {
        val probabilities = doubleArrayOf(0.45, 0.1, 0.50, 0.1)

        val off = BarPhaser.rephase(grid(), candidates(0.0, probabilities), waveform(0.0), BarPhaseOptions(introWeight = 0.0))
        val strong = BarPhaser.rephase(grid(), candidates(0.0, probabilities), waveform(0.0), BarPhaseOptions(introWeight = 1.0))

        assertThat(off.beatInBarAtStart()).isEqualTo(3)
        assertThat(strong.beatInBarAtStart()).isEqualTo(1)
    }

    @Test
    fun `the first sound is the first column above a share of the loud level`() {
        // Columns 0..14 quiet (level 10), then loud: with 20 % of the 75th percentile (200) the threshold is 40.
        val columns = 600
        val peak = ByteArray(columns) { if (it < 15) 10.toByte() else 200.toByte() }
        val w = BandWaveform(peak, peak, peak, peak, version = 1)

        assertThat(BarPhaser.introStartMs(w, levelFraction = 0.2)).isWithin(1e-9).of(15 * 1000.0 / 150)
    }

    @Test
    fun `a silent or empty waveform has no first sound`() {
        val silent = ByteArray(300)

        assertThat(BarPhaser.introStartMs(BandWaveform(silent, silent, silent, silent, version = 1), 0.2)).isNull()
        assertThat(BarPhaser.introStartMs(BandWaveform.MISSING, 0.2)).isNull()
    }

    @Test
    fun `a track that is loud from the first column starts at zero`() {
        assertThat(BarPhaser.introStartMs(waveform(0.0), 0.2)).isEqualTo(0.0)
    }

    @Test
    fun `options reject values outside their range`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { BarPhaseOptions(introWeight = -0.1) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { BarPhaseOptions(introWindowMs = -1.0) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { BarPhaseOptions(introLevelFraction = 0.0) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { BarPhaseOptions(introOffBeatFraction = 0.6) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { BarPhaseOptions(matchToleranceFraction = 0.5) }
    }
}
