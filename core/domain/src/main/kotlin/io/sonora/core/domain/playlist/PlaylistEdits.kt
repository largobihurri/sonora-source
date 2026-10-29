package io.sonora.core.domain.playlist

import io.sonora.core.domain.model.PlaylistEntry
import io.sonora.core.domain.model.Track

/** Pure edits of a playlist's entry list; the list order is the playlist order. */
object PlaylistEdits {

    /** [entries] followed by new entries for [tracks], in that order. Songs already listed are added again. */
    fun append(entries: List<PlaylistEntry>, tracks: List<Track>): List<PlaylistEntry> =
        entries + tracks.map(PlaylistEntry::of)

    /** [entries] without entry [entryId]; unchanged when it is not there. */
    fun remove(entries: List<PlaylistEntry>, entryId: Long): List<PlaylistEntry> =
        if (entries.none { it.id == entryId }) entries else entries.filterNot { it.id == entryId }

    /** [entries] with entry [entryId] moved to [toIndex] (clamped to the list); unchanged when it is not there. */
    fun move(entries: List<PlaylistEntry>, entryId: Long, toIndex: Int): List<PlaylistEntry> {
        val from = entries.indexOfFirst { it.id == entryId }
        if (from < 0) return entries
        val to = toIndex.coerceIn(0, entries.lastIndex)
        if (to == from) return entries
        return entries.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** A playlist name as stored: trimmed, or `null` when nothing is left. */
    fun cleanName(name: String): String? = name.trim().ifEmpty { null }
}
