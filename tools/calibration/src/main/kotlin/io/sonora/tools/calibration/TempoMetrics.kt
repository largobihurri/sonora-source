package io.sonora.tools.calibration

import kotlin.math.abs

/**
 * How our tempo relates to the reference tempo, in the classes of the MIREX tempo evaluation (Gouyon et al., "An
 * experimental comparison of audio tempo induction algorithms", IEEE Trans. Audio, Speech, Lang. Process. 14(5), 2006):
 * accuracy 1 accepts only [EXACT]; accuracy 2 also accepts the metrical-level errors. The MIREX family also contains
 * x3 and /3, which a DJ track analysis does not produce in practice; they fall into [OTHER] here.
 */
enum class OctaveClass(val factor: Double) {
    /** Within the tolerance of the reference. */
    EXACT(1.0),

    /** We found twice the reference tempo. */
    DOUBLE(2.0),

    /** We found half the reference tempo. */
    HALF(0.5),

    /** We found three halves of the reference tempo. */
    THREE_HALVES(1.5),

    /** We found two thirds of the reference tempo. */
    TWO_THIRDS(2.0 / 3.0),

    /** None of the above. */
    OTHER(Double.NaN),
    ;

    /** MIREX accuracy 1: the tempo is right. */
    val isAcc1: Boolean get() = this == EXACT

    /** MIREX accuracy 2: the tempo is right or wrong by a simple metrical ratio. */
    val isAcc2: Boolean get() = this != OTHER
}

/** Tempo error measures between our BPM and the reference BPM (for example from the rekordbox export). */
object TempoMetrics {

    /** The MIREX tempo tolerance: 4 percent. */
    const val DEFAULT_TOLERANCE: Double = 0.04

    private const val EPSILON = 1e-9

    /** `|ours - reference|` in BPM. */
    fun absoluteError(ours: Double, reference: Double): Double = abs(ours - reference)

    /** `|ours - reference| / reference`; the [reference] must be positive. */
    fun relativeError(ours: Double, reference: Double): Double {
        require(reference > 0) { "reference tempo must be positive: $reference" }
        return abs(ours - reference) / reference
    }

    /**
     * Classifies [ours] against [reference]: [OctaveClass.EXACT] when within [tolerance] (a fraction) of the
     * reference, otherwise the first of x2, /2, x3/2 and x2/3 whose target tempo [ours] is within [tolerance] of,
     * otherwise [OctaveClass.OTHER]. The tolerance is relative to the target tempo of each class, as MIREX applies it
     * to the metrically related tempo. At 4 percent the classes cannot overlap, so the order does not matter.
     */
    fun classifyOctave(ours: Double, reference: Double, tolerance: Double = DEFAULT_TOLERANCE): OctaveClass {
        require(reference > 0) { "reference tempo must be positive: $reference" }
        require(tolerance >= 0) { "tolerance must not be negative: $tolerance" }
        for (candidate in OctaveClass.entries) {
            if (candidate == OctaveClass.OTHER) continue
            val target = reference * candidate.factor
            if (abs(ours - target) <= tolerance * target + EPSILON) return candidate
        }
        return OctaveClass.OTHER
    }

    /** How many tracks fell into each class; every class is present, in declaration order, even with a zero count. */
    fun histogram(classes: Iterable<OctaveClass>): Map<OctaveClass, Int> {
        val counts = LinkedHashMap<OctaveClass, Int>()
        for (c in OctaveClass.entries) counts[c] = 0
        for (c in classes) counts[c] = counts.getValue(c) + 1
        return counts
    }
}
