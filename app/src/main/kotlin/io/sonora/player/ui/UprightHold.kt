package io.sonora.player.ui

import android.os.SystemClock
import android.view.OrientationEventListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import io.sonora.feature.nowplaying.GravityDestination

/**
 * After the user swipes a sideways Now Playing away, the app stays upright until the phone is held upright again:
 * turning back to landscape straight away would reopen what was just dismissed, in a loop. Over a Library list with a
 * Visual collection ([visualCollectionList]) there is nothing to hold: the phone is sideways, so the visual collection shows at once. In
 * gravity mode a Now Playing headed upright ([gravityUpright], its destination latched upright) closes into the portrait
 * screen it covered, held there even over a visual collection list until the phone reads upright, whatever its tilt between
 * detents.
 */
internal object UprightHold {

    /**
     * Whether Now Playing just closed ([wasOpen] to not [isOpen]) while the screen was [landscape], over a screen that is
     * not a visual collection list ([visualCollectionList]); or, in gravity mode headed upright ([gravityUpright]), over one that is.
     */
    fun startsOnClose(
        wasOpen: Boolean,
        isOpen: Boolean,
        landscape: Boolean,
        visualCollectionList: Boolean,
        gravityUpright: Boolean = false,
    ): Boolean = wasOpen && !isOpen && (if (visualCollectionList) gravityUpright else landscape)

    /** Whether the phone, at [degrees] from its natural upright position, reads upright (not sideways or unknown). */
    fun isUpright(degrees: Int): Boolean =
        degrees != OrientationEventListener.ORIENTATION_UNKNOWN && (degrees <= UPRIGHT_SLACK || degrees >= 360 - UPRIGHT_SLACK)

    private const val UPRIGHT_SLACK = 30
}

/**
 * Whether a closing Now Playing counts as gravity mode's upright destination for [UprightHold.startsOnClose]: only with
 * [gravity] on and its [destination] latched upright (a latch left from an earlier gravity session must hold nothing).
 */
internal fun closesGravityUpright(gravity: Boolean, destination: GravityDestination): Boolean =
    gravity && destination == GravityDestination.Upright

/**
 * Ends the hold only once the phone has read upright for [dwellMs] without a single other reading in between: a jolt of
 * a sideways phone, or the drifting angle of one lying nearly flat, never reads upright that long. Readings with no
 * orientation (the listener's [OrientationEventListener.ORIENTATION_UNKNOWN] for a flat phone) restart the wait too.
 */
internal class UprightDwell(private val dwellMs: Long = UPRIGHT_DWELL_MS) {

    private var uprightSinceMs: Long? = null

    /** Whether the hold ends with this reading of [degrees] taken at [nowMs] (a monotonic clock). */
    fun releasesAt(degrees: Int, nowMs: Long): Boolean {
        if (!UprightHold.isUpright(degrees)) {
            uprightSinceMs = null
            return false
        }
        val since = uprightSinceMs ?: nowMs.also { uprightSinceMs = it }
        return nowMs - since >= dwellMs
    }

    private companion object {
        const val UPRIGHT_DWELL_MS = 500L
    }
}

/** While [held], watches the phone's physical orientation and calls [onUpright] once it is held upright (see [UprightDwell]). */
@Composable
internal fun UprightHoldRelease(held: Boolean, onUpright: () -> Unit) {
    if (!held) return
    val context = LocalContext.current
    val release by rememberUpdatedState(onUpright)
    DisposableEffect(context) {
        val dwell = UprightDwell()
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (dwell.releasesAt(orientation, SystemClock.uptimeMillis())) release()
            }
        }
        if (listener.canDetectOrientation()) listener.enable() else release()
        onDispose { listener.disable() }
    }
}
