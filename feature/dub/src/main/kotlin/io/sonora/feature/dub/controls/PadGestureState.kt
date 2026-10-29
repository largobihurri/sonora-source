package io.sonora.feature.dub.controls

/** What a [DubPad] is doing: off, held by a finger (momentary), or latched on. */
enum class PadState { OFF, HELD, LATCHED }

/**
 * The gestures of one DUB pad, as a pure state machine (design §4, "Momentary controls"):
 * - a down engages at once, with no wait to tell a double tap apart; its up releases (momentary);
 * - a second down within [doubleTapWindowMs] of the previous up latches, and the pad stays on after that up;
 * - the next tap on a latched pad unlatches it at its down, and that up changes nothing;
 * - a cancel (a parent or the system took the pointer) counts as a release, also for the press that latched, and never
 *   starts a double tap;
 * - [latch] and [release] are the screen-reader actions, [releaseMomentary] runs when the screen stops (a latch stays).
 *
 * Every pad has its own instance, so two pads held with two fingers never share state. Each transition returns true
 * when [engaged] changed, so the caller reports exactly the edges. Times are in milliseconds on one clock (the pointer
 * events' uptime).
 */
class PadGestureState(private val doubleTapWindowMs: Long = DOUBLE_TAP_WINDOW_MS) {
    private var pressed = false
    private var latched = false

    /** This press turned the latch on: its up keeps the pad on, its cancel turns it off. */
    private var pressLatched = false

    /** This press turned the latch off: the pad stays off for the rest of it. */
    private var pressUnlatching = false

    /** When the last momentary press ended, while a second down could still latch. */
    private var lastUpMs: Long? = null

    val state: PadState
        get() = when {
            latched -> PadState.LATCHED
            pressed && !pressUnlatching -> PadState.HELD
            else -> PadState.OFF
        }

    val engaged: Boolean get() = state != PadState.OFF

    /** A finger landed on the pad at [uptimeMs]. */
    fun down(uptimeMs: Long): Boolean = change {
        if (pressed) return@change
        pressed = true
        if (latched) {
            latched = false
            pressUnlatching = true
        } else {
            val last = lastUpMs
            if (last != null && uptimeMs - last in 0..doubleTapWindowMs) {
                latched = true
                pressLatched = true
            }
        }
        lastUpMs = null
    }

    /** The finger lifted at [uptimeMs]. */
    fun up(uptimeMs: Long): Boolean = change {
        if (!pressed) return@change
        val momentary = !pressLatched && !pressUnlatching
        endPress()
        if (momentary) lastUpMs = uptimeMs
    }

    /** The touch was cancelled: a release, which undoes a latch made by this same press. */
    fun cancel(): Boolean = change {
        if (!pressed) return@change
        if (pressLatched) latched = false
        endPress()
        lastUpMs = null
    }

    /** Latches the pad (the screen-reader action); a finger on it keeps the latch when it lifts. */
    fun latch(): Boolean = change {
        latched = true
        if (pressed) {
            pressUnlatching = false
            pressLatched = true
        }
    }

    /** Turns the pad off whatever it was doing (the screen-reader action). */
    fun release(): Boolean = change {
        latched = false
        endPress()
        lastUpMs = null
    }

    /** The screen stopped: a held pad releases, a latched one stays latched. A late up of that touch changes nothing. */
    fun releaseMomentary(): Boolean = change {
        endPress()
        lastUpMs = null
    }

    private fun endPress() {
        pressed = false
        pressLatched = false
        pressUnlatching = false
    }

    private inline fun change(block: () -> Unit): Boolean {
        val before = engaged
        block()
        return engaged != before
    }

    companion object {
        /** How soon after an up a second down latches the pad. */
        const val DOUBLE_TAP_WINDOW_MS = 250L
    }
}
