package io.sonora.core.transcoder.metadata

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.conversion.MetadataTextField
import io.sonora.core.domain.conversion.TrackMetadata
import io.sonora.core.domain.conversion.UserTextField
import io.sonora.core.transcoder.ogg.VorbisComment
import org.junit.Test

class FlacTagCommentsTest {

    @Test
    fun `every text field, the numbers, the date, comment, lyrics and user text become comments`() {
        val metadata = TrackMetadata(
            text = MetadataTextField.entries.associateWith { it.name.lowercase() },
            date = "2026",
            trackNumber = 3, trackTotal = 12, discNumber = 1, discTotal = 2,
            comment = "Nice",
            lyrics = "La la",
            userText = listOf(UserTextField("REPLAYGAIN_TRACK_GAIN", "-7.10 dB"), UserTextField("bad=name", "x")),
        )

        val comments = FlacTagComments.from(metadata)

        assertThat(comments.map { it.value }).containsAtLeastElementsIn(MetadataTextField.entries.map { it.name.lowercase() })
        assertThat(comments).containsAtLeast(
            VorbisComment("TITLE", "title"),
            VorbisComment("ALBUMARTIST", "album_artist"),
            VorbisComment("INITIALKEY", "initial_key"),
            VorbisComment("TRACKNUMBER", "3"),
            VorbisComment("TRACKTOTAL", "12"),
            VorbisComment("DISCNUMBER", "1"),
            VorbisComment("DISCTOTAL", "2"),
            VorbisComment("DATE", "2026"),
            VorbisComment("COMMENT", "Nice"),
            VorbisComment("LYRICS", "La la"),
            VorbisComment("REPLAYGAIN_TRACK_GAIN", "-7.10 dB"),
        )
        // A description that cannot be a comment name is left out rather than failing the file.
        assertThat(comments.none { it.value == "x" }).isTrue()
        assertThat(comments.map { it.name }.distinct()).hasSize(comments.size)
    }

    @Test
    fun `no tags give no comments`() {
        assertThat(FlacTagComments.from(TrackMetadata())).isEmpty()
    }
}
