package io.sonora.core.djengine.mel

import io.sonora.core.audiodsp.fft.RealFft
import io.sonora.core.djmodel.MelSpectrogram
import io.sonora.core.djmodel.MonoSink
import io.sonora.core.djmodel.MonoSinkFactory
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln1p
import kotlin.math.sqrt

/**
 * The front end of the Beat This! beat tracker (CPJKU/beat_this, MIT, `beat_this/preprocessing.py` `LogMelSpect`; the
 * parameters are in `tools/beatthis/README.md`): a log-mel spectrogram of mono 22.05 kHz audio, 50 frames per second.
 *
 * Per frame of [FFT_SIZE] = 1024 samples, hop [HOP_SIZE] = 441:
 *
 * 1. a periodic Hann window, `0.5 - 0.5 cos(2 pi n / 1024)`;
 * 2. the magnitude (not the power) of the real FFT, times `1 / sqrt(1024)`;
 * 3. the 128 triangular Slaney mel bands from 30 to 11000 Hz ([MelFilterbank]; peak 1, no area normalisation);
 * 4. `log1p(1000 x)`.
 *
 * The STFT is centred as `torch.stft(center=True, pad_mode="reflect")`: the signal is reflected about its first and
 * last sample by 512 samples on both sides (the edge samples themselves are not repeated), so frame `f` is centred on
 * sample `441 f` and a signal of `n` samples gives `1 + floor(n / 441)` frames. The sink sees the stream in blocks, so
 * it holds back the first 513 samples until it can reflect the start, and keeps the last 513 to reflect the end in
 * [MonoSink.finish]; the frames are the same whatever the block sizes (bit for bit). A signal of at most 512 samples,
 * which torch refuses, is padded like numpy's `reflect` (the reflection repeats), and an empty signal gives no frames.
 *
 * Arithmetic: the transform is [RealFft] (double precision inside, float arrays), the mel projection and the
 * compression are double precision, and the output is float. Against the float64 numpy reference of the goldens the
 * difference is far below the recommended 5e-4 (the torch float32 front end itself differs by up to 1.1e-4).
 *
 * The filterbank is computed in Kotlin from the formula, not loaded from the golden file; the test compares both
 * (they differ only by the float32 rounding of the file).
 *
 * Memory: the sink allocates its buffers and the FFT tables when it is created; accepting samples allocates only the
 * output (pages of [PAGE_FRAMES] frames), and [MonoSink.finish] one array of the final size.
 */
class LogMelSink : MonoSinkFactory<MelSpectrogram> {

    override val sampleRateHz: Int = SAMPLE_RATE_HZ

    override fun create(): MonoSink<MelSpectrogram> = Sink()

    private class Sink : MonoSink<MelSpectrogram> {
        private val fft = RealFft(FFT_SIZE)
        private val re = FloatArray(fft.binCount)
        private val im = FloatArray(fft.binCount)
        private val magnitude = DoubleArray(fft.binCount)
        private val bands = DoubleArray(BAND_COUNT)
        private val windowed = FloatArray(FFT_SIZE)

        // The padded signal arrives here; frames are cut from the front every HOP_SIZE samples.
        private val pending = FloatArray(PENDING_CAPACITY)
        private var pendingCount = 0

        // The first HALF + 1 samples, until the start can be reflected.
        private val head = FloatArray(PAD + 1)
        private var headCount = 0
        private var started = false

        // The last PAD + 1 samples of the signal (ring buffer), to reflect the end.
        private val recent = FloatArray(PAD + 1)
        private var recentPos = 0

        private val pages = ArrayList<FloatArray>()
        private var frames = 0

        override fun accept(samples: FloatArray, offset: Int, count: Int) {
            require(offset >= 0 && count >= 0 && offset + count <= samples.size) { "range $offset+$count outside ${samples.size}" }
            var at = offset
            var left = count
            if (!started) {
                val take = minOf(left, head.size - headCount)
                System.arraycopy(samples, at, head, headCount, take)
                headCount += take
                at += take
                left -= take
                if (headCount < head.size) return
                start()
            }
            if (left > 0) {
                remember(samples, at, left)
                feed(samples, at, left)
            }
        }

        override fun finish(): MelSpectrogram {
            if (!started) {
                if (headCount > 0) padShortSignal() // fewer than 513 samples
            } else {
                // Reflect the end: padded[n + 512 + j] = x[n - 2 - j], j = 0 until 512.
                val tail = FloatArray(PAD)
                for (j in 0 until PAD) tail[j] = recent[Math.floorMod(recentPos - 2 - j, recent.size)]
                feed(tail, 0, PAD)
            }
            val values = FloatArray(frames * BAND_COUNT)
            var at = 0
            for (page in pages) {
                val n = minOf(page.size, values.size - at)
                System.arraycopy(page, 0, values, at, n)
                at += n
            }
            pages.clear()
            return MelSpectrogram(BAND_COUNT, FRAMES_PER_SECOND, values)
        }

        /** 513 samples are here: feed the reflected start `x[512] .. x[1]`, then the head itself. */
        private fun start() {
            started = true
            val prefix = FloatArray(PAD) { head[PAD - it] }
            feed(prefix, 0, PAD)
            feed(head, 0, head.size)
            System.arraycopy(head, 0, recent, 0, head.size)
            recentPos = 0
        }

        /** Fewer than 513 samples in total: pad both ends like numpy's `reflect` (periodic with period 2 (n - 1)). */
        private fun padShortSignal() {
            val n = headCount
            val padded = FloatArray(n + 2 * PAD) { head[reflect(it - PAD, n)] }
            feed(padded, 0, padded.size)
        }

        private fun reflect(index: Int, n: Int): Int {
            if (n == 1) return 0
            val period = 2 * (n - 1)
            val m = Math.floorMod(index, period)
            return if (m < n) m else period - m
        }

        private fun remember(samples: FloatArray, at: Int, count: Int) {
            val size = recent.size
            if (count >= size) {
                System.arraycopy(samples, at + count - size, recent, 0, size)
                recentPos = 0
                return
            }
            val first = minOf(count, size - recentPos)
            System.arraycopy(samples, at, recent, recentPos, first)
            System.arraycopy(samples, at + first, recent, 0, count - first)
            recentPos = (recentPos + count) % size
        }

        /** Appends padded-signal samples and emits every frame that is complete. */
        private fun feed(samples: FloatArray, offset: Int, count: Int) {
            var at = offset
            var left = count
            while (left > 0) {
                val n = minOf(left, pending.size - pendingCount)
                System.arraycopy(samples, at, pending, pendingCount, n)
                pendingCount += n
                at += n
                left -= n
                var position = 0
                while (pendingCount - position >= FFT_SIZE) {
                    emit(position)
                    position += HOP_SIZE
                }
                if (position > 0) {
                    System.arraycopy(pending, position, pending, 0, pendingCount - position)
                    pendingCount -= position
                }
            }
        }

        private fun emit(position: Int) {
            for (i in 0 until FFT_SIZE) windowed[i] = (pending[position + i] * WINDOW[i]).toFloat()
            fft.forward(windowed, re, im)
            for (k in 0 until fft.binCount) {
                val r = re[k].toDouble()
                val x = im[k].toDouble()
                magnitude[k] = sqrt(r * r + x * x) * MAGNITUDE_SCALE
            }
            FILTERBANK.project(magnitude, bands)

            val inPage = frames % PAGE_FRAMES
            if (inPage == 0) pages += FloatArray(PAGE_FRAMES * BAND_COUNT)
            val page = pages[pages.size - 1]
            for (band in 0 until BAND_COUNT) page[inPage * BAND_COUNT + band] = ln1p(LOG_MULTIPLIER * bands[band]).toFloat()
            frames++
        }
    }

    companion object {
        const val SAMPLE_RATE_HZ: Int = 22_050
        const val FFT_SIZE: Int = 1024
        const val HOP_SIZE: Int = 441
        const val BAND_COUNT: Int = 128
        const val FRAMES_PER_SECOND: Double = 50.0

        private const val PAD = FFT_SIZE / 2
        private const val PAGE_FRAMES = 256
        private const val PENDING_CAPACITY = 8 * FFT_SIZE
        private const val LOG_MULTIPLIER = 1000.0
        private val MAGNITUDE_SCALE = 1.0 / sqrt(FFT_SIZE.toDouble())

        private val WINDOW = DoubleArray(FFT_SIZE) { 0.5 - 0.5 * cos(2.0 * PI * it / FFT_SIZE) }
        private val FILTERBANK = MelFilterbank(SAMPLE_RATE_HZ, FFT_SIZE, BAND_COUNT, 30.0, 11_000.0)
    }
}
