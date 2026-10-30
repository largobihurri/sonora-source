package io.sonora.core.domain.library

import io.sonora.core.domain.model.Album

/**
 * The visual collection's "Hide Albums Without Artwork" setting: which albums it lists, and where its centre lands when that
 * list changes. Visual collection only; the Albums list always lists every album.
 */
object VisualCollectionArtwork {

    /**
     * [albums] in their order, without those that have no artwork when [hideWithoutArtwork]. An album has artwork when
     * the scan resolved one for it (embedded in its files or a folder image), so nothing is loaded to decide.
     */
    fun albums(albums: List<Album>, hideWithoutArtwork: Boolean): List<Album> =
        if (hideWithoutArtwork) albums.filter { !it.artworkUri.isNullOrBlank() } else albums

    /**
     * The centre in [after] of a visual collection that was centred on [before]`[centre]`: that same item when it is still
     * listed, else its nearest neighbour in [before] that is (the following one on a tie). With nothing in common the
     * centre keeps its place, within [after]; an empty [after] centres on 0.
     */
    fun <K> keptCentre(before: List<K>, centre: Int, after: List<K>): Int {
        if (after.isEmpty()) return 0
        if (before.isEmpty()) return centre.coerceIn(0, after.lastIndex)
        val from = centre.coerceIn(0, before.lastIndex)
        val places = HashMap<K, Int>()
        after.forEachIndexed { index, key -> places.putIfAbsent(key, index) }
        for (distance in 0..maxOf(from, before.lastIndex - from)) {
            for (place in intArrayOf(from + distance, from - distance)) {
                if (place in before.indices) places[before[place]]?.let { return it }
            }
        }
        return from.coerceAtMost(after.lastIndex)
    }
}
