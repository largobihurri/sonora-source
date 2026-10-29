package io.sonora.core.domain.library.duplicates

/** A tagged copy of a song, a 16-bit/44.1 kHz FLAC unless told otherwise. */
internal fun copy(
    name: String,
    title: String? = "Song",
    artist: String? = "Band",
    durationMs: Long = 200_000,
    sizeBytes: Long = name.hashCode().toLong() and 0xFFFFFF,
    lastModifiedMs: Long = 1_000,
    format: String? = "FLAC",
    bitrateKbps: Int? = 900,
    sampleRateHz: Int? = 44_100,
    bitsPerSample: Int? = 16,
    tagsFilled: Int = 5,
    hasArtwork: Boolean = true,
    dateAddedSec: Long = 100,
    hash: String? = null,
) = DuplicateCopy(
    uri = "content://music/$name",
    title = title ?: name,
    artist = artist ?: "Unknown Artist",
    tagTitle = title,
    tagArtist = artist,
    durationMs = durationMs,
    sizeBytes = sizeBytes,
    lastModifiedMs = lastModifiedMs,
    format = format,
    bitrateKbps = bitrateKbps,
    sampleRateHz = sampleRateHz,
    bitsPerSample = bitsPerSample,
    channelCount = 2,
    folderPath = "Music/$name",
    fileName = "$name.flac",
    hasArtwork = hasArtwork,
    tagsFilled = tagsFilled,
    dateAddedSec = dateAddedSec,
    contentHash = hash,
)

/** A lossy copy: an MP3 at [kbps]. */
internal fun mp3(name: String, kbps: Int = 320, title: String? = "Song", artist: String? = "Band", durationMs: Long = 200_000) =
    copy(name, title, artist, durationMs, format = "MP3", bitrateKbps = kbps, bitsPerSample = null)
