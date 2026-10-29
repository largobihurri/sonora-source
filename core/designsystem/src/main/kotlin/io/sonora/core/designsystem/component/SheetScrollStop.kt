package io.sonora.core.designsystem.component

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity

/**
 * Whether a list inside a bottom sheet keeps a leftover [delta] (scroll px or fling velocity, positive toward closing
 * the sheet) from the sheet. Only a pull toward closing is held back: when the gesture did not start at the list's top
 * ([gestureStartedAtTop]), or when it is a fling's ([fromFling]) and the fling did not start there
 * ([flingStartedAtTop]). A list scrolled up to its top therefore stops there, and only a new pull from the top moves
 * the sheet.
 */
internal fun keepsFromSheet(gestureStartedAtTop: Boolean, flingStartedAtTop: Boolean, fromFling: Boolean, delta: Float): Boolean {
    if (delta <= 0f) return false
    return !gestureStartedAtTop || (fromFling && !flingStartedAtTop)
}

/**
 * Stops a list inside a bottom sheet hard at its top, as iOS does: a gesture that starts below the top only scrolls
 * the list, and neither its leftover drag nor its fling's leftover velocity moves (or closes) the sheet. A gesture that
 * starts with the list at its top drags the sheet as before. Apply with [sheetScrollStop] to the list; programmatic
 * scrolls dispatch no nested scroll, so they never reach the sheet anyway.
 */
class SheetScrollStop internal constructor(private val atTop: () -> Boolean) : NestedScrollConnection {
    // Plain fields: only the gesture and the nested scroll callbacks read them.
    private var gestureStartedAtTop = true
    private var flingStartedAtTop = true

    internal fun gestureStarted() {
        gestureStartedAtTop = atTop()
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        // A fling's leftover px pass: the sheet ignores them, and eating them would keep the list's fling running,
        // unseen, at the top. Its leftover velocity is held back in onPostFling instead.
        if (source != NestedScrollSource.UserInput) return Offset.Zero
        val keeps = keepsFromSheet(gestureStartedAtTop, flingStartedAtTop, fromFling = false, available.y)
        return if (keeps) Offset(0f, available.y) else Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        flingStartedAtTop = atTop()
        return Velocity.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        val keeps = keepsFromSheet(gestureStartedAtTop, flingStartedAtTop, fromFling = true, available.y)
        return if (keeps) Velocity(0f, available.y) else Velocity.Zero
    }
}

/** A [SheetScrollStop] for the list scrolled by [state]. */
@Composable
fun rememberSheetScrollStop(state: ScrollableState): SheetScrollStop = remember(state) { SheetScrollStop { !state.canScrollBackward } }

/** Keeps [stop]'s list from handing a pull that did not start at its top to the enclosing sheet (see [SheetScrollStop]). */
fun Modifier.sheetScrollStop(stop: SheetScrollStop): Modifier = this
    .pointerInput(stop) {
        // Notes where each gesture starts before the list (or anything in it) sees it; consumes nothing.
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            stop.gestureStarted()
        }
    }
    .nestedScroll(stop)
