package io.sonora.core.domain.library.duplicates

import io.sonora.core.domain.library.AudioFormats

/**
 * Which copy of a song to keep: lossless before lossy, then the deeper bit depth, the higher sample rate and the
 * higher bitrate. Between copies of equal quality: more of the common tags, an embedded picture, the older copy (the
 * one the library had first), then the file's URI so the order never depends on the input.
 */
object DuplicateQuality {
    fun isLossless(format: String?): Boolean = AudioFormats.isLossless(format)

    val BEST_FIRST: Comparator<DuplicateCopy> = compareByDescending<DuplicateCopy> { isLossless(it.format) }
        .thenByDescending { it.bitsPerSample ?: 0 }
        .thenByDescending { it.sampleRateHz ?: 0 }
        .thenByDescending { it.bitrateKbps ?: 0 }
        .thenByDescending { it.tagsFilled }
        .thenByDescending { it.hasArtwork }
        .thenBy { it.dateAddedSec }
        .thenBy { it.uri }
}
