package io.sonora.core.domain.library.duplicates

import java.text.Collator
import java.util.Locale

/**
 * Groups the library's files into duplicates: files with the same [DuplicateCopy.contentHash] (identical bytes,
 * whatever their tags), and files with the same [SongMatch.key] whose lengths are within the tolerance of the
 * shortest one of their run (so a chain of small differences never adds up). Both links join one group, so the
 * identical copies of a song and its re-encodes are compared together.
 */
object DuplicateGrouping {

    /**
     * Every group of two or more copies, best copy first, by the suggested copy's title. [songKey] gives a copy's
     * [SongMatch.key] (the finder passes the ones it has stored).
     */
    fun group(copies: List<DuplicateCopy>, songKey: (DuplicateCopy) -> String? = SongMatch::key): List<DuplicateGroup> {
        val files = copies.distinctBy { it.uri }
        val links = Links(files.size)
        files.indices.filter { files[it].contentHash != null }
            .groupBy { files[it].contentHash }
            .values.forEach { same -> same.drop(1).forEach { links.join(same.first(), it) } }
        files.indices.mapNotNull { index -> songKey(files[index])?.let { it to index } }
            .groupBy({ it.first }, { it.second })
            .values.forEach { song -> joinByLength(song, files, links) }
        val collator = Collator.getInstance(Locale.ROOT).apply { strength = Collator.SECONDARY }
        return files.indices.groupBy(links::root).values
            .filter { it.size > 1 }
            .map { members -> DuplicateGroup(members.map(files::get).sortedWith(DuplicateQuality.BEST_FIRST)) }
            .sortedWith(compareBy(collator) { group: DuplicateGroup -> group.suggested.title }.thenBy { it.key })
    }

    /** Runs of one song's copies, by length, each within the tolerance of its shortest copy. */
    private fun joinByLength(song: List<Int>, files: List<DuplicateCopy>, links: Links) {
        var first: Int? = null
        for (index in song.sortedBy { files[it].durationMs }) {
            val start = first
            if (start != null && SongMatch.sameLength(files[start].durationMs, files[index].durationMs)) {
                links.join(start, index)
            } else {
                first = index.takeIf { files[it].durationMs > 0 }
            }
        }
    }

    /** Union-find over the files' indices. */
    private class Links(size: Int) {
        private val parent = IntArray(size) { it }

        fun root(index: Int): Int {
            var node = index
            while (parent[node] != node) {
                parent[node] = parent[parent[node]]
                node = parent[node]
            }
            return node
        }

        fun join(a: Int, b: Int) {
            parent[root(b)] = root(a)
        }
    }
}
