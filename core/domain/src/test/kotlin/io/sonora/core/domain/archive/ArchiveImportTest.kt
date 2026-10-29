package io.sonora.core.domain.archive

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.AddFolderResult
import io.sonora.core.domain.model.Album
import io.sonora.core.domain.model.Artist
import io.sonora.core.domain.model.FolderConflict
import io.sonora.core.domain.model.LibraryFolder
import io.sonora.core.domain.model.PlaylistEntry
import io.sonora.core.domain.model.PlaylistWithEntries
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.model.TrackSort
import io.sonora.core.domain.port.LibraryFolderRepository
import io.sonora.core.domain.port.LibraryRepository
import io.sonora.core.domain.port.PlaylistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Test

class ArchiveImportTest {

    private val provider = "content://com.android.externalstorage.documents"
    private val musicTree = "$provider/tree/primary%3AMusic"
    private val folderUri = "$musicTree/document/primary%3AMusic%2FRoad%20Trip"

    private fun extracted(name: String) = ExtractedFile(name, "$musicTree/document/primary%3AMusic%2FRoad%20Trip%2F${name.replace(" ", "%20")}")

    /** The same file as the library lists it, through the library folder's own tree ([libraryTree]). */
    private fun libraryTrack(id: Long, name: String, libraryTree: String = "$provider/tree/primary%3A") = Track(
        id = id, uri = "$libraryTree/document/primary%3AMusic%2FRoad%20Trip%2F${name.replace(" ", "%20")}", title = name,
        artist = "Band", album = "Road Trip", albumArtist = null, durationMs = 1, trackNumber = null, discNumber = null,
        year = null, albumId = 1, artworkUri = null, mimeType = "audio/flac", sizeBytes = 1,
    )

    private fun entry(file: String) = ArchiveEntry(file, file.substringBeforeLast('.'), "Band", "Road Trip")
    private val files = listOf("01 - Band - Two.flac", "02 - Band - One.flac")
    private val manifest = ArchiveManifest(ArchiveKind.PLAYLIST, "Road Trip", files.map(::entry))

    @Test
    fun `documents match across trees by provider and document id`() {
        val viaFolder = "$musicTree/document/primary%3AMusic%2FA%2B%20B.flac"
        val viaRoot = "$provider/tree/primary%3A/document/primary%3AMusic%2FA%2B%20B.flac"
        assertThat(ArchiveImportRules.documentKey(viaFolder)).isEqualTo(ArchiveImportRules.documentKey(viaRoot))
        assertThat(ArchiveImportRules.documentKey(viaFolder)).isEqualTo("com.android.externalstorage.documents/primary:Music/A+ B.flac")
        assertThat(ArchiveImportRules.documentKey("$provider/document/primary%3AMusic%2FA%2B%20B.flac"))
            .isEqualTo(ArchiveImportRules.documentKey(viaRoot))
        assertThat(ArchiveImportRules.documentKey("file:///sdcard/a.flac")).isNull()
    }

    @Test
    fun `playlists and selections become playlists, an album, a song or a plain ZIP does not`() {
        assertThat(ArchiveImportRules.playlistName(manifest)).isEqualTo("Road Trip")
        assertThat(ArchiveImportRules.playlistName(manifest.copy(kind = ArchiveKind.ALBUM))).isNull()
        assertThat(ArchiveImportRules.playlistName(manifest.copy(kind = ArchiveKind.SELECTION))).isEqualTo("Road Trip")
        assertThat(ArchiveImportRules.playlistName(manifest.copy(kind = ArchiveKind.SONG))).isNull()
        assertThat(ArchiveImportRules.playlistName(manifest.copy(name = "  "))).isNull()
        assertThat(ArchiveImportRules.playlistName(null)).isNull()
    }

    @Test
    fun `a destination inside a library folder is in the library, any other one has its new folder offered`() {
        val music = LibraryFolder(musicTree, "Music", 0)
        val root = LibraryFolder("$provider/tree/primary%3A", "Internal storage", 0)
        val rock = LibraryFolder("$provider/tree/primary%3AMusic%2FRock", "Music/Rock", 0)
        assertThat(ArchiveImportRules.coverage(musicTree, listOf(music))).isEqualTo(DestinationCoverage.IN_LIBRARY)
        assertThat(ArchiveImportRules.coverage(musicTree, listOf(root))).isEqualTo(DestinationCoverage.IN_LIBRARY)
        assertThat(ArchiveImportRules.coverage(musicTree, emptyList())).isEqualTo(DestinationCoverage.JOINS_LIBRARY)
        // Holding a library folder no longer blocks: only the extracted folder joins, and it holds none.
        assertThat(ArchiveImportRules.coverage(musicTree, listOf(rock))).isEqualTo(DestinationCoverage.JOINS_LIBRARY)
    }

    @Test
    fun `only the extracted folder itself is the folder to add`() {
        val picked = "$provider/tree/primary%3AMusic%2FRoad%20Trip"
        assertThat(ArchiveImportRules.isExtractedFolder(picked, folderUri)).isTrue()
        assertThat(ArchiveImportRules.isExtractedFolder("$provider/tree/primary%3AMusic%2FRoad%20Trip%2F", folderUri)).isTrue()
        assertThat(ArchiveImportRules.isExtractedFolder(musicTree, folderUri)).isFalse()
        assertThat(ArchiveImportRules.isExtractedFolder("$provider/tree/primary%3AMusic%2FRoad%20Trip%2FDisc%201", folderUri)).isFalse()
        assertThat(ArchiveImportRules.isExtractedFolder("content://other.provider/tree/primary%3AMusic%2FRoad%20Trip", folderUri)).isFalse()
    }

    @Test
    fun `entries are matched in manifest order, by file name ignoring case, then by document`() {
        val extracted = listOf(extracted("02 - Band - One.flac"), extracted("01 - BAND - two.flac"))
        val library = listOf(libraryTrack(7, "02 - Band - One.flac"), libraryTrack(8, "01 - BAND - two.flac"), libraryTrack(9, "Other.flac"))

        val match = ArchiveImportRules.matchTracks(manifest, extracted, library)

        assertThat(match.tracks.map { it.id }).containsExactly(8L, 7L).inOrder()
        assertThat(match.missing).isEqualTo(0)
    }

    @Test
    fun `entries without a file or a library track are counted as missing`() {
        val extracted = listOf(extracted("01 - Band - Two.flac"))
        val match = ArchiveImportRules.matchTracks(manifest, extracted, emptyList())
        assertThat(match.tracks).isEmpty()
        assertThat(match.missing).isEqualTo(2)
    }

    @Test
    fun `a playlist archive is recreated in order once the scan lists its songs`() = runBlocking {
        val library = FakeLibrary()
        val playlists = FakePlaylists()
        val folders = FakeFolders(listOf(LibraryFolder("$provider/tree/primary%3A", "Internal storage", 0)))
        val finish = FinishArchiveImport(folders, FakeImported(ImportedFolder(files.map(::extracted), manifest)), library, playlists)
        library.tracks.value = listOf(libraryTrack(1, files[1])) // the scan has listed one so far

        val pending = async { finish(musicTree, folderUri, waitMs = 5_000) }
        yield() // the import now waits for the scan
        library.tracks.value = listOf(libraryTrack(1, files[1]), libraryTrack(2, files[0]))
        val outcome = pending.await()

        assertThat(outcome).isEqualTo(ImportOutcome(DestinationCoverage.IN_LIBRARY, playlistName = "Road Trip", playlistSongs = 2, missing = 0, playlistId = 1))
        assertThat(playlists.created.single().first).isEqualTo("Road Trip")
        assertThat(playlists.created.single().second.map { it.trackUri }).containsExactly(libraryTrack(2, files[0]).uri, libraryTrack(1, files[1]).uri).inOrder()
        assertThat(folders.added).isEmpty()
    }

    @Test
    fun `songs the scan never lists are left out after the wait`() = runBlocking {
        val library = FakeLibrary().apply { tracks.value = listOf(libraryTrack(1, files[1])) }
        val playlists = FakePlaylists()
        val finish = FinishArchiveImport(FakeFolders(emptyList()), FakeImported(ImportedFolder(files.map(::extracted), manifest)), library, playlists)

        val outcome = finish.addAndFinish("$provider/tree/primary%3AMusic%2FRoad%20Trip", folderUri, waitMs = 50)

        assertThat(outcome).isEqualTo(ImportOutcome(DestinationCoverage.JOINS_LIBRARY, playlistName = "Road Trip", playlistSongs = 1, missing = 1, playlistId = 1))
    }

    @Test
    fun `a destination outside the library adds nothing by itself`() = runBlocking {
        val folders = FakeFolders(emptyList())
        val playlists = FakePlaylists()
        val finish = FinishArchiveImport(folders, FakeImported(ImportedFolder(files.map(::extracted), manifest)), FakeLibrary(), playlists)

        val outcome = finish(musicTree, folderUri, waitMs = 1_000)

        assertThat(outcome).isEqualTo(ImportOutcome(DestinationCoverage.OUTSIDE_LIBRARY, playlistName = null, playlistSongs = 0, missing = 0))
        assertThat(folders.added).isEmpty()
        assertThat(playlists.created).isEmpty()
    }

    @Test
    fun `the extracted folder the user allows joins the library, then the playlist comes back`() = runBlocking {
        val folderTree = "$provider/tree/primary%3AMusic%2FRoad%20Trip"
        val folders = FakeFolders(listOf(LibraryFolder("$provider/tree/primary%3AMusic%2FRock", "Music/Rock", 0)), accept = true)
        val library = FakeLibrary().apply { tracks.value = files.mapIndexed { i, f -> libraryTrack(i + 1L, f, folderTree) } }
        val playlists = FakePlaylists()
        val finish = FinishArchiveImport(folders, FakeImported(ImportedFolder(files.map(::extracted), manifest)), library, playlists)

        val outcome = finish.addAndFinish(folderTree, folderUri, waitMs = 1_000)

        assertThat(folders.added).containsExactly(folderTree)
        assertThat(outcome).isEqualTo(ImportOutcome(DestinationCoverage.JOINS_LIBRARY, playlistName = "Road Trip", playlistSongs = 2, missing = 0, playlistId = 1))
        assertThat(playlists.created.single().first).isEqualTo("Road Trip")
    }

    @Test
    fun `another folder picked instead is not added`() = runBlocking {
        val folders = FakeFolders(emptyList())
        val finish = FinishArchiveImport(folders, FakeImported(ImportedFolder(files.map(::extracted), manifest)), FakeLibrary(), FakePlaylists())

        val outcome = finish.addAndFinish(musicTree, folderUri, waitMs = 1_000)

        assertThat(folders.added).isEmpty()
        assertThat(outcome.coverage).isEqualTo(DestinationCoverage.OUTSIDE_LIBRARY)
    }

    @Test
    fun `a destination holding a library folder makes no playlist by itself`() = runBlocking {
        val rock = LibraryFolder("$provider/tree/primary%3AMusic%2FRock", "Music/Rock", 0)
        val folders = FakeFolders(listOf(rock))
        val playlists = FakePlaylists()
        val finish = FinishArchiveImport(folders, FakeImported(ImportedFolder(files.map(::extracted), manifest)), FakeLibrary(), playlists)

        val outcome = finish(musicTree, folderUri, waitMs = 1_000)

        assertThat(outcome).isEqualTo(ImportOutcome(DestinationCoverage.OUTSIDE_LIBRARY, playlistName = null, playlistSongs = 0, missing = 0))
        assertThat(folders.added).isEmpty()
        assertThat(playlists.created).isEmpty()
    }

    @Test
    fun `a plain ZIP, a single song or an album adds only music`() = runBlocking {
        val playlists = FakePlaylists()
        val album = FinishArchiveImport(
            FakeFolders(listOf(LibraryFolder(musicTree, "Music", 0))),
            FakeImported(ImportedFolder(files.map(::extracted), manifest.copy(kind = ArchiveKind.ALBUM, name = "Record"))),
            // The scan has listed the album's songs: they regroup as an album from their tags, no playlist is made.
            FakeLibrary().apply { tracks.value = files.mapIndexed { i, name -> libraryTrack(i + 1L, name) } },
            playlists,
        )
        val plain = FinishArchiveImport(FakeFolders(listOf(LibraryFolder(musicTree, "Music", 0))), FakeImported(ImportedFolder(files.map(::extracted), null)), FakeLibrary(), playlists)
        val song = FinishArchiveImport(
            FakeFolders(listOf(LibraryFolder(musicTree, "Music", 0))),
            FakeImported(ImportedFolder(files.take(1).map(::extracted), manifest.copy(kind = ArchiveKind.SONG))),
            FakeLibrary(),
            playlists,
        )

        assertThat(plain(musicTree, folderUri, waitMs = 1_000).playlistName).isNull()
        assertThat(song(musicTree, folderUri, waitMs = 1_000).playlistName).isNull()
        assertThat(album(musicTree, folderUri, waitMs = 1_000).playlistName).isNull()
        assertThat(playlists.created).isEmpty()
    }

    private class FakeImported(private val folder: ImportedFolder) : ImportedFolders {
        override suspend fun read(folderUri: String): ImportedFolder = folder
    }

    /** Library folders; an add is rejected as holding the first one, unless [accept]. */
    private class FakeFolders(private val existing: List<LibraryFolder>, private val accept: Boolean = false) : LibraryFolderRepository {
        val added = mutableListOf<String>()
        override fun folders(): Flow<List<LibraryFolder>> = flowOf(existing)
        override suspend fun add(treeUri: String): AddFolderResult {
            added += treeUri
            return existing.firstOrNull()?.takeIf { !accept }?.let { AddFolderResult.Rejected(FolderConflict.ContainsExisting(it)) }
                ?: AddFolderResult.Added(LibraryFolder(treeUri, "Music", 0))
        }
        override suspend fun remove(treeUri: String) = Unit
    }

    private class FakeLibrary : LibraryRepository {
        val tracks = MutableStateFlow<List<Track>>(emptyList())
        override fun tracks(sort: TrackSort): Flow<List<Track>> = tracks
        override fun albums(): Flow<List<Album>> = error("unused")
        override fun artists(): Flow<List<Artist>> = error("unused")
        override fun recentlyAddedAlbums(limit: Int): Flow<List<Album>> = error("unused")
        override fun tracksForAlbum(albumId: Long): Flow<List<Track>> = error("unused")
        override fun albumsForArtist(artistId: Long): Flow<List<Album>> = error("unused")
        override fun search(query: String): Flow<List<Track>> = error("unused")
    }

    private class FakePlaylists : PlaylistRepository {
        val created = mutableListOf<Pair<String, List<PlaylistEntry>>>()
        override fun playlists(): Flow<List<PlaylistWithEntries>> = error("unused")
        override fun playlist(id: Long): Flow<PlaylistWithEntries?> = error("unused")
        override suspend fun create(name: String, description: String, entries: List<PlaylistEntry>): Long {
            created += name to entries
            return created.size.toLong()
        }
        override suspend fun updateDetails(id: Long, name: String, description: String) = Unit
        override suspend fun delete(id: Long) = Unit
        override suspend fun editEntries(id: Long, edit: (List<PlaylistEntry>) -> List<PlaylistEntry>) = Unit
    }
}
