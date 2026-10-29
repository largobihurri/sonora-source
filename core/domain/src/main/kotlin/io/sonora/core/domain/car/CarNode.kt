package io.sonora.core.domain.car

import io.sonora.core.domain.model.Track

/** Fixed titles the car shows in the user's language (the session localises them). */
enum class CarLabel { PLAYLISTS, ALBUMS, ARTISTS, SONGS, FAVOURITES, RECENTLY_ADDED, ALL_SONGS }

/** A node's title: the library's own text, or a [CarLabel] to localise. */
sealed interface CarTitle {
    data class Text(val text: String) : CarTitle
    data class Label(val label: CarLabel) : CarTitle
}

/** The heading a node is grouped under in a mixed list (Favourites, search results). */
enum class CarGroup { SONGS, ALBUMS, ARTISTS, PLAYLISTS }

/**
 * One item of the car's library tree. A [CarMediaId.Song] is playable and carries its [track]; every other id is a
 * browsable folder whose children show in [CarStyle.childrenOf] its id.
 */
data class CarNode(
    val id: CarMediaId,
    val title: CarTitle,
    val subtitle: String? = null,
    /** The library's artwork URI (a cache file or a folder image); the session serves it to the car. */
    val artworkUri: String? = null,
    val group: CarGroup? = null,
    val track: Track? = null,
) {
    val isPlayable: Boolean get() = id is CarMediaId.Song
}

/** What a tapped song plays: its whole list, from the song. */
data class CarQueue(val tracks: List<Track>, val startIndex: Int) {
    /** This queue without the songs [isReadable] rejects; `null` when the song to start with is one of them. */
    fun readable(isReadable: (Track) -> Boolean): CarQueue? {
        if (!isReadable(tracks[startIndex])) return null
        val kept = tracks.withIndex().filter { (i, track) -> i == startIndex || isReadable(track) }
        return CarQueue(kept.map { it.value }, kept.indexOfFirst { it.index == startIndex })
    }
}

/** Whether the car has anything to play: songs indexed, and at least one of them readable. */
enum class CarLibraryStatus { READY, EMPTY, NO_ACCESS }
