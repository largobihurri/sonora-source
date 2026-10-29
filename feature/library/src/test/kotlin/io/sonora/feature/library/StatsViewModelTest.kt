package io.sonora.feature.library

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.library.plays.PlayHistoryRepository
import io.sonora.core.domain.library.plays.TrackPlays
import io.sonora.core.domain.stats.Listening
import io.sonora.core.domain.stats.ListeningHistory
import io.sonora.core.domain.stats.StatsRange
import io.sonora.core.domain.stats.TrackListening
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModelTest {

    /** Playbacks by start; answers a range as the database does, from its start inclusive. */
    private class FakeListeningHistory : ListeningHistory {
        val playbacks = MutableStateFlow<List<Listening>>(emptyList())
        val asked = mutableListOf<Long?>()

        override suspend fun record(listening: Listening) {
            playbacks.value += listening
        }

        override fun perTrack(sinceMs: Long?): Flow<List<TrackListening>> {
            asked += sinceMs
            return playbacks.map { all ->
                all.filter { sinceMs == null || it.startedAtMs >= sinceMs }
                    .groupBy { it.trackUri }
                    .map { (uri, group) -> TrackListening(uri, group.count { it.counted }, group.sumOf { it.listenedMs }) }
            }
        }

        override fun firstStartedAtMs(): Flow<Long?> = playbacks.map { all -> all.minOfOrNull { it.startedAtMs } }

        override suspend fun startedBetween(fromMs: Long, toMs: Long, limit: Int, offset: Int): List<Listening> =
            error("not used")

        override suspend fun countStartedBetween(fromMs: Long, toMs: Long): Int = error("not used")
    }

    private class FakePlayHistory(counts: Map<String, TrackPlays>) : PlayHistoryRepository {
        private val plays = flowOf(counts)

        override suspend fun recordPlay(trackUri: String, playedAtMs: Long) = Unit

        override fun plays(): Flow<Map<String, TrackPlays>> = plays
    }

    private val tracks = listOf(libraryTrack(1, album = 10, artist = 1), libraryTrack(2, album = 20, artist = 2))
    private val history = FakeListeningHistory()
    private val clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC)

    private fun viewModel(counts: Map<String, TrackPlays> = emptyMap()) =
        StatsViewModel(history, FakePlayHistory(counts), FakeTrackLibrary(tracks), clock)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `opens on the last week, from exactly seven days ago, and says when statistics start`() = runTest {
        history.record(Listening("old", "content://music/1", NOW - WEEK_MS - 1, 100_000, counted = true))
        history.record(Listening("edge", "content://music/2", NOW - WEEK_MS, 60_000, counted = true))
        history.record(Listening("skip", "content://music/1", NOW - 1_000, 20_000, counted = false))
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }

        val state = vm.state.value
        assertThat(history.asked).containsExactly(NOW - WEEK_MS)
        assertThat(state.loading).isFalse()
        assertThat(state.range).isEqualTo(StatsRange.WEEK)
        assertThat(state.sinceMs).isEqualTo(NOW - WEEK_MS - 1)
        assertThat(state.stats.listenedMs).isEqualTo(80_000)
        assertThat(state.stats.plays).isEqualTo(1)
        // Song 1 was only skipped through this week: its time counts, but it is not among the most played.
        assertThat(state.stats.songs.map { it.title }).containsExactly("Song 2")
    }

    @Test
    fun `all time takes in the play counts from before, and new playbacks show at once`() = runTest {
        history.record(Listening("p1", "content://music/1", NOW - 1_000, 200_000, counted = true))
        val vm = viewModel(counts = mapOf("content://music/2" to TrackPlays(7, 0), "content://music/1" to TrackPlays(1, 0)))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }

        vm.select(StatsRange.ALL_TIME)
        assertThat(history.asked.last()).isNull()
        assertThat(vm.state.value.range).isEqualTo(StatsRange.ALL_TIME)
        assertThat(vm.state.value.stats.plays).isEqualTo(8)
        assertThat(vm.state.value.stats.artists.map { it.title }).containsExactly("Artist 2", "Artist 1").inOrder()

        history.record(Listening("p2", "content://music/1", NOW, 180_000, counted = true))
        assertThat(vm.state.value.stats.plays).isEqualTo(9)
        assertThat(vm.state.value.stats.listenedMs).isEqualTo(380_000)
    }

    private companion object {
        const val NOW = 1_790_000_000_000L
        const val WEEK_MS = 7 * 24 * 60 * 60 * 1000L
    }
}
