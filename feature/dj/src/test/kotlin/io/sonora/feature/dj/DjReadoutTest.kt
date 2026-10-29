package io.sonora.feature.dj

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.Accelerator
import io.sonora.core.djmodel.AnalysisProvenance
import io.sonora.core.djmodel.BandWaveform
import io.sonora.core.djmodel.BeatSource
import io.sonora.core.djmodel.DjAnalysis
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.GridEdit
import io.sonora.core.djmodel.KeyEstimate
import io.sonora.core.djmodel.TempoSegment
import io.sonora.core.domain.analysis.MusicalKey
import io.sonora.core.domain.playback.PlaybackTempo
import java.util.Locale
import org.junit.Test

class DjReadoutTest {

    private fun analysis(grid: DjBeatGrid?, key: MusicalKey? = MusicalKey.A_MINOR, edit: GridEdit? = null) = DjAnalysis(
        durationMs = 240_000,
        grid = grid,
        key = key?.let { KeyEstimate(it, 0.9, "edma") },
        waveform = BandWaveform.MISSING,
        provenance = AnalysisProvenance(1, BeatSource.MODEL, "beat-this", Accelerator.CPU, 1_700_000_000_000),
        gridEdit = edit,
    )

    private fun constant(bpm: Double = 128.0) = DjBeatGrid(listOf(TempoSegment(100.0, bpm, 1)))

    /** 120 BPM from 0 to 2000 ms (four beats), then 150 BPM. */
    private fun variable() = DjBeatGrid(listOf(TempoSegment(0.0, 120.0, 1), TempoSegment(2_000.0, 150.0, 1)))

    @Test
    fun `plus 1_25 percent of 128 BPM reads 129_6`() {
        val tempo = PlaybackTempo(speedBasisPoints = 10_125)

        val readout = DjReadout.of(analysis(constant(128.0)), tempo, 5_000)

        assertThat(readout.bpm).isEqualTo(129.6)
        assertThat(readout.tempoPercent).isWithin(1e-9).of(1.25)
    }

    @Test
    fun `normal speed shows the grid tempo as it is`() {
        val readout = DjReadout.of(analysis(constant(127.9)), PlaybackTempo.DEFAULT, 0)

        assertThat(readout.bpm).isEqualTo(127.9)
        assertThat(readout.tempoPercent).isEqualTo(0.0)
    }

    @Test
    fun `a slower speed gives a negative percent and a lower tempo`() {
        val readout = DjReadout.of(analysis(constant(120.0)), PlaybackTempo(speedBasisPoints = 9_700), 0)

        assertThat(readout.bpm).isEqualTo(116.4)
        assertThat(readout.tempoPercent).isWithin(1e-9).of(-3.0)
    }

    @Test
    fun `a variable tempo grid picks the segment at the position`() {
        val analysis = analysis(variable())
        val tempo = PlaybackTempo.DEFAULT

        assertThat(DjReadout.of(analysis, tempo, 0).bpm).isEqualTo(120.0)
        assertThat(DjReadout.of(analysis, tempo, 1_999).bpm).isEqualTo(120.0)
        assertThat(DjReadout.of(analysis, tempo, 2_000).bpm).isEqualTo(150.0)
        assertThat(DjReadout.of(analysis, tempo, 100_000).bpm).isEqualTo(150.0)
    }

    @Test
    fun `without a beat grid there is no tempo but the percent and the key remain`() {
        val readout = DjReadout.of(analysis(grid = null), PlaybackTempo(speedBasisPoints = 10_300), 1_000)

        assertThat(readout.bpm).isNull()
        assertThat(readout.tempoPercent).isWithin(1e-9).of(3.0)
        assertThat(readout.key).isEqualTo(MusicalKey.A_MINOR)
    }

    @Test
    fun `no detected key gives no key`() {
        assertThat(DjReadout.of(analysis(constant(), key = null), PlaybackTempo.DEFAULT, 0).key).isNull()
    }

    @Test
    fun `with Master Tempo on the key stays where it was found`() {
        val tempo = PlaybackTempo(speedBasisPoints = 10_800, masterTempo = true)

        assertThat(DjReadout.of(analysis(constant()), tempo, 0).key).isEqualTo(MusicalKey.A_MINOR)
    }

    @Test
    fun `with Master Tempo off the key follows the speed`() {
        // +6 % is 1.0 semitones (12 log2 1.06 = 1.01): A minor (8A) goes to B flat minor (3A).
        val tempo = PlaybackTempo(speedBasisPoints = 10_600, masterTempo = false, range = io.sonora.core.domain.playback.TempoRange.SIX)

        assertThat(DjReadout.of(analysis(constant()), tempo, 0).key).isEqualTo(MusicalKey.B_FLAT_MINOR)
    }

    @Test
    fun `semitones shift the key also with Master Tempo on`() {
        val tempo = PlaybackTempo(semitones = -2, masterTempo = true)

        // A minor (8A) down two semitones is G minor (6A).
        assertThat(DjReadout.of(analysis(constant()), tempo, 0).key).isEqualTo(MusicalKey.G_MINOR)
    }

    @Test
    fun `the tempo percent text is signed with two decimals`() {
        assertThat(readout(percentBp = 10_125).tempoPercentText(Locale.US)).isEqualTo("+1.25")
        assertThat(readout(percentBp = 9_700).tempoPercentText(Locale.US)).isEqualTo("-3.00")
        assertThat(readout(percentBp = 10_000).tempoPercentText(Locale.US)).isEqualTo("0.00")
        assertThat(readout(percentBp = 10_125).tempoPercentText(Locale.forLanguageTag("es-ES"))).isEqualTo("+1,25")
    }

    @Test
    fun `the bpm text has one decimal`() {
        assertThat(DjReadout(129.6, 1.25, null).bpmText(Locale.US)).isEqualTo("129.6")
        assertThat(DjReadout(128.0, 0.0, null).bpmText(Locale.US)).isEqualTo("128.0")
        assertThat(DjReadout(128.0, 0.0, null).bpmText(Locale.forLanguageTag("eu"))).isEqualTo("128,0")
        assertThat(DjReadout(null, 0.0, null).bpmText(Locale.US)).isNull()
    }

    private fun readout(percentBp: Int) = DjReadout.of(analysis(constant()), PlaybackTempo(speedBasisPoints = percentBp), 0)

    @Test
    fun `the readout follows the edited tempo`() {
        val readout = DjReadout.of(analysis(constant(128.0), edit = GridEdit(12_345, 0.0)), PlaybackTempo.DEFAULT, 0)

        assertThat(readout.bpm).isEqualTo(123.5)
    }

    @Test
    fun `an edit gives a track without a grid a tempo`() {
        val readout = DjReadout.of(analysis(grid = null, edit = GridEdit(12_000, 0.0)), PlaybackTempo.DEFAULT, 0)

        assertThat(readout.bpm).isEqualTo(120.0)
    }

    @Test
    fun `the track readout holds the grid tempo at the playhead and the detected key, with no tempo in it`() {
        val track = DjTrackReadout.of(analysis(variable()), 2_500)

        assertThat(track).isEqualTo(DjTrackReadout(gridBpm = 150.0, key = MusicalKey.A_MINOR))
        assertThat(DjTrackReadout.of(analysis(grid = null, key = null), 0)).isEqualTo(DjTrackReadout(gridBpm = null, key = null))
    }

    @Test
    fun `a track readout at a tempo is the readout of that tempo, for every step of a fader drag`() {
        val analysis = analysis(constant(128.0))
        val track = DjTrackReadout.of(analysis, 0)

        for (basisPoints in 9_000..11_000 step 37) {
            val tempo = PlaybackTempo(speedBasisPoints = basisPoints)
            assertThat(track.at(tempo)).isEqualTo(DjReadout.of(analysis, tempo, 0))
        }
    }
}
