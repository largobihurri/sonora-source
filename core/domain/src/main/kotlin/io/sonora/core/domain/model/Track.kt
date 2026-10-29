package io.sonora.core.domain.model

/** A playable audio file from the device library. URIs are kept as strings to stay Android-free. */
data class Track(
    val id: Long,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String?,
    val durationMs: Long,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
    val albumId: Long,
    val artistId: Long = 0L,
    val artworkUri: String?,
    val mimeType: String,
    val sizeBytes: Long,
    /** When the file entered the library (first indexed), seconds since epoch. */
    val dateAddedSec: Long = 0L,
    val genre: String? = null,
    /** Containing directory, '/'-separated, from the library folder's display path; `null` when unknown. */
    val folderPath: String? = null,
    val sampleRateHz: Int? = null,
    val bitsPerSample: Int? = null,
    val channelCount: Int? = null,
    /** Short codec name read by the scanner (`FLAC`, `ALAC`, `AAC`, `MP3`, `WAV`, `AIFF`, `Vorbis`); `null` when unknown. */
    val codec: String? = null,
    val bitrateKbps: Int? = null,
    /** Tagged release date: `yyyy`, `yyyy-MM` or `yyyy-MM-dd`. */
    val releaseDate: String? = null,
    val copyright: String? = null,
)
