package io.sonora.feature.settings.duplicates

import kotlin.math.floor

/**
 * The size of the duplicates comparison's field labels ("Profundidad de bits", "Frecuencia de muestreo"), which share
 * one narrow column: one size for every label, the largest at which each wraps only between words without being cut.
 * Pure; the same rule as the selection title's (`feature/library/.../ui/SongSelection.kt`, `wordSafeSize`).
 */
internal object ComparisonLabels {

    /** How a label (or the whole column, [worst]) lays out at one size. */
    enum class Fit {
        /** Wider or taller than its room, or ellipsized. */
        Cut,

        /** Not cut, but a line starts inside a word. */
        SplitsWord,

        /** Not cut, and wrapped only between words. */
        Fits,
    }

    /**
     * Whether [text] laid out with lines starting at [lineStarts] wraps only between words: every line after the first
     * starts right after a space or a hyphen. A word wider than the line is split anywhere by the line breaker (no
     * hyphen drawn), so this is checked on the layout.
     */
    fun wrapsBetweenWords(text: CharSequence, lineStarts: List<Int>): Boolean = lineStarts.all { start ->
        start <= 0 || start > text.length || text[start - 1].isWhitespace() || text[start - 1] == '-'
    }

    /** The column's fit: its worst label's. */
    fun worst(fits: List<Fit>): Fit = fits.minByOrNull { it.ordinal } ?: Fit.Fits

    /**
     * The largest size from [max] down to [min] in [step]s (positive) that [Fit.Fits]; when a word is too wide at
     * every size (a large font scale), the largest that is at least not cut; [min] when every size is cut. One [fit]
     * per size.
     */
    fun fontSize(max: Float, min: Float, step: Float, fit: (Float) -> Fit): Float {
        require(step > 0f) { "Step $step must be positive" }
        var largestUncut: Float? = null
        val steps = floor((max - min) / step + STEP_EPSILON).toInt()
        for (i in 0..steps) {
            val size = max - i * step
            when (fit(size)) {
                Fit.Fits -> return size
                Fit.SplitsWord -> if (largestUncut == null) largestUncut = size
                Fit.Cut -> Unit
            }
        }
        return largestUncut ?: min
    }

    private const val STEP_EPSILON = 1e-4f
}
