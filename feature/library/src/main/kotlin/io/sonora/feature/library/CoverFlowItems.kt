package io.sonora.feature.library

import io.sonora.core.designsystem.component.coverflow.CoverFlowItem
import io.sonora.core.domain.model.Album
import io.sonora.core.domain.model.Artist
import io.sonora.core.domain.model.PlaybackState
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.playlist.ResolvedPlaylist
import io.sonora.feature.library.ui.newestFirst

/** What a Cover Flow cover stands for; its back shows that album's, playlist's or artist's songs. */
internal sealed interface CoverFlowTarget {
    data class Album(val albumId: Long) : CoverFlowTarget

    data class Artist(val artistId: Long, val name: String) : CoverFlowTarget

    data class Playlist(val playlistId: Long) : CoverFlowTarget
}

/** The Library lists as Cover Flow covers, in the lists' own (sorted, pinned) order. Pure, so it is unit-tested. */
internal object CoverFlowItems {

    /** One cover per album, titled with the album and its artist. */
    fun albums(albums: List<Album>): List<CoverFlowItem<CoverFlowTarget>> = albums.map { album ->
        CoverFlowItem(CoverFlowTarget.Album(album.id), listOfNotNull(album.artworkUri), album.title, album.artist)
    }

    /** One cover per album of [songs], in the order the songs list them; albums not in [albums] are skipped. */
    fun songs(songs: List<Track>, albums: List<Album>): List<CoverFlowItem<CoverFlowTarget>> {
        val byId = albums.associateBy { it.id }
        val seen = HashSet<Long>()
        return albums(songs.mapNotNull { song -> byId[song.albumId]?.takeIf { seen.add(it.id) } })
    }

    /** One cover per artist: the front cover of their stacked artwork (the placeholder without), and [albumCount]. */
    fun artists(artists: List<Artist>, albumCount: (Int) -> String): List<CoverFlowItem<CoverFlowTarget>> =
        artists.map { artist ->
            CoverFlowItem(CoverFlowTarget.Artist(artist.id, artist.name), artist.artworkUris.take(1), artist.name, albumCount(artist.albumCount))
        }

    /**
     * Where a Cover Flow of [items] opens: the cover of [target], or the first cover when it is not listed. An artist is
     * matched by id only: a page opened from elsewhere (Search) may carry a differently spelt name.
     */
    fun indexOf(items: List<CoverFlowItem<CoverFlowTarget>>, target: CoverFlowTarget?): Int =
        items.indexOfFirst { it.key.sameAs(target) }.coerceAtLeast(0)

    private fun CoverFlowTarget.sameAs(other: CoverFlowTarget?): Boolean =
        if (this is CoverFlowTarget.Artist && other is CoverFlowTarget.Artist) artistId == other.artistId else this == other

    /** The albums on an artist's back, newest first like the artist's page. */
    fun artistBackAlbums(albums: List<Album>): List<Album> = newestFirst(albums)

    /** One cover per playlist: its own cover (a mosaic or one album cover) and [songCount] of its songs. */
    fun playlists(playlists: List<ResolvedPlaylist>, songCount: (Int) -> String): List<CoverFlowItem<CoverFlowTarget>> =
        playlists.map { playlist ->
            CoverFlowItem(
                CoverFlowTarget.Playlist(playlist.playlist.id),
                playlist.coverArtworkUris,
                playlist.playlist.name,
                songCount(playlist.songCount),
            )
        }
}

/** The Cover Flow's play/pause button. */
enum class CoverFlowPlayButton { PLAY, PAUSE }

/** The play/pause button for [state]: hidden (`null`) with nothing loaded. */
internal fun coverFlowPlayButton(state: PlaybackState): CoverFlowPlayButton? = when {
    state.currentTrack == null -> null
    state.isPlaying -> CoverFlowPlayButton.PAUSE
    else -> CoverFlowPlayButton.PLAY
}

/**
 * The Cover Flow's Now Playing capsule and its playing-row cue: the loaded track ([trackId], [title], [artist], its
 * [artworkUri], an icon without, and [albumId]) and whether it [isPlaying].
 */
internal data class CoverFlowNowPlaying(
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
internal fun coverFlowNowPlaying(state: PlaybackState): CoverFlowNowPlaying? = state.currentTrack?.let {
    CoverFlowNowPlaying(it.id, it.title, it.artist, it.artworkUri, it.albumId, state.isPlaying)
}

/** What the leading slot of a song row on a flipped cover shows. */
internal enum class CoverFlowRowState {
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
internal fun coverFlowRowState(trackId: Long, nowPlaying: CoverFlowNowPlaying?): CoverFlowRowState = when {
    nowPlaying == null || nowPlaying.trackId != trackId -> CoverFlowRowState.NUMBER
    nowPlaying.isPlaying -> CoverFlowRowState.PLAYING
    else -> CoverFlowRowState.PAUSED
}
