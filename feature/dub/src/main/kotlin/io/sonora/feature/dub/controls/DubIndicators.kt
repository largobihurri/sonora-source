package io.sonora.feature.dub.controls

import androidx.annotation.StringRes
import io.sonora.feature.dub.R
import kotlin.math.floor
import kotlin.math.log10

/** The pure readings behind the DUB screen's indicators: the peak meter, the limiter LED and the four beat LEDs. */
object DubIndicators {
    /** The bottom of the peak meter; quieter peaks leave it empty. */
    const val METER_FLOOR_DB = -48.0

    /** The limiter LED lights above this much gain reduction (design §2, "Output protection"). */
    const val LIMITER_LED_DB = 1f

    /** How full the peak meter is for a linear [peak] (1 = full scale): linear in dB from the floor; unusable is 0. */
    fun peakFraction(peak: Float): Float {
        if (!peak.isFinite() || peak <= 0f) return 0f
        val db = 20.0 * log10(peak.toDouble())
        return ((db - METER_FLOOR_DB) / -METER_FLOOR_DB).toFloat().coerceIn(0f, 1f)
    }

    fun limiterLit(gainReductionDb: Float): Boolean = gainReductionDb > LIMITER_LED_DB

    /** Which of the four beat LEDs is lit at [beats] since the bar's first beat (any bar); null without a beat. */
    fun beatLed(beats: Double?): Int? {
        if (beats == null || !beats.isFinite()) return null
        return Math.floorMod(floor(beats).toLong(), BEATS_PER_BAR.toLong()).toInt()
    }

    const val BEATS_PER_BAR = 4
}

/** Why the DUB stage passes the audio through untouched (design §2, "Bypass states"); [message] is the banner's text. */
enum class DubBypassReason(@StringRes val message: Int) {
    BIT_PERFECT(R.string.dub_bypass_bit_perfect),
    REMOTE_RECEIVER(R.string.dub_bypass_remote),
    TOO_MANY_CHANNELS(R.string.dub_bypass_channels),
    UNSUPPORTED_FORMAT(R.string.dub_bypass_format),
}
