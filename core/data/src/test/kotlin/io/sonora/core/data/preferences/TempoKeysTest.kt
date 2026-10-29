package io.sonora.core.data.preferences

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.backup.BackupSettingsPolicy
import io.sonora.core.domain.playback.PlaybackTempo
import io.sonora.core.domain.playback.TempoRange
import org.junit.Test

/** How the tempo store's keys, the legacy whole-percent one and the basis-point ones, are read back into a tempo. */
class TempoKeysTest {

    @Test
    fun `missing keys read as normal speed and pitch`() {
        assertThat(TempoKeys.read(legacyPercent = null, speedBasisPoints = null, masterTempo = null, rangeCode = null))
            .isEqualTo(PlaybackTempo.DEFAULT)
    }

    @Test
    fun `a legacy key alone is its whole percent in basis points with the fitting range`() {
        val slightly = TempoKeys.read(legacyPercent = 105, speedBasisPoints = null, masterTempo = null, rangeCode = null)
        val far = TempoKeys.read(legacyPercent = 125, speedBasisPoints = null, masterTempo = null, rangeCode = null)

        assertThat(slightly).isEqualTo(PlaybackTempo(10_500, range = TempoRange.TEN))
        assertThat(far).isEqualTo(PlaybackTempo(12_500, range = TempoRange.WIDE))
    }

    @Test
    fun `the semitones come along`() {
        val tempo = TempoKeys.read(legacyPercent = 100, speedBasisPoints = 10_000, masterTempo = true, rangeCode = "10", semitones = -4)

        assertThat(tempo.semitones).isEqualTo(-4)
        assertThat(TempoKeys.read(null, null, null, null, semitones = 40).semitones).isEqualTo(PlaybackTempo.MAX_SEMITONES)
    }

    @Test
    fun `a consistent pair reads the finer basis points`() {
        val tempo = TempoKeys.read(legacyPercent = 101, speedBasisPoints = 10_123, masterTempo = false, rangeCode = "6")

        assertThat(tempo).isEqualTo(PlaybackTempo(10_123, masterTempo = false, range = TempoRange.SIX))
    }

    @Test
    fun `the legacy percent is the basis points rounded half up`() {
        assertThat(TempoKeys.legacyPercentOf(10_050)).isEqualTo(101)
        assertThat(TempoKeys.legacyPercentOf(10_049)).isEqualTo(100)
        assertThat(TempoKeys.legacyPercentOf(9_950)).isEqualTo(100)
        assertThat(TempoKeys.legacyPercentOf(9_949)).isEqualTo(99)
        assertThat(TempoKeys.legacyPercentOf(PlaybackTempo.MIN_SPEED_BASIS_POINTS)).isEqualTo(50)
        assertThat(TempoKeys.legacyPercentOf(PlaybackTempo.MAX_SPEED_BASIS_POINTS)).isEqualTo(200)
    }

    @Test
    fun `a pair that agrees only at the rounding edge still counts as consistent`() {
        assertThat(TempoKeys.read(101, 10_050, null, null).speedBasisPoints).isEqualTo(10_050)
        assertThat(TempoKeys.read(100, 10_049, null, null).speedBasisPoints).isEqualTo(10_049)
    }

    @Test
    fun `an old backup restored over the new keys wins by its legacy percent`() {
        val tempo = TempoKeys.read(legacyPercent = 90, speedBasisPoints = 10_123, masterTempo = false, rangeCode = "6")

        assertThat(tempo.speedBasisPoints).isEqualTo(9_000)
        assertThat(tempo.masterTempo).isFalse()
        // The stored range (+-6 %) cannot hold 90 %, so the narrowest one that can is shown.
        assertThat(tempo.range).isEqualTo(TempoRange.TEN)
    }

    @Test
    fun `a downgrade that wrote only the legacy key reads like an old backup`() {
        val tempo = TempoKeys.read(legacyPercent = 120, speedBasisPoints = 10_000, masterTempo = true, rangeCode = "10")

        assertThat(tempo.speedBasisPoints).isEqualTo(12_000)
        assertThat(tempo.range).isEqualTo(TempoRange.WIDE)
    }

    @Test
    fun `an off-grid legacy percent keeps its exact basis points and a wild one is clamped`() {
        assertThat(TempoKeys.read(103, null, null, null).speedBasisPoints).isEqualTo(10_300)
        assertThat(TempoKeys.read(999, null, null, null).speedBasisPoints).isEqualTo(PlaybackTempo.MAX_SPEED_BASIS_POINTS)
        assertThat(TempoKeys.read(-5, null, null, null).speedBasisPoints).isEqualTo(PlaybackTempo.MIN_SPEED_BASIS_POINTS)
        assertThat(TempoKeys.read(999, 10_000, null, null).speedBasisPoints).isEqualTo(PlaybackTempo.MAX_SPEED_BASIS_POINTS)
    }

    @Test
    fun `basis points beyond the playable span are clamped`() {
        assertThat(TempoKeys.read(200, 30_000, null, null).speedBasisPoints).isEqualTo(PlaybackTempo.MAX_SPEED_BASIS_POINTS)
        assertThat(TempoKeys.read(null, 100, null, null).speedBasisPoints).isEqualTo(PlaybackTempo.MIN_SPEED_BASIS_POINTS)
    }

    @Test
    fun `basis points without a legacy key are used as they are`() {
        assertThat(TempoKeys.read(null, 10_123, null, null).speedBasisPoints).isEqualTo(10_123)
    }

    @Test
    fun `an unknown or missing range code falls back to the fitting range`() {
        assertThat(TempoKeys.read(null, 10_400, null, "huge").range).isEqualTo(TempoRange.TEN)
        assertThat(TempoKeys.read(null, 10_400, null, null).range).isEqualTo(TempoRange.TEN)
        assertThat(TempoKeys.read(null, 11_300, null, "huge").range).isEqualTo(TempoRange.SIXTEEN)
        assertThat(TempoKeys.read(null, 15_000, null, "").range).isEqualTo(TempoRange.WIDE)
    }

    @Test
    fun `a known range code is kept when it holds the speed and widened when it does not`() {
        assertThat(TempoKeys.read(null, 10_000, null, "wide").range).isEqualTo(TempoRange.WIDE)
        assertThat(TempoKeys.read(null, 10_123, null, "6").range).isEqualTo(TempoRange.SIX)
        assertThat(TempoKeys.read(null, 10_800, null, "6").range).isEqualTo(TempoRange.TEN)
    }

    @Test
    fun `Master Tempo defaults on and follows its key`() {
        assertThat(TempoKeys.read(null, 10_000, null, null).masterTempo).isTrue()
        assertThat(TempoKeys.read(null, 10_000, false, null).masterTempo).isFalse()
    }

    @Test
    fun `the new keys are not among the ones a backup leaves out`() {
        listOf(TempoKeys.SPEED_BASIS_POINTS_NAME, TempoKeys.MASTER_TEMPO_NAME, TempoKeys.RANGE_NAME, TempoKeys.SPEED_PERCENT_NAME)
            .forEach { assertThat(BackupSettingsPolicy.includes(it)).isTrue() }
    }

    @Test
    fun `the key names are the persisted ones`() {
        assertThat(TempoKeys.SPEED_PERCENT_NAME).isEqualTo("tempo.speed_percent")
        assertThat(TempoKeys.SPEED_BASIS_POINTS_NAME).isEqualTo("tempo.speed_basis_points")
        assertThat(TempoKeys.MASTER_TEMPO_NAME).isEqualTo("tempo.master_tempo")
        assertThat(TempoKeys.RANGE_NAME).isEqualTo("tempo.range")
        assertThat(TempoKeys.SEMITONES_NAME).isEqualTo("tempo.semitones")
    }
}
