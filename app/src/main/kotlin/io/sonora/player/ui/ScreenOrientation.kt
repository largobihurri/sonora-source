package io.sonora.player.ui

import android.content.pm.ActivityInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.sonora.core.designsystem.layout.WindowClass
import io.sonora.player.navigation.TopLevelRoute

/**
 * The orientation the app asks for:
 * - Now Playing turns with the phone only while the user's rotation lock allows it ([ActivityInfo.SCREEN_ORIENTATION_FULL_USER]):
 *   with auto-rotate off it stays upright.
 * - On the Library tab, a list with a visual collection ([libraryVisualCollection]) follows the physical sensor even with auto-rotate
 *   off ([ActivityInfo.SCREEN_ORIENTATION_SENSOR]), so holding the phone sideways always shows the visual collection and holding
 *   it upright returns to the list. SENSOR (unlike FULL_SENSOR) keeps the device's own rule for upside-down portrait.
 * - With gravity mode on ([gravity], Settings), an open Now Playing hangs its groups level in the phone's own frame, so
 *   the window turns beneath it unseen: sideways ([ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE], whichever side the
 *   phone is on, even with the rotation lock) while its destination is the visual collection ([gravitySideways], see
 *   GravityDestination), so the Library composes its visual collection beneath and a drag reveals it; otherwise in its natural
 *   portrait ([ActivityInfo.SCREEN_ORIENTATION_PORTRAIT], not even upside down).
 * - Every other screen stays upright, and so does everything while [uprightHold] (see [UprightHold]).
 * - A large screen ([WindowClass.isLarge], a tablet) is never locked: whatever the screen, the upright hold or gravity
 *   mode, it asks for [ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED], so the system's own rule applies (the user's
 *   auto-rotate setting, all four sides) on every Android version. UNSPECIFIED rather than FULL_USER because it also
 *   undoes any earlier lock when the window turns large (a foldable opening) and leaves the decision to the device, as
 *   large-screen guidance asks; the phone rules above are untouched.
 */
internal fun screenOrientation(
    nowPlayingOpen: Boolean,
    tab: TopLevelRoute,
    libraryVisualCollection: Boolean,
    uprightHold: Boolean = false,
    gravity: Boolean = false,
    gravitySideways: Boolean = false,
    windowClass: WindowClass = WindowClass.Phone,
): Int = when {
    windowClass.isLarge -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    uprightHold -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
    nowPlayingOpen && gravity && gravitySideways -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    nowPlayingOpen && gravity -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    nowPlayingOpen -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
    visualCollectionList(tab, libraryVisualCollection) -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
    else -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
}

/**
 * Whether gravity mode is on for this window: the Settings choice ([setting]), but never in a large window. Gravity mode
 * assumes a portrait-natural phone (its frame, detents and window turns are built on that), so a tablet ignores the
 * setting and keeps its ordinary Now Playing.
 */
internal fun gravityEnabled(setting: Boolean, windowClass: WindowClass): Boolean = setting && !windowClass.isLarge

/** Whether the screen shown is a Library list (songs, albums, artists, playlists) that turns into a visual collection sideways. */
internal fun visualCollectionList(tab: TopLevelRoute, libraryVisualCollection: Boolean): Boolean =
    tab == TopLevelRoute.Library && libraryVisualCollection

/**
 * Gravity mode's window side ([screenOrientation]'s gravitySideways): the latched destination's, but only between
 * gestures: a drag keeps the side it started with, so the window never turns under a finger moving Now Playing. Asked
 * during composition, it keeps the side as snapshot state, so a composition thrown away leaves it as it was
 * (R3-window-side-composition-mutation).
 */
internal class GravityWindowSide {
    private var last by mutableStateOf(false)

    /** The side to ask for with the destination [latched] sideways or not, while the sheet is [dragging] or not. */
    fun sideways(latched: Boolean, dragging: Boolean): Boolean {
        if (!dragging) last = latched
        return last
    }
}
