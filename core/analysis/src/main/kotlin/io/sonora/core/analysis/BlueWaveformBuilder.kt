package io.sonora.core.analysis

import io.sonora.core.domain.analysis.BlueWaveform
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Builds the [BlueWaveform] of one track from its mono samples, block by block, in the same pass as the beat and key
 * analysis. Pure Kotlin and deterministic: the result depends only on the samples and the sample rate, never on the
 * block sizes or on whether the length was known in advance. Not thread-safe.
 *
 * Every detail column covers `sampleRate / 150` frames (column `i` starts at frame `floor(i * sampleRate / 150)`) and
 * gets:
 * - height `floor(31.9 * peak²)`, clamped to 31: a power curve on the column's sample peak;
 * - whiteness `round(7 * highEnergy / energy)`: the share of the energy above a 200 Hz Butterworth high-pass.
 *
 * The previews are reduced from per-column figures at [finish], so they need no track length up front: the preview
 * (400 columns) is `17.4 * mean peak` of the samples with the bass band (around 150 Hz) boosted, and whiteness
 * `min(5, round(7 * highEnergy / energy))` over its span; the tiny preview (100 columns) is `30 * mean bass-band peak`.
 * Both are then levelled per track like rekordbox's: when the track's loud columns (the 90th percentile) would stand
 * above 22 (preview) or 14 (tiny preview), every column is scaled down by the same factor, so a loud master keeps its
 * shape (quiet intros and breaks stay lower) instead of saturating; heights are rounded and clamped to 24 and 15.
 * Per-column preview figures are kept in at most [PREVIEW_BINS] bins that merge pairwise as the track grows, so memory
 * stays bounded (the detail itself is one byte per column).
 *
 * Calibrated against rekordbox's own analysis of a 20 kHz-20 Hz sine sweep (stereo, then left only, then right only;
 * rekordcrate test data `data/anlz/sweep`): full-scale sine detail height 31, one channel (-6 dB) 7; detail whiteness
 * 7 above ~450 Hz falling to 0 below ~100 Hz (matches the 200 Hz Butterworth share within one step in 99.9% of the
 * columns); preview height ~17 for treble at full scale, ~9 at -6 dB, up to ~24-25 for bass (rekordbox never went
 * above 25); tiny preview ~1-2 for treble and 13-15 for bass. The levelling follows rekordbox's demo exports (rekordcrate
 * `data/complete_export/demo_tracks`): preview heights median 18 and 11, 90th percentile 22 and 18, maximum 24 and 22;
 * tiny preview median 13, maximum 15 and 14. rekordbox's exact algorithm is unknown: this is an approximation, not a
 * byte-for-byte match.
 */
class BlueWaveformBuilder(private val sampleRateHz: Int) {

    init {
        require(sampleRateHz in MIN_SAMPLE_RATE_HZ..MAX_SAMPLE_RATE_HZ) {
            "sampleRateHz must be $MIN_SAMPLE_RATE_HZ..$MAX_SAMPLE_RATE_HZ: $sampleRateHz"
        }
    }

    private val highPass = Biquad.highPass(WHITENESS_CROSSOVER_HZ, BUTTERWORTH_Q, sampleRateHz)
    private val bassBand = Biquad.bandPass(BASS_CENTRE_HZ, BASS_Q, sampleRateHz)
    private val detail = ByteBuffer()
    private val bins = PreviewBins(PREVIEW_BINS)

    private var frames = 0L
    private var columns = 0L
    private var columnEnd = BlueWaveform.detailColumnStartFrame(1, sampleRateHz)
    private var finished = false

    // Figures of the column being filled.
    private var columnFrames = 0
    private var peak = 0.0
    private var energy = 0.0
    private var highEnergy = 0.0
    private var boostedPeak = 0.0
    private var bassPeak = 0.0

    /** Feeds the next [count] mono samples of [samples] (from index 0). Non-finite samples count as silence. */
    fun process(samples: FloatArray, count: Int) {
        check(!finished) { "BlueWaveformBuilder is finished" }
        require(count in 0..samples.size) { "count $count outside 0..${samples.size}" }
        for (i in 0 until count) {
            val raw = samples[i].toDouble()
            val x = if (raw.isFinite()) raw else 0.0
            val high = highPass.filter(x)
            val bass = bassBand.filter(x)
            peak = max(peak, abs(x))
            energy += x * x
            highEnergy += high * high
            boostedPeak = max(boostedPeak, abs(x + BASS_BOOST * bass))
            bassPeak = max(bassPeak, abs(bass))
            columnFrames++
            if (++frames == columnEnd) closeColumn()
        }
    }

    /** Ends the input and returns the waveforms. Callable once. */
    fun finish(): BlueWaveform {
        check(!finished) { "BlueWaveformBuilder is finished" }
        finished = true
        if (columnFrames > 0) closeColumn()
        val columnCount = Math.toIntExact(columns)
        val previewSpans = List(BlueWaveform.PREVIEW_COLUMNS) { bins.span(it, BlueWaveform.PREVIEW_COLUMNS, columnCount) }
        val previewHeights = levelled(
            previewSpans.map { PREVIEW_HEIGHT_SCALE * it.boostedPeakSum / max(1L, it.columns) },
            PREVIEW_LOUD_HEIGHT,
            MAX_PREVIEW_HEIGHT,
        )
        val tinyHeights = levelled(
            List(BlueWaveform.TINY_PREVIEW_COLUMNS) {
                val span = bins.span(it, BlueWaveform.TINY_PREVIEW_COLUMNS, columnCount)
                TINY_HEIGHT_SCALE * span.bassPeakSum / max(1L, span.columns)
            },
            TINY_LOUD_HEIGHT,
            BlueWaveform.MAX_TINY_HEIGHT,
        )
        return BlueWaveform(
            detail = detail.toByteArray(),
            preview = ByteArray(BlueWaveform.PREVIEW_COLUMNS) {
                val span = previewSpans[it]
                BlueWaveform.packColumn(
                    previewHeights[it],
                    whiteness(span.highEnergy, span.energy, MAX_PREVIEW_WHITENESS),
                )
            },
            tinyPreview = ByteArray(BlueWaveform.TINY_PREVIEW_COLUMNS) { tinyHeights[it].toByte() },
        )
    }

    /**
     * Rounds [heights] to whole heights at most [maximum], first scaling them all down by one factor when the loud
     * columns (the 90th percentile) stand above [loudHeight]; quieter tracks keep their absolute heights.
     */
    private fun levelled(heights: List<Double>, loudHeight: Double, maximum: Int): List<Int> {
        val loud = heights.sorted()[(heights.size - 1) * LOUD_PERCENTILE / 100]
        val gain = if (loud > loudHeight) loudHeight / loud else 1.0
        return heights.map { min(maximum, (gain * it).roundToInt()) }
    }

    private fun closeColumn() {
        val height = min(BlueWaveform.MAX_HEIGHT, floor(DETAIL_HEIGHT_SCALE * peak * peak).toInt())
        detail.add(BlueWaveform.packColumn(height, whiteness(highEnergy, energy, BlueWaveform.MAX_WHITENESS)))
        bins.add(boostedPeak, bassPeak, energy, highEnergy)
        columns++
        columnEnd = BlueWaveform.detailColumnStartFrame(columns + 1, sampleRateHz)
        columnFrames = 0
        peak = 0.0
        energy = 0.0
        highEnergy = 0.0
        boostedPeak = 0.0
        bassPeak = 0.0
        highPass.flushDenormals()
        bassBand.flushDenormals()
    }

    private fun whiteness(highEnergy: Double, energy: Double, maximum: Int): Int {
        if (energy <= 0.0) return 0
        val share = min(1.0, highEnergy / energy)
        return min(maximum, (BlueWaveform.MAX_WHITENESS * share).roundToInt())
    }

    /** RBJ audio-EQ-cookbook biquad, direct form I, in doubles. */
    private class Biquad(
        private val b0: Double,
        private val b1: Double,
        private val b2: Double,
        private val a1: Double,
        private val a2: Double,
    ) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun filter(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }

        /** Zeroes a decayed state (after silence), so the filter never runs on slow subnormal numbers. */
        fun flushDenormals() {
            if (abs(x1) < TINY && abs(x2) < TINY && abs(y1) < TINY && abs(y2) < TINY) {
                x1 = 0.0
                x2 = 0.0
                y1 = 0.0
                y2 = 0.0
            }
        }

        companion object {
            private const val TINY = 1e-25

            fun highPass(frequencyHz: Double, q: Double, sampleRateHz: Int): Biquad {
                val (c, alpha) = design(frequencyHz, q, sampleRateHz)
                return normalised((1 + c) / 2, -(1 + c), (1 + c) / 2, alpha, c)
            }

            /** Band-pass with 0 dB gain at the centre. */
            fun bandPass(frequencyHz: Double, q: Double, sampleRateHz: Int): Biquad {
                val (c, alpha) = design(frequencyHz, q, sampleRateHz)
                return normalised(alpha, 0.0, -alpha, alpha, c)
            }

            private fun design(frequencyHz: Double, q: Double, sampleRateHz: Int): Pair<Double, Double> {
                val w0 = 2 * PI * frequencyHz / sampleRateHz
                return cos(w0) to sin(w0) / (2 * q)
            }

            private fun normalised(b0: Double, b1: Double, b2: Double, alpha: Double, c: Double): Biquad {
                val a0 = 1 + alpha
                return Biquad(b0 / a0, b1 / a0, b2 / a0, -2 * c / a0, (1 - alpha) / a0)
            }
        }
    }

    /**
     * Per-column preview figures, at most [capacity] bins of [columnsPerBin] consecutive columns each (the last bin may
     * hold fewer). When the bins run out, neighbours merge pairwise and [columnsPerBin] doubles, which depends only on
     * the number of columns, so the result is deterministic.
     */
    private class PreviewBins(private val capacity: Int) {
        private val boostedPeakSums = DoubleArray(capacity)
        private val bassPeakSums = DoubleArray(capacity)
        private val energies = DoubleArray(capacity)
        private val highEnergies = DoubleArray(capacity)
        private val columnCounts = LongArray(capacity)
        private var size = 0
        private var columnsPerBin = 1L

        fun add(boostedPeak: Double, bassPeak: Double, energy: Double, highEnergy: Double) {
            if (size == 0 || columnCounts[size - 1] == columnsPerBin) {
                if (size == capacity) mergePairs()
                size++
            }
            val bin = size - 1
            boostedPeakSums[bin] += boostedPeak
            bassPeakSums[bin] += bassPeak
            energies[bin] += energy
            highEnergies[bin] += highEnergy
            columnCounts[bin]++
        }

        private fun mergePairs() {
            for (bin in 0 until capacity / 2) {
                val a = 2 * bin
                val b = a + 1
                boostedPeakSums[bin] = boostedPeakSums[a] + boostedPeakSums[b]
                bassPeakSums[bin] = bassPeakSums[a] + bassPeakSums[b]
                energies[bin] = energies[a] + energies[b]
                highEnergies[bin] = highEnergies[a] + highEnergies[b]
                columnCounts[bin] = columnCounts[a] + columnCounts[b]
            }
            for (bin in capacity / 2 until capacity) {
                boostedPeakSums[bin] = 0.0
                bassPeakSums[bin] = 0.0
                energies[bin] = 0.0
                highEnergies[bin] = 0.0
                columnCounts[bin] = 0
            }
            size = capacity / 2
            columnsPerBin *= 2
        }

        /**
         * The figures of preview column [index] of [count] over [totalColumns] detail columns: the bins holding detail
         * columns `[index * total / count, (index + 1) * total / count)`, at least one column (shorter tracks repeat
         * columns). A bin that straddles two spans counts in both.
         */
        fun span(index: Int, count: Int, totalColumns: Int): Span {
            if (totalColumns == 0) return Span(0.0, 0.0, 0.0, 0.0, 0)
            val first = index.toLong() * totalColumns / count
            val end = max(first + 1, (index + 1).toLong() * totalColumns / count)
            var boostedPeakSum = 0.0
            var bassPeakSum = 0.0
            var energy = 0.0
            var highEnergy = 0.0
            var columns = 0L
            for (bin in (first / columnsPerBin).toInt()..((end - 1) / columnsPerBin).toInt()) {
                boostedPeakSum += boostedPeakSums[bin]
                bassPeakSum += bassPeakSums[bin]
                energy += energies[bin]
                highEnergy += highEnergies[bin]
                columns += columnCounts[bin]
            }
            return Span(boostedPeakSum, bassPeakSum, energy, highEnergy, columns)
        }

        class Span(
            val boostedPeakSum: Double,
            val bassPeakSum: Double,
            val energy: Double,
            val highEnergy: Double,
            val columns: Long,
        )
    }

    /** A growable byte array (the detail grows by one byte per column). */
    private class ByteBuffer {
        private var bytes = ByteArray(INITIAL_CAPACITY)
        private var size = 0

        fun add(value: Byte) {
            if (size == bytes.size) bytes = bytes.copyOf(bytes.size * 2)
            bytes[size++] = value
        }

        fun toByteArray(): ByteArray = bytes.copyOf(size)

        private companion object {
            const val INITIAL_CAPACITY = 16_384 // ~110 s of detail
        }
    }

    companion object {
        /** The analyser's sample rate range (8..192 kHz). */
        const val MIN_SAMPLE_RATE_HZ: Int = 8_000
        const val MAX_SAMPLE_RATE_HZ: Int = 192_000

        /** Bounds the per-column preview memory: 8192 bins of five numbers (about 320 KB). */
        const val PREVIEW_BINS: Int = 8_192

        private const val BUTTERWORTH_Q = 0.7071067811865476
        private const val WHITENESS_CROSSOVER_HZ = 200.0
        private const val BASS_CENTRE_HZ = 150.0
        private const val BASS_Q = 0.9
        private const val BASS_BOOST = 1.5

        /** Full-scale peak -> 31, -6 dB -> 7, as rekordbox's detail waveform. */
        private const val DETAIL_HEIGHT_SCALE = 31.9

        /** Full-scale treble -> 17, -6 dB -> 9; rekordbox's previews never exceeded 25 (24 in its demo exports). */
        private const val PREVIEW_HEIGHT_SCALE = 17.4
        private const val MAX_PREVIEW_HEIGHT = 24
        private const val MAX_PREVIEW_WHITENESS = 5

        /** -6 dB at the bass centre -> 15. */
        private const val TINY_HEIGHT_SCALE = 30.0

        /** The loud columns of a track: its 90th percentile column, levelled to these heights at most. */
        private const val LOUD_PERCENTILE = 90
        private const val PREVIEW_LOUD_HEIGHT = 22.0
        private const val TINY_LOUD_HEIGHT = 14.0
    }
}
