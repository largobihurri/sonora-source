package io.sonora.core.domain.backup

import io.sonora.core.domain.port.BackupCodec
import io.sonora.core.domain.port.BackupDocuments
import io.sonora.core.domain.port.BackupHistory
import io.sonora.core.domain.port.DjBackupStore
import io.sonora.core.domain.port.LibraryBackupStore
import io.sonora.core.domain.port.PlaylistRepository
import io.sonora.core.domain.port.SettingsBackupStore
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Writes a backup of the library's playlists, marks, play counts, DJ analyses and settings to a picked document. */
class CreateBackup(
    private val store: LibraryBackupStore,
    private val settings: SettingsBackupStore,
    private val playlists: PlaylistRepository,
    private val codec: BackupCodec,
    private val documents: BackupDocuments,
    private val history: BackupHistory,
    private val now: () -> Long,
    private val djStore: DjBackupStore? = null,
) {
    /** Writes the backup to [uri]; `false` when the document could not be written (nothing is recorded then). */
    suspend operator fun invoke(uri: String): Boolean {
        val nowMs = now()
        val backup = BackupAssembler.assemble(
            nowMs = nowMs,
            library = store.librarySongs(),
            playlists = playlists.playlists().first(),
            favourites = store.favourites(),
            pins = store.pins(),
            hidden = store.hidden(),
            plays = store.plays(),
            settings = settings.read(),
            djAnalyses = djAnalyses(),
        )
        try {
            documents.write(uri, codec.encode(backup))
        } catch (_: IOException) {
            return false
        }
        history.recordBackup(nowMs)
        return true
    }

    /** The DJ analyses are recomputable, so a DJ store that cannot be read never costs the user the rest of the backup. */
    private suspend fun djAnalyses(): List<String> {
        val dj = djStore ?: return emptyList()
        return try {
            dj.export()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/** What reading a picked backup document gave. */
sealed interface RestoreReading {
    /** A backup worked out against the current library, ready to preview and apply. */
    data class Ready(val plan: RestorePlan) : RestoreReading

    /** The document is not a backup this Sonora can restore. */
    data class Rejected(val error: BackupError) : RestoreReading

    /** The document could not be read at all. */
    data object Unreadable : RestoreReading
}

/** Reads a backup document and plans its restore, writing nothing. */
class ReadBackup(
    private val store: LibraryBackupStore,
    private val playlists: PlaylistRepository,
    private val codec: BackupCodec,
    private val documents: BackupDocuments,
) {
    suspend operator fun invoke(uri: String): RestoreReading {
        val text = try {
            documents.read(uri)
        } catch (_: IOException) {
            return RestoreReading.Unreadable
        }
        return when (val reading = codec.decode(text)) {
            is BackupReading.Rejected -> RestoreReading.Rejected(reading.error)
            is BackupReading.Read -> RestoreReading.Ready(
                RestorePlanner.plan(reading.backup, store.librarySongs(), playlists.playlists().first()),
            )
        }
    }
}

/** What a restore added: [playlistsAdded] new playlists, and the backed-up songs the library lacks. */
data class RestoreReport(
    val playlistsAdded: Int,
    val songsMatched: Int,
    val unmatched: List<SongIdentity>,
    val djAnalyses: DjRestoreOutcome = DjRestoreOutcome.NONE,
)

/**
 * What restoring the DJ analyses did: [restored] were added, [alreadyPresent] tracks kept the analysis they had, and
 * [skipped] records could not be read (damaged, from a newer Sonora) or could not be stored.
 */
data class DjRestoreOutcome(val restored: Int, val alreadyPresent: Int, val skipped: Int) {
    companion object {
        val NONE = DjRestoreOutcome(0, 0, 0)
    }
}

/** Merges a planned restore into the library: playlists first, then the marks naming them, plays, DJ analyses and settings. */
class ApplyRestore(
    private val store: LibraryBackupStore,
    private val settings: SettingsBackupStore,
    private val playlists: PlaylistRepository,
    private val djStore: DjBackupStore? = null,
) {
    suspend operator fun invoke(plan: RestorePlan): RestoreReport {
        val playlistIds = HashMap<Long, Long>()
        var added = 0
        for (playlist in plan.playlists) {
            playlistIds[playlist.backupId] = playlist.existingId
                ?: playlists.create(playlist.name, playlist.description, playlist.entries).also { added++ }
        }
        store.mergeMarks(plan.marksFor(playlistIds))
        store.mergePlays(plan.plays)
        val dj = restoreDjAnalyses(plan.djAnalyses)
        // Last: a restored language or theme recreates the screen.
        settings.merge(plan.settings)
        return RestoreReport(playlistsAdded = added, songsMatched = plan.preview.songsMatched, unmatched = plan.unmatched, djAnalyses = dj)
    }

    /** Without a DJ store, or when it fails, the records count as skipped: the rest of the restore goes on. */
    private suspend fun restoreDjAnalyses(records: List<String>): DjRestoreOutcome {
        if (records.isEmpty()) return DjRestoreOutcome.NONE
        val dj = djStore ?: return DjRestoreOutcome(restored = 0, alreadyPresent = 0, skipped = records.size)
        return try {
            dj.restore(records)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            DjRestoreOutcome(restored = 0, alreadyPresent = 0, skipped = records.size)
        }
    }
}
