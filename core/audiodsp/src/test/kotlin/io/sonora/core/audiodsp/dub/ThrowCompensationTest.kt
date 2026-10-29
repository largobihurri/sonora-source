package io.sonora.core.audiodsp.dub

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlin.math.roundToLong
import org.junit.Test

/**
 * The latency-compensated THROW end to end, with a synthetic presentation clock: the stage hands frames down
 * [Rig.queued] frames ahead of what is heard, the track runs at [Rig.speed], and a touch is stamped with the wall time
 * at which a given source frame was heard. Pad events are drained at the start of each buffer, as the sink will.
 */
class ThrowCompensationTest {

    private class Rig(
        val rate: Int = 48_000,
        val speed: Double = 1.0,
        val queued: Int = 6_000, // 125 ms at 48 kHz
        val bufferFrames: Int = 1_024,
        bpm: Double = 120.0,
        division: EchoDivision = EchoDivision.QUARTER,
        feedback: Float = 0f,
    ) {
        val history = HistoryRing(2, HistoryRing.capacityFor(rate))
        val pending = PendingSendRing(history, rate)
        val clock = PresentationClock(rate)
        val echo = TapeEcho().apply {
            prepare(rate, 2)
            setTempo(bpm)
            setDivision(division)
            setFeedback(feedback)
            setReturnLevel(1f)
            reset()
        }
        private val events = ArrayDeque<Pair<Long, Boolean>>()
        val modes = mutableListOf<ThrowMode>()
        val frontiersAtEvent = mutableListOf<Long>()

        /** Wall time (ns) at which source frame [frame] is heard. */
        fun heardAt(frame: Double): Long = (1e9 + frame / (rate * speed) * 1e9).roundToLong()

        fun press(heardFrame: Long) = events.addLast(heardAt(heardFrame.toDouble()) to true)
        fun release(heardFrame: Long) = events.addLast(heardAt(heardFrame.toDouble()) to false)

        /** Renders [frames] frames of [input] (frame, channel) and returns the interleaved echo return. */
        fun run(frames: Int, input: (Int, Int) -> Float): FloatArray {
            val out = FloatArray(frames * 2)
            var handed = 0
            while (handed < frames) {
                val presented = maxOf(0, handed - queued).toDouble()
                val now = heardAt(presented)
                clock.record(now, handed.toLong(), presented, speed)
                while (events.isNotEmpty() && events.first().first <= now) {
                    val (t, down) = events.removeFirst()
                    frontiersAtEvent += history.newestFrame + 1
                    val heard = clock.heardFrameAt(t)
                    if (down) modes += pending.press(heard, echo.currentDelayFrames) else pending.release(heard)
                }
                val n = minOf(bufferFrames, frames - handed)
                for (i in 0 until n) {
                    val f = handed + i
                    history.write(input(f, 0), input(f, 1))
                    pending.advance()
                    echo.processFrame(0f, 0f, pending)
                    out[2 * f] = echo.outLeft
                    out[2 * f + 1] = echo.outRight
                }
                handed += n
            }
            return out
        }
    }

    private fun noise(seed: Long, frames: Int, amplitude: Float = 0.5f): FloatArray {
        val n = DubSignals.Noise(seed, amplitude)
        return FloatArray(frames) { n.next() }
    }

    private fun firstRepeatLandsOneDelayAfterThePress(speed: Double) {
        val frames = 120_000
        val left = noise(1, frames)
        val right = noise(2, frames)
        val rig = Rig(speed = speed)
        val press = 30_017L
        val release = 39_617L // 200 ms later
        rig.press(press)
        rig.release(release)
        val out = rig.run(frames) { f, c -> if (c == 0) left[f] else right[f] }
        val delay = 24_000
        val ramp = 240

        assertThat(rig.modes).containsExactly(ThrowMode.COMPENSATED)
        // The events reached the stage between 6000 and 7024 frames after they were heard.
        assertThat(rig.frontiersAtEvent[0] - press).isIn(com.google.common.collect.Range.closed(6_000L, 7_024L))
        for (c in 0..1) {
            val input = if (c == 0) left else right
            for (n in 0 until press.toInt() + delay) {
                assertWithMessage("speed $speed channel $c frame $n").that(out[2 * n + c]).isWithin(0f).of(0f)
            }
            // The first repeat of the heard press frame lands exactly one delay later...
            assertWithMessage("speed $speed channel $c").that(out[2 * (press.toInt() + delay) + c]).isNotEqualTo(0f)
            // ...and once the 5 ms fade-in is over it is the input, sample for sample, until the release.
            for (n in press.toInt() + ramp until release.toInt()) {
                assertWithMessage("speed $speed channel $c frame $n").that(out[2 * (n + delay) + c]).isEqualTo(input[n])
            }
            // Nothing heard after the release (plus its 5 ms fade) is sent, though it was already queued.
            for (n in release.toInt() + ramp + delay until frames) {
                assertWithMessage("speed $speed channel $c frame $n").that(out[2 * n + c]).isWithin(0f).of(0f)
            }
        }
    }

    @Test
    fun `the first repeat lands exactly one delay after the heard press at speed 1_0`() =
        firstRepeatLandsOneDelayAfterThePress(1.0)

    @Test
    fun `the first repeat lands exactly one delay after the heard press at speed 1_25`() =
        firstRepeatLandsOneDelayAfterThePress(1.25)

    @Test
    fun `a PadUp leaves the repeats of earlier throws bit-identical`() {
        val frames = 240_000
        val music = noise(3, frames)
        // The track goes quiet after 1.25 s: throw 1 is still repeating when throw 2 is pressed and released.
        fun input(f: Int, c: Int) = if (f < 60_000) music[f] * (if (c == 0) 1f else -0.5f) else 0f
        fun run(secondThrow: Boolean): FloatArray {
            val rig = Rig(feedback = 0.7f)
            rig.press(10_000)
            rig.release(40_000)
            if (secondThrow) {
                rig.press(100_000)
                rig.release(112_000)
            }
            return rig.run(frames, ::input)
        }
        val withSecond = run(secondThrow = true)
        val without = run(secondThrow = false)
        assertThat(DubSignals.peak(without, 2 * 100_000, 2 * 140_000)).isGreaterThan(0.05f) // throw 1 still rings
        assertThat(withSecond).isEqualTo(without)
    }

    @Test
    fun `audio after the release point never reaches the echo and the release changes nothing before it`() {
        val frames = 200_000
        val a = noise(4, frames)
        val b = noise(5, frames, amplitude = 0.9f)
        val ramp = 240
        fun run(release: Boolean, swapAfterRelease: Boolean): FloatArray {
            val rig = Rig(feedback = 0.7f)
            rig.press(30_000)
            if (release) rig.release(40_000)
            return rig.run(frames) { f, _ -> if (swapAfterRelease && f >= 40_000 + ramp) b[f] else a[f] }
        }
        val released = run(release = true, swapAfterRelease = false)
        // Different audio after the release point (already queued when the release arrived) changes nothing.
        assertThat(run(release = true, swapAfterRelease = true)).isEqualTo(released)
        // Releasing changes nothing that was heard before the release point.
        val held = run(release = false, swapAfterRelease = false)
        val untilRelease = 2 * (40_000 + 24_000)
        assertThat(released.copyOf(untilRelease)).isEqualTo(held.copyOf(untilRelease))
        assertThat(released.copyOfRange(untilRelease, untilRelease + 4_800)).isNotEqualTo(held.copyOfRange(untilRelease, untilRelease + 4_800))
    }

    @Test
    fun `a delay shorter than the queued depth sends from the frontier at once`() {
        val frames = 60_000
        val left = noise(6, frames)
        // 1/16 at 180 BPM is 4000 frames, shorter than the 6000 queued.
        val rig = Rig(bpm = 180.0, division = EchoDivision.SIXTEENTH)
        assertThat(rig.echo.delayFrames).isEqualTo(4_000.0)
        rig.press(20_000)
        rig.release(30_000)
        val out = rig.run(frames) { f, _ -> left[f] }
        assertThat(rig.modes).containsExactly(ThrowMode.FRONTIER)
        val frontier = rig.frontiersAtEvent[0].toInt()
        val releaseFrontier = rig.frontiersAtEvent[1].toInt()
        for (n in 0 until frontier + 4_000) assertWithMessage("frame $n").that(out[2 * n]).isWithin(0f).of(0f)
        assertThat(out[2 * (frontier + 4_000)]).isNotEqualTo(0f)
        for (n in frontier + 240 until releaseFrontier) {
            assertWithMessage("frame $n").that(out[2 * (n + 4_000)]).isEqualTo(left[n])
        }
        for (n in releaseFrontier + 240 + 4_000 until frames) {
            assertWithMessage("frame $n").that(out[2 * n]).isWithin(0f).of(0f)
        }
    }

    @Test
    fun `a throw held longer than the history keeps its audio through the commit into the echo`() {
        val frames = 200_000
        val left = noise(7, frames)
        // A 1.5 s echo (3/8 at 120 BPM is 0.75 s; 1/2 at 80 BPM is 1.5 s) is longer than the 400 ms pending window.
        val rig = Rig(bpm = 80.0, division = EchoDivision.HALF)
        val delay = 72_000
        assertThat(rig.echo.delayFrames).isEqualTo(delay.toDouble())
        rig.press(20_000)
        rig.release(100_000)
        val out = rig.run(frames) { f, _ -> left[f] }
        assertThat(rig.modes).containsExactly(ThrowMode.COMPENSATED)
        for (n in 20_240 until 100_000) assertWithMessage("frame $n").that(out[2 * (n + delay)]).isEqualTo(left[n])
    }
}
