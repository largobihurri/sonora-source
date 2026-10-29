package io.sonora.core.domain.backup

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.library.marks.HiddenItem
import io.sonora.core.domain.library.marks.ItemRef
import io.sonora.core.domain.library.marks.PinContext
import io.sonora.core.domain.model.PlaylistEntry
import io.sonora.core.domain.playlist.FakePlaylists
import io.sonora.core.domain.port.BackupCodec
import io.sonora.core.domain.port.BackupDocuments
import io.sonora.core.domain.port.BackupHistory
import io.sonora.core.domain.port.DjBackupStore
import io.sonora.core.domain.port.LibraryBackupStore
import io.sonora.core.domain.port.SettingsBackupStore
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test

class BackupUseCasesTest {

    private class FakeStore(val songs: List<SongIdentity>) : LibraryBackupStore {
        var favourites = listOf<BackupFavourite>()
        var pins = listOf<BackupPin>()
        var hidden = listOf<HiddenItem>()
        var plays = listOf<BackupPlays>()
        override suspend fun librarySongs() = songs
        override suspend fun favourites() = favourites
        override suspend fun pins() = pins
        override suspend fun hidden() = hidden
        override suspend fun plays() = plays
        override suspend fun mergeMarks(marks: RestoredMarks) {
            favourites = (favourites + marks.favourites).distinctBy { it.ref }
            pins = (pins + marks.pins).distinctBy { it.context to it.ref }
            hidden = (hidden + marks.hidden).distinctBy { it.ref }
        }
        override suspend fun mergePlays(plays: List<BackupPlays>) {
            this.plays = (this.plays + plays).groupBy { it.key }
                .map { (key, all) -> BackupPlays(key, all.maxOf { it.count }, all.maxOf { it.lastPlayedMs }) }
        }
    }

    private class FakeSettings(var files: List<BackupSettingsFile>) : SettingsBackupStore {
        override suspend fun read() = files
        override suspend fun merge(files: List<BackupSettingsFile>) {
            this.files = files
        }
    }

    /** Keeps each encoded backup and hands back a token for it. */
    private class FakeCodec : BackupCodec {
        val encoded = mutableMapOf<String, LibraryBackup>()
        override fun encode(backup: LibraryBackup) = "backup#${encoded.size}".also { encoded[it] = backup }
        override fun decode(text: String): BackupReading =
            encoded[text]?.let(BackupReading::Read) ?: BackupReading.Rejected(BackupError.Malformed("unknown"))
    }

    private class FakeDocuments : BackupDocuments {
        val files = mutableMapOf<String, String>()
        override suspend fun write(uri: String, text: String) {
            if (uri == BROKEN) throw IOException("no space")
            files[uri] = text
        }
        override suspend fun read(uri: String): String = files[uri] ?: throw IOException("gone")
    }

    private class FakeHistory : BackupHistory {
        override val lastBackupMs = MutableStateFlow<Long?>(null)
        override suspend fun recordBackup(atMs: Long) {
            lastBackupMs.value = atMs
        }
    }

    /** Holds records as a set of strings; a record spelled `bad` cannot be read and one that is held is not added again. */
    private class FakeDjStore(var held: Set<String> = emptySet()) : DjBackupStore {
        var exportFails = false
        var restoreFails = false
        var restoredWith: List<String>? = null
        override suspend fun export(): List<String> {
            if (exportFails) throw IllegalStateException("database closed")
            return held.toList()
        }
        override suspend fun restore(records: List<String>): DjRestoreOutcome {
            if (restoreFails) throw IllegalStateException("database closed")
            restoredWith = records
            val skipped = records.count { it == "bad" }
            val fresh = records.filter { it != "bad" && it !in held }.distinct()
            held = held + fresh
            return DjRestoreOutcome(restored = fresh.size, alreadyPresent = records.size - skipped - fresh.size, skipped = skipped)
        }
    }

    private val song = SongIdentity("content://a/1", "One", "Band", "Record", 100_000)
    private val store = FakeStore(listOf(song))
    private val settings = FakeSettings(listOf(BackupSettingsFile("sonora_settings", mapOf("app.language" to SettingValue.StringValue("ENGLISH")))))
    private val playlists = FakePlaylists()
    private val codec = FakeCodec()
    private val documents = FakeDocuments()
    private val history = FakeHistory()

    private val createBackup = CreateBackup(store, settings, playlists, codec, documents, history, now = { 5_000 })
    private val readBackup = ReadBackup(store, playlists, codec, documents)
    private val applyRestore = ApplyRestore(store, settings, playlists)

    @Test
    fun `a backup writes the library's state to the document and records when`() = runBlocking {
        playlists.create("Mix", "", listOf(PlaylistEntry(0, song.key, "One", "Band")))
        store.favourites = listOf(BackupFavourite(ItemRef.song(song.key), 1))
        store.plays = listOf(BackupPlays(song.key, 2, 3))

        assertThat(createBackup("content://doc")).isTrue()

        val written = codec.encoded.getValue(documents.files.getValue("content://doc"))
        assertThat(written.createdAtMs).isEqualTo(5_000)
        assertThat(written.songs).containsExactly(song)
        assertThat(written.playlists.single().entries).containsExactly(song.key)
        assertThat(written.favourites).isEqualTo(store.favourites)
        assertThat(written.plays).isEqualTo(store.plays)
        assertThat(written.settings).isEqualTo(settings.files)
        assertThat(history.lastBackupMs.first()).isEqualTo(5_000)
    }

    @Test
    fun `a backup carries the DJ store's records`() = runBlocking<Unit> {
        val dj = FakeDjStore(setOf("QUJD", "REVG"))

        CreateBackup(store, settings, playlists, codec, documents, history, now = { 5_000 }, djStore = dj)("content://doc")

        val written = codec.encoded.getValue(documents.files.getValue("content://doc"))
        assertThat(written.djAnalyses).containsExactly("QUJD", "REVG")
    }

    @Test
    fun `a backup without a DJ store, or whose DJ store fails, still backs up everything else`() = runBlocking<Unit> {
        store.plays = listOf(BackupPlays(song.key, 2, 3))
        val failing = FakeDjStore().also { it.exportFails = true }

        assertThat(CreateBackup(store, settings, playlists, codec, documents, history, now = { 5_000 })("content://a")).isTrue()
        assertThat(CreateBackup(store, settings, playlists, codec, documents, history, now = { 5_000 }, djStore = failing)("content://b")).isTrue()

        for (uri in listOf("content://a", "content://b")) {
            val written = codec.encoded.getValue(documents.files.getValue(uri))
            assertThat(written.djAnalyses).isEmpty()
            assertThat(written.plays).isEqualTo(store.plays)
        }
    }

    @Test
    fun `a backup that cannot be written records nothing`() = runBlocking {
        assertThat(createBackup(BROKEN)).isFalse()
        assertThat(history.lastBackupMs.first()).isNull()
    }

    @Test
    fun `reading plans the restore without writing, and tells unreadable and rejected files apart`() = runBlocking {
        documents.files["content://backup"] = codec.encode(
            LibraryBackup(createdAtMs = 1, songs = listOf(song), playlists = listOf(BackupPlaylist(9, "Mix", "", listOf(song.key)))),
        )
        documents.files["content://other"] = "{}"

        val ready = readBackup("content://backup") as RestoreReading.Ready
        assertThat(ready.plan.preview).isEqualTo(RestorePreview(playlists = 1, songsMatched = 1, songsNotFound = 0))
        assertThat(playlists.state.value).isEmpty()
        assertThat(readBackup("content://other")).isEqualTo(RestoreReading.Rejected(BackupError.Malformed("unknown")))
        assertThat(readBackup("content://missing")).isEqualTo(RestoreReading.Unreadable)
    }

    @Test
    fun `applying adds playlists and then the marks that name them, keeping everything current`() = runBlocking {
        val existing = playlists.create("Mine", "", emptyList())
        store.favourites = listOf(BackupFavourite(ItemRef.playlist(existing), 1))
        store.plays = listOf(BackupPlays(song.key, 10, 50))
        val gone = SongIdentity("content://a/gone", "Gone", "Band", "Record", 1)
        val backup = LibraryBackup(
            createdAtMs = 1,
            songs = listOf(song, gone),
            playlists = listOf(BackupPlaylist(9, "Mix", "", listOf(song.key, gone.key))),
            favourites = listOf(BackupFavourite(ItemRef.playlist(9), 2)),
            pins = listOf(BackupPin(PinContext.playlist(9), ItemRef.song(song.key), 3)),
            plays = listOf(BackupPlays(song.key, 4, 99)),
            settings = listOf(BackupSettingsFile("sonora_settings", mapOf("app.language" to SettingValue.StringValue("BASQUE")))),
        )
        val plan = RestorePlanner.plan(backup, store.songs, playlists.state.value)

        val report = applyRestore(plan)

        val mix = playlists.state.value.single { it.playlist.name == "Mix" }
        assertThat(mix.entries.map { it.trackUri }).containsExactly(song.key)
        assertThat(store.favourites.map { it.ref }).containsExactly(ItemRef.playlist(existing), ItemRef.playlist(mix.playlist.id))
        assertThat(store.pins.single().context).isEqualTo(PinContext.playlist(mix.playlist.id))
        assertThat(store.plays).containsExactly(BackupPlays(song.key, 10, 99))
        assertThat(settings.files.single().values["app.language"]).isEqualTo(SettingValue.StringValue("BASQUE"))
        assertThat(report).isEqualTo(RestoreReport(playlistsAdded = 1, songsMatched = 1, unmatched = listOf(gone)))
    }

    @Test
    fun `applying the same backup again adds no second copy of its playlists`() = runBlocking {
        val backup = LibraryBackup(createdAtMs = 1, songs = listOf(song), playlists = listOf(BackupPlaylist(9, "Mix", "", listOf(song.key))))
        applyRestore(RestorePlanner.plan(backup, store.songs, playlists.state.value))

        val again = applyRestore(RestorePlanner.plan(backup, store.songs, playlists.state.value))

        assertThat(again.playlistsAdded).isEqualTo(0)
        assertThat(playlists.state.value).hasSize(1)
    }

    @Test
    fun `applying hands the DJ records to the DJ store and reports what it did`() = runBlocking<Unit> {
        val dj = FakeDjStore(setOf("have"))
        val plan = RestorePlanner.plan(LibraryBackup(createdAtMs = 1, djAnalyses = listOf("have", "new", "bad")), store.songs, emptyList())

        val report = ApplyRestore(store, settings, playlists, dj)(plan)

        assertThat(dj.restoredWith).containsExactly("have", "new", "bad").inOrder()
        assertThat(report.djAnalyses).isEqualTo(DjRestoreOutcome(restored = 1, alreadyPresent = 1, skipped = 1))
        assertThat(dj.held).containsExactly("have", "new")
    }

    @Test
    fun `a DJ store that fails does not stop the rest of the restore, and its records count as skipped`() = runBlocking<Unit> {
        val dj = FakeDjStore().also { it.restoreFails = true }
        val backup = LibraryBackup(
            createdAtMs = 1,
            songs = listOf(song),
            plays = listOf(BackupPlays(song.key, 4, 99)),
            settings = listOf(BackupSettingsFile("sonora_settings", mapOf("app.language" to SettingValue.StringValue("BASQUE")))),
            djAnalyses = listOf("a", "b"),
        )

        val report = ApplyRestore(store, settings, playlists, dj)(RestorePlanner.plan(backup, store.songs, emptyList()))

        assertThat(report.djAnalyses).isEqualTo(DjRestoreOutcome(restored = 0, alreadyPresent = 0, skipped = 2))
        assertThat(store.plays).containsExactly(BackupPlays(song.key, 4, 99))
        assertThat(settings.files.single().values["app.language"]).isEqualTo(SettingValue.StringValue("BASQUE"))
    }

    @Test
    fun `a build without a DJ store skips the DJ records and a backup without them reports none`() = runBlocking<Unit> {
        val withRecords = RestorePlanner.plan(LibraryBackup(createdAtMs = 1, djAnalyses = listOf("a", "b")), store.songs, emptyList())

        assertThat(applyRestore(withRecords).djAnalyses).isEqualTo(DjRestoreOutcome(restored = 0, alreadyPresent = 0, skipped = 2))
        assertThat(applyRestore(RestorePlanner.plan(LibraryBackup(createdAtMs = 1), store.songs, emptyList())).djAnalyses)
            .isEqualTo(DjRestoreOutcome.NONE)
    }

    private companion object {
        const val BROKEN = "content://broken"
    }
}
