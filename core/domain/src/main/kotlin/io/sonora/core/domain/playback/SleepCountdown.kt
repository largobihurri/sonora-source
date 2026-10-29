package io.sonora.core.domain.playback

import kotlin.math.PI
import kotlin.math.sin

/** A queue entry as the sleep timer sees it: its album (null without one) and its length, when known. */
data class SleepEntry(val albumKey: String?, val durationMs: Long?)

/** Where playback stands: the queue, the entry playing and the position in it. */
data class SleepPlayhead(val entries: List<SleepEntry>, val currentIndex: Int, val positionMs: Long)

/** How long a sleep timer has left, read against a clock (`nowMs`, monotonic) and the playhead. */
class SleepCountdown private constructor(
    val option: SleepTimerOption,
    /** When a clock timer runs out; null while it waits for the playback instead. */
    private val deadlineMs: Long?,
) {
    /**
     * What is left: on the clock for a clock timer (never below zero); otherwise the rest of the song playing, plus,
     * for an album, the songs of its run after it. Null when a song involved has no known length.
     */
    fun remainingMs(nowMs: Long, playhead: SleepPlayhead): Long? {
        if (deadlineMs != null) return (deadlineMs - nowMs).coerceAtLeast(0L)
        val entries = playhead.entries
        val current = entries.getOrNull(playhead.currentIndex) ?: return null
        val rest = (current.durationMs ?: return null) - playhead.positionMs
        var remaining = rest.coerceAtLeast(0L)
        if (option == SleepTimerOption.EndOfAlbum) {
            for (index in playhead.currentIndex + 1..lastOfAlbumRun(playhead)) {
                remaining += entries[index].durationMs ?: return null
            }
        }
        return remaining
    }

    /** Whether a clock timer has run out. A song or album timer never does: playback tells when it ends. */
    fun isDue(nowMs: Long): Boolean = deadlineMs != null && nowMs >= deadlineMs

    /** Whether playback should pause at the end of the entry playing: it is where the song or album timer ends. */
    fun pausesAtEndOfCurrent(playhead: SleepPlayhead): Boolean = when {
        deadlineMs != null -> false
        option == SleepTimerOption.EndOfAlbum -> lastOfAlbumRun(playhead) == playhead.currentIndex
        else -> true
    }

    /** This timer with [minutes] more; a song or album timer becomes a clock timer ending that much after it would. */
    fun extendedBy(minutes: Int, nowMs: Long, playhead: SleepPlayhead): SleepCountdown {
        val end = deadlineMs ?: (nowMs + (remainingMs(nowMs, playhead) ?: 0L))
        return SleepCountdown(option, end + minutes * MINUTE_MS)
    }

    /** The index of the last entry, from the one playing on, of the album playing's unbroken run in the queue. */
    private fun lastOfAlbumRun(playhead: SleepPlayhead): Int {
        val entries = playhead.entries
        val album = entries.getOrNull(playhead.currentIndex)?.albumKey ?: return playhead.currentIndex
        var last = playhead.currentIndex
        while (entries.getOrNull(last + 1)?.albumKey == album) last++
        return last
    }

    companion object {
        private const val MINUTE_MS = 60_000L

        fun start(option: SleepTimerOption, nowMs: Long): SleepCountdown = SleepCountdown(
            option,
            deadlineMs = (option as? SleepTimerOption.Minutes)?.let { nowMs + it.minutes * MINUTE_MS },
        )
    }
}

/** The volume curve of a sleep timer's end. */
object SleepFade {
    /** The fade's length: the timer's last ten seconds. */
    const val DURATION_MS = 10_000L

    /**
     * The volume factor with [remainingMs] left: 1 until the fade, then a raised-cosine fall (`sin²`) to 0, so it
     * starts and ends without a step and halves at mid-fade. Unknown time left keeps full volume.
     */
    fun gain(remainingMs: Long?): Float {
        if (remainingMs == null || remainingMs >= DURATION_MS) return 1f
        if (remainingMs <= 0L) return 0f
        val s = sin(PI / 2 * remainingMs / DURATION_MS)
        return (s * s).toFloat()
    }
}

/** The custom duration's choices: [MIN_MINUTES] to [MAX_MINUTES] in steps of [STEP_MINUTES]. */
object SleepCustomDuration {
    const val MIN_MINUTES = 5
    const val MAX_MINUTES = 12 * 60
    const val STEP_MINUTES = 5
    const val DEFAULT_MINUTES = 20

    /** [minutes] moved [steps] steps up (or down), onto the step grid and within the bounds. */
    fun step(minutes: Int, steps: Int): Int {
        val onGrid = if (steps > 0) minutes / STEP_MINUTES * STEP_MINUTES else (minutes + STEP_MINUTES - 1) / STEP_MINUTES * STEP_MINUTES
        return (onGrid + steps * STEP_MINUTES).coerceIn(MIN_MINUTES, MAX_MINUTES)
    }
}
