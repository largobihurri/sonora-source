package io.sonora.core.domain.lyrics

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.CachedLyrics
import io.sonora.core.domain.port.LocalLyricsSource
import io.sonora.core.domain.port.LyricsCache
import io.sonora.core.domain.port.LyricsQuery
import io.sonora.core.domain.port.OnlineLyrics
import io.sonora.core.domain.port.OnlineLyricsSource
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

/** Local lyrics first, then what was downloaded before; LRCLIB only when asked. Lyric texts are invented. */
class LyricsRepositoryTest {

    private class FakeCache : LyricsCache {
        val entries = mutableMapOf<String, CachedLyrics>()
        override suspend fun get(trackUri: String) = entries[trackUri]
        override suspend fun put(trackUri: String, entry: CachedLyrics) {
            entries[trackUri] = entry
        }
        override suspend fun remove(trackUri: String) {
            entries.remove(trackUri)
        }
    }

    private class FakeOnline : OnlineLyricsSource {
        val queries = mutableListOf<LyricsQuery>()
        var answer: () -> OnlineLyrics? = { null }
        override suspend fun find(query: LyricsQuery): OnlineLyrics? {
            queries += query
            return answer()
        }
    }

    private var local: LocalLyrics? = null
    private var localFails = false
    private val cache = FakeCache()
    private val online = FakeOnline()
    private val repository = LyricsRepository(
        local = LocalLyricsSource { if (localFails) throw IOException("gone") else local },
        cache = cache,
        online = online,
    )

    private val track = Track(
        id = 1, uri = "content://doc/1", title = "Test Title", artist = "Test Artist", album = "Test Album",
        albumArtist = null, durationMs = 201_600, trackNumber = null, discNumber = null, year = null, albumId = 1,
        artworkUri = null, mimeType = "audio/flac", sizeBytes = 1,
    )

    private val syncedLrc = "[00:01.00]First test line\n[00:02.00]Second test line"
    private val synced = Lyrics.Synced(listOf(LyricLine(1_000, "First test line"), LyricLine(2_000, "Second test line")))

    @Test
    fun `without an online service there is no search, and local lyrics still load`() = runBlocking<Unit> {
        val offline = LyricsRepository(local = LocalLyricsSource { local }, cache = cache, online = null)
        local = LocalLyrics(Lyrics.Plain("Local test line"), LyricsOrigin.SIDECAR)

        assertThat(offline.canSearchOnline).isFalse()
        assertThat(repository.canSearchOnline).isTrue()
        assertThat(offline.load(track)).isEqualTo(LyricsLookup.Found(Lyrics.Plain("Local test line"), LyricsOrigin.SIDECAR))
        assertThrows(IllegalStateException::class.java) { runBlocking { offline.search(track) } }
        assertThat(cache.entries).isEmpty()
    }

    @Test
    fun `local lyrics win and nothing else is asked`() = runBlocking<Unit> {
        local = LocalLyrics(Lyrics.Plain("Local test line"), LyricsOrigin.SIDECAR)
        cache.entries[track.uri] = CachedLyrics.NotFound

        assertThat(repository.load(track)).isEqualTo(LyricsLookup.Found(Lyrics.Plain("Local test line"), LyricsOrigin.SIDECAR))
        assertThat(online.queries).isEmpty()
    }

    @Test
    fun `without local lyrics, downloaded ones are served from the cache`() = runBlocking<Unit> {
        cache.entries[track.uri] = CachedLyrics.Found(OnlineLyrics(syncedLrc, "First test line", instrumental = false))

        assertThat(repository.load(track)).isEqualTo(LyricsLookup.Found(synced, LyricsOrigin.DOWNLOADED))
        assertThat(online.queries).isEmpty()
    }

    @Test
    fun `a song searched before without success stays not found`() = runBlocking<Unit> {
        cache.entries[track.uri] = CachedLyrics.NotFound

        assertThat(repository.load(track)).isEqualTo(LyricsLookup.NotFound)
    }

    @Test
    fun `a song with nothing on the device is missing and never looked up online by itself`() = runBlocking<Unit> {
        assertThat(repository.load(track)).isEqualTo(LyricsLookup.Missing)
        assertThat(online.queries).isEmpty()
    }

    @Test
    fun `a failing local source falls through to the cache`() = runBlocking<Unit> {
        localFails = true
        cache.entries[track.uri] = CachedLyrics.NotFound

        assertThat(repository.load(track)).isEqualTo(LyricsLookup.NotFound)
    }

    @Test
    fun `a search sends the song's names and rounded duration and keeps what it finds`() = runBlocking<Unit> {
        online.answer = { OnlineLyrics(syncedLrc, "First test line\nSecond test line", instrumental = false) }

        assertThat(repository.search(track)).isEqualTo(LyricsLookup.Found(synced, LyricsOrigin.DOWNLOADED))
        assertThat(online.queries).containsExactly(LyricsQuery("Test Title", "Test Artist", "Test Album", durationSec = 202))
        // Served from the device from now on.
        assertThat(repository.load(track)).isEqualTo(LyricsLookup.Found(synced, LyricsOrigin.DOWNLOADED))
        assertThat(online.queries).hasSize(1)
    }

    @Test
    fun `plain and instrumental results are kept too`() = runBlocking<Unit> {
        online.answer = { OnlineLyrics(null, "First test line", instrumental = false) }
        assertThat(repository.search(track)).isEqualTo(LyricsLookup.Found(Lyrics.Plain("First test line"), LyricsOrigin.DOWNLOADED))

        online.answer = { OnlineLyrics(null, null, instrumental = true) }
        assertThat(repository.search(track)).isEqualTo(LyricsLookup.Found(Lyrics.Instrumental, LyricsOrigin.DOWNLOADED))
    }

    @Test
    fun `a search without result is remembered as not found`() = runBlocking<Unit> {
        online.answer = { null }

        assertThat(repository.search(track)).isEqualTo(LyricsLookup.NotFound)
        assertThat(cache.entries[track.uri]).isEqualTo(CachedLyrics.NotFound)

        // An empty record counts as nothing found.
        online.answer = { OnlineLyrics(null, "  ", instrumental = false) }
        assertThat(repository.search(track)).isEqualTo(LyricsLookup.NotFound)
    }

    @Test
    fun `a song without a known duration is searched without one`() = runBlocking<Unit> {
        repository.search(track.copy(durationMs = 0))

        assertThat(online.queries.single().durationSec).isNull()
    }

    @Test
    fun `a search that cannot reach the service fails and remembers nothing`() {
        online.answer = { throw IOException("offline") }

        assertThrows(IOException::class.java) { runBlocking { repository.search(track) } }
        assertThat(cache.entries).isEmpty()
    }

    @Test
    fun `removing downloaded lyrics forgets them`() = runBlocking<Unit> {
        cache.entries[track.uri] = CachedLyrics.Found(OnlineLyrics(syncedLrc, null, instrumental = false))

        repository.removeDownloaded(track)

        assertThat(repository.load(track)).isEqualTo(LyricsLookup.Missing)
    }
}
