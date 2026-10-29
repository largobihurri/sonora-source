package io.sonora.core.watchsync.protocol

import io.sonora.core.watchsync.ManifestTrack

/** Splits the watch's song list into [SyncMessage.Contents] pages that each fit a control frame. */
object ContentsPaging {
    /**
     * [tracks] as pages whose JSON is at most [maxPageBytes] each, in order; every page but the last has
     * [SyncMessage.Contents.more] set. An empty list is one empty page, and a single song bigger than the budget (not
     * possible with valid tags) still gets a page of its own rather than being lost.
     */
    fun pages(tracks: List<ManifestTrack>, maxPageBytes: Int): List<SyncMessage.Contents> {
        val overhead = SyncMessageCodec.encode(SyncMessage.Contents(emptyList(), more = true)).size
        require(maxPageBytes > overhead) { "A page budget of $maxPageBytes bytes cannot hold a page" }
        val pages = ArrayList<List<ManifestTrack>>()
        var current = ArrayList<ManifestTrack>()
        var size = overhead
        for (track in tracks) {
            // The track's JSON, plus the comma that separates it from the one before.
            val bytes = SyncMessageCodec.json.encodeToString(ManifestTrack.serializer(), track).toByteArray(Charsets.UTF_8).size + 1
            if (current.isNotEmpty() && size + bytes > maxPageBytes) {
                pages += current
                current = ArrayList()
                size = overhead
            }
            current += track
            size += bytes
        }
        pages += current
        return pages.mapIndexed { index, page -> SyncMessage.Contents(page, more = index < pages.lastIndex) }
    }
}
