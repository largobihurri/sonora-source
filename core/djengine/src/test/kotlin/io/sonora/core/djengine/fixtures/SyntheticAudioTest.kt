package io.sonora.core.djengine.fixtures

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.analysis.MusicalKey
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class SyntheticAudioTest {

    private val rate = 44_100

    /** Index of the first sample at or after [fromSample] whose magnitude exceeds [threshold]. */
    private fun firstAbove(x: FloatArray, fromSample: Int, threshold: Float = 0.001f): Int =
        (fromSample until x.size).first { abs(x[it]) > threshold }

    /** Amplitude of the component at [frequencyHz] over the whole signal (single-bin correlation). */
    private fun amplitudeAt(x: FloatArray, frequencyHz: Double, rateHz: Int): Double {
        var re = 0.0
        var im = 0.0
        for (n in x.indices) {
            val w = 2 * PI * frequencyHz * n / rateHz
            re += x[n] * cos(w)
            im += x[n] * sin(w)
        }
        return 2 * hypot(re, im) / x.size
    }

    @Test
    fun `beat times follow the tempo from the first beat`() {
        val times = SyntheticAudio.beatTimesMs(bpm = 120.0, firstBeatMs = 250.0, durationMs = 2000.0)

        assertThat(times.toList()).containsExactly(250.0, 750.0, 1250.0, 1750.0).inOrder()
    }

    @Test
    fun `a click track has the right length and a click on every beat`() {
        val x = SyntheticAudio.clickTrack(bpm = 120.0, durationMs = 3000.0, sampleRateHz = rate, firstBeatMs = 250.0)

        assertThat(x.size).isEqualTo(3000 * rate / 1000)
        for (t in SyntheticAudio.beatTimesMs(120.0, 250.0, 3000.0)) {
            val onset = ceil(t * rate / 1000.0).toInt()
            assertThat(firstAbove(x, onset - 200)).isEqualTo(onset)
        }
    }

    @Test
    fun `the track is silent between clicks`() {
        val x = SyntheticAudio.clickTrack(bpm = 60.0, durationMs = 2000.0, sampleRateHz = rate)

        // Clicks every 1000 ms last well under 100 ms.
        for (n in (rate / 10) until rate) assertThat(x[n]).isEqualTo(0f)
    }

    @Test
    fun `the sample rate sets the click grid`() {
        val x = SyntheticAudio.clickTrack(bpm = 120.0, durationMs = 1000.0, sampleRateHz = 22_050, firstBeatMs = 500.0)

        assertThat(x.size).isEqualTo(22_050)
        assertThat(firstAbove(x, 0)).isEqualTo(11_025)
    }

    @Test
    fun `accented clicks are louder every given number of beats`() {
        val x = SyntheticAudio.clickTrack(bpm = 120.0, durationMs = 4000.0, sampleRateHz = rate, accentEvery = 4)

        fun peakOfBeat(i: Int) = (i * rate / 2 until i * rate / 2 + 441).maxOf { abs(x[it]) }
        for (i in 0 until 8) {
            if (i % 4 == 0) assertThat(peakOfBeat(i)).isGreaterThan(0.8f) else assertThat(peakOfBeat(i)).isLessThan(0.6f)
        }
    }

    @Test
    fun `clicks can be placed at arbitrary times and accented one by one`() {
        val x = SyntheticAudio.clicks(doubleArrayOf(100.0, 400.5), 1000.0, rate, accented = booleanArrayOf(false, true))

        assertThat(firstAbove(x, 0)).isEqualTo(ceil(100.0 * rate / 1000).toInt())
        assertThat(firstAbove(x, 10_000)).isEqualTo(ceil(400.5 * rate / 1000).toInt())
        assertThrows(IllegalArgumentException::class.java) {
            SyntheticAudio.clicks(doubleArrayOf(1.0, 2.0), 100.0, rate, accented = booleanArrayOf(true))
        }
    }

    @Test
    fun `a tempo ramp speeds up smoothly from one tempo to the other`() {
        val times = SyntheticAudio.rampBeatTimesMs(startBpm = 100.0, endBpm = 140.0, firstBeatMs = 0.0, durationMs = 60_000.0)
        val intervals = times.toList().zipWithNext { a, b -> b - a }

        assertThat(times[0]).isEqualTo(0.0)
        assertThat(intervals.first()).isWithin(10.0).of(600.0) // 100 BPM
        assertThat(intervals.last()).isWithin(10.0).of(60_000.0 / 140.0)
        assertThat(intervals.zipWithNext { a, b -> b <= a }.all { it }).isTrue()
        // 100 -> 140 BPM average 120 over a minute: about 120 beats.
        assertThat(times.size).isIn(119..121)
    }

    @Test
    fun `a tempo ramp renders a click on every ramp beat`() {
        val x = SyntheticAudio.tempoRamp(100.0, 140.0, 10_000.0, rate)

        assertThat(x.size).isEqualTo(10 * rate)
        for (t in SyntheticAudio.rampBeatTimesMs(100.0, 140.0, 0.0, 10_000.0)) {
            val onset = ceil(t * rate / 1000.0).toInt()
            assertThat(firstAbove(x, maxOf(0, onset - 200))).isEqualTo(onset)
        }
    }

    @Test
    fun `a sine has the asked frequency and amplitude`() {
        val x = SyntheticAudio.sine(frequencyHz = 440.0, durationMs = 1000.0, sampleRateHz = rate, amplitude = 0.25)

        assertThat(x.size).isEqualTo(rate)
        assertThat(x.max()).isWithin(1e-3f).of(0.25f)
        // Interpolated rising zero crossings of the second half give the frequency.
        val crossings = (rate / 2 until x.size - 1).filter { x[it] < 0 && x[it + 1] >= 0 }
            .map { it + (-x[it] / (x[it + 1] - x[it])).toDouble() }
        val hz = (crossings.size - 1) * rate / (crossings.last() - crossings.first())
        assertThat(hz).isWithin(0.05).of(440.0)
    }

    @Test
    fun `pitch classes are equal-tempered from C3`() {
        assertThat(SyntheticAudio.pitchClassHz(0)).isWithin(1e-3).of(130.8128)
        assertThat(SyntheticAudio.pitchClassHz(9)).isWithin(1e-3).of(220.0) // A3
        assertThat(SyntheticAudio.pitchClassHz(0, octaveOffset = 1)).isWithin(1e-3).of(261.6256)
    }

    @Test
    fun `a triad holds the root, third and fifth of its key and not the semitone above the root`() {
        val x = SyntheticAudio.triad(MusicalKey.C_MAJOR, 1000.0, rate)
        val minor = SyntheticAudio.triad(MusicalKey.A_MINOR, 1000.0, rate)

        val c = amplitudeAt(x, SyntheticAudio.pitchClassHz(0), rate)
        assertThat(amplitudeAt(x, SyntheticAudio.pitchClassHz(4), rate)).isGreaterThan(c * 0.5) // E
        assertThat(amplitudeAt(x, SyntheticAudio.pitchClassHz(7), rate)).isGreaterThan(c * 0.5) // G
        assertThat(amplitudeAt(x, SyntheticAudio.pitchClassHz(1), rate)).isLessThan(c * 0.05) // C#
        // A minor: A, C, E.
        val a = amplitudeAt(minor, SyntheticAudio.pitchClassHz(9), rate)
        assertThat(amplitudeAt(minor, SyntheticAudio.pitchClassHz(0, 1), rate)).isGreaterThan(a * 0.3) // C above A3
        assertThat(amplitudeAt(minor, SyntheticAudio.pitchClassHz(10), rate)).isLessThan(a * 0.05) // Bb
        assertThat(x.maxOf { abs(it) }).isAtMost(0.5f)
    }

    @Test
    fun `triads play each key in turn and a progression stays inside its key`() {
        val chord = 500.0
        val sequence = SyntheticAudio.triads(listOf(MusicalKey.C_MAJOR, MusicalKey.G_MAJOR), chord, rate)
        val progression = SyntheticAudio.diatonicProgression(MusicalKey.C_MAJOR, chord, rate)

        assertThat(sequence.size).isEqualTo(2 * (chord * rate / 1000).toInt())
        assertThat(progression.size).isEqualTo(4 * (chord * rate / 1000).toInt())
        // The second chord of the sequence is G major: B (pitch class 11) is there, not in the first (C major) chord.
        val half = sequence.size / 2
        val firstChord = sequence.copyOfRange(0, half)
        val secondChord = sequence.copyOfRange(half, sequence.size)
        assertThat(amplitudeAt(secondChord, SyntheticAudio.pitchClassHz(11), rate)).isGreaterThan(0.05)
        assertThat(amplitudeAt(firstChord, SyntheticAudio.pitchClassHz(11), rate)).isLessThan(0.02)
        // C major I-IV-V-I never plays C# or F#.
        assertThat(amplitudeAt(progression, SyntheticAudio.pitchClassHz(1), rate)).isLessThan(0.01)
        assertThat(amplitudeAt(progression, SyntheticAudio.pitchClassHz(6), rate)).isLessThan(0.01)
    }

    @Test
    fun `noise bursts are deterministic and only sound inside their windows`() {
        val a = SyntheticAudio.noiseBursts(doubleArrayOf(100.0, 500.0), 50.0, 1000.0, rate, seed = 7L)
        val b = SyntheticAudio.noiseBursts(doubleArrayOf(100.0, 500.0), 50.0, 1000.0, rate, seed = 7L)
        val c = SyntheticAudio.noiseBursts(doubleArrayOf(100.0, 500.0), 50.0, 1000.0, rate, seed = 8L)

        assertThat(a).isEqualTo(b)
        assertThat(a).isNotEqualTo(c)
        assertThat(a.size).isEqualTo(rate)
        assertThat(a.sliceArray(0 until 4410).all { it == 0f }).isTrue()
        assertThat(a.sliceArray(4410 until 6615).any { it != 0f }).isTrue()
        assertThat(a.sliceArray(6616 until 22_050).all { it == 0f }).isTrue()
        assertThat(a.maxOf { abs(it) }).isAtMost(0.5f)
    }

    @Test
    fun `invalid arguments are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { SyntheticAudio.clickTrack(0.0, 1000.0) }
        assertThrows(IllegalArgumentException::class.java) { SyntheticAudio.sine(440.0, -1.0) }
        assertThrows(IllegalArgumentException::class.java) { SyntheticAudio.sine(440.0, 1000.0, sampleRateHz = 0) }
    }
}
