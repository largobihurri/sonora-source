package io.sonora.core.playback.api

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import io.sonora.core.api.API_VERSION
import io.sonora.core.api.ApiError
import io.sonora.core.api.ApiQueryResult
import io.sonora.core.api.Page
import io.sonora.core.api.StateRow
import io.sonora.core.api.TrackRow

/**
 * The `Cursor` side of `query`. Column names are part of the API (see `API_VERSION`). Booleans are 0 or 1, ids and
 * counts are integers, a value the library does not have is null. A paged query also puts `total`, `limit`, `offset`
 * and `has_more` in the cursor's extras (`Cursor.getExtras()`), next to `api_version`.
 */
internal object ApiCursors {

    private val TRACK_COLUMNS = listOf(
        "id", "title", "artist", "album", "album_id", "artist_id", "duration_ms", "track_number", "disc_number", "year",
    )

    private class Table(val columns: List<String>, val rows: List<List<Any?>>, val extras: Bundle)

    /** The rows of [result] as a cursor with only the columns of [projection] (all of them when it is null). */
    fun toCursor(result: ApiQueryResult, projection: Array<String>?): Cursor {
        val table = tableOf(result)
        val wanted = projection?.toList() ?: table.columns
        val indices = wanted.map { column ->
            table.columns.indexOf(column).also {
                if (it < 0) throw ApiErrors.exceptionFor(ApiError.BadArgumentValue("projection", "unknown column"))
            }
        }
        return MatrixCursor(wanted.toTypedArray(), table.rows.size).apply {
            table.rows.forEach { row -> addRow(indices.map { row[it] }) }
            extras = table.extras
        }
    }

    private fun tableOf(result: ApiQueryResult): Table = when (result) {
        is ApiQueryResult.Failed -> throw ApiErrors.exceptionFor(result.error)
        is ApiQueryResult.State -> Table(STATE_COLUMNS, listOf(stateRow(result.state)), versionOnly())
        is ApiQueryResult.Tracks -> paged(TRACK_COLUMNS, result.page) { trackValues(it) }
        is ApiQueryResult.Albums -> paged(ALBUM_COLUMNS, result.page) {
            listOf(it.id, it.title, it.artist, it.artistId, it.year, it.trackCount)
        }
        is ApiQueryResult.Artists -> paged(ARTIST_COLUMNS, result.page) {
            listOf(it.id, it.name, it.albumCount, it.trackCount)
        }
        is ApiQueryResult.Playlists -> paged(PLAYLIST_COLUMNS, result.page) {
            listOf(it.id, it.name, it.description, it.trackCount)
        }
        is ApiQueryResult.Queue -> paged(QUEUE_COLUMNS, result.page) {
            listOf(it.index, it.queueItemId, it.isCurrent.toInt()) + trackValues(it.track)
        }
    }

    private val ALBUM_COLUMNS = listOf("id", "title", "artist", "artist_id", "year", "track_count")
    private val ARTIST_COLUMNS = listOf("id", "name", "album_count", "track_count")
    private val PLAYLIST_COLUMNS = listOf("id", "name", "description", "track_count")
    private val QUEUE_COLUMNS = listOf("index", "queue_item_id", "is_current") + TRACK_COLUMNS
    private val STATE_COLUMNS = listOf(
        "api_version", "is_playing", "position_ms", "duration_ms", "shuffle", "repeat", "current_index", "queue_size",
        "track_id", "track_title", "track_artist", "track_album", "track_album_id", "track_artist_id", "track_duration_ms",
        "sleep_timer_mode", "sleep_timer_remaining_ms", "crossfade_seconds", "speed", "dj_mode",
    )

    private fun <T> paged(columns: List<String>, page: Page<T>, values: (T) -> List<Any?>) = Table(
        columns,
        page.rows.map(values),
        versionOnly().apply {
            putInt("total", page.total)
            putInt("limit", page.limit)
            putInt("offset", page.offset)
            putBoolean("has_more", page.hasMore)
        },
    )

    private fun versionOnly() = Bundle().apply { putInt("api_version", API_VERSION) }

    private fun trackValues(track: TrackRow): List<Any?> = listOf(
        track.id, track.title, track.artist, track.album, track.albumId, track.artistId, track.durationMs,
        track.trackNumber, track.discNumber, track.year,
    )

    private fun stateRow(state: StateRow): List<Any?> {
        val track = state.currentTrack
        return listOf(
            state.apiVersion, state.isPlaying.toInt(), state.positionMs, state.durationMs, state.shuffle.toInt(), state.repeat,
            state.currentIndex, state.queueSize,
            track?.id, track?.title, track?.artist, track?.album, track?.albumId, track?.artistId, track?.durationMs,
            state.sleepTimer?.mode, state.sleepTimer?.remainingMs, state.crossfadeSeconds, state.speed, state.djMode.toInt(),
        )
    }

    private fun Boolean.toInt() = if (this) 1 else 0
}
