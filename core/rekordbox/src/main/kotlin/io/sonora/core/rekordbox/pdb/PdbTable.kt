package io.sonora.core.rekordbox.pdb

/**
 * The 20 tables of `export.pdb`, in the order of the table directory on page 0; [id] is the page type stored in the
 * directory and in every page header of the table. Table `T` always starts with its index page `2T+1`, and its first
 * data page, if any, is `2T+2`.
 */
enum class PdbTable(val id: Int) {
    TRACKS(0),
    GENRES(1),
    ARTISTS(2),
    ALBUMS(3),
    LABELS(4),
    KEYS(5),
    COLORS(6),
    PLAYLIST_TREE(7),
    PLAYLIST_ENTRIES(8),
    UNKNOWN_9(9),
    UNKNOWN_10(10),
    HISTORY_PLAYLISTS(11),
    HISTORY_ENTRIES(12),
    ARTWORK(13),
    UNKNOWN_14(14),
    UNKNOWN_15(15),
    COLUMNS(16),
    MENU(17),
    UNKNOWN_18(18),
    HISTORY(19),
    ;

    /** The index page every table starts with. */
    val indexPage: Int get() = 2 * id + 1

    /** The page reserved for the first data page (left as a zero page while the table is empty). */
    val firstDataPage: Int get() = 2 * id + 2
}
