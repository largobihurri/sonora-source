package io.sonora.core.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LibraryQueryTest {

    private fun track(
        id: Long,
        title: String,
        artist: String = "Artist",
        album: String = "Album",
        albumId: Long = 1,
        trackNumber: Int? = null,
        added: Long = 0,
        art: String? = null,
    ) = Track(
        id = id, uri = "content://x/$id", title = title, artist = artist, album = album,
        albumArtist = null, durationMs = 1000, trackNumber = trackNumber, discNumber = null,
        year = null, albumId = albumId, artworkUri = art, mimeType = "audio/mpeg",
        sizeBytes = 1, dateAddedSec = added,
    )

    private val library = listOf(
        track(1, "Bohemian Rhapsody", "Queen", "A Night at the Opera", albumId = 10, trackNumber = 11),
        track(2, "Love of My Life", "Queen", "A Night at the Opera", albumId = 10, trackNumber = 9),
        track(3, "Heroes", "David Bowie", "Heroes", albumId = 20, added = 50),
        track(4, "Opera Singer", "Cake", "Comfort Eagle", albumId = 30, added = 99),
    )

    @Test
    fun `search matches title artist and album case-insensitively`() {
        assertThat(LibraryQuery.search(library, "opera").map { it.id }).containsExactly(1L, 2L, 4L)
        assertThat(LibraryQuery.search(library, "  BOWIE ").map { it.id }).containsExactly(3L)
    }

    @Test
    fun `blank search returns nothing`() {
        assertThat(LibraryQuery.search(library, "   ")).isEmpty()
    }

    @Test
    fun `album order uses track numbers`() {
        val ordered = LibraryQuery.albumOrder(library.filter { it.albumId == 10L })
        assertThat(ordered.map { it.id }).containsExactly(2L, 1L).inOrder()
    }

    @Test
    fun `sort by artist then album`() {
        val sorted = LibraryQuery.sort(library, TrackSort.ARTIST)
        assertThat(sorted.map { it.artist }).containsExactly("Cake", "David Bowie", "Queen", "Queen").inOrder()
        assertThat(sorted.takeLast(2).map { it.id }).containsExactly(2L, 1L).inOrder()
    }

    @Test
    fun `albums grouped and recently added ordered by newest track`() {
        val albums = LibraryQuery.albums(library)
        assertThat(albums.first { it.id == 10L }.trackCount).isEqualTo(2)
        assertThat(LibraryQuery.recentlyAdded(albums, 2).map { it.id }).containsExactly(30L, 20L).inOrder()
    }

    @Test
    fun `repeat mode cycles off all one`() {
        assertThat(RepeatMode.OFF.next()).isEqualTo(RepeatMode.ALL)
        assertThat(RepeatMode.ALL.next()).isEqualTo(RepeatMode.ONE)
        assertThat(RepeatMode.ONE.next()).isEqualTo(RepeatMode.OFF)
    }

    @Test
    fun `an artist's date added is its newest track's`() {
        val artists = LibraryQuery.artists(library).associateBy { it.name }

        assertThat(artists.getValue("Queen").dateAddedSec).isEqualTo(0L)
        assertThat(artists.getValue("Cake").dateAddedSec).isEqualTo(99L)
    }

    @Test
    fun `an artist's artwork is one cover per album, most played album first, at most three`() {
        val tracks = listOf(
            track(1, "a1", "Band", "A", albumId = 1, added = 10, art = "art:a"),
            track(2, "a2", "Band", "A", albumId = 1, added = 11, art = "art:a"),
            track(3, "b1", "Band", "B", albumId = 2, added = 20, art = "art:b"),
            track(4, "c1", "Band", "C", albumId = 3, added = 30, art = "art:c"),
            track(5, "d1", "Band", "D", albumId = 4, added = 40, art = "art:d"),
            track(6, "e1", "Band", "E", albumId = 5, added = 50),
        )
        val plays = mapOf("content://x/1" to 2, "content://x/2" to 3, "content://x/3" to 4)

        val band = LibraryQuery.artists(tracks, plays).single()

        // A: 5 plays, B: 4; C and D unplayed, newest (D) first; E has no artwork.
        assertThat(band.artworkUris).containsExactly("art:a", "art:b", "art:d").inOrder()
    }

    @Test
    fun `without plays an artist's covers are newest album first`() {
        val tracks = listOf(
            track(1, "a1", "Band", "A", albumId = 1, added = 10, art = "art:a"),
            track(2, "b1", "Band", "B", albumId = 2, added = 20, art = "art:b"),
            track(3, "c1", "Band", "C", albumId = 3, added = 20, art = "art:c"),
        )

        // B and C tie on date: the lower album id first.
        assertThat(LibraryQuery.artists(tracks).single().artworkUris).containsExactly("art:b", "art:c", "art:a").inOrder()
    }

    @Test
    fun `album plays count every track of the album, also other artists' tracks`() {
        val tracks = listOf(
            track(1, "a1", "Band", "Mix", albumId = 1, added = 10, art = "art:mix"),
            track(2, "a2", "Guest", "Mix", albumId = 1, added = 10, art = "art:mix"),
            track(3, "b1", "Band", "Own", albumId = 2, added = 20, art = "art:own"),
        )
        val plays = mapOf("content://x/2" to 5, "content://x/3" to 1)

        val artists = LibraryQuery.artists(tracks, plays).associateBy { it.name }

        assertThat(artists.getValue("Band").artworkUris).containsExactly("art:mix", "art:own").inOrder()
        assertThat(artists.getValue("Guest").artworkUris).containsExactly("art:mix")
    }

    @Test
    fun `albums sharing one image show it once, and no artwork gives none`() {
        val tracks = listOf(
            track(1, "a1", "Band", "A", albumId = 1, added = 10, art = "art:folder"),
            track(2, "b1", "Band", "B", albumId = 2, added = 20, art = "art:folder"),
            track(3, "x1", "Plain", "X", albumId = 3),
        )

        val artists = LibraryQuery.artists(tracks).associateBy { it.name }

        assertThat(artists.getValue("Band").artworkUris).containsExactly("art:folder")
        assertThat(artists.getValue("Plain").artworkUris).isEmpty()
    }
}
