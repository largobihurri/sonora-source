package io.sonora.core.domain.port

import io.sonora.core.domain.backup.BackupFavourite
import io.sonora.core.domain.backup.BackupPin
import io.sonora.core.domain.backup.BackupPlays
import io.sonora.core.domain.backup.BackupReading
import io.sonora.core.domain.backup.BackupSettingsFile
import io.sonora.core.domain.backup.DjRestoreOutcome
import io.sonora.core.domain.backup.LibraryBackup
import io.sonora.core.domain.backup.RestoredMarks
import io.sonora.core.domain.backup.SongIdentity
import io.sonora.core.domain.library.marks.HiddenItem
import java.io.IOException
import kotlinx.coroutines.flow.Flow

/** The library's songs, marks and play counts as a backup reads and a restore merges them. */
interface LibraryBackupStore {
    /** Every indexed song, hidden ones included. */
    suspend fun librarySongs(): List<SongIdentity>

    suspend fun favourites(): List<BackupFavourite>

    suspend fun pins(): List<BackupPin>

    suspend fun hidden(): List<HiddenItem>

    suspend fun plays(): List<BackupPlays>

    /** Adds [marks]; every current mark stays as it is (a hidden item keeps its own names). */
    suspend fun mergeMarks(marks: RestoredMarks)

    /** Raises each song's play count and last play to [plays]' where those are higher; never lowers one. */
    suspend fun mergePlays(plays: List<BackupPlays>)
}

/** The app's settings files, as typed key-value pairs. */
interface SettingsBackupStore {
    /** Every value stored in each settings file a backup carries. */
    suspend fun read(): List<BackupSettingsFile>

    /** Writes [files]' values over the stored ones; keys a file does not name stay as they are. */
    suspend fun merge(files: List<BackupSettingsFile>)
}

/** A backup file's text format. */
interface BackupCodec {
    fun encode(backup: LibraryBackup): String

    fun decode(text: String): BackupReading
}

/** The documents a backup is written to and restored from, named by their content URIs. */
interface BackupDocuments {
    /** Replaces [uri]'s content with [text]. */
    @Throws(IOException::class)
    suspend fun write(uri: String, text: String)

    /** [uri]'s text; fails for a file too large to be a backup. */
    @Throws(IOException::class)
    suspend fun read(uri: String): String
}

/** When the last backup was made. */
interface BackupHistory {
    /** Epoch milliseconds of the last backup; `null` before the first. */
    val lastBackupMs: Flow<Long?>

    suspend fun recordBackup(atMs: Long)
}

/**
 * The DJ store's share of a backup (backup format version 2): its analyses as opaque text records, so neither the
 * backup model nor the backup file format knows what is inside. Implemented by the DJ store module and bound
 * optionally, so a build without DJ mode still backs up and restores everything else.
 *
 * A record never carries a waveform: restoring one leaves that track needing its waveform, which the analyser
 * recomputes from the audio.
 */
interface DjBackupStore {
    /** One record per analysed track that can be backed up (rows this build cannot read are left out). */
    suspend fun export(): List<String>

    /**
     * Adds the analyses [records] hold. An analysis the store already has is never overwritten, and a record that
     * cannot be read is skipped and counted, never a failure of the restore.
     */
    suspend fun restore(records: List<String>): DjRestoreOutcome
}
