package io.sonora.feature.nowplaying

import io.sonora.core.designsystem.text.LocaleFormats
import io.sonora.core.designsystem.text.Strings
import io.sonora.core.domain.playback.SleepTimerOption

/** The sleep timer's words: its countdown and its options' names. */
internal object SleepTimerText {
    /** Dashes for a time left the timer cannot know yet (a song with no known length). */
    private const val UNKNOWN = "–:––"

    /** [remainingMs] as `m:ss`, or `h:mm:ss` from an hour; a part second counts as a whole one, as on iOS. */
    fun countdown(remainingMs: Long?): String {
        if (remainingMs == null) return UNKNOWN
        return LocaleFormats.duration((remainingMs.coerceAtLeast(0L) + 999) / 1_000 * 1_000)
    }

    fun option(option: SleepTimerOption, strings: Strings): String = when (option) {
        is SleepTimerOption.Minutes -> strings.plural(R.plurals.sleep_timer_minutes, option.minutes, option.minutes)
        SleepTimerOption.EndOfSong -> strings.get(R.string.sleep_timer_end_of_song)
        SleepTimerOption.EndOfAlbum -> strings.get(R.string.sleep_timer_end_of_album)
    }
}
