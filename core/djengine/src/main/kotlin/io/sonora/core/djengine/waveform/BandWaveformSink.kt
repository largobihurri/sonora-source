package io.sonora.core.djengine.waveform

import io.sonora.core.djmodel.BandWaveform
import io.sonora.core.djmodel.MonoSink
import io.sonora.core.djmodel.MonoSinkFactory
import io.sonora.core.domain.analysis.BlueWaveform
import kotlin.math.abs
import kotlin.math.max

/**
 * The 3-band coloured waveform sink: turns the mono audio of a track into a [BandWaveform], one column per 1/150 s,
 * with the level of the low, mid and high bands and the overall peak.
 *
 * ## Filter bank
 * The signal goes through three 4th-order Bessel filters ([BesselFilters], designed by the bilinear transform with
 * frequency pre-warping and run as cascades of biquads), as in Mixxx's waveform analyser (a Bessel low / band / high
 * filter bank): a low-pass at [LOW_CROSSOVER_HZ] (600 Hz) for **low**, a band-pass 600..4000 Hz for **mid** and a
 * high-pass at [HIGH_CROSSOVER_HZ] (4 kHz) for **high**. The crossovers are the ones of the DJ blueprint, the
 * calibration against rekordbox's own waveforms may move them. Bessel filters trade steepness (24 dB per octave
 * here, gentle compared with a Butterworth or Linkwitz-Riley of the same order) for a clean transient response: no
 * overshoot, so a kick does not ring into the next column.
 *
 * ## Columns
 * Column `i` covers frames `[floor(i * rate / 150), floor((i + 1) * rate / 150))`, exactly the detail columns of the
 * blue waveform ([BlueWaveform.detailColumnStartFrame]), and a partial last column is kept
 * ([BlueWaveform.detailColumnCount]), so both waveforms of a track line up column by column.
 *
 * ## Level: peak, not RMS
 * Each column holds the **peak** `max |y|` of each band's filtered signal, and the full-band peak `max |x|` of the
 * input, then maps it with the [BandLevelCurve]. A column is 6.7 ms at 44.1 kHz, shorter than one cycle of any tone
 * below 150 Hz: the RMS of such a fraction of a cycle swings with the phase (a 100 Hz sine over 6.7 ms is 0.67 of a
 * cycle, and a 50 Hz one only a third), so a bass line would flicker from column to column, whereas the peak of a
 * window of 240 degrees of a sine always contains the crest and a window of 120 degrees is at worst 87 % of it. Peaks
 * are also what the CDJ and Mixxx waveforms draw. The price is that one stray sample sets a column; the filters
 * smooth that inside each band.
 *
 * ## Allocation
 * Nothing is allocated per sample or per block: filter state lives in the cascades and the planes grow by doubling
 * (one byte per column and plane). The output is a function of the samples and the rate alone, so it is identical for
 * any block sizes. Non-finite input samples count as silence.
 *
 * This object is the factory (one [create] per track); the sink it creates is not thread-safe.
 *
 * @param sampleRateHz the rate the pipeline resamples the track to for this stage (16..192 kHz); with the default
 *   44.1 kHz a column is exactly 294 frames.
 * @param curve maps band peaks to bytes, see [BandLevelCurve].
 * @param version stored in the [BandWaveform]; raise [ALGORITHM_VERSION] (or pass a new one) when the crossovers or
 *   the curve change what the sink produces.
 */
class BandWaveformSink(
    override val sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
    private val curve: BandLevelCurve = BandLevelCurve.DEFAULT,
    private val version: Int = ALGORITHM_VERSION,
) : MonoSinkFactory<BandWaveform> {

    init {
        require(sampleRateHz in MIN_SAMPLE_RATE_HZ..MAX_SAMPLE_RATE_HZ) {
            "sampleRateHz must be $MIN_SAMPLE_RATE_HZ..$MAX_SAMPLE_RATE_HZ: $sampleRateHz"
        }
        require(version >= 1) { "version must be positive: $version" }
    }

    override fun create(): MonoSink<BandWaveform> = Sink(sampleRateHz, curve, version)

    private class Sink(
        private val sampleRateHz: Int,
        private val curve: BandLevelCurve,
        private val version: Int,
    ) : MonoSink<BandWaveform> {

        private val lowBand = BesselFilters.lowPass(LOW_CROSSOVER_HZ, sampleRateHz)
        private val midBand = BesselFilters.bandPass(LOW_CROSSOVER_HZ, HIGH_CROSSOVER_HZ, sampleRateHz)
        private val highBand = BesselFilters.highPass(HIGH_CROSSOVER_HZ, sampleRateHz)

        private val low = Plane()
        private val mid = Plane()
        private val high = Plane()
        private val peak = Plane()

        private var frames = 0L
        private var columns = 0L
        private var columnEnd = BlueWaveform.detailColumnStartFrame(1, sampleRateHz)
        private var columnFrames = 0
        private var finished = false

        // Peaks of the column being filled.
        private var lowPeak = 0.0
        private var midPeak = 0.0
        private var highPeak = 0.0
        private var fullPeak = 0.0

        override fun accept(samples: FloatArray, offset: Int, count: Int) {
            check(!finished) { "BandWaveformSink is finished" }
            require(offset >= 0 && count >= 0 && offset <= samples.size - count) {
                "range $offset + $count outside 0..${samples.size}"
            }
            for (i in offset until offset + count) {
                val raw = samples[i].toDouble()
                val x = if (raw.isFinite()) raw else 0.0
                lowPeak = max(lowPeak, abs(lowBand.process(x)))
                midPeak = max(midPeak, abs(midBand.process(x)))
                highPeak = max(highPeak, abs(highBand.process(x)))
                fullPeak = max(fullPeak, abs(x))
                columnFrames++
                if (++frames == columnEnd) closeColumn()
            }
        }

        override fun finish(): BandWaveform {
            check(!finished) { "BandWaveformSink is finished" }
            finished = true
            if (columnFrames > 0) closeColumn()
            check(columns == BlueWaveform.detailColumnCount(frames, sampleRateHz).toLong()) {
                "column count $columns does not follow the reference column math for $frames frames"
            }
            return BandWaveform(low.toByteArray(), mid.toByteArray(), high.toByteArray(), peak.toByteArray(), version)
        }

        private fun closeColumn() {
            low.add(curve.level(lowPeak))
            mid.add(curve.level(midPeak))
            high.add(curve.level(highPeak))
            peak.add(curve.level(fullPeak))
            columns++
            columnEnd = BlueWaveform.detailColumnStartFrame(columns + 1, sampleRateHz)
            columnFrames = 0
            lowPeak = 0.0
            midPeak = 0.0
            highPeak = 0.0
            fullPeak = 0.0
            lowBand.flushDenormals()
            midBand.flushDenormals()
            highBand.flushDenormals()
        }
    }

    /** A growable byte array (one byte per column), doubling when full. */
    private class Plane {
        private var bytes = ByteArray(INITIAL_CAPACITY)
        private var size = 0

        fun add(level: Int) {
            if (size == bytes.size) bytes = bytes.copyOf(bytes.size * 2)
            bytes[size++] = level.toByte()
        }

        fun toByteArray(): ByteArray = bytes.copyOf(size)

        private companion object {
            const val INITIAL_CAPACITY = 16_384 // about 110 s
        }
    }

    companion object {
        /** The default rate: a column is exactly 294 frames. */
        const val DEFAULT_SAMPLE_RATE_HZ: Int = 44_100

        /** Lowest and highest rate the filters are designed for (the 4 kHz crossover needs a comfortable Nyquist). */
        const val MIN_SAMPLE_RATE_HZ: Int = 16_000
        const val MAX_SAMPLE_RATE_HZ: Int = 192_000

        /** Version of the algorithm (crossovers, filter design, peak level, default curve) in [BandWaveform.version]. */
        const val ALGORITHM_VERSION: Int = 1

        /** Low / mid crossover: the low-pass corner and the lower edge of the band-pass, -3 dB. */
        const val LOW_CROSSOVER_HZ: Double = 600.0

        /** Mid / high crossover: the upper edge of the band-pass and the high-pass corner, -3 dB. */
        const val HIGH_CROSSOVER_HZ: Double = 4_000.0
    }
}
