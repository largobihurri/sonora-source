package io.sonora.core.domain.archive

import io.sonora.core.domain.conversion.batch.BatchNaming

/** What extraction does with one archive entry. */
sealed interface EntryVerdict {
    /** An audio file, written at these (safe) folder and file name segments below the destination. */
    data class Audio(val segments: List<String>) : EntryVerdict

    /** A `sonora.json` at the archive's root or in its top folder. */
    data class Manifest(val segments: List<String>) : EntryVerdict

    data class Skipped(val reason: SkipReason) : EntryVerdict
}

enum class SkipReason {
    /** A folder entry: folders are created for the files below them. */
    DIRECTORY,

    /** Absolute, with a parent reference, a drive, control characters or too deep: it could land outside. */
    UNSAFE_PATH,

    /** Anything that is neither music nor the manifest (covers, notes, programs). */
    NOT_AUDIO,
}

/**
 * Path safety for archive entries ("zip slip"): only relative paths whose every segment stays inside the destination
 * are written, and only music and the manifest at that.
 */
object ArchivePaths {

    /** Deepest entry accepted, counting the file: "Artist/Album/CD1/01.flac" is four. */
    const val MAX_DEPTH: Int = 8

    /** Extensions of the audio files extraction writes, lowercase. */
    val AUDIO_EXTENSIONS: Set<String> = setOf(
        "aac", "aif", "aifc", "aiff", "alac", "ape", "dff", "dsf", "flac", "m4a", "m4b", "mka", "mp2", "mp3", "mpc",
        "oga", "ogg", "opus", "wav", "wma", "wv",
    )

    fun isAudio(fileName: String): Boolean {
        val dot = fileName.lastIndexOf('.')
        return dot > 0 && fileName.substring(dot + 1).lowercase() in AUDIO_EXTENSIONS
    }

    /**
     * The folder and file segments of [entryName] made safe to create below a destination, or `null` when the entry
     * could reach outside it: absolute paths (`/x`, `\\server`), drives (`C:`), parent references (`..`, and `... `
     * which Windows reads as the current folder), control characters, or more than [MAX_DEPTH] segments. Backslashes
     * count as separators, `.` and empty segments are dropped, FAT-illegal characters become `_`.
     */
    fun safeSegments(entryName: String): List<String>? {
        val path = entryName.replace('\\', '/')
        if (path.startsWith('/') || path.any { it < ' ' || it == '\u007F' || it == ':' }) return null
        val segments = path.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty() || segments.size > MAX_DEPTH) return null
        if (segments.any { it.trimEnd('.', ' ').isEmpty() }) return null
        return segments.map { BatchNaming.fatSafe(it) }
    }

    /** What extraction does with the entry called [entryName]. */
    fun classify(entryName: String): EntryVerdict {
        if (entryName.endsWith('/') || entryName.endsWith('\\')) return EntryVerdict.Skipped(SkipReason.DIRECTORY)
        val segments = safeSegments(entryName) ?: return EntryVerdict.Skipped(SkipReason.UNSAFE_PATH)
        val name = segments.last()
        return when {
            name.equals(ArchiveManifest.FILE_NAME, ignoreCase = true) && segments.size <= 2 -> EntryVerdict.Manifest(segments)
            isAudio(name) -> EntryVerdict.Audio(segments)
            else -> EntryVerdict.Skipped(SkipReason.NOT_AUDIO)
        }
    }
}
