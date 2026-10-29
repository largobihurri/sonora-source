package io.sonora.core.domain.backup

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.library.marks.HiddenItem
import io.sonora.core.domain.library.marks.ItemRef
import io.sonora.core.domain.library.marks.PinContext
import io.sonora.core.domain.model.Playlist
import io.sonora.core.domain.model.PlaylistEntry
import io.sonora.core.domain.model.PlaylistWithEntries
import org.junit.Test

class RestorePlannerTest {

    private fun strings(vararg values: Pair<String, String>): Map<String, SettingValue> =
        values.associate { (key, value) -> key to SettingValue.StringValue(value) }

    private val kept = SongIdentity("content://a/kept", "Kept", "Band", "Record", 200_000)
    private val moved = SongIdentity("content://old/moved", "Moved", "Band", "Record", 180_000)
    private val gone = SongIdentity("content://a/gone", "Gone", "Band", "Record", 150_000)
    private val movedNow = moved.copy(key = "content://new/moved")
    private val library = listOf(kept, movedNow)

    private val backup = LibraryBackup(
        createdAtMs = 1,
        songs = listOf(kept, moved, gone),
        playlists = listOf(BackupPlaylist(7, "Road Trip", "Summer", listOf(moved.key, gone.key, kept.key))),
        favourites = listOf(
            BackupFavourite(ItemRef.song(moved.key), 10),
            BackupFavourite(ItemRef.song(gone.key), 11),
            BackupFavourite(ItemRef.album(42), 12),
            BackupFavourite(ItemRef.playlist(7), 13),
        ),
        pins = listOf(
            BackupPin(PinContext.SONGS, ItemRef.song(moved.key), 20),
            BackupPin(PinContext.playlist(7), ItemRef.song(kept.key), 21),
            BackupPin(PinContext.PLAYLISTS, ItemRef.playlist(7), 22),
            BackupPin(PinContext.playlist(99), ItemRef.song(kept.key), 23),
        ),
        hidden = listOf(HiddenItem(ItemRef.song(gone.key), "Gone", "Band", 30), HiddenItem(ItemRef.artist(5), "Other", "", 31)),
        plays = listOf(BackupPlays(moved.key, 3, 100), BackupPlays(gone.key, 9, 200)),
        settings = listOf(BackupSettingsFile("sonora_settings", strings("appearance.theme" to "DARK", "export.tree_uri" to "content://x"))),
    )

    @Test
    fun `the DJ analysis records pass through untouched and are counted in the preview`() {
        val withAnalyses = backup.copy(djAnalyses = listOf("QUJD", "REVG", "R0hJ"))

        val plan = RestorePlanner.plan(withAnalyses, library, currentPlaylists = emptyList())

        assertThat(plan.djAnalyses).containsExactly("QUJD", "REVG", "R0hJ").inOrder()
        assertThat(plan.preview.djAnalyses).isEqualTo(3)
        assertThat(RestorePlanner.plan(backup, library, emptyList()).djAnalyses).isEmpty()
    }

    @Test
    fun `the preview counts the playlists and the songs found and not found`() {
        val plan = RestorePlanner.plan(backup, library, currentPlaylists = emptyList())

        assertThat(plan.preview).isEqualTo(RestorePreview(playlists = 1, songsMatched = 2, songsNotFound = 1))
        assertThat(plan.unmatched).containsExactly(gone)
    }

    @Test
    fun `playlist entries keep their order with found songs renamed and missing ones left out`() {
        val playlist = RestorePlanner.plan(backup, library, emptyList()).playlists.single()

        assertThat(playlist.name).isEqualTo("Road Trip")
        assertThat(playlist.description).isEqualTo("Summer")
        assertThat(playlist.existingId).isNull()
        assertThat(playlist.entries).containsExactly(
            PlaylistEntry(0, movedNow.key, "Moved", "Band"),
            PlaylistEntry(0, kept.key, "Kept", "Band"),
        ).inOrder()
    }

    @Test
    fun `a playlist named like a current one gets a suffix, and an identical one is kept instead of copied`() {
        val different = PlaylistWithEntries(Playlist(1, "road trip"), listOf(PlaylistEntry(1, kept.key, "Kept", "Band")))
        val renamed = RestorePlanner.plan(backup, library, listOf(different)).playlists.single()
        assertThat(renamed.name).isEqualTo("Road Trip (2)")
        assertThat(renamed.existingId).isNull()

        val identical = PlaylistWithEntries(
            Playlist(4, "Road Trip (2)"),
            listOf(PlaylistEntry(5, movedNow.key, "Moved", "Band"), PlaylistEntry(6, kept.key, "Kept", "Band")),
        )
        val reused = RestorePlanner.plan(backup, library, listOf(different, identical)).playlists.single()
        assertThat(reused.existingId).isEqualTo(4)
    }

    @Test
    fun `free names skip every taken suffix`() {
        assertThat(RestorePlanner.freeName("Mix", emptyList())).isEqualTo("Mix")
        assertThat(RestorePlanner.freeName("Mix", listOf("MIX", "Mix (2)"))).isEqualTo("Mix (3)")
    }

    @Test
    fun `song marks follow the song to its current key and drop when it is missing`() {
        val marks = RestorePlanner.plan(backup, library, emptyList()).marksFor(mapOf(7L to 70L))

        assertThat(marks.favourites).containsExactly(
            BackupFavourite(ItemRef.song(movedNow.key), 10),
            BackupFavourite(ItemRef.album(42), 12),
            BackupFavourite(ItemRef.playlist(70), 13),
        )
        assertThat(marks.hidden).containsExactly(HiddenItem(ItemRef.artist(5), "Other", "", 31))
    }

    @Test
    fun `playlist marks and pin lists take the restored playlist's id, and other playlists' are dropped`() {
        val marks = RestorePlanner.plan(backup, library, emptyList()).marksFor(mapOf(7L to 70L))

        assertThat(marks.pins).containsExactly(
            BackupPin(PinContext.SONGS, ItemRef.song(movedNow.key), 20),
            BackupPin(PinContext.playlist(70), ItemRef.song(kept.key), 21),
            BackupPin(PinContext.PLAYLISTS, ItemRef.playlist(70), 22),
        )
    }

    @Test
    fun `play counts follow the song, and two backed-up keys of one song add up`() {
        val twin = moved.copy(key = "content://older/moved")
        val withTwin = backup.copy(songs = backup.songs + twin, plays = backup.plays + BackupPlays(twin.key, 2, 150))

        val plays = RestorePlanner.plan(withTwin, library, emptyList()).plays

        assertThat(plays).containsExactly(BackupPlays(movedNow.key, 5, 150))
    }

    @Test
    fun `settings come without secrets or folder grants`() {
        val settings = RestorePlanner.plan(backup, library, emptyList()).settings

        assertThat(settings).containsExactly(BackupSettingsFile("sonora_settings", strings("appearance.theme" to "DARK")))
    }
}
