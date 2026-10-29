package io.sonora.feature.settings.playback

import io.sonora.core.designsystem.text.Strings
import io.sonora.core.domain.playback.ReplayGainMode
import io.sonora.feature.settings.R
import kotlin.math.abs

/** User-facing copy of the Playback settings from string resources, kept pure so it is unit-tested. */
object PlaybackSettingsText {
    fun modeTitle(mode: ReplayGainMode, strings: Strings): String = strings.get(
        when (mode) {
            ReplayGainMode.OFF -> R.string.playback_normalisation_off
            ReplayGainMode.TRACK -> R.string.playback_normalisation_track
            ReplayGainMode.ALBUM -> R.string.playback_normalisation_album
            ReplayGainMode.AUTO -> R.string.playback_normalisation_auto
        },
    )

    fun modeSubtitle(mode: ReplayGainMode, strings: Strings): String = strings.get(
        when (mode) {
            ReplayGainMode.OFF -> R.string.playback_normalisation_off_subtitle
            ReplayGainMode.TRACK -> R.string.playback_normalisation_track_subtitle
            ReplayGainMode.ALBUM -> R.string.playback_normalisation_album_subtitle
            ReplayGainMode.AUTO -> R.string.playback_normalisation_auto_subtitle
        },
    )

    /** "+1.5 dB", "0 dB", "-6 dB": signed, with a decimal only when there is one, in the language's number format. */
    fun preamp(db: Float, strings: Strings): String {
        val number = when {
            db == 0f -> "0"
            abs(db % 1f) < 0.05f -> String.format(strings.locale, "%+.0f", db)
            else -> String.format(strings.locale, "%+.1f", db)
        }
        return strings.get(R.string.playback_preamp_value, number)
    }
}

/** The crossfade length as the slider reads it. */
internal object CrossfadeText {
    fun label(seconds: Int, strings: Strings): String =
        if (seconds <= 0) strings.get(R.string.crossfade_off) else strings.get(R.string.crossfade_seconds, seconds)
}
