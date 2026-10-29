package io.sonora.core.playback.crossfade

import io.sonora.core.domain.playback.FadeCurve

/**
 * The two players a crossfade plays on, as [CrossfadeCoordinator] drives them. The main player is the session's: the
 * queue, the notification and the play counts follow it alone. The tail player only ever plays the last seconds of
 * the song the main player has just left, fading out.
 */
internal interface CrossfadeDeck {
    /** Loads the main player's current entry into the tail player, paused and silent, at [positionMs]. */
    fun prepareTail(positionMs: Long)

    /** Whether the prepared tail can start playing at once. */
    val tailReady: Boolean

    /** Starts the prepared tail; the main player is at [mainPositionMs] now (the tail may catch up to it). */
    fun playTail(mainPositionMs: Long)

    /** Stops the tail player and lets go of its entry. */
    fun stopTail()

    /** The tail player's level, linear 0..1. */
    fun setTailGain(gain: Float)

    /** The main player's fade level, linear 0..1, on top of its own volume. */
    fun setMainGain(gain: Float)

    /**
     * Moves the main player on to its next entry, from the start. Returns whether the move is reported back as a change
     * of entry ([CrossfadeCoordinator.onEntryChanged]): not when there is no next entry, nor when the next entry is
     * the same song (a restated entry is no change of song).
     */
    fun advanceMain(): Boolean
}

/**
 * What [CrossfadeCoordinator] reads of the main player: its current entry, where it is in it and the entry's length
 * (ms), whether it means to play ([playing]: play requested and not suppressed, so buffering still counts as playing),
 * and the crossfade planned into the next entry ([fadeMs], 0 for a gapless transition).
 */
internal data class MainState(
    val entryId: String?,
    val positionMs: Long,
    val durationMs: Long,
    val playing: Boolean,
    val fadeMs: Long,
)

/**
 * Runs crossfades between the main player and a tail player ([CrossfadeDeck]), on [now] (a monotonic clock, ms).
 *
 * A crossfade of F ms into the next entry, with the fade starting at `duration - F`:
 * 1. Prepared: [PREPARE_LEAD_MS] before the tail is due, the current entry is loaded into the tail player, paused and
 *    silent, at the tail's start ([LEAD_IN_MS] before the fade).
 * 2. Overlap: at the tail's start the tail plays silently alongside the main player, so its audio is running by the
 *    fade (an AudioTrack takes a moment to sound).
 * 3. Swap: at the fade's start the same music moves from the main player to the tail over [SWAP_MS] (equal power),
 *    smoothing over any small offset between the two players.
 * 4. Fading: the main player, silent, moves on to the next entry ([CrossfadeDeck.advanceMain]); over F the tail fades
 *    out and the main player fades in, along [FadeCurve]. The tail is then let go.
 *
 * The session therefore shows the next song as the fade begins, and the play-count recorder, which follows the main
 * player only, counts each song once (the tail is never counted).
 *
 * Anything that breaks the plan falls back to a plain transition, never to a glitch: a tail not ready in time, a seek
 * while the tail runs, a pause (the tail stops and the main player is back at full level), a failed tail. A new plan
 * for the entry (a queue edit, a new setting) replaces the prepared tail.
 *
 * A manual change of entry (a skip, a queue tap) fades quickly: the new song comes in over [QUICK_FADE_MS] and a tail
 * still sounding fades out over the same time. Skips are never delayed, so the controllers' queue and position
 * semantics stay exactly Media3's; without a tail, the song skipped away from is cut as before.
 *
 * [update] is called on every player event and whenever the delay it returns runs out. Not thread-safe: call it on
 * the main player's thread.
 */
internal class CrossfadeCoordinator(private val deck: CrossfadeDeck, private val now: () -> Long) {

    /** Whether manual skips fade the new song in (crossfade is on). */
    var quickFades = false

    private sealed interface Phase {
        data object Idle : Phase

        data class Prepared(val entryId: String?, val fadeStartMs: Long, val fadeMs: Long) : Phase

        data class Overlap(val entryId: String?, val fadeStartMs: Long, val fadeMs: Long) : Phase

        data class Swap(val startedAt: Long, val fadeMs: Long) : Phase

        /** The tail (from [tailFrom]) fades out and the main player fades in, over [durationMs]. */
        data class Fading(val startedAt: Long, val durationMs: Long, val tailFrom: Float) : Phase
    }

    private var phase: Phase = Phase.Idle
    private var tailLoaded = false
    private var tailGain = 0f

    /** The main player's next change of entry is the coordinator's own move on, not a skip. */
    private var expectingAdvance = false

    /** Acts on [state]; returns the delay (ms) until it must be called again, or `null` to wait for the next event. */
    fun update(state: MainState): Long? = when (val current = phase) {
        Phase.Idle -> idle(state)
        is Phase.Prepared -> prepared(current, state)
        is Phase.Overlap -> overlap(current, state)
        is Phase.Swap -> swap(current, state)
        is Phase.Fading -> fading(current, state)
    }

    /** The main player's entry changed; [manual] when the user (or a controller) asked for it, not when it ran out. */
    fun onEntryChanged(manual: Boolean) {
        if (expectingAdvance) {
            expectingAdvance = false
            return
        }
        val sounding = tailLoaded && (phase is Phase.Swap || phase is Phase.Fading)
        val from = if (sounding) tailGain else 0f
        if (!sounding) releaseTail()
        if (sounding || (manual && quickFades)) {
            phase = Phase.Fading(now(), QUICK_FADE_MS, from)
            deck.setMainGain(0f)
        } else {
            phase = Phase.Idle
            deck.setMainGain(1f)
        }
    }

    /** The main player jumped within its entry: a tail already running is out of step with it. */
    fun onSeek() {
        if (phase is Phase.Overlap || phase is Phase.Swap) cancel()
    }

    /** The tail player ended or failed: it is let go; a fade under way goes on with the main player alone. */
    fun onTailEnded() {
        releaseTail()
        if (phase !is Phase.Fading) cancel()
    }

    /** Lets go of the tail and puts the main player back at full level. */
    fun release() = cancel()

    private fun idle(state: MainState): Long? {
        if (!state.playing || state.fadeMs <= 0 || state.durationMs <= 0 || state.entryId == null) return null
        val fadeStart = state.durationMs - state.fadeMs
        val tailStart = fadeStart - LEAD_IN_MS
        val armAt = tailStart - PREPARE_LEAD_MS
        val position = state.positionMs
        if (position < armAt) return armAt - position
        // Too close to the end to load the tail in time: this song ends gapless.
        if (position > tailStart - MIN_PREPARE_MS) return null
        deck.prepareTail(tailStart)
        tailLoaded = true
        setTail(0f)
        phase = Phase.Prepared(state.entryId, fadeStart, state.fadeMs)
        return tailStart - position
    }

    private fun prepared(plan: Phase.Prepared, state: MainState): Long? {
        val tailStart = plan.fadeStartMs - LEAD_IN_MS
        val position = state.positionMs
        val samePlan = state.entryId == plan.entryId && state.fadeMs == plan.fadeMs &&
            state.durationMs - state.fadeMs == plan.fadeStartMs
        if (!samePlan || position < tailStart - PREPARE_LEAD_MS - REARM_SLACK_MS) {
            cancel()
            return idle(state)
        }
        if (!state.playing) return null
        if (position < tailStart) return tailStart - position
        if (position > tailStart + LATE_START_MS || !deck.tailReady) {
            cancel()
            return null
        }
        deck.playTail(position)
        phase = Phase.Overlap(plan.entryId, plan.fadeStartMs, plan.fadeMs)
        return (plan.fadeStartMs - position).coerceAtLeast(0L)
    }

    private fun overlap(plan: Phase.Overlap, state: MainState): Long? {
        if (!state.playing || state.entryId != plan.entryId) {
            cancel()
            return idle(state)
        }
        val position = state.positionMs
        if (position < plan.fadeStartMs) return plan.fadeStartMs - position
        phase = Phase.Swap(now(), plan.fadeMs)
        return swap(phase as Phase.Swap, state)
    }

    private fun swap(swap: Phase.Swap, state: MainState): Long? {
        if (!state.playing) {
            cancel()
            return null
        }
        val progress = FadeCurve.progress(now() - swap.startedAt, SWAP_MS)
        setTail(FadeCurve.fadeIn(progress))
        deck.setMainGain(FadeCurve.fadeOut(progress))
        if (progress < 1f) return nextFrame(swap.startedAt, SWAP_MS)
        // The outgoing song now sounds from the tail alone: the main player moves on, silent, and fades in.
        setTail(1f)
        deck.setMainGain(0f)
        phase = Phase.Fading(now(), swap.fadeMs, tailFrom = 1f)
        // Set first: the change of entry may be reported while the main player is still moving on.
        expectingAdvance = true
        if (!deck.advanceMain()) expectingAdvance = false
        return FRAME_MS
    }

    private fun fading(fade: Phase.Fading, state: MainState): Long? {
        if (!state.playing) {
            cancel()
            return null
        }
        val progress = FadeCurve.progress(now() - fade.startedAt, fade.durationMs)
        if (tailLoaded) setTail(fade.tailFrom * FadeCurve.fadeOut(progress))
        deck.setMainGain(FadeCurve.fadeIn(progress))
        if (progress < 1f) return nextFrame(fade.startedAt, fade.durationMs)
        releaseTail()
        deck.setMainGain(1f)
        phase = Phase.Idle
        // The fade is over: a move on still unreported by now never will be, so it cannot swallow a later change.
        expectingAdvance = false
        return idle(state)
    }

    /** The next frame, or the end of a ramp from [startedAt] lasting [durationMs] if that comes first. */
    private fun nextFrame(startedAt: Long, durationMs: Long): Long =
        (startedAt + durationMs - now()).coerceIn(1L, FRAME_MS)

    private fun cancel() {
        releaseTail()
        deck.setMainGain(1f)
        phase = Phase.Idle
        expectingAdvance = false
    }

    private fun releaseTail() {
        if (tailLoaded) deck.stopTail()
        tailLoaded = false
        tailGain = 0f
    }

    private fun setTail(gain: Float) {
        tailGain = gain
        deck.setTailGain(gain)
    }

    companion object {
        /** How long before the tail is due it is loaded. */
        const val PREPARE_LEAD_MS = 5_000L

        /** The least time the tail is given to load; closer to the end, the song ends gapless. */
        const val MIN_PREPARE_MS = 500L

        /** How long the tail plays silently before the fade, so its audio is running by then. */
        const val LEAD_IN_MS = 300L

        /** The equal-power move of the outgoing song from the main player to the tail. */
        const val SWAP_MS = 40L

        /** A manual skip's fade. */
        const val QUICK_FADE_MS = 300L

        /** How late the tail may still start (it catches up to the main player); later, the song ends gapless. */
        const val LATE_START_MS = 250L

        /** A seek back this far before the loading point lets go of the prepared tail. */
        const val REARM_SLACK_MS = 1_000L

        /** Update cadence while levels move. */
        const val FRAME_MS = 16L
    }
}
