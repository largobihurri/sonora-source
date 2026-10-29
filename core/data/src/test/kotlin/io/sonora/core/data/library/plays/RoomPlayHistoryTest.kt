package io.sonora.core.data.library.plays

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.sonora.core.data.library.db.LibraryDatabase
import io.sonora.core.domain.library.plays.TrackPlays
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomPlayHistoryTest {

    private lateinit var db: LibraryDatabase
    private lateinit var history: RoomPlayHistory

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        history = RoomPlayHistory(db.playCountDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `nothing was played at first`() = runTest {
        assertThat(history.plays().first()).isEmpty()
    }

    @Test
    fun `each play adds one and keeps the latest time`() = runTest {
        history.recordPlay(SONG, playedAtMs = 10)
        history.recordPlay(SONG, playedAtMs = 30)
        history.recordPlay(OTHER, playedAtMs = 20)

        assertThat(history.plays().first()).containsExactly(
            SONG, TrackPlays(count = 2, lastPlayedMs = 30),
            OTHER, TrackPlays(count = 1, lastPlayedMs = 20),
        )
    }

    @Test
    fun `plays re-emit when a song is played`() = runTest {
        history.recordPlay(SONG, playedAtMs = 10)
        assertThat(history.plays().first().getValue(SONG).count).isEqualTo(1)

        history.recordPlay(SONG, playedAtMs = 11)

        assertThat(history.plays().first().getValue(SONG).count).isEqualTo(2)
    }

    @Test
    fun `a blank song key is ignored`() = runTest {
        history.recordPlay(" ", playedAtMs = 10)

        assertThat(history.plays().first()).isEmpty()
    }

    private companion object {
        const val SONG = "content://tree/music/document/a.flac"
        const val OTHER = "content://tree/music/document/b.flac"
    }
}
