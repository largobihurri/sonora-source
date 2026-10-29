package io.sonora.core.designsystem.component

import kotlin.coroutines.cancellation.CancellationException

/**
 * Lets at most one action of a sheet showing (an [ActionSheet], a picker) run: the first tap hides the sheet and then runs its action;
 * taps meanwhile (a double tap, another row) and after are ignored. When the slide is interrupted (the sheet leaves
 * the screen, or a drag takes over), nothing runs and the next tap may choose again. Main thread only.
 */
class SheetActionGate {
    private var busy = false

    /** Hides with [hide], then runs [action]; returns false when the tap was ignored or nothing ran. */
    suspend fun choose(hide: suspend () -> Unit, action: () -> Unit): Boolean {
        if (busy) return false
        busy = true
        try {
            hide()
        } catch (e: CancellationException) {
            busy = false
            throw e
        }
        action()
        return true
    }
}
