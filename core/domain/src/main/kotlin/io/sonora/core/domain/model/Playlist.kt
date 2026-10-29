package io.sonora.core.domain.model

/** A user playlist's own details; its songs are [PlaylistEntry]s. Times are epoch milliseconds. */
data class Playlist(
    val id: Long,
    val name: String,
    val description: String = "",
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
)

/**
 * One song in a playlist. The song is named by its file's stable [trackUri] (not the library's row id), so a rescan
 * keeps it; [title] and [artist] are a snapshot taken when it was added, shown once the file is gone. [id] tells
 * entries apart, as the same song can be in a playlist more than once; it is 0 until the entry is stored.
 */
data class PlaylistEntry(val id: Long, val trackUri: String, val title: String, val artist: String) {
    companion object {
        /** A new, not yet stored entry for [track]. */
        fun of(track: Track) = PlaylistEntry(id = 0L, trackUri = track.uri, title = track.title, artist = track.artist)
    }
}

/** A playlist with its entries in playlist order. */
data class PlaylistWithEntries(val playlist: Playlist, val entries: List<PlaylistEntry>)
