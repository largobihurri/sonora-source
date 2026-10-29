package io.sonora.core.domain.playlist

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.Playlist
import io.sonora.core.domain.model.PlaylistWithEntries
import io.sonora.core.domain.model.Track
import org.junit.Test

class PlaylistResolutionTest {

    private val playlist = Playlist(id = 1, name = "Mix")

    private fun resolve(entries: List<io.sonora.core.domain.model.PlaylistEntry>, library: List<Track>) =
        PlaylistResolution.resolve(PlaylistWithEntries(playlist, entries), library.associateBy { it.uri })

    @Test
    fun `entries resolve to the library track with their uri, keeping playlist order and duplicates`() {
        val library = listOf(track(10), track(20))

        val resolved = resolve(listOf(entry(1, 20), entry(2, 10), entry(3, 20)), library)

        assertThat(resolved.items.map { it.track?.id }).containsExactly(20L, 10L, 20L).inOrder()
        assertThat(resolved.items.map { it.entry.id }).containsExactly(1L, 2L, 3L).inOrder()
        assertThat(resolved.items.all { it.isAvailable }).isTrue()
    }

    @Test
    fun `an entry whose file is not in the library is unavailable and left out of the playable tracks`() {
        val resolved = resolve(listOf(entry(1, 10), entry(2, 99), entry(3, 20)), listOf(track(10), track(20)))

        assertThat(resolved.items.map { it.isAvailable }).containsExactly(true, false, true).inOrder()
        assertThat(resolved.items[1].entry.title).isEqualTo("Song 99")
        assertThat(resolved.playableTracks.map { it.id }).containsExactly(10L, 20L).inOrder()
        assertThat(resolved.songCount).isEqualTo(3)
    }

    @Test
    fun `total duration counts the available songs`() {
        val library = listOf(track(10, durationMs = 61_000), track(20, durationMs = 120_000))

        val resolved = resolve(listOf(entry(1, 10), entry(2, 20), entry(3, 10), entry(4, 99)), library)

        assertThat(resolved.totalDurationMs).isEqualTo(242_000L)
    }

    @Test
    fun `four distinct album covers make a mosaic of the first four`() {
        val library = (1L..6L).map { track(it) } + track(7, album = 1)

        val resolved = resolve(listOf(7L, 1L, 2L, 99L, 3L, 4L, 5L).mapIndexed { i, t -> entry(i + 1L, t) }, library)

        assertThat(resolved.coverArtworkUris).containsExactly("art:1", "art:2", "art:3", "art:4").inOrder()
    }

    @Test
    fun `fewer than four distinct covers show the first cover alone`() {
        val library = listOf(track(1, artwork = null), track(2), track(3), track(4, album = 2))

        val resolved = resolve(listOf(entry(1, 1), entry(2, 2), entry(3, 3), entry(4, 4)), library)

        assertThat(resolved.coverArtworkUris).containsExactly("art:2")
    }

    @Test
    fun `a playlist without covers has none, so the placeholder shows`() {
        assertThat(resolve(emptyList(), emptyList()).coverArtworkUris).isEmpty()
        assertThat(resolve(listOf(entry(1, 99)), emptyList()).coverArtworkUris).isEmpty()
    }
}
