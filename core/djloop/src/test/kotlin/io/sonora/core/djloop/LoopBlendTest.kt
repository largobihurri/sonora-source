package io.sonora.core.djloop

import com.google.common.truth.Truth.assertThat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Test

class LoopBlendTest {

    private fun format(encoding: PcmEncoding) = PcmFormat(48_000, 2, encoding)

    /** Quantisation step of the encoding, as a tolerance (float: single precision). */
    private fun tolerance(encoding: PcmEncoding) = when (encoding) {
        PcmEncoding.PCM_16, PcmEncoding.PCM_16_BE -> 1.0 / 32768
        PcmEncoding.PCM_24, PcmEncoding.PCM_24_BE -> 1.0 / 8388608
        PcmEncoding.PCM_32, PcmEncoding.PCM_32_BE -> 1e-8
        PcmEncoding.FLOAT_32 -> 1e-7
    }

    @Test
    fun `the blend starts on the fading out audio and ends on the fading in audio for every encoding`() {
        for (encoding in PcmSamples.ALL_ENCODINGS) {
            val f = format(encoding)
            val n = 100
            val a = PcmSamples.signal(f, n) { 0.5 }
            val b = PcmSamples.signal(f, n) { -0.25 }
            val out = ByteArray(n * f.frameBytes)

            LoopBlend(f).blend(a, 0, b, 0, out, 0, n, 0, n)

            val tol = tolerance(encoding)
            assertThat(PcmSamples.read(f, out, 0, 0)).isWithin(tol).of(0.5)
            assertThat(PcmSamples.read(f, out, 0, 1)).isWithin(tol).of(0.45)
            // The last frame is at t = 99/100 of the quarter turn: almost all fade in.
            val gIn = sin(PI / 2 * 99 / 100)
            val gOut = kotlin.math.cos(PI / 2 * 99 / 100)
            assertThat(PcmSamples.read(f, out, n - 1, 0)).isWithin(tol * 2).of(0.5 * gOut - 0.25 * gIn)
        }
    }

    @Test
    fun `the middle of the blend has equal power gains`() {
        val f = format(PcmEncoding.FLOAT_32)
        val n = 100
        val a = PcmSamples.signal(f, n) { 1.0 }
        val b = PcmSamples.signal(f, n) { 1.0 }
        val out = ByteArray(n * f.frameBytes)

        LoopBlend(f).blend(a, 0, b, 0, out, 0, n, 0, n)

        // cos + sin of 45 degrees.
        assertThat(PcmSamples.read(f, out, 50, 0)).isWithin(1e-6).of(kotlin.math.sqrt(2.0))
    }

    @Test
    fun `blending in two pieces equals blending at once`() {
        for (encoding in PcmSamples.ALL_ENCODINGS) {
            val f = format(encoding)
            val n = 120
            val a = PcmSamples.signal(f, n) { sin(it * 0.3) * 0.7 }
            val b = PcmSamples.signal(f, n) { sin(it * 0.05 + 1) * 0.6 }
            val whole = ByteArray(n * f.frameBytes)
            val parts = ByteArray(n * f.frameBytes)
            val blend = LoopBlend(f)

            blend.blend(a, 0, b, 0, whole, 0, n, 0, n)
            val cut = 37
            blend.blend(a, 0, b, 0, parts, 0, cut, 0, n)
            blend.blend(a, cut * f.frameBytes, b, cut * f.frameBytes, parts, cut * f.frameBytes, n - cut, cut, n)

            assertThat(parts).isEqualTo(whole)
        }
    }

    @Test
    fun `integer encodings clamp instead of wrapping around`() {
        for (encoding in PcmSamples.ALL_ENCODINGS.filter { it != PcmEncoding.FLOAT_32 }) {
            val f = format(encoding)
            val n = 100
            val a = PcmSamples.signal(f, n) { 0.99999 }
            val b = PcmSamples.signal(f, n) { 0.99999 }
            val out = ByteArray(n * f.frameBytes)

            LoopBlend(f).blend(a, 0, b, 0, out, 0, n, 0, n)

            assertThat(PcmSamples.read(f, out, 50, 0)).isGreaterThan(0.99)
        }
    }

    @Test
    fun `a blend writes only the frames it is asked for`() {
        val f = format(PcmEncoding.PCM_16)
        val a = PcmSamples.signal(f, 10) { 0.5 }
        val out = ByteArray(10 * f.frameBytes) { 0x55 }

        LoopBlend(f).blend(a, 0, a, 0, out, 2 * f.frameBytes, 3, 0, 10)

        assertThat(out.copyOfRange(0, 2 * f.frameBytes).toSet()).containsExactly(0x55.toByte())
        assertThat(out.copyOfRange(5 * f.frameBytes, out.size).toSet()).containsExactly(0x55.toByte())
    }

    @Test
    fun `the wrap of a sine has no click where a hard wrap does`() {
        for (encoding in PcmSamples.ALL_ENCODINGS) {
            val f = format(encoding)
            val rate = 48_000.0
            val amplitude = 0.5
            val freq = 440.0
            val inFrame = 5_000
            val loopLength = 12_345
            val outFrame = inFrame + loopLength
            val n = LoopBlend.blendFrames(48_000, loopLength.toLong())
            val total = outFrame + n + 10
            val x = PcmSamples.signal(f, total) { amplitude * sin(2 * PI * freq * it / rate) }
            val fb = f.frameBytes

            // The sequence heard around the wrap: 50 frames before Out, then the first repeat's blend and 50 after it.
            val tail = 50
            val seq = ByteArray((tail + n + tail) * fb)
            System.arraycopy(x, (outFrame - tail) * fb, seq, 0, tail * fb)
            LoopBlend(f).blend(x, outFrame * fb, x, inFrame * fb, seq, tail * fb, n, 0, n)
            System.arraycopy(x, (inFrame + n) * fb, seq, (tail + n) * fb, tail * fb)
            val hard = ByteArray((tail + tail) * fb)
            System.arraycopy(x, (outFrame - tail) * fb, hard, 0, tail * fb)
            System.arraycopy(x, inFrame * fb, hard, tail * fb, tail * fb)

            fun maxDelta(data: ByteArray, frames: Int): Double {
                var max = 0.0
                for (i in 1 until frames) max = maxOf(max, abs(PcmSamples.read(f, data, i, 0) - PcmSamples.read(f, data, i - 1, 0)))
                return max
            }
            val natural = amplitude * 2 * PI * freq / rate
            assertThat(maxDelta(hard, tail + tail)).isGreaterThan(natural * 3)
            assertThat(maxDelta(seq, tail + n + tail)).isLessThan(natural * 1.6)
            // The blend is continuous with both neighbours: first frame is x[Out], the frame after it is x[In + n].
            assertThat(PcmSamples.read(f, seq, tail, 0)).isWithin(tolerance(encoding)).of(PcmSamples.read(f, x, outFrame, 0))
        }
    }

    @Test
    fun `the blend of big endian samples is the blend of the same samples byte swapped, for 16, 24 and 32 bits`() {
        val pairs = listOf(
            PcmEncoding.PCM_16 to PcmEncoding.PCM_16_BE,
            PcmEncoding.PCM_24 to PcmEncoding.PCM_24_BE,
            PcmEncoding.PCM_32 to PcmEncoding.PCM_32_BE,
        )
        for ((little, big) in pairs) {
            val fl = format(little)
            val fb = format(big)
            val n = 200
            // A ramp that crosses zero (negative samples exercise the sign) against a sine.
            val a = PcmSamples.signal(fl, n) { -0.9 + 1.8 * it / n }
            val b = PcmSamples.signal(fl, n) { 0.8 * sin(it * 0.2) }
            val littleOut = ByteArray(n * fl.frameBytes)
            val bigOut = ByteArray(n * fb.frameBytes)

            LoopBlend(fl).blend(a, 0, b, 0, littleOut, 0, n, 0, n)
            val aBig = PcmSamples.swapped(a, little.bytesPerSample)
            val bBig = PcmSamples.swapped(b, little.bytesPerSample)
            LoopBlend(fb).blend(aBig, 0, bBig, 0, bigOut, 0, n, 0, n)

            assertThat(PcmSamples.swapped(bigOut, big.bytesPerSample)).isEqualTo(littleOut)
            // And the values read back in the encoding's own order are the same numbers.
            for (frame in 0 until n step 13) {
                assertThat(PcmSamples.read(fb, bigOut, frame, 0)).isEqualTo(PcmSamples.read(fl, littleOut, frame, 0))
            }
        }
    }

    @Test
    fun `a big endian blend clamps like the little endian one and keeps the sign of negative samples`() {
        for (encoding in listOf(PcmEncoding.PCM_16_BE, PcmEncoding.PCM_24_BE, PcmEncoding.PCM_32_BE)) {
            val f = format(encoding)
            val n = 100
            val loud = PcmSamples.signal(f, n) { 0.99999 }
            val negative = PcmSamples.signal(f, n) { -0.5 }
            val out = ByteArray(n * f.frameBytes)

            LoopBlend(f).blend(loud, 0, loud, 0, out, 0, n, 0, n)
            assertThat(PcmSamples.read(f, out, 50, 0)).isGreaterThan(0.99)

            LoopBlend(f).blend(negative, 0, negative, 0, out, 0, n, 0, n)
            assertThat(PcmSamples.read(f, out, 0, 0)).isWithin(tolerance(encoding)).of(-0.5)
            assertThat(PcmSamples.read(f, out, 50, 0)).isLessThan(-0.6)
        }
    }

    @Test
    fun `the little endian blend is bit identical to an independent reference`() {
        val rng = java.util.Random(42)
        for (encoding in listOf(PcmEncoding.PCM_16, PcmEncoding.PCM_24, PcmEncoding.PCM_32)) {
            val f = format(encoding)
            val n = 160
            val a = ByteArray(n * f.frameBytes).also { rng.nextBytes(it) }
            val b = ByteArray(n * f.frameBytes).also { rng.nextBytes(it) }
            val out = ByteArray(n * f.frameBytes)

            LoopBlend(f).blend(a, 0, b, 0, out, 0, n, 0, n)

            val width = encoding.bytesPerSample
            val limit = (1L shl (8 * width - 1))
            for (i in 0 until n * 2) {
                val gOut = kotlin.math.cos(Math.PI / 2 * (i / 2) / n)
                val gIn = sin(Math.PI / 2 * (i / 2) / n)
                val expected = Math.round(littleSample(a, i * width, width) * gOut + littleSample(b, i * width, width) * gIn)
                    .coerceIn(-limit, limit - 1)
                assertThat(littleSample(out, i * width, width)).isEqualTo(expected.toDouble())
            }
        }
    }

    /** The signed little endian integer of [width] bytes at [at]. */
    private fun littleSample(data: ByteArray, at: Int, width: Int): Double {
        var raw = 0L
        for (i in 0 until width) raw = raw or ((data[at + i].toLong() and 0xFF) shl (8 * i))
        val shift = 64 - 8 * width
        return ((raw shl shift) shr shift).toDouble()
    }

    @Test
    fun `the blend lasts two and a half milliseconds and at most a quarter of the loop`() {
        assertThat(LoopBlend.blendFrames(48_000, 100_000)).isEqualTo(120)
        assertThat(LoopBlend.blendFrames(44_100, 100_000)).isEqualTo(110)
        assertThat(LoopBlend.blendFrames(48_000, 200)).isEqualTo(50)
        assertThat(LoopBlend.blendFrames(48_000, 3)).isEqualTo(0)
    }
}
