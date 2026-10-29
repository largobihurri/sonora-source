package io.sonora.core.domain.model

data class Artist(
    val id: Long,
    val name: String,
    val albumCount: Int,
    val trackCount: Int,
    /** Latest date added among the artist's tracks, seconds since epoch; 0 when unknown. */
    val dateAddedSec: Long = 0L,
    /**
     * Up to [ArtistCovers.MAX] covers of the artist's albums, one per album and image, the most played album first
     * (see [ArtistCovers]); empty when no album has artwork.
     */
    val artworkUris: List<String> = emptyList(),
)

/** One album of an artist as a cover candidate: the album's artwork, its songs' total plays and its date added. */
data class AlbumCover(val albumId: Long, val artworkUri: String?, val plays: Int, val dateAddedSec: Long)

/** Which album covers an artist shows, front first. */
object ArtistCovers {
    const val MAX = 3

    /**
     * Albums with artwork, ordered by plays (most first), then newest, then album id; one cover per album and per
     * image (albums sharing a folder image show it once), at most [MAX].
     */
    fun pick(albums: Collection<AlbumCover>): List<String> =
        albums.asSequence()
            .filter { !it.artworkUri.isNullOrBlank() }
            .distinctBy { it.albumId }
            .sortedWith(compareByDescending<AlbumCover> { it.plays }.thenByDescending { it.dateAddedSec }.thenBy { it.albumId })
            .mapNotNull { it.artworkUri }
            .distinct()
            .take(MAX)
            .toList()
}
