package io.sonora.core.domain.sort

import io.sonora.core.domain.conversion.batch.HistorySection
import io.sonora.core.domain.library.AlbumGrouping
import io.sonora.core.domain.library.ConvertedMusicSection
import io.sonora.core.domain.model.Album
import io.sonora.core.domain.model.Artist
import io.sonora.core.domain.model.FolderContents
import io.sonora.core.domain.model.FolderEntry
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.playlist.PlaylistItem
import java.text.CollationKey
import java.text.Collator
import java.util.Locale

/** One block of Library > Converted: a date [section] with its header, or `null` for one list without headers. */
data class ConvertedGroup(val section: HistorySection?, val tracks: List<Track>)

/**
 * The library's list sorts. Text compares by the locale's collation ignoring case and accents; unknown values (blank
 * text, the "Unknown Artist" / "Unknown Album" placeholders, no year, no date, no duration) go last in both
 * directions. The direction applies to the chosen key only: ties are broken in ascending order (artist, album, disc,
 * track number, title) and finally by id, so the order is stable. Pure; call it off the main thread for big lists.
 */
object LibrarySort {

    fun tracks(tracks: List<Track>, order: SortOrder, locale: Locale): List<Track> =
        tracks.sortedWith(trackComparator(order, TextCollation(locale)))

    fun albums(albums: List<Album>, order: SortOrder, locale: Locale): List<Album> =
        albums.sortedWith(albumComparator(order, TextCollation(locale)))

    fun artists(artists: List<Artist>, order: SortOrder, locale: Locale): List<Artist> =
        artists.sortedWith(artistComparator(order, TextCollation(locale)))

    /**
     * Subfolders stay first, by name (in the chosen direction when sorting by name, else A to Z); the folder's own songs
     * follow [order], [SortKey.NAME] meaning their title.
     */
    fun folder(contents: FolderContents, order: SortOrder, locale: Locale): FolderContents {
        val text = TextCollation(locale)
        val folderOrder = Comparator<FolderEntry> { a, b ->
            (if (order.key == SortKey.NAME && order.descending) -1 else 1) * compareValues(text.key(a.folder.name), text.key(b.folder.name))
        }.thenBy { it.folder.name }.thenBy { it.folder.path }
        val songOrder = if (order.key == SortKey.NAME) order.copy(key = SortKey.TITLE) else order
        return contents.copy(
            subfolders = contents.subfolders.sortedWith(folderOrder),
            tracks = contents.tracks.sortedWith(trackComparator(songOrder, text)),
        )
    }

    /**
     * [SortKey.PLAYLIST_ORDER] keeps the user's order. Any other key sorts the available songs (the same song added twice
     * keeps its playlist order) and leaves the unavailable ones last, in playlist order.
     */
    fun playlist(items: List<PlaylistItem>, order: SortOrder, locale: Locale): List<PlaylistItem> {
        if (order.key == SortKey.PLAYLIST_ORDER) return items
        val songs = trackComparator(order, TextCollation(locale))
        val available = items.mapNotNull { item -> item.track?.let { track -> item to track } }
        val unavailable = items.filter { it.track == null }
        return available.sortedWith(compareBy(songs) { it.second }).map { it.first } + unavailable
    }

    /**
     * Library > Converted. By date converted the date sections stay: newest first as observed, or oldest first with
     * sections and batches reversed (each batch keeps its own order). Any other key gives one list without headers.
     */
    fun converted(sections: List<ConvertedMusicSection>, order: SortOrder, locale: Locale): List<ConvertedGroup> {
        if (order.key == SortKey.DATE_CONVERTED) {
            val ordered = if (order.descending) {
                sections.map { ConvertedGroup(it.section, it.tracks) }
            } else {
                sections.reversed().map { section -> ConvertedGroup(section.section, section.batches.reversed().flatMap { it.tracks }) }
            }
            return ordered.filter { it.tracks.isNotEmpty() }
        }
        val all = sections.flatMap { it.tracks }
        return if (all.isEmpty()) emptyList() else listOf(ConvertedGroup(null, tracks(all, order, locale)))
    }

    /** The songs' order for [order]; keys that are not about the songs themselves keep the given order. */
    fun trackComparator(order: SortOrder, text: TextCollation): Comparator<Track> {
        val title = ascending<Track, CollationKey> { text.known(it.title) }
        val artist = ascending<Track, CollationKey> { text.known(it.artist) }
        val album = ascending<Track, CollationKey> { text.known(it.album) }
        val albumArtist = ascending<Track, CollationKey> { text.known(it.albumArtist?.takeIf(String::isNotBlank) ?: it.artist) }
        val disc = compareBy<Track> { it.discNumber ?: 0 }
        val number = ascending<Track, Int> { it.trackNumber }
        val inAlbum = disc.then(number).then(title)
        val byArtist = artist.then(album).then(inAlbum)
        val d = order.descending
        val chosen: Comparator<Track> = when (order.key) {
            SortKey.TITLE, SortKey.NAME -> by<Track, CollationKey>(d) { text.known(it.title) }.then(artist).then(album).then(disc).then(number)
            SortKey.ARTIST -> by<Track, CollationKey>(d) { text.known(it.artist) }.then(album).then(inAlbum)
            SortKey.ALBUM -> by<Track, CollationKey>(d) { text.known(it.album) }.then(albumArtist).then(inAlbum)
            SortKey.GENRE -> by<Track, CollationKey>(d) { text.known(it.genre) }.then(byArtist)
            SortKey.RELEASE_DATE -> by<Track, Int>(d) { it.year?.takeIf { year -> year > 0 } }.then(byArtist)
            SortKey.DATE_ADDED -> by<Track, Long>(d) { it.dateAddedSec.takeIf { sec -> sec > 0 } }.then(byArtist)
            SortKey.DURATION -> by<Track, Long>(d) { it.durationMs.takeIf { ms -> ms > 0 } }.then(title).then(artist).then(album)
            SortKey.PLAYLIST_ORDER, SortKey.DATE_CONVERTED -> return Comparator { _, _ -> 0 }
        }
        return chosen.thenBy { it.id }
    }

    fun albumComparator(order: SortOrder, text: TextCollation): Comparator<Album> {
        val title = ascending<Album, CollationKey> { text.known(it.title) }
        val artist = ascending<Album, CollationKey> { text.known(it.artist) }
        val d = order.descending
        val chosen: Comparator<Album> = when (order.key) {
            SortKey.ARTIST -> by<Album, CollationKey>(d) { text.known(it.artist) }.then(title)
            SortKey.RELEASE_DATE -> by<Album, Int>(d) { it.year?.takeIf { year -> year > 0 } }.then(artist).then(title)
            SortKey.DATE_ADDED -> by<Album, Long>(d) { it.dateAddedSec.takeIf { sec -> sec > 0 } }.then(artist).then(title)
            else -> by<Album, CollationKey>(d) { text.known(it.title) }.then(artist)
        }
        return chosen.thenBy { it.id }
    }

    fun artistComparator(order: SortOrder, text: TextCollation): Comparator<Artist> {
        val name = ascending<Artist, CollationKey> { text.known(it.name) }
        val chosen: Comparator<Artist> = when (order.key) {
            SortKey.DATE_ADDED -> by<Artist, Long>(order.descending) { it.dateAddedSec.takeIf { sec -> sec > 0 } }.then(name)
            else -> by<Artist, CollationKey>(order.descending) { text.known(it.name) }
        }
        return chosen.thenBy { it.id }.thenBy { it.name }
    }

    /** By [selector] in the given direction, `null` (unknown) last either way. */
    private fun <T, K : Comparable<K>> by(descending: Boolean, selector: (T) -> K?): Comparator<T> = Comparator { a, b ->
        val x = selector(a)
        val y = selector(b)
        when {
            x == null -> if (y == null) 0 else 1
            y == null -> -1
            descending -> y.compareTo(x)
            else -> x.compareTo(y)
        }
    }

    private fun <T, K : Comparable<K>> ascending(selector: (T) -> K?): Comparator<T> = by(false, selector)
}

/**
 * Locale collation at primary strength (case and accents ignored), with each text's key computed once per sort.
 * Not thread-safe: make one per sort.
 */
class TextCollation(locale: Locale) {
    private val collator = Collator.getInstance(locale).apply {
        strength = Collator.PRIMARY
        decomposition = Collator.CANONICAL_DECOMPOSITION
    }
    private val keys = HashMap<String, CollationKey>()

    fun key(text: String): CollationKey = keys.getOrPut(text) { collator.getCollationKey(text) }

    /** [text]'s key, or `null` when it is unknown: blank or one of the library's "Unknown" placeholders. */
    fun known(text: String?): CollationKey? {
        val trimmed = text?.trim()
        if (trimmed.isNullOrEmpty() || trimmed == AlbumGrouping.UNKNOWN_ARTIST || trimmed == AlbumGrouping.UNKNOWN_ALBUM) return null
        return key(trimmed)
    }
}
