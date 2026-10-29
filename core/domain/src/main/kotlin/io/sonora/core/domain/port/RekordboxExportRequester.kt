package io.sonora.core.domain.port

/**
 * Opens the CDJ export with songs already queued as one Rekordbox playlist. Bound by the CDJ export feature; until it
 * is, the library offers "Export as Rekordbox Playlist" disabled.
 */
fun interface RekordboxExportRequester {
    /** Queues [trackUris] (library song URIs, in this order) as a Rekordbox playlist named [name]. */
    fun request(name: String, trackUris: List<String>)
}
