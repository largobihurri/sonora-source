package io.sonora.tools.calibration.runner

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.analysis.MusicalKey
import org.junit.Test

class ReferenceKeysTest {

    @Test
    fun `classic names with sharps flats and minor suffix`() {
        assertThat(ReferenceKeys.parse("Am")).isEqualTo(MusicalKey.A_MINOR)
        assertThat(ReferenceKeys.parse("C")).isEqualTo(MusicalKey.C_MAJOR)
        assertThat(ReferenceKeys.parse("F#m")).isEqualTo(MusicalKey.F_SHARP_MINOR)
        assertThat(ReferenceKeys.parse("Gbm")).isEqualTo(MusicalKey.F_SHARP_MINOR)
        assertThat(ReferenceKeys.parse("Bb")).isEqualTo(MusicalKey.B_FLAT_MAJOR)
        assertThat(ReferenceKeys.parse("A#m")).isEqualTo(MusicalKey.B_FLAT_MINOR)
        assertThat(ReferenceKeys.parse("Dbm")).isEqualTo(MusicalKey.C_SHARP_MINOR)
        assertThat(ReferenceKeys.parse("G#")).isEqualTo(MusicalKey.A_FLAT_MAJOR)
        assertThat(ReferenceKeys.parse("Ebm")).isEqualTo(MusicalKey.E_FLAT_MINOR)
    }

    @Test
    fun `alphanumeric names`() {
        assertThat(ReferenceKeys.parse("8A")).isEqualTo(MusicalKey.A_MINOR)
        assertThat(ReferenceKeys.parse("8B")).isEqualTo(MusicalKey.C_MAJOR)
        assertThat(ReferenceKeys.parse("12a")).isEqualTo(MusicalKey.C_SHARP_MINOR)
    }

    @Test
    fun `spelled out modes and unicode accidentals`() {
        assertThat(ReferenceKeys.parse("A minor")).isEqualTo(MusicalKey.A_MINOR)
        assertThat(ReferenceKeys.parse("Eb major")).isEqualTo(MusicalKey.E_FLAT_MAJOR)
        assertThat(ReferenceKeys.parse("F♯m")).isEqualTo(MusicalKey.F_SHARP_MINOR)
        assertThat(ReferenceKeys.parse("B♭")).isEqualTo(MusicalKey.B_FLAT_MAJOR)
        assertThat(ReferenceKeys.parse(" Cm ")).isEqualTo(MusicalKey.C_MINOR)
    }

    @Test
    fun `enharmonic spellings across the octave boundary`() {
        assertThat(ReferenceKeys.parse("Cb")).isEqualTo(MusicalKey.B_MAJOR)
        assertThat(ReferenceKeys.parse("B#")).isEqualTo(MusicalKey.C_MAJOR)
        assertThat(ReferenceKeys.parse("E#m")).isEqualTo(MusicalKey.F_MINOR)
    }

    @Test
    fun `unknown text gives null`() {
        assertThat(ReferenceKeys.parse("")).isNull()
        assertThat(ReferenceKeys.parse("H")).isNull()
        assertThat(ReferenceKeys.parse("13A")).isNull()
        assertThat(ReferenceKeys.parse("Am7")).isNull()
        assertThat(ReferenceKeys.parse(null)).isNull()
    }
}
