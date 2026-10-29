package io.sonora.core.playback.interruption

import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Whether the device is in a call audio profile, from the audio mode and the device music is routed to (`null` when
 * unknown). Call and communication modes (VoIP, dictation, assistant conversations) qualify, and so does music routed
 * to a call device (Bluetooth SCO, the earpiece) without a mode change, as when an app opens SCO in normal mode.
 * A BLE headset does not: LE Audio earbuds play media as one too, so only the mode can tell a call there.
 */
internal object CallAudioProfile {
    // The newer modes are plain ints: comparing with them is safe on any API level.
    private val CALL_MODES = setOf(
        AudioManager.MODE_IN_CALL,
        AudioManager.MODE_IN_COMMUNICATION,
        MODE_CALL_SCREENING,
        MODE_CALL_REDIRECT,
        MODE_COMMUNICATION_REDIRECT,
        MODE_ASSISTANT_CONVERSATION,
    )

    private val CALL_ROUTES = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)

    fun isActive(mode: Int, mediaRouteType: Int?): Boolean = mode in CALL_MODES || mediaRouteType in CALL_ROUTES
}

// AudioManager's values, added after minSdk (API 30, 33, 33 and 37).
private const val MODE_CALL_SCREENING = 4
private const val MODE_CALL_REDIRECT = 5
private const val MODE_COMMUNICATION_REDIRECT = 6
private const val MODE_ASSISTANT_CONVERSATION = 7
