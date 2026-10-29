package io.sonora.core.domain.playlist

import io.sonora.core.domain.model.Playlist
import io.sonora.core.domain.model.PlaylistEntry
import io.sonora.core.domain.model.PlaylistWithEntries
import io.sonora.core.domain.model.Track

/** One playlist entry with its library [track]; `null` when its file is no longer in the library (unavailable). */
data class PlaylistItem(val entry: PlaylistEntry, val track: Track?) {
    val isAvailable: Boolean get() = track != null
}

/** A playlist whose entries were matched against the library. */
data class ResolvedPlaylist(val playlist: Playlist, val items: List<PlaylistItem>) {
    /** Every entry, unavailable ones included. */
    val songCount: Int get() = items.size

    /** What Play and Shuffle play: the available songs in playlist order. */
    val playableTracks: List<Track> = items.mapNotNull { it.track }

    val totalDurationMs: Long get() = playableTracks.sumOf { it.durationMs }

    /**
     * The cover: the first [MOSAIC_SIZE] distinct album covers as a 2x2 mosaic, or the first cover alone when there
     * are fewer; empty (a placeholder) when no available song has a cover.
     */
    val coverArtworkUris: List<String> = playableTracks.mapNotNull { it.artworkUri }.distinct().let { covers ->
        if (covers.size >= MOSAIC_SIZE) covers.take(MOSAIC_SIZE) else covers.take(1)
    }

    companion object {
        const val MOSAIC_SIZE = 4
    }
}

/** Matches playlist entries with library tracks by the file's URI. */
object PlaylistResolution {
    fun resolve(playlist: PlaylistWithEntries, libraryByUri: Map<String, Track>) = ResolvedPlaylist(
        playlist.playlist,
        playlist.entries.map { PlaylistItem(it, libraryByUri[it.trackUri]) },
    )
}
