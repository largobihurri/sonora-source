package io.sonora.core.domain.stats

import io.sonora.core.domain.library.plays.TrackPlays
import io.sonora.core.domain.model.Track

/** The time ranges the statistics cover, each ending now: the last 7, 30 or 365 days, or everything recorded. */
enum class StatsRange(private val days: Long?) {
    WEEK(7),
    MONTH(30),
    YEAR(365),
    ALL_TIME(null),
    ;

    /** The first instant the range includes (playbacks started at or after it), `null` for all time. */
    fun startMs(nowMs: Long): Long? = days?.let { nowMs - it * DAY_MS }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}

/** One place in a top list: a song (by uri), album (by id) or artist (by name), with what it added up to. */
data class RankedItem(
    val key: String,
    val title: String,
    val subtitle: String?,
    val artworkUri: String?,
    val plays: Int,
    val listenedMs: Long,
)

/** What a range added up to: time heard, plays, and the top artists, albums and songs. */
data class ListeningStats(
    val listenedMs: Long = 0L,
    val plays: Int = 0,
    val artists: List<RankedItem> = emptyList(),
    val albums: List<RankedItem> = emptyList(),
    val songs: List<RankedItem> = emptyList(),
)

/**
 * Adds up [listening] (per song, already limited to [range]) into [ListeningStats], with the top [limit] of each kind.
 *
 * Play counts from before listening was recorded ([playCounts], every play ever) have no times, so they count toward
 * [StatsRange.ALL_TIME] only: there a song's plays are the larger of its count and its recorded plays, as both record
 * the same plays from then on. The totals include songs no longer in the library; the top lists name songs through
 * [tracks], so they leave those out.
 *
 * Ranked by plays, then time heard, then title. Artists group a song's artist case- and space-insensitively, albums by
 * album; both take their name, subtitle and artwork from their most played song. A song only skipped through (heard,
 * no play counted) adds its time to its artist and album, but nothing without a play makes a top list.
 */
fun listeningStats(
    range: StatsRange,
    listening: List<TrackListening>,
    playCounts: Map<String, TrackPlays>,
    tracks: List<Track>,
    limit: Int = TOP_LIMIT,
): ListeningStats {
    val perSong = HashMap<String, TrackListening>()
    for (song in listening) perSong[song.trackUri] = song
    if (range == StatsRange.ALL_TIME) {
        for ((uri, counted) in playCounts) {
            val recorded = perSong[uri]
            perSong[uri] = TrackListening(uri, maxOf(counted.count, recorded?.plays ?: 0), recorded?.listenedMs ?: 0L)
        }
    }
    val heard = perSong.values.filter { it.plays > 0 || it.listenedMs > 0 }
    val byUri = tracks.associateBy { it.uri }
    val songs = heard.mapNotNull { song ->
        byUri[song.trackUri]?.let { track ->
            track to RankedItem(track.uri, track.title, track.artist.trim().ifEmpty { null }, track.artworkUri, song.plays, song.listenedMs)
        }
    }.sortedWith(compareBy(RANKING) { it.second })

    val artists = songs.filter { it.first.artist.isNotBlank() }
        .groupBy { it.first.artist.trim().lowercase() }
        .map { (key, group) ->
            val top = group.first().first
            group.sum(key, top.artist.trim(), null, top.artworkUri)
        }
    val albums = songs.groupBy { it.first.albumId }
        .map { (albumId, group) ->
            val top = group.first().first
            val by = top.albumArtist?.takeIf { it.isNotBlank() } ?: top.artist
            group.sum(albumId.toString(), top.album, by.trim().ifEmpty { null }, top.artworkUri)
        }
    return ListeningStats(
        listenedMs = heard.sumOf { it.listenedMs },
        plays = heard.sumOf { it.plays },
        artists = artists.top(limit),
        albums = albums.top(limit),
        songs = songs.map { it.second }.top(limit),
    )
}

/** The first [limit] played items, ranked. */
private fun List<RankedItem>.top(limit: Int) = filter { it.plays > 0 }.sortedWith(RANKING).take(limit)

private fun List<Pair<Track, RankedItem>>.sum(key: String, title: String, subtitle: String?, artworkUri: String?) =
    RankedItem(key, title, subtitle, artworkUri, sumOf { it.second.plays }, sumOf { it.second.listenedMs })

private val RANKING: Comparator<RankedItem> = compareByDescending<RankedItem> { it.plays }
    .thenByDescending { it.listenedMs }
    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
    .thenBy { it.key }

/** How many artists, albums and songs each top list shows. */
const val TOP_LIMIT = 10
