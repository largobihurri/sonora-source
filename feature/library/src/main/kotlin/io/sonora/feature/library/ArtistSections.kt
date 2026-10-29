package io.sonora.feature.library

import io.sonora.core.domain.library.marks.Pinned
import io.sonora.core.domain.model.Album
import io.sonora.core.domain.model.Track

/** One block of the artist page's songs. */
internal sealed interface ArtistSection {
    /** The section's songs, top to bottom. */
    val tracks: List<Track>

    /** A one-track release: its song and the release's year. */
    data class Single(val track: Track, val year: Int?)

    /** The artist's one-track releases gathered together; each row shows its own artwork. */
    data class Singles(val entries: List<Single>) : ArtistSection {
        override val tracks: List<Track> = entries.map { it.track }
    }

    /** A release of two or more tracks: a header for [album], then its songs in album order. */
    data class AlbumSongs(val album: Album, override val tracks: List<Track>) : ArtistSection

    /** Songs whose album is unknown (blank, or not among the artist's albums), when there are several. */
    data class OtherSongs(override val tracks: List<Track>) : ArtistSection
}

/** The artist page's songs in sections, and the queue a tap plays: every section flattened top to bottom. */
internal class ArtistSongs(val sections: List<ArtistSection>) {
    val queue: List<Track> = sections.flatMap { it.tracks }

    private val offsets: List<Int> = sections.runningFold(0) { start, section -> start + section.tracks.size }

    /** Where row [row] of section [section] sits in [queue]. */
    fun queueIndex(section: Int, row: Int): Int = offsets[section] + row
}

/**
 * Groups an artist's songs by release, for the artist page:
 * - **Singles** first: the songs of releases holding exactly one track in the library ([Album.trackCount] of 1), in
 *   the order of [albums] (the page's album carousel). A lone song whose album is blank or not among [albums] joins
 *   them at the end.
 * - Then one section per remaining release with any of the artist's songs, in the order of [albums], its songs in
 *   album order (disc, then track number, then title), as the album page lists them. A longer release where the
 *   artist has a single song (a compilation) still gets its own section, so the song is not taken for a single.
 * - Last, when several songs have a blank or unlisted album, one "other songs" section with them in the incoming
 *   order (the repository's, by title).
 *
 * Songs pinned on the artist page sit first within their own section, in pin order (the order [songs] lists them).
 * Releases without any of the artist's songs (an album credited to the artist only as album artist) give nothing.
 */
internal object ArtistSections {
    fun of(albums: List<Album>, songs: Pinned<Track>): ArtistSongs {
        val tracks = songs.items
        val pinRank = HashMap<Long, Int>()
        tracks.forEachIndexed { index, track -> if (songs.isPinned(track.uri)) pinRank.putIfAbsent(track.id, index) }
        fun <T> List<T>.pinnedFirst(track: (T) -> Track) = sortedBy { pinRank[track(it).id] ?: Int.MAX_VALUE }

        val byAlbum = tracks.groupBy { it.albumId }
        val placed = HashSet<Long>()
        val singles = ArrayList<ArtistSection.Single>()
        val albumSections = ArrayList<ArtistSection>()
        for (album in albums) {
            if (album.title.isBlank() || !placed.add(album.id)) continue
            val own = byAlbum[album.id] ?: continue
            if (album.trackCount <= 1 && own.size == 1) {
                singles += ArtistSection.Single(own[0], album.year ?: own[0].year)
            } else {
                albumSections += ArtistSection.AlbumSongs(album, own.sortedWith(AlbumOrder).pinnedFirst { it })
            }
        }
        val loose = tracks.filter { it.albumId !in placed }
        val other = if (loose.size == 1) {
            singles += ArtistSection.Single(loose[0], loose[0].year)
            null
        } else {
            loose.takeIf { it.isNotEmpty() }?.let { ArtistSection.OtherSongs(it) }
        }
        val sections = buildList {
            if (singles.isNotEmpty()) add(ArtistSection.Singles(singles.pinnedFirst { it.track }))
            addAll(albumSections)
            if (other != null) add(other)
        }
        return ArtistSongs(sections)
    }

    /** The album page's order (the repository's `tracksForAlbum`): disc, track number, then title and id. */
    private val AlbumOrder: Comparator<Track> = compareBy<Track> { it.discNumber ?: 0 }
        .thenBy { it.trackNumber ?: Int.MAX_VALUE }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        .thenBy { it.id }
}
