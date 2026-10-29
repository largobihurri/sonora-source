package io.sonora.core.domain.library

/** Tag values read from one audio file; `null` means the tag is missing or unreadable. */
data class TrackTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val isCompilation: Boolean = false,
    val durationMs: Long = 0L,
    val sampleRateHz: Int? = null,
    val bitsPerSample: Int? = null,
    val channelCount: Int? = null,
    /** Short codec name: `FLAC`, `ALAC`, `AAC`, `MP3`, `WAV`, `AIFF`, `Vorbis`; `null` when unknown. */
    val codec: String? = null,
    /** Average bitrate in kbps as the header reports it. */
    val bitrateKbps: Int? = null,
    /** The tagged date as far as it is valid: `yyyy`, `yyyy-MM` or `yyyy-MM-dd`. */
    val releaseDate: String? = null,
    val copyright: String? = null,
) {
    companion object {
        /** What a file whose tags could not be read contributes: nothing, so every fallback applies. */
        val UNTAGGED = TrackTags()
    }
}

/** One indexed audio file as the grouping rules see it. */
data class ScannedTrack(
    val documentUri: String,
    /** Containing directory, '/'-separated, starting with the library folder's display path. */
    val folderPath: String,
    val fileName: String,
    val tags: TrackTags,
    /** Cached copy of the file's embedded picture, if it has one. */
    val embeddedArtworkUri: String? = null,
    /** Best cover image found next to the file (cover.jpg and friends), if any. */
    val folderImageUri: String? = null,
)

/** Library-wide resolution of one file: display values, album membership and stable ids. */
data class ResolvedTrack(
    val title: String,
    val artist: String,
    val artistId: Long,
    val album: String,
    val albumId: Long,
    val albumArtist: String,
    val albumArtistId: Long,
    val isCompilation: Boolean,
    val artworkUri: String?,
)
