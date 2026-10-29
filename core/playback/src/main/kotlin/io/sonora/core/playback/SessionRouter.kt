package io.sonora.core.playback

import android.content.Context
import androidx.annotation.MainThread
import androidx.media3.common.Player

/**
 * Hands the playback session to another device and back (Chromecast in the `full` build). Only builds with such a link
 * bind it (see [PlaybackModule.optionalSessionRouter]); without it the session always plays on this phone.
 */
fun interface SessionRouter {
    /** The player the session uses around [local], or null when this device cannot route (no Google Play services). */
    @MainThread
    fun route(context: Context, local: Player): RoutedPlayer?
}

/** A session player that can move to another device; [release] also releases the local player it wraps. */
interface RoutedPlayer {
    val player: Player

    fun release()
}
