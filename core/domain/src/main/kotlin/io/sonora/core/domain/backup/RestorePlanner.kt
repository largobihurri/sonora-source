package io.sonora.core.domain.backup

import io.sonora.core.domain.library.marks.HiddenItem
import io.sonora.core.domain.library.marks.ItemRef
import io.sonora.core.domain.library.marks.MarkKind
import io.sonora.core.domain.library.marks.PinContext
import io.sonora.core.domain.model.PlaylistEntry
import io.sonora.core.domain.model.PlaylistWithEntries

/**
 * What a restore would bring: [playlists] in the file, how many of its songs the library has or lacks, and how many
 * DJ analysis records ([djAnalyses]) it carries (which of them are new is only known to the DJ store).
 */
data class RestorePreview(val playlists: Int, val songsMatched: Int, val songsNotFound: Int, val djAnalyses: Int = 0)

/**
 * One backed-up playlist as it will be restored: under [name] (suffixed when another playlist has the name) with the
 * [entries] whose songs were found. [existingId] is set when an identical playlist is already there, which is kept
 * instead of adding a copy.
 */
data class PlannedPlaylist(
    val backupId: Long,
    val name: String,
    val description: String,
    val entries: List<PlaylistEntry>,
    val existingId: Long? = null,
)

/** Favourites, pins and hidden items to add, named as the current library names them. */
data class RestoredMarks(
    val favourites: List<BackupFavourite> = emptyList(),
    val pins: List<BackupPin> = emptyList(),
    val hidden: List<HiddenItem> = emptyList(),
)

/**
 * A restore worked out against the current library, before anything is written. Songs are already renamed to the
 * library's keys; playlists in [marks] still carry their backed-up ids until [marksFor] gets the restored ones.
 */
data class RestorePlan(
    val playlists: List<PlannedPlaylist>,
    val marks: RestoredMarks,
    val plays: List<BackupPlays>,
    val settings: List<BackupSettingsFile>,
    val preview: RestorePreview,
    /** The backed-up songs the library lacks (or holds more than once by tags), by artist then title. */
    val unmatched: List<SongIdentity>,
    /** The backup's DJ analysis records, untouched: they name tracks by audio identity, not by library key. */
    val djAnalyses: List<String> = emptyList(),
) {
    /** [marks] with each playlist named by its restored id from [playlistIds]; marks of other playlists are dropped. */
    fun marksFor(playlistIds: Map<Long, Long>): RestoredMarks {
        fun ref(ref: ItemRef): ItemRef? =
            if (ref.kind == MarkKind.PLAYLIST) ref.key.toLongOrNull()?.let(playlistIds::get)?.let(ItemRef::playlist) else ref

        fun context(context: PinContext): PinContext? =
            if (context.id.startsWith(PLAYLIST_CONTEXT)) {
                context.id.removePrefix(PLAYLIST_CONTEXT).toLongOrNull()?.let(playlistIds::get)?.let(PinContext::playlist)
            } else {
                context
            }

        return RestoredMarks(
            favourites = marks.favourites.mapNotNull { favourite -> ref(favourite.ref)?.let { favourite.copy(ref = it) } },
            pins = marks.pins.mapNotNull { pin ->
                val context = context(pin.context) ?: return@mapNotNull null
                ref(pin.ref)?.let { pin.copy(context = context, ref = it) }
            },
            hidden = marks.hidden.mapNotNull { item -> ref(item.ref)?.let { item.copy(ref = it) } },
        )
    }

    private companion object {
        /** How `PinContext.playlist` names a playlist's own list. */
        const val PLAYLIST_CONTEXT = "playlist:"
    }
}

/**
 * Works out how a backup merges into the current library. Nothing current is ever removed: playlists are added (an
 * identical one already there is kept as is; another of the same name gets a numbered suffix), marks are added, play
 * counts only grow, and songs the library lacks are left out and reported.
 */
object RestorePlanner {
    fun plan(backup: LibraryBackup, library: List<SongIdentity>, currentPlaylists: List<PlaylistWithEntries>): RestorePlan {
        val referenced = referencedSongs(backup).map(backup::song)
        val matches = SongMatcher.match(referenced, library)
        fun target(key: String): SongIdentity? = matches[key]?.target
        fun ref(ref: ItemRef): ItemRef? = if (ref.kind == MarkKind.SONG) target(ref.key)?.let { ItemRef.song(it.key) } else ref

        val taken = currentPlaylists.mapTo(mutableListOf()) { it.playlist.name }
        val playlists = backup.playlists.map { playlist ->
            val entries = playlist.entries.mapNotNull { key -> target(key)?.let { PlaylistEntry(0, it.key, it.title, it.artist) } }
            val keys = entries.map { it.trackUri }
            val identical = currentPlaylists.firstOrNull { current ->
                isNamedLike(current.playlist.name, playlist.name) && current.entries.map { it.trackUri } == keys
            }
            if (identical != null) {
                PlannedPlaylist(playlist.id, identical.playlist.name, identical.playlist.description, entries, identical.playlist.id)
            } else {
                val name = freeName(playlist.name, taken).also { taken += it }
                PlannedPlaylist(playlist.id, name, playlist.description, entries)
            }
        }

        val marks = RestoredMarks(
            favourites = backup.favourites.mapNotNull { favourite -> ref(favourite.ref)?.let { favourite.copy(ref = it) } }
                .distinctBy { it.ref },
            pins = backup.pins.mapNotNull { pin -> ref(pin.ref)?.let { pin.copy(ref = it) } }.distinctBy { it.context to it.ref },
            hidden = backup.hidden.mapNotNull { item -> ref(item.ref)?.let { item.copy(ref = it) } }.distinctBy { it.ref },
        )
        // Two backed-up keys can be one library song now (a file that moved, then was copied back): their plays add up.
        val plays = backup.plays
            .mapNotNull { plays -> target(plays.key)?.let { plays.copy(key = it.key) } }
            .groupBy { it.key }
            .map { (key, group) -> BackupPlays(key, group.sumOf { it.count }, group.maxOf { it.lastPlayedMs }) }

        val (found, missing) = referenced.partition { target(it.key) != null }
        return RestorePlan(
            playlists = playlists,
            marks = marks,
            plays = plays,
            settings = BackupSettingsPolicy.filter(backup.settings),
            preview = RestorePreview(backup.playlists.size, songsMatched = found.size, songsNotFound = missing.size, djAnalyses = backup.djAnalyses.size),
            unmatched = missing.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER, SongIdentity::artist).thenBy(String.CASE_INSENSITIVE_ORDER, SongIdentity::title),
            ),
            djAnalyses = backup.djAnalyses,
        )
    }

    /** [name], or `name (2)`, `name (3)`... whichever no playlist in [taken] (compared ignoring case) has. */
    fun freeName(name: String, taken: Collection<String>): String {
        val lowered = taken.mapTo(HashSet()) { it.lowercase() }
        if (name.lowercase() !in lowered) return name
        return generateSequence(2) { it + 1 }.map { "$name ($it)" }.first { it.lowercase() !in lowered }
    }

    /** Every song key the backup names: its song table, playlist entries, song marks and play counts, once each. */
    private fun referencedSongs(backup: LibraryBackup): Set<String> = buildSet {
        backup.songs.mapTo(this) { it.key }
        backup.playlists.forEach { addAll(it.entries) }
        backup.favourites.filter { it.ref.kind == MarkKind.SONG }.mapTo(this) { it.ref.key }
        backup.pins.filter { it.ref.kind == MarkKind.SONG }.mapTo(this) { it.ref.key }
        backup.hidden.filter { it.ref.kind == MarkKind.SONG }.mapTo(this) { it.ref.key }
        backup.plays.mapTo(this) { it.key }
    }

    /** Whether [current] is [name] or a suffixed copy of it (`name (2)`), ignoring case. */
    private fun isNamedLike(current: String, name: String): Boolean =
        current.equals(name, ignoreCase = true) ||
            (current.startsWith("$name (", ignoreCase = true) && current.endsWith(")") &&
                current.substring(name.length + 2, current.length - 1).toIntOrNull() != null)
}
