package io.sonora.core.playback.loop

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djloop.LoopBlend
import io.sonora.core.djloop.PcmEncoding
import io.sonora.core.djloop.PcmFormat
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.TempoSegment
import io.sonora.core.djmodel.loop.LoopQuantizer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test

/**
 * The loop session as the audio thread runs it: audio in, the inner sink's frames and positions out, the user's
 * requests resolved at the frame being heard. The source is a counter (frame k of the stream holds k in channel 0), so
 * every output frame says which source frame it is, and the heard position is simulated from the frames played.
 */
class LoopSessionTest {

    private val rate = 48_000
    private val delay = 1_105L

    /** The first buffer's presentation time: renderer time starts at 1e12 us. */
    private val basePts = 1_000_000_000_000L
    private val anchor = basePts * rate / 1_000_000L

    private val hub = LoopHub().apply { enabled = true }
    private val trim = TrimModel().apply { configured(delay) }
    private val format = PcmFormat(rate, 2, PcmEncoding.PCM_32)

    /** What the inner sink got: channel 0 of every frame, in order. */
    private val played = ArrayList<Int>()
    private var discontinuities = 0
    private val snapshots = ArrayList<LoopSnapshot>()
    private val refusals = ArrayList<LoopRefusal>()
    private val seeks = ArrayList<Double>()
    private val outputPts = ArrayList<Long>()

    private val session = LoopSession(
        format, hub, trim, streamOffsetUs = basePts,
        output = { buffer, pts ->
            outputPts += pts
            val ints = buffer.order(ByteOrder.LITTLE_ENDIAN)
            while (ints.remaining() >= 8) {
                played += ints.getInt()
                ints.getInt()
            }
            true
        },
        onDiscontinuity = { discontinuities++ },
    )

    init {
        hub.listener = object : LoopHubListener {
            override fun onSnapshot(snapshot: LoopSnapshot) {
                snapshots += snapshot
            }

            override fun onNeedsSeek(inMs: Double) {
                seeks += inMs
            }

            override fun onTooLong(inMs: Double, outMs: Double) = Unit

            override fun onRefused(refusal: LoopRefusal) {
                refusals += refusal
            }
        }
    }

    private var fed = 0
    private var current: ByteBuffer? = null
    private var currentPts = 0L

    private fun nextBuffer(): ByteBuffer {
        val n = 480
        val buffer = ByteBuffer.allocate(n * 8).order(ByteOrder.LITTLE_ENDIAN)
        for (k in 0 until n) {
            buffer.putInt(fed + k)
            buffer.putInt(-(fed + k))
        }
        buffer.flip()
        currentPts = basePts + fed * 1_000_000L / rate
        fed += n
        return buffer
    }

    /** Feeds exactly [frames] frames of the counter source (a multiple of 480) while nothing holds the audio back. */
    private fun feed(frames: Int) {
        require(frames % 480 == 0)
        val end = fed + frames
        while (fed < end) {
            val buffer = current ?: nextBuffer()
            if (session.write(buffer, currentPts)) current = null else current = buffer
            check(current == null) { "the session held a buffer: use playTo" }
        }
    }

    /** Runs the decoder and the output like the renderer does until [target] frames reached the inner sink. */
    private fun playTo(target: Int) {
        var guard = 0
        while (played.size < target && guard++ < 10_000) {
            val buffer = current ?: nextBuffer().also { current = it }
            if (session.write(buffer, currentPts)) current = null
        }
        check(played.size >= target) { "stuck at ${played.size} of $target" }
    }

    /** The inner sink's position when output frame [o] is being heard (trimming taken off). */
    private fun heardAt(o: Long): Long = basePts + (o - trim.trimmedFrames) * 1_000_000L / rate

    private fun post(request: LoopRequest) = hub.post(request)

    @Test
    fun `without a loop the audio and the positions pass through unchanged`() {
        feed(19_200)

        assertThat(played).hasSize(19_200)
        for (i in 0 until 19_200 step 997) assertThat(played[i]).isEqualTo(i)
        for (o in 2_000L..19_000L step 1_000L) assertThat(session.mapPosition(heardAt(o))).isEqualTo(heardAt(o))
        assertThat(discontinuities).isEqualTo(0)
        assertThat(hub.audible.looping).isFalse()
    }

    @Test
    fun `the inner sink gets the stream's own presentation times`() {
        feed(2_400)

        assertThat(outputPts.first()).isEqualTo(basePts)
        for (i in 1 until outputPts.size) assertThat(outputPts[i]).isGreaterThan(outputPts[i - 1])
    }

    /**
     * Loop In heard at output frame [inFrame] and Loop Out at [outFrame] (counter values), with the decoder
     * [lead] frames ahead of what is heard, as it is with an AudioTrack buffer: the loop joins at the frontier.
     */
    private fun setLoop(inFrame: Int, outFrame: Int, lead: Int = 1_920) {
        hub.context = LoopContext(null, quantize = false)
        feed(outFrame + lead - (outFrame + lead) % 480)
        post(LoopRequest.MarkIn)
        session.drain(heardAt(inFrame.toLong()))
        post(LoopRequest.MarkOut)
        session.drain(heardAt(outFrame.toLong()))
    }

    @Test
    fun `Loop In and Loop Out at the frames being heard repeat exactly that span`() {
        setLoop(inFrame = 10_000, outFrame = 18_000)
        val join = fed
        playTo(join + 40_000)

        val blend = LoopBlend.blendFrames(rate, 8_000).toInt()
        // Live up to the frontier, then the loop [10000, 18000) with an exact period of 8000 frames.
        for (i in 0 until join step 331) assertThat(played[i]).isEqualTo(i)
        for (k in blend until 8_000) assertThat(played[join + k]).isEqualTo(10_000 + k)
        for (k in blend until 8_000) assertThat(played[join + 8_000 + k]).isEqualTo(10_000 + k)
        assertThat(hub.snapshot.engaged).isTrue()
    }

    @Test
    fun `a request before any audio arrives is refused`() {
        post(LoopRequest.MarkIn)
        session.drain(heardAt(0))

        assertThat(refusals).containsExactly(LoopRefusal.NOT_AVAILABLE)
    }

    @Test
    fun `Loop Out without Loop In is refused with NO_IN`() {
        hub.context = LoopContext(null, quantize = false)
        feed(4_800)

        post(LoopRequest.MarkOut)
        session.drain(heardAt(3_000))

        assertThat(refusals).containsExactly(LoopRefusal.NO_IN)
    }

    @Test
    fun `the snapshot says where Loop In was marked, in the clock's time`() {
        hub.context = LoopContext(null, quantize = false)
        feed(28_800)

        post(LoopRequest.MarkIn)
        session.drain(heardAt(24_000))

        // Heard at output frame 24000 with the encoder delay trimmed from the start: the clock reads (24000 - 1105) frames.
        assertThat(hub.snapshot.markedInMs).isWithin(0.01).of((24_000 - delay) * 1_000.0 / rate)
        assertThat(hub.snapshot.available).isTrue()
    }

    // --- Positions across a loop ----------------------------------------------------------------------------------

    /** The source frame (counter value) that output frame [o] plays once the loop [inFrame]..[outFrame] joined at [join]. */
    private fun sourceAt(o: Int, join: Int, inFrame: Int, outFrame: Int) =
        if (o < join) o else inFrame + (o - join) % (outFrame - inFrame)

    @Test
    fun `positions inside the loop are the source's, and each wrap is announced before the position goes back`() {
        setLoop(10_000, 18_000)
        val join = fed
        playTo(join + 40_000)

        var previous = Long.MIN_VALUE
        var o = 0
        while (o < join + 40_000) {
            val before = discontinuities
            val mapped = session.mapPosition(heardAt(o.toLong()))
            assertThat(mapped).isWithin(2L).of(heardAt(sourceAt(o, join, 10_000, 18_000).toLong()))
            // A position that goes back was announced by that very call.
            if (mapped < previous) assertThat(discontinuities).isGreaterThan(before)
            previous = mapped
            o += 250
        }

        // The jump into the loop and one wrap per iteration: 5 iterations fit in 40000 frames.
        assertThat(discontinuities).isEqualTo(1 + (o - 250 - join) / 8_000)
    }

    @Test
    fun `the clock is told the loop is heard only while the audio inside it plays`() {
        setLoop(10_000, 18_000)
        val join = fed
        playTo(join + 20_000)

        session.mapPosition(heardAt(join - 1L))
        assertThat(hub.audible.looping).isFalse()

        session.mapPosition(heardAt(join + 500L))
        assertThat(hub.audible.looping).isTrue()
        // The clock's time of the loop's ends: the source frame as time, the encoder delay trimmed.
        assertThat(hub.audible.inMs).isWithin(0.05).of((10_000 - delay) * 1_000.0 / rate)
        assertThat(hub.audible.outMs).isWithin(0.05).of((18_000 - delay) * 1_000.0 / rate)
    }

    @Test
    fun `Exit carries on linearly from where the loop was, positions included, and no more wraps are announced`() {
        setLoop(10_000, 18_000)
        val join = fed
        playTo(join + 20_000)
        // The decoder side is ahead of what is heard: the loop is left where the output stands when Exit is applied.
        val exitOutput = played.size
        post(LoopRequest.ExitLoop)
        session.drain(heardAt((exitOutput - 3_000).toLong()))
        playTo(exitOutput + 60_000)

        // Sweep the heard positions: after the exit nothing wraps and the position is the source frame being heard.
        var o = 0
        while (o < exitOutput + 8_000 + 500) {
            session.mapPosition(heardAt(o.toLong()))
            o += 250
        }
        val wrapsAtExit = discontinuities
        val late = played.size - 2_000
        while (o < late) {
            session.mapPosition(heardAt(o.toLong()))
            o += 250
        }
        assertThat(discontinuities).isEqualTo(wrapsAtExit)
        assertThat(session.mapPosition(heardAt(late.toLong()))).isWithin(2L).of(heardAt(played[late].toLong()))
        // The counter rises by one per frame well after the exit.
        for (i in late - 1_000 until late) assertThat(played[i + 1]).isEqualTo(played[i] + 1)
        assertThat(hub.audible.looping).isFalse()
        assertThat(hub.snapshot.engaged).isFalse()
        assertThat(hub.snapshot.loopInMs).isNotNull()
    }

    @Test
    fun `a hold at the end of the track keeps the session busy and ended only after Exit`() {
        hub.context = LoopContext(null, quantize = false)
        feed(19_200)
        post(LoopRequest.MarkIn)
        session.drain(heardAt(12_000))
        post(LoopRequest.MarkOut)
        session.drain(heardAt(19_000))

        // The decoder has no more audio, but the loop holds the stream.
        assertThat(session.finish()).isFalse()
        assertThat(session.isEnded()).isFalse()
        assertThat(session.hasPendingData()).isTrue()

        post(LoopRequest.ExitLoop)
        session.drain(heardAt(19_500))
        var guard = 0
        while (!session.finish() && guard++ < 50) session.pump()
        assertThat(session.isEnded()).isTrue()
        assertThat(session.hasPendingData()).isFalse()
    }

    // --- Restart, flush, offsets -------------------------------------------------------------------------------------

    @Test
    fun `a flush forgets the audio and re-bases the stream on the next buffer`() {
        feed(9_600)

        session.flush()
        played.clear()
        outputPts.clear()
        fed = 480_000
        feed(960)

        assertThat(outputPts.first()).isEqualTo(basePts + 480_000L * 1_000_000 / rate)
        assertThat(played.first()).isEqualTo(480_000)
    }

    @Test
    fun `a seek keeps the armed loop and it engages when the new audio reaches Out`() {
        val grid = DjBeatGrid(listOf(TempoSegment(0.0, 120.0, 1)))
        hub.context = LoopContext(LoopQuantizer(grid, 600_000.0), quantize = true)
        feed(14_400)
        // A four beat loop (2 s) from the beat nearest to 300 ms: In at 500 ms, Out at 2500 ms; the audio is far from it.
        post(LoopRequest.BeatLoop(4.0))
        session.drain(heardAt(300L * rate / 1_000 + delay))
        feed(480)
        assertThat(hub.snapshot.armed).isTrue()

        session.flush()
        played.clear()
        fed = 0
        current = null
        playTo(130_000)

        val inFrame = 500 * rate / 1_000 + delay.toInt()
        val outFrame = inFrame + 2_000 * rate / 1_000
        val firstWrap = played.indexOfFirst { it == outFrame - 1 } + 1
        assertThat(hub.snapshot.engaged).isTrue()
        assertThat(played.subList(firstWrap + 300, firstWrap + 400)).isEqualTo((inFrame + 300 until inFrame + 400).toList())
    }

    @Test
    fun `the clock's time follows the trimming model`() {
        hub.context = LoopContext(null, quantize = false)
        feed(28_800)
        post(LoopRequest.MarkIn)
        session.drain(heardAt(24_000))
        val atStart = hub.snapshot.markedInMs!!

        trim.audioHandled()
        trim.flushed()
        session.timeChanged()

        // Untrimmed, the same decoder frame is heard one encoder delay later on the clock.
        assertThat(hub.snapshot.markedInMs!! - atStart).isWithin(0.01).of(delay * 1_000.0 / rate)
    }

    @Test
    fun `another stream offset moves the clock's time of the same decoder frame`() {
        hub.context = LoopContext(null, quantize = false)
        feed(28_800)
        post(LoopRequest.MarkIn)
        session.drain(heardAt(24_000))
        val before = hub.snapshot.markedInMs!!

        session.setStreamOffsetUs(basePts + 1_000_000)

        assertThat(hub.snapshot.markedInMs!! - before).isWithin(1e-6).of(-1_000.0)
    }

    @Test
    fun `release says loops are no longer available and the clock hears none`() {
        feed(4_800)
        session.release()

        assertThat(hub.snapshot.available).isFalse()
        assertThat(hub.audible.looping).isFalse()
    }

    @Test
    fun `quantized loops land on the beats of the grid whatever the encoder delay`() {
        val grid = DjBeatGrid(listOf(TempoSegment(0.0, 120.0, 1)))
        hub.context = LoopContext(LoopQuantizer(grid, 600_000.0), quantize = true)
        feed(192_000)

        // Heard at 1.230 s of the clock (the stream plays from its start, so clock = grid time): nearest beat 1.000 s.
        post(LoopRequest.MarkIn)
        session.drain(heardAt(1_230L * rate / 1_000 + delay))
        // At 3.900 s: nearest beat 4.000 s.
        post(LoopRequest.MarkOut)
        session.drain(heardAt(3_900L * rate / 1_000 + delay))
        feed(480)

        assertThat(hub.snapshot.loopInMs).isWithin(0.01).of(1_000.0)
        assertThat(hub.snapshot.loopOutMs).isWithin(0.01).of(4_000.0)
        assertThat(hub.snapshot.armed || hub.snapshot.engaged).isTrue()
    }

    @Test
    fun `the snapshot carries the loop in grid time, which is the clock's plus the delay the clock has trimmed`() {
        setLoop(inFrame = 24_000, outFrame = 48_000)
        session.pump()

        // Heard at output frames 24000 and 48000: grid time counts from the frame after the encoder delay.
        assertThat(hub.snapshot.loopInGridMs).isWithin(0.01).of((24_000 - delay) * 1_000.0 / rate)
        assertThat(hub.snapshot.loopOutGridMs).isWithin(0.01).of((48_000 - delay) * 1_000.0 / rate)
    }

    @Test
    fun `the snapshot carries a Loop In mark in grid time too`() {
        hub.context = LoopContext(null, quantize = false)
        feed(28_800)

        post(LoopRequest.MarkIn)
        session.drain(heardAt(24_000))

        assertThat(hub.snapshot.markedInGridMs).isWithin(0.01).of((24_000 - delay) * 1_000.0 / rate)
        assertThat(hub.snapshot.loopInGridMs).isNull()
    }

    @Test
    fun `Engage far from the audio heard asks for a seek to In in the clock's time and arms the loop`() {
        hub.context = LoopContext(null, quantize = false)
        feed(14_400)

        post(LoopRequest.Engage(20_000.0, 22_000.0))
        session.drain(heardAt(9_600))
        session.pump()

        assertThat(hub.snapshot.armed).isTrue()
        assertThat(seeks).hasSize(1)
        assertThat(seeks.single()).isWithin(0.01).of(20_000.0)
        assertThat(refusals).isEmpty()
    }

    @Test
    fun `Engage inside the audio heard arms the loop without a seek`() {
        hub.context = LoopContext(null, quantize = false)
        feed(48_000)
        val inMs = 400.0
        val outMs = 700.0

        post(LoopRequest.Engage(inMs, outMs))
        session.drain(heardAt(24_000 + delay))
        session.pump()

        assertThat(seeks).isEmpty()
        assertThat(hub.snapshot.loopInGridMs).isWithin(0.01).of(inMs)
        assertThat(hub.snapshot.loopOutGridMs).isWithin(0.01).of(outMs)
    }
}
