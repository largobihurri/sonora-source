package io.sonora.player.wear.library

/** The order of the song list: by artist, album, track number, title; songs with no artist last. */
object TrackOrder {
    private val comparator: Comparator<WatchTrack> = compareBy<WatchTrack, String?>(nullsLast(String.CASE_INSENSITIVE_ORDER)) { it.artist }
        .thenBy(nullsLast(String.CASE_INSENSITIVE_ORDER)) { it.album }
        .thenBy(nullsLast<Int>()) { it.trackNumber }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        .thenBy { it.id }

    fun sorted(tracks: List<WatchTrack>): List<WatchTrack> = tracks.sortedWith(comparator)
}
