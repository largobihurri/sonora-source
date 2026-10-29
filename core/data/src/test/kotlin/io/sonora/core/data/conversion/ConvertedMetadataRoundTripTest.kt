package io.sonora.core.data.conversion

import com.google.common.truth.Truth.assertThat
import io.sonora.core.data.library.tags.JAudioTagger
import io.sonora.core.domain.audio.PcmDecoder
import io.sonora.core.domain.audio.PcmFormat
import io.sonora.core.domain.audio.PcmSampleFormat
import io.sonora.core.domain.audio.PcmStream
import io.sonora.core.domain.conversion.ConversionProfiles
import io.sonora.core.domain.conversion.CoverArt
import io.sonora.core.domain.conversion.CoverArtRule
import io.sonora.core.domain.port.SourceMetadataReader
import io.sonora.core.transcoder.ConversionEngine
import io.sonora.core.transcoder.encoder.EncoderRegistry
import io.sonora.core.transcoder.metadata.Id3MetadataChunkSource
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.test.runTest
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.mp3.MP3File
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.aiff.AiffTag
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.ID3v23Tag
import org.jaudiotagger.tag.id3.ID3v24Tag
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import org.jaudiotagger.tag.images.ArtworkFactory
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tagged source files (written by JAudioTagger like a tagger would) through the reader, the ID3 chunk source and the
 * conversion engine into an AIFF that JAudioTagger reads back: every mapped field and the cover arrive unchanged.
 */
class ConvertedMetadataRoundTripTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val cover = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(3_000) { (it * 7).toByte() }

    /** Keeps covers as they are (the Android re-encoder is covered by its own Robolectric test). */
    private val keepCover: (ByteArray) -> CoverArt? = { bytes -> CoverArtRule.mimeTypeOf(bytes)?.let { CoverArt(bytes, it, 500, 500) } }

    private val reader by lazy { SourceMetadataFileReader(folder.newFolder("cache"), keepCover) }

    private fun tagged(name: String, bytes: ByteArray, readerKey: String, prepare: (org.jaudiotagger.audio.AudioFile) -> Unit = {}): File {
        val file = folder.newFile(name).apply { writeBytes(bytes) }
        JAudioTagger.read {
            val audio = AudioFileIO.readAs(file, readerKey)
            prepare(audio)
            audio.tagOrCreateAndSetDefault.apply {
                setField(FieldKey.TITLE, "Señorita (Extended Mix)")
                setField(FieldKey.ARTIST, "Artist A")
                setField(FieldKey.ALBUM_ARTIST, "Various")
                setField(FieldKey.ALBUM, "Ibiza 2026")
                setField(FieldKey.GENRE, "Deep House")
                setField(FieldKey.COMPOSER, "Composer")
                setField(FieldKey.YEAR, "2026")
                setField(FieldKey.TRACK, "3")
                setField(FieldKey.TRACK_TOTAL, "12")
                setField(FieldKey.DISC_NO, "1")
                setField(FieldKey.DISC_TOTAL, "2")
                setField(FieldKey.COMMENT, "Comentário")
                setField(FieldKey.BPM, "124")
                setField(FieldKey.KEY, "8A")
                setField(FieldKey.ISRC, "GBAYE0000001")
                setField(FieldKey.RECORD_LABEL, "Defected")
                setField(FieldKey.LYRICS, "Line one\nLine two")
                if (this is FlacTag) {
                    // FlacTag.setField(Artwork) needs the image size, which AndroidArtwork cannot decode.
                    setField(createArtworkField(cover, 3, "image/jpeg", "", 500, 500, 24, 0))
                } else {
                    setField(ArtworkFactory.getNew().apply { binaryData = cover; mimeType = "image/jpeg"; pictureType = 3 })
                }
            }
            audio.commit()
        }
        return file
    }

    private suspend fun convert(file: File, readerKey: String): Tag {
        val source = "file://${file.path}"
        val metadata = SourceMetadataReader { reader.read(readerKey, direct = null, copyTo = { out -> file.inputStream().use { it.copyTo(out) } }) }
        val decoder = object : PcmDecoder {
            override suspend fun open(sourceUri: String): PcmStream = SilentStream()
        }
        val output = folder.newFile("${file.name}.aiff")
        RandomAccessFile(output, "rw").use { access ->
            ConversionEngine(decoder, EncoderRegistry.default(), Id3MetadataChunkSource(metadata))
                .convert(source, ConversionProfiles.XDJ_700, access.channel)
        }
        return JAudioTagger.read { AudioFileIO.readAs(output, "aiff").tag }
    }

    private fun assertCarried(tag: Tag) {
        assertThat(tag.getFirst(FieldKey.TITLE)).isEqualTo("Señorita (Extended Mix)")
        assertThat(tag.getFirst(FieldKey.ARTIST)).isEqualTo("Artist A")
        assertThat(tag.getFirst(FieldKey.ALBUM_ARTIST)).isEqualTo("Various")
        assertThat(tag.getFirst(FieldKey.ALBUM)).isEqualTo("Ibiza 2026")
        assertThat(tag.getFirst(FieldKey.GENRE)).isEqualTo("Deep House")
        assertThat(tag.getFirst(FieldKey.COMPOSER)).isEqualTo("Composer")
        assertThat(tag.getFirst(FieldKey.YEAR)).isEqualTo("2026")
        assertThat(tag.getFirst(FieldKey.TRACK)).isEqualTo("3")
        assertThat(tag.getFirst(FieldKey.TRACK_TOTAL)).isEqualTo("12")
        assertThat(tag.getFirst(FieldKey.DISC_NO)).isEqualTo("1")
        assertThat(tag.getFirst(FieldKey.DISC_TOTAL)).isEqualTo("2")
        assertThat(tag.getFirst(FieldKey.COMMENT)).isEqualTo("Comentário")
        assertThat(tag.getFirst(FieldKey.BPM)).isEqualTo("124")
        assertThat(tag.getFirst(FieldKey.KEY)).isEqualTo("8A")
        assertThat(tag.getFirst(FieldKey.ISRC)).isEqualTo("GBAYE0000001")
        assertThat(tag.getFirst(FieldKey.RECORD_LABEL)).isEqualTo("Defected")
        assertThat(tag.getFirst(FieldKey.LYRICS)).isEqualTo("Line one\nLine two")
        val id3 = (tag as AiffTag).iD3Tag
        assertThat(id3).isInstanceOf(ID3v23Tag::class.java)
        assertThat(id3.getFirst("TSSE")).isEqualTo("Sonora (Pioneer XDJ-700)")
        assertThat(id3.getFirst("TLEN")).isEmpty()
        assertThat(tag.firstArtwork.binaryData).isEqualTo(cover)
        assertThat(tag.firstArtwork.mimeType).isEqualTo("image/jpeg")
    }

    @Test
    fun `an AIFF source keeps its tags and cover`() = runTest {
        assertCarried(convert(tagged("in.aiff", AudioFixtures.aiff(), "aiff"), "aiff"))
    }

    @Test
    fun `a WAV source keeps its tags and cover`() = runTest {
        assertCarried(convert(tagged("in.wav", AudioFixtures.wav(), "wav"), "wav"))
    }

    @Test
    fun `a FLAC source keeps its Vorbis comments, cover and ReplayGain`() = runTest {
        val file = tagged("in.flac", AudioFixtures.flac(), "flac")
        JAudioTagger.read {
            val audio = AudioFileIO.readAs(file, "flac")
            audio.tag.apply {
                addField(createField(FieldKey.ARTIST, "Artist B"))
                setField((this as FlacTag).createField("REPLAYGAIN_TRACK_GAIN", "-7.10 dB"))
            }
            audio.commit()
        }

        val tag = convert(file, "flac")

        assertThat(tag.getFirst(FieldKey.ARTIST)).isEqualTo("Artist A; Artist B")
        val txxx = (tag as AiffTag).iD3Tag.getFields("TXXX").map { ((it as AbstractID3v2Frame).body as FrameBodyTXXX).let { b -> b.description to b.text } }
        assertThat(txxx).contains("REPLAYGAIN_TRACK_GAIN" to "-7.10 dB")
        assertThat(tag.getFirst(FieldKey.TITLE)).isEqualTo("Señorita (Extended Mix)")
        assertThat(tag.firstArtwork.binaryData).isEqualTo(cover)
    }

    @Test
    fun `an MP3 with an ID3v2_4 tag keeps its tags and cover`() = runTest {
        val file = tagged("in.mp3", AudioFixtures.mp3(), "mp3") { (it as MP3File).iD3v2Tag = ID3v24Tag() }

        assertCarried(convert(file, "mp3"))
    }

    @Test
    fun `an unreadable source converts without an ID3 chunk`() = runTest {
        val garbage = folder.newFile("broken.flac").apply { writeBytes(ByteArray(512) { 7 }) }

        val metadata = reader.read("flac", direct = garbage, copyTo = { error("no copy needed") })

        assertThat(metadata).isNull()
    }

    /** One second of silence at 44.1 kHz stereo 16-bit. */
    private class SilentStream : PcmStream {
        private var left = 44_100
        override val format = PcmFormat(44_100, 2, PcmSampleFormat.INT16, "test", durationUs = null, totalFrames = 44_100)
        override suspend fun read(buffer: FloatArray): Int {
            if (left == 0) return -1
            val frames = minOf(left, buffer.size / 2)
            buffer.fill(0f, 0, frames * 2)
            left -= frames
            return frames
        }
        override fun close() = Unit
    }
}
