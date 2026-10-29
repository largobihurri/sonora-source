package io.sonora.core.domain.playback

import kotlinx.coroutines.flow.StateFlow

/** When the sleep timer stops playback. */
sealed interface SleepTimerOption {
    /** After [minutes] on the clock, whether playback is paused meanwhile or not. */
    data class Minutes(val minutes: Int) : SleepTimerOption

    /** When the song playing ends (a skip moves the timer to the new song). */
    data object EndOfSong : SleepTimerOption

    /**
     * When the album playing ends: after the last song of the album's run in the queue (the songs of the same album
     * that follow the one playing). A song with no album stops at its own end.
     */
    data object EndOfAlbum : SleepTimerOption

    companion object {
        /** The fixed durations offered, in minutes. */
        val PRESET_MINUTES: List<Int> = listOf(15, 30, 45, 60, 90)
    }
}

/** A running sleep timer: what it waits for and how long is left ([remainingMs] is null while it cannot be known). */
data class SleepTimerState(val option: SleepTimerOption, val remainingMs: Long?)

/**
 * The sleep timer, run by the playback service so it keeps counting with the app in the background. Its last
 * [SleepFade.DURATION_MS] fade the music out; then playback pauses and the volume is restored for the next play.
 */
interface SleepTimer {
    /** The running timer, or null when none is set. Updated about once a second. */
    val state: StateFlow<SleepTimerState?>

    /** Starts the timer with [option], replacing any running one. */
    fun start(option: SleepTimerOption)

    /** Gives the running timer [minutes] more; a song or album timer becomes a clock timer ending that much later. */
    fun extend(minutes: Int)

    /** Stops the running timer, restoring the volume if it was fading. */
    fun cancel()
}
