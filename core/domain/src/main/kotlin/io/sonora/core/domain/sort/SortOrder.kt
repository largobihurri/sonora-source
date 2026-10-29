package io.sonora.core.domain.sort

/** Which way a list runs. Names are persisted: never rename one. */
enum class SortDirection { ASCENDING, DESCENDING }

/** What a list can be sorted by. Names are persisted: never rename one. */
enum class SortKey(
    /** The direction a list starts in when the user picks this key: newest first for dates. */
    val naturalDirection: SortDirection = SortDirection.ASCENDING,
    /** Compared as text (locale collation), so an index of letters can follow it. */
    val isAlphabetical: Boolean = false,
) {
    /** The user's own order of a playlist; it has no direction. */
    PLAYLIST_ORDER,
    TITLE(isAlphabetical = true),
    NAME(isAlphabetical = true),
    ARTIST(isAlphabetical = true),
    ALBUM(isAlphabetical = true),
    GENRE(isAlphabetical = true),

    /** The metadata year. */
    RELEASE_DATE(SortDirection.DESCENDING),

    /** When the file entered the library. */
    DATE_ADDED(SortDirection.DESCENDING),

    /** When the converter made the file (Library > Converted). */
    DATE_CONVERTED(SortDirection.DESCENDING),
    DURATION,
    ;

    /** Whether ascending and descending mean anything for this key. */
    val hasDirection: Boolean get() = this != PLAYLIST_ORDER
}

/** A list's sort: a [key] and its [direction]; ties are always broken in ascending order. */
data class SortOrder(val key: SortKey, val direction: SortDirection = key.naturalDirection) {
    val descending: Boolean get() = direction == SortDirection.DESCENDING

    /** This order with [key]: the same order when it is already the key, else [key] in its natural direction. */
    fun withKey(key: SortKey): SortOrder = if (key == this.key) this else SortOrder(key)

    fun withDirection(direction: SortDirection): SortOrder = copy(direction = direction)
}

/** The sortable lists, each with the keys its menu offers (in menu order) and the order it starts in. */
enum class SortList(val keys: List<SortKey>, val default: SortOrder) {
    SONGS(SONG_KEYS, SortOrder(SortKey.TITLE)),
    ALBUMS(listOf(SortKey.TITLE, SortKey.ARTIST, SortKey.RELEASE_DATE, SortKey.DATE_ADDED), SortOrder(SortKey.TITLE)),
    ARTISTS(listOf(SortKey.NAME, SortKey.DATE_ADDED), SortOrder(SortKey.NAME)),

    /** One folder's contents: subfolders first, then its songs. */
    FOLDER(listOf(SortKey.NAME, SortKey.ARTIST, SortKey.DATE_ADDED, SortKey.DURATION), SortOrder(SortKey.NAME)),

    /** Converted files are not library entries, so they have no date added; the date converted stands for it. */
    CONVERTED(listOf(SortKey.DATE_CONVERTED) + SONG_KEYS - SortKey.DATE_ADDED, SortOrder(SortKey.DATE_CONVERTED)),
    PLAYLIST(listOf(SortKey.PLAYLIST_ORDER) + SONG_KEYS, SortOrder(SortKey.PLAYLIST_ORDER)),
    ;

    /** [order] when this list offers it (a key without direction always ascending); otherwise the [default]. */
    fun valid(order: SortOrder?): SortOrder = when {
        order == null || order.key !in keys -> default
        !order.key.hasDirection -> order.withDirection(SortDirection.ASCENDING)
        else -> order
    }
}

private val SONG_KEYS = listOf(
    SortKey.TITLE, SortKey.ARTIST, SortKey.ALBUM, SortKey.GENRE, SortKey.RELEASE_DATE, SortKey.DATE_ADDED, SortKey.DURATION,
)

/** Where a sort is remembered: one per list, and one per playlist ([playlistId]). */
data class SortTarget(val list: SortList, val playlistId: Long? = null)

/** [items] sorted by [order]. */
data class Sorted<T>(val order: SortOrder, val items: T)
