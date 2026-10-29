package io.sonora.core.transcoder.metadata

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.conversion.CoverArt
import io.sonora.core.domain.conversion.MetadataTextField
import io.sonora.core.domain.conversion.TrackMetadata
import io.sonora.core.domain.conversion.UserTextField
import java.nio.ByteBuffer
import org.junit.Test

/** Byte-level checks of the ID3v2.3 tag; [MetadataRoundTripTest] reads it back with JAudioTagger. */
class Id3v23WriterTest {

    /** Frame id -> raw body, in tag order. */
    private fun frames(tag: ByteArray): List<Pair<String, ByteArray>> {
        val buffer = ByteBuffer.wrap(tag)
        val header = ByteArray(10).also { buffer.get(it) }
        val size = (header[6].toInt() shl 21) or (header[7].toInt() shl 14) or (header[8].toInt() shl 7) or header[9].toInt()
        assertThat(size).isEqualTo(tag.size - 10)
        val frames = mutableListOf<Pair<String, ByteArray>>()
        while (buffer.remaining() >= 10) {
            val id = String(ByteArray(4).also { buffer.get(it) }, Charsets.ISO_8859_1)
            val length = buffer.getInt()
            assertThat(buffer.getShort()).isEqualTo(0) // no frame flags
            frames += id to ByteArray(length).also { buffer.get(it) }
        }
        assertThat(buffer.remaining()).isEqualTo(0)
        return frames
    }

    private fun List<Pair<String, ByteArray>>.body(id: String) = single { it.first == id }.second

    @Test
    fun `the header is ID3v2_3 without flags and with a syncsafe size`() {
        val tag = Id3v23Writer.write(TrackMetadata(text = mapOf(MetadataTextField.TITLE to "x".repeat(300))), "Sonora")

        assertThat(tag.copyOfRange(0, 6)).isEqualTo(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0))
        tag.copyOfRange(6, 10).forEach { assertThat(it.toInt() and 0x80).isEqualTo(0) }
        frames(tag) // checks the declared size
    }

    @Test
    fun `Latin-1 text is stored as ISO-8859-1, anything else as UTF-16 with a byte order mark`() {
        val tag = Id3v23Writer.write(
            TrackMetadata(text = mapOf(MetadataTextField.TITLE to "Café", MetadataTextField.ARTIST to "坂本龍一")),
            "Sonora",
        )
        val frames = frames(tag)

        assertThat(frames.body("TIT2")).isEqualTo(byteArrayOf(0) + "Café".toByteArray(Charsets.ISO_8859_1))
        assertThat(frames.body("TPE1")).isEqualTo(
            byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte()) + "坂本龍一".toByteArray(Charsets.UTF_16LE),
        )
    }

    @Test
    fun `numbers, dates and the encoder are written the ID3v2_3 way and the length is left out`() {
        val tag = Id3v23Writer.write(
            TrackMetadata(trackNumber = 3, trackTotal = 12, discNumber = 1, date = "2021-05-07", text = mapOf(MetadataTextField.BPM to "127.6")),
            "Sonora (Pioneer XDJ-700)",
        )
        val frames = frames(tag)
        fun text(id: String) = String(frames.body(id), 1, frames.body(id).size - 1, Charsets.ISO_8859_1)

        assertThat(text("TRCK")).isEqualTo("3/12")
        assertThat(text("TPOS")).isEqualTo("1")
        assertThat(text("TYER")).isEqualTo("2021")
        assertThat(text("TDAT")).isEqualTo("0705")
        assertThat(text("TBPM")).isEqualTo("128")
        assertThat(text("TSSE")).isEqualTo("Sonora (Pioneer XDJ-700)")
        assertThat(frames.map { it.first }).containsNoneOf("TLEN", "TDRC")
    }

    @Test
    fun `a year alone gives TYER only, an unreadable BPM or date nothing`() {
        val frames = frames(Id3v23Writer.write(TrackMetadata(date = "1999", text = mapOf(MetadataTextField.BPM to "fast")), "S"))
        val none = frames(Id3v23Writer.write(TrackMetadata(date = "sometime"), "S"))

        assertThat(frames.map { it.first }).containsExactly("TYER", "TSSE").inOrder()
        assertThat(none.map { it.first }).containsExactly("TSSE")
    }

    @Test
    fun `user text, comment, lyrics and cover use their own frame layouts`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
        val tag = Id3v23Writer.write(
            TrackMetadata(
                userText = listOf(UserTextField("REPLAYGAIN_TRACK_GAIN", "-6.20 dB"), UserTextField("Ünïcode", "値")),
                comment = "Nice",
                lyrics = "La la",
                cover = CoverArt(png, "image/png", 1, 1),
            ),
            "S",
        )
        val frames = frames(tag)
        val txxx = frames.filter { it.first == "TXXX" }.map { it.second }

        assertThat(txxx[0]).isEqualTo(byteArrayOf(0) + "REPLAYGAIN_TRACK_GAIN".toByteArray(Charsets.ISO_8859_1) + 0 + "-6.20 dB".toByteArray(Charsets.ISO_8859_1))
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        assertThat(txxx[1]).isEqualTo(
            byteArrayOf(1) + bom + "Ünïcode".toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0) + bom + "値".toByteArray(Charsets.UTF_16LE),
        )
        assertThat(frames.body("COMM")).isEqualTo(byteArrayOf(0) + "eng".toByteArray() + 0 + "Nice".toByteArray())
        assertThat(frames.body("USLT")).isEqualTo(byteArrayOf(0) + "eng".toByteArray() + 0 + "La la".toByteArray())
        assertThat(frames.body("APIC")).isEqualTo(byteArrayOf(0) + "image/png".toByteArray() + byteArrayOf(0, 3, 0) + png)
        assertThat(frames.last().first).isEqualTo("APIC")
    }

    @Test
    fun `every mapped text field has its own frame`() {
        val all = MetadataTextField.entries.associateWith { if (it == MetadataTextField.BPM) "120" else "v" }
        val ids = frames(Id3v23Writer.write(TrackMetadata(text = all), "S")).map { it.first }

        assertThat(ids).containsAtLeast(
            "TIT2", "TPE1", "TPE2", "TALB", "TCON", "TCOM", "TBPM", "TKEY", "TSRC", "TPUB", "TPE3", "TPE4", "TEXT",
            "TOPE", "TOAL", "TORY", "TIT1", "TIT3", "TCOP", "TLAN", "TMED", "TSOT", "TSOP", "TSOA", "TSO2", "TSOC", "TCMP",
        )
        assertThat(ids).containsNoDuplicates()
    }
}
