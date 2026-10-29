package io.sonora.core.domain.backup

import io.sonora.core.domain.library.marks.HiddenItem
import io.sonora.core.domain.library.marks.ItemRef
import io.sonora.core.domain.library.marks.PinContext
import io.sonora.core.domain.port.DjBackupStore
import java.util.Locale

/**
 * Everything a Sonora backup file holds: the user's playlists, favourites, pins, hidden items, play counts and app
 * settings. Songs are named by their document key (the file's stable URI) everywhere; [songs] holds each referenced
 * song's tags once, so a restore on a library whose keys changed can still find the song by its tags. Folder access
 * grants and secrets are never part of it.
 *
 * [djAnalyses] (format version 2) are the DJ analyses of the permanent DJ store as opaque base64 text, one per track:
 * what the DJ store's [DjBackupStore] exports and restores. This module never looks inside them, and a backup holds
 * no waveforms (they are recomputed from the audio).
 */
data class LibraryBackup(
    val createdAtMs: Long,
    val songs: List<SongIdentity> = emptyList(),
    val playlists: List<BackupPlaylist> = emptyList(),
    val favourites: List<BackupFavourite> = emptyList(),
    val pins: List<BackupPin> = emptyList(),
    val hidden: List<HiddenItem> = emptyList(),
    val plays: List<BackupPlays> = emptyList(),
    val settings: List<BackupSettingsFile> = emptyList(),
    val djAnalyses: List<String> = emptyList(),
) {
    /** The song with [key] from [songs]; a key-only identity (no tags to fall back on) when the table lacks it. */
    fun song(key: String): SongIdentity = songsByKey[key] ?: SongIdentity(key, title = "", artist = "", album = "", durationMs = 0)

    private val songsByKey: Map<String, SongIdentity> by lazy { songs.associateBy { it.key } }

    companion object {
        /** The `format` id of a Sonora backup file. Persisted: never change it. */
        const val FORMAT_ID = "sonora-backup"

        /** The newest format version this Sonora writes and reads: 2 added [djAnalyses]; a version 1 file reads as having none. */
        const val VERSION = 2
    }
}

/** A song as a backup and the library name it: its document [key] plus the tags that identify it when the key fails. */
data class SongIdentity(val key: String, val title: String, val artist: String, val album: String, val durationMs: Long)

/** One playlist as backed up: [id] is the playlist's id when it was backed up, [entries] its songs' keys in order. */
data class BackupPlaylist(val id: Long, val name: String, val description: String, val entries: List<String>)

data class BackupFavourite(val ref: ItemRef, val addedAtMs: Long)

data class BackupPin(val context: PinContext, val ref: ItemRef, val pinnedAtMs: Long)

/** Plays of the song with [key]: how many and when last, epoch milliseconds. */
data class BackupPlays(val key: String, val count: Int, val lastPlayedMs: Long)

/** The values stored in one settings file ([name], e.g. `sonora_settings`), by key. */
data class BackupSettingsFile(val name: String, val values: Map<String, SettingValue>)

/** A typed settings value, as the settings files store it. */
sealed interface SettingValue {
    data class BooleanValue(val value: Boolean) : SettingValue
    data class IntValue(val value: Int) : SettingValue
    data class LongValue(val value: Long) : SettingValue
    data class FloatValue(val value: Float) : SettingValue
    data class DoubleValue(val value: Double) : SettingValue
    data class StringValue(val value: String) : SettingValue
    data class StringSetValue(val value: Set<String>) : SettingValue
}

/** Why a file cannot be restored. */
sealed interface BackupError {
    /** Another program's file, or no format id at all. */
    data class OtherFormat(val formatId: String?) : BackupError

    /** Written by a newer Sonora in a version this one cannot read. */
    data class NewerVersion(val version: Int) : BackupError

    /** Not JSON, or missing or wrongly typed fields. */
    data class Malformed(val reason: String) : BackupError
}

/** The result of decoding a backup file's text. */
sealed interface BackupReading {
    data class Read(val backup: LibraryBackup) : BackupReading

    data class Rejected(val error: BackupError) : BackupReading
}

/** Which settings a backup carries: never a secret and never a folder access grant, whatever file holds them. */
object BackupSettingsPolicy {
    private val EXCLUDED_WORDS = listOf("password", "secret", "token", "session", "credential", "apikey", "api_key", "uri", "grant")

    fun includes(key: String): Boolean {
        val lower = key.lowercase()
        return EXCLUDED_WORDS.none { it in lower }
    }

    /** [files] without the excluded keys. */
    fun filter(files: List<BackupSettingsFile>): List<BackupSettingsFile> =
        files.map { file -> file.copy(values = file.values.filterKeys(::includes)) }
}

/** The backup file's name for [year]-[month]-[day]: `Sonora-backup-20261004.json`. */
object BackupNaming {
    const val MIME_TYPE = "application/json"

    fun fileName(year: Int, month: Int, day: Int): String = String.format(Locale.ROOT, "Sonora-backup-%04d%02d%02d.json", year, month, day)
}
