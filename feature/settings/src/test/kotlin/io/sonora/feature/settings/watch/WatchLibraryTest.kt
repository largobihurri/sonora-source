package io.sonora.feature.settings.watch

import com.google.common.truth.Truth.assertThat
import io.sonora.core.watchsync.ManifestTrack
import io.sonora.core.watchsync.sender.WatchState
import org.junit.Test

class WatchLibraryTest {

    private fun song(
        id: String,
        title: String = "Song $id",
        album: String? = "Album",
        artist: String? = "Artist",
        albumArtist: String? = null,
        track: Int? = null,
        disc: Int? = null,
        size: Long = 1_000,
    ) = ManifestTrack(
        id = id, title = title, artist = artist, albumArtist = albumArtist, album = album, trackNumber = track, discNumber = disc,
        durationMs = 180_000, sizeBytes = size, sha256 = "0".repeat(64),
    )

    @Test
    fun `songs of one album share a group, ordered by disc and track`() {
        val groups = WatchLibrary.groups(
            listOf(
                song("a3", track = 1, disc = 2),
                song("a2", track = 2, disc = 1),
                song("a1", track = 1, disc = 1),
            ),
        )
        assertThat(groups).hasSize(1)
        assertThat(groups.single().songs.map { it.id }).containsExactly("a1", "a2", "a3").inOrder()
    }

    @Test
    fun `songs without a number follow the numbered ones, by title`() {
        val group = WatchLibrary.groups(
            listOf(song("b", title = "banana"), song("n2", track = 2), song("a", title = "Apple"), song("n1", track = 1)),
        ).single()
        assertThat(group.songs.map { it.id }).containsExactly("n1", "n2", "a", "b").inOrder()
    }

    @Test
    fun `albums are listed alphabetically, ignoring case`() {
        val groups = WatchLibrary.groups(
            listOf(song("1", album = "zebra"), song("2", album = "Apple"), song("3", album = "mango")),
        )
        assertThat(groups.map { it.album }).containsExactly("Apple", "mango", "zebra").inOrder()
    }

    @Test
    fun `the same album name by two artists makes two groups`() {
        val groups = WatchLibrary.groups(
            listOf(
                song("1", album = "Greatest Hits", albumArtist = "Queen", artist = "Queen"),
                song("2", album = "Greatest Hits", albumArtist = "ABBA", artist = "ABBA"),
            ),
        )
        assertThat(groups.map { it.artist }).containsExactly("ABBA", "Queen").inOrder()
    }

    @Test
    fun `an album tagged with different case or spacing is one group`() {
        val groups = WatchLibrary.groups(
            listOf(song("1", album = "Bandido", albumArtist = "Myke Towers"), song("2", album = " bandido ", albumArtist = "myke towers")),
        )
        assertThat(groups).hasSize(1)
        assertThat(groups.single().album).isEqualTo("Bandido")
    }

    @Test
    fun `without an album artist, songs of one album with different artists are one various-artists group`() {
        val group = WatchLibrary.groups(
            listOf(song("1", album = "Mix", artist = "One"), song("2", album = "Mix", artist = "Two")),
        ).single()
        assertThat(group.variousArtists).isTrue()
        assertThat(group.artist).isNull()
    }

    @Test
    fun `an album whose songs share an artist shows that artist`() {
        val group = WatchLibrary.groups(
            listOf(song("1", album = "Mix", artist = "One"), song("2", album = "Mix", artist = "One")),
        ).single()
        assertThat(group.variousArtists).isFalse()
        assertThat(group.artist).isEqualTo("One")
    }

    @Test
    fun `songs without an album gather in one last group`() {
        val groups = WatchLibrary.groups(
            listOf(song("1", album = null, title = "Loose B"), song("2", album = "Zed"), song("3", album = "  ", title = "Loose A")),
        )
        assertThat(groups.map { it.album }).containsExactly("Zed", null).inOrder()
        assertThat(groups.last().songs.map { it.title }).containsExactly("Loose A", "Loose B").inOrder()
    }

    @Test
    fun `a group adds up its size and lists its ids`() {
        val group = WatchLibrary.groups(listOf(song("1", size = 400), song("2", size = 600))).single()
        assertThat(group.sizeBytes).isEqualTo(1_000)
        assertThat(group.songIds).containsExactly("1", "2")
    }

    @Test
    fun `an empty library has no groups`() {
        assertThat(WatchLibrary.groups(emptyList())).isEmpty()
    }

    // The space figures

    @Test
    fun `space reports used, free and what the songs take`() {
        val state = WatchState(
            freeBytes = 9_000, totalBytes = 23_000, reserveBytes = 1_000,
            tracks = listOf(song("1", size = 300), song("2", size = 200)),
        )
        val space = WatchSpace.of(state)
        assertThat(space.totalBytes).isEqualTo(23_000)
        assertThat(space.freeBytes).isEqualTo(9_000)
        assertThat(space.usedBytes).isEqualTo(14_000)
        assertThat(space.usableBytes).isEqualTo(8_000)
        assertThat(space.songsBytes).isEqualTo(500)
        assertThat(space.songCount).isEqualTo(2)
        assertThat(space.usedFraction).isWithin(1e-6f).of(14f / 23f)
    }

    @Test
    fun `space never goes negative when the watch reports more free than total`() {
        val space = WatchSpace.of(WatchState(freeBytes = 5_000, totalBytes = 4_000, reserveBytes = 0, tracks = emptyList()))
        assertThat(space.usedBytes).isEqualTo(0)
        assertThat(space.usedFraction).isEqualTo(0f)
    }

    @Test
    fun `space with an unknown total shows an empty bar`() {
        val space = WatchSpace.of(WatchState(freeBytes = 0, totalBytes = 0, reserveBytes = 0, tracks = emptyList()))
        assertThat(space.usedFraction).isEqualTo(0f)
    }

    @Test
    fun `usable space is zero when free space is within the reserve`() {
        val space = WatchSpace.of(WatchState(freeBytes = 500, totalBytes = 10_000, reserveBytes = 1_000, tracks = emptyList()))
        assertThat(space.usableBytes).isEqualTo(0)
    }
}
