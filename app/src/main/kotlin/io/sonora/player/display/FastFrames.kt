package io.sonora.player.display

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * When Sonora asks for the display's fastest refresh rate: while a finger is on the screen and for [TOUCH_TAIL_MS]
 * after it lifts (flings, Cover Flow glides, transitions started by a tap), and for [TURN_MS] after the display turns
 * (Now Playing's turn, which moves with no finger down). Otherwise the rate is left to the system, so a still screen
 * goes back to its slower, thriftier rates. Pure, so it is unit-tested; times are in milliseconds on one monotonic
 * clock.
 */
internal class FastFramePolicy {
    private var touching = false
    private var until = Long.MIN_VALUE

    /** A finger went down ([down]) or the last one lifted (or the gesture was cancelled), at [now]. */
    fun touch(down: Boolean, now: Long) {
        touching = down
        if (!down) extend(now + TOUCH_TAIL_MS)
    }

    /** The display turned at [now]. */
    fun turned(now: Long) = extend(now + TURN_MS)

    /** Whether the fastest rate is wanted at [now]. */
    fun fast(now: Long): Boolean = touching || now < until

    /** When the current request ends if nothing else happens, or null while a finger is down. */
    fun endsAt(): Long? = if (touching) null else until

    private fun extend(to: Long) {
        if (to > until) until = to
    }

    companion object {
        /** Long enough for a fling or a Cover Flow glide to come to rest. */
        const val TOUCH_TAIL_MS = 2_500L

        /** Now Playing's turn (420 ms) with room for the new layout's first frames. */
        const val TURN_MS = 1_000L
    }
}

/**
 * Follows [FastFramePolicy]: [fast] is what the UI votes, the high frame rate category while true
 * (Modifier.preferredFrameRate at its root), no preference otherwise. Touches arrive through [onTouch] (the
 * activity's dispatchTouchEvent), display turns through [onTurn].
 *
 * The vote is the standard request and works where the system honours it (Android 15+ on stock builds). Some phones
 * decide on their own: ColorOS keeps 60 Hz with no finger down whatever an app votes (measured on a realme GT 7 Pro,
 * also for a preferred display mode, which besides changes the window's attributes mid-turn, so it is not used).
 */
internal class FastFrames {
    /** Whether the UI should vote for the high frame rate category now. */
    var fast by mutableStateOf(false)
        private set

    private val policy = FastFramePolicy()
    private val handler = Handler(Looper.getMainLooper())
    private val settle = Runnable { apply() }

    fun onTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> policy.touch(down = true, now())
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> policy.touch(down = false, now())
            else -> return
        }
        apply()
    }

    fun onTurn() {
        policy.turned(now())
        apply()
    }

    /** Back to no preference, e.g. when the activity stops. */
    fun release() {
        handler.removeCallbacks(settle)
        fast = false
    }

    private fun apply() {
        val now = now()
        handler.removeCallbacks(settle)
        fast = policy.fast(now)
        policy.endsAt()?.takeIf { it > now }?.let { handler.postDelayed(settle, it - now) }
    }

    private fun now() = SystemClock.uptimeMillis()
}
