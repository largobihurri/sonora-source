package io.sonora.feature.nowplaying

import io.sonora.core.domain.playback.PlaybackTempo
import java.text.NumberFormat
import java.util.Locale

/** How speed and pitch read in Now Playing: "1.25×", "+2", and the badge's "1.25× · +2". */
internal object PlaybackTempoText {

    /** The speed with up to two decimals in [locale]'s style and a times sign: "1.25×", "1×". */
    fun speed(tempo: PlaybackTempo, locale: Locale): String {
        val number = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 2
        }
        return number.format(tempo.speed.toDouble()) + "×"
    }

    /** Semitones signed: "+2", "−3" (a true minus sign), "0". */
    fun semitones(semitones: Int): String = when {
        semitones > 0 -> "+$semitones"
        semitones < 0 -> "−${-semitones}"
        else -> "0"
    }

    /** The badge: the speed, and the pitch change when there is one. */
    fun badge(tempo: PlaybackTempo, locale: Locale): String =
        if (tempo.semitones == 0) speed(tempo, locale) else "${speed(tempo, locale)} · ${semitones(tempo.semitones)}"
}
