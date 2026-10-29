package io.sonora.core.rekordbox.pdb

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.analysis.MusicalKey
import org.junit.Test

class ClassicKeyNamesTest {

    @Test
    fun `majors are the bare root and minors the root plus m`() {
        assertThat(MusicalKey.C_MAJOR.classicName).isEqualTo("C")
        assertThat(MusicalKey.E_MAJOR.classicName).isEqualTo("E")
        assertThat(MusicalKey.D_FLAT_MAJOR.classicName).isEqualTo("Db")
        assertThat(MusicalKey.F_SHARP_MAJOR.classicName).isEqualTo("F#")
        assertThat(MusicalKey.A_MINOR.classicName).isEqualTo("Am")
        assertThat(MusicalKey.F_MINOR.classicName).isEqualTo("Fm")
        assertThat(MusicalKey.C_SHARP_MINOR.classicName).isEqualTo("C#m")
        assertThat(MusicalKey.B_FLAT_MINOR.classicName).isEqualTo("Bbm")
    }

    @Test
    fun `the 24 keys have 24 distinct names`() {
        assertThat(MusicalKey.entries.map { it.classicName }.toSet()).hasSize(24)
    }
}
