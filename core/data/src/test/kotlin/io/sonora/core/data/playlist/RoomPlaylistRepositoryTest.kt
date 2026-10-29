package io.sonora.core.data.playlist

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.sonora.core.data.library.db.LibraryDatabase
import io.sonora.core.domain.model.PlaylistEntry
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomPlaylistRepositoryTest {

    /** A clock the test moves forward. */
    private class SteppingClock(var ms: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(ms)
    }

    private lateinit var db: LibraryDatabase
    private lateinit var repository: RoomPlaylistRepository
    private val clock = SteppingClock(1_000)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomPlaylistRepository(db.playlistDao(), clock)
    }

    @After
    fun tearDown() = db.close()

    private fun new(uri: String) = PlaylistEntry(id = 0, trackUri = "content://music/$uri", title = "T $uri", artist = "A $uri")

    private fun entryCount(): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM playlist_entries").use {
        it.moveToFirst()
        it.getInt(0)
    }

    @Test
    fun `a created playlist reads back with its details, times and entries in order`() = runBlocking {
        val id = repository.create("Road Trip", "Summer", listOf(new("b"), new("a")))

        val stored = repository.playlist(id).first()!!
        assertThat(stored.playlist.name).isEqualTo("Road Trip")
        assertThat(stored.playlist.description).isEqualTo("Summer")
        assertThat(stored.playlist.createdAtMs).isEqualTo(1_000L)
        assertThat(stored.playlist.updatedAtMs).isEqualTo(1_000L)
        assertThat(stored.entries.map { it.trackUri }).containsExactly("content://music/b", "content://music/a").inOrder()
        assertThat(stored.entries.map { it.title }).containsExactly("T b", "T a").inOrder()
        assertThat(stored.entries.map { it.id }.toSet()).hasSize(2)
        assertThat(stored.entries.none { it.id == 0L }).isTrue()
    }

    @Test
    fun `the same song can be in a playlist twice, as two entries`() = runBlocking {
        val id = repository.create("Mix", "", listOf(new("a"), new("a")))

        val entries = repository.playlist(id).first()!!.entries
        assertThat(entries.map { it.trackUri }).containsExactly("content://music/a", "content://music/a")
        assertThat(entries[0].id).isNotEqualTo(entries[1].id)
    }

    @Test
    fun `editing entries keeps the ids of kept entries, stores new ones and marks the playlist updated`() = runBlocking {
        val id = repository.create("Mix", "", listOf(new("a"), new("b"), new("c")))
        val before = repository.playlist(id).first()!!.entries
        clock.ms = 5_000

        repository.editEntries(id) { listOf(it[2], it[0], new("d")) }

        val after = repository.playlist(id).first()!!
        assertThat(after.entries.take(2).map { it.id }).containsExactly(before[2].id, before[0].id).inOrder()
        assertThat(after.entries.map { it.trackUri }).containsExactly(
            "content://music/c", "content://music/a", "content://music/d",
        ).inOrder()
        assertThat(after.entries.last().id).isNotEqualTo(0L)
        assertThat(after.playlist.updatedAtMs).isEqualTo(5_000L)
        assertThat(after.playlist.createdAtMs).isEqualTo(1_000L)
    }

    @Test
    fun `an edit that lists a kept entry twice stores the repeat as a new entry`() = runBlocking<Unit> {
        val id = repository.create("Mix", "", listOf(new("a"), new("b")))
        val before = repository.playlist(id).first()!!.entries

        repository.editEntries(id) { it + it[0] }

        val after = repository.playlist(id).first()!!.entries
        assertThat(after.map { it.trackUri }).containsExactly(
            "content://music/a", "content://music/b", "content://music/a",
        ).inOrder()
        assertThat(after.take(2).map { it.id }).containsExactly(before[0].id, before[1].id).inOrder()
        assertThat(after.map { it.id }.toSet()).hasSize(3)
    }

    @Test
    fun `an entry of another playlist is stored as a new entry and that playlist keeps its own`() = runBlocking<Unit> {
        val other = repository.create("Other", "", listOf(new("x")))
        val foreign = repository.playlist(other).first()!!.entries.single()
        val id = repository.create("Mix", "", listOf(new("a")))

        repository.editEntries(id) { it + foreign }

        val mix = repository.playlist(id).first()!!.entries
        assertThat(mix.map { it.trackUri }).containsExactly("content://music/a", "content://music/x").inOrder()
        assertThat(mix.last().id).isNotEqualTo(foreign.id)
        assertThat(repository.playlist(other).first()!!.entries).containsExactly(foreign)
    }

    @Test
    fun `playlists sort like SQLite NOCASE - ASCII case folded, other letters by code point, ties by id`() = runBlocking<Unit> {
        listOf("éclair", "Éclair", "b", "B", "a").forEach { repository.create(it, "", emptyList()) }

        val all = repository.playlists().first()

        // "b" and "B" tie, so creation order (id) decides; NOCASE does not fold "É", which sorts before "é".
        assertThat(all.map { it.playlist.name }).containsExactly("a", "b", "B", "Éclair", "éclair").inOrder()
    }

    @Test
    fun `editing a playlist that does not exist stores nothing`() = runBlocking {
        repository.editEntries(42) { it + new("a") }

        assertThat(entryCount()).isEqualTo(0)
        assertThat(repository.playlist(42).first()).isNull()
    }

    @Test
    fun `deleting a playlist deletes its entries and leaves the others alone`() = runBlocking {
        val gone = repository.create("Gone", "", listOf(new("a"), new("b")))
        val kept = repository.create("Kept", "", listOf(new("a")))

        repository.delete(gone)

        assertThat(repository.playlist(gone).first()).isNull()
        assertThat(repository.playlist(kept).first()!!.entries).hasSize(1)
        assertThat(entryCount()).isEqualTo(1)
    }

    @Test
    fun `playlists list by name ignoring case, each with its entries`() = runBlocking {
        repository.create("beta", "", listOf(new("x")))
        repository.create("Alpha", "", emptyList())
        repository.create("Gamma", "", listOf(new("y"), new("z")))

        val all = repository.playlists().first()
        assertThat(all.map { it.playlist.name }).containsExactly("Alpha", "beta", "Gamma").inOrder()
        assertThat(all.map { it.entries.size }).containsExactly(0, 1, 2).inOrder()
        assertThat(all.last().entries.map { it.trackUri }).containsExactly("content://music/y", "content://music/z").inOrder()
    }

    @Test
    fun `details update renames and changes the description`() = runBlocking {
        val id = repository.create("Mix", "", emptyList())
        clock.ms = 9_000

        repository.updateDetails(id, "Party", "Loud")

        val playlist = repository.playlist(id).first()!!.playlist
        assertThat(playlist.name).isEqualTo("Party")
        assertThat(playlist.description).isEqualTo("Loud")
        assertThat(playlist.updatedAtMs).isEqualTo(9_000L)
    }
}
