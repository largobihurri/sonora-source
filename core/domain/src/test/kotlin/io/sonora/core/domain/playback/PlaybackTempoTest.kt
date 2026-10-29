package io.sonora.core.domain.playback

import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackTempoTest {

    @Test
    fun `the default plays at normal speed and pitch`() {
        val tempo = PlaybackTempo()

        assertThat(tempo.speed).isEqualTo(1f)
        assertThat(tempo.semitones).isEqualTo(0)
        assertThat(tempo.pitchFactor).isEqualTo(1f)
        assertThat(tempo.isDefault).isTrue()
        assertThat(PlaybackTempo.DEFAULT).isEqualTo(tempo)
    }

    @Test
    fun `a speed snaps to the nearest 0_05 step`() {
        assertThat(PlaybackTempo.DEFAULT.withSpeed(1.26f).speedPercent).isEqualTo(125)
        assertThat(PlaybackTempo.DEFAULT.withSpeed(1.28f).speedPercent).isEqualTo(130)
        assertThat(PlaybackTempo.DEFAULT.withSpeed(0.731f).speed).isEqualTo(0.75f)
    }

    @Test
    fun `a speed is clamped to half and double`() {
        assertThat(PlaybackTempo.DEFAULT.withSpeed(0.1f).speed).isEqualTo(0.5f)
        assertThat(PlaybackTempo.DEFAULT.withSpeed(3f).speed).isEqualTo(2f)
        assertThat(PlaybackTempo.DEFAULT.withSpeed(Float.NaN).speed).isEqualTo(1f)
    }

    @Test
    fun `speed steps move 0_05 at a time and stop at the ends`() {
        val tempo = PlaybackTempo.DEFAULT

        assertThat(tempo.stepSpeed(1).speedPercent).isEqualTo(105)
        assertThat(tempo.stepSpeed(-3).speedPercent).isEqualTo(85)
        assertThat(tempo.stepSpeed(40).speed).isEqualTo(2f)
        assertThat(tempo.stepSpeed(-40).speed).isEqualTo(0.5f)
    }

    @Test
    fun `pitch is clamped to an octave either way`() {
        assertThat(PlaybackTempo.DEFAULT.withSemitones(5).semitones).isEqualTo(5)
        assertThat(PlaybackTempo.DEFAULT.withSemitones(13).semitones).isEqualTo(12)
        assertThat(PlaybackTempo.DEFAULT.withSemitones(-20).semitones).isEqualTo(-12)
        assertThat(PlaybackTempo.DEFAULT.withSemitones(12).stepPitch(1).semitones).isEqualTo(12)
        assertThat(PlaybackTempo.DEFAULT.stepPitch(-2).semitones).isEqualTo(-2)
    }

    @Test
    fun `speed and pitch change independently`() {
        val tempo = PlaybackTempo.DEFAULT.withSemitones(3).withSpeed(1.5f)

        assertThat(tempo.semitones).isEqualTo(3)
        assertThat(tempo.speed).isEqualTo(1.5f)
        assertThat(tempo.withSpeed(0.8f).semitones).isEqualTo(3)
        assertThat(tempo.stepPitch(1).speed).isEqualTo(1.5f)
        assertThat(tempo.isDefault).isFalse()
        assertThat(PlaybackTempo.DEFAULT.withSemitones(1).isDefault).isFalse()
    }

    @Test
    fun `semitones convert to equal-tempered pitch factors`() {
        assertThat(PlaybackTempo.pitchFactorOf(12)).isEqualTo(2f)
        assertThat(PlaybackTempo.pitchFactorOf(-12)).isEqualTo(0.5f)
        assertThat(PlaybackTempo.pitchFactorOf(7).toDouble()).isWithin(1e-5).of(1.498307)
        assertThat(PlaybackTempo.DEFAULT.withSemitones(-1).pitchFactor.toDouble()).isWithin(1e-5).of(0.943874)
    }

    @Test
    fun `pitch factors convert back to the nearest semitone`() {
        for (semitones in PlaybackTempo.MIN_SEMITONES..PlaybackTempo.MAX_SEMITONES) {
            assertThat(PlaybackTempo.semitonesOf(PlaybackTempo.pitchFactorOf(semitones))).isEqualTo(semitones)
        }
        assertThat(PlaybackTempo.semitonesOf(1.03f)).isEqualTo(1)
        assertThat(PlaybackTempo.semitonesOf(8f)).isEqualTo(12)
        assertThat(PlaybackTempo.semitonesOf(0f)).isEqualTo(-12)
    }

    @Test
    fun `stored values are read back clamped onto the grid`() {
        assertThat(PlaybackTempo.restored(speedPercent = 127, semitones = 30)).isEqualTo(PlaybackTempo.ofPercent(125, 12))
        assertThat(PlaybackTempo.restored(speedPercent = 5, semitones = -3)).isEqualTo(PlaybackTempo.ofPercent(50, -3))
    }

    @Test
    fun `a tempo outside the speed bounds or its range cannot be built`() {
        assertThrows(IllegalArgumentException::class.java) { PlaybackTempo(speedBasisPoints = 4_999, range = TempoRange.WIDE) }
        assertThrows(IllegalArgumentException::class.java) { PlaybackTempo(speedBasisPoints = 20_001, range = TempoRange.WIDE) }
        assertThrows(IllegalArgumentException::class.java) { PlaybackTempo(speedBasisPoints = 11_500) }
        assertThrows(IllegalArgumentException::class.java) { PlaybackTempo(semitones = 13) }
    }

    // --- v2: basis points, ranges, Master Tempo -------------------------------------------------------------------

    @Test
    fun `the default is one times in range ten with Master Tempo on`() {
        val tempo = PlaybackTempo.DEFAULT

        assertThat(tempo.speedBasisPoints).isEqualTo(10_000)
        assertThat(tempo.tempoPercent).isEqualTo(0.0)
        assertThat(tempo.masterTempo).isTrue()
        assertThat(tempo.range).isEqualTo(TempoRange.TEN)
    }

    @Test
    fun `a tempo percent snaps to one basis point and reads back`() {
        val tempo = PlaybackTempo.DEFAULT.withTempoPercent(1.25)

        assertThat(tempo.speedBasisPoints).isEqualTo(10_125)
        assertThat(tempo.tempoPercent).isWithin(1e-9).of(1.25)
        assertThat(PlaybackTempo.DEFAULT.withTempoPercent(-3.333).speedBasisPoints).isEqualTo(9_667)
        assertThat(PlaybackTempo.DEFAULT.withTempoPercent(Double.NaN).speedBasisPoints).isEqualTo(10_000)
    }

    @Test
    fun `a tempo percent is clamped to the range`() {
        assertThat(PlaybackTempo.DEFAULT.withTempoPercent(50.0).speedBasisPoints).isEqualTo(11_000)
        assertThat(PlaybackTempo.DEFAULT.withTempoPercent(-50.0).speedBasisPoints).isEqualTo(9_000)
        assertThat(PlaybackTempo.DEFAULT.withRange(TempoRange.WIDE).withTempoPercent(500.0).speedBasisPoints).isEqualTo(20_000)
        assertThat(PlaybackTempo.DEFAULT.withRange(TempoRange.WIDE).withTempoPercent(-90.0).speedBasisPoints).isEqualTo(5_000)
    }

    @Test
    fun `every range has the documented bounds and fader step`() {
        assertThat(TempoRange.entries.map { it.code }).containsExactly("6", "10", "16", "wide").inOrder()
        assertThat(listOf(TempoRange.SIX.minBp, TempoRange.SIX.maxBp, TempoRange.SIX.faderStepBp)).containsExactly(9_400, 10_600, 2).inOrder()
        assertThat(listOf(TempoRange.TEN.minBp, TempoRange.TEN.maxBp, TempoRange.TEN.faderStepBp)).containsExactly(9_000, 11_000, 5).inOrder()
        assertThat(listOf(TempoRange.SIXTEEN.minBp, TempoRange.SIXTEEN.maxBp, TempoRange.SIXTEEN.faderStepBp)).containsExactly(8_400, 11_600, 5).inOrder()
        assertThat(listOf(TempoRange.WIDE.minBp, TempoRange.WIDE.maxBp, TempoRange.WIDE.faderStepBp)).containsExactly(5_000, 20_000, 50).inOrder()
    }

    @Test
    fun `range codes are stable and unknown codes read as null`() {
        TempoRange.entries.forEach { assertThat(TempoRange.fromCode(it.code)).isEqualTo(it) }
        assertThat(TempoRange.fromCode("12")).isNull()
        assertThat(TempoRange.fromCode(null)).isNull()
    }

    @Test
    fun `no range goes beyond the maximum speed`() {
        TempoRange.entries.forEach { assertThat(it.maxBp / 10_000.0).isAtMost(PlaybackTempo.MAX_SPEED) }
        assertThat(PlaybackTempo.MAX_SPEED).isEqualTo(2.0)
    }

    @Test
    fun `the fader ends reach the range bounds and the centre is zero percent`() {
        TempoRange.entries.forEach { range ->
            val tempo = PlaybackTempo.DEFAULT.withRange(range)
            assertThat(tempo.withFader(1.0).speedBasisPoints).isEqualTo(range.maxBp)
            assertThat(tempo.withFader(-1.0).speedBasisPoints).isEqualTo(range.minBp)
            assertThat(tempo.withFader(0.0).speedBasisPoints).isEqualTo(10_000)
            assertThat(tempo.withFader(5.0).speedBasisPoints).isEqualTo(range.maxBp)
            assertThat(tempo.withFader(-5.0).speedBasisPoints).isEqualTo(range.minBp)
        }
    }

    @Test
    fun `the wide fader is asymmetric around its centre`() {
        val wide = PlaybackTempo.DEFAULT.withRange(TempoRange.WIDE)

        assertThat(wide.withFader(0.5).tempoPercent).isWithin(1e-9).of(50.0)
        assertThat(wide.withFader(-0.5).tempoPercent).isWithin(1e-9).of(-25.0)
    }

    @Test
    fun `the fader is quantised to the range step`() {
        val ten = PlaybackTempo.DEFAULT
        // Range ten: 1000 bp each way in steps of 5 bp, so 0.0024 is 2.4 bp (rounds to 0) and 0.0026 is 2.6 bp (to 5).
        assertThat(ten.withFader(0.0024).speedBasisPoints).isEqualTo(10_000)
        assertThat(ten.withFader(0.0026).speedBasisPoints).isEqualTo(10_005)
        val six = PlaybackTempo.DEFAULT.withRange(TempoRange.SIX)
        assertThat(six.withFader(0.0034).speedBasisPoints).isEqualTo(10_002)
        val wide = PlaybackTempo.DEFAULT.withRange(TempoRange.WIDE)
        assertThat(wide.withFader(0.024).speedBasisPoints % 50).isEqualTo(0)
        assertThat(wide.withFader(0.011).speedBasisPoints).isEqualTo(10_100)
    }

    @Test
    fun `the fader is monotonic and never leaves the range`() {
        TempoRange.entries.forEach { range ->
            val tempo = PlaybackTempo.DEFAULT.withRange(range)
            var last = Int.MIN_VALUE
            for (i in -200..200) {
                val bp = tempo.withFader(i / 200.0).speedBasisPoints
                assertThat(bp).isAtLeast(last)
                assertThat(bp).isAtLeast(range.minBp)
                assertThat(bp).isAtMost(range.maxBp)
                last = bp
            }
        }
    }

    @Test
    fun `the displayed tempo is the track tempo times the speed rounded to a tenth`() {
        assertThat(PlaybackTempo.DEFAULT.withTempoPercent(1.25).displayedBpm(128.0)).isWithin(1e-9).of(129.6)
        assertThat(PlaybackTempo.DEFAULT.displayedBpm(127.96)).isWithin(1e-9).of(128.0)
    }

    @Test
    fun `a nudge of a tenth from the track tempo reads one tenth higher within a basis point`() {
        val nudged = PlaybackTempo.DEFAULT.nudgeBpm(trackBpm = 128.0, deltaBpm = 0.1)

        assertThat(nudged.displayedBpm(128.0)).isWithin(1e-9).of(128.1)
        assertThat(abs(128.0 * nudged.speedBasisPoints / 10_000.0 - 128.1)).isAtMost(0.0064)
        assertThat(nudged.speedBasisPoints).isEqualTo(10_008)
    }

    @Test
    fun `fifty nudges up then fifty down return to the start without drift`() {
        for (track in listOf(128.0, 87.3, 174.0, 60.5, 99.9)) {
            val start = PlaybackTempo.DEFAULT
            var tempo = start
            repeat(50) { tempo = tempo.nudgeBpm(track, 0.1) }
            assertThat(tempo.displayedBpm(track)).isWithin(1e-9).of(Math.round(track * 10.0) / 10.0 + 5.0)
            repeat(50) { tempo = tempo.nudgeBpm(track, -0.1) }
            assertThat(tempo.speedBasisPoints).isEqualTo(start.speedBasisPoints)
        }
    }

    @Test
    fun `nudges stop at the range bounds and ignore an unusable track tempo`() {
        var tempo = PlaybackTempo.DEFAULT
        repeat(200) { tempo = tempo.nudgeBpm(128.0, 0.1) }
        assertThat(tempo.speedBasisPoints).isEqualTo(11_000)
        assertThat(PlaybackTempo.DEFAULT.nudgeBpm(0.0, 0.1)).isEqualTo(PlaybackTempo.DEFAULT)
        assertThat(PlaybackTempo.DEFAULT.nudgeBpm(Double.NaN, 0.1)).isEqualTo(PlaybackTempo.DEFAULT)
        assertThat(PlaybackTempo.DEFAULT.nudgeBpm(128.0, Double.NaN)).isEqualTo(PlaybackTempo.DEFAULT)
    }

    @Test
    fun `narrowing the range clamps the speed and widening keeps it`() {
        val wide = PlaybackTempo.DEFAULT.withRange(TempoRange.WIDE).withTempoPercent(40.0)

        val narrowed = wide.withRange(TempoRange.SIX)
        assertThat(narrowed.range).isEqualTo(TempoRange.SIX)
        assertThat(narrowed.speedBasisPoints).isEqualTo(10_600)
        assertThat(PlaybackTempo.DEFAULT.withTempoPercent(-8.0).withRange(TempoRange.SIX).speedBasisPoints).isEqualTo(9_400)
        assertThat(narrowed.withRange(TempoRange.WIDE).speedBasisPoints).isEqualTo(10_600)
    }

    @Test
    fun `reset goes to zero percent and keeps the range, Master Tempo and pitch`() {
        val tempo = PlaybackTempo.DEFAULT.withRange(TempoRange.SIXTEEN).withMasterTempo(false).withSemitones(3).withTempoPercent(8.0)

        val reset = tempo.resetTempo()

        assertThat(reset.speedBasisPoints).isEqualTo(10_000)
        assertThat(reset.range).isEqualTo(TempoRange.SIXTEEN)
        assertThat(reset.masterTempo).isFalse()
        assertThat(reset.semitones).isEqualTo(3)
    }

    @Test
    fun `the pitch factor follows Master Tempo and the semitones`() {
        val faster = PlaybackTempo.DEFAULT.withTempoPercent(8.0)

        assertThat(faster.pitchFactor.toDouble()).isWithin(1e-6).of(1.0)
        assertThat(faster.withMasterTempo(false).pitchFactor.toDouble()).isWithin(1e-6).of(1.08)
        assertThat(faster.withSemitones(12).pitchFactor.toDouble()).isWithin(1e-6).of(2.0)
        assertThat(faster.withSemitones(12).withMasterTempo(false).pitchFactor.toDouble()).isWithin(1e-6).of(2.16)
        assertThat(faster.withSemitones(-12).withMasterTempo(false).pitchFactor.toDouble()).isWithin(1e-6).of(0.54)
        assertThat(PlaybackTempo.DEFAULT.withMasterTempo(false).pitchFactor).isEqualTo(1f)
    }

    @Test
    fun `speed and the default flag follow the basis points`() {
        val tempo = PlaybackTempo.DEFAULT.withTempoPercent(1.25)

        assertThat(tempo.speed).isEqualTo(1.0125f)
        assertThat(tempo.isDefault).isFalse()
        assertThat(tempo.resetTempo().isDefault).isTrue()
        assertThat(PlaybackTempo.DEFAULT.withMasterTempo(false).withRange(TempoRange.SIX).isDefault).isTrue()
    }

    @Test
    fun `a legacy speed percent restores to basis points in the narrowest fitting range`() {
        val ten = PlaybackTempo.restored(speedPercent = 105, semitones = 0)
        assertThat(ten.speedBasisPoints).isEqualTo(10_500)
        assertThat(ten.range).isEqualTo(TempoRange.TEN)
        assertThat(PlaybackTempo.restored(speedPercent = 115, semitones = 0).range).isEqualTo(TempoRange.SIXTEEN)
        assertThat(PlaybackTempo.restored(speedPercent = 125, semitones = 0).range).isEqualTo(TempoRange.WIDE)
        assertThat(PlaybackTempo.restored(speedPercent = 75, semitones = 0).range).isEqualTo(TempoRange.WIDE)
        assertThat(PlaybackTempo.restored(speedPercent = 90, semitones = 0).range).isEqualTo(TempoRange.TEN)
        assertThat(PlaybackTempo.restored(speedPercent = 105, semitones = 0).masterTempo).isTrue()
    }

    @Test
    fun `the legacy speed steps widen the range only when they must`() {
        assertThat(PlaybackTempo.DEFAULT.stepSpeed(2).range).isEqualTo(TempoRange.TEN)
        assertThat(PlaybackTempo.DEFAULT.stepSpeed(3).range).isEqualTo(TempoRange.SIXTEEN)
        assertThat(PlaybackTempo.DEFAULT.withSpeed(1.5f).range).isEqualTo(TempoRange.WIDE)
        assertThat(PlaybackTempo.DEFAULT.withRange(TempoRange.WIDE).withSpeed(1.05f).range).isEqualTo(TempoRange.WIDE)
        assertThat(PlaybackTempo.DEFAULT.stepSpeed(1).speedPercent).isEqualTo(105)
    }

    @Test
    fun `the percent factory builds a fitting tempo`() {
        val tempo = PlaybackTempo.ofPercent(125, semitones = 2)

        assertThat(tempo.speedBasisPoints).isEqualTo(12_500)
        assertThat(tempo.semitones).isEqualTo(2)
        assertThat(tempo.range).isEqualTo(TempoRange.WIDE)
        assertThat(PlaybackTempo.ofPercent(100)).isEqualTo(PlaybackTempo.DEFAULT)
        assertThat(PlaybackTempo.ofPercent(300).speedBasisPoints).isEqualTo(20_000)
    }
}
