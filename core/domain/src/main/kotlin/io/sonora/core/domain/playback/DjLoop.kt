package io.sonora.core.domain.playback

import kotlinx.coroutines.flow.StateFlow

/**
 * What the DJ screen shows of the loop, in the time of the playback clock ([ClockSample.positionMs]), which is also the
 * time the waveform and the beat lines are drawn in.
 *
 * - Nothing marked: [inMs] and [outMs] are `null`.
 * - **Loop In** pressed, no Out yet: only [inMs] is set (the audio keeps being captured from there).
 * - A loop closed and waiting for the audio to reach its Out: both set and [armed].
 * - The loop repeating: both set and [looping].
 * - Left with Exit (the audio carried on, no jump): both set, neither [armed] nor [looping]; [canReloop].
 *
 * @property gridInMs [inMs] on the beat grid's time line, which is the time saved loops (cues with `loopEndMs`) are kept in.
 *   It differs from [inMs] by the encoder delay the player has trimmed (up to a few tens of ms, see the playback
 *   module's `StreamTimeBase`); `null` when [inMs] is, and for a loop the player runs by seeking (too long to capture).
 * @property gridOutMs [outMs] on the grid's time line, `null` as [outMs] is.
 * @property available whether loops can run on the stream playing now. They cannot while bit-perfect output is armed
 *   (like the tempo, which it holds neutral), for a stream that is not plain PCM, when DJ mode is off, or with nothing
 *   playing: every command is then ignored.
 */
data class DjLoopState(
    val inMs: Double? = null,
    val outMs: Double? = null,
    val looping: Boolean = false,
    val armed: Boolean = false,
    val available: Boolean = false,
    val gridInMs: Double? = null,
    val gridOutMs: Double? = null,
) {
    /** Loop In is set and Out has not been: the next Loop Out closes the loop. */
    val markingIn: Boolean get() = inMs != null && outMs == null

    /** A loop is marked and not active: Reloop starts it again. */
    val canReloop: Boolean get() = inMs != null && outMs != null && !looping && !armed

    companion object {
        val NONE = DjLoopState()
    }
}

/**
 * The DJ loop, CDJ style, on the track playing. The commands take effect at the audio, sample accurate and without a
 * click, and resolve "now" at the audible frame, not at the moment of the call. They are ignored while [DjLoopState.available]
 * is false. Main thread.
 *
 * Loop In marks where the audio is heard; Loop Out closes the loop there and it repeats exactly `[In, Out)`. With
 * Quantize on (and a beat grid) both snap to the nearest beat. Exit never jumps: the audio carries on from where the
 * loop is. A seek or a change of song clears the loop.
 */
interface DjLoopController {
    val loop: StateFlow<DjLoopState>

    /** Loop In (a CDJ's IN button): marks the audible frame. Pressed while a loop is set, it marks a new In. */
    fun loopIn()

    /** Loop Out: closes the loop opened by [loopIn] at the audible frame and starts it. Does nothing without an In. */
    fun loopOut()

    /**
     * A loop of [beats] beats (1/32 to 32; see the loop sizes of `LoopSizes`) from the effective beat grid: starting at
     * the nearest beat (Quantize on) or the audible frame, or, while a loop is set, from its In. Needs a grid.
     */
    fun beatLoop(beats: Double)

    /** Half the loop, seamlessly while it plays (a CDJ's 1/2X). */
    fun halve()

    /** Twice the loop (a CDJ's 2X): the audio after Out plays and the loop wraps at the new Out. */
    fun double()

    /** Leaves the loop without a jump: playback carries on linearly from where it is. The loop is remembered. */
    fun exit()

    /** Starts the remembered loop again (RELOOP). */
    fun reloop()

    /** Forgets the loop and the In mark. */
    fun clear()

    /**
     * Engages the saved loop `[gridInMs, gridOutMs)`, given on the beat grid's time line ([DjLoopState.gridInMs]): the
     * loop a hot cue or memory cue with a loop end keeps. Loop points are used as given (no Quantize, no grid needed).
     * When the audio heard is not inside the loop the player jumps to its In first, so the loop captures and wraps by
     * itself; a loop that is set is replaced. Does nothing by default.
     */
    fun engage(gridInMs: Double, gridOutMs: Double) = Unit
}

/**
 * The loop being heard, for the playback clock: [PlaybackClock] implementations fill [ClockSample.looping] and the loop
 * bounds from here, so the smoother knows the position wraps.
 */
fun interface LoopClockSource {
    /** Writes the loop fields of [into]; allocation-free, any thread. */
    fun readInto(into: ClockSample)

    companion object {
        /** No loop, ever. */
        val NONE = LoopClockSource { it.looping = false }
    }
}
