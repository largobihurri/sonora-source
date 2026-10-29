package io.sonora.core.domain.archive

import io.sonora.core.domain.conversion.batch.BatchNaming
import io.sonora.core.domain.model.Track
import java.net.URI
import java.net.URISyntaxException

/** Where everything goes inside an archive: one [folder] holding the manifest and the music, in manifest order. */
data class ArchiveLayout(val folder: String, val manifest: ArchiveManifest) {
    val manifestPath: String get() = "$folder/${ArchiveManifest.FILE_NAME}"

    /** The path inside the archive of the file of manifest entry [index]. */
    fun entryPath(index: Int): String = "$folder/${manifest.entries[index].file}"
}

/**
 * Names inside a Sonora archive. They must survive any unpacker on any system: no separators, nothing FAT or Windows
 * rejects (see [BatchNaming.fatSafe], plus device names like `CON`), unique ignoring case, and short enough that
 * folder plus file stay well within Windows' 260-character paths once extracted.
 */
object ArchiveNaming {

    const val MAX_FOLDER_BYTES: Int = 64
    const val MAX_FILE_BYTES: Int = 120

    /** The folder's name when the album or playlist has none usable. */
    const val DEFAULT_NAME: String = "Sonora"

    private const val UNTITLED = "Track"
    private const val UNKNOWN_EXTENSION = "audio"
    private val RESERVED = setOf("con", "prn", "aux", "nul") + (1..9).flatMap { listOf("com$it", "lpt$it") }

    private val EXTENSION_BY_MIME = mapOf(
        "audio/mpeg" to "mp3", "audio/mp3" to "mp3", "audio/flac" to "flac", "audio/x-flac" to "flac",
        "audio/mp4" to "m4a", "audio/x-m4a" to "m4a", "audio/aac" to "aac", "audio/wav" to "wav", "audio/x-wav" to "wav",
        "audio/vnd.wave" to "wav", "audio/aiff" to "aiff", "audio/x-aiff" to "aiff", "audio/ogg" to "ogg",
        "audio/opus" to "opus", "audio/x-ms-wma" to "wma", "audio/x-matroska" to "mka",
    )

    /** The archive's folder, named after the album or playlist [name]. */
    fun folderName(name: String): String {
        val trimmed = name.trim().trim('.', ' ')
        if (trimmed.isEmpty()) return DEFAULT_NAME
        return notReserved(BatchNaming.fatSafe(trimmed, MAX_FOLDER_BYTES))
    }

    /** The archive file's own name, e.g. "Road Trip.zip". */
    fun archiveFileName(name: String): String = folderName(name) + ".zip"

    /**
     * The file names of [tracks] in order: "01 - Artist - Title.ext" for albums and playlists (as many digits as the
     * count needs), "Artist - Title.ext" for a single song.
     */
    fun fileNames(kind: ArchiveKind, tracks: List<Track>): List<String> {
        val width = maxOf(2, tracks.size.toString().length)
        val taken = mutableListOf<String>()
        tracks.forEachIndexed { index, track ->
            val number = if (kind == ArchiveKind.SONG) "" else "${(index + 1).toString().padStart(width, '0')} - "
            val title = track.title.trim().ifEmpty { UNTITLED }
            val artist = track.artist.trim()
            val stem = number + if (artist.isEmpty()) title else "$artist - $title"
            val safe = BatchNaming.fatSafe("${notReserved(stem.trimStart('.', ' '))}.${extensionOf(track)}", MAX_FILE_BYTES)
            taken += BatchNaming.uniqueFileName(safe, taken, MAX_FILE_BYTES)
        }
        return taken
    }

    /** Where [tracks] go in a [kind] archive called [name], and the manifest describing them. */
    fun layout(kind: ArchiveKind, name: String, tracks: List<Track>): ArchiveLayout {
        val entries = fileNames(kind, tracks).zip(tracks) { file, track ->
            ArchiveEntry(
                file = file,
                title = track.title,
                artist = track.artist,
                album = track.album,
                albumArtist = track.albumArtist,
                trackNumber = track.trackNumber,
                discNumber = track.discNumber,
                durationMs = track.durationMs,
            )
        }
        return ArchiveLayout(folderName(name), ArchiveManifest(kind, name.trim(), entries))
    }

    /** The extension [track]'s file has (lowercase), else one for its MIME type, else "audio". */
    fun extensionOf(track: Track): String {
        val fileName = decodedPath(track.uri).substringAfterLast('/').substringAfterLast(':')
        val dot = fileName.lastIndexOf('.')
        val fromFile = if (dot > 0) fileName.substring(dot + 1).lowercase() else ""
        if (fromFile.length in 1..5 && fromFile.all { it in 'a'..'z' || it in '0'..'9' }) return fromFile
        return EXTENSION_BY_MIME[track.mimeType.lowercase()] ?: UNKNOWN_EXTENSION
    }

    /** [uri]'s path, percent-decoded ("primary%3AMusic%2Fa.flac" is "primary:Music/a.flac"), or [uri] itself. */
    private fun decodedPath(uri: String): String = try {
        URI(uri).path ?: uri
    } catch (_: URISyntaxException) {
        uri
    }

    /** Windows cannot create `CON`, `NUL`, `COM1`… with any extension: such names get a trailing `_`. */
    private fun notReserved(name: String): String {
        val stem = name.substringBefore('.')
        return if (stem.lowercase().trimEnd() in RESERVED) stem + "_" + name.removePrefix(stem) else name
    }
}
