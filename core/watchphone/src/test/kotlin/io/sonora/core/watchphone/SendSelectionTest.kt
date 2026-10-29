package io.sonora.core.watchphone

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.conversion.ConversionProfiles
import io.sonora.core.domain.model.Playlist
import io.sonora.core.domain.model.PlaylistEntry
import io.sonora.core.domain.model.PlaylistWithEntries
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.playlist.PlaylistResolution
import io.sonora.core.watchsync.TrackIds
import org.junit.Test

class SendSelectionTest {

    private fun track(
        id: Long,
        title: String = "Song $id",
        number: Int? = null,
        disc: Int? = null,
        sizeBytes: Long = 5_000_000,
    ) = Track(
        id = id,
        uri = "content://media/$id",
        title = title,
        artist = "Artist",
        album = "Album",
        albumArtist = "Album Artist",
        durationMs = 215_000,
        trackNumber = number,
        discNumber = disc,
        year = 2020,
        albumId = 7,
        artworkUri = null,
        mimeType = "audio/flac",
        sizeBytes = sizeBytes,
    )

    @Test
    fun `the watch profile is the one W1 registered`() {
        assertThat(SendSelection.PROFILE).isSameInstanceAs(ConversionProfiles.WATCH_OPUS)
    }

    @Test
    fun `a track becomes a candidate with its tags, source and a stable id`() {
        val candidate = SendSelection.candidateOf(track(42, number = 3, disc = 2, sizeBytes = 123))

        assertThat(candidate.id).isEqualTo(TrackIds.of("content://media/42", ConversionProfiles.WATCH_OPUS.id))
        assertThat(candidate.sourceUri).isEqualTo("content://media/42")
        assertThat(candidate.title).isEqualTo("Song 42")
        assertThat(candidate.artist).isEqualTo("Artist")
        assertThat(candidate.albumArtist).isEqualTo("Album Artist")
        assertThat(candidate.album).isEqualTo("Album")
        assertThat(candidate.trackNumber).isEqualTo(3)
        assertThat(candidate.discNumber).isEqualTo(2)
        assertThat(candidate.durationMs).isEqualTo(215_000)
        assertThat(candidate.sourceVersion).isEqualTo(123)
    }

    @Test
    fun `an album is sent in disc and track order, songs without a number after their disc's numbered ones by title`() {
        val tracks = listOf(
            track(1, title = "Zed", number = null),
            track(2, number = 2, disc = 1),
            track(3, number = 1, disc = 2),
            track(4, number = 1, disc = 1),
            track(5, title = "Alpha", number = null),
        )

        val ids = SendSelection.forAlbum(tracks).map { it.sourceUri.substringAfterLast('/').toLong() }

        assertThat(ids).containsExactly(4L, 2L, 5L, 1L, 3L).inOrder()
    }

    @Test
    fun `a playlist is sent in playlist order and leaves out songs that are gone`() {
        val a = track(1)
        val b = track(2)
        val entries = listOf(2L, 9L, 1L, 2L).mapIndexed { index, id ->
            PlaylistEntry(index.toLong() + 1, "content://media/$id", "Song $id", "Artist")
        }
        val resolved = PlaylistResolution.resolve(
            PlaylistWithEntries(Playlist(1, "Run"), entries),
            libraryByUri = listOf(a, b).associateBy { it.uri },
        )

        val sent = SendSelection.forPlaylist(resolved)

        assertThat(sent.map { it.sourceUri }).containsExactly("content://media/2", "content://media/1", "content://media/2").inOrder()
    }
}
