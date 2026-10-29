package io.sonora.core.api

import com.google.common.truth.Truth.assertThat
import io.sonora.core.api.ApiError.ArgumentOutOfRange
import io.sonora.core.api.ApiError.BadArgumentType
import io.sonora.core.api.ApiError.LocationRejected
import org.junit.Test

class QueryParserTest {

    private fun parsed(path: String, vararg params: Pair<String, Any?>): ApiQuery {
        val result = QueryParser.parse(path.split('/').filter { it.isNotEmpty() }, mapOf(*params))
        assertThat(result).isInstanceOf(QueryParseResult.Parsed::class.java)
        return (result as QueryParseResult.Parsed).query
    }

    private fun rejected(path: String, vararg params: Pair<String, Any?>): ApiError {
        val result = QueryParser.parse(path.split('/').filter { it.isNotEmpty() }, mapOf(*params))
        assertThat(result).isInstanceOf(QueryParseResult.Rejected::class.java)
        return (result as QueryParseResult.Rejected).error
    }

    @Test
    fun `the list paths parse with the default page`() {
        val page = PageRequest(limit = 50, offset = 0)
        assertThat(parsed("state")).isEqualTo(ApiQuery.State)
        assertThat(parsed("queue")).isEqualTo(ApiQuery.Queue(page))
        assertThat(parsed("tracks")).isEqualTo(ApiQuery.Tracks(null, page))
        assertThat(parsed("albums")).isEqualTo(ApiQuery.Albums(page))
        assertThat(parsed("artists")).isEqualTo(ApiQuery.Artists(page))
        assertThat(parsed("playlists")).isEqualTo(ApiQuery.Playlists(page))
    }

    @Test
    fun `the nested paths take an id`() {
        val page = PageRequest()
        assertThat(parsed("albums/12/tracks")).isEqualTo(ApiQuery.AlbumTracks(12, page))
        assertThat(parsed("artists/3/tracks")).isEqualTo(ApiQuery.ArtistTracks(3, page))
        assertThat(parsed("playlists/8/tracks")).isEqualTo(ApiQuery.PlaylistTracks(8, page))
    }

    @Test
    fun `an id in a path must be a positive whole number`() {
        assertThat(rejected("albums/x/tracks")).isEqualTo(BadArgumentType("id", "integer"))
        assertThat(rejected("albums/1.5/tracks")).isEqualTo(BadArgumentType("id", "integer"))
        assertThat(rejected("albums/0/tracks")).isInstanceOf(ArgumentOutOfRange::class.java)
        assertThat(rejected("playlists/-2/tracks")).isInstanceOf(ArgumentOutOfRange::class.java)
    }

    @Test
    fun `an unknown path is rejected`() {
        listOf("", "song", "tracks/5", "albums/1", "albums/1/artists", "albums/1/tracks/2", "state/x", "queue/tracks").forEach {
            assertThat(rejected(it)).isEqualTo(ApiError.UnknownQuery)
        }
    }

    @Test
    fun `limit and offset page the list`() {
        assertThat(parsed("albums", "limit" to 10, "offset" to 20)).isEqualTo(ApiQuery.Albums(PageRequest(10, 20)))
        assertThat(parsed("albums", "limit" to "10", "offset" to "20")).isEqualTo(ApiQuery.Albums(PageRequest(10, 20)))
        assertThat(parsed("playlists/8/tracks", "limit" to 5L)).isEqualTo(ApiQuery.PlaylistTracks(8, PageRequest(5, 0)))
    }

    @Test
    fun `a limit above the cap is held to the cap`() {
        assertThat(parsed("tracks", "limit" to 100_000)).isEqualTo(ApiQuery.Tracks(null, PageRequest(ApiLimits.MAX_PAGE_SIZE, 0)))
        assertThat(parsed("tracks", "limit" to ApiLimits.MAX_PAGE_SIZE))
            .isEqualTo(ApiQuery.Tracks(null, PageRequest(ApiLimits.MAX_PAGE_SIZE, 0)))
    }

    @Test
    fun `a limit below 1, a negative offset or a non number is rejected`() {
        assertThat(rejected("tracks", "limit" to 0)).isInstanceOf(ArgumentOutOfRange::class.java)
        assertThat(rejected("tracks", "limit" to -5)).isInstanceOf(ArgumentOutOfRange::class.java)
        assertThat(rejected("tracks", "offset" to -1)).isInstanceOf(ArgumentOutOfRange::class.java)
        assertThat(rejected("tracks", "limit" to "many")).isEqualTo(BadArgumentType("limit", "integer"))
        assertThat(rejected("tracks", "offset" to 1.5)).isEqualTo(BadArgumentType("offset", "integer"))
    }

    @Test
    fun `the tracks search text is trimmed, and blank means no search`() {
        assertThat(parsed("tracks", "q" to "  daft ")).isEqualTo(ApiQuery.Tracks("daft", PageRequest()))
        assertThat(parsed("tracks", "q" to "AC/DC")).isEqualTo(ApiQuery.Tracks("AC/DC", PageRequest()))
        assertThat(parsed("tracks", "q" to "   ")).isEqualTo(ApiQuery.Tracks(null, PageRequest()))
        assertThat(rejected("tracks", "q" to 5)).isEqualTo(BadArgumentType("q", "string"))
        assertThat(rejected("tracks", "q" to "x".repeat(ApiLimits.MAX_QUERY_LENGTH + 1))).isInstanceOf(ArgumentOutOfRange::class.java)
    }

    @Test
    fun `a location in any parameter is rejected`() {
        assertThat(rejected("tracks", "q" to "content://media/external/audio/media/1")).isEqualTo(LocationRejected("q"))
        assertThat(rejected("tracks", "q" to "/sdcard/Music")).isEqualTo(LocationRejected("q"))
        assertThat(rejected("albums", "sort" to "file:///x")).isEqualTo(LocationRejected("sort"))
    }

    @Test
    fun `state ignores paging parameters`() {
        assertThat(parsed("state", "limit" to 5)).isEqualTo(ApiQuery.State)
    }
}
