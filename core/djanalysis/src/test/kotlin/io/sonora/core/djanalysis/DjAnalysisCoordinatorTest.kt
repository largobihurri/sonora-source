package io.sonora.core.djanalysis

import com.google.common.truth.Truth.assertThat
import dagger.Lazy
import io.sonora.core.djmodel.AnalysisStatus
import io.sonora.core.djmodel.DeviceLoad
import io.sonora.core.djmodel.DjAnalysisControl
import io.sonora.core.djmodel.DjAnalysisStatusSource
import io.sonora.core.djmodel.DjAnalysisLimits
import io.sonora.core.djmodel.DjModeSettings
import io.sonora.core.domain.conversion.PerformanceMode
import io.sonora.core.domain.conversion.ThermalLevel
import io.sonora.core.domain.model.PlaybackState
import io.sonora.core.domain.model.QueueItem
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.PlaybackController
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DjAnalysisCoordinatorTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val settings = FakeSettings()
    private val player = MutableStateFlow(PlaybackState())
    private val playback = CountingLazy { controller(player) }
    private var loadSubscribed = false
    private val deviceLoad = MutableStateFlow(DeviceLoad())
    private val load = DeviceLoadSource { flow { loadSubscribed = true; emitAll(deviceLoad) } }
    private val store = FakeStore()
    private val passes = OneAnalysis()
    private val runner = CountingLazy { DjAnalysisRunner({ audioId(it.length) }, store, passes, RecordingLog(), false) }
    private val scheduleLog = RecordingLog()
    private val limits = DjAnalysisLimitsHolder()
    private val dubEnabled = MutableStateFlow(false)
    private val coordinator = DjAnalysisCoordinator(
        settings, playback, load, runner, scope, scheduleLog, limits, dubEnabled = dubEnabled,
        monotonicNanos = { dispatcher.scheduler.currentTime * 1_000_000L },
    )

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `with DJ mode off nothing is created, subscribed or analysed`() {
        player.value = playing("content://a")

        coordinator.start()
        scope.advanceUntilIdle()

        assertThat(playback.created).isFalse()
        assertThat(runner.created).isFalse()
        assertThat(loadSubscribed).isFalse()
        assertThat(coordinator.status.value).isEmpty()
        assertThat(scheduleLog.infos).isEmpty()
    }

    @Test
    fun `turning DJ mode on analyses the playing track and stores it`() {
        player.value = playing("content://a")
        coordinator.start()
        scope.advanceUntilIdle()

        settings.setEnabled(true)
        scope.advanceUntilIdle()

        assertThat(coordinator.status.value).containsExactly("content://a", AnalysisStatus.Analysed)
        assertThat(store.rows.keys).containsExactly(audioId("content://a".length))
        assertThat(passes.runs).containsExactly("content://a")
    }

    @Test
    fun `DUB mode alone also analyses the playing track, for its echo's beat grid`() {
        player.value = playing("content://a")
        coordinator.start()
        scope.advanceUntilIdle()
        assertThat(runner.created).isFalse()

        dubEnabled.value = true
        scope.advanceUntilIdle()

        assertThat(coordinator.status.value).containsExactly("content://a", AnalysisStatus.Analysed)
        assertThat(passes.runs).containsExactly("content://a")
    }

    @Test
    fun `reanalyse runs the analysis again for the playing track and replaces the stored one`() {
        val id = audioId("content://a".length)
        store.rows[id] = analysis(durationMs = 999)
        player.value = playing("content://a")
        settings.setEnabled(true)
        coordinator.start()
        scope.advanceUntilIdle()
        assertThat(passes.runs).isEmpty()

        val control: DjAnalysisControl = coordinator
        control.reanalyse("content://a")
        scope.advanceUntilIdle()

        assertThat(passes.runs).containsExactly("content://a")
        assertThat(store.replaceCalls).isEqualTo(1)
        assertThat(store.rows[id]!!.durationMs).isEqualTo(1_000)
        assertThat(coordinator.status.value).containsExactly("content://a", AnalysisStatus.Analysed)
    }

    @Test
    fun `retry gives a failed playing track a new attempt that keeps an existing analysis`() {
        passes.failures += "content://a"
        player.value = playing("content://a")
        settings.setEnabled(true)
        coordinator.start()
        scope.advanceUntilIdle()
        assertThat(coordinator.status.value["content://a"]).isInstanceOf(AnalysisStatus.Failed::class.java)

        passes.failures.clear()
        coordinator.retry("content://a")
        scope.advanceUntilIdle()

        assertThat(coordinator.status.value).containsExactly("content://a", AnalysisStatus.Analysed)
        assertThat(passes.runs).containsExactly("content://a", "content://a")
        assertThat(store.putCalls).isEqualTo(1)
        assertThat(store.replaceCalls).isEqualTo(0)
    }

    @Test
    fun `a request made while DJ mode is off does nothing`() {
        player.value = playing("content://a")
        coordinator.start()
        scope.advanceUntilIdle()

        coordinator.reanalyse("content://a")
        coordinator.retry("content://a")
        scope.advanceUntilIdle()

        assertThat(passes.runs).isEmpty()
        assertThat(runner.created).isFalse()
    }

    @Test
    fun `the coordinator is the status source the DJ screens read`() {
        val source: DjAnalysisStatusSource = coordinator
        val status = source.status // reading it starts the queue, with no call to start()
        player.value = playing("content://a")
        settings.setEnabled(true)
        scope.advanceUntilIdle()

        assertThat(status.value).containsExactly("content://a", AnalysisStatus.Analysed)
    }

    @Test
    fun `tracks appended to a paused queue start the look-ahead at once`() {
        val current = "content://cur"
        store.rows[audioId(current.length)] = analysis()
        store.rows[audioId("content://a1".length)] = analysis() // already analysed earlier
        store.rows[audioId("content://bb22".length)] = analysis()
        player.value = paused(current)
        settings.setEnabled(true)
        coordinator.start()
        scope.advanceUntilIdle()
        assertThat(coordinator.status.value).containsExactly(current, AnalysisStatus.Analysed)

        for (uri in listOf("content://a1", "content://bb22", "content://ccc333")) {
            player.value = player.value.appended(uri)
        }
        scope.runCurrent()

        assertThat(passes.runs).containsExactly("content://ccc333")
        assertThat(coordinator.status.value).containsExactly(
            current, AnalysisStatus.Analysed,
            "content://a1", AnalysisStatus.Analysed,
            "content://bb22", AnalysisStatus.Analysed,
            "content://ccc333", AnalysisStatus.Analysed,
        )
    }

    @Test
    fun `the scheduling of an appended track is logged with its latency`() {
        val current = "content://cur"
        store.rows[audioId(current.length)] = analysis()
        player.value = paused(current)
        settings.setEnabled(true)
        coordinator.start()
        scope.advanceUntilIdle()
        scheduleLog.infos.clear()

        player.value = player.value.appended("content://ccc333")
        scope.runCurrent()

        val tag = ScheduleTraceLines.tag("content://ccc333")
        assertThat(scheduleLog.infos).containsAtLeast(
            "schedule input playing=${ScheduleTraceLines.tag(current)} upNext=1 load=ok",
            "schedule queued uri=$tag after=0ms",
            "schedule identifying uri=$tag after=0ms",
            "schedule started uri=$tag id=ap1:${audioId("content://ccc333".length).hash.take(12)} after=0ms",
        ).inOrder()
    }

    @Test
    fun `start is idempotent`() {
        coordinator.start()
        coordinator.start()

        assertThat(coordinator.status).isSameInstanceAs(coordinator.status)
    }

    @Test
    fun `normal mode analyses the playing track and looks three tracks ahead`() {
        player.value = queue(current = 0, uri(0), uri(1), uri(2), uri(3), uri(4), uri(5))
        settings.setEnabled(true)
        coordinator.start()
        scope.advanceUntilIdle()

        assertThat(coordinator.status.value.keys).containsExactly(uri(0), uri(1), uri(2), uri(3)).inOrder()
        assertThat(passes.runs).containsExactly(uri(0), uri(1), uri(2), uri(3)).inOrder()
    }

    @Test
    fun `high performance looks five tracks ahead`() {
        settings.setPerformance(PerformanceMode.HIGH_PERFORMANCE)
        player.value = queue(current = 0, uri(0), uri(1), uri(2), uri(3), uri(4), uri(5), uri(6))
        settings.setEnabled(true)
        coordinator.start()
        scope.advanceUntilIdle()

        assertThat(passes.runs).hasSize(6)
        assertThat(coordinator.status.value.keys).doesNotContain(uri(6))
    }

    @Test
    fun `low resources analyses only the playing track`() {
        settings.setPerformance(PerformanceMode.LOW_RESOURCES)
        player.value = queue(current = 0, uri(0), uri(1), uri(2))
        settings.setEnabled(true)
        coordinator.start()
        scope.advanceUntilIdle()

        assertThat(passes.runs).containsExactly(uri(0))
        assertThat(coordinator.status.value).containsExactly(uri(0), AnalysisStatus.Analysed)
    }

    @Test
    fun `low resources still analyses the next track once it is the playing one`() {
        settings.setPerformance(PerformanceMode.LOW_RESOURCES)
        player.value = queue(current = 0, uri(0), uri(1))
        settings.setEnabled(true)
        coordinator.start()
        scope.advanceUntilIdle()

        player.value = queue(current = 1, uri(0), uri(1))
        scope.advanceUntilIdle()

        assertThat(passes.runs).containsExactly(uri(0), uri(1)).inOrder()
    }

    @Test
    fun `moderate heat steps high performance down to the normal look-ahead`() {
        settings.setPerformance(PerformanceMode.HIGH_PERFORMANCE)
        deviceLoad.value = DeviceLoad(thermalLevel = ThermalLevel.MODERATE)
        player.value = queue(current = 0, uri(0), uri(1), uri(2), uri(3), uri(4), uri(5), uri(6))
        settings.setEnabled(true)
        coordinator.start()
        scope.advanceUntilIdle()

        assertThat(passes.runs).hasSize(4) // the playing track and three ahead, not five
        assertThat(limits.current().budget.mode).isEqualTo(PerformanceMode.NORMAL)
    }

    @Test
    fun `battery saver steps normal down to low resources and holds nothing but the playing track`() {
        deviceLoad.value = DeviceLoad(batterySaver = true)
        player.value = queue(current = 0, uri(0), uri(1), uri(2))
        settings.setEnabled(true)
        coordinator.start()
        scope.advanceUntilIdle()

        assertThat(passes.runs).containsExactly(uri(0))
        assertThat(coordinator.status.value.keys).containsExactly(uri(0))
        assertThat(limits.current().budget.mode).isEqualTo(PerformanceMode.LOW_RESOURCES)
    }

    @Test
    fun `the limits for the analyser follow the chosen mode and the load`() {
        assertThat(limits.current()).isEqualTo(DjAnalysisLimits.of(PerformanceMode.NORMAL, DeviceLoad()))
        player.value = queue(current = 0, uri(0))
        settings.setEnabled(true)
        coordinator.start()
        scope.advanceUntilIdle()

        settings.setPerformance(PerformanceMode.HIGH_PERFORMANCE)
        scope.advanceUntilIdle()
        assertThat(limits.current().budget.intraOpThreads).isEqualTo(4)

        deviceLoad.value = DeviceLoad(thermalThrottled = true, thermalLevel = ThermalLevel.SEVERE)
        scope.advanceUntilIdle()
        assertThat(limits.current().budget.mode).isEqualTo(PerformanceMode.LOW_RESOURCES)
        assertThat(limits.current().pauseNow).isTrue()
    }

    /** Distinct uris of distinct lengths: the fake identifier hashes the length, so equal lengths would be duplicates. */
    private fun uri(n: Int) = "content://" + "t".repeat(n + 1)

    private fun queue(current: Int, vararg uris: String): PlaybackState {
        val items = uris.mapIndexed { i, uri ->
            QueueItem(i + 1L, Track(i + 1L, uri, "t", "a", "b", null, 1_000, null, null, null, 0, artworkUri = null, mimeType = "audio/flac", sizeBytes = 0))
        }
        return PlaybackState(currentTrack = items[current].track, queue = items, currentIndex = current)
    }

    private fun playing(uri: String): PlaybackState {
        val track = Track(1, uri, "t", "a", "b", null, 1_000, null, null, null, 0, artworkUri = null, mimeType = "audio/flac", sizeBytes = 0)
        return PlaybackState(currentTrack = track, queue = listOf(QueueItem(1, track)), currentIndex = 0)
    }

    private fun paused(uri: String): PlaybackState = playing(uri).copy(isPlaying = false)

    private fun PlaybackState.appended(uri: String): PlaybackState {
        val track = Track(queue.size + 1L, uri, "t", "a", "b", null, 1_000, null, null, null, 0, artworkUri = null, mimeType = "audio/flac", sizeBytes = 0)
        return copy(queue = queue + QueueItem(queue.size + 1L, track))
    }

    /** A [PlaybackController] whose only member in use is its state. */
    private fun controller(state: StateFlow<PlaybackState>): PlaybackController =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PlaybackController::class.java)) { _, method, _ ->
            if (method.name == "getState") state else throw UnsupportedOperationException(method.name)
        } as PlaybackController

    private class CountingLazy<T>(private val create: () -> T) : Lazy<T> {
        private var value: T? = null
        val created: Boolean get() = value != null

        override fun get(): T = value ?: create().also { value = it }
    }

    private class FakeSettings : DjModeSettings {
        private val state = MutableStateFlow(false)
        override val enabled: StateFlow<Boolean> = state

        override fun setEnabled(enabled: Boolean) {
            state.value = enabled
        }

        private val mode = MutableStateFlow(PerformanceMode.NORMAL)
        override val performance: StateFlow<PerformanceMode> = mode

        override fun setPerformance(performance: PerformanceMode) {
            mode.value = performance
        }
    }

    private class OneAnalysis : AnalysisPasses {
        val runs = ArrayList<String>()
        val failures = HashSet<String>()

        override suspend fun full(
            sourceUri: String,
            audioId: io.sonora.core.djmodel.AudioId,
            onProgress: (io.sonora.core.djmodel.AnalysisStage, Float) -> Unit,
        ): PassResult<io.sonora.core.djmodel.DjAnalysis> {
            runs += sourceUri
            if (sourceUri in failures) error("decoder exploded")
            return PassResult(analysis(), emptyMap(), 1_000)
        }

        override suspend fun waveform(
            sourceUri: String,
            onProgress: (io.sonora.core.djmodel.AnalysisStage, Float) -> Unit,
        ): PassResult<io.sonora.core.djmodel.BandWaveform> = error("not expected")
    }
}
