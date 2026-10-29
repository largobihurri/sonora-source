package io.sonora.feature.dub.controls

import kotlin.math.log10
import kotlin.math.pow

/**
 * The law of a preamp way's level fader (design §10.1): a 0..1 position from minus infinity at the bottom to +6 dB at
 * the top, with a sticky 0 dB detent at [UNITY_POSITION]. Below the detent the gain is the cube of the position's part
 * of the way to it (so half way is about -18 dB and the bottom is a true silence with no jump); above it the level
 * rises linearly in decibels to [MAX_DB]. The drag uses [axis] through [DetentDrag].
 */
object FaderMapping {
    const val UNITY_POSITION = 0.75
    const val MAX_DB = 6.0

    /** Half the width of the 0 dB detent, in position units. */
    const val DETENT_ZONE = 0.03

    /** dB per decade of the position below the detent: a gain that is the cube of the position. */
    private const val LOWER_DB_PER_DECADE = 60.0

    val axis = DetentAxis(UNITY_POSITION, DETENT_ZONE)

    /** The level at [position], in dB; minus infinity at the bottom. */
    fun dbOf(position: Double): Double {
        val p = if (position.isNaN()) UNITY_POSITION else position.coerceIn(0.0, 1.0)
        return when {
            p == UNITY_POSITION -> 0.0
            p <= 0.0 -> Double.NEGATIVE_INFINITY
            p < UNITY_POSITION -> LOWER_DB_PER_DECADE * log10(p / UNITY_POSITION)
            else -> MAX_DB * (p - UNITY_POSITION) / (1.0 - UNITY_POSITION)
        }
    }

    /** The linear gain at [position]: exactly 1 at the detent, exactly 0 at the bottom. */
    fun gainOf(position: Double): Double {
        val db = dbOf(position)
        return when {
            db == 0.0 -> 1.0
            db == Double.NEGATIVE_INFINITY -> 0.0
            else -> 10.0.pow(db / 20.0)
        }
    }

    /** The position showing [db]; NaN reads as unity, minus infinity as the bottom, anything above +6 dB as the top. */
    fun positionOf(db: Double): Double = when {
        db.isNaN() || db == 0.0 -> UNITY_POSITION
        db == Double.NEGATIVE_INFINITY -> 0.0
        db >= MAX_DB -> 1.0
        db < 0.0 -> UNITY_POSITION * 10.0.pow(db / LOWER_DB_PER_DECADE)
        else -> UNITY_POSITION + db / MAX_DB * (1.0 - UNITY_POSITION)
    }
}
