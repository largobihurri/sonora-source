package io.sonora.core.data.lyrics.lrclib

import io.sonora.core.domain.port.LyricsQuery
import io.sonora.core.domain.port.OnlineLyrics
import io.sonora.core.domain.port.OnlineLyricsSource
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Status code and body text of one HTTP response. */
data class HttpResponse(val code: Int, val body: String)

/** One blocking HTTP GET. Throws [IOException] when no response arrives. */
fun interface HttpGet {
    fun get(url: String): HttpResponse
}

/** [HttpGet] over [HttpURLConnection]: 10 s connect and read timeouts, a descriptive [userAgent], bodies up to 2 MiB. */
class UrlConnectionHttpGet(private val userAgent: String) : HttpGet {
    override fun get(url: String): HttpResponse {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            val stream = if (code < 400) connection.inputStream else connection.errorStream
            return HttpResponse(code, stream?.use(::readCapped).orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private fun readCapped(input: InputStream): String = String(input.readBytesUpTo(MAX_BODY_BYTES), Charsets.UTF_8)

    private fun InputStream.readBytesUpTo(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val read = read(buffer)
            if (read < 0) return out.toByteArray()
            out.write(buffer, 0, read)
            if (out.size() > limit) throw IOException("Response larger than $limit bytes")
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000
        const val MAX_BODY_BYTES = 2 * 1024 * 1024
    }
}

/** An LRCLIB lyrics record; fields the app does not use are ignored. */
@Serializable
internal data class LrclibRecord(
    val trackName: String? = null,
    val artistName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
) {
    val hasContent: Boolean get() = !syncedLyrics.isNullOrBlank() || !plainLyrics.isNullOrBlank() || instrumental

    fun toOnline() = OnlineLyrics(
        syncedLrc = syncedLyrics?.takeIf { it.isNotBlank() },
        plainText = plainLyrics?.takeIf { it.isNotBlank() },
        instrumental = instrumental,
    )
}

/**
 * [OnlineLyricsSource] over LRCLIB (https://lrclib.net, no API key). `GET /api/get` asks for the exact song (artist,
 * title, album and duration; the service matches the duration within 2 s); without one (404, or no artist or duration
 * to ask with) `GET /api/search` lists candidates and [best] picks one. Only the song's names and length are sent.
 * Any other status or an unreadable answer throws [IOException].
 */
class LrclibClient(private val http: HttpGet) : OnlineLyricsSource {

    override suspend fun find(query: LyricsQuery): OnlineLyrics? = withContext(Dispatchers.IO) {
        val exact = if (query.artist.isNotBlank() && query.durationSec != null) exact(query) else null
        (exact ?: search(query))?.toOnline()
    }

    private fun exact(query: LyricsQuery): LrclibRecord? {
        val url = url(
            "get",
            "artist_name" to query.artist,
            "track_name" to query.title,
            "album_name" to query.album,
            "duration" to query.durationSec?.toString(),
        )
        val response = http.get(url)
        return when (response.code) {
            200 -> decode<LrclibRecord>(response.body).takeIf { it.hasContent }
            404 -> null
            else -> throw IOException("LRCLIB answered ${response.code}")
        }
    }

    private fun search(query: LyricsQuery): LrclibRecord? {
        val response = http.get(url("search", "track_name" to query.title, "artist_name" to query.artist))
        if (response.code == 404) return null
        if (response.code != 200) throw IOException("LRCLIB answered ${response.code}")
        return best(decode<List<LrclibRecord>>(response.body), query)
    }

    private inline fun <reified T> decode(body: String): T = try {
        JSON.decodeFromString<T>(body)
    } catch (e: SerializationException) {
        throw IOException("Unreadable LRCLIB answer", e)
    } catch (e: IllegalArgumentException) {
        throw IOException("Unreadable LRCLIB answer", e)
    }

    private fun url(endpoint: String, vararg parameters: Pair<String, String?>): String =
        parameters.filter { !it.second.isNullOrBlank() }
            .joinToString("&", prefix = "$BASE/$endpoint?") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }

    internal companion object {
        private const val BASE = "https://lrclib.net/api"
        private const val MAX_DURATION_DIFFERENCE_SEC = 2.0
        private val JSON = Json { ignoreUnknownKeys = true }

        /**
         * The search result for [query]: records with content and, when the song's length is known, within 2 s of it;
         * timed lyrics first, then the same title, the same artist, and the closest length.
         */
        fun best(records: List<LrclibRecord>, query: LyricsQuery): LrclibRecord? {
            val length = query.durationSec?.toDouble()
            fun difference(record: LrclibRecord) = if (length == null) 0.0 else abs((record.duration ?: Double.MAX_VALUE) - length)
            return records
                .filter { it.hasContent && difference(it) <= MAX_DURATION_DIFFERENCE_SEC }
                .sortedWith(
                    compareBy<LrclibRecord> { it.syncedLyrics.isNullOrBlank() }
                        .thenBy { !it.trackName.equals(query.title.trim(), ignoreCase = true) }
                        .thenBy { !it.artistName.equals(query.artist.trim(), ignoreCase = true) }
                        .thenBy { difference(it) },
                )
                .firstOrNull()
        }
    }
}
