package io.sonora.core.transcoder.wav

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.conversion.MetadataTextField
import io.sonora.core.domain.conversion.TrackMetadata
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test

class RiffInfoTest {

    /** The sub-chunks of an INFO list body as id to text (the terminating NUL removed). */
    private fun entries(body: ByteArray): List<Pair<String, String>> {
        assertThat(String(body, 0, 4, Charsets.US_ASCII)).isEqualTo("INFO")
        val b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        val out = ArrayList<Pair<String, String>>()
        var at = 4
        while (at < body.size) {
            val id = String(body, at, 4, Charsets.US_ASCII)
            val size = b.getInt(at + 4)
            assertThat(body[at + 8 + size - 1]).isEqualTo(0)
            out += id to String(body, at + 8, size - 1, Charsets.UTF_8)
            at += 8 + size + (size and 1)
        }
        assertThat(at).isEqualTo(body.size)
        return out
    }

    @Test
    fun `the basic fields go to their INFO ids, NUL-terminated and padded to even sizes`() {
        val metadata = TrackMetadata(
            text = mapOf(
                MetadataTextField.TITLE to "Señorita",
                MetadataTextField.ARTIST to "Nadia",
                MetadataTextField.ALBUM to "Ibiza",
                MetadataTextField.GENRE to "House",
                MetadataTextField.COPYRIGHT to "(c) 2026",
            ),
            date = "2026-05-07",
            trackNumber = 3,
            trackTotal = 12,
            comment = "Nice",
        )

        val body = RiffInfo.body(metadata, "Sonora (WAV)")

        assertThat(entries(body)).containsExactly(
            "INAM" to "Señorita",
            "IART" to "Nadia",
            "IPRD" to "Ibiza",
            "IGNR" to "House",
            "ICRD" to "2026-05-07",
            "ITRK" to "3",
            "ICMT" to "Nice",
            "ICOP" to "(c) 2026",
            "ISFT" to "Sonora (WAV)",
        ).inOrder()
        assertThat(body.size % 2).isEqualTo(0)
    }

    @Test
    fun `missing fields are left out, the encoder is always there`() {
        assertThat(entries(RiffInfo.body(TrackMetadata(), "Sonora"))).containsExactly("ISFT" to "Sonora")
    }
}
