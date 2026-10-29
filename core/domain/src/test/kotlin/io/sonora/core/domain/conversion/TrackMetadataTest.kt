package io.sonora.core.domain.conversion

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrackMetadataTest {

    @Test
    fun `multiple values are cleaned, de-duplicated and joined with a semicolon`() {
        assertThat(MetadataValues.join(listOf("Daft Punk", " Pharrell  Williams ", "daft punk", "", null)))
            .isEqualTo("Daft Punk; Pharrell Williams")
        assertThat(MetadataValues.join(listOf("AC/DC"))).isEqualTo("AC/DC")
        assertThat(MetadataValues.join(listOf(" ", null))).isNull()
    }

    @Test
    fun `leading numbers parse from n, n of total and zero-padded forms`() {
        assertThat(MetadataValues.leadingNumber("07")).isEqualTo(7)
        assertThat(MetadataValues.leadingNumber("3/12")).isEqualTo(3)
        assertThat(MetadataValues.leadingNumber("0")).isNull()
        assertThat(MetadataValues.leadingNumber("A1")).isNull()
        assertThat(MetadataValues.leadingNumber(null)).isNull()
    }

    @Test
    fun `metadata without any value is empty`() {
        assertThat(TrackMetadata().isEmpty).isTrue()
        assertThat(TrackMetadata(trackNumber = 1).isEmpty).isFalse()
        assertThat(TrackMetadata(text = mapOf(MetadataTextField.TITLE to "x")).isEmpty).isFalse()
    }

    @Test
    fun `images are recognised by their signature, not their declared type`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0)
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
        val gif = "GIF89a".toByteArray()

        assertThat(CoverArtRule.mimeTypeOf(jpeg)).isEqualTo("image/jpeg")
        assertThat(CoverArtRule.mimeTypeOf(png)).isEqualTo("image/png")
        assertThat(CoverArtRule.mimeTypeOf(gif)).isNull()
        assertThat(CoverArtRule.mimeTypeOf(ByteArray(0))).isNull()
    }

    @Test
    fun `small JPEG and PNG covers are kept byte for byte, anything else is re-encoded`() {
        val limit = CoverArtRule.MAX_ORIGINAL_BYTES

        assertThat(CoverArtRule.keepsOriginal("image/jpeg", 1500, 1500, limit)).isTrue()
        assertThat(CoverArtRule.keepsOriginal("image/png", 600, 600, 10_000)).isTrue()
        assertThat(CoverArtRule.keepsOriginal("image/jpeg", 1501, 900, 10_000)).isFalse()
        assertThat(CoverArtRule.keepsOriginal("image/jpeg", 900, 1501, 10_000)).isFalse()
        assertThat(CoverArtRule.keepsOriginal("image/jpeg", 800, 800, limit + 1)).isFalse()
        assertThat(CoverArtRule.keepsOriginal(null, 800, 800, 10_000)).isFalse()
    }

    @Test
    fun `re-encoded covers fit 1000 x 1000 keeping their shape and are never enlarged`() {
        assertThat(CoverArtRule.scaledSize(3000, 3000)).isEqualTo(1000 to 1000)
        assertThat(CoverArtRule.scaledSize(4000, 2000)).isEqualTo(1000 to 500)
        assertThat(CoverArtRule.scaledSize(1200, 2400)).isEqualTo(500 to 1000)
        assertThat(CoverArtRule.scaledSize(640, 480)).isEqualTo(640 to 480)
        assertThat(CoverArtRule.scaledSize(5000, 1)).isEqualTo(1000 to 1)
    }
}
