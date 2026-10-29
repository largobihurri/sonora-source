package io.sonora.feature.dub

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.PlaybackState
import io.sonora.core.dubmodel.DubDivision
import io.sonora.core.dubmodel.DubMeters
import io.sonora.core.dubmodel.DubPadKind
import io.sonora.core.dubmodel.DubSetup
import io.sonora.core.dubmodel.DubSetupRepository
import io.sonora.core.dubmodel.DubStage
import io.sonora.core.dubmodel.DubStageState
import io.sonora.core.dubmodel.PreampWay
import io.sonora.feature.dub.controls.XyPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The DUB screen's view model on a fake stage, setup store and player state. */
@OptIn(ExperimentalCoroutinesApi::class)
class DubViewModelTest {

    private class FakeStage : DubStage {
        override val state = MutableStateFlow(DubStageState(enabled = true, hosted = true))
        val calls = ArrayList<String>()
        var meterReads = 0
        override fun meters(): DubMeters = DubMeters(peak = 0.5f).also { meterReads++ }
        override fun setPad(pad: DubPadKind, engaged: Boolean, uptimeNanos: Long) {
            calls += "pad $pad $engaged $uptimeNanos"
        }
        override fun setFilter(x: Float, y: Float) {
            calls += "filter $x $y"
        }
        override fun setKilled(way: PreampWay, killed: Boolean) {
            calls += "kill $way $killed"
        }
        override fun tap(uptimeNanos: Long) {
            calls += "tap $uptimeNanos"
        }
        override fun clearTap() {
            calls += "clearTap"
        }
        override fun stop() {
            calls += "stop"
        }
        override fun play() {
            calls += "play"
        }
        override fun releaseAll() {
            calls += "releaseAll"
        }
    }

    private class FakeSetups : DubSetupRepository {
        val stored = MutableStateFlow(DubSetup())
        val shown = MutableStateFlow(DubSetup())
        override val setup = shown
        override suspend fun update(transform: (DubSetup) -> DubSetup) {
            stored.value = transform(stored.value)
            shown.value = stored.value
        }
        override fun preview(setup: DubSetup) {
            shown.value = setup
        }
    }

    private val dispatcher = StandardTestDispatcher()
    private val stage = FakeStage()
    private val setups = FakeSetups()
    private val playback = MutableStateFlow(PlaybackState(isPlaying = true))

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = DubViewModel(stage, setups, playback)

    private fun dubTest(body: suspend TestScope.() -> Unit) = runTest(dispatcher) { body() }

    @Test
    fun `opening the screen resets the performance state and keeps the setup`() = dubTest {
        setups.update { it.copy(division = DubDivision.EIGHTH, lock = true) }
        val vm = viewModel()
        vm.onFilter(XyPosition(0.9f, 0.2f))
        vm.onKill(PreampWay.SUB, true)
        runCurrent()
        stage.calls.clear()
        vm.opened()
        runCurrent()
        assertThat(vm.performance.value).isEqualTo(DubPerformance())
        assertThat(stage.calls).contains("releaseAll")
        assertThat(vm.setup.value.division).isEqualTo(DubDivision.EIGHTH)
        assertThat(vm.setup.value.lock).isTrue()
    }

    @Test
    fun `a drag previews and its release is stored`() = dubTest {
        val vm = viewModel()
        runCurrent()
        vm.onFeedback(0.7f, final = false)
        runCurrent()
        assertThat(setups.shown.value.feedback).isEqualTo(0.7f)
        assertThat(setups.stored.value.feedback).isEqualTo(DubSetup.DEFAULT_FEEDBACK)
        vm.onFeedback(0.75f, final = true)
        runCurrent()
        assertThat(setups.stored.value.feedback).isEqualTo(0.75f)

        vm.onLevel(PreampWay.BASS, -6.0, final = false)
        runCurrent()
        assertThat(setups.shown.value.preamp[PreampWay.BASS].levelDb).isWithin(0.01f).of(-6f)
        assertThat(setups.stored.value.preamp[PreampWay.BASS].gain).isEqualTo(1f)
        vm.onLevel(PreampWay.BASS, -6.0, final = true)
        vm.onHighPass(PreampWay.TOP, 7_000f, final = true)
        vm.onDivision(DubDivision.HALF)
        vm.onReturn(0.3f, final = true)
        runCurrent()
        assertThat(setups.stored.value.preamp[PreampWay.BASS].levelDb).isWithin(0.01f).of(-6f)
        assertThat(setups.stored.value.preamp[PreampWay.TOP].highPassHz).isEqualTo(7_000f)
        assertThat(setups.stored.value.division).isEqualTo(DubDivision.HALF)
        assertThat(setups.stored.value.returnLevel).isEqualTo(0.3f)
        vm.onResetWay(PreampWay.TOP)
        vm.onResetWay(PreampWay.BASS)
        runCurrent()
        assertThat(setups.stored.value.preamp).isEqualTo(DubSetup().preamp)
    }

    @Test
    fun `pads, the filter, kills and TAP go to the stage with their times in nanoseconds`() = dubTest {
        val vm = viewModel()
        vm.onPad(DubPadKind.THROW, engaged = true, uptimeMillis = 1_234)
        vm.onFilter(XyPosition(0.75f, 0.4f))
        vm.onFilter(XyPosition(0.5f, 0.4f))
        vm.onKill(PreampWay.TOP, true)
        vm.onTap(2_000)
        vm.onClearTap()
        vm.onStop()
        vm.onPlay()
        assertThat(stage.calls).containsAtLeast(
            "pad THROW true 1234000000",
            "filter 0.5 0.4",
            "filter 0.0 0.4",
            "kill TOP true",
            "tap 2000000000",
            "clearTap",
            "stop",
            "play",
        ).inOrder()
        assertThat(vm.performance.value.killed).containsExactly(PreampWay.TOP)
        assertThat(vm.performance.value.filter).isEqualTo(XyPosition(0.5f, 0.4f))
    }

    @Test
    fun `the meters run while playing and stand still while paused`() = dubTest {
        val vm = viewModel()
        val seen = ArrayList<DubMeters>()
        val job = launch { vm.meters.collect { seen += it } }
        advanceTimeBy(500)
        runCurrent()
        val playingReads = stage.meterReads
        assertThat(playingReads).isAtLeast(5)
        assertThat(seen.last().peak).isEqualTo(0.5f)

        playback.value = PlaybackState(isPlaying = false)
        runCurrent()
        val pausedFrom = stage.meterReads
        val pausedSeen = seen.size
        advanceTimeBy(2_000)
        runCurrent()
        assertThat(stage.meterReads).isEqualTo(pausedFrom)
        assertThat(seen.size).isEqualTo(pausedSeen) // no frames while paused
        assertThat(seen.last()).isEqualTo(DubMeters())
        job.cancel()
    }
}
