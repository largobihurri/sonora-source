package io.sonora.core.domain.library.removal

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.library.marks.FakeLibraryMarks
import io.sonora.core.domain.library.marks.HiddenItem
import io.sonora.core.domain.library.marks.ItemRef
import io.sonora.core.domain.library.marks.LibraryMarksRepository
import io.sonora.core.domain.library.marks.PinContext
import io.sonora.core.domain.model.PlaylistEntry
import io.sonora.core.domain.playlist.FakePlaylists
import io.sonora.core.domain.playlist.entry
import io.sonora.core.domain.playlist.track
import io.sonora.core.domain.port.PlaylistRepository
import io.sonora.core.domain.port.WriteAccessRequest
import io.sonora.core.domain.port.WriteGrant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Test

class DeleteFromPhoneTest {

    private class FakeDeleter : LibraryFileDeleter {
        val files = mutableSetOf<String>()
        val readOnly = mutableSetOf<String>()
        val refusing = mutableSetOf<String>()
        val attempts = mutableListOf<String>()

        /** Deleting [gated] waits for [gate]. */
        var gated: String? = null
        val gate = CompletableDeferred<Unit>()

        override suspend fun writeAccessFor(uri: String) =
            if (uri in readOnly) WriteAccessRequest("tree:$uri", "Music", "start:$uri") else null

        override suspend fun grantWriteAccess(request: WriteAccessRequest, pickedTreeUri: String) = WriteGrant.GRANTED

        override suspend fun delete(uri: String): Boolean {
            attempts += uri
            if (uri == gated) gate.await()
            if (uri in refusing) return false
            if (uri == "throws") error("provider crashed")
            return files.remove(uri)
        }
    }

    /** [FakePlaylists] whose listing or edits can break, and whose edits can wait for [gate]. */
    private class FlakyPlaylists(private val stored: FakePlaylists) : PlaylistRepository by stored {
        var listingFails = false
        val failing = mutableSetOf<Long>()
        var gate: CompletableDeferred<Unit>? = null

        override fun playlists() = if (listingFails) flow { error("database closed") } else stored.playlists()

        override suspend fun editEntries(id: Long, edit: (List<PlaylistEntry>) -> List<PlaylistEntry>) {
            gate?.await()
            if (id in failing) error("disk full")
            stored.editEntries(id, edit)
        }
    }

    /** [FakeLibraryMarks] whose song clean-up (forgetting or handing over) can break. */
    private class FlakyMarks(private val stored: FakeLibraryMarks) : LibraryMarksRepository by stored {
        var fails = false

        override suspend fun forgetSongs(uris: Collection<String>) {
            if (fails) error("disk full")
            stored.forgetSongs(uris)
        }

        override suspend fun moveSongs(moves: Map<String, String>) {
            if (fails) error("disk full")
            stored.moveSongs(moves)
        }
    }

    private val files = FakeDeleter()
    private val playlists = FakePlaylists()
    private val marks = FakeLibraryMarks()
    private val flakyPlaylists = FlakyPlaylists(playlists)
    private val flakyMarks = FlakyMarks(marks)
    private val delete = DeleteFromPhone(files, flakyPlaylists, flakyMarks)

    private val one = track(1)
    private val two = track(2)
    private val three = track(3)

    init {
        files.files += listOf(one.uri, two.uri, three.uri)
    }

    @Test
    fun `nothing selected deletes nothing`() = runBlocking<Unit> {
        assertThat(delete(emptyList())).isEqualTo(DeleteResult.NothingToDelete)
        assertThat(files.attempts).isEmpty()
    }

    @Test
    fun `deletes every file once and reports the count`() = runBlocking<Unit> {
        assertThat(delete(listOf(one, two, one))).isEqualTo(DeleteResult.Deleted(deleted = 2, failed = 0))
        assertThat(files.files).containsExactly(three.uri)
        assertThat(files.attempts).containsExactly(one.uri, two.uri)
    }

    @Test
    fun `songs named by their files' URIs are deleted like the songs themselves`() = runBlocking<Unit> {
        marks.setFavourite(ItemRef.song(two), true)
        assertThat(delete.uris(listOf(one.uri, two.uri, one.uri))).isEqualTo(DeleteResult.Deleted(deleted = 2, failed = 0))
        assertThat(files.files).containsExactly(three.uri)
        assertThat(marks.favourites.value).isEmpty()
        assertThat(delete.uris(emptyList())).isEqualTo(DeleteResult.NothingToDelete)
    }

    @Test
    fun `a read-only folder stops the whole selection before any file is touched`() = runBlocking<Unit> {
        files.readOnly += two.uri
        val result = delete(listOf(one, two))
        assertThat(result).isEqualTo(DeleteResult.NeedsWriteAccess(WriteAccessRequest("tree:${two.uri}", "Music", "start:${two.uri}")))
        assertThat(files.attempts).isEmpty()
        assertThat(files.files).contains(one.uri)
    }

    @Test
    fun `files the storage refuses are counted as failed and keep their marks`() = runBlocking<Unit> {
        files.refusing += two.uri
        marks.setFavourite(ItemRef.song(two), true)
        assertThat(delete(listOf(one, two))).isEqualTo(DeleteResult.Deleted(deleted = 1, failed = 1))
        assertThat(marks.favourites.value).containsExactly(ItemRef.song(two))
    }

    @Test
    fun `a crashing provider counts as a failure, the rest still go`() = runBlocking<Unit> {
        val broken = one.copy(uri = "throws")
        assertThat(delete(listOf(broken, two))).isEqualTo(DeleteResult.Deleted(deleted = 1, failed = 1))
    }

    @Test
    fun `deleted songs leave every playlist, other entries stay in order`() = runBlocking<Unit> {
        val id = playlists.create("Mix", "", listOf(entry(0, 1), entry(0, 3), entry(0, 1), entry(0, 2)))
        playlists.create("Other", "", listOf(entry(0, 3)))
        delete(listOf(one, two))
        val mix = playlists.state.value.first { it.playlist.id == id }
        assertThat(mix.entries.map { it.trackUri }).containsExactly(three.uri)
        assertThat(playlists.state.value.first { it.playlist.name == "Other" }.entries).hasSize(1)
    }

    @Test
    fun `deleted songs with a replacement move to it in their playlists, in place`() = runBlocking<Unit> {
        val id = playlists.create("Mix", "", listOf(entry(0, 1), entry(0, 3), entry(0, 2)))
        files.refusing += two.uri
        assertThat(delete.uris(listOf(one.uri, two.uri), replacements = mapOf(one.uri to three.uri, two.uri to three.uri)))
            .isEqualTo(DeleteResult.Deleted(deleted = 1, failed = 1))
        assertThat(entries(id)).containsExactly(three.uri, three.uri, two.uri).inOrder()
    }

    @Test
    fun `deleted songs lose their favourites, pins and hidden marks`() = runBlocking<Unit> {
        marks.setFavourite(ItemRef.song(one), true)
        marks.setFavourite(ItemRef.song(three), true)
        marks.setPinned(PinContext.SONGS, ItemRef.song(one), true)
        marks.hide(HiddenItem(ItemRef.song(one), "Song 1", "Artist 1", 5))
        delete(listOf(one))
        assertThat(marks.favourites.value).containsExactly(ItemRef.song(three))
        assertThat(marks.pins.value).isEmpty()
        assertThat(marks.hidden.value).isEmpty()
    }

    @Test
    fun `deleted songs with a replacement hand it their marks, a refused one keeps its own`() = runBlocking<Unit> {
        files.refusing += two.uri
        marks.setFavourite(ItemRef.song(one), true)
        marks.setPinned(PinContext.SONGS, ItemRef.song(one), true)
        marks.hide(HiddenItem(ItemRef.song(one), "Song 1", "Artist 1", 5))
        marks.setFavourite(ItemRef.song(two), true)

        delete.uris(listOf(one.uri, two.uri), replacements = mapOf(one.uri to three.uri, two.uri to three.uri))

        assertThat(marks.moves).containsExactly(mapOf(one.uri to three.uri))
        assertThat(marks.favourites.value).containsExactly(ItemRef.song(two), ItemRef.song(three))
        assertThat(marks.pins.value).containsExactly(PinContext.SONGS to ItemRef.song(three))
        assertThat(marks.hidden.value).isEmpty()
    }

    @Test
    fun `a replacement deleted as well takes no marks, they are forgotten`() = runBlocking<Unit> {
        marks.setFavourite(ItemRef.song(one), true)

        delete.uris(listOf(one.uri, two.uri), replacements = mapOf(one.uri to two.uri))

        assertThat(marks.moves).isEmpty()
        assertThat(marks.favourites.value).isEmpty()
    }

    @Test
    fun `marks that cannot be handed over count as a failed clean-up`() = runBlocking<Unit> {
        flakyMarks.fails = true
        assertThat(delete.uris(listOf(one.uri), replacements = mapOf(one.uri to three.uri)))
            .isEqualTo(DeleteResult.Deleted(deleted = 1, failed = 0, cleanUpFailed = true))
    }

    private fun entries(id: Long) = playlists.state.value.first { it.playlist.id == id }.entries.map { it.trackUri }

    @Test
    fun `playlists that cannot be read still let the marks go and the files count as deleted`() = runBlocking<Unit> {
        flakyPlaylists.listingFails = true
        marks.setFavourite(ItemRef.song(one), true)
        assertThat(delete(listOf(one))).isEqualTo(DeleteResult.Deleted(deleted = 1, failed = 0, cleanUpFailed = true))
        assertThat(marks.favourites.value).isEmpty()
    }

    @Test
    fun `one playlist that cannot be edited does not keep the others from being cleaned`() = runBlocking<Unit> {
        val broken = playlists.create("A", "", listOf(entry(0, 1), entry(0, 2)))
        val fine = playlists.create("B", "", listOf(entry(0, 1), entry(0, 3)))
        flakyPlaylists.failing += broken
        assertThat(delete(listOf(one))).isEqualTo(DeleteResult.Deleted(deleted = 1, failed = 0, cleanUpFailed = true))
        assertThat(entries(fine)).containsExactly(three.uri)
    }

    @Test
    fun `marks that cannot be forgotten do not keep the playlists from being cleaned`() = runBlocking<Unit> {
        val id = playlists.create("Mix", "", listOf(entry(0, 1), entry(0, 2)))
        flakyMarks.fails = true
        assertThat(delete(listOf(one))).isEqualTo(DeleteResult.Deleted(deleted = 1, failed = 0, cleanUpFailed = true))
        assertThat(entries(id)).containsExactly(two.uri)
    }

    @Test
    fun `cancelling during the clean-up still finishes it`() = runBlocking<Unit> {
        val id = playlists.create("Mix", "", listOf(entry(0, 1), entry(0, 2)))
        marks.setFavourite(ItemRef.song(one), true)
        val gate = CompletableDeferred<Unit>().also { flakyPlaylists.gate = it }
        val job = launch { delete(listOf(one)) }
        yield()
        job.cancel()
        gate.complete(Unit)
        job.join()
        assertThat(entries(id)).containsExactly(two.uri)
        assertThat(marks.favourites.value).isEmpty()
    }

    @Test
    fun `cancelling between files cleans up after the files already deleted`() = runBlocking<Unit> {
        val id = playlists.create("Mix", "", listOf(entry(0, 1), entry(0, 2)))
        marks.setFavourite(ItemRef.song(one), true)
        marks.setFavourite(ItemRef.song(two), true)
        files.gated = two.uri
        val job = launch { delete(listOf(one, two)) }
        yield()
        job.cancel()
        job.join()
        assertThat(files.files).containsExactly(two.uri, three.uri)
        assertThat(entries(id)).containsExactly(two.uri)
        assertThat(marks.favourites.value).containsExactly(ItemRef.song(two))
    }
}
