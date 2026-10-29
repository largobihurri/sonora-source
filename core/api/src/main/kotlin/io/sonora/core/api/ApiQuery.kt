package io.sonora.core.api

/** Which rows a query asks for: [limit] and [offset] already checked. */
data class PageRequest(val limit: Int = ApiLimits.DEFAULT_PAGE_SIZE, val offset: Int = 0)

/** A validated read. A parser builds it from a path and parameters ([QueryParser]); [ApiQueryEngine] answers it. */
sealed interface ApiQuery {
    data object State : ApiQuery
    data class Queue(val page: PageRequest) : ApiQuery

    /** Every song by title, or the songs matching [text] (title, artist or album) when it is not null. */
    data class Tracks(val text: String?, val page: PageRequest) : ApiQuery
    data class Albums(val page: PageRequest) : ApiQuery
    data class Artists(val page: PageRequest) : ApiQuery
    data class Playlists(val page: PageRequest) : ApiQuery
    data class AlbumTracks(val albumId: Long, val page: PageRequest) : ApiQuery
    data class ArtistTracks(val artistId: Long, val page: PageRequest) : ApiQuery
    data class PlaylistTracks(val playlistId: Long, val page: PageRequest) : ApiQuery
}

sealed interface QueryParseResult {
    data class Parsed(val query: ApiQuery) : QueryParseResult
    data class Rejected(val error: ApiError) : QueryParseResult
}

/**
 * Builds an [ApiQuery] from the path segments after the authority and the query parameters (as a map, so the
 * provider adapter can fill it from a `Uri` or from selection arguments).
 *
 * | Path | Parameters |
 * |---|---|
 * | `state` | none |
 * | `queue`, `albums`, `artists`, `playlists` | `limit`, `offset` |
 * | `tracks` | `q` (optional search text), `limit`, `offset` |
 * | `albums/<id>/tracks`, `artists/<id>/tracks`, `playlists/<id>/tracks` | `limit`, `offset` |
 *
 * `limit` defaults to [ApiLimits.DEFAULT_PAGE_SIZE] and is held to [ApiLimits.MAX_PAGE_SIZE]; `offset` defaults to 0.
 */
object QueryParser {

    fun parse(segments: List<String>, params: Map<String, Any?> = emptyMap()): QueryParseResult = try {
        QueryParseResult.Parsed(build(segments, params))
    } catch (failure: ApiFailure) {
        QueryParseResult.Rejected(failure.error)
    }

    private fun build(segments: List<String>, params: Map<String, Any?>): ApiQuery {
        if (segments == listOf("state")) return ApiQuery.State
        val shape = shapeOf(segments) ?: throw ApiFailure(ApiError.UnknownQuery)
        rejectLocations(params)
        val args = ArgumentReader(params)
        val page = PageRequest(
            limit = args.int("limit", 1..Int.MAX_VALUE, default = ApiLimits.DEFAULT_PAGE_SIZE).coerceAtMost(ApiLimits.MAX_PAGE_SIZE),
            offset = args.int("offset", 0..Int.MAX_VALUE, default = 0),
        )
        return when (shape) {
            Shape.QUEUE -> ApiQuery.Queue(page)
            Shape.TRACKS -> ApiQuery.Tracks(searchText(args), page)
            Shape.ALBUMS -> ApiQuery.Albums(page)
            Shape.ARTISTS -> ApiQuery.Artists(page)
            Shape.PLAYLISTS -> ApiQuery.Playlists(page)
            Shape.ALBUM_TRACKS -> ApiQuery.AlbumTracks(idOf(segments), page)
            Shape.ARTIST_TRACKS -> ApiQuery.ArtistTracks(idOf(segments), page)
            Shape.PLAYLIST_TRACKS -> ApiQuery.PlaylistTracks(idOf(segments), page)
        }
    }

    private enum class Shape { QUEUE, TRACKS, ALBUMS, ARTISTS, PLAYLISTS, ALBUM_TRACKS, ARTIST_TRACKS, PLAYLIST_TRACKS }

    private fun shapeOf(segments: List<String>): Shape? = when (segments.size) {
        1 -> when (segments[0]) {
            "queue" -> Shape.QUEUE
            "tracks" -> Shape.TRACKS
            "albums" -> Shape.ALBUMS
            "artists" -> Shape.ARTISTS
            "playlists" -> Shape.PLAYLISTS
            else -> null
        }
        3 -> if (segments[2] != "tracks") null else when (segments[0]) {
            "albums" -> Shape.ALBUM_TRACKS
            "artists" -> Shape.ARTIST_TRACKS
            "playlists" -> Shape.PLAYLIST_TRACKS
            else -> null
        }
        else -> null
    }

    private fun idOf(segments: List<String>): Long = ArgumentReader(mapOf("id" to segments[1])).id("id")

    /** The trimmed `q`, or null when it is absent or blank (no search). */
    private fun searchText(args: ArgumentReader): String? {
        val text = args.stringOrNull("q")?.trim()?.ifEmpty { null } ?: return null
        if (text.length > ApiLimits.MAX_QUERY_LENGTH) {
            throw ApiFailure(ApiError.ArgumentOutOfRange("q", "at most ${ApiLimits.MAX_QUERY_LENGTH} characters"))
        }
        return text
    }
}
