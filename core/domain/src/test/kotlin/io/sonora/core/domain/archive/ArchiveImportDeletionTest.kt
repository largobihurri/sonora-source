package io.sonora.core.domain.archive

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.library.marks.FakeLibraryMarks
import io.sonora.core.domain.library.marks.HiddenItem
import io.sonora.core.domain.library.marks.ItemRef
import io.sonora.core.domain.library.marks.PinContext
import io.sonora.core.domain.library.removal.DeleteFromPhone
import io.sonora.core.domain.library.removal.LibraryFileDeleter
import io.sonora.core.domain.model.AddFolderResult
import io.sonora.core.domain.model.LibraryFolder
import io.sonora.core.domain.model.PlaylistEntry
import io.sonora.core.domain.playlist.FakePlaylists
import io.sonora.core.domain.port.LibraryFolderRepository
import io.sonora.core.domain.port.WriteAccessRequest
import io.sonora.core.domain.port.WriteGrant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ArchiveImportDeletionTest {

    private val provider = "content://com.android.externalstorage.documents"
    private val musicTree = "$provider/tree/primary%3AMusic"
    private val folderUri = "$musicTree/document/primary%3AMusic%2FMix"
    private val folderTree = "$provider/tree/primary%3AMusic%2FMix"
    private val rootTree = "$provider/tree/primary%3A"

    private fun key(name: String) = "com.android.externalstorage.documents/primary:Music/Mix/$name"

    /** The library's URI of the extracted file [name], through the library folder [tree]. */
    private fun libraryUri(name: String, tree: String = folderTree) = "$tree/document/primary%3AMusic%2FMix%2F$name"

    private val other = "$rootTree/document/primary%3AMusic%2FOther%2Fc.flac"

    private class FakeFiles : LibraryFileDeleter {
        val files = mutableSetOf<String>()
        val readOnly = mutableSetOf<String>()
        val refusing = mutableSetOf<String>()
        val attempts = mutableListOf<String>()
        override suspend fun writeAccessFor(uri: String) = if (uri in readOnly) WriteAccessRequest("tree", "Music/Mix", "start") else null
        override suspend fun grantWriteAccess(request: WriteAccessRequest, pickedTreeUri: String) = WriteGrant.GRANTED
        override suspend fun delete(uri: String): Boolean {
            attempts += uri
            return uri !in refusing && files.remove(uri)
        }
    }

    private class FakeFolderDeleter : ImportedFolderDeleter {
        val calls = mutableListOf<Pair<String, List<String>>>()
        var deletes = true
        override suspend fun delete(folderUri: String, trees: List<String>): Boolean {
            calls += folderUri to trees
            return deletes
        }
    }

    private class FakeFolders(initial: List<LibraryFolder>) : LibraryFolderRepository {
        val folders = MutableStateFlow(initial)
        val removed = mutableListOf<String>()
        override fun folders(): Flow<List<LibraryFolder>> = folders
        override suspend fun add(treeUri: String): AddFolderResult = error("unused")
        override suspend fun remove(treeUri: String) {
            removed += treeUri
            folders.value = folders.value.filterNot { it.treeUri == treeUri }
        }
    }

    private val imports = FakeArchiveImports()
    private val files = FakeFiles()
    private val folderDeleter = FakeFolderDeleter()
    private val playlists = FakePlaylists()
    private val marks = FakeLibraryMarks()
    private var indexed = listOf<String>()

    private fun deletion(libraryFolders: FakeFolders) = DeleteArchiveImport(
        imports,
        { indexed },
        DeleteFromPhone(files, playlists, marks),
        libraryFolders,
        folderDeleter,
        playlists,
    )

    /** An import of a.flac and b.flac into Music/Mix; its playlist when [playlistId] is given. */
    private suspend fun recorded(playlistId: Long? = null): Long {
        val id = imports.add(
            ArchiveImportEntry(0, "Mix.zip", 1, musicTree, folderUri, playlistId),
            listOf(ImportedSong(key("a.flac"), "A", "Band"), ImportedSong(key("b.flac"), "B", "Band")),
        )
        return id
    }

    private fun entry(uri: String) = PlaylistEntry(id = 0, trackUri = uri, title = "", artist = "")

    @Test
    fun `Delete from Phone deletes the songs, the folder, the playlist and the record, and the folder leaves the library`() = runBlocking<Unit> {
        val a = libraryUri("a.flac")
        val b = libraryUri("b.flac")
        indexed = listOf(a, b, other)
        files.files += indexed
        val folders = FakeFolders(listOf(LibraryFolder(folderTree, "Music/Mix", 0), LibraryFolder("$provider/tree/primary%3AMusic%2FRock", "Music/Rock", 0)))
        val mix = playlists.create("Mix", "", listOf(entry(a), entry(b)))
        val kept = playlists.create("Kept", "", listOf(entry(a), entry(other)))
        val id = recorded(playlistId = mix)
        marks.setFavourite(ItemRef.song(a), true)
        marks.setPinned(PinContext.SONGS, ItemRef.song(b), true)
        marks.hide(HiddenItem(ItemRef.song(b), "B", "Band", 1))

        val result = deletion(folders)(id)

        assertThat(result).isEqualTo(ImportDeletion.Deleted(songs = 2))
        assertThat(files.files).containsExactly(other)
        assertThat(playlists.state.value.map { it.playlist.id }).containsExactly(kept)
        assertThat(playlists.state.value.single().entries.map { it.trackUri }).containsExactly(other)
        assertThat(marks.favourites.value).isEmpty()
        assertThat(marks.pins.value).isEmpty()
        assertThat(marks.hidden.value).isEmpty()
        assertThat(folderDeleter.calls).containsExactly(folderUri to listOf(folderTree))
        assertThat(folders.removed).containsExactly(folderTree)
        assertThat(imports.get(id)).isNull()
    }

    @Test
    fun `an import inside a library folder keeps that folder in the library`() = runBlocking<Unit> {
        indexed = listOf(libraryUri("a.flac", rootTree), libraryUri("b.flac", rootTree), other)
        files.files += indexed
        val folders = FakeFolders(listOf(LibraryFolder(rootTree, "Internal storage", 0)))
        val id = recorded()

        assertThat(deletion(folders)(id)).isEqualTo(ImportDeletion.Deleted(songs = 2))

        assertThat(folderDeleter.calls).containsExactly(folderUri to listOf(rootTree))
        assertThat(folders.removed).isEmpty()
        assertThat(imports.get(id)).isNull()
    }

    @Test
    fun `a read-only folder asks for write access before anything is deleted`() = runBlocking<Unit> {
        indexed = listOf(libraryUri("a.flac"), libraryUri("b.flac"))
        files.files += indexed
        files.readOnly += libraryUri("b.flac")
        val id = recorded()

        val result = deletion(FakeFolders(listOf(LibraryFolder(folderTree, "Music/Mix", 0))))(id)

        assertThat(result).isEqualTo(ImportDeletion.NeedsWriteAccess(WriteAccessRequest("tree", "Music/Mix", "start")))
        assertThat(files.attempts).isEmpty()
        assertThat(folderDeleter.calls).isEmpty()
        assertThat(imports.get(id)).isNotNull()
    }

    @Test
    fun `songs the storage refuses keep their folder, playlist, library folder and record`() = runBlocking<Unit> {
        indexed = listOf(libraryUri("a.flac"), libraryUri("b.flac"))
        files.files += indexed
        files.refusing += libraryUri("b.flac")
        val folders = FakeFolders(listOf(LibraryFolder(folderTree, "Music/Mix", 0)))
        val mix = playlists.create("Mix", "", listOf(entry(libraryUri("a.flac")), entry(libraryUri("b.flac"))))
        val id = recorded(playlistId = mix)

        val result = deletion(folders)(id)

        assertThat(result).isEqualTo(ImportDeletion.Incomplete(deleted = 1, failed = 1, folderKept = true))
        assertThat(folderDeleter.calls).isEmpty()
        assertThat(folders.removed).isEmpty()
        assertThat(playlists.state.value.map { it.playlist.id }).containsExactly(mix)
        assertThat(imports.get(id)).isNotNull()
    }

    @Test
    fun `a folder that cannot be deleted keeps the record to try again, its playlist goes with the songs`() = runBlocking<Unit> {
        indexed = listOf(libraryUri("a.flac"), libraryUri("b.flac"))
        files.files += indexed
        folderDeleter.deletes = false
        val folders = FakeFolders(listOf(LibraryFolder(folderTree, "Music/Mix", 0)))
        val mix = playlists.create("Mix", "", listOf(entry(libraryUri("a.flac"))))
        val id = recorded(playlistId = mix)

        val result = deletion(folders)(id)

        assertThat(result).isEqualTo(ImportDeletion.Incomplete(deleted = 2, failed = 0, folderKept = true))
        assertThat(playlists.state.value).isEmpty()
        assertThat(folders.removed).isEmpty()
        assertThat(imports.get(id)).isNotNull()
    }

    @Test
    fun `an import left outside the library deletes its folder through its destination`() = runBlocking<Unit> {
        indexed = listOf(other)
        val id = recorded()

        val result = deletion(FakeFolders(emptyList()))(id)

        assertThat(result).isEqualTo(ImportDeletion.Deleted(songs = 0))
        assertThat(files.attempts).isEmpty()
        assertThat(folderDeleter.calls).containsExactly(folderUri to emptyList<String>())
        assertThat(imports.get(id)).isNull()
    }

    @Test
    fun `an import no longer in the history deletes nothing`() = runBlocking<Unit> {
        assertThat(deletion(FakeFolders(emptyList()))(42)).isEqualTo(ImportDeletion.Gone)
        assertThat(folderDeleter.calls).isEmpty()
    }

    @Test
    fun `the library folders holding an extracted folder are found by path`() {
        val folders = listOf(
            LibraryFolder(rootTree, "Internal storage", 0),
            LibraryFolder(folderTree, "Music/Mix", 0),
            LibraryFolder("$provider/tree/primary%3AMusic%2FMixes", "Music/Mixes", 0),
        )
        assertThat(ArchiveImportRules.foldersHolding(folderUri, folders).map { it.treeUri }).containsExactly(rootTree, folderTree).inOrder()
        assertThat(ArchiveImportRules.foldersHolding("file:///sdcard/Mix", folders)).isEmpty()
    }
}
