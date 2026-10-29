package io.sonora.feature.settings.watch

import io.sonora.core.watchsync.ManifestTrack
import io.sonora.core.watchsync.sender.WatchState

/**
 * The songs of one album on the watch.
 *
 * @property album The album title, or `null` for songs that carry no album tag (they all share one group).
 * @property artist The album artist, or the artist every song shares; `null` when unknown or when [variousArtists].
 * @property variousArtists The songs have no album artist and more than one artist.
 */
data class WatchAlbumGroup(
    val album: String?,
    val artist: String?,
    val variousArtists: Boolean,
    val songs: List<ManifestTrack>,
) {
    val songIds: List<String> get() = songs.map { it.id }
    val sizeBytes: Long get() = songs.sumOf { it.sizeBytes }
}

/** What the watch has room for, as the Watch page shows it. */
data class WatchSpace(
    val totalBytes: Long,
    val freeBytes: Long,
    /** What songs may still use: the free space minus the reserve the watch keeps for itself. */
    val usableBytes: Long,
    /** What Sonora's songs take on the watch. */
    val songsBytes: Long,
    val songCount: Int,
) {
    /** Everything in use on the watch, Sonora's songs and the rest. */
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceIn(0L, maxOf(0L, totalBytes))

    /** [usedBytes] of [totalBytes] as `0..1`; an unknown total shows an empty bar. */
    val usedFraction: Float get() = if (totalBytes <= 0) 0f else (usedBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)

    companion object {
        fun of(state: WatchState) = WatchSpace(
            totalBytes = state.totalBytes,
            freeBytes = state.freeBytes,
            usableBytes = state.usableBytes,
            songsBytes = state.tracks.sumOf { it.sizeBytes },
            songCount = state.tracks.size,
        )
    }
}

/** Groups the watch's songs for the page: by album, in the order a person reads them. Pure. */
object WatchLibrary {

    fun groups(tracks: List<ManifestTrack>): List<WatchAlbumGroup> =
        tracks.groupBy(::groupKey).values
            .map(::group)
            .sortedWith(
                compareBy<WatchAlbumGroup> { it.album == null }
                    .thenBy { it.album.orEmpty().lowercase() }
                    .thenBy { it.artist.orEmpty().lowercase() },
            )

    private fun group(songs: List<ManifestTrack>): WatchAlbumGroup {
        val albumArtist = songs.firstNotNullOfOrNull { it.albumArtist.clean() }
        val artists = songs.mapNotNull { it.artist.clean() }.distinctBy { it.lowercase() }
        return WatchAlbumGroup(
            album = songs.firstNotNullOfOrNull { it.album.clean() },
            artist = albumArtist ?: artists.singleOrNull(),
            variousArtists = albumArtist == null && artists.size > 1,
            songs = songs.sortedWith(
                compareBy<ManifestTrack> { it.discNumber ?: 1 }
                    .thenBy { it.trackNumber ?: Int.MAX_VALUE }
                    .thenBy { it.title.lowercase() }
                    .thenBy { it.id },
            ),
        )
    }

    /** Songs of one album share a key; the same title by two album artists does not. Case and edge spaces do not count. */
    private fun groupKey(track: ManifestTrack): String {
        val album = track.album.clean() ?: return NO_ALBUM
        return album.lowercase() + "|" + track.albumArtist.clean()?.lowercase().orEmpty()
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private const val NO_ALBUM = "\u0000"
}
