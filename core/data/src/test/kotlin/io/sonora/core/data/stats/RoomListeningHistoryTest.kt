package io.sonora.core.data.stats

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.stats.Listening
import io.sonora.core.domain.stats.TrackListening
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomListeningHistoryTest {

    private val context = RuntimeEnvironment.getApplication()
    private var db = open()
    private var history = RoomListeningHistory(db.listeningDao())

    private fun open() = Room.databaseBuilder(context, StatsDatabase::class.java, StatsDatabase.NAME)
        .allowMainThreadQueries()
        .build()

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(StatsDatabase.NAME)
    }

    @Test
    fun `a playback saved again keeps the most heard and stays counted, also after reopening`() = runBlocking<Unit> {
        history.record(Listening("p1", "content://a", 1_000, 30_000, counted = true))
        history.record(Listening("p1", "content://a", 1_000, 50_000, counted = false))
        // A save that lands late, with less heard, must not undo the later one.
        history.record(Listening("p1", "content://a", 1_000, 40_000, counted = false))

        db.close()
        db = open()
        history = RoomListeningHistory(db.listeningDao())

        assertThat(history.perTrack(null).first()).containsExactly(TrackListening("content://a", 1, 50_000))
    }

    @Test
    fun `songs add up their counted plays and their time heard, skips included`() = runBlocking<Unit> {
        history.record(Listening("p1", "content://a", 1_000, 200_000, counted = true))
        history.record(Listening("p2", "content://a", 2_000, 15_000, counted = false))
        history.record(Listening("p3", "content://a", 3_000, 180_000, counted = true))
        history.record(Listening("p4", "content://b", 4_000, 5_000, counted = false))

        assertThat(history.perTrack(null).first()).containsExactly(
            TrackListening("content://a", 2, 395_000),
            TrackListening("content://b", 0, 5_000),
        )
    }

    @Test
    fun `a range keeps the playbacks started at or after its start`() = runBlocking<Unit> {
        history.record(Listening("before", "content://a", 9_999, 10_000, counted = true))
        history.record(Listening("at", "content://a", 10_000, 20_000, counted = true))
        history.record(Listening("after", "content://b", 10_001, 30_000, counted = false))

        assertThat(history.perTrack(10_000).first()).containsExactly(
            TrackListening("content://a", 1, 20_000),
            TrackListening("content://b", 0, 30_000),
        )
        assertThat(history.perTrack(10_002).first()).isEmpty()
    }

    @Test
    fun `statistics start at the first playback, none before any`() = runBlocking<Unit> {
        assertThat(history.firstStartedAtMs().first()).isNull()

        history.record(Listening("p2", "content://a", 5_000, 10_000, counted = false))
        history.record(Listening("p1", "content://b", 3_000, 10_000, counted = false))

        assertThat(history.firstStartedAtMs().first()).isEqualTo(3_000)
    }

    @Test
    fun `a window lists the playbacks started inside it, both ends included, oldest first, ties by id`() = runBlocking<Unit> {
        history.record(Listening("before", "content://a", 999, 10_000, counted = true))
        history.record(Listening("z", "content://c", 2_000, 10_000, counted = false))
        history.record(Listening("b", "content://b", 1_000, 20_000, counted = true))
        history.record(Listening("a", "content://a", 2_000, 5_000, counted = false))
        history.record(Listening("end", "content://a", 3_000, 7_000, counted = true))
        history.record(Listening("after", "content://a", 3_001, 10_000, counted = true))

        val plays = history.startedBetween(1_000, 3_000, limit = 10, offset = 0)

        assertThat(plays.map { it.id }).containsExactly("b", "a", "z", "end").inOrder()
        assertThat(plays[1]).isEqualTo(Listening("a", "content://a", 2_000, 5_000, counted = false))
        assertThat(history.countStartedBetween(1_000, 3_000)).isEqualTo(4)
    }

    @Test
    fun `a window is paged by limit and offset`() = runBlocking<Unit> {
        (1..5).forEach { history.record(Listening("p$it", "content://a", it * 1_000L, 1_000, counted = true)) }

        assertThat(history.startedBetween(0, 10_000, limit = 2, offset = 1).map { it.id }).containsExactly("p2", "p3").inOrder()
        assertThat(history.startedBetween(0, 10_000, limit = 2, offset = 4).map { it.id }).containsExactly("p5")
        assertThat(history.startedBetween(0, 10_000, limit = 2, offset = 9)).isEmpty()
    }

    @Test
    fun `an empty window and an empty log list nothing`() = runBlocking<Unit> {
        assertThat(history.startedBetween(0, 10, limit = 5, offset = 0)).isEmpty()
        assertThat(history.countStartedBetween(0, 10)).isEqualTo(0)

        history.record(Listening("p1", "content://a", 5, 1, counted = false))
        assertThat(history.startedBetween(6, 9, limit = 5, offset = 0)).isEmpty()
        assertThat(history.startedBetween(5, 5, limit = 5, offset = 0).map { it.id }).containsExactly("p1")
    }
}
