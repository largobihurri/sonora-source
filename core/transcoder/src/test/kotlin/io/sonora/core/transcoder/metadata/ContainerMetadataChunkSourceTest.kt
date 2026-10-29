package io.sonora.core.transcoder.metadata

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.conversion.ConversionProfiles
import io.sonora.core.domain.conversion.MetadataTextField
import io.sonora.core.domain.conversion.OutputTarget
import io.sonora.core.domain.conversion.OutputTargets
import io.sonora.core.domain.conversion.TrackMetadata
import io.sonora.core.domain.model.AudioFormat
import io.sonora.core.domain.port.SourceMetadataReader
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ContainerMetadataChunkSourceTest {

    private val reader = SourceMetadataReader { TrackMetadata(text = mapOf(MetadataTextField.TITLE to "Aria")) }
    private val source = ContainerMetadataChunkSource(
        Id3MetadataChunkSource(reader),
        OpusTagsMetadataChunkSource(reader) { _, _ -> null },
        FlacMetadataChunkSource(reader),
        Mp4MetadataChunkSource(reader),
    )

    @Test
    fun `AIFF profiles get an ID3 chunk`() = runTest {
        assertThat(source.chunksFor("uri", ConversionProfiles.XDJ_700).map { it.id }).containsExactly("ID3 ")
    }

    @Test
    fun `Ogg Opus profiles get the OpusTags chunk`() = runTest {
        assertThat(source.chunksFor("uri", ConversionProfiles.WATCH_OPUS).map { it.id }).containsExactly("OTAG")
    }

    @Test
    fun `WAV profiles get a LIST INFO chunk and an id3 chunk`() = runTest {
        val wav = OutputTargets.profileFor(OutputTarget.Lossless(AudioFormat.WAV))
        assertThat(source.chunksFor("uri", wav).map { it.id }).containsExactly("LIST", "id3 ").inOrder()
    }

    @Test
    fun `FLAC profiles get the VORBIS_COMMENT chunk`() = runTest {
        val flac = OutputTargets.profileFor(OutputTarget.Lossless(AudioFormat.FLAC, bitDepth = 24))
        assertThat(source.chunksFor("uri", flac).map { it.id }).containsExactly("FVCM")
    }

    @Test
    fun `M4A profiles get the ilst chunk and MP3 profiles an ID3 tag`() = runTest {
        assertThat(source.chunksFor("uri", OutputTargets.profileFor(OutputTarget.Aac())).map { it.id }).containsExactly("ilst")
        assertThat(source.chunksFor("uri", OutputTargets.profileFor(OutputTarget.Mp3())).map { it.id }).containsExactly("ID3 ")
    }

    @Test
    fun `every output format gets its tags`() = runTest {
        val profiles = io.sonora.core.domain.model.AudioFormat.entries.map { OutputTargets.profileFor(OutputTargets.defaultFor(it)) } +
            ConversionProfiles.XDJ_700 + ConversionProfiles.WATCH_OPUS
        for (profile in profiles) {
            assertThat(source.chunksFor("uri", profile)).isNotEmpty()
        }
    }

    @Test
    fun `the ID3 source writes for AIFF, WAV and MP3 only`() = runTest {
        val id3 = Id3MetadataChunkSource(reader)
        assertThat(id3.chunksFor("uri", OutputTargets.profileFor(OutputTarget.Lossless(AudioFormat.FLAC)))).isEmpty()
        assertThat(id3.chunksFor("uri", OutputTargets.profileFor(OutputTarget.Aac()))).isEmpty()
        assertThat(id3.chunksFor("uri", ConversionProfiles.WATCH_OPUS)).isEmpty()
        assertThat(id3.chunksFor("uri", OutputTargets.profileFor(OutputTarget.Lossless(AudioFormat.AIFF))).map { it.id })
            .containsExactly("ID3 ")
        assertThat(id3.chunksFor("uri", OutputTargets.profileFor(OutputTarget.Mp3())).map { it.id }).containsExactly("ID3 ")
        val info = id3.chunksFor("uri", OutputTargets.profileFor(OutputTarget.Lossless(AudioFormat.WAV))).first()
        assertThat(String(info.data, 0, 4, Charsets.US_ASCII)).isEqualTo("INFO")
    }
}
