package io.sonora.core.playback

import kotlin.math.roundToInt

/** Pure conversions between a continuous 0..1 slider fraction and discrete stream volume steps. */
object VolumeMapping {

    fun stepToFraction(step: Int, min: Int, max: Int): Float {
        if (max <= min) return 0f
        return ((step - min).toFloat() / (max - min)).coerceIn(0f, 1f)
    }

    fun fractionToStep(fraction: Float, min: Int, max: Int): Int {
        if (max <= min) return min
        val f = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
        return (min + f * (max - min)).roundToInt().coerceIn(min, max)
    }
}
