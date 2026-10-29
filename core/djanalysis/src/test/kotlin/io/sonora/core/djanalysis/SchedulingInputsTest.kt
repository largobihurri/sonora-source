package io.sonora.core.djanalysis

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.DeviceLoad
import io.sonora.core.djmodel.DjAnalysisLimits
import io.sonora.core.djmodel.SchedulingInput
import io.sonora.core.domain.conversion.PerformanceMode
import io.sonora.core.domain.conversion.ThermalLevel
import io.sonora.core.domain.model.PlaybackState
import io.sonora.core.domain.model.QueueItem
import io.sonora.core.domain.model.Track
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SchedulingInputsTest {

    @Test
    fun `nothing plays, nothing to analyse`() {
        assertThat(SchedulingInputs.window(PlaybackState())).isEqualTo(PlayerWindow(null, emptyList()))
    }

    @Test
    fun `the playing track and the next ones in play order`() {
        val state = state(current = 1, "a", "b", "c", "d", "e", "f")

        assertThat(SchedulingInputs.window(state, limit = 3)).isEqualTo(PlayerWindow("b", listOf("c", "d", "e")))
    }

    @Test
    fun `tracks before the playing one are never looked at`() {
        val state = state(current = 4, "a", "b", "c", "d", "e")

        assertThat(SchedulingInputs.window(state, limit = 3)).isEqualTo(PlayerWindow("e", emptyList()))
    }

    @Test
    fun `repeated uris and the playing one do not use up the look-ahead`() {
        val state = state(current = 0, "a", "b", "a", "b", "c", "d", "e")

        assertThat(SchedulingInputs.window(state, limit = 3)).isEqualTo(PlayerWindow("a", listOf("b", "c", "d")))
    }

    @Test
    fun `a limit of zero keeps only the playing track`() {
        assertThat(SchedulingInputs.window(state(current = 0, "a", "b"), limit = 0)).isEqualTo(PlayerWindow("a", emptyList()))
    }

    @Test
    fun `DJ mode off gives one off input and never subscribes to the player or the load`() = runTest {
        var playerSubscribed = false
        var loadSubscribed = false
        val playback = flow<PlaybackState> { playerSubscribed = true }
        val load = flow<DeviceLoad> { loadSubscribed = true }

        val inputs = SchedulingInputs.flow(MutableStateFlow(false), playback, load).take(1).toList()

        assertThat(inputs).containsExactly(SchedulingInput(djModeOn = false, playingUri = null, upNextUris = emptyList()))
        assertThat(playerSubscribed).isFalse()
        assertThat(loadSubscribed).isFalse()
    }

    @Test
    fun `DJ mode on follows the player and the load, not the position updates`() = runTest(UnconfinedTestDispatcher()) {
        val djMode = MutableStateFlow(true)
        val player = MutableStateFlow(state(current = 0, "a", "b"))
        val load = MutableStateFlow(DeviceLoad())
        val collected = async { SchedulingInputs.flow(djMode, player, load).take(4).toList() }

        player.value = player.value.copy(positionMs = 250) // a position update: no new input
        player.value = state(current = 1, "a", "b")
        load.value = DeviceLoad(batterySaver = true)
        djMode.value = false

        assertThat(collected.await()).containsExactly(
            SchedulingInput(true, "a", listOf("b"), DeviceLoad()),
            SchedulingInput(true, "b", emptyList(), DeviceLoad()),
            SchedulingInput(true, "b", emptyList(), DeviceLoad(batterySaver = true)),
            SchedulingInput(false, null, emptyList(), DeviceLoad()),
        ).inOrder()
    }

    @Test
    fun `the chosen mode sets how far ahead the queue is looked at`() = runTest(UnconfinedTestDispatcher()) {
        val player = MutableStateFlow(state(current = 0, "a", "b", "c", "d", "e", "f", "g", "h"))

        fun upNext(mode: PerformanceMode) =
            SchedulingInputs.flow(MutableStateFlow(true), player, MutableStateFlow(DeviceLoad()), MutableStateFlow(mode))

        assertThat(upNext(PerformanceMode.LOW_RESOURCES).first().upNextUris).isEmpty()
        assertThat(upNext(PerformanceMode.NORMAL).first().upNextUris).containsExactly("b", "c", "d").inOrder()
        assertThat(upNext(PerformanceMode.HIGH_PERFORMANCE).first().upNextUris).containsExactly("b", "c", "d", "e", "f").inOrder()
    }

    @Test
    fun `without a chosen mode the default one is used`() = runTest(UnconfinedTestDispatcher()) {
        val player = MutableStateFlow(state(current = 0, "a", "b", "c", "d", "e"))

        val input = SchedulingInputs.flow(MutableStateFlow(true), player, MutableStateFlow(DeviceLoad())).first()

        assertThat(input.upNextUris).containsExactly("b", "c", "d").inOrder()
    }

    @Test
    fun `heat and battery saver step the look-ahead down with the mode`() = runTest(UnconfinedTestDispatcher()) {
        val player = MutableStateFlow(state(current = 0, "a", "b", "c", "d", "e", "f", "g"))
        val high = MutableStateFlow(PerformanceMode.HIGH_PERFORMANCE)

        suspend fun upNext(load: DeviceLoad) =
            SchedulingInputs.flow(MutableStateFlow(true), player, MutableStateFlow(load), high).first().upNextUris.size

        assertThat(upNext(DeviceLoad())).isEqualTo(5)
        assertThat(upNext(DeviceLoad(thermalLevel = ThermalLevel.LIGHT))).isEqualTo(5)
        assertThat(upNext(DeviceLoad(thermalLevel = ThermalLevel.MODERATE))).isEqualTo(3)
        assertThat(upNext(DeviceLoad(batterySaver = true))).isEqualTo(3)
        assertThat(upNext(DeviceLoad(batterySaver = true, thermalLevel = ThermalLevel.MODERATE))).isEqualTo(0)
        assertThat(upNext(DeviceLoad(thermalLevel = ThermalLevel.SEVERE, thermalThrottled = true))).isEqualTo(0)
    }

    @Test
    fun `changing the mode while DJ mode is on emits the new window and publishes the new limits`() = runTest(UnconfinedTestDispatcher()) {
        val player = MutableStateFlow(state(current = 0, "a", "b", "c", "d", "e", "f"))
        val performance = MutableStateFlow(PerformanceMode.NORMAL)
        val published = ArrayList<PerformanceMode>()
        val collected = async {
            SchedulingInputs.flow(MutableStateFlow(true), player, MutableStateFlow(DeviceLoad()), performance) { published += it.budget.mode }
                .take(3)
                .toList()
        }

        performance.value = PerformanceMode.LOW_RESOURCES
        performance.value = PerformanceMode.HIGH_PERFORMANCE

        assertThat(collected.await().map { it.upNextUris.size }).containsExactly(3, 0, 5).inOrder()
        assertThat(published.distinct()).containsExactly(
            PerformanceMode.NORMAL,
            PerformanceMode.LOW_RESOURCES,
            PerformanceMode.HIGH_PERFORMANCE,
        ).inOrder()
    }

    @Test
    fun `the published limits carry the load the mode was derived from`() = runTest(UnconfinedTestDispatcher()) {
        val player = MutableStateFlow(state(current = 0, "a", "b"))
        val load = MutableStateFlow(DeviceLoad())
        val limits = ArrayList<DjAnalysisLimits>()
        val collected = async {
            SchedulingInputs.flow(MutableStateFlow(true), player, load, MutableStateFlow(PerformanceMode.NORMAL)) { limits += it }
                .take(2)
                .toList()
        }

        load.value = DeviceLoad(thermalThrottled = true, thermalLevel = ThermalLevel.SEVERE)
        collected.await()

        assertThat(limits.last().load.hot).isTrue()
        assertThat(limits.last().budget.mode).isEqualTo(PerformanceMode.LOW_RESOURCES)
        assertThat(limits.last().pauseNow).isTrue()
    }

    private fun state(current: Int, vararg uris: String) = PlaybackState(
        currentTrack = track(uris[current]),
        queue = uris.mapIndexed { i, uri -> QueueItem(i.toLong(), track(uri)) },
        currentIndex = current,
    )

    private fun track(uri: String) = Track(
        id = uri.hashCode().toLong(),
        uri = uri,
        title = uri,
        artist = "",
        album = "",
        albumArtist = null,
        durationMs = 1_000,
        trackNumber = null,
        discNumber = null,
        year = null,
        albumId = 0,
        artworkUri = null,
        mimeType = "audio/flac",
        sizeBytes = 0,
    )
}
