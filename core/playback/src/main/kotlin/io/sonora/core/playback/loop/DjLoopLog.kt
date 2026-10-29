package io.sonora.core.playback.loop

import android.util.Log

/**
 * What the DJ loop says about itself in logcat, for the one question a phone cannot answer on its own: why loops are (not)
 * available. Silent unless the tag is switched on (`adb shell setprop log.tag.SonoraDjLoop VERBOSE`, then
 * `adb logcat -s SonoraDjLoop`), like the other Sonora tags. Only decisions are logged (a stream configured, a session
 * started or dropped, the state the screen is given), never anything per buffer.
 */
internal object DjLoopLog {
    const val TAG = "SonoraDjLoop"

    internal val defaultSink: (String) -> Unit = { Log.i(TAG, it) }

    /** A JVM without Android's `Log` reads as off. */
    internal val defaultEnabled: () -> Boolean = { runCatching { Log.isLoggable(TAG, Log.VERBOSE) }.getOrDefault(false) }

    /** Where lines go; tests replace it to read them. */
    @Volatile
    var sink: (String) -> Unit = defaultSink

    /** Whether the tag is on; replaceable for the same reason. */
    @Volatile
    var enabled: () -> Boolean = defaultEnabled

    /** Logs [message] when the tag is on; the lambda is not run otherwise, so a call costs nothing while it is off. */
    inline fun v(message: () -> String) {
        if (enabled()) sink(message())
    }
}
