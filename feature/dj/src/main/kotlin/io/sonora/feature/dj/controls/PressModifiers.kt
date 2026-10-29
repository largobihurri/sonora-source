package io.sonora.feature.dj.controls

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Whether the press in progress on a hold-to-repeat button has already repeated. The button's own click comes when the
 * finger lifts, held or not, and must not add one more step after the repeats: it asks [consumeClick].
 */
@Stable
class HoldRepeatState {
    private var repeated = false

    internal fun pressStarted() {
        repeated = false
    }

    internal fun repeatFired() {
        repeated = true
    }

    /** True when the click that ends a press is the end of a hold that repeated, and so must do nothing. */
    fun consumeClick(): Boolean = repeated.also { repeated = false }
}

/**
 * Makes a button repeat its step while the finger stays on it, on [schedule]: [onRepeat] runs for each repeat (the step
 * itself, plus a haptic tick) after the delay the schedule gives. The press itself and its click are the button's own
 * ([state] tells the click to stand down after a hold). Reads the touches before the button does and consumes nothing,
 * so the button keeps its ripple and its click. Lifting, or the finger leaving the button, ends the repeats.
 */
@Composable
fun Modifier.holdToRepeat(state: HoldRepeatState, schedule: HoldRepeatSchedule = HoldRepeatSchedule.Default, onRepeat: () -> Unit): Modifier {
    val haptics = LocalHapticFeedback.current
    val step by rememberUpdatedState(onRepeat)
    return pointerInput(schedule, state) {
        coroutineScope {
            var repeating: Job? = null
            watchPresses(
                onDown = {
                    state.pressStarted()
                    repeating = launchRepeats(schedule) {
                        state.repeatFired()
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        step()
                    }
                },
                onEnd = { _ ->
                    repeating?.cancel()
                    repeating = null
                },
            )
        }
    }
}

/**
 * Which events of a button that counts on touch-down also count through its click. A touch counts when the finger
 * lands ([pressed]); the click that follows its lift must not count it again. The touch watcher sees the lift before the
 * button's click does (an earlier pointer pass), so the mark set at the press is only cleared by the click itself, or by
 * a cancelled touch, after which no click comes. A click with no touch before it (TalkBack, a keyboard) counts.
 */
class PressClickGate {
    private var pressPending = false

    /** A finger landed: the touch counts now. */
    fun pressed(): Boolean {
        pressPending = true
        return true
    }

    /** The touch ended: [released] is true for a lift on the button (its click follows), false for a cancellation. */
    fun ended(released: Boolean) {
        if (!released) pressPending = false
    }

    /** The button's click: true when it counts, that is when no touch already counted for it. */
    fun clicked(): Boolean {
        val counts = !pressPending
        pressPending = false
        return counts
    }
}

/**
 * Reports a finger going down on this button at the moment it lands (a click only comes when the finger lifts, which
 * is too late and too uneven for tapping a tempo). Reads the touches before the button does and consumes nothing;
 * [onEnd] follows when the touch ends, with `released` true for a lift on the button and false for a cancellation.
 */
@Composable
fun Modifier.onPressDown(onEnd: (released: Boolean) -> Unit = {}, onDown: () -> Unit): Modifier {
    val down by rememberUpdatedState(onDown)
    val end by rememberUpdatedState(onEnd)
    return pointerInput(Unit) { watchPresses(onDown = { down() }, onEnd = { released -> end(released) }) }
}

private fun CoroutineScope.launchRepeats(schedule: HoldRepeatSchedule, step: () -> Unit): Job = launch {
    var repeat = 0
    while (true) {
        delay(schedule.delayBeforeRepeat(repeat))
        step()
        repeat++
    }
}

private suspend fun PointerInputScope.watchPresses(onDown: () -> Unit, onEnd: (released: Boolean) -> Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            onDown()
            val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
            onEnd(up != null)
        }
    }
}
