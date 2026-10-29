package io.sonora.core.transcoder.metadata

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.audio.AiffParser
import io.sonora.core.domain.audio.AiffSource
import io.sonora.core.domain.audio.PcmSampleFormat
import io.sonora.core.domain.conversion.ConversionProfiles
import io.sonora.core.domain.conversion.CoverArt
import io.sonora.core.domain.conversion.MetadataTextField
import io.sonora.core.domain.conversion.TrackMetadata
import io.sonora.core.domain.conversion.UserTextField
import io.sonora.core.domain.port.SourceMetadataReader
import io.sonora.core.transcoder.ConversionEngine
import io.sonora.core.transcoder.FakePcmDecoder
import io.sonora.core.transcoder.FakePcmStream
import io.sonora.core.transcoder.MemoryChannel
import io.sonora.core.transcoder.encoder.EncoderRegistry
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.test.runTest
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.aiff.AiffTag
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.ID3v23Tag
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Metadata through the engine into an AIFF, read back by JAudioTagger (as taggers and rekordbox read it). */
class MetadataRoundTripTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val source = "content://music/track.flac"
    private val xdj = ConversionProfiles.XDJ_700
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(5_000) { it.toByte() }

    private val metadata = TrackMetadata(
        text = mapOf(
            MetadataTextField.TITLE to "Señorita (Extended Mix)",
            MetadataTextField.ARTIST to "Artist A; アーティスト",
            MetadataTextField.ALBUM_ARTIST to "Various Artists",
            MetadataTextField.ALBUM to "Ibiza 2026",
            MetadataTextField.GENRE to "Deep House",
            MetadataTextField.COMPOSER to "Composer",
            MetadataTextField.BPM to "124",
            MetadataTextField.INITIAL_KEY to "8A",
            MetadataTextField.ISRC to "GBAYE0000001",
            MetadataTextField.LABEL to "Defected",
            MetadataTextField.REMIXER to "Remixer",
        ),
        date = "2026-05-07",
        trackNumber = 3, trackTotal = 12, discNumber = 1, discTotal = 2,
        comment = "Comentário",
        lyrics = "Line one\nLine two",
        userText = listOf(UserTextField("REPLAYGAIN_TRACK_GAIN", "-7.10 dB"), UserTextField("REPLAYGAIN_TRACK_PEAK", "0.988")),
        cover = CoverArt(jpeg, "image/jpeg", 600, 600),
    )

    private suspend fun convert(read: SourceMetadataReader): ByteArray {
        val output = MemoryChannel()
        val decoder = FakePcmDecoder(
            mapOf(source to { FakePcmStream(FakePcmStream.format(44_100, 2, PcmSampleFormat.INT16, 441), FloatArray(882)) }),
        )
        ConversionEngine(decoder, EncoderRegistry.default(), Id3MetadataChunkSource(read)).convert(source, xdj, output)
        return output.bytes()
    }

    private fun tagOf(bytes: ByteArray): Tag {
        TagOptionSingleton.getInstance().isAndroid = true
        val file = folder.newFile("out.aiff").apply { writeBytes(bytes) }
        return AudioFileIO.readAs(file, "aiff").tag
    }

    @Test
    fun `every mapped field, the cover and the encoder survive the conversion`() = runTest {
        val bytes = convert { metadata }
        val tag = tagOf(bytes)

        assertThat(tag.getFirst(FieldKey.TITLE)).isEqualTo("Señorita (Extended Mix)")
        assertThat(tag.getFirst(FieldKey.ARTIST)).isEqualTo("Artist A; アーティスト")
        assertThat(tag.getFirst(FieldKey.ALBUM_ARTIST)).isEqualTo("Various Artists")
        assertThat(tag.getFirst(FieldKey.ALBUM)).isEqualTo("Ibiza 2026")
        assertThat(tag.getFirst(FieldKey.GENRE)).isEqualTo("Deep House")
        assertThat(tag.getFirst(FieldKey.COMPOSER)).isEqualTo("Composer")
        assertThat(tag.getFirst(FieldKey.BPM)).isEqualTo("124")
        assertThat(tag.getFirst(FieldKey.KEY)).isEqualTo("8A")
        assertThat(tag.getFirst(FieldKey.ISRC)).isEqualTo("GBAYE0000001")
        assertThat(tag.getFirst(FieldKey.RECORD_LABEL)).isEqualTo("Defected")
        assertThat(tag.getFirst(FieldKey.REMIXER)).isEqualTo("Remixer")
        assertThat(tag.getFirst(FieldKey.YEAR)).startsWith("2026")
        assertThat(tag.getFirst(FieldKey.TRACK)).isEqualTo("3")
        assertThat(tag.getFirst(FieldKey.TRACK_TOTAL)).isEqualTo("12")
        assertThat(tag.getFirst(FieldKey.DISC_NO)).isEqualTo("1")
        assertThat(tag.getFirst(FieldKey.DISC_TOTAL)).isEqualTo("2")
        assertThat(tag.getFirst(FieldKey.COMMENT)).isEqualTo("Comentário")
        assertThat(tag.getFirst(FieldKey.LYRICS)).isEqualTo("Line one\nLine two")
        val id3 = (tag as AiffTag).iD3Tag
        assertThat(id3).isInstanceOf(ID3v23Tag::class.java)
        assertThat(id3.getFirst("TSSE")).isEqualTo("Sonora (Pioneer XDJ-700)") // FieldKey.ENCODER is TENC in ID3
        assertThat(id3.getFirst("TLEN")).isEmpty()
        val txxx = id3.getFields("TXXX").map { ((it as AbstractID3v2Frame).body as FrameBodyTXXX).let { body -> body.description to body.text } }
        assertThat(txxx).containsExactly("REPLAYGAIN_TRACK_GAIN" to "-7.10 dB", "REPLAYGAIN_TRACK_PEAK" to "0.988")
        val art = tag.firstArtwork
        assertThat(art.binaryData).isEqualTo(jpeg)
        assertThat(art.mimeType).isEqualTo("image/jpeg")
        assertThat(art.pictureType).isEqualTo(3)
    }

    @Test
    fun `the AIFF parser still reads the output, skipping the ID3 chunk after the sound`() = runTest {
        val odd = metadata.copy(text = mapOf(MetadataTextField.TITLE to "odd"), cover = null, userText = emptyList())
        for (meta in listOf(metadata, odd)) {
            val bytes = convert { meta }

            val header = AiffParser.parse(AiffSource.of(ByteArrayInputStream(bytes)))

            assertThat(header.frameCount).isEqualTo(441)
            assertThat(header.dataStart + header.dataLength).isEqualTo(54L + 441 * 4)
            assertThat(bytes.size % 2).isEqualTo(0)
            assertThat(String(bytes, 54 + 441 * 4, 4, Charsets.US_ASCII)).isEqualTo("ID3 ")
        }
    }

    @Test
    fun `unreadable or empty tags write no chunk and the conversion still succeeds`() = runTest {
        for (reader in listOf(SourceMetadataReader { null }, SourceMetadataReader { TrackMetadata() }, SourceMetadataReader { error("boom") })) {
            val bytes = convert(reader)

            assertThat(bytes.size).isEqualTo(54 + 441 * 4)
        }
    }
}
