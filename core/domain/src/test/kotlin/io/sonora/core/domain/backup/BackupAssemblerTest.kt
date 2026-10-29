package io.sonora.core.domain.backup

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.library.marks.HiddenItem
import io.sonora.core.domain.library.marks.ItemRef
import io.sonora.core.domain.library.marks.PinContext
import io.sonora.core.domain.model.Playlist
import io.sonora.core.domain.model.PlaylistEntry
import io.sonora.core.domain.model.PlaylistWithEntries
import org.junit.Test

class BackupAssemblerTest {

    private val listed = SongIdentity("content://a/listed", "Listed", "Band", "Record", 200_000)
    private val favourite = SongIdentity("content://a/favourite", "Favourite", "Band", "Record", 190_000)
    private val played = SongIdentity("content://a/played", "Played", "Band", "Record", 180_000)
    private val unused = SongIdentity("content://a/unused", "Unused", "Band", "Record", 170_000)

    private val playlist = PlaylistWithEntries(
        Playlist(3, "Mix", "Mine"),
        listOf(PlaylistEntry(1, listed.key, "Listed", "Band"), PlaylistEntry(2, "content://gone", "Gone Song", "Old Band")),
    )

    private fun assemble(settings: List<BackupSettingsFile> = emptyList(), djAnalyses: List<String> = emptyList()) = BackupAssembler.assemble(
        nowMs = 1_000,
        library = listOf(listed, favourite, played, unused),
        playlists = listOf(playlist),
        favourites = listOf(BackupFavourite(ItemRef.song(favourite.key), 5), BackupFavourite(ItemRef.album(9), 6)),
        pins = listOf(BackupPin(PinContext.SONGS, ItemRef.song(favourite.key), 7)),
        hidden = listOf(HiddenItem(ItemRef.artist(4), "Artist", "", 8)),
        plays = listOf(BackupPlays(played.key, 4, 900)),
        settings = settings,
        djAnalyses = djAnalyses,
    )

    @Test
    fun `the song table holds every referenced song once, and no other`() {
        val backup = assemble()

        assertThat(backup.songs).containsExactly(
            listed,
            SongIdentity("content://gone", "Gone Song", "Old Band", album = "", durationMs = 0),
            favourite,
            played,
        )
    }

    @Test
    fun `the DJ analysis records go into the backup as they are, in order`() {
        assertThat(assemble(djAnalyses = listOf("QUJD", "REVG")).djAnalyses).containsExactly("QUJD", "REVG").inOrder()
        assertThat(assemble().djAnalyses).isEmpty()
    }

    @Test
    fun `playlists keep their entries in order, by key`() {
        assertThat(assemble().playlists).containsExactly(BackupPlaylist(3, "Mix", "Mine", listOf(listed.key, "content://gone")))
    }

    @Test
    fun `marks, plays and the time are carried as they are`() {
        val backup = assemble()

        assertThat(backup.createdAtMs).isEqualTo(1_000)
        assertThat(backup.favourites).hasSize(2)
        assertThat(backup.pins).hasSize(1)
        assertThat(backup.hidden).hasSize(1)
        assertThat(backup.plays).containsExactly(BackupPlays(played.key, 4, 900))
    }

    @Test
    fun `settings leave out secrets and folder grants`() {
        val values = mapOf(
            "appearance.theme" to SettingValue.StringValue("DARK"),
            "account.session_key" to SettingValue.StringValue("s3cret"),
            "conversion.output_tree_uri" to SettingValue.StringValue("content://tree"),
            "equalizer.enabled" to SettingValue.BooleanValue(true),
        )

        val settings = assemble(listOf(BackupSettingsFile("sonora_settings", values))).settings.single()

        assertThat(settings.values.keys).containsExactly("appearance.theme", "equalizer.enabled")
    }

    @Test
    fun `the file is named after the day it was made`() {
        assertThat(BackupNaming.fileName(2026, 10, 4)).isEqualTo("Sonora-backup-20261004.json")
    }

    @Test
    fun `a song missing from the table is known by its key alone`() {
        assertThat(LibraryBackup(createdAtMs = 0).song("content://x")).isEqualTo(SongIdentity("content://x", "", "", "", 0))
    }
}
