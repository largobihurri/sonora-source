package io.sonora.feature.dub.controls

/**
 * The gestures of a preamp kill switch (design §10.1, "tap toggles (latched), hold is momentary"), as a pure state
 * machine. A down flips the way at once (so a hold acts with no delay); an up within [holdMs] of the down was a tap and
 * keeps the flip; a longer press was a hold, so its up restores the state before the press. Holding a killed way
 * therefore brings it back only while held. A cancel restores the state before the press. Each call returns the kill
 * state to apply, or null for no change. Times are milliseconds on one clock.
 */
class KillGestureState(private val holdMs: Long = HOLD_MS) {
    private var pressing = false
    private var before = false
    private var downAtMs = 0L

    /** A finger landed at [uptimeMs] on a way that is [killed]: the state to apply now (the flip). */
    fun down(uptimeMs: Long, killed: Boolean): Boolean {
        pressing = true
        before = killed
        downAtMs = uptimeMs
        return !killed
    }

    /** The finger lifted at [uptimeMs]: null after a tap (the flip stays), the state before the press after a hold. */
    fun up(uptimeMs: Long): Boolean? {
        if (!pressing) return null
        pressing = false
        return if (uptimeMs - downAtMs < holdMs) null else before
    }

    /** The touch was cancelled: the state before the press, or null with no press. */
    fun cancel(): Boolean? {
        if (!pressing) return null
        pressing = false
        return before
    }

    companion object {
        /** A press at least this long is a momentary hold rather than a tap. */
        const val HOLD_MS = 300L
    }
}
