package io.sonora.core.playback.loop

import android.net.Uri
import androidx.media3.common.FlagSet
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.Accelerator
import io.sonora.core.djmodel.AnalysisProvenance
import io.sonora.core.djmodel.BandWaveform
import io.sonora.core.djmodel.BeatSource
import io.sonora.core.djmodel.DjAnalysis
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.DjModeSettings
import io.sonora.core.djmodel.DjQuantizeSettings
import io.sonora.core.djmodel.DjTrackAnalyses
import io.sonora.core.djmodel.TempoSegment
import io.sonora.core.dubmodel.DubModeSettings
import io.sonora.core.domain.conversion.PerformanceMode
import io.sonora.core.domain.playback.ClockSample
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The DJ loop controller on a fake player and fake settings: what the buttons post, what the screen shows, when it clears. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LoopControllerTest {

    private class FakeDjMode(on: Boolean = true) : DjModeSettings {
        override val enabled = MutableStateFlow(on)
        override fun setEnabled(enabled: Boolean) {
            this.enabled.value = enabled
        }

        override val performance: StateFlow<PerformanceMode> = MutableStateFlow(PerformanceMode.DEFAULT)
        override fun setPerformance(performance: PerformanceMode) = Unit
    }

    private class FakeDubMode : DubModeSettings {
        override val enabled = MutableStateFlow(false)
        override fun setEnabled(enabled: Boolean) {
            this.enabled.value = enabled
        }
    }

    private class FakeQuantize(on: Boolean = true) : DjQuantizeSettings {
        override val quantize = MutableStateFlow(on)
        override fun setQuantize(on: Boolean) {
            quantize.value = on
        }
    }

    private class FakeAnalyses : DjTrackAnalyses {
        val byUri = HashMap<String, MutableStateFlow<DjAnalysis?>>()
        val asked = ArrayList<String>()

        fun flowOf(uri: String) = byUri.getOrPut(uri) { MutableStateFlow(null) }

        override fun analysisOf(uri: String): Flow<DjAnalysis?> {
            asked += uri
            return flowOf(uri)
        }
    }

    /** A player whose position is set by the test; seeks are recorded and reported to the listeners like Media3 does. */
    private class FakePlayer {
        var current: MediaItem? = item("content://song/1")
        var positionMs = 0L
        val seeks = ArrayList<Long>()
        val listeners = ArrayList<Player.Listener>()

        fun seek(targetMs: Long) {
            val old = info(positionMs)
            positionMs = targetMs
            listeners.toList().forEach { it.onPositionDiscontinuity(old, info(targetMs), Player.DISCONTINUITY_REASON_SEEK) }
        }

        fun transition(to: MediaItem?) {
            current = to
            listeners.toList().forEach { it.onMediaItemTransition(to, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) }
        }

        private fun info(position: Long) = Player.PositionInfo(null, 0, current, null, 0, position, position, -1, -1)

        val player: Player = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "getCurrentMediaItem" -> current
                    "getCurrentPosition" -> positionMs
                    "seekTo" -> {
                        val target = args.last() as Long
                        seeks += target
                        seek(target)
                        null
                    }
                    "addListener" -> listeners.add(args[0] as Player.Listener).let { null }
                    "removeListener" -> listeners.remove(args[0] as Player.Listener).let { null }
                    "hashCode" -> 1
                    "equals" -> args[0] === this
                    "toString" -> "FakePlayer"
                    else -> throw UnsupportedOperationException(method.name)
                }
            },
        ) as Player

        companion object {
            fun item(uri: String) = MediaItem.Builder().setMediaId("7:77").setUri(Uri.parse(uri)).build()
        }
    }

    private val analyses = FakeAnalyses()
    private val quantize = FakeQuantize()
    private val djMode = FakeDjMode()
    private val dubMode = FakeDubMode()
    private val fake = FakePlayer()

    private val created = ArrayList<LoopController>()

    private fun TestScope.controller(): LoopController =
        LoopController(analyses, quantize, djMode, dubMode).also {
            created += it
            it.attach(fake.player, CoroutineScope(StandardTestDispatcher(testScheduler)))
        }

    /** A test that ends with every controller detached: the seek based loop would otherwise keep the test clock busy for ever. */
    private fun loopTest(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            created.forEach { it.detach() }
        }
    }

    private fun grid(bpm: Double = 120.0) = DjBeatGrid(listOf(TempoSegment(0.0, bpm, 1)))

    private fun analysis(grid: DjBeatGrid? = grid(), durationMs: Long = 240_000L) = DjAnalysis(
        durationMs = durationMs,
        grid = grid,
        key = null,
        waveform = BandWaveform(ByteArray(150), ByteArray(150), ByteArray(150), ByteArray(150), version = 1),
        provenance = AnalysisProvenance(1, BeatSource.MODEL, "beat-this", Accelerator.CPU, 1L),
    )

    private fun drain(controller: LoopController): List<LoopRequest> {
        val requests = ArrayList<LoopRequest>()
        while (true) requests += controller.hub.poll() ?: break
        return requests
    }

    private fun snapshot(
        available: Boolean = true,
        marked: Double? = null,
        loopIn: Double? = null,
        loopOut: Double? = null,
        armed: Boolean = false,
        engaged: Boolean = false,
        markedGrid: Double? = null,
        loopInGrid: Double? = null,
        loopOutGrid: Double? = null,
    ) = LoopSnapshot(available, marked, loopIn, loopOut, armed, engaged, markedGrid, loopInGrid, loopOutGrid)

    @Test
    fun `the buttons become requests for the audio thread`() = loopTest {
        val controller = controller()
        runCurrent()

        controller.loopIn()
        controller.loopOut()
        controller.beatLoop(4.0)
        controller.halve()
        controller.double()
        controller.exit()
        controller.reloop()
        controller.clear()

        assertThat(drain(controller)).containsExactly(
            LoopRequest.MarkIn,
            LoopRequest.MarkOut,
            LoopRequest.BeatLoop(4.0),
            LoopRequest.HalveLoop,
            LoopRequest.DoubleLoop,
            LoopRequest.ExitLoop,
            LoopRequest.Reloop,
            LoopRequest.ClearLoop,
        ).inOrder()
    }

    @After
    fun restoreLog() {
        DjLoopLog.enabled = DjLoopLog.defaultEnabled
        DjLoopLog.sink = DjLoopLog.defaultSink
    }

    @Test
    fun `the log says why loops are or are not available, once per change`() = loopTest {
        val lines = ArrayList<String>()
        DjLoopLog.enabled = { true }
        DjLoopLog.sink = { lines += it }
        val controller = controller()
        runCurrent()

        controller.hub.publish(snapshot(available = true))
        controller.hub.publish(snapshot(available = true, marked = 1_000.0))
        controller.hub.publish(snapshot(available = false))

        assertThat(lines).contains("loops available=true (sink hosts=true, DJ mode=true, player=true)")
        assertThat(lines).contains("loops available=false (sink hosts=false, DJ mode=true, player=true)")
        assertThat(lines.count { it.startsWith("loops available=") }).isEqualTo(2)
    }

    @Test
    fun `the log is silent, and its messages are not even built, while the tag is off`() = loopTest {
        var built = 0
        DjLoopLog.enabled = { false }
        DjLoopLog.sink = { built++ }
        val controller = controller()
        runCurrent()

        controller.hub.publish(snapshot(available = true))
        DjLoopLog.v { built++.toString() }

        assertThat(built).isEqualTo(0)
    }

    @Test
    fun `engage posts the saved loop's grid times for the audio thread`() = loopTest {
        val controller = controller()
        runCurrent()

        controller.engage(1_000.0, 3_000.0)

        assertThat(drain(controller)).containsExactly(LoopRequest.Engage(1_000.0, 3_000.0))
    }

    @Test
    fun `engage with DJ mode off does nothing`() = loopTest {
        djMode.setEnabled(false)
        val controller = controller()
        runCurrent()

        controller.engage(1_000.0, 3_000.0)

        assertThat(drain(controller)).isEmpty()
    }

    @Test
    fun `the screen also gets the loop in grid time`() = loopTest {
        val controller = controller()
        runCurrent()

        controller.hub.publish(snapshot(marked = 1_500.0, markedGrid = 1_480.0))
        assertThat(controller.loop.value.gridInMs).isEqualTo(1_480.0)
        assertThat(controller.loop.value.gridOutMs).isNull()

        controller.hub.publish(snapshot(loopIn = 1_500.0, loopOut = 3_500.0, loopInGrid = 1_480.0, loopOutGrid = 3_480.0, engaged = true))
        assertThat(controller.loop.value.gridInMs).isEqualTo(1_480.0)
        assertThat(controller.loop.value.gridOutMs).isEqualTo(3_480.0)
    }

    @Test
    fun `with DJ mode off the buttons do nothing and loops are not available`() = loopTest {
        djMode.setEnabled(false)
        val controller = controller()
        runCurrent()

        controller.loopIn()
        controller.beatLoop(1.0)

        assertThat(drain(controller)).isEmpty()
        assertThat(controller.hub.enabled).isFalse()
        assertThat(controller.loop.value.available).isFalse()
    }

    @Test
    fun `DJ mode follows the setting and turning it off clears the loop`() = loopTest {
        val controller = controller()
        runCurrent()
        assertThat(controller.hub.enabled).isTrue()
        drain(controller)

        djMode.setEnabled(false)
        runCurrent()

        assertThat(controller.hub.enabled).isFalse()
        assertThat(drain(controller)).contains(LoopRequest.ClearLoop)
    }

    @Test
    fun `DUB mode keeps the loop hub, and its grid, on while the DJ layout is off`() = loopTest {
        djMode.setEnabled(false)
        val controller = controller()
        runCurrent()
        assertThat(controller.hub.enabled).isFalse()

        dubMode.setEnabled(true)
        runCurrent()
        assertThat(controller.hub.enabled).isTrue()
        controller.loopIn()
        assertThat(drain(controller)).contains(LoopRequest.MarkIn)

        dubMode.setEnabled(false)
        runCurrent()
        assertThat(controller.hub.enabled).isFalse()
    }

    @Test
    fun `the screen shows what the audio thread reports`() = loopTest {
        val controller = controller()
        runCurrent()
        val hub = controller.hub

        hub.publish(snapshot(marked = 1_500.0))
        assertThat(controller.loop.value.inMs).isEqualTo(1_500.0)
        assertThat(controller.loop.value.outMs).isNull()
        assertThat(controller.loop.value.markingIn).isTrue()
        assertThat(controller.loop.value.available).isTrue()

        hub.publish(snapshot(loopIn = 1_500.0, loopOut = 3_500.0, armed = true))
        assertThat(controller.loop.value.armed).isTrue()
        assertThat(controller.loop.value.outMs).isEqualTo(3_500.0)

        hub.publish(snapshot(loopIn = 1_500.0, loopOut = 3_500.0, engaged = true))
        assertThat(controller.loop.value.looping).isTrue()
        assertThat(controller.loop.value.armed).isFalse()

        hub.publish(snapshot(loopIn = 1_500.0, loopOut = 3_500.0))
        assertThat(controller.loop.value.looping).isFalse()
        assertThat(controller.loop.value.canReloop).isTrue()
    }

    @Test
    fun `a loop or a mark holds the playback, so no crossfade starts meanwhile`() = loopTest {
        val controller = controller()
        runCurrent()
        val held = ArrayList<Boolean>()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        scope.launch { controller.holdsPlayback.collect { held += it } }
        runCurrent()

        controller.hub.publish(snapshot(marked = 10.0))
        runCurrent()
        controller.hub.publish(snapshot(loopIn = 10.0, loopOut = 20.0, armed = true))
        runCurrent()
        controller.hub.publish(snapshot(loopIn = 10.0, loopOut = 20.0, engaged = true))
        runCurrent()
        controller.hub.publish(snapshot(loopIn = 10.0, loopOut = 20.0))
        runCurrent()
        scope.cancel()

        assertThat(held).containsExactly(false, true, false).inOrder()
    }

    // --- The track's grid -------------------------------------------------------------------------------------------

    @Test
    fun `the audio thread gets the effective grid of the song playing and Quantize`() = loopTest {
        val controller = controller()
        runCurrent()
        assertThat(analyses.asked).containsExactly("content://song/1")
        assertThat(controller.hub.context.quantizer).isNull()

        analyses.flowOf("content://song/1").value = analysis()
        runCurrent()
        assertThat(controller.hub.context.quantizer!!.hasGrid).isTrue()
        assertThat(controller.hub.context.quantize).isTrue()

        quantize.setQuantize(false)
        runCurrent()
        assertThat(controller.hub.context.quantize).isFalse()
    }

    @Test
    fun `a track without a grid still has a quantizer for manual loops`() = loopTest {
        val controller = controller()
        analyses.flowOf("content://song/1").value = analysis(grid = null)
        runCurrent()

        assertThat(controller.hub.context.quantizer!!.hasGrid).isFalse()
    }

    @Test
    fun `nothing is asked of the analysis while DJ mode is off`() = loopTest {
        djMode.setEnabled(false)
        val controller = controller()
        runCurrent()

        assertThat(analyses.asked).isEmpty()
        assertThat(controller.hub.context.quantizer).isNull()
    }

    // --- Clearing ---------------------------------------------------------------------------------------------------

    @Test
    fun `another song clears the loop and brings its own grid`() = loopTest {
        val controller = controller()
        analyses.flowOf("content://song/1").value = analysis()
        runCurrent()
        drain(controller)

        fake.transition(FakePlayer.item("content://song/2"))
        analyses.flowOf("content://song/2").value = analysis(grid = grid(100.0))
        runCurrent()

        assertThat(drain(controller)).contains(LoopRequest.ClearLoop)
        assertThat(analyses.asked).contains("content://song/2")
        assertThat(controller.hub.context.quantizer).isNotNull()
    }

    @Test
    fun `a seek from anywhere else clears the loop`() = loopTest {
        val controller = controller()
        runCurrent()
        drain(controller)

        fake.seek(30_000)

        assertThat(drain(controller)).containsExactly(LoopRequest.ClearLoop)
    }

    @Test
    fun `the seek the controller makes itself keeps the loop`() = loopTest {
        val controller = controller()
        runCurrent()
        drain(controller)

        // The audio thread asks for a seek back to In: 2000 ms; the controller lands a little before it.
        controller.hub.listener!!.onNeedsSeek(2_000.0)
        runCurrent()

        assertThat(fake.seeks).containsExactly(1_880L)
        assertThat(drain(controller)).isEmpty()
        // The next seek is the user's again.
        fake.seek(10_000)
        assertThat(drain(controller)).containsExactly(LoopRequest.ClearLoop)
    }

    @Test
    fun `a seek that does not help is not repeated in a loop`() = loopTest {
        val controller = controller()
        runCurrent()

        controller.hub.listener!!.onNeedsSeek(2_000.0)
        controller.hub.listener!!.onNeedsSeek(2_000.0)
        runCurrent()

        assertThat(fake.seeks).hasSize(1)
    }

    // --- Too long for the memory: the seek based loop -------------------------------------------------------------

    @Test
    fun `a loop too long for the memory loops by seeking back to In just before Out`() = loopTest {
        val controller = controller()
        runCurrent()
        fake.positionMs = 10_000

        controller.hub.listener!!.onTooLong(10_000.0, 12_000.0)
        runCurrent()

        assertThat(controller.loop.value.looping).isTrue()
        assertThat(controller.loop.value.inMs).isEqualTo(10_000.0)
        assertThat(controller.loop.value.outMs).isEqualTo(12_000.0)

        fake.positionMs = 11_990
        advanceTimeBy(60)
        runCurrent()

        assertThat(fake.seeks).contains(10_000L)
        // Its own jump back does not clear it.
        assertThat(controller.loop.value.looping).isTrue()
    }

    @Test
    fun `the seek based loop can be left and started again, and a new loop replaces it`() = loopTest {
        val controller = controller()
        runCurrent()
        controller.hub.listener!!.onTooLong(10_000.0, 12_000.0)
        runCurrent()

        controller.exit()
        assertThat(controller.loop.value.looping).isFalse()
        assertThat(controller.loop.value.canReloop).isTrue()
        controller.reloop()
        runCurrent()
        assertThat(controller.loop.value.looping).isTrue()

        controller.loopIn()
        assertThat(controller.loop.value.inMs).isNull()
        assertThat(drain(controller)).contains(LoopRequest.MarkIn)
    }

    // --- Clock and detach -----------------------------------------------------------------------------------------

    @Test
    fun `the clock reads the loop being heard`() = loopTest {
        val controller = controller()
        runCurrent()
        controller.hub.publishAudible(AudibleLoop(true, 1_000.0, 3_000.0))

        val sample = ClockSample()
        controller.readInto(sample)

        assertThat(sample.looping).isTrue()
        assertThat(sample.loopInMs).isEqualTo(1_000.0)
        assertThat(sample.loopOutMs).isEqualTo(3_000.0)
    }

    @Test
    fun `detached, the controller is idle and the loop is gone`() = loopTest {
        val controller = controller()
        runCurrent()
        controller.hub.publish(snapshot(loopIn = 1.0, loopOut = 2.0, engaged = true))

        controller.detach()

        assertThat(controller.loop.value.available).isFalse()
        assertThat(controller.loop.value.looping).isFalse()
        assertThat(fake.listeners).isEmpty()
        controller.loopIn()
        assertThat(drain(controller)).isEmpty()
    }
}
