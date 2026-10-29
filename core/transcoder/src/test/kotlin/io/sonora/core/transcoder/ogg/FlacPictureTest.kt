package io.sonora.core.transcoder.ogg

import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import java.util.Base64
import org.junit.Test

class FlacPictureTest {

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4, 5)

    @Test
    fun `the picture block holds its fields big endian in the order of the FLAC format`() {
        val block = FlacPicture.block(jpeg, "image/jpeg", width = 300, height = 280)

        val buffer = ByteBuffer.wrap(block)
        assertThat(buffer.getInt()).isEqualTo(3) // front cover
        assertThat(buffer.getInt()).isEqualTo(10)
        val mime = ByteArray(10).also { buffer.get(it) }
        assertThat(String(mime, Charsets.US_ASCII)).isEqualTo("image/jpeg")
        assertThat(buffer.getInt()).isEqualTo(0) // empty description
        assertThat(buffer.getInt()).isEqualTo(300)
        assertThat(buffer.getInt()).isEqualTo(280)
        assertThat(buffer.getInt()).isEqualTo(24) // colour depth
        assertThat(buffer.getInt()).isEqualTo(0) // not indexed
        assertThat(buffer.getInt()).isEqualTo(jpeg.size)
        val data = ByteArray(jpeg.size).also { buffer.get(it) }
        assertThat(data).isEqualTo(jpeg)
        assertThat(buffer.remaining()).isEqualTo(0)
    }

    @Test
    fun `the Vorbis comment value is the base64 of the block`() {
        val comment = FlacPicture.comment(jpeg, "image/jpeg", width = 300, height = 300)

        assertThat(comment.name).isEqualTo("METADATA_BLOCK_PICTURE")
        assertThat(Base64.getDecoder().decode(comment.value)).isEqualTo(FlacPicture.block(jpeg, "image/jpeg", 300, 300))
        assertThat(comment.value).doesNotContain("\n")
        assertThat(comment.value).doesNotContain("=\r")
    }

    @Test
    fun `a description is UTF-8 and counted in bytes`() {
        val block = FlacPicture.block(jpeg, "image/png", 10, 10, description = "Carátula")

        val buffer = ByteBuffer.wrap(block)
        buffer.position(4 + 4 + "image/png".length)
        assertThat(buffer.getInt()).isEqualTo("Carátula".toByteArray(Charsets.UTF_8).size)
    }
}
