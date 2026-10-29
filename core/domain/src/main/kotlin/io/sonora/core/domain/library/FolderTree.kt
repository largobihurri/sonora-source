package io.sonora.core.domain.library

import io.sonora.core.domain.model.FolderContents
import io.sonora.core.domain.model.FolderEntry
import io.sonora.core.domain.model.FolderRef
import io.sonora.core.domain.model.Track

/**
 * Folder browsing over the index: the tree is implied by the tracks' folder paths (a directory exists in the
 * library when it or a directory below it holds a track), so it needs no table of its own.
 */
object FolderTree {

    /** Whether [folderPath] is [folder] itself or lies below it (a sibling sharing a name prefix does not). */
    fun contains(folder: FolderRef, folderPath: String?): Boolean =
        folderPath != null && (folderPath == folder.path || folderPath.startsWith(folder.path + "/"))

    /**
     * The directories one level below [folder], by name, from [trackCounts] (directory path -> number of tracks
     * directly in it, for directories of [folder]'s library folder); each count includes everything below it.
     */
    fun subfolders(folder: FolderRef, trackCounts: Map<String, Int>): List<FolderEntry> {
        val prefix = folder.path + "/"
        val counts = LinkedHashMap<String, Int>()
        for ((path, count) in trackCounts) {
            if (!path.startsWith(prefix)) continue
            val name = path.substring(prefix.length).substringBefore('/')
            if (name.isNotEmpty()) counts[name] = (counts[name] ?: 0) + count
        }
        return counts.entries
            .sortedWith(compareBy<Map.Entry<String, Int>, String>(String.CASE_INSENSITIVE_ORDER) { it.key }.thenBy { it.key })
            .map { (name, count) -> FolderEntry(folder.child(name), count) }
    }

    /** A folder screen: [folder]'s subfolders from [trackCounts] and its [ownTracks] in play order. */
    fun contents(folder: FolderRef, trackCounts: Map<String, Int>, ownTracks: List<Track>) =
        FolderContents(folder, subfolders(folder, trackCounts), playOrder(ownTracks))

    /**
     * The order a folder plays in: folder by folder (path segments compared case-insensitively, so a folder's own
     * tracks come before its subfolders' and `Rock` before `Rock Classics`), then album, disc, track number and
     * title, with the URI as the final tie-break so the order is stable.
     */
    fun playOrder(tracks: List<Track>): List<Track> = tracks.sortedWith(trackOrder)

    /** Compares '/'-separated paths segment by segment; a path sorts before the paths below it. */
    val pathOrder: Comparator<String> = Comparator { a, b ->
        val left = a.split('/')
        val right = b.split('/')
        for (i in 0 until minOf(left.size, right.size)) {
            val byName = String.CASE_INSENSITIVE_ORDER.compare(left[i], right[i]).takeIf { it != 0 }
                ?: left[i].compareTo(right[i])
            if (byName != 0) return@Comparator byName
        }
        left.size.compareTo(right.size)
    }

    private val trackOrder: Comparator<Track> =
        compareBy<Track, String?>(nullsFirst(pathOrder)) { it.folderPath }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.album }
            .thenBy { it.discNumber ?: 0 }
            .thenBy { it.trackNumber ?: Int.MAX_VALUE }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            .thenBy { it.uri }
}
