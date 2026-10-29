package io.sonora.core.domain.model

data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val year: Int?,
    val trackCount: Int,
    val artworkUri: String?,
    val artistId: Long = 0L,
    /** Latest date added among the album's tracks, seconds since epoch. */
    val dateAddedSec: Long = 0L,
    /** Tagged as a compilation or detected as one (several artists, no album artist). */
    val isCompilation: Boolean = false,
)
