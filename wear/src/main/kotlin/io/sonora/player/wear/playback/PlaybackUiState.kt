package io.sonora.player.wear.playback

/** What the screens show of the player, as one value. */
data class PlaybackUiState(
    /** The session's controller is connected; false at first and after the service went away. */
    val connected: Boolean = false,
    /** The playing song's id ([io.sonora.player.wear.library.WatchTrack.id]); `null` when nothing is queued. */
    val mediaId: String? = null,
    val title: String? = null,
    val artist: String? = null,
    /** The user wants sound: true while playing, and also while playback waits for an output. */
    val playWhenReady: Boolean = false,
    /** Playback waits because the watch has no suitable output (no Bluetooth headphones connected). */
    val waitingForOutput: Boolean = false,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val durationMs: Long? = null,
) {
    val hasMedia: Boolean get() = mediaId != null
}
