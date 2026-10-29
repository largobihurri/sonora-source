package io.sonora.core.djengine.key

/**
 * A pair of key profiles (tonality models): the relative weight of each of the 12 scale degrees, from the tonic
 * (degree 0) up in semitones, for a major and for a minor key. Only the shape matters (the classifier correlates, so
 * offset and scale are irrelevant), but [EDMA] and [TEMPERLEY] are published normalised to a sum of 1.
 *
 * [id] is a short lower-case tag that goes into `KeyEstimate.method` (`hpcp-<id>/1`).
 */
class KeyProfile(val id: String, val name: String, major: DoubleArray, minor: DoubleArray) {
    private val majorWeights: DoubleArray = major.copyOf()
    private val minorWeights: DoubleArray = minor.copyOf()

    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(majorWeights.size == DEGREES && minorWeights.size == DEGREES) { "a profile has $DEGREES weights per mode" }
        require((majorWeights + minorWeights).all { it.isFinite() }) { "weights must be finite" }
    }

    /** The weight of [degree] (0 = tonic .. 11 = major seventh) in a major or ([minor]) minor key. */
    fun weight(minor: Boolean, degree: Int): Double {
        require(degree in 0 until DEGREES) { "degree $degree of $DEGREES" }
        return if (minor) minorWeights[degree] else majorWeights[degree]
    }

    override fun toString(): String = "KeyProfile($id)"

    companion object {
        const val DEGREES: Int = 12

        /**
         * `edma`: the major and minor profiles Faraldo, Gomez, Jorda and Herrera extracted from an EDM corpus (the median
         * of the averaged chromagrams of the training tracks), "Key Estimation in Electronic Dance Music", ECIR 2016,
         * LNCS 9626, pp. 335-347, Fig. 3 (solid grey line, sum of each vector normalised to 1).
         *
         * UNVERIFIED NUMBERS (TODO for the calibration harness, D1.19/D1.20): the paper prints these profiles only as a
         * chart, not as a table. The values below were read off that chart (about +-0.003 per degree); both vectors
         * sum to 1.00 within 0.005, as the paper says they should. Replace them with exact values if the authors'
         * numbers are obtained under a compatible licence, or re-fit them on the calibration set.
         */
        val EDMA: KeyProfile = KeyProfile(
            id = "edma",
            name = "EDMA (Faraldo et al. 2016, Fig. 3, read from the chart)",
            major = doubleArrayOf(0.166, 0.047, 0.083, 0.067, 0.100, 0.093, 0.053, 0.132, 0.052, 0.075, 0.070, 0.065),
            minor = doubleArrayOf(0.172, 0.054, 0.076, 0.101, 0.056, 0.085, 0.050, 0.135, 0.074, 0.050, 0.092, 0.055),
        )

        /**
         * Temperley's corpus-based profiles of "The Cognition of Basic Musical Structures" (MIT Press, 2001, the 1999
         * set), as integers, which is also the "temperley" curve of Faraldo et al. Fig. 3 once divided by 38.5 (5/38.5
         * = 0.130 on the tonic, 1.5/38.5 = 0.039 on the minor-seventh degree of the major profile). This is not the
         * 2005 Bayesian (Kostka-Payne) set: those numbers could not be verified here and are not included.
         */
        val TEMPERLEY: KeyProfile = KeyProfile(
            id = "temperley",
            name = "Temperley (CBMS, 2001)",
            major = doubleArrayOf(5.0, 2.0, 3.5, 2.0, 4.5, 4.0, 2.0, 4.5, 2.0, 3.5, 1.5, 4.0),
            minor = doubleArrayOf(5.0, 2.0, 3.5, 4.5, 2.0, 4.0, 2.0, 4.5, 3.5, 2.0, 1.5, 4.0),
        )

        /** The probe-tone profiles of Krumhansl and Kessler, "Tracing the dynamic changes in perceived tonal organization", Psychological Review 89 (1982). */
        val KRUMHANSL_KESSLER: KeyProfile = KeyProfile(
            id = "krumhansl",
            name = "Krumhansl-Kessler (1982)",
            major = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88),
            minor = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17),
        )
    }
}
