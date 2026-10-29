package io.sonora.feature.dj

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/** The numbers the beat grid editor shows, in the user's locale; pure, so the digits are tested without a device. */
object GridEditText {

    /** Shown for a tempo that is not known. */
    const val NO_VALUE: String = "—"

    private const val PLUS = "+"
    private const val MINUS = "−"
    private const val HUNDREDTHS_PER_BPM = 100

    /** [bpm] with two decimals ("128.00", "128,00" in Spanish), or [NO_VALUE] without one. */
    fun bpm(bpm: Double?, locale: Locale): String = bpm?.let { String.format(locale, "%.2f", it) } ?: NO_VALUE

    /** A tempo step button's label: "+0.01", "−0.01", "+1", "−1" ([deltaHundredths] is the signed step). */
    fun bpmStepLabel(deltaHundredths: Int, locale: Locale): String =
        (if (deltaHundredths < 0) MINUS else PLUS) + bpmStep(deltaHundredths, locale)

    /** A tempo step without sign for a spoken description ("0.01", "1"). */
    fun bpmStep(deltaHundredths: Int, locale: Locale): String {
        val size = abs(deltaHundredths)
        return if (size % HUNDREDTHS_PER_BPM == 0) {
            NumberFormat.getIntegerInstance(locale).format(size / HUNDREDTHS_PER_BPM)
        } else {
            String.format(locale, "%.2f", size / HUNDREDTHS_PER_BPM.toDouble())
        }
    }

    /** A nudge step without sign for a spoken description ("10"). */
    fun nudgeStep(deltaMs: Int, locale: Locale): String = NumberFormat.getIntegerInstance(locale).format(abs(deltaMs))

    /** A grid nudge button's label: "+10 ms", "−1 ms" ([deltaMs] is the signed step). */
    fun nudgeLabel(deltaMs: Int, unit: String, locale: Locale): String =
        (if (deltaMs < 0) MINUS else PLUS) + nudgeStep(deltaMs, locale) + " " + unit
}
