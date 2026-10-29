package io.sonora.core.domain.analysis

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MusicalKeyTest {

    /** The user's notation decision (alphanumeric, minor = nA, major = nB), keyed by the qm-dsp index. */
    private val expected = mapOf(
        1 to "8B", 2 to "3B", 3 to "10B", 4 to "5B", 5 to "12B", 6 to "7B",
        7 to "2B", 8 to "9B", 9 to "4B", 10 to "11B", 11 to "6B", 12 to "1B",
        13 to "5A", 14 to "12A", 15 to "7A", 16 to "2A", 17 to "9A", 18 to "4A",
        19 to "11A", 20 to "6A", 21 to "1A", 22 to "8A", 23 to "3A", 24 to "10A",
    )

    @Test
    fun `every qm-dsp index maps to the agreed alphanumeric name`() {
        for ((index, name) in expected) {
            assertThat(MusicalKey.fromIndex(index)!!.alphanumeric).isEqualTo(name)
        }
        assertThat(MusicalKey.entries.map { it.index }).containsExactlyElementsIn(1..24).inOrder()
    }

    @Test
    fun `alphanumeric names are unique and round trip`() {
        assertThat(MusicalKey.entries.map { it.alphanumeric }.toSet()).hasSize(24)
        for (key in MusicalKey.entries) {
            assertThat(MusicalKey.fromAlphanumeric(key.alphanumeric)).isEqualTo(key)
        }
    }

    @Test
    fun `well-known anchors`() {
        assertThat(MusicalKey.A_MINOR.alphanumeric).isEqualTo("8A")
        assertThat(MusicalKey.C_MAJOR.alphanumeric).isEqualTo("8B")
        assertThat(MusicalKey.G_SHARP_MINOR.alphanumeric).isEqualTo("1A")
        assertThat(MusicalKey.B_MAJOR.alphanumeric).isEqualTo("1B")
        assertThat(MusicalKey.C_SHARP_MINOR.alphanumeric).isEqualTo("12A")
        assertThat(MusicalKey.E_MAJOR.alphanumeric).isEqualTo("12B")
        assertThat(MusicalKey.fromIndex(22)).isEqualTo(MusicalKey.A_MINOR)
    }

    @Test
    fun `minor keys are 13 to 24 and end in A`() {
        for (key in MusicalKey.entries) {
            assertThat(key.isMinor).isEqualTo(key.index > 12)
            assertThat(key.alphanumeric.last()).isEqualTo(if (key.isMinor) 'A' else 'B')
        }
    }

    @Test
    fun `one step up the wheel is a fifth up and minor shares its number with the relative major`() {
        fun root(key: MusicalKey) = (key.index - 1) % 12
        fun number(key: MusicalKey) = key.alphanumeric.dropLast(1).toInt()
        for (key in MusicalKey.entries) {
            val next = MusicalKey.entries.single {
                it.isMinor == key.isMinor && number(it) == number(key) % 12 + 1
            }
            assertThat(root(next)).isEqualTo((root(key) + 7) % 12)
            if (key.isMinor) {
                val relativeMajor = MusicalKey.entries.single { !it.isMinor && root(it) == (root(key) + 3) % 12 }
                assertThat(number(relativeMajor)).isEqualTo(number(key))
            }
        }
    }

    @Test
    fun `fromAlphanumeric ignores case and surrounding blanks`() {
        assertThat(MusicalKey.fromAlphanumeric(" 8a ")).isEqualTo(MusicalKey.A_MINOR)
        assertThat(MusicalKey.fromAlphanumeric("12b")).isEqualTo(MusicalKey.E_MAJOR)
    }

    @Test
    fun `unknown names and indices are null`() {
        for (text in listOf("", "8", "A8", "8C", "13A", "0B", "08A", "Am")) {
            assertThat(MusicalKey.fromAlphanumeric(text)).isNull()
        }
        for (index in listOf(-1, 0, 25)) {
            assertThat(MusicalKey.fromIndex(index)).isNull()
        }
    }
}
