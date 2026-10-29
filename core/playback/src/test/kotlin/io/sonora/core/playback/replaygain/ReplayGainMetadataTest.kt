package io.sonora.core.playback.replaygain

import androidx.annotation.OptIn
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.playback.ReplayGainTags
import org.junit.Test

/** ReplayGain tags as Media3's extractors hand them over with a stream's format. */
@OptIn(UnstableApi::class)
class ReplayGainMetadataTest {

    @Test
    fun `Vorbis comments from FLAC, Ogg and Opus are read`() {
        val metadata = Metadata(
            VorbisComment("TITLE", "Song"),
            VorbisComment("REPLAYGAIN_TRACK_GAIN", "-6.20 dB"),
            VorbisComment("REPLAYGAIN_TRACK_PEAK", "0.977"),
            VorbisComment("REPLAYGAIN_ALBUM_GAIN", "-7.10 dB"),
            VorbisComment("REPLAYGAIN_ALBUM_PEAK", "0.999"),
        )

        assertThat(ReplayGainMetadata.tags(metadata)).isEqualTo(ReplayGainTags(-6.2f, 0.977f, -7.1f, 0.999f))
    }

    @Test
    fun `ID3 TXXX frames from MP3 are read by description`() {
        val metadata = Metadata(
            TextInformationFrame("TIT2", null, listOf("Song")),
            TextInformationFrame("TXXX", "replaygain_track_gain", listOf("+1.50 dB")),
            TextInformationFrame("TXXX", "replaygain_track_peak", listOf("0.5")),
        )

        assertThat(ReplayGainMetadata.tags(metadata)).isEqualTo(ReplayGainTags(trackGainDb = 1.5f, trackPeak = 0.5f))
    }

    @Test
    fun `MP4 freeform atoms are read by name`() {
        val metadata = Metadata(
            InternalFrame("com.apple.iTunes", "replaygain_album_gain", "-3.00 dB"),
            InternalFrame("com.apple.iTunes", "iTunSMPB", "00000000 00000840"),
        )

        assertThat(ReplayGainMetadata.tags(metadata)).isEqualTo(ReplayGainTags(albumGainDb = -3f))
    }

    @Test
    fun `no metadata or garbage values read as untagged`() {
        assertThat(ReplayGainMetadata.tags(null)).isEqualTo(ReplayGainTags.NONE)
        assertThat(ReplayGainMetadata.tags(Metadata(VorbisComment("REPLAYGAIN_TRACK_GAIN", "loud")))).isEqualTo(ReplayGainTags.NONE)
    }
}
