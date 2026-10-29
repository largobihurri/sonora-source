package io.sonora.core.data.library.info

import com.google.common.truth.Truth.assertThat
import io.sonora.core.data.library.tags.JAudioTagger
import io.sonora.core.domain.audio.AiffParser
import io.sonora.core.domain.audio.AiffSource
import io.sonora.core.domain.model.EmbeddedArtworkInfo
import io.sonora.core.domain.model.SampleFormat
import io.sonora.core.domain.model.TagEntry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.id3.ID3v24Frame
import org.jaudiotagger.tag.id3.ID3v24Frames
import org.jaudiotagger.tag.id3.ID3v24Tag
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** File info mapped from what JAudioTagger reads: real generated files and real tag objects. */
class FileInfoMapperTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun read(name: String, bytes: ByteArray, readerKey: String): AudioFile {
        val file = folder.newFile(name).apply { writeBytes(bytes) }
        return JAudioTagger.read { AudioFileIO.readAs(file, readerKey) }
    }

    @Test
    fun `the relative path is the part below the library folder`() {
        assertThat(FileInfoMapper.relativePath("Music/Rock/Pink Floyd/Animals", "Music/Rock")).isEqualTo("Pink Floyd/Animals")
        assertThat(FileInfoMapper.relativePath("Music/Rock", "Music/Rock")).isEqualTo("")
        assertThat(FileInfoMapper.relativePath("Elsewhere/Album", "Music")).isEqualTo("Elsewhere/Album")
        assertThat(FileInfoMapper.relativePath("Music/Rocking", "Music/Rock")).isEqualTo("Music/Rocking")
        assertThat(FileInfoMapper.relativePath(null, "Music")).isNull()
    }

    @Test
    fun `containers are named from the file name, then the MIME type`() {
        assertThat(FileInfoMapper.container("a.flac", "audio/flac")).isEqualTo("FLAC")
        assertThat(FileInfoMapper.container("a.MP3", null)).isEqualTo("MP3")
        assertThat(FileInfoMapper.container("a.m4a", null)).isEqualTo("M4A (MPEG-4)")
        assertThat(FileInfoMapper.container("a.aac", null)).isEqualTo("AAC (ADTS)")
        assertThat(FileInfoMapper.container("a.ogg", null)).isEqualTo("Ogg")
        assertThat(FileInfoMapper.container("a.opus", null)).isEqualTo("Ogg Opus")
        assertThat(FileInfoMapper.container("a.wav", null)).isEqualTo("WAV")
        assertThat(FileInfoMapper.container("a.aif", null)).isEqualTo("AIFF")
        assertThat(FileInfoMapper.container("a.aiff", null, isAifc = true)).isEqualTo("AIFF-C")
        assertThat(FileInfoMapper.container("track", "audio/x-wav")).isEqualTo("WAV")
        assertThat(FileInfoMapper.container("track", null)).isNull()
    }

    @Test
    fun `24-bit and float WAV read as integer and float PCM with their rate, channels and bitrate`() {
        val pcm24 = FileInfoMapper.audio(RawAudioHeader.of(read("hi.wav", wav(2, 96_000, 24, 9_600, 1), "wav").audioHeader), "wav", null)
        val float = FileInfoMapper.audio(RawAudioHeader.of(read("f.wav", wav(1, 48_000, 32, 4_800, 3), "wav").audioHeader), "wav", null)

        assertThat(pcm24.codec).isEqualTo("PCM")
        assertThat(pcm24.sampleFormat).isEqualTo(SampleFormat.Integer(24))
        assertThat(pcm24.sampleRateHz).isEqualTo(96_000)
        assertThat(pcm24.channelCount).isEqualTo(2)
        assertThat(pcm24.bitrateKbps).isEqualTo(4_608)
        assertThat(pcm24.variableBitrate).isFalse()
        assertThat(pcm24.durationMs).isEqualTo(100)
        assertThat(float.codec).isEqualTo("PCM")
        assertThat(float.sampleFormat).isEqualTo(SampleFormat.FloatingPoint(32))
        assertThat(float.channelCount).isEqualTo(1)
    }

    @Test
    fun `AIFF details come from the AIFF header, which knows float and little-endian layouts`() {
        val bytes = aiff(2, 48_000, 32, 4_800, compression = "fl32")
        val header = AiffParser.parse(AiffSource.of(ByteArrayInputStream(bytes)))
        // JAudioTagger reports this float AIFF-C as lossy and variable bitrate; the AIFF header knows better.
        val audio = FileInfoMapper.audio(RawAudioHeader.of(read("f.aifc", bytes, "aifc").audioHeader), "aifc", header)

        assertThat(audio.codec).isEqualTo("PCM")
        assertThat(audio.sampleFormat).isEqualTo(SampleFormat.FloatingPoint(32))
        assertThat(audio.variableBitrate).isFalse()
        assertThat(audio.bitrateKbps).isEqualTo(3_072)
        assertThat(audio.sampleRateHz).isEqualTo(48_000)
        assertThat(audio.durationMs).isEqualTo(100)

        val sowt = aiff(2, 44_100, 16, 441, compression = "sowt")
        val little = FileInfoMapper.audio(RawAudioHeader(), "aifc", AiffParser.parse(AiffSource.of(ByteArrayInputStream(sowt))))
        assertThat(little.codec).isEqualTo("PCM (little-endian)")
        assertThat(little.sampleFormat).isEqualTo(SampleFormat.Integer(16))
    }

    @Test
    fun `lossy and lossless codecs from compressed headers`() {
        val mp3 = RawAudioHeader(
            encodingType = "mp3", format = "MPEG-1 Layer 3", sampleRate = 44_100, bitsPerSample = 16, channels = "Joint Stereo",
            bitrateKbps = 245, variableBitrate = true, lossless = false, durationSec = 201.5, encoder = "LAME3.100",
        )
        val alac = RawAudioHeader(encodingType = "ALAC", format = "Apple Lossless", sampleRate = 96_000, bitsPerSample = 24, channels = "2", lossless = true)
        val aac = RawAudioHeader(encodingType = "AAC", sampleRate = 44_100, bitsPerSample = 16, channels = "2", bitrateKbps = 256, lossless = false)
        val flac = RawAudioHeader(encodingType = "FLAC 24 bits", sampleRate = 192_000, bitsPerSample = 24, channels = "6", lossless = true)

        val mp3Info = FileInfoMapper.audio(mp3, "mp3", null)
        assertThat(mp3Info.codec).isEqualTo("MP3 (MPEG-1 Layer 3)")
        assertThat(mp3Info.encoder).isEqualTo("LAME 3.100")
        assertThat(mp3Info.sampleFormat).isEqualTo(SampleFormat.Lossy)
        assertThat(mp3Info.channelCount).isEqualTo(2)
        assertThat(mp3Info.bitrateKbps).isEqualTo(245)
        assertThat(mp3Info.variableBitrate).isTrue()
        assertThat(mp3Info.durationMs).isEqualTo(201_500)
        assertThat(FileInfoMapper.audio(alac, "m4a", null).let { it.codec to it.sampleFormat }).isEqualTo("ALAC" to SampleFormat.Integer(24))
        assertThat(FileInfoMapper.audio(aac, "m4a", null).let { it.codec to it.sampleFormat }).isEqualTo("AAC" to SampleFormat.Lossy)
        assertThat(FileInfoMapper.audio(flac, "flac", null).let { Triple(it.codec, it.sampleFormat, it.channelCount) })
            .isEqualTo(Triple("FLAC", SampleFormat.Integer(24), 6))
        assertThat(FileInfoMapper.audio(RawAudioHeader(encodingType = "Ogg Vorbis v1", lossless = false), "ogg", null).codec).isEqualTo("Vorbis")
    }

    @Test
    fun `formats without a tag reader still get a codec name`() {
        assertThat(FileInfoMapper.codecWithoutReader("a.opus", null)).isEqualTo("Opus")
        assertThat(FileInfoMapper.codecWithoutReader("a.aac", null)).isEqualTo("AAC")
        assertThat(FileInfoMapper.codecWithoutReader("a", "audio/opus")).isEqualTo("Opus")
        assertThat(FileInfoMapper.codecWithoutReader("a.xyz", null)).isNull()
    }

    @Test
    fun `Vorbis comments map to the common fields, ReplayGain and the remaining raw fields`() {
        val tag = VorbisCommentTag.createNewTag().apply {
            vendor = "reference libFLAC 1.4.3 20230623"
            setField(FieldKey.TITLE, "Dogs")
            setField(FieldKey.ARTIST, "Pink Floyd")
            setField(FieldKey.ALBUM_ARTIST, "Pink Floyd")
            setField(FieldKey.ALBUM, "Animals")
            setField(FieldKey.DISC_NO, "1")
            setField(FieldKey.DISC_TOTAL, "2")
            setField(FieldKey.TRACK, "2")
            setField(FieldKey.TRACK_TOTAL, "5")
            setField(FieldKey.YEAR, "1977-01-23")
            setField(FieldKey.GENRE, "Rock")
            setField(FieldKey.COMPOSER, "Roger Waters")
            setField(FieldKey.COMMENT, "  ")
            addField(createField("REPLAYGAIN_TRACK_GAIN", "-6.20 dB"))
            addField(createField("REPLAYGAIN_ALBUM_PEAK", "0.988"))
            addField(createField("LABEL", "Harvest"))
            addField(createField("ISRC", ""))
            setArtworkField(jpeg, "image/jpeg")
        }

        val info = FileInfoMapper.tags(tag) { 1_400 to 1_200 }

        assertThat(info.title).isEqualTo("Dogs")
        assertThat(info.artist).isEqualTo("Pink Floyd")
        assertThat(info.albumArtist).isEqualTo("Pink Floyd")
        assertThat(info.album).isEqualTo("Animals")
        assertThat(listOf(info.discNumber, info.discTotal, info.trackNumber, info.trackTotal)).containsExactly(1, 2, 2, 5).inOrder()
        assertThat(info.date).isEqualTo("1977-01-23")
        assertThat(info.genre).isEqualTo("Rock")
        assertThat(info.composer).isEqualTo("Roger Waters")
        assertThat(info.comment).isNull()
        assertThat(info.replayGain).containsExactly(
            TagEntry("REPLAYGAIN_TRACK_GAIN", "-6.20 dB"),
            TagEntry("REPLAYGAIN_ALBUM_PEAK", "0.988"),
        ).inOrder()
        assertThat(info.artwork).isEqualTo(EmbeddedArtworkInfo(1_400, 1_200, jpeg.size.toLong(), "image/jpeg"))
        assertThat(info.otherFields).containsExactly(TagEntry("LABEL", "Harvest"))
        assertThat(FileInfoMapper.tagEncoder(tag)).isEqualTo("reference libFLAC 1.4.3 20230623")
    }

    @Test
    fun `ID3 user text frames are listed by their description, and n of total numbers are split`() {
        val tag = ID3v24Tag().apply {
            setField(FieldKey.TITLE, "Song")
            setField(FieldKey.TRACK, "3")
            setField(FieldKey.TRACK_TOTAL, "12")
            setField(FieldKey.ENCODER, "LAME 3.100")
            addField(txxx("REPLAYGAIN_TRACK_GAIN", "-3.10 dB"))
            addField(txxx("MusicBrainz Album Id", "1234-abcd"))
        }

        val info = FileInfoMapper.tags(tag) { null }

        assertThat(info.trackNumber).isEqualTo(3)
        assertThat(info.trackTotal).isEqualTo(12)
        assertThat(info.replayGain).containsExactly(TagEntry("REPLAYGAIN_TRACK_GAIN", "-3.10 dB"))
        assertThat(info.otherFields).contains(TagEntry("MusicBrainz Album Id", "1234-abcd"))
        assertThat(info.otherFields.map { it.key }).containsNoneOf("TIT2", "TRCK", "REPLAYGAIN_TRACK_GAIN")
        assertThat(FileInfoMapper.tagEncoder(tag)).isEqualTo("LAME 3.100")
    }

    private fun txxx(description: String, value: String) = ID3v24Frame(ID3v24Frames.FRAME_ID_USER_DEFINED_INFO).apply {
        body = FrameBodyTXXX().apply {
            this.description = description
            text = value
        }
    }

    /** A JPEG's start, enough for a tag to carry. */
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16) + ByteArray(64)

    private fun wav(channels: Int, rate: Int, bits: Int, frames: Int, formatType: Int): ByteArray {
        val blockAlign = channels * bits / 8
        val data = frames * blockAlign
        return ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(formatType.toShort()); putShort(channels.toShort())
            putInt(rate); putInt(rate * blockAlign); putShort(blockAlign.toShort()); putShort(bits.toShort())
            put("data".toByteArray()); putInt(data)
        }.array()
    }

    private fun aiff(channels: Int, rate: Int, bits: Int, frames: Int, compression: String): ByteArray {
        val bytesPerFrame = channels * if (compression == "fl32") 4 else bits / 8
        val comm = ByteBuffer.allocate(24).apply {
            putShort(channels.toShort()); putInt(frames); putShort(bits.toShort()); put(extended(rate))
            put(compression.toByteArray()); put(0); put(0)
        }.array()
        val ssnd = ByteArray(8) + ByteArray(frames * bytesPerFrame)
        val body = ByteArrayOutputStream().apply {
            write("AIFC".toByteArray())
            write("COMM".toByteArray()); write(be32(comm.size)); write(comm)
            write("SSND".toByteArray()); write(be32(ssnd.size)); write(ssnd)
        }.toByteArray()
        return "FORM".toByteArray() + be32(body.size) + body
    }

    private fun be32(value: Int) = ByteBuffer.allocate(4).putInt(value).array()

    private fun extended(rate: Int): ByteArray {
        val highestBit = 31 - Integer.numberOfLeadingZeros(rate)
        val mantissa = rate.toLong() shl (63 - highestBit)
        return ByteBuffer.allocate(10).putShort((16_383 + highestBit).toShort()).putLong(mantissa).array()
    }
}
