package io.sonora.player.wear.library

import kotlinx.coroutines.flow.StateFlow

/**
 * The songs on the watch, in list order. The seam between the screens and where the songs are kept: [FilesTrackRepository]
 * scans the music folder, which is also where the phone's transfer (`WatchSyncReceiver`) writes, atomically, so the list
 * only needs a [refresh] when a transfer ends.
 */
interface TrackRepository {
    /** The songs in list order ([TrackOrder]); `null` until the first load has ended, so a screen can tell loading from empty. */
    val tracks: StateFlow<List<WatchTrack>?>

    /** Loads the songs again (a folder scan, or nothing at all for a library that announces its own changes). */
    suspend fun refresh()
}

/** The songs, loading the list first if nothing has (a caller may ask before the app was ever opened). */
suspend fun TrackRepository.loaded(): List<WatchTrack> {
    if (tracks.value == null) refresh()
    return tracks.value.orEmpty()
}
