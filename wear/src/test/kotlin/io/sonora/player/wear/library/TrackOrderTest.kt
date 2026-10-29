package io.sonora.player.wear.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrackOrderTest {

    private fun track(id: String, title: String = id, artist: String? = null, album: String? = null, number: Int? = null) =
        WatchTrack(id, "file:///$id", title, artist, album, number, null)

    private fun ids(tracks: List<WatchTrack>) = TrackOrder.sorted(tracks).map { it.id }

    @Test
    fun `songs are ordered by artist ignoring case`() {
        assertThat(ids(listOf(track("1", artist = "beta"), track("2", artist = "Alpha"), track("3", artist = "Gamma"))))
            .containsExactly("2", "1", "3").inOrder()
    }

    @Test
    fun `songs with no artist come last`() {
        assertThat(ids(listOf(track("1"), track("2", artist = "Zed")))).containsExactly("2", "1").inOrder()
    }

    @Test
    fun `an artist's songs are ordered by album, then track number`() {
        val tracks = listOf(
            track("b2", artist = "A", album = "B", number = 2),
            track("a2", artist = "A", album = "A", number = 2),
            track("a1", artist = "A", album = "A", number = 1),
            track("b1", artist = "A", album = "B", number = 1),
        )
        assertThat(ids(tracks)).containsExactly("a1", "a2", "b1", "b2").inOrder()
    }

    @Test
    fun `an album's songs without a track number follow the numbered ones, by title`() {
        val tracks = listOf(
            track("z", title = "Zebra", artist = "A", album = "X"),
            track("n", title = "Numbered", artist = "A", album = "X", number = 9),
            track("a", title = "apple", artist = "A", album = "X"),
        )
        assertThat(ids(tracks)).containsExactly("n", "a", "z").inOrder()
    }

    @Test
    fun `equal songs keep a stable order by id`() {
        assertThat(ids(listOf(track("b", title = "Same"), track("a", title = "Same")))).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `sorting does not change the list it is given`() {
        val tracks = listOf(track("2", artist = "B"), track("1", artist = "A"))
        TrackOrder.sorted(tracks)
        assertThat(tracks.map { it.id }).containsExactly("2", "1").inOrder()
    }
}
