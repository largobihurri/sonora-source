package io.sonora.core.transcoder.mp4

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.conversion.CoverArt
import io.sonora.core.domain.conversion.MetadataTextField
import io.sonora.core.domain.conversion.TrackMetadata
import io.sonora.core.domain.conversion.UserTextField
import java.nio.ByteBuffer
import org.junit.Test

class Mp4IlstTest {

    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)

    /** The `data` box of each item: name to (type indicator, payload). */
    private fun items(ilst: ByteArray): Map<String, Pair<Int, ByteArray>> {
        val box = Mp4Boxes.parse(ilst).single()
        assertThat(box.type).isEqualTo("ilst")
        return Mp4Boxes.parse(box.payload).associate { item ->
            val data = Mp4Boxes.parse(item.payload).last()
            assertThat(data.type).isEqualTo("data")
            val b = ByteBuffer.wrap(data.payload)
            assertThat(b.getInt(4)).isEqualTo(0) // locale
            val name = if (item.type == "----") {
                val parts = Mp4Boxes.parse(item.payload)
                "----:" + String(parts[0].payload, 4, parts[0].payload.size - 4) + ":" + String(parts[1].payload, 4, parts[1].payload.size - 4)
            } else {
                item.type
            }
            name to (b.getInt(0) to data.payload.copyOfRange(8, data.payload.size))
        }
    }

    @Test
    fun `the iTunes items carry the text, numbers, date, genre, cover and encoder`() {
        val metadata = TrackMetadata(
            text = mapOf(
                MetadataTextField.TITLE to "Señorita",
                MetadataTextField.ARTIST to "Nadia",
                MetadataTextField.ALBUM to "Ibiza",
                MetadataTextField.ALBUM_ARTIST to "Various",
                MetadataTextField.GENRE to "House",
                MetadataTextField.COMPOSER to "Composer",
                MetadataTextField.BPM to "123.6",
                MetadataTextField.COMPILATION to "1",
                MetadataTextField.ISRC to "GBAYE0000001",
            ),
            date = "2026-05-07",
            trackNumber = 3, trackTotal = 12, discNumber = 1, discTotal = 2,
            comment = "Nice",
            userText = listOf(UserTextField("REPLAYGAIN_TRACK_GAIN", "-7.10 dB")),
            cover = CoverArt(png, "image/png", 10, 10),
        )

        val items = items(Mp4Ilst.build(metadata, "Sonora (AAC)"))

        fun text(name: String) = items.getValue(name).let { (type, payload) -> assertThat(type).isEqualTo(1); String(payload, Charsets.UTF_8) }
        assertThat(text("©nam")).isEqualTo("Señorita")
        assertThat(text("©ART")).isEqualTo("Nadia")
        assertThat(text("©alb")).isEqualTo("Ibiza")
        assertThat(text("aART")).isEqualTo("Various")
        assertThat(text("©gen")).isEqualTo("House")
        assertThat(text("©wrt")).isEqualTo("Composer")
        assertThat(text("©day")).isEqualTo("2026-05-07")
        assertThat(text("©cmt")).isEqualTo("Nice")
        assertThat(text("©too")).isEqualTo("Sonora (AAC)")
        assertThat(text("----:com.apple.iTunes:ISRC")).isEqualTo("GBAYE0000001")
        assertThat(text("----:com.apple.iTunes:REPLAYGAIN_TRACK_GAIN")).isEqualTo("-7.10 dB")
        assertThat(items.getValue("trkn").first).isEqualTo(0)
        assertThat(items.getValue("trkn").second).isEqualTo(byteArrayOf(0, 0, 0, 3, 0, 12, 0, 0))
        assertThat(items.getValue("disk").second).isEqualTo(byteArrayOf(0, 0, 0, 1, 0, 2))
        assertThat(items.getValue("tmpo").first).isEqualTo(21)
        assertThat(items.getValue("tmpo").second).isEqualTo(byteArrayOf(0, 124))
        assertThat(items.getValue("cpil").second).isEqualTo(byteArrayOf(1))
        assertThat(items.getValue("covr").first).isEqualTo(14)
        assertThat(items.getValue("covr").second).isEqualTo(png)
    }

    @Test
    fun `an empty source gives the encoder alone and a JPEG cover its own type`() {
        assertThat(items(Mp4Ilst.build(TrackMetadata(), "Sonora")).keys).containsExactly("©too")
        val jpeg = TrackMetadata(cover = CoverArt(byteArrayOf(-1, -40, -1), "image/jpeg", 1, 1))
        assertThat(items(Mp4Ilst.build(jpeg, "Sonora")).getValue("covr").first).isEqualTo(13)
    }

    @Test
    fun `a track without a total keeps zero there`() {
        val items = items(Mp4Ilst.build(TrackMetadata(trackNumber = 7), "Sonora"))
        assertThat(items.getValue("trkn").second).isEqualTo(byteArrayOf(0, 0, 0, 7, 0, 0, 0, 0))
    }
}
