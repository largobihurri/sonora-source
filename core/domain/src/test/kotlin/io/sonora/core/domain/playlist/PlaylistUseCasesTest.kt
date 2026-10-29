package io.sonora.core.domain.playlist

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.Track
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaylistUseCasesTest {

    private val playlists = FakePlaylists()
    private val library = FakeLibrary(listOf(track(10), track(20), track(30)))

    @Test
    fun `create trims the name and description and starts empty`() = runBlocking {
        val id = CreatePlaylist(playlists)("  Road Trip ", " Summer songs ")

        val stored = playlists.state.value.single()
        assertThat(stored.playlist.id).isEqualTo(id)
        assertThat(stored.playlist.name).isEqualTo("Road Trip")
        assertThat(stored.playlist.description).isEqualTo("Summer songs")
        assertThat(stored.entries).isEmpty()
    }

    @Test
    fun `create with songs adds them in one step`() = runBlocking {
        val id = CreatePlaylist(playlists)("Mix", tracks = listOf(track(20), track(10)))

        val entries = playlists.playlist(id).first()!!.entries
        assertThat(entries.map { it.trackUri }).containsExactly("content://music/20", "content://music/10").inOrder()
    }

    @Test
    fun `a blank name is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { CreatePlaylist(playlists)("  ") } }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { EditPlaylistDetails(playlists)(1, " ", "") }
        }
        assertThat(playlists.state.value).isEmpty()
    }

    @Test
    fun `edit renames and changes the description`() = runBlocking {
        val id = CreatePlaylist(playlists)("Mix")

        EditPlaylistDetails(playlists)(id, " Party ", "Loud")

        assertThat(playlists.state.value.single().playlist.name).isEqualTo("Party")
        assertThat(playlists.state.value.single().playlist.description).isEqualTo("Loud")
    }

    @Test
    fun `add appends, remove drops one entry, move reorders and delete removes the playlist`() = runBlocking {
        val id = CreatePlaylist(playlists)("Mix", tracks = listOf(track(10)))
        AddTracksToPlaylist(playlists)(id, listOf(track(20), track(10)))
        val added = playlists.playlist(id).first()!!.entries
        assertThat(added.map { it.trackUri }).containsExactly(
            "content://music/10", "content://music/20", "content://music/10",
        ).inOrder()

        MovePlaylistEntry(playlists)(id, entryId = added[2].id, toIndex = 0)
        RemovePlaylistEntry(playlists)(id, entryId = added[0].id)

        val edited = playlists.playlist(id).first()!!.entries
        assertThat(edited.map { it.id }).containsExactly(added[2].id, added[1].id).inOrder()

        DeletePlaylist(playlists)(id)
        assertThat(playlists.playlist(id).first()).isNull()
    }

    @Test
    fun `adding nothing leaves the playlist alone`() = runBlocking {
        val id = CreatePlaylist(playlists)("Mix", tracks = listOf(track(10)))

        AddTracksToPlaylist(playlists)(id, emptyList())

        assertThat(playlists.playlist(id).first()!!.entries).hasSize(1)
    }

    @Test
    fun `observed playlists resolve their entries against the library, which can change`() = runBlocking {
        val id = CreatePlaylist(playlists)("Mix", tracks = listOf(track(10), track(30)))
        CreatePlaylist(playlists)("Another")
        val observe = ObservePlaylist(playlists, library)

        assertThat(observe(id).first()!!.items.map { it.isAvailable }).containsExactly(true, true).inOrder()

        library.tracks.value = listOf(track(10))
        assertThat(observe(id).first()!!.items.map { it.isAvailable }).containsExactly(true, false).inOrder()

        val all = ObservePlaylists(playlists, library)().first()
        assertThat(all.map { it.playlist.name }).containsExactly("Another", "Mix").inOrder()
        assertThat(all.last().songCount).isEqualTo(2)
    }

    @Test
    fun `a deleted playlist observes as null`() = runBlocking {
        assertThat(ObservePlaylist(playlists, library)(42).first()).isNull()
    }

    @Test
    fun `playlists list like the Room query - ASCII case folded, other letters by code point, ties by id`() = runBlocking {
        listOf("éclair", "Éclair", "b", "B", "a").forEach { CreatePlaylist(playlists)(it) }

        val all = ObservePlaylists(playlists, library)().first()

        assertThat(all.map { it.playlist.name }).containsExactly("a", "b", "B", "Éclair", "éclair").inOrder()
    }

    /** A converted file outside the library folders: the converter's track for it (negative id). */
    private val convertedOnly = track(99).copy(id = -7, uri = "content://converted/99")

    @Test
    fun `an entry for a converted file outside the library plays from the converted file`() = runBlocking {
        val id = CreatePlaylist(playlists)("Mix", tracks = listOf(track(10), convertedOnly))
        val converted = flowOf(listOf(convertedOnly))

        // The first emission may come before the converted files are read; the next one has them.
        val one = withTimeout(1_000) {
            ObservePlaylist(playlists, library, EmptyCoroutineContext, converted)(id).first { it!!.playableTracks.size == 2 }!!
        }
        val all = withTimeout(1_000) {
            ObservePlaylists(playlists, library, EmptyCoroutineContext, converted)().first { it.single().playableTracks.size == 2 }
        }

        assertThat(one.playableTracks.map { it.uri }).containsExactly("content://music/10", "content://converted/99").inOrder()
        assertThat(all.single().items.map { it.isAvailable }).containsExactly(true, true).inOrder()
    }

    @Test
    fun `playlists show before the converted files are read, and when reading them fails`() = runBlocking {
        val id = CreatePlaylist(playlists)("Mix", tracks = listOf(track(10), convertedOnly))
        val neverRead = MutableSharedFlow<List<Track>>()
        val failing = flow<List<Track>> { throw IllegalStateException("provider gone") }

        val waiting = ObservePlaylist(playlists, library, EmptyCoroutineContext, neverRead)(id).first()!!
        val failed = ObservePlaylists(playlists, library, EmptyCoroutineContext, failing)().first().single()

        assertThat(waiting.items.map { it.isAvailable }).containsExactly(true, false).inOrder()
        assertThat(failed.items.map { it.isAvailable }).containsExactly(true, false).inOrder()
    }
}
