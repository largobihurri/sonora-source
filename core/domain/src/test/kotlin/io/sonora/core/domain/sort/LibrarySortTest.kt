package io.sonora.core.domain.sort

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.conversion.batch.HistorySection
import io.sonora.core.domain.library.AlbumGrouping
import io.sonora.core.domain.library.ConvertedBatchTracks
import io.sonora.core.domain.library.ConvertedMusicSection
import io.sonora.core.domain.model.Album
import io.sonora.core.domain.model.Artist
import io.sonora.core.domain.model.FolderContents
import io.sonora.core.domain.model.FolderEntry
import io.sonora.core.domain.model.FolderRef
import io.sonora.core.domain.model.PlaylistEntry
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.playlist.PlaylistItem
import io.sonora.core.domain.sort.SortDirection.ASCENDING
import io.sonora.core.domain.sort.SortDirection.DESCENDING
import java.util.Locale
import org.junit.Test

class LibrarySortTest {

    private val spanish = Locale.forLanguageTag("es")
    private val english = Locale.ENGLISH

    private fun track(
        id: Long,
        title: String,
        artist: String = "Artist",
        album: String = "Album",
        genre: String? = null,
        year: Int? = null,
        added: Long = 0,
        durationMs: Long = 1_000,
        trackNumber: Int? = null,
        discNumber: Int? = null,
    ) = Track(
        id = id, uri = "content://x/$id", title = title, artist = artist, album = album, albumArtist = null,
        durationMs = durationMs, trackNumber = trackNumber, discNumber = discNumber, year = year, albumId = 0,
        artworkUri = null, mimeType = "audio/flac", sizeBytes = 1, dateAddedSec = added, genre = genre,
    )

    private fun List<Track>.ids() = map { it.id }

    private fun songs(tracks: List<Track>, key: SortKey, direction: SortDirection = ASCENDING, locale: Locale = english) =
        LibrarySort.tracks(tracks, SortOrder(key, direction), locale).ids()

    // Text keys

    @Test
    fun `titles collate ignoring case and accents in both directions`() {
        val tracks = listOf(track(1, "éclair"), track(2, "Zebra"), track(3, "apple"), track(4, "Banana"))

        assertThat(songs(tracks, SortKey.TITLE)).containsExactly(3L, 4L, 1L, 2L).inOrder()
        assertThat(songs(tracks, SortKey.TITLE, DESCENDING)).containsExactly(2L, 1L, 4L, 3L).inOrder()
    }

    @Test
    fun `titles use the collation of the given locale`() {
        // Castellano sorts ñ as its own letter after n; English folds it into n.
        val tracks = listOf(track(1, "ñu"), track(2, "nube"), track(3, "oso"))

        assertThat(songs(tracks, SortKey.TITLE, locale = spanish)).containsExactly(2L, 1L, 3L).inOrder()
        assertThat(songs(tracks, SortKey.TITLE, locale = english)).containsExactly(1L, 2L, 3L).inOrder()
    }

    @Test
    fun `unknown artists and albums sort last in both directions`() {
        val tracks = listOf(
            track(1, "a", artist = AlbumGrouping.UNKNOWN_ARTIST),
            track(2, "b", artist = "Björk"),
            track(3, "c", artist = " "),
            track(4, "d", artist = "Abba"),
        )

        assertThat(songs(tracks, SortKey.ARTIST)).containsExactly(4L, 2L, 1L, 3L).inOrder()
        assertThat(songs(tracks, SortKey.ARTIST, DESCENDING)).containsExactly(2L, 4L, 1L, 3L).inOrder()

        val albums = listOf(track(1, "a", album = AlbumGrouping.UNKNOWN_ALBUM), track(2, "b", album = "Zz"))
        assertThat(songs(albums, SortKey.ALBUM)).containsExactly(2L, 1L).inOrder()
        assertThat(songs(albums, SortKey.ALBUM, DESCENDING)).containsExactly(2L, 1L).inOrder()
    }

    @Test
    fun `an artist's songs follow album, disc and track number, ascending whatever the direction`() {
        val tracks = listOf(
            track(1, "Late", artist = "Queen", album = "B", trackNumber = 1),
            track(2, "Two", artist = "Queen", album = "A", trackNumber = 2, discNumber = 1),
            track(3, "One", artist = "Queen", album = "A", trackNumber = 1, discNumber = 1),
            track(4, "Disc two", artist = "Queen", album = "A", trackNumber = 1, discNumber = 2),
            track(5, "Bowie", artist = "Bowie"),
        )

        assertThat(songs(tracks, SortKey.ARTIST)).containsExactly(5L, 3L, 2L, 4L, 1L).inOrder()
        assertThat(songs(tracks, SortKey.ARTIST, DESCENDING)).containsExactly(3L, 2L, 4L, 1L, 5L).inOrder()
    }

    @Test
    fun `equal titles break ties by artist, then album, then id`() {
        val tracks = listOf(
            track(4, "Intro", artist = "B"),
            track(2, "Intro", artist = "A", album = "Y"),
            track(3, "Intro", artist = "A", album = "X"),
            track(1, "intro", artist = "A", album = "X"),
        )

        assertThat(songs(tracks, SortKey.TITLE)).containsExactly(1L, 3L, 2L, 4L).inOrder()
        assertThat(songs(tracks.reversed(), SortKey.TITLE)).containsExactly(1L, 3L, 2L, 4L).inOrder()
    }

    @Test
    fun `genre sorts by collation with untagged songs last`() {
        val tracks = listOf(track(1, "a", genre = null), track(2, "b", genre = "rock"), track(3, "c", genre = "Électro"), track(4, "d", genre = ""))

        assertThat(songs(tracks, SortKey.GENRE)).containsExactly(3L, 2L, 1L, 4L).inOrder()
        assertThat(songs(tracks, SortKey.GENRE, DESCENDING)).containsExactly(2L, 3L, 1L, 4L).inOrder()
    }

    // Number keys

    @Test
    fun `release date sorts by year with unknown years last`() {
        val tracks = listOf(track(1, "a", year = 1999), track(2, "b"), track(3, "c", year = 2024), track(4, "d", year = 1975))

        assertThat(songs(tracks, SortKey.RELEASE_DATE)).containsExactly(4L, 1L, 3L, 2L).inOrder()
        assertThat(songs(tracks, SortKey.RELEASE_DATE, DESCENDING)).containsExactly(3L, 1L, 4L, 2L).inOrder()
    }

    @Test
    fun `date added sorts by the time the file entered the library, unknown last`() {
        val tracks = listOf(track(1, "a", added = 300), track(2, "b", added = 0), track(3, "c", added = 100))

        assertThat(songs(tracks, SortKey.DATE_ADDED)).containsExactly(3L, 1L, 2L).inOrder()
        assertThat(songs(tracks, SortKey.DATE_ADDED, DESCENDING)).containsExactly(1L, 3L, 2L).inOrder()
    }

    @Test
    fun `songs added together keep album order when sorted by date added`() {
        val tracks = listOf(
            track(1, "Second", album = "A", added = 100, trackNumber = 2),
            track(2, "First", album = "A", added = 100, trackNumber = 1),
            track(3, "Newest", album = "B", added = 200),
        )

        assertThat(songs(tracks, SortKey.DATE_ADDED, DESCENDING)).containsExactly(3L, 2L, 1L).inOrder()
    }

    @Test
    fun `duration sorts by length with unknown lengths last`() {
        val tracks = listOf(track(1, "a", durationMs = 200_000), track(2, "b", durationMs = 0), track(3, "c", durationMs = 90_000))

        assertThat(songs(tracks, SortKey.DURATION)).containsExactly(3L, 1L, 2L).inOrder()
        assertThat(songs(tracks, SortKey.DURATION, DESCENDING)).containsExactly(1L, 3L, 2L).inOrder()
    }

    // Albums and artists

    private fun album(id: Long, title: String, artist: String = "Artist", year: Int? = null, added: Long = 0) =
        Album(id = id, title = title, artist = artist, year = year, trackCount = 1, artworkUri = null, dateAddedSec = added)

    private fun albums(list: List<Album>, key: SortKey, direction: SortDirection = ASCENDING) =
        LibrarySort.albums(list, SortOrder(key, direction), english).map { it.id }

    @Test
    fun `albums sort by title, artist, release year and date added`() {
        val list = listOf(
            album(1, "Heroes", "David Bowie", year = 1977, added = 50),
            album(2, "a Night at the Opera", "Queen", year = 1975, added = 10),
            album(3, "Low", "David Bowie", added = 90),
            album(4, AlbumGrouping.UNKNOWN_ALBUM, AlbumGrouping.UNKNOWN_ARTIST, year = 2001),
        )

        assertThat(albums(list, SortKey.TITLE)).containsExactly(2L, 1L, 3L, 4L).inOrder()
        assertThat(albums(list, SortKey.TITLE, DESCENDING)).containsExactly(3L, 1L, 2L, 4L).inOrder()
        assertThat(albums(list, SortKey.ARTIST)).containsExactly(1L, 3L, 2L, 4L).inOrder()
        assertThat(albums(list, SortKey.ARTIST, DESCENDING)).containsExactly(2L, 1L, 3L, 4L).inOrder()
        assertThat(albums(list, SortKey.RELEASE_DATE, DESCENDING)).containsExactly(4L, 1L, 2L, 3L).inOrder()
        assertThat(albums(list, SortKey.DATE_ADDED, DESCENDING)).containsExactly(3L, 1L, 2L, 4L).inOrder()
    }

    @Test
    fun `artists sort by name and by date added`() {
        val list = listOf(
            Artist(1, "Queen", 1, 1, dateAddedSec = 10),
            Artist(2, AlbumGrouping.UNKNOWN_ARTIST, 1, 1, dateAddedSec = 99),
            Artist(3, "ABBA", 1, 1, dateAddedSec = 50),
            Artist(4, "Ólafur Arnalds", 1, 1, dateAddedSec = 0),
        )

        fun sorted(key: SortKey, direction: SortDirection) =
            LibrarySort.artists(list, SortOrder(key, direction), english).map { it.id }

        assertThat(sorted(SortKey.NAME, ASCENDING)).containsExactly(3L, 4L, 1L, 2L).inOrder()
        assertThat(sorted(SortKey.NAME, DESCENDING)).containsExactly(1L, 4L, 3L, 2L).inOrder()
        assertThat(sorted(SortKey.DATE_ADDED, DESCENDING)).containsExactly(2L, 3L, 1L, 4L).inOrder()
    }

    // Folders

    @Test
    fun `folder contents keep folders first and sort both by name`() {
        val root = FolderRef("tree", "Music")
        val contents = FolderContents(
            folder = root,
            subfolders = listOf(FolderEntry(root.child("rock"), 1), FolderEntry(root.child("Ambient"), 2)),
            tracks = listOf(track(1, "Zulu", durationMs = 10), track(2, "alpha", durationMs = 30)),
        )

        val byName = LibrarySort.folder(contents, SortOrder(SortKey.NAME, DESCENDING), english)
        assertThat(byName.subfolders.map { it.folder.name }).containsExactly("rock", "Ambient").inOrder()
        assertThat(byName.tracks.ids()).containsExactly(1L, 2L).inOrder()

        val byDuration = LibrarySort.folder(contents, SortOrder(SortKey.DURATION, DESCENDING), english)
        assertThat(byDuration.subfolders.map { it.folder.name }).containsExactly("Ambient", "rock").inOrder()
        assertThat(byDuration.tracks.ids()).containsExactly(2L, 1L).inOrder()
    }

    // Playlists

    private fun item(entryId: Long, track: Track?) =
        PlaylistItem(PlaylistEntry(entryId, track?.uri ?: "content://gone/$entryId", track?.title ?: "Gone", "Someone"), track)

    @Test
    fun `playlist order keeps the manual order`() {
        val items = listOf(item(1, track(1, "b")), item(2, null), item(3, track(3, "a")))

        val sorted = LibrarySort.playlist(items, SortOrder(SortKey.PLAYLIST_ORDER), english)

        assertThat(sorted).isEqualTo(items)
    }

    @Test
    fun `a sorted playlist puts unavailable songs last and keeps duplicate songs in playlist order`() {
        val song = track(9, "Middle")
        val items = listOf(item(1, null), item(2, track(1, "Zed")), item(3, song), item(4, track(2, "Ace")), item(5, song), item(6, null))

        fun sorted(direction: SortDirection) =
            LibrarySort.playlist(items, SortOrder(SortKey.TITLE, direction), english).map { it.entry.id }

        assertThat(sorted(ASCENDING)).containsExactly(4L, 3L, 5L, 2L, 1L, 6L).inOrder()
        assertThat(sorted(DESCENDING)).containsExactly(2L, 3L, 5L, 4L, 1L, 6L).inOrder()
    }

    // Converted

    private fun section(section: HistorySection, vararg batches: ConvertedBatchTracks) = ConvertedMusicSection(section, batches.toList())

    private val converted = listOf(
        section(
            HistorySection.TODAY,
            ConvertedBatchTracks(batchId = 3, createdAtMs = 300, tracks = listOf(track(-31, "c1"), track(-32, "c2"))),
            ConvertedBatchTracks(batchId = 2, createdAtMs = 200, tracks = listOf(track(-21, "b1"))),
        ),
        section(HistorySection.OLDER, ConvertedBatchTracks(batchId = 1, createdAtMs = 100, tracks = listOf(track(-11, "a1", durationMs = 0)))),
    )

    @Test
    fun `converted newest first keeps the date sections`() {
        val groups = LibrarySort.converted(converted, SortOrder(SortKey.DATE_CONVERTED, DESCENDING), english)

        assertThat(groups.map { it.section to it.tracks.ids() }).containsExactly(
            HistorySection.TODAY to listOf(-31L, -32L, -21L),
            HistorySection.OLDER to listOf(-11L),
        ).inOrder()
    }

    @Test
    fun `converted oldest first reverses sections and batches but keeps each batch's own order`() {
        val groups = LibrarySort.converted(converted, SortOrder(SortKey.DATE_CONVERTED, ASCENDING), english)

        assertThat(groups.map { it.section to it.tracks.ids() }).containsExactly(
            HistorySection.OLDER to listOf(-11L),
            HistorySection.TODAY to listOf(-21L, -31L, -32L),
        ).inOrder()
    }

    @Test
    fun `converted by any other key is one list without sections`() {
        val groups = LibrarySort.converted(converted, SortOrder(SortKey.DURATION, DESCENDING), english)

        assertThat(groups).hasSize(1)
        assertThat(groups.single().section).isNull()
        assertThat(groups.single().tracks.last().id).isEqualTo(-11L)
        assertThat(LibrarySort.converted(emptyList(), SortOrder(SortKey.TITLE), english)).isEmpty()
    }
}
