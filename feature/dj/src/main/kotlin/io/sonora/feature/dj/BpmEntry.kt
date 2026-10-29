package io.sonora.feature.dj

import io.sonora.core.djmodel.GridEdit

/**
 * Reading the tempo the user types into the beat grid editor: a plain decimal number of BPM with at most two decimals,
 * inside the range a [GridEdit] can hold. Pure, so every way to get it wrong is tested without a keyboard.
 *
 * Both "." and "," are decimal separators whatever the locale (the number pad of a Spanish phone offers one and a paste
 * brings the other). Nothing else is: no sign, no exponent, no grouping, no unit. More than two decimals is rejected
 * and never rounded, so what the user typed is exactly what is applied.
 */
object BpmEntry {

    /** The slowest tempo that is accepted, in hundredths of a BPM: the one of a [GridEdit]. */
    const val MIN_HUNDREDTHS: Int = GridEdit.MIN_BPM_HUNDREDTHS

    /** The fastest tempo that is accepted, in hundredths of a BPM: the one of a [GridEdit]. */
    const val MAX_HUNDREDTHS: Int = GridEdit.MAX_BPM_HUNDREDTHS

    private const val RADIX = 10
    private const val DECIMALS = 2
    private const val HUNDRED = 100L

    /** Why a text is not a tempo. */
    enum class Problem {
        /** Nothing was typed. */
        Empty,

        /** Not a plain decimal number. */
        NotANumber,

        /** More than two decimals. */
        TooManyDecimals,

        /** A number, but a tempo no grid edit can hold. */
        OutOfRange,
    }

    /** The outcome of [parse]. */
    sealed interface Result {
        /** The tempo, in hundredths of a BPM, ready for [GridEditCommand.SetBpm]. */
        data class Valid(val bpmHundredths: Int) : Result

        data class Invalid(val problem: Problem) : Result
    }

    /** Reads [text] as a tempo. */
    fun parse(text: String): Result {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Result.Invalid(Problem.Empty)
        val separator = trimmed.indexOfFirst { it == '.' || it == ',' }
        val whole = if (separator < 0) trimmed else trimmed.substring(0, separator)
        val fraction = if (separator < 0) "" else trimmed.substring(separator + 1)
        if (whole.isEmpty() || !whole.all(::isDigit) || !fraction.all(::isDigit)) return Result.Invalid(Problem.NotANumber)
        if (fraction.length > DECIMALS) return Result.Invalid(Problem.TooManyDecimals)
        var hundredths = 0L
        for (digit in whole) {
            hundredths = hundredths * RADIX + Character.digit(digit, RADIX)
            // Far past the largest tempo already: stop before a long run of digits can overflow.
            if (hundredths * HUNDRED > MAX_HUNDREDTHS) return Result.Invalid(Problem.OutOfRange)
        }
        hundredths *= HUNDRED
        // "5" after the separator is 50 hundredths, "05" is 5.
        var scale = HUNDRED / RADIX
        for (digit in fraction) {
            hundredths += Character.digit(digit, RADIX) * scale
            scale /= RADIX
        }
        return if (hundredths in MIN_HUNDREDTHS..MAX_HUNDREDTHS) Result.Valid(hundredths.toInt()) else Result.Invalid(Problem.OutOfRange)
    }

    private fun isDigit(char: Char): Boolean = Character.digit(char, RADIX) >= 0
}
