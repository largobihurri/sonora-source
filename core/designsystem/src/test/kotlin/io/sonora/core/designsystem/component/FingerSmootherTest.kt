package io.sonora.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sin
import org.junit.Test

class FingerSmootherTest {

    private val frame60 = 16_666_667L
    private val frame120 = 8_333_333L
    private val second = 1_000_000_000L

    // The real ratio: a ~1,800 px track stands for ~54,000 px of list, about 30 px of list per finger pixel.
    private val ratio = 30f
    private val track = 1_800f
    private val band = 6f // 2 dp at ~3 px/dp

    /** One frame of a simulated drag: when, where the thumb is (the finger, px) and where the list is (px of list). */
    private data class Frame(val nanos: Long, val thumbPx: Float, val listPx: Float)

    /**
     * A drag as the phone reports it: a touch every 1/120 s, each in whole pixels, the finger moving at [speed] px/s
     * from 900 px until [stopNanos] and resting there after. Each frame reads the latest touch, as the thumb does, and
     * the list is the smoother's output (the thumb applies it as it is); from [releaseNanos] on the finger is let go.
     */
    private fun drag(speed: Float, frameNanos: Long, totalNanos: Long, stopNanos: Long = Long.MAX_VALUE, releaseNanos: Long = Long.MAX_VALUE): List<Frame> {
        val touch = 8_333_333L
        val phase = 1_300_000L
        fun fingerAt(nanos: Long): Float {
            val lastTouch = ((nanos - phase) / touch) * touch + phase
            return round(900f + speed * minOf(lastTouch, stopNanos) / 1e9f)
        }
        val smoother = FingerSmoother(track, band)
        smoother.reset(900f / track)
        var released = Float.NaN // the finger where it was let go
        val frames = mutableListOf<Frame>()
        var nanos = 0L
        while (nanos < totalNanos) {
            nanos += frameNanos
            if (nanos >= releaseNanos && released.isNaN()) {
                released = fingerAt(nanos)
                smoother.settle(released / track)
            }
            val finger = if (released.isNaN()) fingerAt(nanos) else released
            frames += Frame(nanos, finger, smoother.update(finger / track, frameNanos) * track * ratio)
        }
        return frames
    }

    private fun steps(frames: List<Frame>) = frames.zipWithNext { a, b -> b.listPx - a.listPx }
    private fun percentile(values: List<Float>, p: Double) = values.sorted()[((values.size - 1) * p).toInt()]

    /** The list's position (px) after each frame for finger [samples] read one per frame. */
    private fun simulate(samples: List<Float>, frameNanos: Long, startPx: Float = samples.first()): List<Float> {
        val smoother = FingerSmoother(track, band)
        smoother.reset(startPx / track)
        return samples.map { finger -> smoother.update(finger / track, frameNanos) * track * ratio }
    }

    private fun travel(positions: List<Float>) = positions.zipWithNext { a, b -> abs(b - a) }.sum()

    private fun alternating(amplitude: Float, frames: Int) = List(frames) { 900f + if (it % 2 == 0) amplitude else -amplitude }
    private fun wobble(amplitude: Float, frames: Int, frameNanos: Long) =
        List(frames) { 900f + amplitude * sin(2.0 * Math.PI * 8.0 * it * frameNanos / 1e9).toFloat() }
    private fun noise(amplitude: Float, frames: Int): List<Float> {
        var seed = 12_345L
        return List(frames) {
            seed = (seed * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L)
            900f + amplitude * ((seed ushr 40).toFloat() / (1L shl 24) * 2f - 1f)
        }
    }

    // -- following a drag --

    @Test
    fun `the list keeps within 30 ms of the thumb at 33, 120 and 400 px per second`() {
        // Device QA of L5c: 764-781 ms at 33 px/s, 42-55 ms at 120 and 86-92 ms at 400 (median).
        for (speed in listOf(33f, 120f, 400f)) {
            for (frameNanos in listOf(frame60, frame120)) {
                val frames = drag(speed, frameNanos, totalNanos = 3 * second)
                // How long ago the thumb was where the list is now, over the whole drag (its start included).
                val lagMs = frames.map { (it.thumbPx - it.listPx / ratio) / speed * 1_000f }
                assertThat(percentile(lagMs, 0.5)).isAtMost(30f)
            }
        }
    }

    @Test
    fun `a slow drag in whole pixels moves the list forward every frame in even steps`() {
        // 33 px/s is about half a finger pixel per 60 Hz frame and 15 px of list: the touches arrive in whole pixels at
        // 120 Hz, so the raw finger moves 0, 1, 0, 1, 1 ... px per frame; the list must not copy that.
        for (frameNanos in listOf(frame60, frame120)) {
            val frames = drag(33f, frameNanos, totalNanos = 3 * second)
            val start = frames.indexOfFirst { it.listPx != 900f * ratio }
            val moving = steps(frames.drop(start + (200_000_000L / frameNanos).toInt()))
            assertThat(moving.min()).isGreaterThan(0f)
            assertThat(percentile(moving, 0.9) / percentile(moving, 0.1)).isAtMost(2f)
        }
    }

    @Test
    fun `when the finger stops the list settles within 150 ms, never stepping back or past the finger`() {
        // L5c: the last part decayed for ~1.5 s after every drag (83, 48, 32 ... 2 px per frame).
        val stop = 1_500_000_000L
        for (speed in listOf(33f, 120f, 400f)) {
            for (frameNanos in listOf(frame60, frame120)) {
                val frames = drag(speed, frameNanos, totalNanos = 2 * second, stopNanos = stop)
                val after = frames.zipWithNext().filter { (_, b) -> b.nanos > stop }
                for ((a, b) in after) assertThat(b.listPx).isAtLeast(a.listPx) // never back
                val lastMove = after.lastOrNull { (a, b) -> b.listPx - a.listPx > 0.5f }?.second?.nanos ?: stop
                assertThat(lastMove - stop).isAtMost(150_000_000L)
                val rest = frames.last()
                assertThat(rest.listPx).isWithin(0.5f).of(rest.thumbPx * ratio) // on the finger, not short of it
                assertThat(frames.maxOf { it.listPx }).isAtMost(rest.listPx + 0.5f) // and never past it
            }
        }
    }

    @Test
    fun `the list moves the same at 60 and at 120 frames per second`() {
        for (speed in listOf(33f, 120f, 400f)) {
            val at60 = drag(speed, frame60, totalNanos = 2 * second, stopNanos = 1_500_000_000L)
            val at120 = drag(speed, frame120, totalNanos = 2 * second, stopNanos = 1_500_000_000L)
            for ((i, frame) in at60.withIndex()) {
                // Every other 120 Hz frame falls on a 60 Hz one (within a few hundred nanoseconds).
                val other = at120.getOrNull(2 * i + 1) ?: continue
                // A frame at 120 Hz may read a touch 1/120 s newer: that much of the finger's travel, plus a pixel.
                assertThat(abs(frame.listPx - other.listPx)).isAtMost(ratio * (1f + speed / 120f))
            }
        }
    }

    @Test
    fun `letting go lands the list exactly on the finger, without a jump or a step back`() {
        val release = 1_500_000_000L
        for (speed in listOf(33f, 120f, 400f)) {
            for (frameNanos in listOf(frame60, frame120)) {
                val frames = drag(speed, frameNanos, totalNanos = 2 * second, releaseNanos = release)
                val held = steps(frames.filter { it.nanos < release })
                val after = frames.zipWithNext().filter { (_, b) -> b.nanos >= release }.map { (a, b) -> b.listPx - a.listPx }
                assertThat(after.min()).isAtLeast(0f)
                // No step after letting go is bigger than the drag's own (the list does not leap onto the finger).
                assertThat(after.max()).isAtMost(held.max() * 1.5f + 1f)
                val rest = frames.last()
                assertThat(rest.listPx).isWithin(0.01f).of(rest.thumbPx * ratio)
                val landed = frames.first { it.nanos >= release && it.listPx == rest.listPx }
                assertThat(landed.nanos - release).isAtMost(150_000_000L)
            }
        }
    }

    @Test
    fun `a fast drag keeps pace with the finger`() {
        for (frameNanos in listOf(frame60, frame120)) {
            val frames = drag(1_200f, frameNanos, totalNanos = second / 2)
            val last = frames.last()
            assertThat(last.thumbPx - last.listPx / ratio).isAtLeast(0f)
            assertThat(last.thumbPx - last.listPx / ratio).isAtMost(1_200f * 0.035f) // within 35 ms of the finger
        }
    }

    @Test
    fun `turning back moves nothing until the finger is a band back, then follows it smoothly`() {
        val smoother = FingerSmoother(track, band)
        smoother.reset(900f / track)
        var out = 900f
        for (i in 1..120) out = smoother.update((900f + i) / track, frame120) * track // 120 px forward in a second
        repeat(30) { out = smoother.update(1_020f / track, frame120) * track } // rest
        assertThat(out).isWithin(1e-2f).of(1_020f)
        for (i in 1..5) assertThat(smoother.update((1_020f - i) / track, frame120) * track).isWithin(1e-2f).of(1_020f)
        val back = List(60) { smoother.update((1_015f - it) / track, frame120) * track * ratio }
        assertThat(back.last()).isLessThan(1_000f * ratio)
        for ((a, b) in back.zipWithNext()) assertThat(b).isAtMost(a) // only backwards
        assertThat(back.zipWithNext { a, b -> a - b }.max()).isLessThan(2.5f * ratio) // no leap
    }

    // -- a resting finger --

    @Test
    fun `a smoother holds still while the finger is still, at any frame rate`() {
        for (frameNanos in listOf(frame60, frame120)) {
            val smoother = FingerSmoother(track, band)
            smoother.reset(0.4f)
            repeat(200) { assertThat(smoother.update(0.4f, frameNanos)).isWithin(1e-6f).of(0.4f) }
        }
    }

    @Test
    fun `a still finger's jitter of 2 and 4 px moves nothing`() {
        for (frameNanos in listOf(frame120, frame60)) {
            val frames = (2 * second / frameNanos).toInt()
            for (amplitude in listOf(2f, 4f)) {
                for (samples in listOf(alternating(amplitude, frames), noise(amplitude, frames), wobble(amplitude, frames, frameNanos))) {
                    // The thumb was grabbed where the finger rests: the list starts there.
                    val positions = simulate(listOf(900f) + samples.drop(1), frameNanos, startPx = 900f)
                    assertThat(travel(positions)).isEqualTo(0f)
                }
            }
        }
    }

    @Test
    fun `on the QA phone a resting finger's jitter of 4 px moves nothing wherever the grab lands in it`() {
        // QA round 4, Canciones: +-4 px from rest gave 46 updates in 2 s (steps to 54 px): 8 px from one extreme to the
        // other is more than the 2 dp (7 px) band. Until the finger has clearly moved, the band is 2.5 dp (8.75 px).
        val phoneBand = 7f
        val restBand = 8.75f
        for (frameNanos in listOf(frame60, frame120)) {
            val frames = (2 * second / frameNanos).toInt()
            for (grab in -4..4) {
                for (samples in listOf(alternating(4f, frames), noise(4f, frames), wobble(4f, frames, frameNanos))) {
                    val smoother = FingerSmoother(track, phoneBand, restBand)
                    smoother.reset((900f + grab) / track)
                    val positions = (listOf(900f + grab) + samples.drop(1).map { round(it) }).map { smoother.update(it / track, frameNanos) * track * ratio }
                    assertThat(travel(positions)).isEqualTo(0f)
                }
            }
        }
    }

    @Test
    fun `the wider rest band costs little at the start and none once moving`() {
        // Leaving the rest band takes 1.75 px more of finger: ~50 ms at 33 px/s, ~15 ms at 120. Once out, the drag
        // runs on the 2 dp band and its take-up: the same lag as without it.
        for (speed in listOf(33f, 120f)) {
            val narrow = FingerSmoother(track, 7f)
            val wide = FingerSmoother(track, 7f, 8.75f)
            narrow.reset(0.5f)
            wide.reset(0.5f)
            var startNarrow = -1
            var startWide = -1
            var last = 0f to 0f
            for (i in 1..360) {
                val finger = 0.5f + speed * i * frame120 / 1e9f / track
                last = narrow.update(finger, frame120) to wide.update(finger, frame120)
                if (startNarrow < 0 && last.first != 0.5f) startNarrow = i
                if (startWide < 0 && last.second != 0.5f) startWide = i
            }
            assertThat((startWide - startNarrow) * frame120 / 1e6).isAtMost(1.75 / speed * 1_000 + 9)
            // After 3 s both are as close to the finger as each other: within 10 ms of travel (the follower's gap
            // to the held position settles anywhere up to speed x 25 ms, depending on how the drag started).
            assertThat(abs(last.first - last.second) * track).isLessThan(speed * 0.01f)
        }
    }

    @Test
    fun `a grab that lands on the extreme of the jitter creeps by what exceeds the band, once`() {
        // The finger rests at 900 but the first sample was 4 px off: the held position is 4 px off too, and 8 px of
        // swing is 2 px more than the band, which the list follows once and then holds.
        val frames = 240
        val positions = simulate(alternating(4f, frames).map { 1_800f - it }, frame120, startPx = 896f)
        assertThat(travel(positions)).isAtMost(2f * ratio * 1.1f)
        assertThat(travel(positions.drop(18))).isLessThan(1f) // done within 150 ms
    }

    @Test
    fun `the finger at an end of the track puts the list at that end`() {
        val smoother = FingerSmoother(track, band)
        smoother.reset(0.5f)
        var out = 0.5f
        repeat(400) { out = smoother.update(1f, frame120) }
        assertThat(out).isEqualTo(1f)
        smoother.reset(0.5f)
        repeat(400) { out = smoother.update(0f, frame120) }
        assertThat(out).isEqualTo(0f)
    }

    @Test
    fun `no jump at the grab, the first frame holds the starting fraction`() {
        val smoother = FingerSmoother(track, band)
        smoother.reset(0.25f)
        assertThat(smoother.update(0.25f, frame120)).isWithin(1e-6f).of(0.25f)
        assertThat(smoother.update(0.25f + 2f / track, frame120)).isWithin(1e-6f).of(0.25f) // inside the band
    }
}
