package io.sonora.feature.cdjexport

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Tracks another screen asked to export as one playlist called [name]; [trackUris] are library document URIs. */
data class ExportPlaylistRequest(val name: String, val trackUris: List<String>)

/**
 * "Export as Rekordbox playlist" from other screens (an album, a playlist, a selection): they [request] it, the app
 * shell shows the Export tab while one is [pending], and the export screen lists the tracks and [consume]s it. The
 * user still picks the stick and confirms the export there; tracks that cannot be read or do not fit the stick's
 * player are listed as rejected, like picked files.
 *
 * Only the latest request is kept: a newer one replaces one not consumed yet. Thread-safe.
 */
@Singleton
class ExportRequests @Inject constructor() {
    private val _pending = MutableStateFlow<ExportPlaylistRequest?>(null)

    /** The request the export screen has not taken yet, or `null`. */
    val pending: StateFlow<ExportPlaylistRequest?> = _pending.asStateFlow()

    /**
     * Asks to export [trackUris] (library `Track.uri`s, in playlist order) as one playlist called [name]. A track listed
     * twice is kept once. Ignored, returning `false`, when [name] is blank or there are no tracks: the pending request,
     * if any, stays.
     */
    fun request(name: String, trackUris: List<String>): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trackUris.isEmpty()) return false
        _pending.value = ExportPlaylistRequest(trimmed, trackUris.distinct())
        return true
    }

    /** Takes [request] off [pending]; `false` when it is no longer pending (taken already, or replaced by a newer one). */
    fun consume(request: ExportPlaylistRequest): Boolean = _pending.compareAndSet(request, null)
}
