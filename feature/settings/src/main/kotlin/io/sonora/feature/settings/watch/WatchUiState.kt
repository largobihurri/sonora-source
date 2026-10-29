package io.sonora.feature.settings.watch

/** A one-off notice after removing songs from the watch. */
sealed interface WatchMessage {
    data class Removed(val count: Int) : WatchMessage

    /** The watch confirmed only [removed] of the [requested] songs. */
    data class PartlyRemoved(val removed: Int, val requested: Int) : WatchMessage

    data object RemoveFailed : WatchMessage
}
