package io.sonora.core.rekordbox.setting

import com.google.common.truth.Truth.assertThat
import io.sonora.core.rekordbox.DemoTracks
import org.junit.Test

class DevSettingWriterTest {

    private val demo = DemoTracks.resource("DEVSETTING.DAT")

    @Test
    fun `crc16 xmodem matches the catalogue check value and rekordbox's own checksum`() {
        assertThat(Crc16Xmodem.of("123456789".toByteArray())).isEqualTo(0x31C3)
        // rekordbox's DEVSETTING.DAT: the checksum of the 32 data bytes at 104 is stored little-endian at 136.
        assertThat(Crc16Xmodem.of(demo, 104, 136)).isEqualTo(0xD016)
    }

    @Test
    fun `classic key display reproduces rekordbox's default file byte for byte`() {
        assertThat(DevSettingWriter.write(KeyDisplayFormat.CLASSIC)).isEqualTo(demo)
    }

    @Test
    fun `alphanumeric key display changes only the key display byte and the checksum`() {
        val written = DevSettingWriter.write()

        assertThat(written.size).isEqualTo(140)
        assertThat(written[0x74].toInt()).isEqualTo(KeyDisplayFormat.ALPHANUMERIC.code)
        val differing = written.indices.filter { written[it] != demo[it] }
        assertThat(differing).containsAnyIn(listOf(0x88, 0x89))
        assertThat(differing - setOf(0x74, 0x88, 0x89)).isEmpty()
        val crc = (written[0x88].toInt() and 0xFF) or ((written[0x89].toInt() and 0xFF) shl 8)
        assertThat(crc).isEqualTo(Crc16Xmodem.of(written, 104, 136))
    }
}
