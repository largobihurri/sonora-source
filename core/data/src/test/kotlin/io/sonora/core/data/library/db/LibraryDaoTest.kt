package io.sonora.core.data.library.db

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.sonora.core.data.library.RoomLibraryRepository
import io.sonora.core.domain.library.AlbumGrouping
import io.sonora.core.domain.library.FileIdentity
import io.sonora.core.domain.library.ScannedTrack
import io.sonora.core.domain.library.TrackTags
import io.sonora.core.domain.model.TrackSort
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LibraryDaoTest {

    private lateinit var db: LibraryDatabase
    private lateinit var dao: LibraryDao

    private val folder = LibraryFolderEntity("content://tree/music", "Music", addedAtMs = 1)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.libraryDao()
    }

    @After
    fun tearDown() = db.close()

    private fun scanned(
        name: String,
        folderPath: String,
        artist: String,
        album: String,
        disc: Int? = null,
        number: Int? = null,
        embedded: String? = null,
    ) = ScannedTrack(
        documentUri = "content://tree/music/document/$folderPath/$name",
        folderPath = folderPath,
        fileName = name,
        tags = TrackTags(title = name.substringBeforeLast('.'), artist = artist, album = album, discNumber = disc, trackNumber = number, year = 2000 + (number ?: 0)),
        embeddedArtworkUri = embedded,
    )

    /** Stores [tracks] the way the scanner does after regrouping, with `dateAddedSec` = position. */
    private suspend fun index(vararg tracks: ScannedTrack, treeUri: String = folder.treeUri) {
        val resolution = AlbumGrouping.resolve(tracks.toList())
        dao.upsertTracks(
            tracks.mapIndexed { i, t ->
                TrackEntity.of(t, treeUri, "audio/flac", FileIdentity(t.documentUri, 10, 20), i.toLong(), resolution.getValue(t.documentUri))
            },
        )
    }

    private val d2t1 = scanned("d2t1.flac", "Music/Record/CD2", "Band", "Record", disc = 2, number = 1)
    private val d1t2 = scanned("d1t2.flac", "Music/Record/CD1", "Band", "Record", disc = 1, number = 2, embedded = "file:///a.jpg")
    private val d1t1 = scanned("d1t1.flac", "Music/Record/CD1", "Band", "Record", disc = 1, number = 1)
    private val hitA = scanned("a.mp3", "Music/Hits", "Band", "Hits", number = 1)
    private val hitB = scanned("b.mp3", "Music/Hits", "Other", "Hits", number = 2)

    @Test
    fun `albums aggregate grouped tracks`() = runTest {
        dao.insertFolder(folder)
        index(d2t1, d1t2, d1t1, hitA, hitB)

        val albums = dao.albums().first().map { it.toDomain() }

        assertThat(albums.map { it.title }).containsExactly("Hits", "Record").inOrder()
        val hits = albums[0]
        assertThat(hits.artist).isEqualTo(AlbumGrouping.VARIOUS_ARTISTS)
        assertThat(hits.isCompilation).isTrue()
        assertThat(hits.trackCount).isEqualTo(2)
        val record = albums[1]
        assertThat(record.trackCount).isEqualTo(3)
        assertThat(record.year).isEqualTo(2002)
        assertThat(record.artworkUri).isEqualTo("file:///a.jpg")
        assertThat(record.dateAddedSec).isEqualTo(2)
    }

    @Test
    fun `album tracks are ordered by disc then track`() = runTest {
        index(d2t1, d1t2, d1t1)
        val albumId = dao.allTracks().first().albumId

        val titles = dao.tracksForAlbum(albumId).first().map { it.title }

        assertThat(titles).containsExactly("d1t1", "d1t2", "d2t1").inOrder()
    }

    @Test
    fun `artist albums include compilations the artist appears on`() = runTest {
        index(d2t1, d1t2, d1t1, hitA, hitB)
        val band = dao.allTracks().first { it.artist == "Band" }.artistId
        val other = dao.allTracks().first { it.artist == "Other" }.artistId

        assertThat(dao.albumsForArtist(band).first().map { it.album }).containsExactly("Hits", "Record").inOrder()
        assertThat(dao.albumsForArtist(other).first().map { it.album }).containsExactly("Hits")
        // Album track counts stay whole even when reached through one artist.
        assertThat(dao.albumsForArtist(other).first().single().trackCount).isEqualTo(2)
    }

    @Test
    fun `artist tracks are the artist's own songs by title`() = runTest {
        index(d2t1, d1t2, d1t1, hitA, hitB)
        val band = dao.allTracks().first { it.artist == "Band" }.artistId
        val other = dao.allTracks().first { it.artist == "Other" }.artistId

        assertThat(dao.tracksForArtist(band).first().map { it.title }).containsExactly("a", "d1t1", "d1t2", "d2t1").inOrder()
        assertThat(dao.tracksForArtist(other).first().map { it.title }).containsExactly("b")
    }

    @Test
    fun `artists count their albums and tracks`() = runTest {
        index(d2t1, d1t2, d1t1, hitA, hitB)

        val artists = dao.artists().first().map { it.toDomain() }

        assertThat(artists.map { Triple(it.name, it.albumCount, it.trackCount) })
            .containsExactly(Triple("Band", 2, 4), Triple("Other", 1, 1)).inOrder()
    }

    @Test
    fun `an artist's date added is its newest track's`() = runTest {
        index(d2t1, d1t2, d1t1, hitA, hitB)

        val added = dao.artists().first().associate { it.artist to it.toDomain().dateAddedSec }

        assertThat(added).containsExactly("Band", 3L, "Other", 4L)
    }

    @Test
    fun `recently added albums are newest first and limited`() = runTest {
        index(hitA, hitB, d1t1)

        assertThat(dao.recentlyAddedAlbums(1).first().map { it.album }).containsExactly("Record")
    }

    @Test
    fun `listing changes remove files and update locations atomically`() = runTest {
        index(d1t1, d1t2)

        dao.applyListing(
            removed = listOf(d1t1.documentUri),
            moved = listOf(LocationUpdate(d1t2.documentUri, "Music/Moved", "content://cover")),
        )

        val rows = dao.indexedFiles(folder.treeUri)
        assertThat(rows.map { it.documentUri }).containsExactly(d1t2.documentUri)
        assertThat(rows.single().folderPath).isEqualTo("Music/Moved")
        assertThat(rows.single().folderImageUri).isEqualTo("content://cover")
    }

    @Test
    fun `adding a folder again keeps the first entry`() = runTest {
        dao.insertFolder(folder)
        dao.insertFolder(folder.copy(displayName = "Renamed", addedAtMs = 99))

        assertThat(dao.folderList()).containsExactly(folder)
    }

    @Test
    fun `removing a folder drops its tracks only, and orphans are cleaned up`() = runTest {
        val other = LibraryFolderEntity("content://tree/other", "Other", addedAtMs = 2)
        dao.insertFolder(folder)
        dao.insertFolder(other)
        index(d1t1)
        index(hitA, treeUri = other.treeUri)
        index(hitB, treeUri = "content://tree/gone")

        dao.removeFolder(folder.treeUri)
        dao.deleteOrphanTracks()

        assertThat(dao.folderList()).containsExactly(other)
        assertThat(dao.allTracks().map { it.documentUri }).containsExactly(hitA.documentUri)
    }

    @Test
    fun `repository maps rows to domain and searches across Unicode case`() = runTest {
        index(scanned("Ébano.flac", "Music/X", "Ñandú", "Árbol"), d1t1)
        val repository = RoomLibraryRepository(dao)

        assertThat(repository.search("ñan").first().map { it.title }).containsExactly("Ébano")
        assertThat(repository.search("ÁRBOL").first()).hasSize(1)
        assertThat(repository.search(" ").first()).isEmpty()
        val sorted = repository.tracks(TrackSort.ARTIST).first()
        assertThat(sorted.map { it.artist }).containsExactly("Band", "Ñandú").inOrder()
        assertThat(sorted.first().folderPath).isEqualTo("Music/Record/CD1")
    }
}
