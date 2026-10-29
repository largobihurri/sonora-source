package io.sonora.core.djengine.key

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.analysis.MusicalKey
import java.util.Random
import org.junit.Assert.assertThrows
import org.junit.Test

class KeyClassifierTest {

    private val bins = ChromaFrames.BINS_PER_FRAME

    /**
     * One 36-bin frame from 12 pitch-class values: pitch class `p` goes to bin `3 * ((p - firstBinPitchClass) mod 12)`,
     * plus [shiftBins] (a detuning in thirds of a semitone), wrapping around.
     */
    private fun frame(pitchClasses: DoubleArray, firstBinPitchClass: Int = 0, shiftBins: Int = 0): FloatArray {
        val out = FloatArray(bins)
        for (p in 0 until 12) {
            val bin = Math.floorMod(3 * Math.floorMod(p - firstBinPitchClass, 12) + shiftBins, bins)
            out[bin] += pitchClasses[p].toFloat()
        }
        return out
    }

    private fun frames(vararg perFrame: FloatArray): ChromaFrames {
        val all = FloatArray(perFrame.size * bins)
        perFrame.forEachIndexed { i, f -> f.copyInto(all, i * bins) }
        return ChromaFrames(all, framesPerSecond = 10.0)
    }

    /** Pitch-class vector with 1 on each of [notes] (semitones above C). */
    private fun notes(vararg notes: Int, weight: Double = 1.0): DoubleArray =
        DoubleArray(12).also { v -> for (n in notes) v[Math.floorMod(n, 12)] += weight }

    /** The profile of [key] as a pitch-class vector: degree d of the profile sits on pitch class root + d. */
    private fun template(profile: KeyProfile, key: MusicalKey): DoubleArray {
        val root = (key.index - 1) % 12
        return DoubleArray(12) { pc -> profile.weight(key.isMinor, Math.floorMod(pc - root, 12)) }
    }

    private fun majorTriad(root: Int) = notes(root, root + 4, root + 7)

    private fun minorTriad(root: Int) = notes(root, root + 3, root + 7)

    @Test
    fun `a I IV V I progression in C major is C major`() {
        val chroma = frames(
            frame(majorTriad(0)),
            frame(majorTriad(5)),
            frame(majorTriad(7)),
            frame(majorTriad(0)),
        )

        val estimate = KeyClassifier.classify(chroma)

        assertThat(estimate).isNotNull()
        assertThat(estimate!!.key).isEqualTo(MusicalKey.C_MAJOR)
        assertThat(estimate.method).isEqualTo("hpcp-edma/1")
    }

    @Test
    fun `an i iv v i progression in A minor is A minor`() {
        // Tonic and fifth are doubled in the bass, as in dance music: the bass carries the key.
        val chroma = frames(
            frame(minorTriad(9) + bass(9)),
            frame(minorTriad(2) + bass(2)),
            frame(minorTriad(4) + bass(4)),
            frame(minorTriad(9) + bass(9)),
        )

        assertThat(KeyClassifier.classify(chroma)!!.key).isEqualTo(MusicalKey.A_MINOR)
    }

    private fun bass(pitchClass: Int): DoubleArray = notes(pitchClass, weight = 1.0)

    private operator fun DoubleArray.plus(other: DoubleArray) = DoubleArray(12) { this[it] + other[it] }

    @Test
    fun `every rotation of every profile classifies to its own key with strength one`() {
        for (profile in listOf(KeyProfile.EDMA, KeyProfile.TEMPERLEY, KeyProfile.KRUMHANSL_KESSLER)) {
            for (key in MusicalKey.entries) {
                val estimate = KeyClassifier.classify(frames(frame(template(profile, key))), profile)

                assertThat(estimate).isNotNull()
                assertThat(estimate!!.key).isEqualTo(key)
                assertThat(estimate.strength).isWithin(1e-6).of(1.0)
                assertThat(estimate.method).isEqualTo("hpcp-${profile.id}/1")
            }
        }
    }

    @Test
    fun `the first bin pitch class says where bin zero is`() {
        val key = MusicalKey.E_FLAT_MINOR
        // The same content with bin 0 on A (pitch class 9, like a 440 Hz reference) instead of C.
        val chroma = frames(frame(template(KeyProfile.EDMA, key), firstBinPitchClass = 9))

        assertThat(KeyClassifier.classify(chroma, firstBinPitchClass = 9)!!.key).isEqualTo(key)
        // Read with the default (C at bin 0) the key is a different one: the parameter matters.
        assertThat(KeyClassifier.classify(chroma)!!.key).isNotEqualTo(key)
    }

    @Test
    fun `a third of a semitone of detuning is corrected either way`() {
        for (shift in listOf(-1, 1)) {
            for (key in listOf(MusicalKey.C_MAJOR, MusicalKey.F_SHARP_MINOR, MusicalKey.B_MAJOR, MusicalKey.G_MINOR)) {
                val chroma = frames(frame(template(KeyProfile.EDMA, key), shiftBins = shift))

                val estimate = KeyClassifier.classify(chroma)

                assertThat(estimate!!.key).isEqualTo(key)
                assertThat(estimate.strength).isWithin(1e-6).of(1.0)
            }
        }
    }

    @Test
    fun `silent zero and flat chroma give no key`() {
        assertThat(KeyClassifier.classify(ChromaFrames(FloatArray(0), 10.0))).isNull()
        assertThat(KeyClassifier.classify(ChromaFrames(FloatArray(bins * 20), 10.0))).isNull()
        assertThat(KeyClassifier.classify(ChromaFrames(FloatArray(bins * 20) { 0.7f }, 10.0))).isNull()
        assertThat(KeyClassifier.classify(ChromaFrames(FloatArray(bins * 3) { Float.NaN }, 10.0))).isNull()
    }

    @Test
    fun `non finite and negative values count as zero`() {
        val clean = frame(template(KeyProfile.EDMA, MusicalKey.D_MAJOR))
        val dirty = clean.copyOf().also {
            // Bins that hold no content in this frame (tonic of D is at bin 6; bin 1 is between C and C#).
            it[1] = Float.NaN
            it[4] = -3f
            it[7] = Float.POSITIVE_INFINITY
        }

        val estimate = KeyClassifier.classify(frames(dirty), KeyProfile.EDMA, detuningCorrection = false)

        assertThat(estimate!!.key).isEqualTo(MusicalKey.D_MAJOR)
        assertThat(estimate.strength).isWithin(1e-6).of(1.0)
    }

    @Test
    fun `strength stays in the correlation range for any input`() {
        val random = Random(7)
        repeat(50) {
            val values = FloatArray(bins * 25) { random.nextFloat() }

            val estimate = KeyClassifier.classify(ChromaFrames(values, 10.0))

            assertThat(estimate).isNotNull()
            assertThat(estimate!!.strength).isAtLeast(-1.0)
            assertThat(estimate.strength).isAtMost(1.0)
            // The best of the 12 rotations of a zero-mean template cannot be negative, and KeyEstimate wants 0..1.
            assertThat(estimate.strength).isAtLeast(0.0)
        }
    }

    @Test
    fun `clean input is stronger than noisy input and noise does not move a clear key`() {
        val key = MusicalKey.A_FLAT_MAJOR
        val clean = frame(template(KeyProfile.EDMA, key))
        val random = Random(11)
        val noisy = FloatArray(bins * 40)
        for (f in 0 until 40) {
            for (b in 0 until bins) noisy[f * bins + b] = clean[b] + 0.08f * random.nextFloat()
        }

        val cleanEstimate = KeyClassifier.classify(frames(clean))!!
        val noisyEstimate = KeyClassifier.classify(ChromaFrames(noisy, 10.0))!!

        assertThat(noisyEstimate.key).isEqualTo(key)
        assertThat(noisyEstimate.strength).isLessThan(cleanEstimate.strength)
        assertThat(noisyEstimate.strength).isGreaterThan(0.5)
    }

    @Test
    fun `heavier noise lowers the strength further`() {
        val clean = frame(template(KeyProfile.EDMA, MusicalKey.D_MINOR))
        fun noisyStrength(amplitude: Float): Double {
            val random = Random(5)
            val values = FloatArray(bins * 30)
            for (f in 0 until 30) for (b in 0 until bins) values[f * bins + b] = clean[b] + amplitude * random.nextFloat()
            return KeyClassifier.classify(ChromaFrames(values, 10.0), detuningCorrection = false)!!.strength
        }

        assertThat(noisyStrength(0.05f)).isGreaterThan(noisyStrength(0.2f))
        assertThat(noisyStrength(0.2f)).isGreaterThan(noisyStrength(0.6f))
    }

    @Test
    fun `silent frames are ignored`() {
        val tonic = frame(template(KeyProfile.EDMA, MusicalKey.G_MAJOR))
        // Near-silent frames whose pattern is a different key (what a normaliser makes of dither noise) and exact zeros.
        val whisper = frame(template(KeyProfile.EDMA, MusicalKey.C_SHARP_MINOR)).map { it * 1e-7f }.toFloatArray()
        val silence = FloatArray(bins)
        val padded = frames(*(listOf(silence, whisper) + List(3) { tonic } + List(200) { whisper } + List(50) { silence }).toTypedArray())
        val bare = frames(tonic, tonic, tonic)

        for (weighting in FrameWeighting.entries) {
            val a = KeyClassifier.classify(padded, weighting = weighting)!!
            val b = KeyClassifier.classify(bare, weighting = weighting)!!

            assertThat(a.key).isEqualTo(MusicalKey.G_MAJOR)
            assertThat(a.strength).isWithin(1e-9).of(b.strength)
        }
    }

    @Test
    fun `energy weighting lets loud frames count more than quiet ones`() {
        val loud = frame(template(KeyProfile.EDMA, MusicalKey.E_MAJOR)).map { it * 10f }.toFloatArray()
        val quiet = frame(template(KeyProfile.EDMA, MusicalKey.B_FLAT_MINOR))
        val chroma = frames(loud, quiet, quiet, quiet, quiet, quiet)

        assertThat(KeyClassifier.classify(chroma, weighting = FrameWeighting.ENERGY)!!.key).isEqualTo(MusicalKey.E_MAJOR)
        assertThat(KeyClassifier.classify(chroma, weighting = FrameWeighting.UNIFORM)!!.key).isEqualTo(MusicalKey.B_FLAT_MINOR)
    }

    @Test
    fun `other profiles are used when asked and tag the method`() {
        val chroma = frames(frame(majorTriad(0)), frame(majorTriad(5)), frame(majorTriad(7)), frame(majorTriad(0)))

        val temperley = KeyClassifier.classify(chroma, KeyProfile.TEMPERLEY)!!
        val krumhansl = KeyClassifier.classify(chroma, KeyProfile.KRUMHANSL_KESSLER)!!

        assertThat(temperley.key).isEqualTo(MusicalKey.C_MAJOR)
        assertThat(temperley.method).isEqualTo("hpcp-temperley/1")
        assertThat(krumhansl.key).isEqualTo(MusicalKey.C_MAJOR)
        assertThat(krumhansl.method).isEqualTo("hpcp-krumhansl/1")
    }

    @Test
    fun `the result does not depend on how the frames are grouped in time`() {
        val a = frame(majorTriad(2))
        val b = frame(majorTriad(7))
        val once = KeyClassifier.classify(frames(a, b, a, b))!!
        val twice = KeyClassifier.classify(frames(a, a, b, b, a, a, b, b))!!

        assertThat(twice.key).isEqualTo(once.key)
        assertThat(twice.strength).isWithin(1e-9).of(once.strength)
    }

    @Test
    fun `an invalid first bin pitch class is rejected`() {
        val chroma = frames(frame(majorTriad(0)))

        assertThrows(IllegalArgumentException::class.java) { KeyClassifier.classify(chroma, firstBinPitchClass = 12) }
        assertThrows(IllegalArgumentException::class.java) { KeyClassifier.classify(chroma, firstBinPitchClass = -1) }
    }
}
