package io.sonora.core.playback.dub

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.google.common.truth.Truth.assertThat
import io.sonora.core.dubmodel.DubBypass
import io.sonora.core.dubmodel.DubPadKind
import io.sonora.core.playback.equalizer.PeakLimiter
import io.sonora.core.playback.output.BitPerfectGate
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Test

/** The DUB stage's sink with a fake sink below it: hosting, float output, the pending-buffer rule and the pads. */
@OptIn(UnstableApi::class)
class DubAudioSinkTest {

    /** The sink below: keeps every config and every buffer it takes; refuses the next [refusals] buffers. */
    private class FakeSink {
        val configs = mutableListOf<Format>()
        val received = mutableListOf<ByteBuffer>()
        val floats = mutableListOf<Float>()
        var refusals = 0
        var positionUs = AudioSink.CURRENT_POSITION_NOT_SET
        var endedCalls = 0

        val sink: AudioSink = Proxy.newProxyInstance(
            AudioSink::class.java.classLoader,
            arrayOf(AudioSink::class.java),
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "configure" -> {
                        configs += (args[0] as AudioSink.AudioSinkConfig).format
                        null
                    }
                    "handleBuffer" -> {
                        val buffer = args[0] as ByteBuffer
                        received += buffer
                        if (refusals > 0) {
                            refusals--
                            return@InvocationHandler false
                        }
                        if (configs.last().pcmEncoding == C.ENCODING_PCM_FLOAT) {
                            val view = buffer.duplicate().order(ByteOrder.nativeOrder())
                            while (view.remaining() >= 4) floats += view.float
                        }
                        buffer.position(buffer.limit())
                        true
                    }
                    "getCurrentPositionUs" -> positionUs
                    "playToEndOfStream" -> endedCalls++.let { null }
                    "hasPendingData" -> false
                    "isEnded" -> endedCalls > 0
                    "getPlaybackParameters" -> PlaybackParameters.DEFAULT
                    "flush", "reset", "play", "pause", "setListener" -> null
                    else -> throw UnsupportedOperationException(method.name)
                }
            },
        ) as AudioSink
    }

    private val hub = DubHub()
    private val below = FakeSink()
    private var armed = false
    private val gate = object : BitPerfectGate {
        override val armed: Boolean get() = this@DubAudioSinkTest.armed
        override fun streamStarting(format: Format) = Unit
    }
    private val sink = DubAudioSink(below.sink, hub, gate)

    private fun format(encoding: Int = C.ENCODING_PCM_16BIT, channels: Int = 2, rate: Int = RATE): Format =
        Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(encoding)
            .setSampleRate(rate)
            .setChannelCount(channels)
            .build()

    private fun configure(format: Format = format()) = sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())

    /** [frames] stereo frames of a 0.4 sine as 16-bit PCM, starting at frame [from]. */
    private fun pcm16(frames: Int, from: Int = 0): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in 0 until frames) {
            val v = (0.4 * sin(2 * PI * 440.0 * (from + f) / RATE) * 32_767).roundToInt().toShort()
            buffer.putShort(v)
            buffer.putShort(v)
        }
        buffer.flip()
        return buffer
    }

    @Test
    fun `with DUB mode off the stream goes on untouched in its own encoding`() {
        configure()
        assertThat(below.configs.single().pcmEncoding).isEqualTo(C.ENCODING_PCM_16BIT)
        val input = pcm16(256)
        assertThat(sink.handleBuffer(input, 0, 1)).isTrue()
        assertThat(below.received.single()).isSameInstanceAs(input)
        assertThat(hub.hosting).isEqualTo(DubHostingDecision.Bypassed(null))
    }

    @Test
    fun `hosted, the stream goes on as float, delayed only by the limiter's look-ahead`() {
        hub.enabled = true
        configure()
        assertThat(below.configs.single().pcmEncoding).isEqualTo(C.ENCODING_PCM_FLOAT)
        assertThat(hub.hosting).isInstanceOf(DubHostingDecision.Hosted::class.java)
        val frames = 2_048
        val input = pcm16(frames)
        val expected = FloatArray(frames) { input.getShort(it * 4) / 32_768f }
        assertThat(sink.handleBuffer(input, 0, 1)).isTrue()
        assertThat(input.hasRemaining()).isFalse()
        val latency = (PeakLimiter.LOOKAHEAD_SECONDS * RATE).roundToInt()
        for (f in latency until frames) assertThat(below.floats[2 * f]).isEqualTo(expected[f - latency])
    }

    /** [frames] stereo frames of a full-scale square (16-bit ±32767, 50-frame half periods): a loud master's peaks. */
    private fun fullScale16(frames: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in 0 until frames) {
            val v: Short = if ((f / 50) % 2 == 0) 32_767 else -32_767
            buffer.putShort(v)
            buffer.putShort(v)
        }
        buffer.flip()
        return buffer
    }

    @Test
    fun `with nothing engaged a full-scale stream goes on bit for bit, only delayed`() {
        hub.enabled = true
        configure()
        val frames = 4_096
        val input = fullScale16(frames)
        val expected = FloatArray(frames) { input.getShort(it * 4) / 32_768f }
        assertThat(sink.handleBuffer(input, 0, 1)).isTrue()
        val latency = (PeakLimiter.LOOKAHEAD_SECONDS * RATE).roundToInt()
        for (f in latency until frames) assertThat(below.floats[2 * f]).isEqualTo(expected[f - latency])
        assertThat(hub.gainReductionDb).isEqualTo(0f)
    }

    @Test
    fun `with a throw in flight the stage's output stays under the limiter's ceiling`() {
        hub.enabled = true
        configure()
        hub.post(DubEvent(DubPadKind.THROW, engaged = true, uptimeNanos = 0))
        repeat(8) { assertThat(sink.handleBuffer(fullScale16(4_096), 0, 1)).isTrue() }
        assertThat(below.floats.maxOf { kotlin.math.abs(it) }).isAtMost(PeakLimiter.CEILING)
    }

    @Test
    fun `a buffer in which the stage settles back to idle is still limited`() {
        hub.enabled = true
        configure()
        // A resonant high-pass on a loud master: its edges ring over full scale.
        hub.filterX = 1f
        hub.filterY = 1f
        repeat(2) { assertThat(sink.handleBuffer(fullScale16(16_384), 0, 1)).isTrue() }
        // Back to the centre: the filter fades into its bypass part-way through one of these long buffers.
        hub.filterX = 0f
        repeat(4) { assertThat(sink.handleBuffer(fullScale16(16_384), 0, 1)).isTrue() }
        // Nothing the stage shaped gets past the limiter: no sample is louder than the master's own peak (once idle
        // again the master's peaks come back on the limiter's release, up to their untouched value).
        assertThat(below.floats.maxOf { kotlin.math.abs(it) }).isAtMost(32_767 / 32_768f)
    }

    @Test
    fun `bit-perfect output, a remote receiver and more than two channels bypass the stage and say why`() {
        hub.enabled = true
        armed = true
        configure()
        assertThat(hub.hosting).isEqualTo(DubHostingDecision.Bypassed(DubBypass.BIT_PERFECT))
        armed = false
        hub.remoteRouted = true
        configure()
        assertThat(hub.hosting).isEqualTo(DubHostingDecision.Bypassed(DubBypass.REMOTE_RECEIVER))
        hub.remoteRouted = false
        configure(format(channels = 6))
        assertThat(hub.hosting).isEqualTo(DubHostingDecision.Bypassed(DubBypass.TOO_MANY_CHANNELS))
        configure(format(encoding = C.ENCODING_PCM_8BIT))
        assertThat(hub.hosting).isEqualTo(DubHostingDecision.Bypassed(DubBypass.UNSUPPORTED_FORMAT))
        // Every one of them went on in its own encoding.
        assertThat(below.configs.map { it.pcmEncoding })
            .containsExactly(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_8BIT)
            .inOrder()
    }

    @Test
    fun `a buffer the sink below does not take comes back unchanged and is processed once`() {
        hub.enabled = true
        configure()
        val first = pcm16(512)
        below.refusals = 2
        assertThat(sink.handleBuffer(first, 0, 1)).isFalse()
        val handedOn = below.received.last()
        assertThat(sink.handleBuffer(first, 0, 1)).isFalse()
        assertThat(below.received.last()).isSameInstanceAs(handedOn)
        assertThat(sink.handleBuffer(first, 0, 1)).isTrue()
        assertThat(sink.handleBuffer(pcm16(512, from = 512), 10_667, 1)).isTrue()

        // The same audio through a sink that never refused gives the same floats: the stage did not run twice.
        val reference = FakeSink()
        val other = DubAudioSink(reference.sink, DubHub().apply { enabled = true }, gate)
        other.configure(AudioSink.AudioSinkConfig.Builder(format()).build())
        other.handleBuffer(pcm16(512), 0, 1)
        other.handleBuffer(pcm16(512, from = 512), 10_667, 1)
        assertThat(below.floats).isEqualTo(reference.floats)
    }

    @Test
    fun `a pad edge from the hub reaches the stage at the next buffer`() {
        hub.enabled = true
        configure()
        hub.post(DubEvent(DubPadKind.CUT, engaged = true, uptimeNanos = 0))
        sink.handleBuffer(pcm16(4_800), 0, 1)
        val tail = below.floats.subList(2 * 2_400, 2 * 4_800)
        assertThat(tail.maxOf { abs(it) }).isEqualTo(0f)
        // Pads are ignored once DUB mode is off.
        hub.enabled = false
        hub.post(DubEvent(DubPadKind.CUT, engaged = true, uptimeNanos = 0))
        below.floats.clear()
        sink.handleBuffer(pcm16(4_800, from = 4_800), 100_000, 1)
        assertThat(below.floats.subList(2 * 2_400, 2 * 4_800).maxOf { abs(it) }).isGreaterThan(0.3f)
    }

    @Test
    fun `a flush keeps the stage, a new format starts it again`() {
        hub.enabled = true
        configure()
        sink.handleBuffer(pcm16(1_024), 0, 1)
        sink.flush()
        assertThat(sink.handleBuffer(pcm16(1_024), 500_000, 1)).isTrue()
        configure(format(encoding = C.ENCODING_PCM_FLOAT, rate = 44_100, channels = 1))
        assertThat(below.configs.last().sampleRate).isEqualTo(44_100)
        assertThat(below.configs.last().pcmEncoding).isEqualTo(C.ENCODING_PCM_FLOAT)
    }

    // --- D-10: the tails -------------------------------------------------------------------------------------------

    private class TailListener : DubHubListener {
        var tailsDone = 0
        override fun onHosting(decision: DubHostingDecision) = Unit
        override fun onTailDone() {
            tailsDone++
        }
    }

    private fun silence(frames: Int): ByteBuffer =
        ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.LITTLE_ENDIAN).also { it.position(it.limit()); it.flip() }

    @Test
    fun `under DUB STOP the tail is reported once the output stays below -70 dBFS for 200 ms`() {
        val listener = TailListener().also { hub.listener = it }
        hub.enabled = true
        configure()
        hub.dryMuted = true
        var frame = 0
        while (frame < RATE * 190 / 1_000) {
            sink.handleBuffer(pcm16(960, from = frame), frame * 1_000_000L / RATE, 1)
            frame += 960
        }
        assertThat(listener.tailsDone).isEqualTo(0) // 20 ms of fade plus 170 ms of quiet: not yet
        repeat(10) {
            sink.handleBuffer(pcm16(960, from = frame), frame * 1_000_000L / RATE, 1)
            frame += 960
        }
        assertThat(listener.tailsDone).isEqualTo(1)
        repeat(10) { sink.handleBuffer(pcm16(960, from = frame), 0, 1).also { frame += 960 } }
        assertThat(listener.tailsDone).isEqualTo(1) // once per STOP
        hub.dryMuted = false
        sink.handleBuffer(pcm16(960), 0, 1)
        hub.dryMuted = true
        repeat(30) { sink.handleBuffer(pcm16(960), 0, 1) }
        assertThat(listener.tailsDone).isEqualTo(2)
    }

    @Test
    fun `a tail that never goes quiet is reported after 8 s`() {
        val listener = TailListener().also { hub.listener = it }
        hub.enabled = true
        hub.params = DubParams.of(io.sonora.core.dubmodel.DubSetup(feedback = 0.9f), 240.0)
        configure()
        hub.post(DubEvent(DubPadKind.THROW, engaged = true, uptimeNanos = 0))
        sink.handleBuffer(pcm16(9_600), 0, 1)
        hub.post(DubEvent(DubPadKind.FREEZE, engaged = true, uptimeNanos = 0))
        sink.handleBuffer(pcm16(4_800, from = 9_600), 200_000, 1)
        hub.dryMuted = true
        var seconds = 0.0
        while (listener.tailsDone == 0 && seconds < 9.0) {
            sink.handleBuffer(silence(4_800), 0, 1)
            seconds += 0.1
        }
        assertThat(seconds).isWithin(0.15).of(8.0)
    }

    @Test
    fun `at the end of the stream the tail plays out before the sink below is told`() {
        hub.enabled = true
        hub.params = DubParams.of(io.sonora.core.dubmodel.DubSetup(feedback = 0.5f), 120.0)
        configure()
        hub.post(DubEvent(DubPadKind.THROW, engaged = true, uptimeNanos = System.nanoTime()))
        sink.handleBuffer(pcm16(4_800), 0, 1)
        hub.post(DubEvent(DubPadKind.THROW, engaged = false, uptimeNanos = System.nanoTime()))
        sink.handleBuffer(pcm16(4_800, from = 4_800), 100_000, 1)
        val before = below.floats.size
        below.refusals = 1
        sink.playToEndOfStream()
        assertThat(below.endedCalls).isEqualTo(0)
        assertThat(sink.hasPendingData()).isTrue()
        assertThat(sink.isEnded()).isFalse()
        sink.playToEndOfStream()
        assertThat(below.endedCalls).isEqualTo(1)
        val tail = below.floats.subList(before, below.floats.size)
        // The throw (frames 0 to 4 800) repeats 0.5 s later, 14 400 frames into the tail, then decays until it is
        // quiet; never more than 10 s.
        assertThat(tail.size / 2).isGreaterThan(RATE)
        assertThat(tail.size / 2).isAtMost(RATE * 10)
        assertThat(tail.subList(2 * 14_900, 2 * 19_000).maxOf { abs(it) }).isGreaterThan(0.1f)
        assertThat(tail.takeLast(2 * 960).maxOf { abs(it) }).isLessThan(3.2e-4f)
        assertThat(sink.isEnded()).isTrue()
    }

    @Test
    fun `with nothing ringing only the limiter's look-ahead is played out at the end`() {
        hub.enabled = true
        configure()
        sink.handleBuffer(pcm16(4_800), 0, 1)
        val before = below.floats.size
        sink.playToEndOfStream()
        assertThat(below.endedCalls).isEqualTo(1)
        val latency = (PeakLimiter.LOOKAHEAD_SECONDS * RATE).roundToInt()
        assertThat((below.floats.size - before) / 2).isAtLeast(latency)
        assertThat((below.floats.size - before) / 2).isAtMost(latency + RATE / 4)
        // The last input frames came out: the look-ahead's delay line was emptied into the sink below.
        val lastInput = pcm16(4_800).getShort((4_800 - 1) * 4) / 32_768f
        assertThat(below.floats[before + 2 * (latency - 1)]).isEqualTo(lastInput)
    }

    @Test
    fun `a bypassed stream ends at once`() {
        configure()
        sink.handleBuffer(pcm16(480), 0, 1)
        sink.playToEndOfStream()
        assertThat(below.endedCalls).isEqualTo(1)
    }

    private companion object {
        const val RATE = 48_000
    }
    /** A sink below that takes every buffer and allocates nothing, so only the stage's own allocations count. */
    private class LeanSink : ForwardingAudioSink(
        Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, _ ->
            when (method.name) {
                "flush", "reset", "play", "pause", "setListener" -> null
                else -> throw UnsupportedOperationException(method.name)
            }
        } as AudioSink,
    ) {
        var positionUs = 0L
        override fun configure(config: AudioSink.AudioSinkConfig) = Unit
        override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
            buffer.position(buffer.limit())
            return true
        }
        override fun getCurrentPositionUs(sourceEnded: Boolean): Long = positionUs
        override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
    }

    @Test
    fun `a hosted buffer allocates nothing on the render path`() {
        val threads = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val lean = LeanSink()
        hub.enabled = true
        val stage = DubAudioSink(lean, hub, gate)
        stage.configure(AudioSink.AudioSinkConfig.Builder(format()).build())
        val input = pcm16(480)
        var presentationUs = 0L
        val step = {
            input.rewind()
            lean.positionUs = presentationUs
            stage.handleBuffer(input, presentationUs, 1)
            presentationUs += 10_000L
        }
        repeat(200) { step() }
        val id = Thread.currentThread().id
        val before = threads.getThreadAllocatedBytes(id)
        repeat(2_000) { step() }
        // Even one boxed value per buffer (16 bytes) would come to 32 KB here.
        assertThat(threads.getThreadAllocatedBytes(id) - before).isLessThan(4096L)
    }

}
