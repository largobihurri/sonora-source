package io.sonora.core.audiodsp.fft

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.lang.management.ManagementFactory
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class RealFftTest {

    private val sizes = generateSequence(2) { it * 2 }.takeWhile { it <= 4096 }.toList()

    private fun randomSignal(n: Int, seed: Long) = Random(seed).let { r -> FloatArray(n) { r.nextFloat() * 2f - 1f } }

    /** The textbook O(n^2) DFT of a real signal, bins 0..n/2, in double precision. */
    private fun naiveDft(x: FloatArray): Pair<DoubleArray, DoubleArray> {
        val n = x.size
        val re = DoubleArray(n / 2 + 1)
        val im = DoubleArray(n / 2 + 1)
        for (k in re.indices) {
            var r = 0.0
            var i = 0.0
            for (t in 0 until n) {
                val angle = -2.0 * PI * ((k.toLong() * t) % n) / n
                r += x[t] * cos(angle)
                i += x[t] * sin(angle)
            }
            re[k] = r
            im[k] = i
        }
        return re to im
    }

    private fun assertMatchesDft(x: FloatArray, fft: RealFft, tolerance: Double) {
        val (expectedRe, expectedIm) = naiveDft(x)
        val re = FloatArray(fft.binCount)
        val im = FloatArray(fft.binCount)

        fft.forward(x, re, im)

        for (k in re.indices) {
            assertThat(re[k].toDouble()).isWithin(tolerance).of(expectedRe[k])
            assertThat(im[k].toDouble()).isWithin(tolerance).of(expectedIm[k])
        }
    }

    @Test
    fun `forward matches a naive DFT of random input for every power of two from 2 to 4096`() {
        for (n in sizes) {
            assertMatchesDft(randomSignal(n, seed = n.toLong()), RealFft(n), tolerance = 2e-4)
        }
    }

    @Test
    fun `the DC and Nyquist bins are real`() {
        for (n in sizes) {
            val fft = RealFft(n)
            val re = FloatArray(fft.binCount)
            val im = FloatArray(fft.binCount)
            fft.forward(randomSignal(n, 7), re, im)
            assertThat(im[0]).isEqualTo(0f)
            assertThat(im[n / 2]).isEqualTo(0f)
        }
    }

    @Test
    fun `a sine on a bin puts all its energy there`() {
        val n = 1024
        val bin = 37
        val x = FloatArray(n) { sin(2.0 * PI * bin * it / n).toFloat() }
        val fft = RealFft(n)
        val re = FloatArray(fft.binCount)
        val im = FloatArray(fft.binCount)

        fft.forward(x, re, im)

        for (k in re.indices) {
            val magnitude = hypot(re[k].toDouble(), im[k].toDouble())
            if (k == bin) assertThat(magnitude).isWithin(1e-2).of(n / 2.0) else assertThat(magnitude).isLessThan(1e-2)
        }
    }

    @Test
    fun `an impulse has a flat spectrum and a constant has only DC`() {
        val n = 256
        val fft = RealFft(n)
        val re = FloatArray(fft.binCount)
        val im = FloatArray(fft.binCount)

        fft.forward(FloatArray(n).also { it[0] = 1f }, re, im)
        assertThat(re.toList().toSet()).containsExactly(1f)
        assertThat(im.toList().toSet()).containsExactly(0f)

        fft.forward(FloatArray(n) { 0.5f }, re, im)
        assertThat(re[0]).isWithin(1e-4f).of(n / 2f)
        for (k in 1 until re.size) assertThat(hypot(re[k], im[k])).isLessThan(1e-4f)
    }

    @Test
    fun `inverse undoes forward for every size`() {
        for (n in sizes) {
            val fft = RealFft(n)
            val x = randomSignal(n, seed = 100L + n)
            val re = FloatArray(fft.binCount)
            val im = FloatArray(fft.binCount)
            val back = FloatArray(n)

            fft.forward(x, re, im)
            fft.inverse(re, im, back)

            for (t in 0 until n) assertThat(back[t]).isWithin(1e-5f).of(x[t])
        }
    }

    @Test
    fun `inverse of a naive DFT gives the signal`() {
        val n = 128
        val x = randomSignal(n, 5)
        val (dr, di) = naiveDft(x)
        val out = FloatArray(n)

        RealFft(n).inverse(FloatArray(dr.size) { dr[it].toFloat() }, FloatArray(di.size) { di[it].toFloat() }, out)

        for (t in 0 until n) assertThat(out[t]).isWithin(1e-5f).of(x[t])
    }

    @Test
    fun `forward reads from an offset and inverse writes at one`() {
        val n = 64
        val x = randomSignal(n, 9)
        val padded = FloatArray(n + 10) { 99f }
        x.copyInto(padded, 3)
        val fft = RealFft(n)
        val re = FloatArray(fft.binCount)
        val im = FloatArray(fft.binCount)
        val expectedRe = FloatArray(fft.binCount)
        val expectedIm = FloatArray(fft.binCount)
        fft.forward(x, expectedRe, expectedIm)

        fft.forward(padded, re, im, inputOffset = 3)

        assertThat(re).isEqualTo(expectedRe)
        assertThat(im).isEqualTo(expectedIm)

        val out = FloatArray(n + 10) { -1f }
        fft.inverse(re, im, out, outputOffset = 5)
        assertThat(out[4]).isEqualTo(-1f)
        assertThat(out[5 + n]).isEqualTo(-1f)
        assertThat(out[5 + 17]).isWithin(1e-5f).of(x[17])
    }

    @Test
    fun `one instance gives the same result on every call`() {
        val fft = RealFft(512)
        val a = randomSignal(512, 1)
        val b = randomSignal(512, 2)
        fun run(x: FloatArray) = FloatArray(fft.binCount).also { re -> fft.forward(x, re, FloatArray(fft.binCount)) }

        val first = run(a)
        run(b)

        assertThat(run(a)).isEqualTo(first)
    }

    @Test
    fun `the size must be a power of two of at least 2`() {
        for (n in listOf(-4, 0, 1, 3, 6, 100, 1000)) {
            assertThrows(IllegalArgumentException::class.java) { RealFft(n) }
        }
    }

    @Test
    fun `buffers of the wrong size are rejected`() {
        val fft = RealFft(16)
        assertThrows(IllegalArgumentException::class.java) { fft.forward(FloatArray(15), FloatArray(9), FloatArray(9)) }
        assertThrows(IllegalArgumentException::class.java) { fft.forward(FloatArray(16), FloatArray(8), FloatArray(9)) }
        assertThrows(IllegalArgumentException::class.java) { fft.forward(FloatArray(16), FloatArray(9), FloatArray(8)) }
        assertThrows(IllegalArgumentException::class.java) { fft.inverse(FloatArray(9), FloatArray(9), FloatArray(15)) }
        assertThrows(IllegalArgumentException::class.java) { fft.inverse(FloatArray(8), FloatArray(9), FloatArray(16)) }
    }

    @Test
    fun `transforms allocate nothing after construction`() {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val fft = RealFft(2048)
        val x = randomSignal(2048, 3)
        val re = FloatArray(fft.binCount)
        val im = FloatArray(fft.binCount)
        val out = FloatArray(2048)
        repeat(2000) { fft.forward(x, re, im); fft.inverse(re, im, out) } // warm up the JIT

        val id = Thread.currentThread().id
        val before = threads.getThreadAllocatedBytes(id)
        repeat(200) { fft.forward(x, re, im); fft.inverse(re, im, out) }
        val allocated = threads.getThreadAllocatedBytes(id) - before

        assertThat(allocated).isLessThan(4096L)
    }
}
