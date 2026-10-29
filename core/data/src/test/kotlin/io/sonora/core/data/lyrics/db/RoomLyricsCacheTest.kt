package io.sonora.core.data.lyrics.db

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.port.CachedLyrics
import io.sonora.core.domain.port.OnlineLyrics
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Lyric texts are invented for the tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomLyricsCacheTest {

    private val context = RuntimeEnvironment.getApplication()
    private var db = open()
    private var cache = RoomLyricsCache(db.lyricsDao())

    private fun open() = Room.databaseBuilder(context, LyricsDatabase::class.java, LyricsDatabase.NAME)
        .allowMainThreadQueries()
        .build()

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(LyricsDatabase.NAME)
    }

    @Test
    fun `found lyrics and not-found results read back, also after reopening the database`() = runBlocking<Unit> {
        val found = OnlineLyrics("[00:01.00]First test line", "First test line", instrumental = false)
        cache.put("content://doc/a", CachedLyrics.Found(found))
        cache.put("content://doc/b", CachedLyrics.NotFound)
        cache.put("content://doc/c", CachedLyrics.Found(OnlineLyrics(null, null, instrumental = true)))

        db.close()
        db = open()
        cache = RoomLyricsCache(db.lyricsDao())

        assertThat(cache.get("content://doc/a")).isEqualTo(CachedLyrics.Found(found))
        assertThat(cache.get("content://doc/b")).isEqualTo(CachedLyrics.NotFound)
        assertThat(cache.get("content://doc/c")).isEqualTo(CachedLyrics.Found(OnlineLyrics(null, null, instrumental = true)))
        assertThat(cache.get("content://doc/other")).isNull()
    }

    @Test
    fun `a new result replaces the old one and removal forgets it`() = runBlocking<Unit> {
        cache.put("content://doc/a", CachedLyrics.NotFound)
        cache.put("content://doc/a", CachedLyrics.Found(OnlineLyrics(null, "First test line", instrumental = false)))

        assertThat(cache.get("content://doc/a")).isEqualTo(CachedLyrics.Found(OnlineLyrics(null, "First test line", false)))

        cache.remove("content://doc/a")
        assertThat(cache.get("content://doc/a")).isNull()
    }
}
