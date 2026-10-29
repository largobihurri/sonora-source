package io.sonora.core.playback.loop

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import com.google.common.primitives.ImmutableIntArray
import com.google.common.truth.Truth.assertThat
import io.sonora.core.playback.output.BitPerfectGate
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.After
import org.junit.Test

/** The loop sink as the audio renderer drives it, over a fake sink that plays what it is given. */
@OptIn(UnstableApi::class)
class LoopAudioSinkTest {

    private val rate = 48_000
    private val basePts = 1_000_000_000_000L
    private val delay = 1_105

    /** The sink below: keeps what it is handed, answers with the position set, and records the calls that matter. */
    private inner class FakeInner {
        val calls = ArrayList<String>()
        val frames = ArrayList<Int>()
        val pts = ArrayList<Long>()
        val buffersSeen = ArrayList<ByteBuffer>()
        var position = C.TIME_UNSET
        var listener: AudioSink.Listener? = null
        var offsetUs: Long? = null
        var configured: AudioSink.AudioSinkConfig? = null

        /** Frames it takes per call; the buffer is offered again when it takes less than all. */
        var acceptFrames = Int.MAX_VALUE

        val sink: AudioSink = Proxy.newProxyInstance(
            AudioSink::class.java.classLoader,
            arrayOf(AudioSink::class.java),
            InvocationHandler { _, method, args ->
                calls += method.name
                when (method.name) {
                    "handleBuffer" -> {
                        val buffer = args[0] as ByteBuffer
                        buffersSeen += buffer
                        pts += args[1] as Long
                        val n = minOf(buffer.remaining() / 8, acceptFrames)
                        repeat(n) {
                            frames += buffer.order(ByteOrder.LITTLE_ENDIAN).getInt()
                            buffer.getInt()
                        }
                        !buffer.hasRemaining()
                    }
                    "getCurrentPositionUs" -> position
                    "setListener" -> {
                        listener = args[0] as AudioSink.Listener?
                        null
                    }
                    "setOutputStreamOffsetUs" -> {
                        offsetUs = args[0] as Long
                        null
                    }
                    "configure" -> {
                        configured = args[0] as AudioSink.AudioSinkConfig
                        null
                    }
                    "isEnded" -> calls.count { it == "playToEndOfStream" } > 0
                    "hasPendingData" -> false
                    "flush", "reset", "play", "pause", "playToEndOfStream", "handleDiscontinuity", "setVolume" -> null
                    else -> throw UnsupportedOperationException(method.name)
                }
            },
        ) as AudioSink
    }

    private class Gate(override var armed: Boolean) : BitPerfectGate {
        override fun streamStarting(format: Format) = Unit
    }

    private val inner = FakeInner()
    private val hub = LoopHub().apply { enabled = true; context = LoopContext(null, quantize = false) }
    private val refusals = ArrayList<LoopRefusal>()
    private var discontinuities = 0
    private var gate: Gate? = null

    private val sink by lazy { LoopAudioSink(inner.sink, hub, gate) }

    init {
        hub.listener = object : LoopHubListener {
            override fun onSnapshot(snapshot: LoopSnapshot) = Unit
            override fun onNeedsSeek(inMs: Double) = Unit
            override fun onTooLong(inMs: Double, outMs: Double) = Unit
            override fun onRefused(refusal: LoopRefusal) {
                refusals += refusal
            }
        }
    }

    private fun pcm(
        encoding: Int = C.ENCODING_PCM_32BIT,
        delay: Int = this.delay,
        mime: String = MimeTypes.AUDIO_RAW,
    ): Format = Format.Builder()
        .setSampleMimeType(mime)
        .setPcmEncoding(encoding)
        .setSampleRate(rate)
        .setChannelCount(2)
        .setEncoderDelay(delay)
        .build()

    private fun configure(format: Format = pcm(), mapping: ImmutableIntArray? = null) {
        sink.setListener(object : AudioSink.Listener {
            override fun onPositionDiscontinuity() {
                discontinuities++
            }

            override fun onUnderrun(bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) = Unit
            override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) = Unit
        })
        sink.configure(AudioSink.AudioSinkConfig.Builder(format).setOutputChannelMapping(mapping).build())
    }

    private var fed = 0

    private fun buffer(frames: Int = 480): ByteBuffer {
        val b = ByteBuffer.allocate(frames * 8).order(ByteOrder.LITTLE_ENDIAN)
        for (k in 0 until frames) {
            b.putInt(fed + k)
            b.putInt(-(fed + k))
        }
        b.flip()
        return b
    }

    /** Hands the sink [frames] frames of the counter source, one buffer at a time, until it takes them all. */
    private fun feed(frames: Int) {
        var left = frames
        while (left > 0) {
            val n = minOf(480, left)
            val b = buffer(n)
            val pts = basePts + fed * 1_000_000L / rate
            var guard = 0
            while (!sink.handleBuffer(b, pts, 1) && guard++ < 20) Unit
            fed += n
            left -= n
        }
    }

    private fun heardAt(o: Long): Long = basePts + (o - delay) * 1_000_000L / rate

    @Test
    fun `with DJ mode off everything goes straight to the sink below`() {
        hub.enabled = false
        configure()
        val b = buffer()

        assertThat(sink.handleBuffer(b, basePts, 1)).isTrue()
        inner.position = 123_456L

        assertThat(inner.buffersSeen.single()).isSameInstanceAs(b)
        assertThat(inner.pts.single()).isEqualTo(basePts)
        assertThat(sink.getCurrentPositionUs(false)).isEqualTo(123_456L)
        assertThat(hub.snapshot.available).isFalse()
    }

    @Test
    fun `with DJ mode on the audio passes through unchanged and loops are available`() {
        configure()
        feed(9_600)

        assertThat(inner.frames).hasSize(9_600)
        for (i in 0 until 9_600 step 331) assertThat(inner.frames[i]).isEqualTo(i)
        assertThat(inner.pts.first()).isEqualTo(basePts)
        assertThat(hub.snapshot.available).isTrue()
    }

    @Test
    fun `streams loops cannot host go through untouched`() {
        for (format in listOf(pcm(encoding = C.ENCODING_PCM_8BIT), pcm(mime = MimeTypes.AUDIO_MPEG))) {
            hub.enabled = true
            configure(format)
            val b = buffer()
            sink.handleBuffer(b, basePts, 1)

            assertThat(inner.buffersSeen.last()).isSameInstanceAs(b)
            assertThat(hub.snapshot.available).isFalse()
        }
        configure(pcm(), mapping = ImmutableIntArray.of(1, 0))
        assertThat(hub.snapshot.available).isFalse()
    }

    @Test
    fun `DJ mode turned on while no audio is handed over is taken at the next position query`() {
        hub.enabled = false
        configure()
        assertThat(sink.handleBuffer(buffer(), basePts, 1)).isTrue()
        assertThat(hub.snapshot.available).isFalse()

        // Paused, or waiting for the decoder: the renderer only asks where the sink is.
        hub.enabled = true
        inner.position = 1_000L
        sink.getCurrentPositionUs(false)

        assertThat(hub.snapshot.available).isTrue()
    }

    @Test
    fun `bit-perfect output let go of while no audio is handed over is taken at the next position query`() {
        gate = Gate(armed = true)
        configure()
        assertThat(sink.handleBuffer(buffer(), basePts, 1)).isTrue()
        assertThat(hub.snapshot.available).isFalse()

        gate!!.armed = false
        inner.position = 1_000L
        sink.getCurrentPositionUs(false)

        assertThat(hub.snapshot.available).isTrue()
    }

    @Test
    fun `a buffer the sink below has not taken is not given to a new host in the meantime`() {
        hub.enabled = false
        configure()
        inner.acceptFrames = 100
        val b = buffer()
        assertThat(sink.handleBuffer(b, basePts, 1)).isFalse()

        hub.enabled = true
        inner.position = 1_000L
        sink.getCurrentPositionUs(false)
        // The same buffer comes back until the sink below has it all: hosting starts after that, never half way through it.
        assertThat(hub.snapshot.available).isFalse()
        inner.acceptFrames = Int.MAX_VALUE
        assertThat(sink.handleBuffer(b, basePts, 1)).isTrue()
        assertThat(hub.snapshot.available).isFalse()
        sink.handleBuffer(buffer(), basePts + 10_000, 1)
        assertThat(hub.snapshot.available).isTrue()
    }

    @Test
    fun `what is decided is logged once, with the reason loops are not hosted`() {
        val lines = ArrayList<String>()
        DjLoopLog.enabled = { true }
        DjLoopLog.sink = { lines += it }
        configure(pcm(mime = MimeTypes.AUDIO_MPEG))
        sink.handleBuffer(buffer(), basePts, 1)
        sink.handleBuffer(buffer(), basePts, 1)
        sink.getCurrentPositionUs(false)

        assertThat(lines.filter { it.startsWith("loops not hosted") }).hasSize(1)
        assertThat(lines.single { it.startsWith("loops not hosted") }).contains("NOT_RAW_PCM")
        assertThat(lines.single { it.startsWith("loops not hosted") }).contains("mime=audio/mpeg")
        assertThat(lines.any { it.startsWith("stream configured") }).isTrue()

        hub.enabled = false
        sink.handleBuffer(buffer(), basePts, 1)
        assertThat(lines.last()).contains("DJ_MODE_OFF")
    }

    @Test
    fun `a stream that can be hosted is logged as hosted`() {
        val lines = ArrayList<String>()
        DjLoopLog.enabled = { true }
        DjLoopLog.sink = { lines += it }
        configure(pcm(encoding = C.ENCODING_PCM_FLOAT))

        assertThat(lines).contains("hosting loops on PcmFormat(48000 Hz, 2 ch, FLOAT_32)")
    }

    @After
    fun restoreLog() {
        DjLoopLog.enabled = DjLoopLog.defaultEnabled
        DjLoopLog.sink = DjLoopLog.defaultSink
    }

    @Test
    fun `an armed bit-perfect output leaves the stream untouched`() {
        gate = Gate(armed = true)
        configure()
        val b = buffer()
        sink.handleBuffer(b, basePts, 1)

        assertThat(inner.buffersSeen.single()).isSameInstanceAs(b)
        assertThat(hub.snapshot.available).isFalse()
    }

    @Test
    fun `a request while loops are unavailable is refused, not kept`() {
        hub.enabled = false
        configure()
        hub.post(LoopRequest.MarkIn)

        sink.handleBuffer(buffer(), basePts, 1)

        assertThat(refusals).containsExactly(LoopRefusal.NOT_AVAILABLE)
        assertThat(hub.hasRequests()).isFalse()
    }

    @Test
    fun `the listener is the sink below's too`() {
        configure()

        assertThat(inner.listener).isNotNull()
        inner.listener!!.onPositionDiscontinuity()
        assertThat(discontinuities).isEqualTo(1)
    }

    @Test
    fun `a loop made from the buttons repeats, the position wraps with it and each wrap is announced`() {
        configure()
        feed(19_200)
        hub.post(LoopRequest.MarkIn)
        inner.position = heardAt(10_000)
        sink.getCurrentPositionUs(false)
        hub.post(LoopRequest.MarkOut)
        // The request is resolved at the position the sink below reports when it is handled.
        inner.position = heardAt(18_000)
        // The decoder is ahead of what is heard: the loop joins at the frontier, which is where the output stands now.
        val join = inner.frames.size
        var guard = 0
        while (guard++ < 400 && !hub.snapshot.engaged) {
            val b = buffer()
            if (sink.handleBuffer(b, basePts + fed * 1_000_000L / rate, 1)) fed += 480
        }
        assertThat(hub.snapshot.engaged).isTrue()
        assertThat(inner.frames[join + 500]).isEqualTo(10_500)

        // Before the join the sink's own position, inside the loop the source's, the wrap announced first.
        val before = discontinuities
        inner.position = heardAt(join - 10L)
        assertThat(sink.getCurrentPositionUs(false)).isEqualTo(heardAt(join - 10L))
        assertThat(discontinuities).isEqualTo(before)
        inner.position = heardAt(join + 500L)
        val mapped = sink.getCurrentPositionUs(false)

        assertThat(discontinuities).isEqualTo(before + 1)
        assertThat(mapped).isLessThan(heardAt(join - 10L))
        assertThat(mapped).isWithin(2L).of(heardAt(10_500L))
    }

    @Test
    fun `the end of the stream is not passed down while a loop holds it`() {
        configure()
        feed(19_200)
        hub.post(LoopRequest.MarkIn)
        inner.position = heardAt(12_000)
        sink.getCurrentPositionUs(false)
        hub.post(LoopRequest.MarkOut)
        inner.position = heardAt(19_000)
        sink.getCurrentPositionUs(false)
        sink.handleBuffer(ByteBuffer.allocate(0), basePts, 1)

        sink.playToEndOfStream()

        assertThat(inner.calls).doesNotContain("playToEndOfStream")
        assertThat(sink.isEnded()).isFalse()
        assertThat(sink.hasPendingData()).isTrue()

        hub.post(LoopRequest.ExitLoop)
        inner.position = heardAt(19_500)
        sink.getCurrentPositionUs(false)
        var guard = 0
        while (!inner.calls.contains("playToEndOfStream") && guard++ < 50) sink.playToEndOfStream()

        assertThat(inner.calls).contains("playToEndOfStream")
        assertThat(sink.isEnded()).isTrue()
        assertThat(sink.hasPendingData()).isFalse()
    }

    @Test
    fun `flush and reset reach the sink below and the next buffer re-bases the stream`() {
        configure()
        feed(4_800)

        sink.flush()
        assertThat(inner.calls).contains("flush")
        fed = 240_000
        inner.pts.clear()
        feed(480)
        assertThat(inner.pts.first()).isEqualTo(basePts + 240_000L * 1_000_000 / rate)

        sink.reset()
        assertThat(inner.calls).contains("reset")
    }

    @Test
    fun `the stream offset reaches the sink below and the loop times`() {
        configure()
        sink.setOutputStreamOffsetUs(basePts)
        assertThat(inner.offsetUs).isEqualTo(basePts)
        feed(28_800)
        hub.post(LoopRequest.MarkIn)
        inner.position = heardAt(24_000)
        sink.getCurrentPositionUs(false)

        // The clock reads the renderer's time less the offset, less the encoder delay trimmed from the start.
        assertThat(hub.snapshot.markedInMs).isWithin(0.05).of((24_000 - delay) * 1_000.0 / rate)
    }

    @Test
    fun `a seek takes the trimming off the clock's time`() {
        configure()
        sink.setOutputStreamOffsetUs(basePts)
        feed(28_800)
        hub.post(LoopRequest.MarkIn)
        inner.position = heardAt(24_000)
        sink.getCurrentPositionUs(false)
        val atStart = hub.snapshot.markedInMs!!

        sink.flush()

        // The mark is on the decoder's frame and survives the flush; the clock now runs one encoder delay ahead.
        assertThat(hub.snapshot.markedInMs!! - atStart).isWithin(0.05).of(delay * 1_000.0 / rate)
    }

    @Test
    fun `DJ mode turned on in mid-stream starts hosting loops at the next buffer`() {
        hub.enabled = false
        configure()
        feed(2_400)
        assertThat(hub.snapshot.available).isFalse()

        hub.enabled = true
        feed(2_400)

        assertThat(hub.snapshot.available).isTrue()
        // No jump: the stream goes on in order.
        for (i in 0 until 4_800 step 211) assertThat(inner.frames[i]).isEqualTo(i)
    }

    @Test
    fun `DJ mode turned off drops the loop host only when nothing is held`() {
        configure()
        feed(2_400)
        hub.post(LoopRequest.MarkIn)
        inner.position = heardAt(1_000)
        sink.getCurrentPositionUs(false)

        hub.enabled = false
        feed(480)
        // A Loop In mark is a hold: the host stays until the mark is gone.
        assertThat(hub.snapshot.available).isTrue()

        hub.post(LoopRequest.ClearLoop)
        sink.getCurrentPositionUs(false)
        sink.handleBuffer(ByteBuffer.allocate(0), basePts, 1)
        feed(480)

        assertThat(hub.snapshot.available).isFalse()
    }

    @Test
    fun `the same format keeps the armed loop through a new configuration, another format starts afresh`() {
        configure()
        feed(9_600)
        hub.post(LoopRequest.MarkIn)
        inner.position = heardAt(4_000)
        sink.getCurrentPositionUs(false)
        hub.post(LoopRequest.MarkOut)
        inner.position = heardAt(9_000)
        sink.getCurrentPositionUs(false)
        feed(480)
        val loop = hub.snapshot.loopInMs
        assertThat(loop).isNotNull()

        configure()
        assertThat(hub.snapshot.loopInMs).isEqualTo(loop)

        configure(pcm(encoding = C.ENCODING_PCM_FLOAT))
        assertThat(hub.snapshot.loopInMs).isNull()
        assertThat(hub.snapshot.available).isTrue()
    }

    @Test
    fun `audio the sink below takes slowly still arrives complete and in order`() {
        configure()
        inner.acceptFrames = 100
        feed(1_920)

        var guard = 0
        while (inner.frames.size < 1_920 && guard++ < 100) sink.playToEndOfStream()

        assertThat(inner.frames).hasSize(1_920)
        for (i in 0 until 1_920) assertThat(inner.frames[i]).isEqualTo(i)
    }
}
