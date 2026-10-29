package io.sonora.core.dubmodel

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DubSetupTest {

    @Test
    fun `the defaults are a quarter note, 55 percent feedback and 80 percent return`() {
        val setup = DubSetup()

        assertThat(setup.division).isEqualTo(DubDivision.QUARTER)
        assertThat(setup.feedback).isEqualTo(0.55f)
        assertThat(setup.returnLevel).isEqualTo(0.80f)
        assertThat(setup.lock).isFalse()
        assertThat(setup.padBehaviour).isEqualTo(PadBehaviour.HOLD_DOUBLE_TAP_LATCH)
    }

    @Test
    fun `DROP kills ways 1 and 2 by default`() {
        assertThat(DubSetup().dropWays).containsExactly(PreampWay.SUB, PreampWay.BASS)
    }

    @Test
    fun `the preamp defaults to the five ways of the design at unity gain`() {
        val ways = DubSetup().preamp.ways

        assertThat(ways.map { it.highPassHz }).containsExactly(20f, 80f, 250f, 1_200f, 5_000f).inOrder()
        assertThat(ways.map { it.lowPassHz }).containsExactly(80f, 250f, 1_200f, 5_000f, 20_000f).inOrder()
        assertThat(ways.map { it.gain }).containsExactly(1f, 1f, 1f, 1f, 1f).inOrder()
        assertThat(ways.map { it.levelDb }).containsExactly(0f, 0f, 0f, 0f, 0f).inOrder()
    }

    @Test
    fun `adjacent default corners meet so the ways cover the range without gaps`() {
        val ways = PreampSetup().ways

        for (i in 0 until ways.size - 1) assertThat(ways[i].lowPassHz).isEqualTo(ways[i + 1].highPassHz)
        assertThat(PreampSetup()[PreampWay.SUB].highPassOff).isTrue()
        assertThat(PreampSetup()[PreampWay.TOP].lowPassOff).isTrue()
        assertThat(PreampSetup()[PreampWay.BASS].highPassOff).isFalse()
        assertThat(PreampSetup()[PreampWay.BASS].lowPassOff).isFalse()
    }

    @Test
    fun `the divisions are their note values in beats`() {
        assertThat(DubDivision.entries.map { it.beats }).containsExactly(0.25, 0.5, 0.75, 1.0, 1.5, 2.0).inOrder()
        assertThat(DubDivision.DOTTED_EIGHTH.wholeNotes).isEqualTo(3.0 / 16)
    }

    @Test
    fun `feedback is clamped to 0 to 95 percent and return to 0 to 100 percent`() {
        assertThat(DubSetup(feedback = 1.2f, returnLevel = 1.5f).clamped())
            .isEqualTo(DubSetup(feedback = 0.95f, returnLevel = 1f))
        assertThat(DubSetup(feedback = -0.1f, returnLevel = -2f).clamped())
            .isEqualTo(DubSetup(feedback = 0f, returnLevel = 0f))
    }

    @Test
    fun `values that are not numbers take their defaults`() {
        val clamped = DubSetup(feedback = Float.NaN, returnLevel = Float.POSITIVE_INFINITY).clamped()

        assertThat(clamped.feedback).isEqualTo(0.55f)
        // Infinity is a number past the top: it clamps, it does not reset.
        assertThat(clamped.returnLevel).isEqualTo(1f)
        assertThat(DubSetup(returnLevel = Float.NaN).clamped().returnLevel).isEqualTo(0.80f)
    }

    @Test
    fun `a setup inside its ranges is left as it is`() {
        val setup = DubSetup(
            division = DubDivision.DOTTED_EIGHTH,
            feedback = 0.7f,
            returnLevel = 0.3f,
            lock = true,
            padBehaviour = PadBehaviour.TOGGLE,
            dropWays = setOf(PreampWay.SUB),
            preamp = PreampSetup().with(PreampWay.LOW_MID, WaySetup(300f, 900f, 0.5f)),
        )

        assertThat(setup.clamped()).isEqualTo(setup)
    }

    @Test
    fun `corners are kept inside 20 Hz to 20 kHz`() {
        val way = WaySetup(highPassHz = 5f, lowPassHz = 40_000f).clamped(WaySetup.defaultFor(PreampWay.BASS))

        assertThat(way.highPassHz).isEqualTo(20f)
        assertThat(way.lowPassHz).isEqualTo(20_000f)
        assertThat(way.highPassOff).isTrue()
        assertThat(way.lowPassOff).isTrue()
    }

    @Test
    fun `crossed corners are swapped so the high-pass stays below the low-pass`() {
        val way = WaySetup(highPassHz = 2_000f, lowPassHz = 500f).clamped(WaySetup.defaultFor(PreampWay.LOW_MID))

        assertThat(way.highPassHz).isEqualTo(500f)
        assertThat(way.lowPassHz).isEqualTo(2_000f)
    }

    @Test
    fun `a corner or a gain that is not a number takes the fallback's`() {
        val fallback = WaySetup.defaultFor(PreampWay.HIGH_MID)

        val way = WaySetup(highPassHz = Float.NaN, lowPassHz = Float.NaN, gain = Float.NaN).clamped(fallback)

        assertThat(way).isEqualTo(fallback)
    }

    @Test
    fun `moving a corner past the other stops at it`() {
        val bass = WaySetup.defaultFor(PreampWay.BASS)

        assertThat(bass.withHighPass(1_000f).highPassHz).isEqualTo(250f)
        assertThat(bass.withLowPass(30f).lowPassHz).isEqualTo(80f)
        assertThat(bass.withHighPass(1f).highPassHz).isEqualTo(20f)
        assertThat(bass.withLowPass(30_000f).lowPassHz).isEqualTo(20_000f)
        assertThat(bass.withHighPass(100f)).isEqualTo(WaySetup(100f, 250f))
    }

    @Test
    fun `the fader runs from minus infinity to plus 6 dB with the detent at unity`() {
        val way = WaySetup.defaultFor(PreampWay.TOP)

        assertThat(way.withLevelDb(0f).gain).isEqualTo(1f)
        assertThat(way.withLevelDb(Float.NEGATIVE_INFINITY).gain).isEqualTo(0f)
        assertThat(way.withLevelDb(Float.NEGATIVE_INFINITY).levelDb).isEqualTo(Float.NEGATIVE_INFINITY)
        assertThat(way.withLevelDb(12f).levelDb).isWithin(1e-4f).of(6f)
        assertThat(way.withLevelDb(-6f).gain).isWithin(1e-4f).of(0.501187f)
        assertThat(WaySetup.MAX_GAIN).isWithin(1e-5f).of(1.9952623f)
        assertThat(way.withLevelDb(Float.NaN).gain).isEqualTo(1f)
    }

    @Test
    fun `a gain is clamped to 0 to plus 6 dB`() {
        val fallback = WaySetup.defaultFor(PreampWay.SUB)

        assertThat(WaySetup(20f, 80f, gain = 5f).clamped(fallback).gain).isEqualTo(WaySetup.MAX_GAIN)
        assertThat(WaySetup(20f, 80f, gain = -1f).clamped(fallback).gain).isEqualTo(0f)
    }

    @Test
    fun `a preamp with a missing or an extra way is brought back to five`() {
        val short = PreampSetup(listOf(WaySetup(30f, 70f))).clamped()
        val long = PreampSetup(PreampSetup().ways + WaySetup(1f, 2f)).clamped()

        assertThat(short.ways).hasSize(5)
        assertThat(short[PreampWay.SUB]).isEqualTo(WaySetup(30f, 70f))
        assertThat(short.ways.drop(1)).isEqualTo(PreampSetup().ways.drop(1))
        assertThat(long).isEqualTo(PreampSetup())
    }

    @Test
    fun `the setup clamps every way of its preamp`() {
        val wild = DubSetup(preamp = PreampSetup().with(PreampWay.TOP, WaySetup(30_000f, 1f, 9f)))

        val top = wild.clamped().preamp[PreampWay.TOP]

        assertThat(top).isEqualTo(WaySetup(20f, 20_000f, WaySetup.MAX_GAIN))
    }
}
