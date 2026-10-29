package io.sonora.core.playback.api

import android.database.Cursor
import com.google.common.truth.Truth.assertThat
import io.sonora.core.api.AlbumRow
import io.sonora.core.api.ApiError
import io.sonora.core.api.ApiQueryResult
import io.sonora.core.api.ArtistRow
import io.sonora.core.api.Page
import io.sonora.core.api.PlaylistRow
import io.sonora.core.api.QueueRow
import io.sonora.core.api.SleepTimerRow
import io.sonora.core.api.StateRow
import io.sonora.core.api.TrackRow
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Query results as cursors: stable column names, library ids and tags only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ApiCursorsTest {

    private fun trackRow(id: Long, trackNumber: Int? = 2) = TrackRow(
        id = id, title = "Title $id", artist = "Artist", album = "Album", albumId = 7, artistId = 3, durationMs = 180_000,
        trackNumber = trackNumber, discNumber = null, year = 1999,
    )

    private fun cursorOf(result: ApiQueryResult, projection: Array<String>? = null): Cursor = ApiCursors.toCursor(result, projection)

    private fun Cursor.columns() = columnNames.toList()

    private fun Cursor.row(): Map<String, Any?> = columnNames.associateWith { name ->
        val index = getColumnIndexOrThrow(name)
        when (getType(index)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> getLong(index)
            Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
            else -> getString(index)
        }
    }

    @Test
    fun `tracks have the documented columns, one row each, and the paging in the extras`() {
        val page = Page(listOf(trackRow(1), trackRow(2, trackNumber = null)), total = 5, limit = 2, offset = 0)

        val cursor = cursorOf(ApiQueryResult.Tracks(page))

        assertThat(cursor.columns()).containsExactly(
            "id", "title", "artist", "album", "album_id", "artist_id", "duration_ms", "track_number", "disc_number", "year",
        ).inOrder()
        assertThat(cursor.count).isEqualTo(2)
        cursor.moveToFirst()
        assertThat(cursor.row()).containsExactly(
            "id", 1L, "title", "Title 1", "artist", "Artist", "album", "Album", "album_id", 7L, "artist_id", 3L,
            "duration_ms", 180_000L, "track_number", 2L, "disc_number", null, "year", 1999L,
        )
        cursor.moveToNext()
        assertThat(cursor.row()["track_number"]).isNull()
        assertThat(cursor.extras.getInt("total")).isEqualTo(5)
        assertThat(cursor.extras.getInt("limit")).isEqualTo(2)
        assertThat(cursor.extras.getInt("offset")).isEqualTo(0)
        assertThat(cursor.extras.getBoolean("has_more")).isTrue()
        assertThat(cursor.extras.getInt("api_version")).isEqualTo(1)
    }

    @Test
    fun `the last page says there is no more`() {
        val cursor = cursorOf(ApiQueryResult.Tracks(Page(listOf(trackRow(5)), total = 5, limit = 2, offset = 4)))

        assertThat(cursor.extras.getBoolean("has_more", true)).isFalse()
        assertThat(cursor.extras.getInt("offset")).isEqualTo(4)
    }

    @Test
    fun `an empty page is a cursor with the columns and no rows`() {
        val cursor = cursorOf(ApiQueryResult.Albums(Page(emptyList(), total = 0, limit = 50, offset = 0)))

        assertThat(cursor.count).isEqualTo(0)
        assertThat(cursor.columns()).isNotEmpty()
    }

    @Test
    fun `albums, artists and playlists have their own columns`() {
        val albums = cursorOf(ApiQueryResult.Albums(Page(listOf(AlbumRow(4, "Blue", "Joni", 9, null, 10)), 1, 50, 0)))
        val artists = cursorOf(ApiQueryResult.Artists(Page(listOf(ArtistRow(9, "Joni", 2, 20)), 1, 50, 0)))
        val playlists = cursorOf(ApiQueryResult.Playlists(Page(listOf(PlaylistRow(2, "Road", "Trip", 12)), 1, 50, 0)))

        assertThat(albums.columns()).containsExactly("id", "title", "artist", "artist_id", "year", "track_count").inOrder()
        albums.moveToFirst()
        assertThat(albums.row()).containsExactly("id", 4L, "title", "Blue", "artist", "Joni", "artist_id", 9L, "year", null, "track_count", 10L)
        assertThat(artists.columns()).containsExactly("id", "name", "album_count", "track_count").inOrder()
        artists.moveToFirst()
        assertThat(artists.row()).containsExactly("id", 9L, "name", "Joni", "album_count", 2L, "track_count", 20L)
        assertThat(playlists.columns()).containsExactly("id", "name", "description", "track_count").inOrder()
        playlists.moveToFirst()
        assertThat(playlists.row()).containsExactly("id", 2L, "name", "Road", "description", "Trip", "track_count", 12L)
    }

    @Test
    fun `the queue shows each entry's position, whether it is playing and its track`() {
        val rows = listOf(QueueRow(0, 100, isCurrent = true, track = trackRow(1)), QueueRow(1, 101, isCurrent = false, track = trackRow(2)))

        val cursor = cursorOf(ApiQueryResult.Queue(Page(rows, 2, 50, 0)))

        assertThat(cursor.columns().take(4)).containsExactly("index", "queue_item_id", "is_current", "id").inOrder()
        assertThat(cursor.columns()).contains("title")
        cursor.moveToFirst()
        assertThat(cursor.row()["index"]).isEqualTo(0L)
        assertThat(cursor.row()["queue_item_id"]).isEqualTo(100L)
        assertThat(cursor.row()["is_current"]).isEqualTo(1L)
        assertThat(cursor.row()["id"]).isEqualTo(1L)
        cursor.moveToNext()
        assertThat(cursor.row()["is_current"]).isEqualTo(0L)
    }

    @Test
    fun `the state is one row that flattens the playing track and the sleep timer`() {
        val state = StateRow(
            apiVersion = 1, isPlaying = true, positionMs = 12_000, durationMs = 180_000, shuffle = false, repeat = "all",
            currentIndex = 0, queueSize = 3, currentTrack = trackRow(1), sleepTimer = SleepTimerRow("minutes", 60_000),
            crossfadeSeconds = 4, speed = 1.25, djMode = true,
        )

        val cursor = cursorOf(ApiQueryResult.State(state))

        assertThat(cursor.count).isEqualTo(1)
        cursor.moveToFirst()
        assertThat(cursor.row()).containsExactly(
            "api_version", 1L, "is_playing", 1L, "position_ms", 12_000L, "duration_ms", 180_000L, "shuffle", 0L,
            "repeat", "all", "current_index", 0L, "queue_size", 3L, "track_id", 1L, "track_title", "Title 1",
            "track_artist", "Artist", "track_album", "Album", "track_album_id", 7L, "track_artist_id", 3L,
            "track_duration_ms", 180_000L, "sleep_timer_mode", "minutes", "sleep_timer_remaining_ms", 60_000L,
            "crossfade_seconds", 4L, "speed", 1.25, "dj_mode", 1L,
        )
        assertThat(cursor.extras.getInt("api_version")).isEqualTo(1)
    }

    @Test
    fun `an idle state has empty track and timer columns`() {
        val state = StateRow(1, false, 0, 0, false, "off", -1, 0, null, null, 0, 1.0, false)

        val cursor = cursorOf(ApiQueryResult.State(state))

        cursor.moveToFirst()
        val row = cursor.row()
        assertThat(row["track_id"]).isNull()
        assertThat(row["track_title"]).isNull()
        assertThat(row["sleep_timer_mode"]).isNull()
        assertThat(row["sleep_timer_remaining_ms"]).isNull()
        assertThat(row["is_playing"]).isEqualTo(0L)
        assertThat(row["speed"]).isEqualTo(1.0)
    }

    @Test
    fun `a projection keeps only the named columns in the order asked`() {
        val cursor = cursorOf(ApiQueryResult.Tracks(Page(listOf(trackRow(1)), 1, 50, 0)), arrayOf("title", "id"))

        assertThat(cursor.columns()).containsExactly("title", "id").inOrder()
        cursor.moveToFirst()
        assertThat(cursor.getString(0)).isEqualTo("Title 1")
        assertThat(cursor.getLong(1)).isEqualTo(1L)
    }

    @Test
    fun `a projection naming a column the query lacks is a bad argument`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            cursorOf(ApiQueryResult.Tracks(Page(listOf(trackRow(1)), 1, 50, 0)), arrayOf("title", "_data"))
        }

        assertThat(error).hasMessageThat().startsWith("bad_argument_value: ")
        assertThat(error).hasMessageThat().doesNotContain("_data")
    }

    @Test
    fun `a failed query is never turned into a cursor`() {
        assertThrows(IllegalStateException::class.java) { cursorOf(ApiQueryResult.Failed(ApiError.Failed)) }
        assertThrows(IllegalArgumentException::class.java) { cursorOf(ApiQueryResult.Failed(ApiError.UnknownQuery)) }
    }

    @Test
    fun `no column of any query carries a location`() {
        val everything = listOf(
            "uri", "path", "url", "artwork", "file", "folder", "size", "data",
        )
        val track = cursorOf(ApiQueryResult.Tracks(Page(listOf(trackRow(1)), 1, 50, 0))).columns()
        val queue = cursorOf(ApiQueryResult.Queue(Page(listOf(QueueRow(0, 1, true, trackRow(1))), 1, 50, 0))).columns()

        for (column in track + queue) assertThat(everything.any { column.contains(it) }).isFalse()
    }

    private fun Cursor.getColumnIndexOrThrow(name: String) = getColumnIndex(name).also { check(it >= 0) { name } }
}
