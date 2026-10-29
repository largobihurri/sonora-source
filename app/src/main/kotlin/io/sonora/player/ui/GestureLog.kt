package io.sonora.player.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log

/** Gesture decisions (axis lock, sheet progress, settles, track swipes) under the `SonoraGestures` tag, debug only. */
internal object GestureLog {
    private const val TAG = "SonoraGestures"

    /** Logs [message] when the app is debuggable; release builds never format or write it. */
    fun of(context: Context): ((String) -> Unit)? =
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) { message -> Log.d(TAG, message) } else null
}
