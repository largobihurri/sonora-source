package io.sonora.core.domain.storage

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class VolumeLabelTest {

    @Test
    fun `labels are trimmed and upper-cased`() {
        assertThat(VolumeLabel.of("  my cdj usb ").text).isEqualTo("MY CDJ USB")
        assertThat(VolumeLabel.of("a").text).isEqualTo("A")
        assertThat(VolumeLabel.of("12345678901").text).isEqualTo("12345678901")
    }

    @Test
    fun `characters allowed in FAT short names are accepted`() {
        assertThat(VolumeLabel.validate("!#\$%&'()-@^")).isNull()
        assertThat(VolumeLabel.validate("_`{}~ 09AZ")).isNull()
    }

    @Test
    fun `blank input is empty`() {
        assertThat(VolumeLabel.validate("")).isEqualTo(VolumeLabelError.Empty)
        assertThat(VolumeLabel.validate("   ")).isEqualTo(VolumeLabelError.Empty)
    }

    @Test
    fun `more than 11 characters after trimming is too long`() {
        assertThat(VolumeLabel.validate("123456789012")).isEqualTo(VolumeLabelError.TooLong(length = 12))
        assertThat(VolumeLabel.validate(" 12345678901 ")).isNull()
    }

    @Test
    fun `characters forbidden in FAT short names are rejected`() {
        for (character in "\"*+,./:;<=>?[\\]|") {
            assertThat(VolumeLabel.validate("A${character}B")).isEqualTo(VolumeLabelError.InvalidCharacter(character))
        }
    }

    @Test
    fun `control and non-ASCII characters are rejected`() {
        assertThat(VolumeLabel.validate("A\tB")).isEqualTo(VolumeLabelError.InvalidCharacter('\t'))
        assertThat(VolumeLabel.validate("A\u0000")).isEqualTo(VolumeLabelError.InvalidCharacter('\u0000'))
        assertThat(VolumeLabel.validate("café")).isEqualTo(VolumeLabelError.InvalidCharacter('É'))
        assertThat(VolumeLabel.validate("A\u007F")).isEqualTo(VolumeLabelError.InvalidCharacter('\u007F'))
    }

    @Test
    fun `of rejects invalid labels and ofOrNull returns null`() {
        assertThrows(IllegalArgumentException::class.java) { VolumeLabel.of("a.b") }
        assertThat(VolumeLabel.ofOrNull("a.b")).isNull()
        assertThat(VolumeLabel.ofOrNull("ok")).isEqualTo(VolumeLabel.of("OK"))
    }
}
