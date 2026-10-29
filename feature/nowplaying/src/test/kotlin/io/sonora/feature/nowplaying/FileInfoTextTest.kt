package io.sonora.feature.nowplaying

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.AudioStreamInfo
import io.sonora.core.domain.model.DecodedAudioFormat
import io.sonora.core.domain.model.EmbeddedArtworkInfo
import io.sonora.core.domain.model.FileDetails
import io.sonora.core.domain.model.FileLocation
import io.sonora.core.domain.model.PcmEncoding
import io.sonora.core.domain.model.SampleFormat
import io.sonora.core.domain.model.TagEntry
import io.sonora.core.domain.model.TagInfo
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.model.TrackFileInfo
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Test

class FileInfoTextTest {

    private val en = XmlStrings.of()

    private fun song(artist: String, album: String) = Track(
        id = 1, uri = "content://a", title = "Song", artist = artist, album = album, albumArtist = null,
        durationMs = 1_000, trackNumber = null, discNumber = null, year = null, albumId = 1, artworkUri = null,
        mimeType = "audio/flac", sizeBytes = 1,
    )

    @Test
    fun `the panel header reads artist and album, leaving out blank ones`() {
        assertThat(FileInfoText.headerSubtitle(song("Artist", "Album"))).isEqualTo("Artist · Album")
        assertThat(FileInfoText.headerSubtitle(song("Artist", " "))).isEqualTo("Artist")
        assertThat(FileInfoText.headerSubtitle(song("", "Album"))).isEqualTo("Album")
        assertThat(FileInfoText.headerSubtitle(song(" ", ""))).isNull()
    }

    @Test
    fun `sizes use binary units with one decimal`() {
        assertThat(io.sonora.core.designsystem.text.LocaleFormats.fileSize(1_023, en)).isEqualTo("1,023 bytes")
        assertThat(io.sonora.core.designsystem.text.LocaleFormats.fileSize(1, en)).isEqualTo("1 byte")
        assertThat(io.sonora.core.designsystem.text.LocaleFormats.fileSize(1_536, en)).isEqualTo("1.5\u00A0KiB")
    }

    @Test
    fun `sample rates read in kHz without trailing zeros`() {
        assertThat(FileInfoText.sampleRate(44_100, en)).isEqualTo("44.1\u00A0kHz")
        assertThat(FileInfoText.sampleRate(48_000, en)).isEqualTo("48\u00A0kHz")
        assertThat(FileInfoText.sampleRate(176_400, en)).isEqualTo("176.4\u00A0kHz")
        assertThat(FileInfoText.sampleRate(22_050, en)).isEqualTo("22.05\u00A0kHz")
        assertThat(FileInfoText.sampleRate(11_025, en)).isEqualTo("11.025\u00A0kHz")
    }

    @Test
    fun `bit depth labels tell integer, float and lossy apart`() {
        assertThat(FileInfoText.sampleFormat(SampleFormat.Integer(16), en)).isEqualTo("16-bit")
        assertThat(FileInfoText.sampleFormat(SampleFormat.Integer(24), en)).isEqualTo("24-bit")
        assertThat(FileInfoText.sampleFormat(SampleFormat.FloatingPoint(32), en)).isEqualTo("32-bit float")
        assertThat(FileInfoText.sampleFormat(SampleFormat.Lossy, en)).isEqualTo("Lossy")
    }

    @Test
    fun `channels, bitrates and durations`() {
        assertThat(FileInfoText.channels(1, en)).isEqualTo("Mono")
        assertThat(FileInfoText.channels(2, en)).isEqualTo("Stereo")
        assertThat(FileInfoText.channels(6, en)).isEqualTo("6 channels")
        assertThat(FileInfoText.bitrate(320, variable = false, en)).isEqualTo("320\u00A0kbps")
        assertThat(FileInfoText.bitrate(245, variable = true, en)).isEqualTo("245\u00A0kbps (VBR)")
        assertThat(FileInfoText.bitrate(4_608, variable = false, en)).isEqualTo("4,608\u00A0kbps")
        assertThat(io.sonora.core.designsystem.text.LocaleFormats.duration(0)).isEqualTo("0:00")
        assertThat(io.sonora.core.designsystem.text.LocaleFormats.duration(205_400)).isEqualTo("3:25")
        assertThat(io.sonora.core.designsystem.text.LocaleFormats.duration(3_723_000)).isEqualTo("1:02:03")
    }

    @Test
    fun `disc and track positions show their totals when known`() {
        assertThat(FileInfoText.position(3, 12, en)).isEqualTo("3 of 12")
        assertThat(FileInfoText.position(3, null, en)).isEqualTo("3")
        assertThat(FileInfoText.position(null, 12, en)).isNull()
        assertThat(FileInfoText.position(null, null, en)).isNull()
    }

    @Test
    fun `PCM encodings name their depth, kind and byte order`() {
        assertThat(FileInfoText.pcm(PcmEncoding.PCM_16, en)).isEqualTo("16-bit integer")
        assertThat(FileInfoText.pcm(PcmEncoding.PCM_24_BIG_ENDIAN, en)).isEqualTo("24-bit integer, big-endian")
        assertThat(FileInfoText.pcm(PcmEncoding.FLOAT_32, en)).isEqualTo("32-bit float")
    }

    private val info = TrackFileInfo(
        location = FileLocation("Music/Rock", "Pink Floyd/Animals", "01 Dogs.flac", "content://doc/1"),
        file = FileDetails("FLAC", 44_040_192, LocalDateTime.of(2024, 5, 3, 14, 30).toInstant(ZoneOffset.UTC).toEpochMilli()),
        audio = AudioStreamInfo(
            codec = "FLAC", encoder = "reference libFLAC 1.4.3", sampleRateHz = 96_000, sampleFormat = SampleFormat.Integer(24),
            channelCount = 2, bitrateKbps = 3_012, variableBitrate = false, durationMs = 1_026_000,
        ),
        tags = TagInfo(
            title = "Dogs", artist = "Pink Floyd", albumArtist = "Pink Floyd", album = "Animals", discNumber = 1,
            discTotal = 1, trackNumber = 2, trackTotal = 5, date = "1977", genre = "Rock", composer = null, comment = "",
            replayGain = listOf(TagEntry("REPLAYGAIN_TRACK_GAIN", "-6.20 dB")),
            artwork = EmbeddedArtworkInfo(1_400, 1_400, 512_000, "image/jpeg"),
            otherFields = listOf(TagEntry("LABEL", "Harvest"), TagEntry("ISRC", " ")),
        ),
    )

    private fun rows(sections: List<InfoSection>, title: String) =
        sections.single { it.title == title }.rows.associate { it.label to it.value }

    @Test
    fun `the sheet lists location, file, audio and metadata, skipping empty values`() {
        val sections = FileInfoText.sections(info, decoded = null, en, zone = ZoneOffset.UTC)

        assertThat(sections.map { it.title }).containsExactly("Location", "File", "Audio", "Metadata").inOrder()
        assertThat(rows(sections, "Location")).containsExactly(
            "Library Folder", "Music/Rock", "Folder", "Pink Floyd/Animals", "File Name", "01 Dogs.flac", "URI", "content://doc/1",
        ).inOrder()
        assertThat(rows(sections, "File")).containsExactly(
            "Format", "FLAC", "Size", "42.0\u00A0MiB", "Modified", "May 3, 2024, 2:30 PM",
        ).inOrder()
        assertThat(rows(sections, "Audio")).containsExactly(
            "Codec", "FLAC", "Encoder", "reference libFLAC 1.4.3", "Sample Rate", "96\u00A0kHz", "Bit Depth", "24-bit",
            "Channels", "Stereo", "Bitrate", "3,012\u00A0kbps", "Duration", "17:06",
        ).inOrder()
        assertThat(rows(sections, "Metadata")).containsExactly(
            "Title", "Dogs", "Artist", "Pink Floyd", "Album Artist", "Pink Floyd", "Album", "Animals", "Disc", "1 of 1",
            "Track", "2 of 5", "Year", "1977", "Genre", "Rock", "REPLAYGAIN_TRACK_GAIN", "-6.20 dB",
            "Artwork", "1400\u00A0×\u00A01400 · JPEG · 500.0\u00A0KiB", "LABEL", "Harvest",
        ).inOrder()
        assertThat(sections.single { it.title == "Location" }.rows.single { it.label == "URI" }.copyable).isTrue()
    }

    @Test
    fun `a file without readable tags says so instead of listing metadata`() {
        val sections = FileInfoText.sections(info.copy(tags = null), decoded = null, en, zone = ZoneOffset.UTC)

        val metadata = sections.single { it.title == "Metadata" }
        assertThat(metadata.rows).isEmpty()
        assertThat(metadata.footer).isEqualTo("Tags unavailable for this file.")
    }

    @Test
    fun `a file read without any tag says it has none`() {
        val sections = FileInfoText.sections(info.copy(tags = TagInfo()), decoded = null, en, zone = ZoneOffset.UTC)

        val metadata = sections.single { it.title == "Metadata" }
        assertThat(metadata.rows).isEmpty()
        assertThat(metadata.footer).isEqualTo("This file has no tags.")
    }

    @Test
    fun `the playback section shows what the decoder receives and what the AudioTrack plays`() {
        val decoded = DecodedAudioFormat(
            queueItemId = 7, inputMimeType = "audio/raw", inputEncoding = PcmEncoding.PCM_24_BIG_ENDIAN,
            inputSampleRateHz = 96_000, channelCount = 2, outputEncoding = PcmEncoding.FLOAT_32, outputSampleRateHz = 96_000,
        )

        val sections = FileInfoText.sections(info, decoded, en, zone = ZoneOffset.UTC)

        assertThat(sections.map { it.title }).containsExactly("Location", "File", "Audio", "Playback", "Metadata").inOrder()
        assertThat(rows(sections, "Playback")).containsExactly(
            "Decoder Input", "PCM 24-bit integer, big-endian · 96\u00A0kHz", "Output", "32-bit float · 96\u00A0kHz", "Channels", "Stereo",
        ).inOrder()
        val compressed = decoded.copy(inputMimeType = "audio/flac", inputEncoding = null)
        assertThat(rows(FileInfoText.sections(info, compressed, en, ZoneOffset.UTC), "Playback")["Decoder Input"])
            .isEqualTo("FLAC · 96\u00A0kHz")
    }

    @Test
    fun `a value never breaks between a number and its unit`() {
        // QA round 4: File Info's artwork line wrapped as "700 x 700 · JPEG · 210,6" / "KiB". No-break spaces keep each
        // number with its unit (and the two sides of a size together); the line may still wrap at the separators.
        val es = XmlStrings.of("-es")
        val artwork = info.copy(tags = info.tags!!.copy(artwork = EmbeddedArtworkInfo(700, 700, 215_654, "image/jpeg")))
        assertThat(rows(FileInfoText.sections(artwork, decoded = null, es, zone = ZoneOffset.UTC), "Metadatos")["Portada"])
            .isEqualTo("700\u00A0×\u00A0700 · JPEG · 210,6\u00A0KiB")
        for (strings in listOf(en, es, XmlStrings.of("-eu"))) {
            for (value in listOf(
                FileInfoText.sampleRate(44_100, strings),
                FileInfoText.bitrate(320, variable = true, strings),
                FileInfoText.sampleFormat(SampleFormat.Integer(24), strings),
                FileInfoText.pcm(PcmEncoding.FLOAT_32, strings),
                io.sonora.core.designsystem.text.LocaleFormats.fileSize(215_654, strings),
            )) {
                assertThat(value).doesNotContainMatch("\\d (k|K|M|G|T|b)")
            }
        }
    }

    @Test
    fun `values follow the language`() {
        val es = XmlStrings.of("-es")

        assertThat(FileInfoText.sampleRate(44_100, es)).isEqualTo("44,1\u00A0kHz")
        assertThat(FileInfoText.position(3, 12, es)).isEqualTo("3 de 12")
        assertThat(rows(FileInfoText.sections(info, decoded = null, es, zone = ZoneOffset.UTC), "Archivo")["Tamaño"])
            .isEqualTo("42,0\u00A0MiB")
        assertThat(FileInfoText.channels(6, XmlStrings.of("-eu"))).isEqualTo("6 kanal")
    }
}
