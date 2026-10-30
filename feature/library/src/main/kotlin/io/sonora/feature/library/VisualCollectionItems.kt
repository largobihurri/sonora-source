package io.sonora.feature.library

import io.sonora.core.designsystem.component.visualcollection.VisualCollectionItem
import io.sonora.core.domain.model.Album
import io.sonora.core.domain.model.Artist
import io.sonora.core.domain.model.PlaybackState
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.playlist.ResolvedPlaylist
import io.sonora.feature.library.ui.newestFirst

/** What a visual collection cover stands for; its back shows that album's, playlist's or artist's songs. */
internal sealed interface VisualCollectionTarget {
    data class Album(val albumId: Long) : VisualCollectionTarget

    data class Artist(val artistId: Long, val name: String) : VisualCollectionTarget

    data class Playlist(val playlistId: Long) : VisualCollectionTarget
}

/** The Library lists as visual collection covers, in the lists' own (sorted, pinned) order. Pure, so it is unit-tested. */
internal object VisualCollectionItems {

    /** One cover per album, titled with the album and its artist. */
    fun albums(albums: List<Album>): List<VisualCollectionItem<VisualCollectionTarget>> = albums.map { album ->
        VisualCollectionItem(VisualCollectionTarget.Album(album.id), listOfNotNull(album.artworkUri), album.title, album.artist)
    }

    /** One cover per album of [songs], in the order the songs list them; albums not in [albums] are skipped. */
    fun songs(songs: List<Track>, albums: List<Album>): List<VisualCollectionItem<VisualCollectionTarget>> {
        val byId = albums.associateBy { it.id }
        val seen = HashSet<Long>()
        return albums(songs.mapNotNull { song -> byId[song.albumId]?.takeIf { seen.add(it.id) } })
    }

    /** One cover per artist: the front cover of their stacked artwork (the placeholder without), and [albumCount]. */
    fun artists(artists: List<Artist>, albumCount: (Int) -> String): List<VisualCollectionItem<VisualCollectionTarget>> =
        artists.map { artist ->
            VisualCollectionItem(VisualCollectionTarget.Artist(artist.id, artist.name), artist.artworkUris.take(1), artist.name, albumCount(artist.albumCount))
        }

    /**
     * Where a visual collection of [items] opens: the cover of [target], or the first cover when it is not listed. An artist is
     * matched by id only: a page opened from elsewhere (Search) may carry a differently spelt name.
     */
    fun indexOf(items: List<VisualCollectionItem<VisualCollectionTarget>>, target: VisualCollectionTarget?): Int =
        items.indexOfFirst { it.key.sameAs(target) }.coerceAtLeast(0)

    private fun VisualCollectionTarget.sameAs(other: VisualCollectionTarget?): Boolean =
        if (this is VisualCollectionTarget.Artist && other is VisualCollectionTarget.Artist) artistId == other.artistId else this == other

    /** The albums on an artist's back, newest first like the artist's page. */
    fun artistBackAlbums(albums: List<Album>): List<Album> = newestFirst(albums)

    /** One cover per playlist: its own cover (a mosaic or one album cover) and [songCount] of its songs. */
    fun playlists(playlists: List<ResolvedPlaylist>, songCount: (Int) -> String): List<VisualCollectionItem<VisualCollectionTarget>> =
        playlists.map { playlist ->
            VisualCollectionItem(
                VisualCollectionTarget.Playlist(playlist.playlist.id),
                playlist.coverArtworkUris,
                playlist.playlist.name,
                songCount(playlist.songCount),
            )
        }
}

/** The visual collection's play/pause button. */
enum class VisualCollectionPlayButton { PLAY, PAUSE }

/** The play/pause button for [state]: hidden (`null`) with nothing loaded. */
internal fun visualCollectionPlayButton(state: PlaybackState): VisualCollectionPlayButton? = when {
    state.currentTrack == null -> null
    state.isPlaying -> VisualCollectionPlayButton.PAUSE
    else -> VisualCollectionPlayButton.PLAY
}

/**
 * The visual collection's Now Playing capsule and its playing-row cue: the loaded track ([trackId], [title], [artist], its
 * [artworkUri], an icon without, and [albumId]) and whether it [isPlaying].
 */
internal data class VisualCollectionNowPlaying(
    val trackId: Long,
    val title: String,
    val artist: String,
    val artworkUri: String?,
    val albumId: Long,
    val isPlaying: Boolean,
) {
    /** The capsule's main line: "Song · Artist", the song alone without an artist. */
    val caption: String get() = if (artist.isBlank()) title else "$title · $artist"
}

/** The Now Playing capsule for [state]: hidden (`null`) with nothing loaded. */
internal fun visualCollectionNowPlaying(state: PlaybackState): VisualCollectionNowPlaying? = state.currentTrack?.let {
    VisualCollectionNowPlaying(it.id, it.title, it.artist, it.artworkUri, it.albumId, state.isPlaying)
}

/** What the leading slot of a song row on a flipped cover shows. */
internal enum class VisualCollectionRowState {
    /** Its number: it is not the loaded track. */
    NUMBER,

    /** Bouncing bars: it is the loaded track, playing. */
    PLAYING,

    /** Bars at rest: it is the loaded track, paused. */
    PAUSED,
}

/**
 * The leading slot of the row of song [trackId] while [nowPlaying] is loaded. Matched by track id: a song listed twice
 * (a playlist) shows the bars on both rows, as the queue does not say which entry it was started from.
 */
internal fun visualCollectionRowState(trackId: Long, nowPlaying: VisualCollectionNowPlaying?): VisualCollectionRowState = when {
    nowPlaying == null || nowPlaying.trackId != trackId -> VisualCollectionRowState.NUMBER
    nowPlaying.isPlaying -> VisualCollectionRowState.PLAYING
    else -> VisualCollectionRowState.PAUSED
}
