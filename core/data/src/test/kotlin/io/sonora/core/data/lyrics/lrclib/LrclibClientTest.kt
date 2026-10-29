package io.sonora.core.data.lyrics.lrclib

import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpServer
import io.sonora.core.domain.port.LyricsQuery
import io.sonora.core.domain.port.OnlineLyrics
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

/** LRCLIB answers are canned (no live network). Every lyric text is invented for the tests. */
class LrclibClientTest {

    /** Answers each request from [routes] by API path (`get`, `search`); records the URLs asked. */
    private class CannedHttp(private val routes: Map<String, HttpResponse>) : HttpGet {
        val urls = mutableListOf<String>()
        override fun get(url: String): HttpResponse {
            urls += url
            val endpoint = url.substringAfter("/api/").substringBefore('?')
            return routes[endpoint] ?: HttpResponse(404, """{"code":404,"name":"TrackNotFound"}""")
        }
    }

    private val query = LyricsQuery(title = "Test Title", artist = "Test Artist", album = "Test Album", durationSec = 200)

    private fun record(
        title: String = "Test Title",
        artist: String = "Test Artist",
        duration: Double? = 200.0,
        plain: String? = "First test line\nSecond test line",
        synced: String? = "[00:01.00]First test line\n[00:02.00]Second test line",
        instrumental: Boolean = false,
    ): String {
        fun json(value: String?) = value?.let { "\"" + it.replace("\n", "\\n") + "\"" } ?: "null"
        return """{"id":1,"name":"$title","trackName":"$title","artistName":"$artist","albumName":"Test Album",""" +
            """"duration":${duration ?: "null"},"instrumental":$instrumental,"plainLyrics":${json(plain)},""" +
            """"syncedLyrics":${json(synced)},"unknownField":[1,2]}"""
    }

    private fun parameters(url: String): Map<String, String> = url.substringAfter('?').split('&').associate {
        val (key, value) = it.split('=', limit = 2)
        key to URLDecoder.decode(value, "UTF-8")
    }

    @Test
    fun `an exact match is fetched with the song's names and duration`() = runBlocking<Unit> {
        val http = CannedHttp(mapOf("get" to HttpResponse(200, record())))

        val found = LrclibClient(http).find(query)

        assertThat(found).isEqualTo(
            OnlineLyrics(
                syncedLrc = "[00:01.00]First test line\n[00:02.00]Second test line",
                plainText = "First test line\nSecond test line",
                instrumental = false,
            ),
        )
        assertThat(http.urls).hasSize(1)
        assertThat(http.urls.single()).startsWith("https://lrclib.net/api/get?")
        assertThat(parameters(http.urls.single())).containsExactly(
            "artist_name", "Test Artist", "track_name", "Test Title", "album_name", "Test Album", "duration", "200",
        )
    }

    @Test
    fun `without an exact match the search picks timed lyrics within two seconds`() = runBlocking<Unit> {
        val results = "[" + listOf(
            record(duration = 230.0), // too long
            record(duration = 201.0, synced = null), // plain only
            record(duration = 198.5), // timed, in range
            record(title = "Other", duration = 200.0, plain = null, synced = null), // empty
        ).joinToString(",") + "]"
        val http = CannedHttp(mapOf("search" to HttpResponse(200, results)))

        val found = LrclibClient(http).find(query)

        assertThat(found?.syncedLyrics()).isTrue()
        assertThat(http.urls.map { it.substringAfter("/api/").substringBefore('?') }).containsExactly("get", "search").inOrder()
        assertThat(parameters(http.urls.last())).containsExactly("track_name", "Test Title", "artist_name", "Test Artist")
    }

    private fun OnlineLyrics.syncedLyrics() = syncedLrc != null

    @Test
    fun `among equal candidates the same names and the closest duration win`() = runBlocking<Unit> {
        val results = "[" + listOf(
            record(title = "Test Title (Live)", duration = 200.0, synced = "[00:01.00]Live test line"),
            record(duration = 202.0, synced = "[00:01.00]Far test line"),
            record(duration = 199.5, synced = "[00:01.00]Close test line"),
        ).joinToString(",") + "]"

        val found = LrclibClient(CannedHttp(mapOf("search" to HttpResponse(200, results)))).find(query)

        assertThat(found?.syncedLrc).isEqualTo("[00:01.00]Close test line")
    }

    @Test
    fun `nothing within two seconds is nothing found`() = runBlocking<Unit> {
        val results = "[" + record(duration = 203.0) + "," + record(duration = null) + "]"

        assertThat(LrclibClient(CannedHttp(mapOf("search" to HttpResponse(200, results)))).find(query)).isNull()
        assertThat(LrclibClient(CannedHttp(mapOf("search" to HttpResponse(200, "[]")))).find(query)).isNull()
    }

    @Test
    fun `an instrumental record is a result`() = runBlocking<Unit> {
        val http = CannedHttp(mapOf("get" to HttpResponse(200, record(plain = null, synced = null, instrumental = true))))

        assertThat(LrclibClient(http).find(query)).isEqualTo(OnlineLyrics(null, null, instrumental = true))
    }

    @Test
    fun `a song without duration or artist is only searched, by the names it has`() = runBlocking<Unit> {
        val http = CannedHttp(mapOf("search" to HttpResponse(200, "[" + record(duration = 999.0) + "]")))

        val found = LrclibClient(http).find(query.copy(artist = " ", durationSec = null))

        assertThat(found).isNotNull()
        assertThat(http.urls).hasSize(1)
        assertThat(parameters(http.urls.single())).containsExactly("track_name", "Test Title")
    }

    @Test
    fun `server errors and broken answers are failures, not empty results`() {
        val failing = LrclibClient(CannedHttp(mapOf("get" to HttpResponse(500, "oops"))))
        assertThrows(IOException::class.java) { runBlocking { failing.find(query) } }

        val broken = LrclibClient(CannedHttp(mapOf("get" to HttpResponse(200, "<html>"))))
        assertThrows(IOException::class.java) { runBlocking { broken.find(query) } }
    }

    @Test
    fun `the http transport sends the user agent and reads error bodies`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var agent: String? = null
        server.createContext("/ok") { exchange ->
            agent = exchange.requestHeaders.getFirst("User-Agent")
            val body = "[]".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/missing") { exchange ->
            val body = """{"code":404}""".toByteArray()
            exchange.sendResponseHeaders(404, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val http = UrlConnectionHttpGet("Sonora/9.9 (personal music player)")
            val base = "http://127.0.0.1:${server.address.port}"

            assertThat(http.get("$base/ok")).isEqualTo(HttpResponse(200, "[]"))
            assertThat(agent).isEqualTo("Sonora/9.9 (personal music player)")
            assertThat(http.get("$base/missing")).isEqualTo(HttpResponse(404, """{"code":404}"""))
        } finally {
            server.stop(0)
        }
    }
}
