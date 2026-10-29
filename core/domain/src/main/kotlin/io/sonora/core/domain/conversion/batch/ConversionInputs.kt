package io.sonora.core.domain.conversion.batch

/**
 * Which files of a picked folder the converter takes: FLAC, ALAC and AAC in M4A, raw AAC, MP3, WAV and AIFF.
 * Hidden files (dot files, including macOS `._` resource forks on USB sticks) and the converter's own part files
 * are never audio.
 */
object ConversionInputs {

    private val EXTENSIONS = setOf("flac", "m4a", "aac", "mp3", "wav", "aif", "aiff")

    private val MIME_TYPES = setOf(
        "audio/flac",
        "audio/x-flac",
        "audio/mp4",
        "audio/m4a",
        "audio/x-m4a",
        "audio/aac",
        "audio/mpeg",
        "audio/mp3",
        "audio/wav",
        "audio/wave",
        "audio/x-wav",
        "audio/vnd.wave",
        "audio/aiff",
        "audio/x-aiff",
    )

    /**
     * Well-known other files whose provider may still report a generic audio type; their extension wins. Any other
     * tail after a dot ("Vol.2", "Remix.Edit") may just be part of the name, so the MIME type decides.
     */
    private val OTHER_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "txt", "nfo", "log", "pdf", "cue", "m3u", "m3u8", "pls",
        "xml", "json", "db", "mp4", "m4v", "mkv", "avi", "mov", "webm", "ogg", "oga", "opus", "wma", "mid", "midi",
        BatchNaming.PARTIAL_SUFFIX.removePrefix("."),
    )

    /** By extension when it is a known one; otherwise (no extension, or a dot inside the name) by MIME type. */
    fun isSupported(fileName: String, mimeType: String?): Boolean {
        if (fileName.startsWith('.')) return false
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension in EXTENSIONS) return true
        if (extension in OTHER_EXTENSIONS) return false
        return mimeType?.lowercase() in MIME_TYPES
    }

    /** Files in a stable order: by path (folders, then name), ignoring case. */
    fun <T> sorted(files: List<T>, path: (T) -> String): List<T> =
        files.sortedWith(compareBy<T> { path(it).lowercase() }.thenBy(path))
}
