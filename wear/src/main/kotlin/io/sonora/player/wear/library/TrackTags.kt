package io.sonora.player.wear.library

/** The tags a metadata reader found, as the strings it returned (`null` when absent). */
data class RawTags(
    val title: String?,
    val artist: String?,
    val albumArtist: String?,
    val album: String?,
    /** `3` or `3/12`, whichever the file's tag holds. */
    val trackNumber: String?,
    val durationMs: String?,
)

/** The song these tags and this file make: a missing title falls back to the file name, a missing artist to the album's. */
fun RawTags.toWatchTrack(id: String, uri: String, fileName: String): WatchTrack = WatchTrack(
    id = id,
    uri = uri,
    title = title.cleaned() ?: TrackFiles.titleFromFileName(fileName),
    artist = artist.cleaned() ?: albumArtist.cleaned(),
    album = album.cleaned(),
    trackNumber = parseTrackNumber(trackNumber),
    durationMs = durationMs?.trim()?.toLongOrNull()?.takeIf { it > 0 },
)

/** The track number of `3`, `03` or `3/12`; `null` for anything else, and for 0. */
fun parseTrackNumber(raw: String?): Int? =
    raw?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 }

private fun String?.cleaned(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
