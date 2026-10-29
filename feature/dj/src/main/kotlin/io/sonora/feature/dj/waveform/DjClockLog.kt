package io.sonora.feature.dj.waveform

import android.util.Log

/**
 * The DJ screen's clock diagnostics: one `SonoraDjClock` line (Log.i) per second while the DJ screen plays, from the
 * shared [stats] (the position source counts the frames and reads, the scrolling waveform its tile misses). Read with
 * `adb logcat -s SonoraDjClock` (on ColorOS first `adb shell setprop log.tag.SonoraDjClock VERBOSE`). Nothing is
 * formatted when the tag is not loggable. Main thread only.
 */
internal object DjClockLog {
    const val TAG = "SonoraDjClock"

    /** The counts of the current second, shared by the position source and the waveform. */
    val stats = DjClockStats()

    /** Logs [stats] as one line, when the tag is loggable. */
    fun report(stats: DjClockStats) {
        if (Log.isLoggable(TAG, Log.INFO)) Log.i(TAG, stats.summary())
    }
}
