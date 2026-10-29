package io.sonora.core.transcoder.metadata

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.conversion.CoverArt
import io.sonora.core.domain.conversion.MetadataTextField
import io.sonora.core.domain.conversion.TrackMetadata
import io.sonora.core.domain.conversion.UserTextField
import io.sonora.core.transcoder.ogg.VorbisComment
import java.util.Base64
import org.junit.Test

class OpusTagCommentsTest {

    private val jpegHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

    private fun cover(width: Int, height: Int, mime: String = "image/jpeg", size: Int = 100) =
        CoverArt(jpegHeader + ByteArray(size - jpegHeader.size), mime, width, height)

    private val noScaling = CoverScaler { _, _ -> error("no scaling expected") }

    private fun comments(metadata: TrackMetadata, scaler: CoverScaler = noScaling) = OpusTagComments.from(metadata, scaler)

    private fun List<VorbisComment>.asPairs() = map { it.name to it.value }

    @Test
    fun `the common fields map to their Vorbis comment names in a fixed order`() {
        val metadata = TrackMetadata(
            text = mapOf(
                MetadataTextField.GENRE to "Baroque",
                MetadataTextField.ALBUM to "Goldberg",
                MetadataTextField.TITLE to "Aria",
                MetadataTextField.ALBUM_ARTIST to "Glenn Gould",
                MetadataTextField.ARTIST to "Bach",
            ),
            date = "1981-09-01",
            trackNumber = 1,
            discNumber = 2,
        )

        assertThat(comments(metadata).asPairs()).containsExactly(
            "TITLE" to "Aria",
            "ARTIST" to "Bach",
            "ALBUM" to "Goldberg",
            "ALBUMARTIST" to "Glenn Gould",
            "TRACKNUMBER" to "1",
            "DISCNUMBER" to "2",
            "DATE" to "1981-09-01",
            "GENRE" to "Baroque",
        ).inOrder()
    }

    @Test
    fun `track and disc totals are separate comments`() {
        val metadata = TrackMetadata(trackNumber = 3, trackTotal = 12, discNumber = 1, discTotal = 2)

        assertThat(comments(metadata).asPairs()).containsExactly(
            "TRACKNUMBER" to "3",
            "TRACKTOTAL" to "12",
            "DISCNUMBER" to "1",
            "DISCTOTAL" to "2",
        ).inOrder()
    }

    @Test
    fun `fields the source does not have are not written`() {
        assertThat(comments(TrackMetadata(text = mapOf(MetadataTextField.TITLE to "Aria")))).hasSize(1)
        assertThat(comments(TrackMetadata())).isEmpty()
    }

    @Test
    fun `no loudness comment is invented`() {
        val names = comments(TrackMetadata(text = mapOf(MetadataTextField.TITLE to "x"))).map { it.name }

        assertThat(names).doesNotContain("R128_TRACK_GAIN")
    }

    @Test
    fun `a ReplayGain track gain becomes R128_TRACK_GAIN in Q7_8 relative to minus 23 LUFS`() {
        fun gainOf(value: String) = comments(TrackMetadata(userText = listOf(UserTextField("REPLAYGAIN_TRACK_GAIN", value))))
            .singleOrNull { it.name == "R128_TRACK_GAIN" }?.value

        // ReplayGain 2 references minus 18 LUFS, so the gain is 5 dB lower against minus 23 LUFS: (-6.5 - 5) * 256.
        assertThat(gainOf("-6.50 dB")).isEqualTo("-2944")
        assertThat(gainOf("+3.00 dB")).isEqualTo("-512")
        assertThat(gainOf("5 dB")).isEqualTo("0")
        assertThat(gainOf("-6,5dB")).isEqualTo("-2944")
        assertThat(gainOf("not a gain")).isNull()
        assertThat(gainOf("")).isNull()
    }

    @Test
    fun `the ReplayGain description is matched ignoring case and an absurd gain is clamped to 16 bits`() {
        val metadata = TrackMetadata(userText = listOf(UserTextField("replaygain_track_gain", "-300 dB")))

        assertThat(comments(metadata).single().value).isEqualTo("-32768")
    }

    @Test
    fun `a small JPEG cover is embedded as it is without scaling`() {
        val art = cover(300, 280)

        val picture = comments(TrackMetadata(cover = art)).single()

        assertThat(picture.name).isEqualTo("METADATA_BLOCK_PICTURE")
        val block = Base64.getDecoder().decode(picture.value)
        assertThat(block.copyOfRange(block.size - art.bytes.size, block.size)).isEqualTo(art.bytes)
        // Type 3 (front cover) and the size sit at the start of the block.
        assertThat(block.copyOfRange(0, 4)).isEqualTo(byteArrayOf(0, 0, 0, 3))
    }

    @Test
    fun `a large or non JPEG cover goes through the scaler and its result is embedded`() {
        val scaled = cover(300, 300, size = 60)
        val requests = ArrayList<Pair<CoverArt, Int>>()
        val scaler = CoverScaler { source, maxSide -> requests += source to maxSide; scaled }
        val large = cover(1_200, 1_200)

        val picture = comments(TrackMetadata(cover = large), scaler).single()

        assertThat(requests).hasSize(1)
        assertThat(requests.single().second).isEqualTo(300)
        assertThat(Base64.getDecoder().decode(picture.value).size).isAtLeast(scaled.bytes.size)
        val png = cover(200, 200, mime = "image/png")
        comments(TrackMetadata(cover = png), scaler)
        assertThat(requests).hasSize(2)
    }

    @Test
    fun `a cover that cannot be scaled is left out and the tags still come out`() {
        val metadata = TrackMetadata(text = mapOf(MetadataTextField.TITLE to "Aria"), cover = cover(2_000, 2_000))

        val result = comments(metadata) { _, _ -> null }

        assertThat(result.asPairs()).containsExactly("TITLE" to "Aria")
    }

    @Test
    fun `the cover comes last`() {
        val metadata = TrackMetadata(
            text = mapOf(MetadataTextField.TITLE to "Aria"),
            date = "1981",
            userText = listOf(UserTextField("REPLAYGAIN_TRACK_GAIN", "-6.5 dB")),
            cover = cover(100, 100),
        )

        assertThat(comments(metadata).map { it.name }).containsExactly(
            "TITLE", "DATE", "R128_TRACK_GAIN", "METADATA_BLOCK_PICTURE",
        ).inOrder()
    }
}
