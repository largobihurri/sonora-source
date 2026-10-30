package io.sonora.core.playback.dub

import android.net.Uri
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import io.sonora.core.audiodsp.dub.EchoDivision
import io.sonora.core.djmodel.Accelerator
import io.sonora.core.djmodel.AnalysisProvenance
import io.sonora.core.djmodel.BandWaveform
import io.sonora.core.djmodel.BeatSource
import io.sonora.core.djmodel.DjAnalysis
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.DjTrackAnalyses
import io.sonora.core.djmodel.TempoSegment
import io.sonora.core.domain.playback.DjLoopController
import io.sonora.core.domain.playback.DjLoopState
import io.sonora.core.dubmodel.DubBypass
import io.sonora.core.dubmodel.DubClockSource
import io.sonora.core.dubmodel.DubDivision
import io.sonora.core.dubmodel.DubModeSettings
import io.sonora.core.dubmodel.DubPadKind
import io.sonora.core.dubmodel.DubSetup
import io.sonora.core.dubmodel.DubSetupRepository
import io.sonora.core.dubmodel.DubStage
import io.sonora.core.dubmodel.PreampWay
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The DUB controller on a fake player and fake settings: the mode, the settings snapshot, the pads, the bypass. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DubControllerTest {

    private class FakeDubMode(on: Boolean = false) : DubModeSettings {
        override val enabled = MutableStateFlow(on)
        override fun setEnabled(enabled: Boolean) {
            this.enabled.value = enabled
        }
    }

    private class FakeSetups : DubSetupRepository {
        override val setup = MutableStateFlow(DubSetup())
        override suspend fun update(transform: (DubSetup) -> DubSetup) {
            setup.value = transform(setup.value)
        }

        override fun preview(setup: DubSetup) {
            this.setup.value = setup
        }
    }

    private class FakeLoops : DjLoopController {
        override val loop = MutableStateFlow(DjLoopState.NONE)
        var cleared = 0
        override fun loopIn() = Unit
        override fun loopOut() = Unit
        override fun beatLoop(beats: Double) = Unit
        override fun halve() = Unit
        override fun double() = Unit
        override fun exit() = Unit
        override fun reloop() = Unit
        override fun clear() {
            cleared++
            loop.value = DjLoopState.NONE
        }
    }

    private class FakeAnalyses : DjTrackAnalyses {
        val flow = MutableStateFlow<DjAnalysis?>(null)
        var queries = 0
        override fun analysisOf(uri: String): Flow<DjAnalysis?> = flow.also { queries++ }
    }

    /** A player that records the calls the controller makes and tells its listeners what the test says. */
    private class FakePlayer {
        val calls = ArrayList<String>()
        val listeners = ArrayList<Player.Listener>()
        var state = Player.STATE_READY
        var device: DeviceInfo = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_LOCAL).build()
        var speed = 1f
        var playWhenReady = true

        fun pauseFromOutside(reason: Int) {
            playWhenReady = false
            listeners.toList().forEach { it.onPlayWhenReadyChanged(false, reason) }
        }

        fun route(type: Int) {
            device = DeviceInfo.Builder(type).build()
            listeners.toList().forEach { it.onDeviceInfoChanged(device) }
        }

        val player: Player = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "getCurrentMediaItem" -> MediaItem.Builder().setUri(Uri.parse("content://song/1")).build()
                    "getCurrentPosition" -> 30_000L
                    "getPlaybackState" -> state
                    "getDeviceInfo" -> device
                    "getPlaybackParameters" -> PlaybackParameters(speed)
                    "getPlayWhenReady" -> playWhenReady
                    "getCurrentMediaItemIndex" -> 3
                    "stop", "prepare", "play" -> calls.add(method.name).let { null }
                    "pause" -> calls.add("pause").also { playWhenReady = false }.let { null }
                    "seekTo" -> calls.add("seekTo ${args.toList()}").let { null }
                    "addListener" -> listeners.add(args[0] as Player.Listener).let { null }
                    "removeListener" -> listeners.remove(args[0] as Player.Listener).let { null }
                    "hashCode" -> 1
                    "equals" -> args[0] === this
                    "toString" -> "FakePlayer"
                    else -> throw UnsupportedOperationException(method.name)
                }
            },
        ) as Player
    }

    private val mode = FakeDubMode()
    private val setups = FakeSetups()
    private val analyses = FakeAnalyses()
    private val fake = FakePlayer()
    private val loops = FakeLoops()

    private fun TestScope.controller(): DubController =
        DubController(mode, setups, analyses, loops).also { it.attach(fake.player, CoroutineScope(StandardTestDispatcher(testScheduler))) }

    private fun analysis(bpm: Double) = DjAnalysis(
        durationMs = 240_000L,
        grid = DjBeatGrid(listOf(TempoSegment(0.0, bpm, 1))),
        key = null,
        waveform = BandWaveform(ByteArray(150), ByteArray(150), ByteArray(150), ByteArray(150), version = 1),
        provenance = AnalysisProvenance(1, BeatSource.MODEL, "model", Accelerator.CPU, 1L),
    )

    @Test
    fun `the hub follows DUB mode and a change re-prepares the player at the same position`() = runTest {
        val dub = controller()
        runCurrent()
        assertThat(dub.hub.enabled).isFalse()
        assertThat(fake.calls).isEmpty() // the first reading is not a change
        mode.setEnabled(true)
        runCurrent()
        assertThat(dub.hub.enabled).isTrue()
        assertThat(dub.state.value.enabled).isTrue()
        assertThat(fake.calls).containsExactly("stop", "prepare").inOrder()
        fake.calls.clear()
        fake.state = Player.STATE_IDLE
        mode.setEnabled(false)
        runCurrent()
        assertThat(dub.hub.enabled).isFalse()
        assertThat(fake.calls).isEmpty() // nothing to re-prepare while idle
        dub.detach()
    }

    @Test
    fun `the setup and the grid make one snapshot and TAP overrides the grid`() = runTest {
        mode.setEnabled(true) // the grid is only read in DUB mode
        val dub = controller()
        setups.preview(DubSetup(division = DubDivision.DOTTED_EIGHTH, feedback = 0.7f))
        analyses.flow.value = analysis(bpm = 87.5)
        runCurrent()
        assertThat(dub.hub.params.division).isEqualTo(EchoDivision.DOTTED_EIGHTH)
        assertThat(dub.hub.params.feedback).isEqualTo(0.7f)
        assertThat(dub.hub.params.bpm).isEqualTo(87.5)
        assertThat(dub.state.value.clockSource).isEqualTo(DubClockSource.GRID)

        // Four taps 500 ms apart at speed 1.25: 120 BPM heard is 96 BPM of source time.
        fake.speed = 1.25f
        for (i in 0 until 4) dub.tap(1_000_000_000L + i * 500_000_000L)
        runCurrent()
        assertThat(dub.hub.params.bpm).isWithin(0.01).of(96.0)
        assertThat(dub.state.value.clockSource).isEqualTo(DubClockSource.TAP)
        dub.clearTap()
        runCurrent()
        assertThat(dub.hub.params.bpm).isEqualTo(87.5)

        analyses.flow.value = null
        runCurrent()
        assertThat(dub.hub.params.bpm).isEqualTo(DubStage.DEFAULT_BPM)
        assertThat(dub.state.value.clockSource).isEqualTo(DubClockSource.NONE)
        dub.detach()
    }

    @Test
    fun `pads, kills and the filter reach the hub only while DUB mode is on`() = runTest {
        val dub = controller()
        runCurrent()
        dub.setPad(DubPadKind.THROW, engaged = true, uptimeNanos = 5L)
        assertThat(dub.hub.poll()).isNull()
        mode.setEnabled(true)
        runCurrent()
        dub.setPad(DubPadKind.THROW, engaged = true, uptimeNanos = 5L)
        assertThat(dub.hub.poll()).isEqualTo(DubEvent(DubPadKind.THROW, true, 5L))
        dub.setKilled(PreampWay.BASS, true)
        dub.setKilled(PreampWay.TOP, true)
        assertThat(dub.hub.kills).isEqualTo(0b10010)
        dub.setFilter(0.5f, 2f)
        assertThat(dub.hub.filterX).isEqualTo(0.5f)
        assertThat(dub.hub.filterY).isEqualTo(1f)

        val generation = dub.hub.releaseGeneration
        dub.releaseAll()
        assertThat(dub.hub.releaseGeneration).isEqualTo(generation + 1)
        assertThat(dub.hub.kills).isEqualTo(0)
        assertThat(dub.hub.filterX).isEqualTo(0f)
        dub.detach()
    }

    @Test
    fun `a pause for focus loss lets every pad go and other pauses do not`() = runTest {
        val dub = controller()
        runCurrent()
        val generation = dub.hub.releaseGeneration
        fake.listeners.forEach { it.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }
        assertThat(dub.hub.releaseGeneration).isEqualTo(generation)
        fake.listeners.forEach { it.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) }
        assertThat(dub.hub.releaseGeneration).isEqualTo(generation + 1)
        dub.detach()
    }

    @Test
    fun `a remote receiver bypasses the stage and the sink's decision is shown`() = runTest {
        mode.setEnabled(true)
        val dub = controller()
        runCurrent()
        fake.route(DeviceInfo.PLAYBACK_TYPE_REMOTE)
        assertThat(dub.hub.remoteRouted).isTrue()
        assertThat(dub.state.value.bypass).isEqualTo(DubBypass.REMOTE_RECEIVER)
        fake.route(DeviceInfo.PLAYBACK_TYPE_LOCAL)
        assertThat(dub.hub.remoteRouted).isFalse()
        assertThat(dub.state.value.bypass).isNull()

        dub.hub.publishHosting(DubHostingDecision.Bypassed(DubBypass.BIT_PERFECT))
        runCurrent()
        assertThat(dub.state.value.hosted).isFalse()
        assertThat(dub.state.value.bypass).isEqualTo(DubBypass.BIT_PERFECT)
        dub.hub.publishHosting(DubHostingDecision.Hosted(io.sonora.core.djloop.PcmFormat(48_000, 2, io.sonora.core.djloop.PcmEncoding.FLOAT_32)))
        runCurrent()
        assertThat(dub.state.value.hosted).isTrue()
        assertThat(dub.state.value.bypass).isNull()
        dub.detach()
    }

    @Test
    fun `the crossfade is held while DUB mode is on`() = runTest {
        val dub = controller()
        assertThat(dub.holdsCrossfade.first()).isFalse()
        mode.setEnabled(true)
        assertThat(dub.holdsCrossfade.first()).isTrue()
        dub.detach()
    }

    // --- D-10: DUB STOP ---------------------------------------------------------------------------------------------

    private fun TestScope.hostedController(): DubController {
        mode.setEnabled(true)
        val dub = controller()
        runCurrent()
        dub.hub.publishHosting(DubHostingDecision.Hosted(io.sonora.core.djloop.PcmFormat(48_000, 2, io.sonora.core.djloop.PcmEncoding.FLOAT_32)))
        runCurrent()
        fake.calls.clear()
        return dub
    }

    @Test
    fun `DUB STOP fades the dry signal and pauses at the STOP position once the tail is over`() = runTest {
        val dub = hostedController()
        dub.stop()
        assertThat(dub.hub.dryMuted).isTrue()
        assertThat(dub.state.value.stopping).isTrue()
        assertThat(fake.calls).isEmpty() // the player keeps running while the tail rings
        dub.hub.listener!!.onTailDone()
        runCurrent()
        assertThat(fake.calls).containsExactly("pause", "seekTo [3, 30000]").inOrder()
        assertThat(dub.hub.dryMuted).isFalse()
        assertThat(dub.state.value.stopping).isFalse()
        dub.detach()
    }

    @Test
    fun `DUB STOP gives up waiting for the tail after the cap`() = runTest {
        val dub = hostedController()
        dub.stop()
        advanceTimeBy(DubController.STOP_CAP_MS + 1_000)
        runCurrent()
        assertThat(fake.calls).containsExactly("pause", "seekTo [3, 30000]").inOrder()
        assertThat(dub.state.value.stopping).isFalse()
        dub.detach()
    }

    @Test
    fun `DUB STOP while bypassed or paused pauses at once`() = runTest {
        val dub = controller() // DUB mode off: nothing is hosted
        runCurrent()
        dub.stop()
        assertThat(fake.calls).containsExactly("pause")
        assertThat(dub.hub.dryMuted).isFalse()
        dub.detach()
    }

    @Test
    fun `a pause from outside stays immediate and ends the ring out`() = runTest {
        val dub = hostedController()
        fake.pauseFromOutside(Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        assertThat(fake.calls).isEmpty() // no STOP was pressed: nothing to do, the pause was the player's
        fake.playWhenReady = true
        dub.stop()
        fake.pauseFromOutside(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)
        runCurrent()
        assertThat(dub.hub.dryMuted).isFalse()
        assertThat(dub.state.value.stopping).isFalse()
        assertThat(fake.calls).containsExactly("seekTo [3, 30000]")
        dub.detach()
    }

    @Test
    fun `PLAY during the ring out brings the dry signal back`() = runTest {
        val dub = hostedController()
        dub.stop()
        dub.play()
        assertThat(dub.hub.dryMuted).isFalse()
        assertThat(dub.state.value.stopping).isFalse()
        assertThat(fake.calls).isEmpty()
        fake.playWhenReady = false
        dub.play()
        assertThat(fake.calls).containsExactly("play")
        dub.detach()
    }

    @Test
    fun `PLAY on an idle player, a queue restored after a restart, prepares it first`() = runTest {
        val dub = hostedController()
        fake.playWhenReady = false
        fake.state = Player.STATE_IDLE
        dub.play()
        assertThat(fake.calls).containsExactly("prepare", "play").inOrder()
        dub.detach()
    }

    @Test
    fun `DUB STOP during a held loop lets the loop go first`() = runTest {
        val dub = hostedController()
        loops.loop.value = DjLoopState(inMs = 1_000.0, outMs = 3_000.0, looping = true)
        dub.stop()
        assertThat(loops.cleared).isEqualTo(1)
        assertThat(dub.hub.dryMuted).isTrue()
        dub.detach()
    }

    @Test
    fun `turning DUB mode off during the ring out cancels the pending STOP`() = runTest {
        val dub = hostedController()
        dub.stop()
        mode.setEnabled(false)
        runCurrent()
        fake.calls.clear() // the re-prepare of the toggle
        advanceTimeBy(DubController.STOP_CAP_MS + 1_000)
        runCurrent()
        assertThat(fake.calls).isEmpty() // no late pause, no seek back
        assertThat(dub.hub.dryMuted).isFalse()
        assertThat(dub.state.value.stopping).isFalse()
        dub.detach()
    }

    @Test
    fun `with DUB mode off no analysis is queried, not even on a change of song`() = runTest {
        val dub = controller()
        runCurrent()
        fake.listeners.toList().forEach {
            it.onMediaItemTransition(MediaItem.Builder().setUri(Uri.parse("content://song/2")).build(), Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        }
        runCurrent()
        assertThat(analyses.queries).isEqualTo(0)

        mode.setEnabled(true)
        runCurrent()
        assertThat(analyses.queries).isEqualTo(1) // the song playing, once DUB mode is on
        analyses.flow.value = analysis(bpm = 92.0)
        runCurrent()
        assertThat(dub.hub.params.bpm).isEqualTo(92.0)

        mode.setEnabled(false)
        runCurrent()
        assertThat(dub.state.value.clockSource).isEqualTo(DubClockSource.NONE)
        dub.detach()
    }
}
