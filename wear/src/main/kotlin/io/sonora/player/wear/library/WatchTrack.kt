package io.sonora.player.wear.library

/**
 * A song on the watch, as the list and the player see it. Plain strings and numbers only, so the model (and what builds
 * it) is testable on the JVM.
 *
 * @property id Stable for the song across scans; the file name for the files scan, the library row's key later.
 * @property uri Where the player reads it (a `file://` URI for a file in the app's storage).
 * @property durationMs `null` when unknown.
 */
data class WatchTrack(
    val id: String,
    val uri: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val trackNumber: Int?,
    val durationMs: Long?,
)
