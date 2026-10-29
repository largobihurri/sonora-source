package io.sonora.core.domain.model

/** Sort keys for track lists. */
enum class TrackSort { TITLE, ALBUM, ARTIST }

/** Pure library helpers shared by adapters and tests. */
object LibraryQuery {

    /** Case-insensitive "contains" match on title, artist or album. Blank query matches nothing. */
    fun search(tracks: List<Track>, query: String): List<Track> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return tracks.filter {
            it.title.contains(q, ignoreCase = true) ||
                it.artist.contains(q, ignoreCase = true) ||
                it.album.contains(q, ignoreCase = true)
        }
    }

    fun sort(tracks: List<Track>, sort: TrackSort): List<Track> {
        val byTitle = compareBy<Track, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
        val comparator = when (sort) {
            TrackSort.TITLE -> byTitle
            TrackSort.ALBUM -> compareBy<Track, String>(String.CASE_INSENSITIVE_ORDER) { it.album }
                .thenBy { it.discNumber ?: 0 }.thenBy { it.trackNumber ?: Int.MAX_VALUE }.then(byTitle)
            TrackSort.ARTIST -> compareBy<Track, String>(String.CASE_INSENSITIVE_ORDER) { it.artist }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.album }
                .thenBy { it.trackNumber ?: Int.MAX_VALUE }.then(byTitle)
        }
        return tracks.sortedWith(comparator)
    }

    /** Album track order: disc, then track number, then title. */
    fun albumOrder(tracks: List<Track>): List<Track> =
        tracks.sortedWith(
            compareBy<Track> { it.discNumber ?: 0 }
                .thenBy { it.trackNumber ?: Int.MAX_VALUE }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
        )

    fun albums(tracks: List<Track>): List<Album> =
        tracks.groupBy { it.albumId }.map { (albumId, group) ->
            val first = group.first()
            Album(
                id = albumId,
                title = first.album,
                artist = first.albumArtist?.takeIf { it.isNotBlank() } ?: first.artist,
                year = group.mapNotNull { it.year }.maxOrNull(),
                trackCount = group.size,
                artworkUri = first.artworkUri,
                artistId = first.artistId,
                dateAddedSec = group.maxOf { it.dateAddedSec },
            )
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })

    /**
     * Artists of [tracks] with their album covers ([ArtistCovers]). An album's plays are the sum of [plays] (by track
     * uri) over all its songs in [tracks], other artists' songs on it included; without plays, newest albums lead.
     */
    fun artists(tracks: List<Track>, plays: Map<String, Int> = emptyMap()): List<Artist> {
        val albums = tracks.groupBy { it.albumId }.mapValues { (albumId, group) ->
            AlbumCover(
                albumId = albumId,
                artworkUri = group.firstNotNullOfOrNull { it.artworkUri },
                plays = group.sumOf { plays[it.uri] ?: 0 },
                dateAddedSec = group.maxOf { it.dateAddedSec },
            )
        }
        return tracks.groupBy { it.artist }.map { (name, group) ->
            val albumIds = group.map { it.albumId }.distinct()
            Artist(
                id = group.first().artistId,
                name = name,
                albumCount = albumIds.size,
                trackCount = group.size,
                dateAddedSec = group.maxOf { it.dateAddedSec },
                artworkUris = ArtistCovers.pick(albumIds.map(albums::getValue)),
            )
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    fun recentlyAdded(albums: List<Album>, limit: Int): List<Album> =
        albums.sortedByDescending { it.dateAddedSec }.take(limit)
}
