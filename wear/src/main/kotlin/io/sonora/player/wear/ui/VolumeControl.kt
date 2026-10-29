package io.sonora.player.wear.ui

/**
 * What the crown turns on Now Playing: the watch's own music volume ([SystemVolume]) or the phone's (the remote). Only ever
 * called with the user's own crown steps.
 */
fun interface VolumeControl {
    /** Moves the volume by [steps] (negative lowers it) and returns the percent it should now be, for the ring. */
    fun step(steps: Int): Int
}
