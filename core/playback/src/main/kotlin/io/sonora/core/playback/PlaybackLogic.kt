package io.sonora.core.playback

import androidx.media3.common.Player

/** Pure decisions extracted from the controller so they can be unit tested. */
internal object PlaybackLogic {
    const val RESTART_THRESHOLD_MS = 3_000L

    enum class PreviousAction { RESTART, PREVIOUS_ITEM }

    fun previousAction(positionMs: Long, hasPrevious: Boolean): PreviousAction =
        if (positionMs > RESTART_THRESHOLD_MS || !hasPrevious) PreviousAction.RESTART else PreviousAction.PREVIOUS_ITEM

    /** Clamps a requested start index into the queue bounds. */
    fun safeStartIndex(index: Int, size: Int): Int = if (size == 0) 0 else index.coerceIn(0, size - 1)

    /**
     * Whether the UI should show playback as running: the intent to play, as Media3's own play button follows it. A
     * track that was asked to play but is still buffering counts, so its bars and pause button show from the tap
     * instead of flashing the paused pose until the first audio. Idle and ended players, a paused one, and one held
     * back by a playback suppression (e.g. a transient audio focus loss) do not. Consumers that need audio actually moving read [Player.isPlaying] instead.
     */
    fun showsAsPlaying(playWhenReady: Boolean, playbackState: Int, suppressionReason: Int): Boolean =
        playWhenReady &&
            playbackState != Player.STATE_IDLE &&
            playbackState != Player.STATE_ENDED &&
            suppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
}
