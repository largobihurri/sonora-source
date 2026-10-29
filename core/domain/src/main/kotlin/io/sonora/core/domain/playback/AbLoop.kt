package io.sonora.core.domain.playback

import kotlinx.coroutines.flow.StateFlow

/**
 * The A-B loop for DJ practice: while [enabled], the first tap sets A ([startMs]) at the playing position, the second
 * sets B ([endMs]), and a third clears both. With both set, playback jumps from B back to A ([nextStep]).
 *
 * B always comes at least [MIN_SPAN_MS] after A, and both stay within the track, B at least [END_MARGIN_MS] before its
 * end. The points belong to the queue entry
 * [itemId]: a track change ([onItem]) or a seek outside the span ([afterSeek]) clears them, keeping the mode on.
 */
data class AbLoop(
    val enabled: Boolean = false,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val itemId: Long? = null,
) {
    /** What the next tap does. */
    enum class Phase { OFF, AWAITING_A, AWAITING_B, LOOPING }

    val phase: Phase
        get() = when {
            !enabled -> Phase.OFF
            startMs == null -> Phase.AWAITING_A
            endMs == null -> Phase.AWAITING_B
            else -> Phase.LOOPING
        }

    /** Both points are set, so playback repeats the span. */
    val isLooping: Boolean get() = phase == Phase.LOOPING

    /** Turns the mode on with no points, or off forgetting them. */
    fun toggled(): AbLoop = if (enabled) AbLoop() else AbLoop(enabled = true)

    /** Keeps the mode, forgets the points. */
    fun cleared(): AbLoop = AbLoop(enabled = enabled)

    /**
     * The A/B control's tap at [positionMs] of the entry [itemId]: sets A, then B, then clears. A leaves room for the
     * minimum span before the end margin ([durationMs] when known, else above zero); a track shorter than that takes
     * no A. B lands at least the minimum span after A. Nothing while the mode is off.
     */
    fun tapped(positionMs: Long, durationMs: Long, itemId: Long?): AbLoop = when (phase) {
        Phase.OFF -> this
        Phase.LOOPING -> cleared()
        Phase.AWAITING_B -> if (itemId != this.itemId) cleared().tapped(positionMs, durationMs, itemId) else copy(
            endMs = clampEnd(positionMs, startMs!!, durationMs),
        )
        Phase.AWAITING_A -> {
            val latest = latestStart(durationMs)
            if (latest < 0) this else copy(startMs = positionMs.coerceIn(0L, latest), itemId = itemId)
        }
    }

    /** A moved to [positionMs], kept before B (or the end margin) by the minimum span; nothing while A is not set. */
    fun withStart(positionMs: Long, durationMs: Long): AbLoop {
        if (startMs == null) return this
        val latest = endMs?.let { it - MIN_SPAN_MS } ?: latestStart(durationMs)
        return if (latest < 0) this else copy(startMs = positionMs.coerceIn(0L, latest))
    }

    /**
     * B moved to [positionMs], kept after A by the minimum span and the end margin before the track's end; nothing
     * while B is not set.
     */
    fun withEnd(positionMs: Long, durationMs: Long): AbLoop {
        if (endMs == null || startMs == null) return this
        return copy(endMs = clampEnd(positionMs, startMs, durationMs))
    }

    /** A seek to [targetMs] outside the span clears the loop; one inside it (the loop's own jump to A) keeps it. */
    fun afterSeek(targetMs: Long): AbLoop =
        if (isLooping && targetMs !in startMs!!..endMs!!) cleared() else this

    /** The entry playing is now [itemId]: points set on another entry are cleared. */
    fun onItem(itemId: Long?): AbLoop = if (startMs != null && itemId != this.itemId) cleared() else this

    /** What the loop watcher does at [positionMs]. */
    sealed interface Step {
        /** Jump back to A. */
        data class SeekTo(val positionMs: Long) : Step

        /** Check the position again after [delayMs]. */
        data class Wait(val delayMs: Long) : Step
    }

    /**
     * Jumps to A once the position is within [PRE_SEEK_MARGIN_MS] of B, so the jump never overshoots B. Otherwise
     * waits half the time left before that point, within [MIN_POLL_MS] and [MAX_POLL_MS]: the checks close in on B,
     * and each one waking late still lands inside the margin.
     */
    fun nextStep(positionMs: Long): Step {
        if (!isLooping) return Step.Wait(MAX_POLL_MS)
        val remaining = endMs!! - PRE_SEEK_MARGIN_MS - positionMs
        return if (remaining <= 0) Step.SeekTo(startMs!!) else Step.Wait((remaining / 2).coerceIn(MIN_POLL_MS, MAX_POLL_MS))
    }

    private fun latestStart(durationMs: Long): Long =
        if (durationMs > 0) latestEnd(durationMs) - MIN_SPAN_MS else Long.MAX_VALUE

    private fun latestEnd(durationMs: Long): Long = durationMs - END_MARGIN_MS

    /** B within the track's end margin; never before A's minimum span, which [latestStart] keeps inside that margin. */
    private fun clampEnd(positionMs: Long, start: Long, durationMs: Long): Long {
        val end = positionMs.coerceAtLeast(start + MIN_SPAN_MS)
        return if (durationMs > 0) end.coerceAtMost(latestEnd(durationMs).coerceAtLeast(start + MIN_SPAN_MS)) else end
    }

    companion object {
        /** The shortest span: B is at least this long after A. */
        const val MIN_SPAN_MS: Long = 500L

        /** How long before B the jump to A is made, so a check waking a little late does not play past B. */
        const val PRE_SEEK_MARGIN_MS: Long = 20L

        /**
         * How far before the track's end B may sit at the latest: a check waking this late still jumps back to A
         * instead of letting the player reach the end and move on to the next entry.
         */
        const val END_MARGIN_MS: Long = 250L

        const val MIN_POLL_MS: Long = 5L
        const val MAX_POLL_MS: Long = 50L
    }
}

/** The player's A-B loop (see [AbLoop]). Commands act on the entry playing and its position. */
interface AbLoopController {
    val abLoop: StateFlow<AbLoop>

    /** Turns the A-B loop mode on or off (Now Playing's "…" menu). */
    fun toggleAbLoop()

    /** Sets A, then B, then clears the loop. */
    fun tapAbLoop()

    /** Nudges A to [positionMs], within the constraints. */
    fun moveLoopStart(positionMs: Long)

    /** Nudges B to [positionMs], within the constraints. */
    fun moveLoopEnd(positionMs: Long)
}
