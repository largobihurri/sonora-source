package io.sonora.core.watchsync.receiver

import io.sonora.core.watchsync.ManifestTrack

/**
 * The file names of the watch's music folder (`filesDir/music`), shared with the watch app's library, which plays every
 * `*.opus` file there: a song is `<id>.opus` plus its manifest entry `<id>.json`; a transfer in progress is
 * `<id>-<checksum prefix>.opus.part` and is not playable (its extension is `part`).
 */
object LibraryLayout {
    const val AUDIO_EXTENSION = "opus"
    const val SIDECAR_EXTENSION = "json"
    const val PARTIAL_SUFFIX = ".opus.part"
    const val TEMP_SUFFIX = ".tmp"

    fun audioName(id: String) = "$id.$AUDIO_EXTENSION"

    fun sidecarName(id: String) = "$id.$SIDECAR_EXTENSION"

    /** Bound to the checksum, so a partial file of other content with the same id is never resumed. */
    fun partialName(track: ManifestTrack) = "${partialPrefix(track.id)}${track.sha256.lowercase().take(12)}$PARTIAL_SUFFIX"

    fun partialPrefix(id: String) = "$id-"
}
