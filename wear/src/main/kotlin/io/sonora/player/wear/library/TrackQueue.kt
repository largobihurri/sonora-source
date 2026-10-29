package io.sonora.player.wear.library

/** What to play after a tap in the list: the whole list as the queue, starting at the tapped song. */
data class TrackQueue(val tracks: List<WatchTrack>, val startIndex: Int) {
    companion object {
        /** The queue of [tracks] starting at the song [trackId]; at the first one if that song is no longer there. */
        fun startingAt(tracks: List<WatchTrack>, trackId: String): TrackQueue =
            TrackQueue(tracks, tracks.indexOfFirst { it.id == trackId }.coerceAtLeast(0))
    }
}
