package io.sonora.core.data.library.saf

/** Which listed documents are audio, and which tag reader handles them. */
object AudioFiles {
    /** Audio extensions mapped to their JAudioTagger reader key; `null` = playable but no 3.0.1 reader. */
    private val EXTENSIONS = mapOf(
        "mp3" to "mp3",
        "flac" to "flac",
        "m4a" to "m4a",
        "alac" to "m4a",
        "aac" to null, // raw ADTS, not MP4
        "ogg" to "ogg",
        "oga" to "ogg",
        "opus" to null,
        "wav" to "wav",
        "aif" to "aif",
        "aiff" to "aiff",
        "aifc" to "aifc",
    )

    private val MIME_TYPES = mapOf(
        "audio/mpeg" to "mp3",
        "audio/mp3" to "mp3",
        "audio/flac" to "flac",
        "audio/x-flac" to "flac",
        "audio/mp4" to "m4a",
        "audio/m4a" to "m4a",
        "audio/x-m4a" to "m4a",
        "audio/aac" to null,
        "audio/ogg" to "ogg",
        "audio/vorbis" to "ogg",
        "audio/opus" to null,
        "audio/wav" to "wav",
        "audio/wave" to "wav",
        "audio/x-wav" to "wav",
        "audio/vnd.wave" to "wav",
        "audio/aiff" to "aiff",
        "audio/x-aiff" to "aiff",
    )

    /** True for supported audio by extension, or by MIME type when the name has no known extension. */
    fun isAudio(fileName: String, mimeType: String?): Boolean =
        extension(fileName) in EXTENSIONS || mimeType?.lowercase() in MIME_TYPES

    /** JAudioTagger reader key for the file (its extension or MIME type), or `null` when no reader applies. */
    fun readerExtension(fileName: String, mimeType: String?): String? {
        val extension = extension(fileName)
        return if (extension in EXTENSIONS) EXTENSIONS[extension] else MIME_TYPES[mimeType?.lowercase()]
    }

    private fun extension(fileName: String) = fileName.substringAfterLast('.', "").lowercase()
}

/** Picks the folder image used as album art when a file has no embedded picture. */
object FolderImages {
    private val BASE_NAMES = listOf("cover", "folder", "front", "album")
    private val EXTENSIONS = listOf("jpg", "jpeg", "png")

    /**
     * Best candidate among a directory's file names: base names cover > folder > front > album, then extensions
     * jpg > jpeg > png, case-insensitive; `null` when none match.
     */
    fun pick(fileNames: Collection<String>): String? = fileNames
        .mapNotNull { name -> rank(name)?.let { name to it } }
        .minByOrNull { it.second }
        ?.first

    private fun rank(fileName: String): Int? {
        if ('.' !in fileName) return null
        val base = BASE_NAMES.indexOf(fileName.substringBeforeLast('.').lowercase())
        val extension = EXTENSIONS.indexOf(fileName.substringAfterLast('.').lowercase())
        return if (base < 0 || extension < 0) null else base * EXTENSIONS.size + extension
    }
}

/** Display paths for SAF trees. */
object SafPaths {
    /**
     * Path part of a tree document id (`primary:Music/Rock` -> `Music/Rock`), or `null` for a volume root, where
     * the provider's display name should be used instead.
     */
    fun displayPath(treeDocumentId: String): String? =
        treeDocumentId.substringAfter(':', "").trim('/').takeIf { it.isNotEmpty() }
}
