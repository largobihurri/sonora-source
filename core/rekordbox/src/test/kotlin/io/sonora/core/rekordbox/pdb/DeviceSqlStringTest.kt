package io.sonora.core.rekordbox.pdb

import com.google.common.truth.Truth.assertThat
import io.sonora.core.rekordbox.reference.PdbReader
import io.sonora.core.rekordbox.reference.hex
import org.junit.Test

class DeviceSqlStringTest {

    @Test
    fun `empty string is the single byte 03`() {
        assertThat(DeviceSqlString.encode("")).isEqualTo(hex("03"))
    }

    @Test
    fun `short ascii strings carry their total length in the first byte`() {
        assertThat(DeviceSqlString.encode("foo")).isEqualTo(hex("09 666f6f"))
        assertThat(DeviceSqlString.encode("Am")).isEqualTo(hex("07 416d"))
        assertThat(DeviceSqlString.encode("ON")).isEqualTo(hex("07 4f4e"))
    }

    @Test
    fun `ascii up to 126 bytes stays short and longer ascii switches to the long form`() {
        val short = DeviceSqlString.encode("a".repeat(126))
        assertThat(short.size).isEqualTo(127)
        assertThat(short[0]).isEqualTo(0xFF.toByte())

        val long = DeviceSqlString.encode("a".repeat(127))
        assertThat(long.copyOf(4)).isEqualTo(hex("40 8300 00"))
        assertThat(long.size).isEqualTo(131)
    }

    @Test
    fun `non-ascii text is stored as utf-16le in the long form`() {
        assertThat(DeviceSqlString.encode("Déjà")).isEqualTo(hex("90 0c00 00 4400 e900 6a00 e000"))
        assertThat(DeviceSqlString.isUtf16("Déjà")).isTrue()
        assertThat(DeviceSqlString.isUtf16("Deja vu")).isFalse()
    }

    @Test
    fun `isrc uses the 0x90 form with a 03 marker and a trailing nul`() {
        assertThat(DeviceSqlString.encodeIsrc("GBAYE0601498"))
            .isEqualTo(hex("90 1200 00 03") + "GBAYE0601498".toByteArray() + hex("00"))
        assertThat(DeviceSqlString.encodeIsrc("")).isEqualTo(hex("03"))
    }

    @Test
    fun `decoding the encoded forms gives the text back`() {
        for (text in listOf("", "Demo Track 1", "x".repeat(300), "Manie Sans Délire", "日本語のタイトル")) {
            assertThat(PdbReader.decodeString(DeviceSqlString.encode(text), 0)).isEqualTo(text)
        }
    }
}
