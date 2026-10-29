package io.sonora.core.domain.library.duplicates

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.library.marks.FakeLibraryMarks
import io.sonora.core.domain.library.marks.ItemRef
import io.sonora.core.domain.library.marks.PinContext
import io.sonora.core.domain.library.removal.DeleteFromPhone
import io.sonora.core.domain.library.removal.LibraryFileDeleter
import io.sonora.core.domain.model.PlaylistEntry
import io.sonora.core.domain.playlist.FakePlaylists
import io.sonora.core.domain.port.WriteAccessRequest
import io.sonora.core.domain.port.WriteGrant
import kotlinx.coroutines.runBlocking
import org.junit.Test

class DeleteDuplicateCopiesTest {

    private class FakeFiles(vararg uris: String) : LibraryFileDeleter {
        val files = uris.toMutableSet()
        val readOnly = mutableSetOf<String>()
        val refusing = mutableSetOf<String>()

        override suspend fun writeAccessFor(uri: String) = if (uri in readOnly) WriteAccessRequest("tree", "Music", "start") else null

        override suspend fun grantWriteAccess(request: WriteAccessRequest, pickedTreeUri: String) = WriteGrant.GRANTED

        override suspend fun delete(uri: String): Boolean = uri !in refusing && files.remove(uri)
    }

    private val best = copy("best", bitsPerSample = 24)
    private val cd = copy("cd")
    private val lossy = mp3("lossy")
    private val group = DuplicateGroup(listOf(best, cd, lossy))
    private val files = FakeFiles(best.uri, cd.uri, lossy.uri)
    private val playlists = FakePlaylists()
    private val marks = FakeLibraryMarks()
    private val delete = DeleteDuplicateCopies(DeleteFromPhone(files, playlists, marks))

    private fun entry(file: DuplicateCopy) = PlaylistEntry(0, file.uri, file.title, file.artist)

    private fun uris(id: Long) = playlists.state.value.first { it.playlist.id == id }.entries.map { it.trackUri.substringAfterLast('/') }

    @Test
    fun `nothing selected deletes nothing`() = runBlocking<Unit> {
        assertThat(delete(group, emptySet())).isEqualTo(DuplicateDeletion.NothingSelected)
        assertThat(delete(group, setOf("content://music/elsewhere"))).isEqualTo(DuplicateDeletion.NothingSelected)
    }

    @Test
    fun `the last copy of a group is never deleted`() = runBlocking<Unit> {
        assertThat(delete(group, setOf(best.uri, cd.uri, lossy.uri))).isEqualTo(DuplicateDeletion.KeepsNoCopy)
        assertThat(files.files).hasSize(3)
    }

    @Test
    fun `deleted copies' playlist entries move to the kept copy, in place`() = runBlocking<Unit> {
        val mix = playlists.create("Mix", "", listOf(entry(lossy), entry(copy("other")), entry(cd), entry(best)))
        assertThat(delete(group, setOf(cd.uri, lossy.uri))).isEqualTo(DuplicateDeletion.Deleted(deleted = 2, failed = 0))
        assertThat(files.files).containsExactly(best.uri)
        assertThat(uris(mix)).containsExactly("best", "other", "best", "best").inOrder()
    }

    @Test
    fun `deleted copies hand their favourites and pins to the kept copy`() = runBlocking<Unit> {
        marks.setFavourite(ItemRef.song(lossy.uri), true)
        marks.setPinned(PinContext.SONGS, ItemRef.song(cd.uri), true)

        delete(group, setOf(cd.uri, lossy.uri))

        assertThat(marks.moves).containsExactly(mapOf(cd.uri to best.uri, lossy.uri to best.uri))
        assertThat(marks.favourites.value).containsExactly(ItemRef.song(best.uri))
        assertThat(marks.pins.value).containsExactly(PinContext.SONGS to ItemRef.song(best.uri))
    }

    @Test
    fun `when the suggested copy goes, entries move to the best copy kept`() = runBlocking<Unit> {
        val mix = playlists.create("Mix", "", listOf(entry(best)))
        delete(group, setOf(best.uri, lossy.uri))
        assertThat(uris(mix)).containsExactly("cd")
    }

    @Test
    fun `a copy the storage refuses keeps its entries, a read-only folder stops everything`() = runBlocking<Unit> {
        val mix = playlists.create("Mix", "", listOf(entry(cd), entry(lossy)))
        files.refusing += lossy.uri
        assertThat(delete(group, setOf(cd.uri, lossy.uri))).isEqualTo(DuplicateDeletion.Deleted(deleted = 1, failed = 1))
        assertThat(uris(mix)).containsExactly("best", "lossy").inOrder()

        assertThat(marks.moves).containsExactly(mapOf(cd.uri to best.uri))

        files.readOnly += lossy.uri
        assertThat(delete(group, setOf(lossy.uri))).isEqualTo(DuplicateDeletion.NeedsWriteAccess(WriteAccessRequest("tree", "Music", "start")))
        assertThat(files.files).contains(lossy.uri)
    }
}
