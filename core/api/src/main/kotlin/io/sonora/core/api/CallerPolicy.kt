package io.sonora.core.api

/**
 * The custom permission an app must hold to use the external API while the user's "External control" switch is on.
 * Declared (protection level `dangerous`, so the user grants it per app) in the manifest of `core:playback`.
 */
const val EXTERNAL_CONTROL_PERMISSION = "io.sonora.permission.EXTERNAL_CONTROL"

/** Why a caller was turned away. [message] is English text for the `SecurityException`; it never names the caller. */
enum class DenialReason(val message: String) {
    /** The user has not turned External control on in Settings. */
    SWITCH_OFF("External control is off. Turn it on in Sonora's Settings."),

    /** The switch is on, but the calling app does not hold [EXTERNAL_CONTROL_PERMISSION]. */
    PERMISSION_MISSING("The caller needs the $EXTERNAL_CONTROL_PERMISSION permission."),
}

sealed interface CallerVerdict {
    data object Allowed : CallerVerdict
    data class Denied(val reason: DenialReason) : CallerVerdict
}

/**
 * Who may use the external API, decided on the calling uid (never on a package name, which a caller can claim).
 *
 * - Sonora's own uid is always allowed.
 * - Everybody else is denied while the user's switch is off, the adb shell and root included.
 * - With the switch on, the adb shell (uid 2000) and root (uid 0) are allowed: adb already implies physical access
 *   and developer options.
 * - With the switch on, any other app must also hold [EXTERNAL_CONTROL_PERMISSION].
 *
 * Pure: the provider adapter reads the switch and the permission and acts on the verdict.
 */
class CallerPolicy(private val ownUid: Int) {

    /**
     * Whether the caller with [callerUid] may proceed. [holdsPermission] is only asked for an app while the switch
     * is on, as looking it up is a call to the package manager.
     */
    fun check(callerUid: Int, switchOn: Boolean, holdsPermission: () -> Boolean): CallerVerdict = when {
        callerUid == ownUid -> CallerVerdict.Allowed
        !switchOn -> CallerVerdict.Denied(DenialReason.SWITCH_OFF)
        callerUid == SHELL_UID || callerUid == ROOT_UID -> CallerVerdict.Allowed
        holdsPermission() -> CallerVerdict.Allowed
        else -> CallerVerdict.Denied(DenialReason.PERMISSION_MISSING)
    }

    companion object {
        /** The uid `adb shell` commands run as (`Process.SHELL_UID`). */
        const val SHELL_UID = 2000

        const val ROOT_UID = 0
    }
}
