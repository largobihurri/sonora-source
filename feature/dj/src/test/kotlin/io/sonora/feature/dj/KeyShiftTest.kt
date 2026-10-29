package io.sonora.feature.dj

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.analysis.MusicalKey
import io.sonora.core.domain.playback.PlaybackTempo
import org.junit.Test

class KeyShiftTest {

    @Test
    fun `an unchanged pitch keeps the key`() {
        MusicalKey.entries.forEach { assertThat(KeyShift.shifted(it, 1.0)).isEqualTo(it) }
    }

    @Test
    fun `one semitone up moves seven places round the wheel`() {
        // C major (8B) up a semitone is D flat major (3B); A minor (8A) is B flat minor (3A).
        assertThat(KeyShift.shifted(MusicalKey.C_MAJOR, PlaybackTempo.pitchFactorOf(1).toDouble()))
            .isEqualTo(MusicalKey.D_FLAT_MAJOR)
        assertThat(KeyShift.shifted(MusicalKey.A_MINOR, PlaybackTempo.pitchFactorOf(1).toDouble()))
            .isEqualTo(MusicalKey.B_FLAT_MINOR)
    }

    @Test
    fun `two semitones down is two fifths down`() {
        // C major down two semitones is B flat major (6B): 8 - 14 = -6, wrapped to 6.
        assertThat(KeyShift.shifted(MusicalKey.C_MAJOR, PlaybackTempo.pitchFactorOf(-2).toDouble()))
            .isEqualTo(MusicalKey.B_FLAT_MAJOR)
    }

    @Test
    fun `the number wraps from 12 to 1 and keeps the letter`() {
        // E major is 12B; up a semitone is F major (7B): (12 + 7) mod 12 = 7.
        assertThat(KeyShift.shifted(MusicalKey.E_MAJOR, PlaybackTempo.pitchFactorOf(1).toDouble()))
            .isEqualTo(MusicalKey.F_MAJOR)
        // C sharp minor is 12A; down a semitone is C minor (5A).
        assertThat(KeyShift.shifted(MusicalKey.C_SHARP_MINOR, PlaybackTempo.pitchFactorOf(-1).toDouble()))
            .isEqualTo(MusicalKey.C_MINOR)
    }

    @Test
    fun `twelve semitones are an octave and keep the key`() {
        MusicalKey.entries.forEach {
            assertThat(KeyShift.shifted(it, 2.0)).isEqualTo(it)
            assertThat(KeyShift.shifted(it, 0.5)).isEqualTo(it)
        }
    }

    @Test
    fun `a shift of n semitones agrees with the semitone names for every key`() {
        // Up a semitone from every major key lands on the next root name.
        val majors = MusicalKey.entries.filter { !it.isMinor }
        majors.forEach { key ->
            val next = majors[(key.index % 12)]
            assertThat(KeyShift.shifted(key, PlaybackTempo.pitchFactorOf(1).toDouble())).isEqualTo(next)
        }
    }

    @Test
    fun `the factor is rounded to the nearest semitone`() {
        // 1.03 is 0.5 semitones below the line to one, 1.04 is above it (2^(0.5/12) = 1.0293).
        assertThat(KeyShift.shifted(MusicalKey.C_MAJOR, 1.02)).isEqualTo(MusicalKey.C_MAJOR)
        assertThat(KeyShift.shifted(MusicalKey.C_MAJOR, 1.04)).isEqualTo(MusicalKey.D_FLAT_MAJOR)
    }

    @Test
    fun `a factor that is not a pitch keeps the key`() {
        assertThat(KeyShift.shifted(MusicalKey.C_MAJOR, Double.NaN)).isEqualTo(MusicalKey.C_MAJOR)
        assertThat(KeyShift.shifted(MusicalKey.C_MAJOR, 0.0)).isEqualTo(MusicalKey.C_MAJOR)
        assertThat(KeyShift.shifted(MusicalKey.C_MAJOR, -1.0)).isEqualTo(MusicalKey.C_MAJOR)
        assertThat(KeyShift.shifted(MusicalKey.C_MAJOR, Double.POSITIVE_INFINITY)).isEqualTo(MusicalKey.C_MAJOR)
    }
}
