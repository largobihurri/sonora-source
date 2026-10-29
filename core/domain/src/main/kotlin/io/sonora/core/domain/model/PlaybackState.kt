package io.sonora.core.domain.model

enum class RepeatMode {
    OFF, ALL, ONE;

    /** Apple Music order: off -> all -> one -> off. */
    fun next(): RepeatMode = when (this) {
        OFF -> ALL
        ALL -> ONE
        ONE -> OFF
    }
}

/** Snapshot of the player exposed to the UI. */
data class PlaybackState(
    val currentTrack: Track? = null,
    /** The real play order, shuffle included; [currentIndex] points into it. */
    val queue: List<QueueItem> = emptyList(),
    /** Whether playback was asked for and is not stopped: true while a track buffers, so the UI shows it as playing. */
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val currentIndex: Int = -1,
    val isConnected: Boolean = false,
    /** How the player decodes and outputs the current entry; `null` until reported or for another entry. */
    val decodedFormat: DecodedAudioFormat? = null,
) {
    /** Items after the current one, with their queue indices. */
    val upNext: List<IndexedValue<QueueItem>>
        get() = queue.withIndex().filter { it.index > currentIndex }
}
