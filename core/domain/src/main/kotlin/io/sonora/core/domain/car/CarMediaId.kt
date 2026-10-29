package io.sonora.core.domain.car

import java.net.URLDecoder
import java.net.URLEncoder

/** The four tabs Android Auto shows at the top of Sonora's library (its default limit for root children). */
enum class CarTab(val key: String) {
    PLAYLISTS("playlists"),
    ALBUMS("albums"),
    ARTISTS("artists"),
    SONGS("songs"),
}

/**
 * A node of the library tree the car browses, as a media id. Ids are `/`-separated paths and never hold a `:`, so the
 * session never mistakes one for a queue entry's id (`track:item`), and never hold a URI. A playable [Song] carries the
 * list it was tapped in (its context, after `@`) and its position there, so the whole list plays from it.
 */
sealed interface CarMediaId {
    val value: String

    data object Root : CarMediaId {
        override val value = "root"
    }

    data class Tab(val tab: CarTab) : CarMediaId {
        override val value get() = "tab/${tab.key}"
    }

    /** Favourite songs, albums and playlists (the app's Favourites smart playlist). */
    data object Favourites : CarMediaId {
        override val value = "favourites"
    }

    data object RecentlyAdded : CarMediaId {
        override val value = "recent"
    }

    data class Playlist(val id: Long) : CarMediaId {
        override val value get() = "playlist/$id"
    }

    data class Album(val id: Long) : CarMediaId {
        override val value get() = "album/$id"
    }

    data class Artist(val id: Long) : CarMediaId {
        override val value get() = "artist/$id"
    }

    /** Every song of an artist (as track artist). */
    data class ArtistSongs(val id: Long) : CarMediaId {
        override val value get() = "artist/$id/songs"
    }

    /** One letter of a long Albums, Artists or Songs tab ([CarBuckets]). */
    data class Bucket(val tab: CarTab, val key: String) : CarMediaId {
        override val value get() = "${tab.key}/$key"
    }

    /** The songs matching [query]; the query is percent-encoded in the id. */
    data class Search(val query: String) : CarMediaId {
        override val value get() = "search/${URLEncoder.encode(query, Charsets.UTF_8)}"
    }

    /** Track [trackId] at [position] of [context] (one of the [isSongContext] lists). */
    data class Song(val trackId: Long, val position: Int, val context: CarMediaId) : CarMediaId {
        override val value get() = "song/$trackId/$position@${context.value}"
    }

    companion object {
        /** The id [value] names, or `null` when it names nothing Sonora serves (stale, foreign or malformed). */
        fun parse(value: String): CarMediaId? {
            if (value.startsWith(SONG_PREFIX)) return parseSong(value)
            val parts = value.split('/')
            val id = parts.getOrNull(1)?.toLongOrNull()
            return when (parts.size) {
                1 -> when (value) {
                    Root.value -> Root
                    Favourites.value -> Favourites
                    RecentlyAdded.value -> RecentlyAdded
                    else -> null
                }
                2 -> when (parts[0]) {
                    "tab" -> CarTab.entries.firstOrNull { it.key == parts[1] }?.let(::Tab)
                    "playlist" -> id?.let(::Playlist)
                    "album" -> id?.let(::Album)
                    "artist" -> id?.let(::Artist)
                    "search" -> runCatching { Search(URLDecoder.decode(parts[1], Charsets.UTF_8)) }.getOrNull()
                    else -> CarTab.entries.firstOrNull { it.key == parts[0] && it != CarTab.PLAYLISTS }
                        ?.takeIf { CarBuckets.isKey(parts[1]) }
                        ?.let { Bucket(it, parts[1]) }
                }
                3 -> if (parts[0] == "artist" && parts[2] == "songs") id?.let(::ArtistSongs) else null
                else -> null
            }
        }

        private const val SONG_PREFIX = "song/"

        private fun parseSong(value: String): Song? {
            val (trackId, position) = value.removePrefix(SONG_PREFIX).substringBefore('@', "").split('/')
                .takeIf { it.size == 2 } ?: return null
            val context = parse(value.substringAfter('@'))?.takeIf(::isSongContext) ?: return null
            return Song(
                trackId = trackId.toLongOrNull() ?: return null,
                position = position.toIntOrNull()?.takeIf { it >= 0 } ?: return null,
                context = context,
            )
        }

        /** Lists a song can play from: the list itself becomes the queue. */
        fun isSongContext(id: CarMediaId): Boolean = when (id) {
            is Tab -> id.tab == CarTab.SONGS
            CarMediaId.Favourites, is Playlist, is Album, is ArtistSongs, is Search -> true
            else -> false
        }
    }
}

/** How a browsable node's children show in the car. */
enum class CarStyle {
    LIST,
    GRID,
    ;

    companion object {
        /** Albums and artists show their covers in a grid; songs, playlists and letters show as lists. */
        fun childrenOf(id: CarMediaId): CarStyle = when (id) {
            is CarMediaId.Tab -> if (id.tab == CarTab.ALBUMS || id.tab == CarTab.ARTISTS) GRID else LIST
            is CarMediaId.Bucket -> if (id.tab == CarTab.SONGS) LIST else GRID
            CarMediaId.RecentlyAdded, is CarMediaId.Artist -> GRID
            else -> LIST
        }
    }
}
